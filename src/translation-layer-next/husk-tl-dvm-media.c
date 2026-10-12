/* SPDX-License-Identifier: GPL-2.0-or-later */
/*
 * Decoders for Java apps' android.media.MediaCodec (husk.MediaCodecNative's natives): H.264 and HEVC through VideoToolbox, AAC, MP3
 * and Opus (iOS 17's AudioConverter) through AudioToolbox -- the same frameworks on iOS and macOS.
 *
 * Video: MediaCodec takes Annex-B access units (start codes, parameter sets in band or as csd-0/csd-1); VideoToolbox takes
 * length-prefixed NAL units under a format description built from the parameter sets. Each queued access unit is converted and
 * decoded synchronously; decoded frames (32BGRA pixel buffers) come out in decode order, so they wait in a small buffer sorted by
 * presentation time and leave it in display order, as MediaCodec hands them out. The Java side draws a frame into a Bitmap
 * (renderFrame) and the Bitmap into the app's Surface.
 *
 * Audio: one compressed packet per queued buffer (as MediaCodec is fed), decoded to interleaved 16-bit PCM. Opus packets carry
 * their frame count in the TOC byte, which the converter is told per packet; the stream's pre-skip is dropped from the first output.
 *
 * The same decoders serve C callers (tl_media_*): MediaMetadataRetriever's thumbnails in husk-tl-dvm-extractor.c.
 */
#define _DARWIN_C_SOURCE
#include <Accelerate/Accelerate.h>
#include <AudioToolbox/AudioToolbox.h>
#include <CoreMedia/CoreMedia.h>
#include <CoreVideo/CoreVideo.h>
#include <VideoToolbox/VideoToolbox.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "husk-tl-dvm-internal.h"

void tl_log_line(const char *fmt, ...);
typedef struct { uint32_t width, height, stride; int32_t format; uint32_t flags; } tl_abitmap_info;
int tl_AndroidBitmap_getInfo(void *env, void *bitmap, tl_abitmap_info *info);
int tl_AndroidBitmap_lockPixels(void *env, void *bitmap, void **addr);

#define NAT(fn) static bool fn(jobj *self, const jvalue *a, jvalue *ret)
static jvalue I(int32_t v) { jvalue r; r.j = (uint32_t)v; return r; }
static jvalue J(int64_t v) { jvalue r; r.j = v; return r; }
static jvalue Z(bool v) { jvalue r; r.j = v; return r; }
static jvalue L(void *p) { jvalue r; r.j = 0; r.l = p; return r; }

enum { K_AVC = 1, K_HEVC, K_AAC, K_MP3, K_OPUS };
#define K_FMT_OPUS 0x6f707573                       /* kAudioFormatOpus ('opus'), named here so older SDKs build */
enum { FLAG_EOS = 4 };
#define REORDER 4                                   /* frames held to put decode order into display order */

typedef struct frame { CVPixelBufferRef pb; int64_t pts; struct frame *next; } frame;
typedef struct pcm { uint8_t *data; int len; int64_t pts; struct pcm *next; } pcm;
typedef struct { uint8_t *d; size_t n; } blob;

typedef struct codec {
    int kind;
    pthread_mutex_t lock;
    bool trace;
    /* video */
    blob vps, sps, pps;
    bool params_changed;
    CMVideoFormatDescriptionRef fmt;
    VTDecompressionSessionRef vt;
    frame *sorted; int nsorted;                     /* decoded, by presentation time */
    frame *ready, **ready_tail;                     /* in display order, for the Java side */
    int width, height;
    long decoded, failed;
    /* audio */
    AudioConverterRef conv;
    AudioStreamBasicDescription in, out;
    const uint8_t *packet; UInt32 packet_len; AudioStreamPacketDescription desc;
    pcm *pcms, **pcm_tail;
    int rate, channels;
    int skip;                                       /* Opus: pre-skip frames still to drop */
} codec;

static bool trace_on(void) { static int t = -1; if (t < 0) t = getenv("TL_MEDIA_TRACE") != NULL; return t; }

/* ---- video ---- */

static void blob_set(blob *b, const uint8_t *d, size_t n, bool *changed)
{
    if (b->n == n && b->d && !memcmp(b->d, d, n)) return;
    free(b->d); b->d = malloc(n); memcpy(b->d, d, n); b->n = n;
    *changed = true;
}

static void ready_push(codec *c, frame *f) { f->next = NULL; *c->ready_tail = f; c->ready_tail = &f->next; }
/* the earliest sorted frame moves to the ready queue */
static void release_sorted(codec *c, int keep)
{
    while (c->nsorted > keep) {
        frame *f = c->sorted; c->sorted = f->next; c->nsorted--;
        ready_push(c, f);
    }
}

