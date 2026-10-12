/* SPDX-License-Identifier: GPL-2.0-or-later */
/*
 * Codecs for Java apps' android.media.MediaCodec (husk.MediaCodecNative's natives): H.264 and HEVC decoding and H.264 encoding
 * through VideoToolbox, AAC and MP3 decoding and AAC-LC encoding through AudioToolbox -- the same frameworks on iOS and macOS.
 *
 * Video decoding: MediaCodec takes Annex-B access units (start codes, parameter sets in band or as csd-0/csd-1); VideoToolbox takes
 * length-prefixed NAL units under a format description built from the parameter sets. Each queued access unit is converted and
 * decoded synchronously; decoded frames (32BGRA pixel buffers) come out in decode order, so they wait in a small buffer sorted by
 * presentation time and leave it in display order, as MediaCodec hands them out. The Java side draws a frame into a Bitmap
 * (renderFrame) and the Bitmap into the app's Surface.
 *
 * Audio decoding: one compressed packet per queued buffer (as MediaCodec is fed), decoded to interleaved 16-bit PCM.
 *
 * Encoding (the `enc` half): a VTCompressionSession takes the app's frames as pixel buffers -- NV12 made from the YUV420 planes it
 * queues, or BGRA from the pixels read back from its encoder input Surface (an EGL pbuffer; the Java side reads them at
 * eglSwapBuffers) -- with frame reordering off, so what comes out of the callback is one Annex-B access unit per frame in order.
 * The parameter sets are handed out first as a CODEC_CONFIG packet (MediaMuxer wants csd-0/csd-1 before start). AAC goes through an
 * AudioConverter from 16-bit PCM: the input callback hands it what the app queued, and each packet it fills is one raw AAC frame
 * (no ADTS), timed from the first input's presentation time by the frames encoded. Packets wait in a queue the Java side polls with
 * a timeout (nextPacket), since VideoToolbox delivers them on its own thread.
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
#include "husk-tl-dvm-gl.h"

void tl_log_line(const char *fmt, ...);
typedef struct { uint32_t width, height, stride; int32_t format; uint32_t flags; } tl_abitmap_info;
int tl_AndroidBitmap_getInfo(void *env, void *bitmap, tl_abitmap_info *info);
int tl_AndroidBitmap_lockPixels(void *env, void *bitmap, void **addr);

#define NAT(fn) static bool fn(jobj *self, const jvalue *a, jvalue *ret)
static jvalue I(int32_t v) { jvalue r; r.j = (uint32_t)v; return r; }
static jvalue J(int64_t v) { jvalue r; r.j = v; return r; }
static jvalue Z(bool v) { jvalue r; r.j = v; return r; }
static jvalue L(void *p) { jvalue r; r.j = 0; r.l = p; return r; }

enum { K_AVC = 1, K_HEVC, K_AAC, K_MP3, K_AVC_ENC, K_AAC_ENC };
enum { FLAG_SYNC = 1, FLAG_CONFIG = 2, FLAG_EOS = 4 };
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

static bool audio_open(codec *c, const uint8_t *asc, size_t asc_n, int rate, int channels)
{
    memset(&c->in, 0, sizeof(c->in));
    uint8_t esds[128]; size_t esds_n = 0;
    if (c->kind == K_AAC) {
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
    OSStatus st = AudioConverterNew(&c->in, &c->out, &c->conv);
    if (st != noErr) { tl_log_line("media: no %s decoder (%d)", c->kind == K_AAC ? "AAC" : "MP3", (int)st); c->conv = NULL; return false; }
    if (esds_n) AudioConverterSetProperty(c->conv, kAudioConverterDecompressionMagicCookie, (UInt32)esds_n, esds);
    tl_log_line("media: %s decoder, %d Hz, %d channels", c->kind == K_AAC ? "AAC" : "MP3", c->rate, c->channels);
    return true;
}

static OSStatus audio_feed(AudioConverterRef conv, UInt32 *npk, AudioBufferList *bl, AudioStreamPacketDescription **desc, void *arg)
{
    (void)conv;
    codec *c = arg;
    if (!c->packet) { *npk = 0; return 1; }                         /* no more input for this call: AudioConverter returns what it has */
    bl->mNumberBuffers = 1;
    bl->mBuffers[0].mData = (void *)c->packet; bl->mBuffers[0].mDataByteSize = c->packet_len; bl->mBuffers[0].mNumberChannels = c->in.mChannelsPerFrame;
    c->desc = (AudioStreamPacketDescription){ 0, 0, c->packet_len };
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
    pcm *p = calloc(1, sizeof(*p));
    p->data = buf; p->len = (int)(frames * c->out.mBytesPerFrame); p->pts = pts;
    pthread_mutex_lock(&c->lock); *c->pcm_tail = p; c->pcm_tail = &p->next; c->decoded++; pthread_mutex_unlock(&c->lock);
}

/* ---- encoders ---- */

/* MediaCodecInfo.CodecCapabilities' colour formats and profiles, as MediaFormat carries them */
enum { COLOR_SURFACE = 0x7F000789, COLOR_YUV420_FLEXIBLE = 0x7F420888, COLOR_YUV420_PLANAR = 19, COLOR_YUV420_SEMIPLANAR = 21 };
enum { AVC_BASELINE = 1, AVC_MAIN = 2, AVC_HIGH = 8, AVC_CONSTRAINED_BASELINE = 0x10000, AVC_CONSTRAINED_HIGH = 0x80000 };
enum { BITRATE_CQ = 0, BITRATE_VBR = 1, BITRATE_CBR = 2, BITRATE_CBR_FD = 3 };
enum { PARAM_REQUEST_SYNC = 1, PARAM_VIDEO_BITRATE = 2 };
#define AAC_FRAME 1024

