/* SPDX-License-Identifier: GPL-2.0-or-later */
#define _DARWIN_C_SOURCE
#include "husk-tl-audio.h"

#include <AudioToolbox/AudioToolbox.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "husk-tl-bionic.h"
#include "husk-tl-cocos.h"

#define NBUF 3
#define FRAMES_PER_BUF 1024
#define RING_FRAMES 8192

static struct {
    pthread_mutex_t mu;
    pthread_cond_t cv;
    AudioQueueRef q;
    AudioQueueBufferRef bufs[NBUF];
    int rate, channels;
    int16_t *ring;
    size_t head, count;           /* frames */
    bool paused, failed;
} A = { .mu = PTHREAD_MUTEX_INITIALIZER, .cv = PTHREAD_COND_INITIALIZER };

/* The audio thread asks for samples; an empty ring is silence, never a wait. */
static void on_buffer(void *user, AudioQueueRef q, AudioQueueBufferRef b)
{
    (void)user;
    int16_t *out = b->mAudioData;
    size_t want = FRAMES_PER_BUF, ch = (size_t)A.channels;
    pthread_mutex_lock(&A.mu);
    size_t take = A.paused ? 0 : (A.count < want ? A.count : want);
    for (size_t i = 0; i < take; i++)
        for (size_t c = 0; c < ch; c++) out[i * ch + c] = A.ring[((A.head + i) % RING_FRAMES) * ch + c];
    A.head = (A.head + take) % RING_FRAMES;
    A.count -= take;
    pthread_cond_broadcast(&A.cv);
    pthread_mutex_unlock(&A.mu);
    if (take < want) memset(out + take * ch, 0, (want - take) * ch * sizeof(int16_t));
    b->mAudioDataByteSize = (UInt32)(want * ch * sizeof(int16_t));
    AudioQueueEnqueueBuffer(q, b, 0, NULL);
}

static bool open_queue(int rate, int channels)
{
    AudioStreamBasicDescription f = { 0 };
    f.mSampleRate = rate; f.mFormatID = kAudioFormatLinearPCM;
    f.mFormatFlags = kLinearPCMFormatFlagIsSignedInteger | kLinearPCMFormatFlagIsPacked;
    f.mBitsPerChannel = 16; f.mChannelsPerFrame = (UInt32)channels; f.mFramesPerPacket = 1;
    f.mBytesPerFrame = f.mBytesPerPacket = (UInt32)(channels * 2);
    OSStatus st = AudioQueueNewOutput(&f, on_buffer, NULL, NULL, NULL, 0, &A.q);
    if (st != noErr) { tl_log_line("audio: AudioQueueNewOutput failed (%d)", (int)st); return false; }
    A.ring = calloc((size_t)RING_FRAMES * (size_t)channels, sizeof(int16_t));
    A.rate = rate; A.channels = channels;
    for (int i = 0; i < NBUF; i++) {
        AudioQueueAllocateBuffer(A.q, FRAMES_PER_BUF * (UInt32)channels * 2, &A.bufs[i]);
        memset(A.bufs[i]->mAudioData, 0, FRAMES_PER_BUF * (size_t)channels * 2);
        A.bufs[i]->mAudioDataByteSize = FRAMES_PER_BUF * (UInt32)channels * 2;
        AudioQueueEnqueueBuffer(A.q, A.bufs[i], 0, NULL);
    }
    st = AudioQueueStart(A.q, NULL);
    if (st != noErr) { tl_log_line("audio: AudioQueueStart failed (%d)", (int)st); return false; }
    tl_log_line("audio: output running, %d Hz, %d channel(s)", rate, channels);
    return true;
}

static void sleep_for(int frames, int rate)
{
    struct timespec ts = { 0, (long)((double)frames * 1e9 / rate) };
    nanosleep(&ts, NULL);
}

static void ring_write(const int16_t *samples, int frames, int channels, int rate);

/*
 * The mixer's write. A game mixing at a low rate (Unity's FMOD runs at 24 kHz) is played at twice that: each frame, then the
 * point halfway to the next. The iPhone played Subway Surfers' 24 kHz output high-pitched and broken while the same samples
 * were right on a Mac, and every game that sounds right there runs at 44.1 or 48 kHz -- so the queue is only ever opened
 * at one of those.
 */