static void vt_out(void *ref, void *src, OSStatus st, VTDecodeInfoFlags fl, CVImageBufferRef img, CMTime pts, CMTime dur)
{
    (void)src; (void)fl; (void)dur;
    codec *c = ref;
    if (st != noErr || !img) { c->failed++; if (c->trace || c->failed < 4) tl_log_line("media: frame not decoded (%d)", (int)st); return; }
    frame *f = calloc(1, sizeof(*f));
    f->pb = CVPixelBufferRetain(img);
    f->pts = CMTIME_IS_VALID(pts) ? CMTimeConvertScale(pts, 1000000, kCMTimeRoundingMethod_Default).value : 0;
    pthread_mutex_lock(&c->lock);
    frame **p = &c->sorted;
    while (*p && (*p)->pts <= f->pts) p = &(*p)->next;
    f->next = *p; *p = f; c->nsorted++;
    release_sorted(c, REORDER);
    c->decoded++;
    pthread_mutex_unlock(&c->lock);
}

static void session_drop(codec *c)
{
    if (c->vt) { VTDecompressionSessionWaitForAsynchronousFrames(c->vt); VTDecompressionSessionInvalidate(c->vt); CFRelease(c->vt); c->vt = NULL; }
}

static bool session_make(codec *c)
{
    if (c->fmt) { CFRelease(c->fmt); c->fmt = NULL; }
    OSStatus st;
    if (c->kind == K_AVC) {
        if (!c->sps.n || !c->pps.n) return false;
        const uint8_t *ps[2] = { c->sps.d, c->pps.d }; size_t sz[2] = { c->sps.n, c->pps.n };
        st = CMVideoFormatDescriptionCreateFromH264ParameterSets(NULL, 2, ps, sz, 4, &c->fmt);
    } else {
        if (!c->vps.n || !c->sps.n || !c->pps.n) return false;
        const uint8_t *ps[3] = { c->vps.d, c->sps.d, c->pps.d }; size_t sz[3] = { c->vps.n, c->sps.n, c->pps.n };
        st = CMVideoFormatDescriptionCreateFromHEVCParameterSets(NULL, 3, ps, sz, 4, NULL, &c->fmt);
    }
    if (st != noErr) { tl_log_line("media: the stream's parameter sets were refused (%d)", (int)st); c->fmt = NULL; return false; }
    CGSize dims = CMVideoFormatDescriptionGetPresentationDimensions(c->fmt, true, false);
    c->width = (int)dims.width; c->height = (int)dims.height;
    if (c->vt && VTDecompressionSessionCanAcceptFormatDescription(c->vt, c->fmt)) return true;
    session_drop(c);
    int fmt = kCVPixelFormatType_32BGRA;
    CFNumberRef n = CFNumberCreate(NULL, kCFNumberIntType, &fmt);
    const void *k[] = { kCVPixelBufferPixelFormatTypeKey }, *v[] = { n };
    CFDictionaryRef dest = CFDictionaryCreate(NULL, k, v, 1, &kCFTypeDictionaryKeyCallBacks, &kCFTypeDictionaryValueCallBacks);
    VTDecompressionOutputCallbackRecord cb = { vt_out, c };
    st = VTDecompressionSessionCreate(NULL, c->fmt, NULL, dest, &cb, &c->vt);
    CFRelease(dest); CFRelease(n);
    if (st != noErr) { tl_log_line("media: no decoder session (%d)", (int)st); c->vt = NULL; return false; }
    tl_log_line("media: %s decoder for %dx%d", c->kind == K_AVC ? "H.264" : "HEVC", c->width, c->height);
    return true;
}

static int nal_type(const codec *c, const uint8_t *n) { return c->kind == K_AVC ? (n[0] & 0x1f) : ((n[0] >> 1) & 0x3f); }

/* Annex-B (start codes) or already length-prefixed units: each NAL unit to fn */
static void each_nal(const uint8_t *d, size_t n, void (*fn)(void *, const uint8_t *, size_t), void *arg)
{
    bool annexb = n >= 3 && d[0] == 0 && d[1] == 0 && (d[2] == 1 || (n >= 4 && d[2] == 0 && d[3] == 1));
    if (!annexb) {
        size_t i = 0;
        while (i + 4 <= n) {
            uint32_t len = (uint32_t)d[i] << 24 | (uint32_t)d[i + 1] << 16 | (uint32_t)d[i + 2] << 8 | d[i + 3];
            i += 4;
            if (len == 0 || len > n - i) break;
            fn(arg, d + i, len); i += len;
        }
        return;
    }
    size_t i = 0, start = (size_t)-1;
    while (i + 3 <= n) {
        if (d[i] == 0 && d[i + 1] == 0 && d[i + 2] == 1) {
            if (start != (size_t)-1) {
                size_t end = i; while (end > start && d[end - 1] == 0) end--;     /* the zero before a 4-byte start code */
                if (end > start) fn(arg, d + start, end - start);
            }
            i += 3; start = i;
        } else i++;
    }
    if (start != (size_t)-1 && start < n) fn(arg, d + start, n - start);
}