typedef struct packet { uint8_t *d; size_t n; int64_t pts; int flags; struct packet *next; } packet;

typedef struct enc {
    int kind;
    pthread_mutex_t lock; pthread_cond_t cv;            /* the packet queue: filled from VideoToolbox's thread, drained by Java's */
    bool trace;
    packet *q, **q_tail;
    long emitted, failed;
    /* video */
    VTCompressionSessionRef vt;
    OSType pool_fmt;                                    /* what the session's own pixel buffer pool makes */
    int width, height, bitrate, profile, level, bitrate_mode, color;
    float fps, iframe_s;
    bool want_key, csd_sent;
    blob sps, pps;
    uint8_t *scratch; size_t scratch_n;                 /* the read-back frame, turned the right way up */
    /* audio */
    AudioConverterRef conv;
    AudioStreamBasicDescription in, out;
    int rate, channels, aac_profile;
    uint8_t *pcm; size_t pcm_n, pcm_cap, pcm_pos;       /* 16-bit PCM queued by the app, pcm_pos of it handed to the converter */
    bool eos_pending;
    int64_t pts0, frames_out; bool have_pts0;
    uint8_t asc[8]; size_t asc_n;
    UInt32 max_packet;
} enc;

static void enc_push(enc *e, uint8_t *d, size_t n, int64_t pts, int flags)
{
    packet *p = calloc(1, sizeof(*p));
    p->d = d; p->n = n; p->pts = pts; p->flags = flags;
    pthread_mutex_lock(&e->lock);
    *e->q_tail = p; e->q_tail = &p->next; e->emitted++;
    pthread_cond_broadcast(&e->cv);
    pthread_mutex_unlock(&e->lock);
}
static void enc_drop_packets(enc *e)
{
    pthread_mutex_lock(&e->lock);
    for (packet *p = e->q, *n; p; p = n) { n = p->next; free(p->d); free(p); }
    e->q = NULL; e->q_tail = &e->q;
    pthread_cond_broadcast(&e->cv);
    pthread_mutex_unlock(&e->lock);
}

static void vt_set(VTCompressionSessionRef s, CFStringRef key, CFTypeRef v) { OSStatus st = VTSessionSetProperty(s, key, v); if (st != noErr && trace_on()) tl_log_line("media: encoder property refused (%d)", (int)st); }
static void vt_set_int(VTCompressionSessionRef s, CFStringRef key, int v) { CFNumberRef n = CFNumberCreate(NULL, kCFNumberIntType, &v); vt_set(s, key, n); CFRelease(n); }
static void vt_set_double(VTCompressionSessionRef s, CFStringRef key, double v) { CFNumberRef n = CFNumberCreate(NULL, kCFNumberDoubleType, &v); vt_set(s, key, n); CFRelease(n); }
static void dict_set_int(CFMutableDictionaryRef d, CFStringRef key, int v) { CFNumberRef n = CFNumberCreate(NULL, kCFNumberIntType, &v); CFDictionarySetValue(d, key, n); CFRelease(n); }

static CFStringRef vt_profile_level(int profile, int level)
{
    /* MediaCodecInfo.CodecProfileLevel's AVCLevel bits -> VideoToolbox's constants; levels the encoder has no constant for go auto */
    bool base = profile == AVC_BASELINE || profile == AVC_CONSTRAINED_BASELINE, high = profile == AVC_HIGH || profile == AVC_CONSTRAINED_HIGH;
    switch (level) {
    case 0x100: return base ? kVTProfileLevel_H264_Baseline_3_0 : high ? kVTProfileLevel_H264_High_3_0 : kVTProfileLevel_H264_Main_3_0;
    case 0x200: return base ? kVTProfileLevel_H264_Baseline_3_1 : high ? kVTProfileLevel_H264_High_3_1 : kVTProfileLevel_H264_Main_3_1;
    case 0x400: return base ? kVTProfileLevel_H264_Baseline_3_2 : high ? kVTProfileLevel_H264_High_3_2 : kVTProfileLevel_H264_Main_3_2;
    case 0x800: return base ? kVTProfileLevel_H264_Baseline_4_0 : high ? kVTProfileLevel_H264_High_4_0 : kVTProfileLevel_H264_Main_4_0;
    case 0x1000: return base ? kVTProfileLevel_H264_Baseline_4_1 : high ? kVTProfileLevel_H264_High_4_1 : kVTProfileLevel_H264_Main_4_1;
    case 0x2000: return base ? kVTProfileLevel_H264_Baseline_4_2 : high ? kVTProfileLevel_H264_High_4_2 : kVTProfileLevel_H264_Main_4_2;
    case 0x4000: return base ? kVTProfileLevel_H264_Baseline_5_0 : high ? kVTProfileLevel_H264_High_5_0 : kVTProfileLevel_H264_Main_5_0;
    case 0x8000: return base ? kVTProfileLevel_H264_Baseline_5_1 : high ? kVTProfileLevel_H264_High_5_1 : kVTProfileLevel_H264_Main_5_1;
    case 0x10000: return base ? kVTProfileLevel_H264_Baseline_5_2 : high ? kVTProfileLevel_H264_High_5_2 : kVTProfileLevel_H264_Main_5_2;
    default: return base ? kVTProfileLevel_H264_Baseline_AutoLevel : high ? kVTProfileLevel_H264_High_AutoLevel : kVTProfileLevel_H264_Main_AutoLevel;
    }
}

static int64_t cm_us(CMTime t) { return CMTIME_IS_VALID(t) ? CMTimeConvertScale(t, 1000000, kCMTimeRoundingMethod_Default).value : 0; }