static void host_write(const int16_t *samples, int frames, int channels, int rate)
{
    if (rate > 0 && rate * 2 <= 48000 && channels > 0 && channels <= 2 && frames > 0) {
        static int16_t prev[2];
        static bool have_prev;
        int16_t *up = malloc((size_t)frames * 2 * (size_t)channels * sizeof(int16_t));
        if (up) {
            for (int f = 0; f < frames; f++)
                for (int c = 0; c < channels; c++) {
                    int16_t cur = samples[(size_t)f * channels + c];
                    int16_t before = f > 0 ? samples[(size_t)(f - 1) * channels + c] : (have_prev ? prev[c] : cur);
                    up[(size_t)(2 * f) * channels + c] = (int16_t)(((int)before + cur) / 2);
                    up[(size_t)(2 * f + 1) * channels + c] = cur;
                }
            for (int c = 0; c < channels; c++) prev[c] = samples[(size_t)(frames - 1) * channels + c];
            have_prev = true;
            ring_write(up, frames * 2, channels, rate * 2);
            free(up);
            return;
        }
    }
    ring_write(samples, frames, channels, rate);
}

/* Copy into the ring, blocking while it is full (that is the pacing). */
static void ring_write(const int16_t *samples, int frames, int channels, int rate)
{
    pthread_mutex_lock(&A.mu);
    if (!A.q && !A.failed) { if (!open_queue(rate, channels)) A.failed = true; }
    if (A.failed || channels != A.channels) { pthread_mutex_unlock(&A.mu); sleep_for(frames, rate); return; }
    int done = 0;
    while (done < frames) {
        while (A.count >= RING_FRAMES || A.paused) pthread_cond_wait(&A.cv, &A.mu);
        size_t room = RING_FRAMES - A.count, n = (size_t)(frames - done) < room ? (size_t)(frames - done) : room;
        size_t tail = (A.head + A.count) % RING_FRAMES;
        for (size_t i = 0; i < n; i++)
            memcpy(&A.ring[((tail + i) % RING_FRAMES) * (size_t)channels], &samples[(size_t)(done + (int)i) * (size_t)channels], (size_t)channels * sizeof(int16_t));
        A.count += n;
        done += (int)n;
    }
    pthread_mutex_unlock(&A.mu);
}

/* TL_AUDIO_MUTE: pace like a device but make no sound (for test runs on a Mac). TL_AUDIO_STATS=1 also logs, about once a second,
 * how loud the game's output was, so a silent run still shows whether the game is making sound. */
static void muted_write(const int16_t *samples, int frames, int channels, int rate)
{
    static int stats = -1, seen, peak;
    static long loud, total;
    if (stats < 0) stats = getenv("TL_AUDIO_STATS") != NULL;
    if (stats) {
        for (int i = 0; i < frames * channels; i++) {
            int v = samples[i] < 0 ? -samples[i] : samples[i];
            if (v > peak) peak = v;
            if (v > 64) loud++;
        }
        total += frames * channels;
        if ((seen += frames) >= rate) {
            tl_log_line("audio: %d Hz x %d, peak %d, %.0f%% of samples audible", rate, channels, peak, 100.0 * loud / (total ? total : 1));
            seen = 0; peak = 0; loud = 0; total = 0;
        }
    }
    sleep_for(frames, rate);
}

void tl_audio_install(void) { tl_cocos_audio_hook = getenv("TL_AUDIO_MUTE") ? muted_write : host_write; }

void tl_audio_set_paused(bool paused)
{
    pthread_mutex_lock(&A.mu);
    A.paused = paused;
    if (A.q) { if (paused) AudioQueuePause(A.q); else AudioQueueStart(A.q, NULL); }
    pthread_cond_broadcast(&A.cv);
    pthread_mutex_unlock(&A.mu);
}

/* ------------------------------------------------------------------------------------------------------------------- input */
/*
 * The microphone, for OpenSL ES recorders (WhatsApp's voice notes come through Oboe, which records over OpenSL ES) and
 * android.media.AudioRecord. One input AudioQueue, opened at the first reader's rate, fills a ring that readers drain; a
 * reader at another rate or channel count is converted on the way out. The audio session has to allow recording for the
 * queue to start, so the category is switched to play-and-record (through the speaker, as on a phone) while someone records,
 * and back to playback afterwards so the microphone indicator goes out.
 */