typedef struct { codec *c; uint8_t *out; size_t len, cap; } nal_walk;
static void take_nal(void *arg, const uint8_t *nal, size_t n)
{
    nal_walk *w = arg; codec *c = w->c;
    if (n == 0) return;
    int t = nal_type(c, nal);
    if (c->kind == K_AVC) {
        if (t == 7) { blob_set(&c->sps, nal, n, &c->params_changed); return; }
        if (t == 8) { blob_set(&c->pps, nal, n, &c->params_changed); return; }
        if (t == 9) return;                                         /* access unit delimiter */
    } else {
        if (t == 32) { blob_set(&c->vps, nal, n, &c->params_changed); return; }
        if (t == 33) { blob_set(&c->sps, nal, n, &c->params_changed); return; }
        if (t == 34) { blob_set(&c->pps, nal, n, &c->params_changed); return; }
        if (t == 35) return;
    }
    if (w->len + n + 4 > w->cap) { w->cap = (w->len + n + 4) * 2; w->out = realloc(w->out, w->cap); }
    w->out[w->len++] = (uint8_t)(n >> 24); w->out[w->len++] = (uint8_t)(n >> 16); w->out[w->len++] = (uint8_t)(n >> 8); w->out[w->len++] = (uint8_t)n;
    memcpy(w->out + w->len, nal, n); w->len += n;
}

static void video_drain(codec *c)
{
    if (c->vt) { VTDecompressionSessionFinishDelayedFrames(c->vt); VTDecompressionSessionWaitForAsynchronousFrames(c->vt); }
    pthread_mutex_lock(&c->lock); release_sorted(c, 0); pthread_mutex_unlock(&c->lock);
}

static void video_decode(codec *c, const uint8_t *d, size_t n, int64_t pts, int flags)
{
    nal_walk w = { c, NULL, 0, 0 };
    each_nal(d, n, take_nal, &w);
    if (c->params_changed) {
        c->params_changed = false;
        if ((c->vt || c->fmt) && c->vt) video_drain(c);
        session_make(c);
    }
    if (w.len && c->vt) {
        CMBlockBufferRef bb = NULL; CMSampleBufferRef sb = NULL;
        OSStatus st = CMBlockBufferCreateWithMemoryBlock(NULL, NULL, w.len, NULL, NULL, 0, w.len, kCMBlockBufferAssureMemoryNowFlag, &bb);
        if (st == noErr) st = CMBlockBufferReplaceDataBytes(w.out, bb, 0, w.len);
        CMSampleTimingInfo tm = { kCMTimeInvalid, CMTimeMake(pts, 1000000), kCMTimeInvalid };
        size_t ss = w.len;
        if (st == noErr) st = CMSampleBufferCreateReady(NULL, bb, c->fmt, 1, 1, &tm, 1, &ss, &sb);
        if (st == noErr) {
            VTDecodeInfoFlags info = 0;
            st = VTDecompressionSessionDecodeFrame(c->vt, sb, 0, NULL, &info);
            if (st != noErr) { c->failed++; if (c->failed < 4 || c->trace) tl_log_line("media: decode refused (%d)", (int)st); }
            if (st == kVTInvalidSessionErr) { session_drop(c); session_make(c); }
        }
        if (sb) CFRelease(sb);
        if (bb) CFRelease(bb);
    }
    free(w.out);
    if (flags & FLAG_EOS) video_drain(c);
}

/* ---- audio ---- */

/* AudioToolbox wants AAC's AudioSpecificConfig inside an ES descriptor (the 'esds' magic cookie) */
static size_t esds_from_asc(const uint8_t *asc, size_t n, uint8_t *out)
{
    size_t i = 0;
    out[i++] = 0x03; out[i++] = (uint8_t)(23 + n); out[i++] = 0; out[i++] = 0; out[i++] = 0;     /* ES_Descriptor, ES_ID, flags */
    out[i++] = 0x04; out[i++] = (uint8_t)(15 + n); out[i++] = 0x40; out[i++] = 0x15;             /* DecoderConfigDescriptor: AAC, audio stream */
    out[i++] = 0; out[i++] = 0; out[i++] = 0;                                                     /* buffer size */
    for (int k = 0; k < 8; k++) out[i++] = 0;                                                     /* max and average bitrate */
    out[i++] = 0x05; out[i++] = (uint8_t)n; memcpy(out + i, asc, n); i += n;                      /* DecoderSpecificInfo */
    out[i++] = 0x06; out[i++] = 1; out[i++] = 2;                                                  /* SLConfigDescriptor */
    return i;
}