/* an encoded frame: the parameter sets (once, and again when they change) as a CODEC_CONFIG packet, then the access unit in Annex-B */
static void vt_enc_out(void *ref, void *src, OSStatus st, VTEncodeInfoFlags fl, CMSampleBufferRef sb)
{
    (void)src;
    enc *e = ref;
    if (st != noErr || !sb) {
        if (!(fl & kVTEncodeInfo_FrameDropped) || e->trace) { e->failed++; if (e->failed < 4 || e->trace) tl_log_line("media: frame not encoded (%d)", (int)st); }
        return;
    }
    bool sync = true;
    CFArrayRef att = CMSampleBufferGetSampleAttachmentsArray(sb, false);
    if (att && CFArrayGetCount(att) > 0) {
        CFDictionaryRef d = (CFDictionaryRef)CFArrayGetValueAtIndex(att, 0);
        CFBooleanRef ns = d ? (CFBooleanRef)CFDictionaryGetValue(d, kCMSampleAttachmentKey_NotSync) : NULL;
        if (ns && CFBooleanGetValue(ns)) sync = false;
    }
    int64_t pts = cm_us(CMSampleBufferGetPresentationTimeStamp(sb));
    CMFormatDescriptionRef fd = CMSampleBufferGetFormatDescription(sb);
    int hl = 4;
    if (sync && fd) {
        const uint8_t *sps = NULL, *pps = NULL; size_t sn = 0, pn = 0;
        if (CMVideoFormatDescriptionGetH264ParameterSetAtIndex(fd, 0, &sps, &sn, NULL, &hl) == noErr &&
            CMVideoFormatDescriptionGetH264ParameterSetAtIndex(fd, 1, &pps, &pn, NULL, NULL) == noErr && sn && pn) {
            bool changed = false;
            blob_set(&e->sps, sps, sn, &changed); blob_set(&e->pps, pps, pn, &changed);
            if (changed || !e->csd_sent) {
                uint8_t *csd = malloc(sn + pn + 8);
                memcpy(csd, "\0\0\0\1", 4); memcpy(csd + 4, sps, sn); memcpy(csd + 4 + sn, "\0\0\0\1", 4); memcpy(csd + 8 + sn, pps, pn);
                enc_push(e, csd, sn + pn + 8, pts, FLAG_CONFIG);
                e->csd_sent = true;
            }
        }
    } else if (fd) CMVideoFormatDescriptionGetH264ParameterSetAtIndex(fd, 0, NULL, NULL, NULL, &hl);
    CMBlockBufferRef bb = CMSampleBufferGetDataBuffer(sb);
    size_t n = bb ? CMBlockBufferGetDataLength(bb) : 0;
    if (!n) return;
    uint8_t *d = malloc(n);
    if (CMBlockBufferCopyDataBytes(bb, 0, n, d) != noErr) { free(d); e->failed++; return; }
    if (hl < 1 || hl > 4) hl = 4;
    /* length-prefixed NAL units -> start codes, in place when the prefix is 4 bytes (the usual), else rebuilt */
    if (hl == 4) {
        size_t i = 0;
        while (i + 4 <= n) {
            uint32_t len = (uint32_t)d[i] << 24 | (uint32_t)d[i + 1] << 16 | (uint32_t)d[i + 2] << 8 | d[i + 3];
            d[i] = 0; d[i + 1] = 0; d[i + 2] = 0; d[i + 3] = 1;
            if (len == 0 || len > n - i - 4) break;
            i += 4 + len;
        }
    } else {
        uint8_t *o = malloc(n * 2 + 8); size_t on = 0, i = 0;
        while (i + (size_t)hl <= n) {
            uint32_t len = 0; for (int k = 0; k < hl; k++) len = len << 8 | d[i + k];
            i += (size_t)hl;
            if (len == 0 || len > n - i) break;
            memcpy(o + on, "\0\0\0\1", 4); memcpy(o + on + 4, d + i, len); on += 4 + len; i += len;
        }
        free(d); d = o; n = on;
    }
    enc_push(e, d, n, pts, sync ? FLAG_SYNC : 0);
}

static void venc_close(enc *e)
{
    if (e->vt) { VTCompressionSessionCompleteFrames(e->vt, kCMTimeInvalid); VTCompressionSessionInvalidate(e->vt); CFRelease(e->vt); e->vt = NULL; }
}

static void venc_apply_bitrate(enc *e)
{
    if (!e->vt || e->bitrate <= 0) return;
    vt_set_int(e->vt, kVTCompressionPropertyKey_AverageBitRate, e->bitrate);
    if (e->bitrate_mode == BITRATE_CBR || e->bitrate_mode == BITRATE_CBR_FD) {
        /* a hard cap per second on top of the average: the closest VideoToolbox gets to CBR before iOS 16 */
        int64_t bytes = e->bitrate / 8; double secs = 1.0;
        CFNumberRef nb = CFNumberCreate(NULL, kCFNumberSInt64Type, &bytes), ns = CFNumberCreate(NULL, kCFNumberDoubleType, &secs);
        const void *v[2] = { nb, ns };
        CFArrayRef lim = CFArrayCreate(NULL, v, 2, &kCFTypeArrayCallBacks);
        vt_set(e->vt, kVTCompressionPropertyKey_DataRateLimits, lim);
        CFRelease(lim); CFRelease(nb); CFRelease(ns);
        if (__builtin_available(macOS 13.0, iOS 16.0, *)) vt_set_int(e->vt, kVTCompressionPropertyKey_ConstantBitRate, e->bitrate);
    }
}