#include <objc/message.h>
#include <objc/runtime.h>

#define IN_NBUF 4
#define IN_FRAMES_PER_BUF 512
#define IN_RING_FRAMES 65536

static struct {
    pthread_mutex_t mu;
    pthread_cond_t cv;
    AudioQueueRef q;
    AudioQueueBufferRef bufs[IN_NBUF];
    int rate, channels, users;
    int16_t *ring;
    size_t head, count;           /* frames */
    uint64_t total;               /* frames captured since the queue opened */
    bool failed;
} IN = { .mu = PTHREAD_MUTEX_INITIALIZER, .cv = PTHREAD_COND_INITIALIZER };

static void session_category(bool record)
{
    id (*send)(id, SEL) = (id (*)(id, SEL))objc_msgSend;
    id (*send_s)(id, SEL, const char *) = (id (*)(id, SEL, const char *))objc_msgSend;
    bool (*send_cat)(id, SEL, id, id, unsigned long, void **) = (bool (*)(id, SEL, id, id, unsigned long, void **))objc_msgSend;
    bool (*send_active)(id, SEL, bool, void **) = (bool (*)(id, SEL, bool, void **))objc_msgSend;
    id session = send((id)objc_getClass("AVAudioSession"), sel_registerName("sharedInstance"));
    if (!session) return;
    id ns = (id)objc_getClass("NSString");
    id category = send_s(ns, sel_registerName("stringWithUTF8String:"), record ? "AVAudioSessionCategoryPlayAndRecord" : "AVAudioSessionCategoryPlayback");
    id mode = send_s(ns, sel_registerName("stringWithUTF8String:"), "AVAudioSessionModeDefault");
    /* MixWithOthers | AllowBluetooth | DefaultToSpeaker | AllowBluetoothA2DP while recording, MixWithOthers otherwise */
    unsigned long options = record ? (1 | 4 | 8 | 0x20) : 1;
    if (!send_cat(session, sel_registerName("setCategory:mode:options:error:"), category, mode, options, NULL))
        tl_log_line("audio: could not switch the audio session to %s", record ? "play-and-record" : "playback");
    send_active(session, sel_registerName("setActive:error:"), true, NULL);
}

static void in_buffer(void *user, AudioQueueRef q, AudioQueueBufferRef b, const AudioTimeStamp *ts, UInt32 npackets, const AudioStreamPacketDescription *desc)
{
    (void)user; (void)ts; (void)desc;
    const int16_t *in = b->mAudioData;
    size_t frames = npackets ? npackets : b->mAudioDataByteSize / (sizeof(int16_t) * (size_t)IN.channels), ch = (size_t)IN.channels;
    pthread_mutex_lock(&IN.mu);
    if (frames > IN_RING_FRAMES) { in += (frames - IN_RING_FRAMES) * ch; frames = IN_RING_FRAMES; }
    /* a reader that fell behind loses the oldest samples, never the newest */
    if (IN.count + frames > IN_RING_FRAMES) { size_t drop = IN.count + frames - IN_RING_FRAMES; IN.head = (IN.head + drop) % IN_RING_FRAMES; IN.count -= drop; }
    size_t tail = (IN.head + IN.count) % IN_RING_FRAMES;
    for (size_t i = 0; i < frames; i++) memcpy(&IN.ring[((tail + i) % IN_RING_FRAMES) * ch], &in[i * ch], ch * sizeof(int16_t));
    IN.count += frames; IN.total += frames;
    pthread_cond_broadcast(&IN.cv);
    pthread_mutex_unlock(&IN.mu);
    AudioQueueEnqueueBuffer(q, b, 0, NULL);
}