static int opus_frames(const uint8_t *p, size_t n)                /* a packet's frames at 48 kHz, from its TOC byte */
{
    if (!n) return 0;
    int cfg = p[0] >> 3, code = p[0] & 3;
    int size = cfg < 12 ? (const int[]){ 480, 960, 1920, 2880 }[cfg & 3] : cfg < 16 ? (const int[]){ 480, 960 }[cfg & 1] : (const int[]){ 120, 240, 480, 960 }[cfg & 3];
    return size * (code == 0 ? 1 : code < 3 ? 2 : n > 1 ? (p[1] & 0x3f) : 0);
}

static bool audio_open(codec *c, const uint8_t *asc, size_t asc_n, int rate, int channels)
{
    memset(&c->in, 0, sizeof(c->in));
    uint8_t esds[128]; size_t esds_n = 0;
    const uint8_t *cookie = NULL; size_t cookie_n = 0;
    if (c->kind == K_OPUS) {
        /* csd-0 is the OpusHead: channels at 9, pre-skip at 10 (little-endian) */
        bool head = asc && asc_n >= 19 && !memcmp(asc, "OpusHead", 8);
        c->in.mFormatID = K_FMT_OPUS; c->in.mSampleRate = 48000; c->in.mFramesPerPacket = 960;
        c->in.mChannelsPerFrame = head ? asc[9] : channels > 0 ? (UInt32)channels : 2;
        c->skip = head ? asc[10] | asc[11] << 8 : 0;
        if (head) { cookie = asc; cookie_n = asc_n; }
    } else if (c->kind == K_AAC) {
        if (asc && asc_n && asc_n < 64) {
            esds_n = esds_from_asc(asc, asc_n, esds);
            UInt32 sz = sizeof(c->in);
            if (AudioFormatGetProperty(kAudioFormatProperty_ASBDFromESDS, (UInt32)esds_n, esds, &sz, &c->in) != noErr) memset(&c->in, 0, sizeof(c->in));
        }
        if (!c->in.mFormatID) {
            c->in.mFormatID = kAudioFormatMPEG4AAC; c->in.mSampleRate = rate > 0 ? rate : 44100;
            c->in.mChannelsPerFrame = channels > 0 ? (UInt32)channels : 2; c->in.mFramesPerPacket = 1024;
        }
    } else {
        c->in.mFormatID = kAudioFormatMPEGLayer3; c->in.mSampleRate = rate > 0 ? rate : 44100;
        c->in.mChannelsPerFrame = channels > 0 ? (UInt32)channels : 2; c->in.mFramesPerPacket = 1152;
    }
    c->rate = (int)c->in.mSampleRate; c->channels = (int)c->in.mChannelsPerFrame;
    c->out = (AudioStreamBasicDescription){ .mSampleRate = c->in.mSampleRate, .mFormatID = kAudioFormatLinearPCM,
        .mFormatFlags = kLinearPCMFormatFlagIsSignedInteger | kLinearPCMFormatFlagIsPacked, .mBytesPerPacket = 2 * c->in.mChannelsPerFrame,
        .mFramesPerPacket = 1, .mBytesPerFrame = 2 * c->in.mChannelsPerFrame, .mChannelsPerFrame = c->in.mChannelsPerFrame, .mBitsPerChannel = 16 };
    if (c->conv) { AudioConverterDispose(c->conv); c->conv = NULL; }
    const char *name = c->kind == K_AAC ? "AAC" : c->kind == K_MP3 ? "MP3" : "Opus";
    OSStatus st = AudioConverterNew(&c->in, &c->out, &c->conv);
    if (st != noErr && c->kind == K_OPUS) { c->in.mFramesPerPacket = 0; st = AudioConverterNew(&c->in, &c->out, &c->conv); }   /* variable frames per packet */
    if (st != noErr) { tl_log_line("media: no %s decoder (%d)", name, (int)st); c->conv = NULL; return false; }
    if (esds_n) AudioConverterSetProperty(c->conv, kAudioConverterDecompressionMagicCookie, (UInt32)esds_n, esds);
    if (cookie_n) AudioConverterSetProperty(c->conv, kAudioConverterDecompressionMagicCookie, (UInt32)cookie_n, cookie);
    tl_log_line("media: %s decoder, %d Hz, %d channels", name, c->rate, c->channels);
    return true;
}