static bool venc_open(enc *e)
{
    venc_close(e);
    if (e->width <= 0 || e->height <= 0) return false;
    e->pool_fmt = e->color == COLOR_SURFACE ? kCVPixelFormatType_32BGRA : kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange;
    CFMutableDictionaryRef src = CFDictionaryCreateMutable(NULL, 0, &kCFTypeDictionaryKeyCallBacks, &kCFTypeDictionaryValueCallBacks);
    dict_set_int(src, kCVPixelBufferPixelFormatTypeKey, (int)e->pool_fmt);
    dict_set_int(src, kCVPixelBufferWidthKey, e->width); dict_set_int(src, kCVPixelBufferHeightKey, e->height);
    CFDictionaryRef io = CFDictionaryCreate(NULL, NULL, NULL, 0, &kCFTypeDictionaryKeyCallBacks, &kCFTypeDictionaryValueCallBacks);
    CFDictionarySetValue(src, kCVPixelBufferIOSurfacePropertiesKey, io); CFRelease(io);
    OSStatus st = VTCompressionSessionCreate(NULL, e->width, e->height, kCMVideoCodecType_H264, NULL, src, NULL, vt_enc_out, e, &e->vt);
    CFRelease(src);
    if (st != noErr || !e->vt) { tl_log_line("media: no H.264 encoder session for %dx%d (%d)", e->width, e->height, (int)st); e->vt = NULL; return false; }
    vt_set(e->vt, kVTCompressionPropertyKey_RealTime, kCFBooleanTrue);
    vt_set(e->vt, kVTCompressionPropertyKey_AllowFrameReordering, kCFBooleanFalse);
    vt_set(e->vt, kVTCompressionPropertyKey_ProfileLevel, vt_profile_level(e->profile, e->level));
    bool base = e->profile == AVC_BASELINE || e->profile == AVC_CONSTRAINED_BASELINE;
    vt_set(e->vt, kVTCompressionPropertyKey_H264EntropyMode, base ? kVTH264EntropyMode_CAVLC : kVTH264EntropyMode_CABAC);
    if (e->fps > 0) vt_set_double(e->vt, kVTCompressionPropertyKey_ExpectedFrameRate, e->fps);
    /* KEY_I_FRAME_INTERVAL: seconds between key frames; 0 makes every frame one, negative only the first */
    double fps = e->fps > 0 ? e->fps : 30;
    int interval = e->iframe_s < 0 ? 1 << 30 : e->iframe_s == 0 ? 1 : (int)(e->iframe_s * fps + 0.5);
    vt_set_int(e->vt, kVTCompressionPropertyKey_MaxKeyFrameInterval, interval < 1 ? 1 : interval);
    if (e->iframe_s > 0) vt_set_double(e->vt, kVTCompressionPropertyKey_MaxKeyFrameIntervalDuration, e->iframe_s);
    venc_apply_bitrate(e);
    VTCompressionSessionPrepareToEncodeFrames(e->vt);
    e->want_key = true;
    tl_log_line("media: H.264 encoder for %dx%d, %d bps, %.1f fps, %s input", e->width, e->height, e->bitrate, e->fps, e->color == COLOR_SURFACE ? "surface" : "YUV420");
    return true;
}

/* a pixel buffer to fill: the session's pool when it makes this format, else a fresh IOSurface-backed one */
static CVPixelBufferRef venc_buffer(enc *e, OSType fmt)
{
    CVPixelBufferRef pb = NULL;
    CVPixelBufferPoolRef pool = e->vt ? VTCompressionSessionGetPixelBufferPool(e->vt) : NULL;
    if (pool && e->pool_fmt == fmt && CVPixelBufferPoolCreatePixelBuffer(NULL, pool, &pb) == kCVReturnSuccess && pb) return pb;
    CFDictionaryRef io = CFDictionaryCreate(NULL, NULL, NULL, 0, &kCFTypeDictionaryKeyCallBacks, &kCFTypeDictionaryValueCallBacks);
    const void *k[] = { kCVPixelBufferIOSurfacePropertiesKey }, *v[] = { io };
    CFDictionaryRef attrs = CFDictionaryCreate(NULL, k, v, 1, &kCFTypeDictionaryKeyCallBacks, &kCFTypeDictionaryValueCallBacks);
    pb = NULL;
    if (CVPixelBufferCreate(NULL, (size_t)e->width, (size_t)e->height, fmt, attrs, &pb) != kCVReturnSuccess) pb = NULL;
    CFRelease(attrs); CFRelease(io);
    return pb;
}

static bool venc_frame(enc *e, CVPixelBufferRef pb, int64_t pts)
{
    if (!e->vt) return false;
    CFDictionaryRef props = NULL;
    if (e->want_key) {
        const void *k[] = { kVTEncodeFrameOptionKey_ForceKeyFrame }, *v[] = { kCFBooleanTrue };
        props = CFDictionaryCreate(NULL, k, v, 1, &kCFTypeDictionaryKeyCallBacks, &kCFTypeDictionaryValueCallBacks);
        e->want_key = false;
    }
    CMTime dur = e->fps > 0 ? CMTimeMake((int64_t)(1000000 / e->fps), 1000000) : kCMTimeInvalid;
    VTEncodeInfoFlags info = 0;
    OSStatus st = VTCompressionSessionEncodeFrame(e->vt, pb, CMTimeMake(pts, 1000000), dur, props, NULL, &info);
    if (props) CFRelease(props);
    if (st != noErr) {
        e->failed++;
        if (e->failed < 4 || e->trace) tl_log_line("media: encode refused (%d)", (int)st);
        if (st == kVTInvalidSessionErr) venc_open(e);
        return false;
    }
    return true;
}