static bool open_input_locked(int rate, int channels)
{
    session_category(true);
    AudioStreamBasicDescription f = { 0 };
    f.mSampleRate = rate; f.mFormatID = kAudioFormatLinearPCM;
    f.mFormatFlags = kLinearPCMFormatFlagIsSignedInteger | kLinearPCMFormatFlagIsPacked;
    f.mBitsPerChannel = 16; f.mChannelsPerFrame = (UInt32)channels; f.mFramesPerPacket = 1;
    f.mBytesPerFrame = f.mBytesPerPacket = (UInt32)(channels * 2);
    OSStatus st = AudioQueueNewInput(&f, in_buffer, NULL, NULL, NULL, 0, &IN.q);
    if (st != noErr) { tl_log_line("audio: AudioQueueNewInput failed (%d)", (int)st); IN.q = NULL; return false; }
    IN.ring = calloc((size_t)IN_RING_FRAMES * (size_t)channels, sizeof(int16_t));
    IN.rate = rate; IN.channels = channels; IN.head = IN.count = 0; IN.total = 0;
    for (int i = 0; i < IN_NBUF; i++) {
        AudioQueueAllocateBuffer(IN.q, IN_FRAMES_PER_BUF * (UInt32)channels * 2, &IN.bufs[i]);
        AudioQueueEnqueueBuffer(IN.q, IN.bufs[i], 0, NULL);
    }
    st = AudioQueueStart(IN.q, NULL);
    if (st != noErr) {
        tl_log_line("audio: AudioQueueStart (input) failed (%d): is the microphone allowed for Husk in Settings?", (int)st);
        AudioQueueDispose(IN.q, true); IN.q = NULL; free(IN.ring); IN.ring = NULL;
        return false;
    }
    tl_log_line("audio: microphone running, %d Hz, %d channel(s)", rate, channels);
    return true;
}

bool tl_audioin_open(int rate, int channels)
{
    if (rate <= 0) rate = 48000;
    if (channels <= 0 || channels > 2) channels = 1;
    pthread_mutex_lock(&IN.mu);
    if (!IN.q) IN.failed = !open_input_locked(rate, channels);
    bool ok = IN.q != NULL;
    if (ok) IN.users++;
    pthread_mutex_unlock(&IN.mu);
    return ok;
}

void tl_audioin_close(void)
{
    pthread_mutex_lock(&IN.mu);
    if (IN.users > 0 && --IN.users == 0 && IN.q) {
        AudioQueueStop(IN.q, true);
        AudioQueueDispose(IN.q, true);
        IN.q = NULL; free(IN.ring); IN.ring = NULL; IN.count = 0;
        tl_log_line("audio: microphone stopped");
        session_category(false);
    }
    IN.failed = false;
    pthread_cond_broadcast(&IN.cv);
    pthread_mutex_unlock(&IN.mu);
}

/* Frames at the reader's rate and channel count; blocking waits for them all unless the microphone is gone. */
int tl_audioin_read(int16_t *dst, int frames, int rate, int channels, bool blocking)
{
    if (!dst || frames <= 0) return 0;
    if (rate <= 0) rate = IN.rate; if (channels <= 0) channels = IN.channels;
    pthread_mutex_lock(&IN.mu);
    if (!IN.q) { pthread_mutex_unlock(&IN.mu); return -1; }
    int in_rate = IN.rate, in_ch = IN.channels;
    /* how many captured frames one output frame consumes */
    double step = (double)in_rate / (double)rate;
    size_t need = step == 1.0 ? (size_t)frames : (size_t)((double)frames * step) + 1;
    if (need > IN_RING_FRAMES) need = IN_RING_FRAMES;
    while (blocking && IN.q && IN.count < need) pthread_cond_wait(&IN.cv, &IN.mu);
    size_t avail = IN.count;
    int out = 0;
    double pos = 0;
    for (; out < frames; out++) {
        size_t src = (size_t)pos;
        if (src >= avail) break;
        const int16_t *s = &IN.ring[((IN.head + src) % IN_RING_FRAMES) * (size_t)in_ch];
        for (int c = 0; c < channels; c++) {
            int16_t v = in_ch == channels ? s[c] : in_ch == 1 ? s[0] : (int16_t)(((int)s[0] + s[1]) / 2);
            dst[(size_t)out * (size_t)channels + c] = v;
        }
        pos += step;
    }
    size_t used = (size_t)pos;
    if (used > avail) used = avail;
    IN.head = (IN.head + used) % IN_RING_FRAMES; IN.count -= used;
    pthread_mutex_unlock(&IN.mu);
    return out;
}

bool tl_audioin_running(void) { return IN.q != NULL; }