static OSStatus audio_feed(AudioConverterRef conv, UInt32 *npk, AudioBufferList *bl, AudioStreamPacketDescription **desc, void *arg)
{
    (void)conv;
    codec *c = arg;
    if (!c->packet) { *npk = 0; return 1; }                         /* no more input for this call: AudioConverter returns what it has */
    bl->mNumberBuffers = 1;
    bl->mBuffers[0].mData = (void *)c->packet; bl->mBuffers[0].mDataByteSize = c->packet_len; bl->mBuffers[0].mNumberChannels = c->in.mChannelsPerFrame;
    c->desc = (AudioStreamPacketDescription){ 0, c->kind == K_OPUS && !c->in.mFramesPerPacket ? (UInt32)opus_frames(c->packet, c->packet_len) : 0, c->packet_len };
    if (desc) *desc = &c->desc;
    *npk = 1; c->packet = NULL;
    return noErr;
}

static void audio_decode(codec *c, const uint8_t *d, size_t n, int64_t pts)
{
    if (!c->conv && !audio_open(c, NULL, 0, 0, 0)) return;
    if (!n) return;
    c->packet = d; c->packet_len = (UInt32)n;
    UInt32 frames = 4096, bytes = frames * c->out.mBytesPerFrame;
    uint8_t *buf = malloc(bytes);
    AudioBufferList bl = { 1, { { c->out.mChannelsPerFrame, bytes, buf } } };
    OSStatus st = AudioConverterFillComplexBuffer(c->conv, audio_feed, c, &frames, &bl, NULL);
    c->packet = NULL;
    if ((st != noErr && st != 1) || frames == 0) { free(buf); if (st != noErr && st != 1) { c->failed++; if (c->failed < 4) tl_log_line("media: audio packet not decoded (%d)", (int)st); } return; }
    if (c->skip > 0) {                                              /* Opus pre-skip: the first decoded frames are the encoder's warm-up */
        UInt32 drop = (UInt32)c->skip < frames ? (UInt32)c->skip : frames;
        memmove(buf, buf + drop * c->out.mBytesPerFrame, (frames - drop) * c->out.mBytesPerFrame); frames -= drop; c->skip -= (int)drop;
        if (!frames) { free(buf); return; }
    }
    pcm *p = calloc(1, sizeof(*p));
    p->data = buf; p->len = (int)(frames * c->out.mBytesPerFrame); p->pts = pts;
    pthread_mutex_lock(&c->lock); *c->pcm_tail = p; c->pcm_tail = &p->next; c->decoded++; pthread_mutex_unlock(&c->lock);
}

/* ---- natives ---- */

static int kind_of(const char *mime)
{
    if (!mime) return 0;
    if (!strcasecmp(mime, "video/avc")) return K_AVC;
    if (!strcasecmp(mime, "video/hevc")) return K_HEVC;
    if (!strcasecmp(mime, "audio/mp4a-latm")) return K_AAC;
    if (!strcasecmp(mime, "audio/mpeg")) return K_MP3;
    if (!strcasecmp(mime, "audio/opus")) return K_OPUS;
    return 0;
}
static bool opus_available(void) { if (__builtin_available(macOS 14.0, iOS 17.0, *)) return true; return false; }
static codec *codec_new(int kind)
{
    codec *c = calloc(1, sizeof(*c));
    c->kind = kind; c->trace = trace_on();
    pthread_mutex_init(&c->lock, NULL);
    c->ready_tail = &c->ready; c->pcm_tail = &c->pcms;
    return c;
}
static void codec_video_csd(codec *c, const uint8_t *const *csd, const size_t *csd_n, int ncsd)
{
    for (int i = 0; i < ncsd; i++) if (csd[i] && csd_n[i]) { nal_walk w = { c, NULL, 0, 0 }; each_nal(csd[i], csd_n[i], take_nal, &w); free(w.out); }
    if (c->params_changed) { c->params_changed = false; session_make(c); }
}
static frame *codec_take_frame(codec *c)
{
    pthread_mutex_lock(&c->lock);
    frame *f = c->ready;
    if (f) { c->ready = f->next; if (!c->ready) c->ready_tail = &c->ready; }
    pthread_mutex_unlock(&c->lock);
    return f;
}
static void drop_outputs(codec *c);
static void codec_free(codec *c)
{
    if (!c) return;
    session_drop(c);
    drop_outputs(c);
    if (c->fmt) CFRelease(c->fmt);
    if (c->conv) AudioConverterDispose(c->conv);
    free(c->vps.d); free(c->sps.d); free(c->pps.d);
    if (c->trace) tl_log_line("media: codec closed after %ld outputs, %ld failures", c->decoded, c->failed);
    pthread_mutex_destroy(&c->lock);
    free(c);
}
static void frame_dims(const frame *f, int *w, int *h)
{
    CGRect r = CVImageBufferGetCleanRect(f->pb);
    *w = (int)r.size.width; *h = (int)r.size.height;
    if (*w <= 0 || *h <= 0) { *w = (int)CVPixelBufferGetWidth(f->pb); *h = (int)CVPixelBufferGetHeight(f->pb); }
}
#define C ((codec *)(uintptr_t)a[0].j)