/* the app's YUV420 planes (planar I420, or NV12 for COLOR_FormatYUV420SemiPlanar; stride = width, as the input format says) */
static bool venc_yuv(enc *e, const uint8_t *d, size_t n, int color, int64_t pts)
{
    size_t w = (size_t)e->width, h = (size_t)e->height, cw = (w + 1) / 2, ch = (h + 1) / 2;
    if (n < w * h + 2 * cw * ch) { if (e->failed++ < 4) tl_log_line("media: a %zu-byte frame is too small for %zux%zu YUV420", n, w, h); return false; }
    CVPixelBufferRef pb = venc_buffer(e, kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange);
    if (!pb) return false;
    CVPixelBufferLockBaseAddress(pb, 0);
    uint8_t *y = CVPixelBufferGetBaseAddressOfPlane(pb, 0), *uv = CVPixelBufferGetBaseAddressOfPlane(pb, 1);
    size_t ys = CVPixelBufferGetBytesPerRowOfPlane(pb, 0), uvs = CVPixelBufferGetBytesPerRowOfPlane(pb, 1);
    for (size_t r = 0; r < h; r++) memcpy(y + r * ys, d + r * w, w);
    const uint8_t *src = d + w * h;
    if (color == COLOR_YUV420_SEMIPLANAR) {
        for (size_t r = 0; r < ch; r++) memcpy(uv + r * uvs, src + r * cw * 2, cw * 2);
    } else {
        const uint8_t *u = src, *v = src + cw * ch;
        for (size_t r = 0; r < ch; r++) {
            uint8_t *o = uv + r * uvs; const uint8_t *ur = u + r * cw, *vr = v + r * cw;
            for (size_t x = 0; x < cw; x++) { o[2 * x] = ur[x]; o[2 * x + 1] = vr[x]; }
        }
    }
    CVPixelBufferUnlockBaseAddress(pb, 0);
    bool ok = venc_frame(e, pb, pts);
    CVPixelBufferRelease(pb);
    return ok;
}

/* pixels read back from the input Surface's GL framebuffer: RGBA, bottom row first when flip */
static bool venc_rgba(enc *e, const uint8_t *px, int w, int h, bool flip, int64_t pts)
{
    if (!px || w <= 0 || h <= 0) return false;
    CVPixelBufferRef pb = venc_buffer(e, kCVPixelFormatType_32BGRA);
    if (!pb) return false;
    CVPixelBufferLockBaseAddress(pb, 0);
    size_t dw = CVPixelBufferGetWidth(pb), dh = CVPixelBufferGetHeight(pb), ds = CVPixelBufferGetBytesPerRow(pb);
    size_t cw = (size_t)w < dw ? (size_t)w : dw, chh = (size_t)h < dh ? (size_t)h : dh;
    vImage_Buffer s = { (void *)px, (vImagePixelCount)chh, (vImagePixelCount)cw, (size_t)w * 4 };
    vImage_Buffer d = { CVPixelBufferGetBaseAddress(pb), (vImagePixelCount)chh, (vImagePixelCount)cw, ds };
    const uint8_t map[4] = { 2, 1, 0, 3 };                                    /* RGBA -> BGRA */
    vImage_Error err;
    if (flip) {
        if (e->scratch_n < (size_t)w * h * 4) { e->scratch_n = (size_t)w * h * 4; e->scratch = realloc(e->scratch, e->scratch_n); }
        vImage_Buffer t = { e->scratch, (vImagePixelCount)chh, (vImagePixelCount)cw, (size_t)w * 4 };
        err = vImageVerticalReflect_ARGB8888(&s, &t, kvImageNoFlags);
        if (err == kvImageNoError) err = vImagePermuteChannels_ARGB8888(&t, &d, map, kvImageNoFlags);
    } else err = vImagePermuteChannels_ARGB8888(&s, &d, map, kvImageNoFlags);
    CVPixelBufferUnlockBaseAddress(pb, 0);
    bool ok = err == kvImageNoError && venc_frame(e, pb, pts);
    CVPixelBufferRelease(pb);
    return ok;
}

/* ---- AAC ---- */

static int aac_rate_index(int rate)
{
    static const int rates[] = { 96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350 };
    for (int i = 0; i < 13; i++) if (rates[i] == rate) return i;
    return -1;
}

/* the AudioSpecificConfig inside the converter's magic cookie (an ES descriptor), when it has one */
static size_t asc_from_cookie(const uint8_t *c, size_t n, uint8_t *out, size_t cap)
{
    size_t i = 0;
    while (i + 2 <= n) {
        uint8_t tag = c[i++]; size_t len = 0; int k = 0;
        while (i < n && k++ < 4) { uint8_t b = c[i++]; len = len << 7 | (b & 0x7f); if (!(b & 0x80)) break; }
        if (tag == 0x05) { if (len > cap || i + len > n) return 0; memcpy(out, c + i, len); return len; }
        if (tag == 0x03) i += 3;                        /* ES_ID, flags: the descriptors nest from here */
        else if (tag == 0x04) i += 13;                  /* object type, stream type, buffer size, bitrates: then the DecoderSpecificInfo */
        else i += len;
    }
    return 0;
}

static bool aenc_open(enc *e)
{
    if (e->conv) { AudioConverterDispose(e->conv); e->conv = NULL; }
    if (e->rate <= 0) e->rate = 44100;
    if (e->channels <= 0) e->channels = 1;
    if (aac_rate_index(e->rate) < 0) { tl_log_line("media: AAC cannot encode %d Hz", e->rate); return false; }
    e->in = (AudioStreamBasicDescription){ .mSampleRate = e->rate, .mFormatID = kAudioFormatLinearPCM,
        .mFormatFlags = kLinearPCMFormatFlagIsSignedInteger | kLinearPCMFormatFlagIsPacked, .mBytesPerPacket = 2 * (UInt32)e->channels,
        .mFramesPerPacket = 1, .mBytesPerFrame = 2 * (UInt32)e->channels, .mChannelsPerFrame = (UInt32)e->channels, .mBitsPerChannel = 16 };
    e->out = (AudioStreamBasicDescription){ .mSampleRate = e->rate, .mFormatID = kAudioFormatMPEG4AAC, .mFramesPerPacket = AAC_FRAME, .mChannelsPerFrame = (UInt32)e->channels };
    OSStatus st = AudioConverterNew(&e->in, &e->out, &e->conv);
    if (st != noErr) { tl_log_line("media: no AAC encoder for %d Hz, %d channels (%d)", e->rate, e->channels, (int)st); e->conv = NULL; return false; }
    if (e->bitrate > 0) {
        UInt32 br = (UInt32)e->bitrate;
        if (AudioConverterSetProperty(e->conv, kAudioConverterEncodeBitRate, sizeof br, &br) != noErr) {
            /* not a rate this encoder does: the nearest it offers */
            UInt32 sz = 0;
            if (AudioConverterGetPropertyInfo(e->conv, kAudioConverterApplicableEncodeBitRates, &sz, NULL) == noErr && sz >= sizeof(AudioValueRange)) {
                AudioValueRange *r = malloc(sz);
                if (AudioConverterGetProperty(e->conv, kAudioConverterApplicableEncodeBitRates, &sz, r) == noErr) {
                    double best = -1; UInt32 pick = br;
                    for (UInt32 i = 0; i < sz / sizeof(AudioValueRange); i++) {
                        double v = br < r[i].mMinimum ? r[i].mMinimum : br > r[i].mMaximum ? r[i].mMaximum : br, dist = v > br ? v - br : br - v;
                        if (best < 0 || dist < best) { best = dist; pick = (UInt32)v; }
                    }
                    AudioConverterSetProperty(e->conv, kAudioConverterEncodeBitRate, sizeof pick, &pick);
                    e->bitrate = (int)pick;
                }
                free(r);
            }
        }
    }
    UInt32 sz = sizeof e->max_packet;
    if (AudioConverterGetProperty(e->conv, kAudioConverterPropertyMaximumOutputPacketSize, &sz, &e->max_packet) != noErr || e->max_packet == 0) e->max_packet = 8192 * (UInt32)e->channels;
    /* csd-0: object type 2 (LC), the rate's index, the channel configuration; the converter's cookie knows better when it has one */
    int ri = aac_rate_index(e->rate);
    e->asc[0] = (uint8_t)(2 << 3 | ri >> 1); e->asc[1] = (uint8_t)((ri & 1) << 7 | (e->channels & 0xf) << 3); e->asc_n = 2;
    UInt32 cn = 0;
    if (AudioConverterGetPropertyInfo(e->conv, kAudioConverterCompressionMagicCookie, &cn, NULL) == noErr && cn > 0 && cn < 256) {
        uint8_t cookie[256];
        if (AudioConverterGetProperty(e->conv, kAudioConverterCompressionMagicCookie, &cn, cookie) == noErr) {
            size_t n = asc_from_cookie(cookie, cn, e->asc, sizeof e->asc);
            if (n) e->asc_n = n;
        }
    }
    e->frames_out = 0; e->have_pts0 = false; e->pcm_n = e->pcm_pos = 0; e->eos_pending = false;
    tl_log_line("media: AAC encoder, %d Hz, %d channels, %d bps", e->rate, e->channels, e->bitrate);
    return true;
}

/* the converter asks for PCM frames: what the app queued, or, with nothing left, an error (try again later) / end of stream at EOS */
static OSStatus aenc_feed(AudioConverterRef conv, UInt32 *npk, AudioBufferList *bl, AudioStreamPacketDescription **desc, void *arg)
{
    (void)conv;
    enc *e = arg;
    size_t bpf = e->in.mBytesPerFrame, avail = (e->pcm_n - e->pcm_pos) / bpf;
    if (desc) *desc = NULL;
    if (avail == 0) { *npk = 0; return e->eos_pending ? noErr : 1; }
    if (avail > *npk) avail = *npk;
    bl->mNumberBuffers = 1;
    bl->mBuffers[0].mNumberChannels = e->in.mChannelsPerFrame; bl->mBuffers[0].mData = e->pcm + e->pcm_pos; bl->mBuffers[0].mDataByteSize = (UInt32)(avail * bpf);
    e->pcm_pos += avail * bpf;
    *npk = (UInt32)avail;
    return noErr;
}

static void aenc_run(enc *e)
{
    for (;;) {
        UInt32 npk = 1;
        uint8_t *buf = malloc(e->max_packet);
        AudioBufferList bl = { 1, { { e->out.mChannelsPerFrame, e->max_packet, buf } } };
        AudioStreamPacketDescription d = { 0, 0, 0 };
        OSStatus st = AudioConverterFillComplexBuffer(e->conv, aenc_feed, e, &npk, &bl, &d);
        if (npk == 0 || bl.mBuffers[0].mDataByteSize == 0) {
            free(buf);
            if (st != noErr && st != 1) { e->failed++; if (e->failed < 4 || e->trace) tl_log_line("media: AAC packet not encoded (%d)", (int)st); }
            break;
        }
        int64_t pts = e->pts0 + e->frames_out * 1000000 / e->rate;
        e->frames_out += AAC_FRAME;
        enc_push(e, buf, bl.mBuffers[0].mDataByteSize, pts, 0);
    }
    if (e->pcm_pos) { memmove(e->pcm, e->pcm + e->pcm_pos, e->pcm_n - e->pcm_pos); e->pcm_n -= e->pcm_pos; e->pcm_pos = 0; }
}