NAT(M_supported)
{
    (void)self;
    int k = kind_of(tl_jni_string(a[0].l));
    bool ok = k == K_AVC || k == K_AAC || k == K_MP3 || (k == K_OPUS && opus_available());
    if (k == K_HEVC) {
        if (__builtin_available(macOS 10.13, iOS 11.0, *)) ok = VTIsHardwareDecodeSupported(kCMVideoCodecType_HEVC);
    }
    *ret = Z(ok);
    return true;
}
NAT(M_create)
{
    (void)self;
    int k = kind_of(tl_jni_string(a[0].l));
    if (!k || (k == K_OPUS && !opus_available())) { *ret = J(0); return true; }
    *ret = J((int64_t)(uintptr_t)codec_new(k));
    return true;
}
/* configure(h, width, height, rate, channels, csd0, csd1, csd2): codec-specific data as the format carries it */
NAT(M_configure)
{
    (void)self;
    codec *c = C;
    jobj *csd[3] = { a[5].l, a[6].l, a[7].l };
    if (c->kind == K_AVC || c->kind == K_HEVC) {
        c->width = a[1].i; c->height = a[2].i;
        const uint8_t *d[3]; size_t n[3];
        for (int i = 0; i < 3; i++) { d[i] = csd[i] ? csd[i]->arr.data : NULL; n[i] = csd[i] ? csd[i]->arr.len : 0; }
        codec_video_csd(c, d, n, 3);
        *ret = Z(true);
    } else {
        *ret = Z(audio_open(c, csd[0] ? csd[0]->arr.data : NULL, csd[0] ? csd[0]->arr.len : 0, a[3].i, a[4].i));
    }
    return true;
}
/* decode(h, data, len, ptsUs, flags): one access unit or packet; csd (BUFFER_FLAG_CODEC_CONFIG) goes through here too */
NAT(M_decode)
{
    (void)self;
    codec *c = C;
    jobj *b = a[1].l;
    size_t n = b ? (size_t)a[2].i : 0;
    if (b && n > b->arr.len) n = b->arr.len;
    const uint8_t *d = b ? b->arr.data : NULL;
    if (c->kind == K_AVC || c->kind == K_HEVC) video_decode(c, d, n, a[3].j, a[4].i);
    else if (a[4].i & 2) audio_open(c, d, n, c->rate, c->channels);           /* codec config: an AudioSpecificConfig */
    else audio_decode(c, d, n, a[3].j);
    *ret = I(0);
    return true;
}
/* nextFrame(h, info) -> a frame handle (0: none yet); info = { ptsUs, width, height } */
NAT(M_nextFrame)
{
    (void)self;
    frame *f = codec_take_frame(C);
    if (!f) { *ret = J(0); return true; }
    int64_t *info = a[1].l ? (int64_t *)((jobj *)a[1].l)->arr.data : NULL;
    if (info) { int w, h; frame_dims(f, &w, &h); info[0] = f->pts; info[1] = w; info[2] = h; }
    *ret = J((int64_t)(uintptr_t)f);
    return true;
}
/* nextPcm(h, info) -> 16-bit PCM bytes (null: none yet); info = { ptsUs, rate, channels } */
NAT(M_nextPcm)
{
    (void)self;
    codec *c = C;
    pthread_mutex_lock(&c->lock);
    pcm *p = c->pcms;
    if (p) { c->pcms = p->next; if (!c->pcms) c->pcm_tail = &c->pcms; }
    pthread_mutex_unlock(&c->lock);
    if (!p) { *ret = L(NULL); return true; }
    int64_t *info = a[1].l ? (int64_t *)((jobj *)a[1].l)->arr.data : NULL;
    if (info) { info[0] = p->pts; info[1] = c->rate; info[2] = c->channels; }
    jobj *arr = tl_jni_new_prim_array('B', (uint32_t)p->len);
    arr->refs = 1u << 30;
    memcpy(arr->arr.data, p->data, (size_t)p->len);
    free(p->data); free(p);
    *ret = L(arr);
    return true;
}
/* renderFrame(frame, bitmap): the frame's picture into a Bitmap of its size (RGBA) */
NAT(M_renderFrame)
{
    (void)self;
    frame *f = (frame *)(uintptr_t)a[0].j;
    tl_abitmap_info bi; void *px = NULL;
    if (!f || !a[1].l || tl_AndroidBitmap_getInfo(NULL, a[1].l, &bi) || tl_AndroidBitmap_lockPixels(NULL, a[1].l, &px) || !px) { *ret = Z(false); return true; }
    CVPixelBufferLockBaseAddress(f->pb, kCVPixelBufferLock_ReadOnly);
    uint8_t *base = CVPixelBufferGetBaseAddress(f->pb);
    size_t stride = CVPixelBufferGetBytesPerRow(f->pb);
    CGRect r = CVImageBufferGetCleanRect(f->pb);
    size_t x0 = (size_t)r.origin.x, y0 = (size_t)r.origin.y;
    size_t w = bi.width, h = bi.height;
    if (w > CVPixelBufferGetWidth(f->pb) - x0) w = CVPixelBufferGetWidth(f->pb) - x0;
    if (h > CVPixelBufferGetHeight(f->pb) - y0) h = CVPixelBufferGetHeight(f->pb) - y0;
    bool ok = false;
    if (base) {
        vImage_Buffer s = { base + y0 * stride + x0 * 4, h, w, stride }, d = { px, h, w, bi.stride };
        const uint8_t map[4] = { 2, 1, 0, 3 };                     /* BGRA -> RGBA */
        ok = vImagePermuteChannels_ARGB8888(&s, &d, map, kvImageNoFlags) == kvImageNoError;
    }
    CVPixelBufferUnlockBaseAddress(f->pb, kCVPixelBufferLock_ReadOnly);
    *ret = Z(ok);
    return true;
}
NAT(M_freeFrame) { (void)self; (void)ret; frame *f = (frame *)(uintptr_t)a[0].j; if (f) { CVPixelBufferRelease(f->pb); free(f); } return true; }
static void drop_outputs(codec *c)
{
    pthread_mutex_lock(&c->lock);
    for (frame *f = c->sorted, *n; f; f = n) { n = f->next; CVPixelBufferRelease(f->pb); free(f); }
    for (frame *f = c->ready, *n; f; f = n) { n = f->next; CVPixelBufferRelease(f->pb); free(f); }
    c->sorted = NULL; c->nsorted = 0; c->ready = NULL; c->ready_tail = &c->ready;
    for (pcm *p = c->pcms, *n; p; p = n) { n = p->next; free(p->data); free(p); }
    c->pcms = NULL; c->pcm_tail = &c->pcms;
    pthread_mutex_unlock(&c->lock);
}
NAT(M_flush)
{
    (void)self; (void)ret;
    codec *c = C;
    if (c->vt) { VTDecompressionSessionFinishDelayedFrames(c->vt); VTDecompressionSessionWaitForAsynchronousFrames(c->vt); }
    if (c->conv) AudioConverterReset(c->conv);
    drop_outputs(c);
    return true;
}
NAT(M_destroy) { (void)self; (void)ret; codec_free(C); return true; }
NAT(M_size) { (void)self; codec *c = C; *ret = I(c->width << 16 | (c->height & 0xffff)); return true; }