static void aenc_pcm(enc *e, const uint8_t *d, size_t n, int64_t pts, int flags)
{
    if (!e->conv && !aenc_open(e)) return;
    if (!e->have_pts0 && (n || (flags & FLAG_EOS))) { e->pts0 = pts; e->have_pts0 = true; }
    if (n) {
        if (e->pcm_n + n > e->pcm_cap) { e->pcm_cap = (e->pcm_n + n) * 2; e->pcm = realloc(e->pcm, e->pcm_cap); }
        memcpy(e->pcm + e->pcm_n, d, n); e->pcm_n += n;
    }
    if (flags & FLAG_EOS) {
        e->eos_pending = true;
        aenc_run(e);
        AudioConverterReset(e->conv);
        e->eos_pending = false; e->pcm_n = e->pcm_pos = 0; e->have_pts0 = false; e->frames_out = 0;
    } else aenc_run(e);
}

/* ---- natives ---- */

static int kind_of(const char *mime)
{
    if (!mime) return 0;
    if (!strcasecmp(mime, "video/avc")) return K_AVC;
    if (!strcasecmp(mime, "video/hevc")) return K_HEVC;
    if (!strcasecmp(mime, "audio/mp4a-latm")) return K_AAC;
    if (!strcasecmp(mime, "audio/mpeg")) return K_MP3;
    return 0;
}
#define C ((codec *)(uintptr_t)a[0].j)

NAT(M_supported)
{
    (void)self;
    int k = kind_of(tl_jni_string(a[0].l));
    bool ok = k == K_AVC || k == K_AAC || k == K_MP3;
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
    if (!k) { *ret = J(0); return true; }
    codec *c = calloc(1, sizeof(*c));
    c->kind = k; c->trace = trace_on();
    pthread_mutex_init(&c->lock, NULL);
    c->ready_tail = &c->ready; c->pcm_tail = &c->pcms;
    *ret = J((int64_t)(uintptr_t)c);
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
        for (int i = 0; i < 3; i++) if (csd[i]) { nal_walk w = { c, NULL, 0, 0 }; each_nal(csd[i]->arr.data, csd[i]->arr.len, take_nal, &w); free(w.out); }
        if (c->params_changed) { c->params_changed = false; session_make(c); }
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
    codec *c = C;
    pthread_mutex_lock(&c->lock);
    frame *f = c->ready;
    if (f) { c->ready = f->next; if (!c->ready) c->ready_tail = &c->ready; }
    pthread_mutex_unlock(&c->lock);
    if (!f) { *ret = J(0); return true; }
    int64_t *info = a[1].l ? (int64_t *)((jobj *)a[1].l)->arr.data : NULL;
    if (info) {
        CGRect r = CVImageBufferGetCleanRect(f->pb);
        int w = (int)r.size.width, h = (int)r.size.height;
        if (w <= 0 || h <= 0) { w = (int)CVPixelBufferGetWidth(f->pb); h = (int)CVPixelBufferGetHeight(f->pb); }
        info[0] = f->pts; info[1] = w; info[2] = h;
    }
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
NAT(M_destroy)
{
    (void)self; (void)ret;
    codec *c = C;
    if (!c) return true;
    session_drop(c);
    drop_outputs(c);
    if (c->fmt) CFRelease(c->fmt);
    if (c->conv) AudioConverterDispose(c->conv);
    free(c->vps.d); free(c->sps.d); free(c->pps.d);
    if (c->trace) tl_log_line("media: codec closed after %ld outputs, %ld failures", c->decoded, c->failed);
    pthread_mutex_destroy(&c->lock);
    free(c);
    return true;
}
NAT(M_size) { (void)self; codec *c = C; *ret = I(c->width << 16 | (c->height & 0xffff)); return true; }

/* ---- the encoder natives ---- */
#define E ((enc *)(uintptr_t)a[0].j)

NAT(E_create)
{
    (void)self;
    const char *mime = tl_jni_string(a[0].l);
    int k = mime && !strcasecmp(mime, "video/avc") ? K_AVC_ENC : mime && !strcasecmp(mime, "audio/mp4a-latm") ? K_AAC_ENC : 0;
    if (!k) { *ret = J(0); return true; }
    enc *e = calloc(1, sizeof(*e));
    e->kind = k; e->trace = trace_on();
    pthread_mutex_init(&e->lock, NULL); pthread_cond_init(&e->cv, NULL);
    e->q_tail = &e->q;
    *ret = J((int64_t)(uintptr_t)e);
    return true;
}
/* configure(h, width, height, bitrate, frameRate, iFrameInterval, profile, level, bitrateMode, colorFormat, sampleRate, channels, aacProfile) */
NAT(E_configure)
{
    (void)self;
    enc *e = E;
    if (e->kind == K_AVC_ENC) {
        e->width = a[1].i & ~1; e->height = a[2].i & ~1; e->bitrate = a[3].i; e->fps = a[4].f; e->iframe_s = a[5].f;
        e->profile = a[6].i; e->level = a[7].i; e->bitrate_mode = a[8].i; e->color = a[9].i;
        if (e->bitrate <= 0) e->bitrate = (int)((double)e->width * e->height * (e->fps > 0 ? e->fps : 30) * 0.1);
        e->csd_sent = false;
        *ret = Z(venc_open(e));
    } else {
        e->rate = a[10].i; e->channels = a[11].i; e->bitrate = a[3].i; e->aac_profile = a[12].i;
        if (e->bitrate <= 0) e->bitrate = 64000 * (e->channels > 0 ? e->channels : 1);
        *ret = Z(aenc_open(e));
    }
    return true;
}
/* encode(h, data, offset, len, ptsUs, flags): a YUV420 frame or 16-bit PCM; null data with END_OF_STREAM drains the encoder */
NAT(E_encode)
{
    (void)self;
    enc *e = E;
    jobj *b = a[1].l;
    size_t off = b && a[2].i > 0 ? (size_t)a[2].i : 0, n = b && a[3].i > 0 ? (size_t)a[3].i : 0;
    if (b && off > b->arr.len) off = b->arr.len;
    if (b && n > b->arr.len - off) n = b->arr.len - off;
    const uint8_t *d = b ? (const uint8_t *)b->arr.data + off : NULL;
    int64_t pts = a[4].j; int flags = a[5].i;
    bool ok = true;
    if (e->kind == K_AVC_ENC) {
        if (n && !(flags & FLAG_CONFIG)) ok = venc_yuv(e, d, n, e->color, pts);
        if (flags & FLAG_EOS) {
            if (e->vt) VTCompressionSessionCompleteFrames(e->vt, kCMTimeInvalid);
            enc_push(e, NULL, 0, pts, FLAG_EOS);
        }
    } else {
        if (!(flags & FLAG_CONFIG)) aenc_pcm(e, d, n, pts, flags);
        if (flags & FLAG_EOS) enc_push(e, NULL, 0, pts, FLAG_EOS);
    }
    *ret = Z(ok);
    return true;
}
/* encodeRgba(h, pixels, width, height, flip, ptsUs): the input Surface's frame, read back from GL (RGBA, bottom row first when flip) */
NAT(E_encodeRgba)
{
    (void)self;
    enc *e = E;
    *ret = Z(e->kind == K_AVC_ENC && venc_rgba(e, gl_buffer(a[1].l), a[2].i, a[3].i, a[4].z != 0, a[5].j));
    return true;
}
/* nextPacket(h, info, timeoutUs) -> the next encoded packet (null: none within the timeout); info = { ptsUs, flags } */
NAT(E_nextPacket)
{
    (void)self;
    enc *e = E;
    int64_t timeout = a[2].j;
    pthread_mutex_lock(&e->lock);
    if (!e->q && timeout > 0) {
        struct timespec ts; clock_gettime(CLOCK_REALTIME, &ts);
        ts.tv_sec += timeout / 1000000; ts.tv_nsec += (timeout % 1000000) * 1000;
        if (ts.tv_nsec >= 1000000000) { ts.tv_sec++; ts.tv_nsec -= 1000000000; }
        while (!e->q) if (pthread_cond_timedwait(&e->cv, &e->lock, &ts)) break;
    }
    packet *p = e->q;
    if (p) { e->q = p->next; if (!e->q) e->q_tail = &e->q; }
    pthread_mutex_unlock(&e->lock);
    if (!p) { *ret = L(NULL); return true; }
    int64_t *info = a[1].l ? (int64_t *)((jobj *)a[1].l)->arr.data : NULL;
    if (info) { info[0] = p->pts; info[1] = p->flags; }
    jobj *arr = tl_jni_new_prim_array('B', (uint32_t)p->n);
    arr->refs = 1u << 30;
    if (p->n) memcpy(arr->arr.data, p->d, p->n);
    free(p->d); free(p);
    *ret = L(arr);
    return true;
}
/* setParameter(h, key, value): MediaCodec.setParameters' request-sync and video-bitrate */
NAT(E_param)
{
    (void)self; (void)ret;
    enc *e = E;
    if (a[1].i == PARAM_REQUEST_SYNC) e->want_key = true;
    else if (a[1].i == PARAM_VIDEO_BITRATE && a[2].i > 0) { e->bitrate = a[2].i; venc_apply_bitrate(e); }
    return true;
}
NAT(E_flush)
{
    (void)self; (void)ret;
    enc *e = E;
    if (e->vt) VTCompressionSessionCompleteFrames(e->vt, kCMTimeInvalid);
    if (e->conv) { AudioConverterReset(e->conv); e->pcm_n = e->pcm_pos = 0; e->have_pts0 = false; e->frames_out = 0; }
    enc_drop_packets(e);
    e->want_key = true;
    return true;
}
NAT(E_destroy)
{
    (void)self; (void)ret;
    enc *e = E;
    if (!e) return true;
    venc_close(e);
    if (e->conv) AudioConverterDispose(e->conv);
    enc_drop_packets(e);
    free(e->sps.d); free(e->pps.d); free(e->scratch); free(e->pcm);
    if (e->trace) tl_log_line("media: encoder closed after %ld packets, %ld failures", e->emitted, e->failed);
    pthread_mutex_destroy(&e->lock); pthread_cond_destroy(&e->cv);
    free(e);
    return true;
}
/* csd(h) -> the audio encoder's AudioSpecificConfig (csd-0); the video encoder's parameter sets come as its first packet */
NAT(E_csd)
{
    (void)self;
    enc *e = E;
    if (e->kind != K_AAC_ENC || !e->asc_n) { *ret = L(NULL); return true; }
    jobj *arr = tl_jni_new_prim_array('B', (uint32_t)e->asc_n);
    arr->refs = 1u << 30;
    memcpy(arr->arr.data, e->asc, e->asc_n);
    *ret = L(arr);
    return true;
}
/* bitrate(h) -> the bitrate in use (an AAC rate the encoder could not do is replaced by the nearest it offers) */
NAT(E_bitrate) { (void)self; *ret = I(E->bitrate); return true; }

static const struct { const char *name, *sig; dvm_native_fn fn; } k_media[] = {
    { "createEncoder", "(Ljava/lang/String;)J", E_create },
    { "configureEncoder", "(JIIIFFIIIIIII)Z", E_configure },
    { "encode", "(J[BIIJI)Z", E_encode },
    { "encodeRgba", "(JLjava/nio/ByteBuffer;IIZJ)Z", E_encodeRgba },
    { "nextPacket", "(J[JJ)[B", E_nextPacket },
    { "setEncoderParameter", "(JII)V", E_param },
    { "flushEncoder", "(J)V", E_flush },
    { "destroyEncoder", "(J)V", E_destroy },
    { "encoderCsd", "(J)[B", E_csd },
    { "encoderBitrate", "(J)I", E_bitrate },
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