static const struct { const char *name, *sig; dvm_native_fn fn; } k_media[] = {
    { "supported", "(Ljava/lang/String;)Z", M_supported },
    { "create", "(Ljava/lang/String;)J", M_create },
    { "configure", "(JIIII[B[B[B)Z", M_configure },
    { "decode", "(J[BIJI)I", M_decode },
    { "nextFrame", "(J[J)J", M_nextFrame },
    { "nextPcm", "(J[J)[B", M_nextPcm },
    { "renderFrame", "(JLandroid/graphics/Bitmap;)Z", M_renderFrame },
    { "freeFrame", "(J)V", M_freeFrame },
    { "flush", "(J)V", M_flush },
    { "destroy", "(J)V", M_destroy },
    { "size", "(J)I", M_size },
    { NULL, NULL, NULL },
};
dvm_native_fn tl_media_native(const char *name, const char *sig);
dvm_native_fn tl_media_native(const char *name, const char *sig)
{
    for (int i = 0; k_media[i].name; i++) if (!strcmp(k_media[i].name, name) && !strcmp(k_media[i].sig, sig)) return k_media[i].fn;
    return NULL;
}

/* ---- the decoders from C (husk-tl-dvm-extractor.c: MediaMetadataRetriever's frames) ---- */

void *tl_media_open(const char *mime, const uint8_t *const *csd, const size_t *csd_n, int ncsd);
void tl_media_feed(void *h, const uint8_t *d, size_t n, int64_t pts, bool eos);
void *tl_media_take_frame(void *h, int64_t *pts);
bool tl_media_frame_dims(void *f, int *w, int *h);
bool tl_media_frame_draw(void *f, int rotation, uint8_t *rgba, int w, int h, size_t stride);
void tl_media_frame_free(void *f);
void tl_media_close(void *h);

void *tl_media_open(const char *mime, const uint8_t *const *csd, const size_t *csd_n, int ncsd)
{
    int k = kind_of(mime);
    if (k != K_AVC && k != K_HEVC) return NULL;
    codec *c = codec_new(k);
    codec_video_csd(c, csd, csd_n, ncsd);
    if (!c->vt) { codec_free(c); return NULL; }
    return c;
}
void tl_media_feed(void *h, const uint8_t *d, size_t n, int64_t pts, bool eos) { video_decode(h, d, n, pts, eos ? FLAG_EOS : 0); }
void *tl_media_take_frame(void *h, int64_t *pts) { frame *f = codec_take_frame(h); if (f && pts) *pts = f->pts; return f; }
bool tl_media_frame_dims(void *fp, int *w, int *h) { if (!fp) return false; frame_dims(fp, w, h); return true; }
void tl_media_frame_free(void *fp) { frame *f = fp; if (f) { CVPixelBufferRelease(f->pb); free(f); } }
void tl_media_close(void *h) { codec_free(h); }
/* the frame's picture scaled to w x h after a rotation of 0/90/180/270 degrees clockwise (the track's rotation, so it comes out
 * upright), as RGBA rows at dst */
bool tl_media_frame_draw(void *fp, int rotation, uint8_t *dst, int dw, int dh, size_t dstride)
{
    frame *f = fp;
    if (!f || dw <= 0 || dh <= 0) return false;
    bool swap = rotation == 90 || rotation == 270;
    int sw = swap ? dh : dw, sh = swap ? dw : dh;                   /* the picture before it is turned */
    if (CVPixelBufferLockBaseAddress(f->pb, kCVPixelBufferLock_ReadOnly) != kCVReturnSuccess) return false;
    uint8_t *base = CVPixelBufferGetBaseAddress(f->pb);
    size_t stride = CVPixelBufferGetBytesPerRow(f->pb), pw = CVPixelBufferGetWidth(f->pb), ph = CVPixelBufferGetHeight(f->pb);
    CGRect r = CVImageBufferGetCleanRect(f->pb);
    size_t x0 = r.origin.x > 0 ? (size_t)r.origin.x : 0, y0 = r.origin.y > 0 ? (size_t)r.origin.y : 0;
    size_t w = r.size.width > 0 ? (size_t)r.size.width : pw, h = r.size.height > 0 ? (size_t)r.size.height : ph;
    if (x0 >= pw || y0 >= ph) x0 = y0 = 0;
    if (w > pw - x0) w = pw - x0;
    if (h > ph - y0) h = ph - y0;
    bool ok = false;
    if (base && w && h) {
        vImage_Buffer src = { base + y0 * stride + x0 * 4, h, w, stride }, pic = src;
        uint8_t *tmp = NULL;
        if ((int)w != sw || (int)h != sh) {
            tmp = malloc((size_t)sw * (size_t)sh * 4);
            pic = (vImage_Buffer){ tmp, (vImagePixelCount)sh, (vImagePixelCount)sw, (size_t)sw * 4 };
            if (vImageScale_ARGB8888(&src, &pic, NULL, kvImageNoFlags) != kvImageNoError) { free(tmp); tmp = NULL; pic = src; sw = (int)w; sh = (int)h; }
        }
        const uint8_t map[4] = { 2, 1, 0, 3 };                     /* BGRA -> RGBA */
        if (rotation == 0 && sw == dw && sh == dh) {
            vImage_Buffer d = { dst, (vImagePixelCount)dh, (vImagePixelCount)dw, dstride };
            ok = vImagePermuteChannels_ARGB8888(&pic, &d, map, kvImageNoFlags) == kvImageNoError;
        } else {
            for (int y = 0; y < sh; y++) {
                const uint8_t *row = (const uint8_t *)pic.data + (size_t)y * pic.rowBytes;
                for (int x = 0; x < sw; x++) {
                    int dx = rotation == 90 ? sh - 1 - y : rotation == 180 ? sw - 1 - x : rotation == 270 ? y : x;
                    int dy = rotation == 90 ? x : rotation == 180 ? sh - 1 - y : rotation == 270 ? sw - 1 - x : y;
                    if (dx < 0 || dy < 0 || dx >= dw || dy >= dh) continue;
                    uint8_t *o = dst + (size_t)dy * dstride + (size_t)dx * 4;
                    o[0] = row[x * 4 + 2]; o[1] = row[x * 4 + 1]; o[2] = row[x * 4]; o[3] = row[x * 4 + 3];
                }
            }
            ok = true;
        }
        free(tmp);
    }
    CVPixelBufferUnlockBaseAddress(f->pb, kCVPixelBufferLock_ReadOnly);
    return ok;
}
