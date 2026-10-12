/* SPDX-License-Identifier: GPL-2.0-or-later */
/*
 * android.media.MediaMuxer (husk.MediaMuxerNative's natives): an MP4 writer in plain C, no Apple framework behind it, so a file it
 * writes is the same on every host and the test under tools/ can run it on Linux.
 *
 * Layout: ftyp, then one mdat that samples go into in the order the app writes them (the interleaving is the app's, as with
 * Android's MPEG4Writer), and the moov at the end, written at stop from the sample tables kept in memory: per sample its offset,
 * size, presentation time and sync flag. Video samples arrive as Annex-B access units and are stored length-prefixed (avc1/hvc1
 * with the parameter sets in avcC/hvcC, taken from csd-0/csd-1 or a CODEC_CONFIG buffer); AAC frames go in as they are, under
 * mp4a/esds built from the AudioSpecificConfig. Decode times are derived at stop: samples sorted by presentation time give the
 * decode order's times, shifted back by the largest reordering delay, which becomes a ctts offset per sample (zero, and no ctts,
 * for streams without B-frames, like Husk's own encoder's). Edit lists cover that delay and a track starting after the earliest
 * one. The orientation hint is the tkhd matrix, the location a udta/©xyz string, as Android writes them.
 */
#define _DARWIN_C_SOURCE
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

enum { T_AVC = 1, T_HEVC, T_AAC };
enum { MUX_MPEG4 = 0, MUX_WEBM = 1, MUX_3GPP = 2, MUX_HEIF = 3, MUX_OGG = 4 };
enum { MUX_FLAG_SYNC = 1, MUX_FLAG_CONFIG = 2, MUX_FLAG_EOS = 4 };
#define VIDEO_TIMESCALE 90000
#define MOVIE_TIMESCALE 1000
#define MAX_TRACKS 4
#define MAX_PS 4

typedef struct { uint8_t *d; size_t n; } mblob;
typedef struct { uint64_t off; uint32_t size; int64_t pts; bool sync; } msample;
typedef struct { uint64_t off; uint32_t count; } mchunk;

typedef struct {
    int kind;
    int width, height, rate, channels, bitrate;
    float fps;
    mblob vps[MAX_PS], sps[MAX_PS], pps[MAX_PS]; int nvps, nsps, npps;
    mblob asc;
    uint32_t timescale;
    msample *s; size_t n, cap;
    mchunk *c; size_t nc, ccap;
    /* worked out at stop */
    int64_t *dts;                               /* decode time per sample, media timescale, from 0 */
    uint32_t *cto;                              /* composition offset per sample, media timescale */
    int64_t delay;                              /* the reordering delay (presentation starts this late), media timescale */
    int64_t start_us;                           /* the first presentation time */
    uint64_t duration;                          /* media timescale */
} mtrack;

typedef struct mux {
    FILE *f;
    int format;
    mtrack t[MAX_TRACKS]; int nt;
    bool started, stopped;
    uint64_t mdat_pos;                          /* the mdat header (a free box precedes it, room for a 64-bit size) */
    int rotation;
    bool has_loc; float lat, lon;
    int last_track;
    uint8_t *scratch; size_t scratch_cap;
    char err[160];
} mux;

/* ---- a growable byte buffer with big-endian writes, for the boxes ---- */

typedef struct { uint8_t *d; size_t n, cap; } bb;
static void bb_need(bb *b, size_t more) { if (b->n + more > b->cap) { b->cap = (b->n + more) * 2 + 256; b->d = realloc(b->d, b->cap); } }
static void bb_bytes(bb *b, const void *p, size_t n) { bb_need(b, n); memcpy(b->d + b->n, p, n); b->n += n; }
static void bb_u8(bb *b, uint32_t v) { bb_need(b, 1); b->d[b->n++] = (uint8_t)v; }
static void bb_u16(bb *b, uint32_t v) { bb_u8(b, v >> 8); bb_u8(b, v); }
static void bb_u24(bb *b, uint32_t v) { bb_u8(b, v >> 16); bb_u16(b, v); }
static void bb_u32(bb *b, uint32_t v) { bb_u16(b, v >> 16); bb_u16(b, v); }
static void bb_u64(bb *b, uint64_t v) { bb_u32(b, (uint32_t)(v >> 32)); bb_u32(b, (uint32_t)v); }
static void bb_zero(bb *b, size_t n) { bb_need(b, n); memset(b->d + b->n, 0, n); b->n += n; }
static void bb_tag(bb *b, const char *t) { bb_bytes(b, t, 4); }
static size_t box_begin(bb *b, const char *t) { size_t p = b->n; bb_u32(b, 0); bb_tag(b, t); return p; }
static size_t fullbox_begin(bb *b, const char *t, uint32_t version, uint32_t flags) { size_t p = box_begin(b, t); bb_u8(b, version); bb_u24(b, flags); return p; }
static void box_end(bb *b, size_t p) { uint32_t n = (uint32_t)(b->n - p); b->d[p] = (uint8_t)(n >> 24); b->d[p + 1] = (uint8_t)(n >> 16); b->d[p + 2] = (uint8_t)(n >> 8); b->d[p + 3] = (uint8_t)n; }

/* ---- NAL units and their headers ---- */

static void blob_copy(mblob *b, const uint8_t *d, size_t n) { free(b->d); b->d = malloc(n ? n : 1); memcpy(b->d, d, n); b->n = n; }

/* Annex-B (start codes) or length-prefixed: each NAL unit to fn */
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
                size_t end = i; while (end > start && d[end - 1] == 0) end--;
                if (end > start) fn(arg, d + start, end - start);
            }
            i += 3; start = i;
        } else i++;
    }
    if (start != (size_t)-1 && start < n) fn(arg, d + start, n - start);
}

/* an RBSP bit reader: emulation prevention bytes removed first */
typedef struct { uint8_t *d; size_t n, pos; bool bad; } bits;
static void bits_init(bits *b, const uint8_t *nal, size_t n)
{
    b->d = malloc(n ? n : 1); b->n = 0; b->pos = 0; b->bad = false;
    int zeros = 0;
    for (size_t i = 0; i < n; i++) {
        if (zeros >= 2 && nal[i] == 3) { zeros = 0; continue; }
        b->d[b->n++] = nal[i];
        zeros = nal[i] == 0 ? zeros + 1 : 0;
    }
}
static uint32_t bits_u(bits *b, int k)
{
    uint32_t v = 0;
    for (int i = 0; i < k; i++) {
        if (b->pos >= b->n * 8) { b->bad = true; return 0; }
        v = v << 1 | ((b->d[b->pos >> 3] >> (7 - (b->pos & 7))) & 1); b->pos++;
    }
    return v;
}
static uint32_t bits_ue(bits *b)
{
    int z = 0;
    while (!bits_u(b, 1)) { if (++z > 31 || b->bad) { b->bad = true; return 0; } }
    return z ? (1u << z) - 1 + bits_u(b, z) : 0;
}

typedef struct { int chroma, luma_depth, chroma_depth; } ps_info;

/* H.264 SPS: profile, level and, for the avcC extension, chroma format and bit depths */
static void avc_sps_info(const uint8_t *nal, size_t n, ps_info *o)
{
    o->chroma = 1; o->luma_depth = 8; o->chroma_depth = 8;
    if (n < 4) return;
    int profile = nal[1];
    bits b; bits_init(&b, nal + 4, n - 4);
    bits_ue(&b);                                                /* seq_parameter_set_id */
    if (profile == 100 || profile == 110 || profile == 122 || profile == 244 || profile == 44 || profile == 83 || profile == 86 ||
        profile == 118 || profile == 128 || profile == 138 || profile == 139 || profile == 134 || profile == 135) {
        o->chroma = (int)bits_ue(&b);
        if (o->chroma == 3) bits_u(&b, 1);
        o->luma_depth = 8 + (int)bits_ue(&b); o->chroma_depth = 8 + (int)bits_ue(&b);
        if (b.bad) { o->chroma = 1; o->luma_depth = o->chroma_depth = 8; }
    }
    free(b.d);
}

/* HEVC SPS: past the profile/tier/level block to chroma_format_idc and the bit depths */
static void hevc_sps_info(const uint8_t *nal, size_t n, ps_info *o)
{
    o->chroma = 1; o->luma_depth = 8; o->chroma_depth = 8;
    if (n < 3) return;
    bits b; bits_init(&b, nal + 2, n - 2);
    bits_u(&b, 4);                                              /* sps_video_parameter_set_id */
    int sub = (int)bits_u(&b, 3);                               /* sps_max_sub_layers_minus1 */
    bits_u(&b, 1);                                              /* temporal_id_nesting */
    bits_u(&b, 32); bits_u(&b, 32); bits_u(&b, 32); bits_u(&b, 8);  /* general profile, compat flags, constraints, level: 96 bits */
    uint32_t prof[8] = { 0 }, lev[8] = { 0 };
    for (int i = 0; i < sub; i++) { prof[i] = bits_u(&b, 1); lev[i] = bits_u(&b, 1); }
    if (sub > 0) for (int i = sub; i < 8; i++) bits_u(&b, 2);
    for (int i = 0; i < sub; i++) { if (prof[i]) { bits_u(&b, 32); bits_u(&b, 32); bits_u(&b, 24); } if (lev[i]) bits_u(&b, 8); }
    bits_ue(&b);                                                /* sps_seq_parameter_set_id */
    int chroma = (int)bits_ue(&b);
    if (chroma == 3) bits_u(&b, 1);
    bits_ue(&b); bits_ue(&b);                                   /* width, height */
    if (bits_u(&b, 1)) { bits_ue(&b); bits_ue(&b); bits_ue(&b); bits_ue(&b); }
    int ld = 8 + (int)bits_ue(&b), cd = 8 + (int)bits_ue(&b);
    if (!b.bad) { o->chroma = chroma; o->luma_depth = ld; o->chroma_depth = cd; }
    free(b.d);
}

typedef struct { mtrack *t; } ps_walk;
static void take_ps(void *arg, const uint8_t *nal, size_t n)
{
    mtrack *t = ((ps_walk *)arg)->t;
    if (!n) return;
    if (t->kind == T_AVC) {
        int ty = nal[0] & 0x1f;
        if (ty == 7 && t->nsps < MAX_PS) blob_copy(&t->sps[t->nsps++], nal, n);
        else if (ty == 8 && t->npps < MAX_PS) blob_copy(&t->pps[t->npps++], nal, n);
    } else {
        int ty = (nal[0] >> 1) & 0x3f;
        if (ty == 32 && t->nvps < MAX_PS) blob_copy(&t->vps[t->nvps++], nal, n);
        else if (ty == 33 && t->nsps < MAX_PS) blob_copy(&t->sps[t->nsps++], nal, n);
        else if (ty == 34 && t->npps < MAX_PS) blob_copy(&t->pps[t->npps++], nal, n);
    }
}
static void track_take_csd(mtrack *t, const uint8_t *d, size_t n)
{
    if (!d || !n) return;
    if (t->kind == T_AAC) { if (!t->asc.n) blob_copy(&t->asc, d, n); return; }
    ps_walk w = { t };
    each_nal(d, n, take_ps, &w);
}
static bool track_ready(const mtrack *t) { return t->kind == T_AAC ? t->asc.n > 0 : t->kind == T_AVC ? t->nsps && t->npps : t->nvps && t->nsps && t->npps; }

/* an access unit, Annex-B or already length-prefixed, as 4-byte-length-prefixed NAL units without parameter sets and delimiters */
typedef struct { mtrack *t; bb out; } au_walk;
static void take_au_nal(void *arg, const uint8_t *nal, size_t n)
{
    au_walk *w = arg;
    if (!n) return;
    int ty = w->t->kind == T_AVC ? nal[0] & 0x1f : (nal[0] >> 1) & 0x3f;
    if (w->t->kind == T_AVC ? (ty == 7 || ty == 8 || ty == 9) : (ty == 32 || ty == 33 || ty == 34 || ty == 35)) {
        if (!track_ready(w->t)) take_ps(&(ps_walk){ w->t }, nal, n);
        return;
    }
    bb_u32(&w->out, (uint32_t)n); bb_bytes(&w->out, nal, n);
}

/* ---- the writer ---- */

static mux *mux_open(const char *path, int fd, int format)
{
    FILE *f = NULL;
    if (fd >= 0) {
        int d = dup(fd);
        if (d >= 0) { lseek(d, 0, SEEK_SET); if (ftruncate(d, 0)) { /* a pipe or read-only: written from where it is */ } f = fdopen(d, "w"); if (!f) close(d); }
    } else if (path) f = fopen(path, "wb");
    if (!f) return NULL;
    mux *m = calloc(1, sizeof(*m));
    m->f = f; m->format = format; m->last_track = -1;
    return m;
}

static int mux_add_track(mux *m, const char *mime, int w, int h, int rate, int channels, int bitrate, float fps, const uint8_t *csd0, size_t n0, const uint8_t *csd1, size_t n1)
{
    if (m->started) { snprintf(m->err, sizeof m->err, "addTrack after start"); return -1; }
    if (m->nt >= MAX_TRACKS) { snprintf(m->err, sizeof m->err, "too many tracks (%d)", MAX_TRACKS); return -1; }
    int kind = !mime ? 0 : !strcasecmp(mime, "video/avc") ? T_AVC : !strcasecmp(mime, "video/hevc") ? T_HEVC : !strcasecmp(mime, "audio/mp4a-latm") ? T_AAC : 0;
    if (!kind) { snprintf(m->err, sizeof m->err, "unsupported track mime %s", mime ? mime : "(none)"); return -1; }
    if (kind == T_AAC && m->format == MUX_3GPP) { /* 3GPP carries AAC too */ }
    mtrack *t = &m->t[m->nt];
    memset(t, 0, sizeof *t);
    t->kind = kind; t->width = w; t->height = h; t->rate = rate; t->channels = channels; t->bitrate = bitrate; t->fps = fps;
    t->timescale = kind == T_AAC ? (uint32_t)(rate > 0 ? rate : 44100) : VIDEO_TIMESCALE;
    track_take_csd(t, csd0, n0); track_take_csd(t, csd1, n1);
    if (kind == T_AAC && !t->asc.n) { snprintf(m->err, sizeof m->err, "the audio format has no csd-0"); return -1; }
    return m->nt++;
}

static void mux_orientation(mux *m, int degrees) { m->rotation = degrees; }
static void mux_location(mux *m, float lat, float lon) { m->has_loc = true; m->lat = lat; m->lon = lon; }

static bool mux_start(mux *m)
{
    if (m->started || m->stopped) { snprintf(m->err, sizeof m->err, "start in the wrong state"); return false; }
    if (!m->nt) { snprintf(m->err, sizeof m->err, "no tracks added"); return false; }
    bb b = { 0 };
    size_t p = box_begin(&b, "ftyp");
    if (m->format == MUX_3GPP) { bb_tag(&b, "3gp4"); bb_u32(&b, 0); bb_tag(&b, "3gp4"); bb_tag(&b, "isom"); }
    else { bb_tag(&b, "isom"); bb_u32(&b, 0); bb_tag(&b, "isom"); bb_tag(&b, "mp42"); }
    box_end(&b, p);
    p = box_begin(&b, "free"); box_end(&b, p);                   /* becomes the mdat's 64-bit header when it grows past 4 GB */
    m->mdat_pos = b.n;
    p = box_begin(&b, "mdat"); (void)p;                          /* the size is patched at stop */
    bool ok = fwrite(b.d, 1, b.n, m->f) == b.n;
    free(b.d);
    if (!ok) { snprintf(m->err, sizeof m->err, "cannot write to the file"); return false; }
    m->started = true;
    return true;
}

static bool mux_write(mux *m, int ti, const uint8_t *d, size_t n, int64_t pts, int flags)
{
    if (!m->started || m->stopped) { snprintf(m->err, sizeof m->err, "writeSampleData before start or after stop"); return false; }
    if (ti < 0 || ti >= m->nt) { snprintf(m->err, sizeof m->err, "no track %d", ti); return false; }
    mtrack *t = &m->t[ti];
    if (flags & MUX_FLAG_CONFIG) { if (!track_ready(t)) track_take_csd(t, d, n); return true; }
    const uint8_t *out = d; size_t on = n;
    au_walk w = { t, { 0 } };
    if (t->kind != T_AAC) {
        each_nal(d, n, take_au_nal, &w);
        out = w.out.d; on = w.out.n;
    }
    if (!on) { free(w.out.d); return true; }
    long pos = ftell(m->f);
    if (pos < 0) { free(w.out.d); snprintf(m->err, sizeof m->err, "cannot tell the file position"); return false; }
    bool ok = fwrite(out, 1, on, m->f) == on;
    free(w.out.d);
    if (!ok) { snprintf(m->err, sizeof m->err, "cannot write to the file"); return false; }
    if (t->n == t->cap) { t->cap = t->cap ? t->cap * 2 : 256; t->s = realloc(t->s, t->cap * sizeof *t->s); }
    t->s[t->n++] = (msample){ (uint64_t)pos, (uint32_t)on, pts, t->kind == T_AAC || (flags & MUX_FLAG_SYNC) != 0 };
    if (m->last_track == ti && t->nc) t->c[t->nc - 1].count++;
    else {
        if (t->nc == t->ccap) { t->ccap = t->ccap ? t->ccap * 2 : 64; t->c = realloc(t->c, t->ccap * sizeof *t->c); }
        t->c[t->nc++] = (mchunk){ (uint64_t)pos, 1 };
    }
    m->last_track = ti;
    return true;
}

static int cmp_i64(const void *a, const void *b) { int64_t x = *(const int64_t *)a, y = *(const int64_t *)b; return x < y ? -1 : x > y; }
static int64_t to_scale(int64_t us, uint32_t scale) { return (int64_t)((double)us * scale / 1000000.0 + (us < 0 ? -0.5 : 0.5)); }

/* decode times and composition offsets from the presentation times, as explained at the top */
static void track_time(mtrack *t)
{
    size_t n = t->n;
    int64_t *sorted = malloc(n * sizeof *sorted);
    for (size_t i = 0; i < n; i++) sorted[i] = t->s[i].pts;
    qsort(sorted, n, sizeof *sorted, cmp_i64);
    int64_t delay = 0;
    for (size_t i = 0; i < n; i++) if (sorted[i] - t->s[i].pts > delay) delay = sorted[i] - t->s[i].pts;
    t->start_us = sorted[0];
    t->delay = to_scale(delay, t->timescale);
    t->dts = malloc(n * sizeof *t->dts); t->cto = malloc(n * sizeof *t->cto);
    int64_t dts0 = sorted[0] - delay;
    bool any_cto = false;
    for (size_t i = 0; i < n; i++) {
        t->dts[i] = to_scale(sorted[i] - delay - dts0, t->timescale);
        if (i && t->dts[i] < t->dts[i - 1]) t->dts[i] = t->dts[i - 1];
        int64_t cto = to_scale(t->s[i].pts - dts0, t->timescale) - t->dts[i];
        t->cto[i] = cto > 0 ? (uint32_t)cto : 0;
        if (t->cto[i]) any_cto = true;
    }
    if (!any_cto) { free(t->cto); t->cto = NULL; }
    free(sorted);
}
static uint32_t sample_delta(const mtrack *t, size_t i)
{
    if (i + 1 < t->n) return (uint32_t)(t->dts[i + 1] - t->dts[i]);
    if (t->n > 1) return (uint32_t)(t->dts[i] - t->dts[i - 1]);                  /* the last one lasts as long as the one before */
    return t->kind == T_AAC ? 1024 : (uint32_t)(t->fps > 0 ? VIDEO_TIMESCALE / t->fps : VIDEO_TIMESCALE / 30);
}

static void write_esds(bb *b, const mtrack *t)
{
    size_t p = fullbox_begin(b, "esds", 0, 0);
    size_t asc = t->asc.n;
    bb_u8(b, 0x03); bb_u8(b, (uint32_t)(23 + asc)); bb_u16(b, 0); bb_u8(b, 0);              /* ES_Descriptor: ES_ID, flags */
    bb_u8(b, 0x04); bb_u8(b, (uint32_t)(15 + asc)); bb_u8(b, 0x40); bb_u8(b, 0x15);          /* DecoderConfig: MPEG-4 audio, audio stream */
    bb_u24(b, 0);                                                                            /* bufferSizeDB */
    bb_u32(b, (uint32_t)(t->bitrate > 0 ? t->bitrate : 0)); bb_u32(b, (uint32_t)(t->bitrate > 0 ? t->bitrate : 0));
    bb_u8(b, 0x05); bb_u8(b, (uint32_t)asc); bb_bytes(b, t->asc.d, asc);                      /* DecoderSpecificInfo */
    bb_u8(b, 0x06); bb_u8(b, 1); bb_u8(b, 0x02);                                             /* SLConfig: MP4 */
    box_end(b, p);
}

static void write_avcc(bb *b, const mtrack *t)
{
    size_t p = box_begin(b, "avcC");
    const mblob *sps = &t->sps[0];
    ps_info info; avc_sps_info(sps->d, sps->n, &info);
    bb_u8(b, 1); bb_u8(b, sps->d[1]); bb_u8(b, sps->d[2]); bb_u8(b, sps->d[3]);              /* version, profile, compatibility, level */
    bb_u8(b, 0xfc | 3);                                                                      /* 4-byte NAL lengths */
    bb_u8(b, 0xe0 | (uint32_t)t->nsps);
    for (int i = 0; i < t->nsps; i++) { bb_u16(b, (uint32_t)t->sps[i].n); bb_bytes(b, t->sps[i].d, t->sps[i].n); }
    bb_u8(b, (uint32_t)t->npps);
    for (int i = 0; i < t->npps; i++) { bb_u16(b, (uint32_t)t->pps[i].n); bb_bytes(b, t->pps[i].d, t->pps[i].n); }
    int profile = sps->d[1];
    if (profile == 100 || profile == 110 || profile == 122 || profile == 144) {
        bb_u8(b, 0xfc | (uint32_t)info.chroma); bb_u8(b, 0xf8 | (uint32_t)(info.luma_depth - 8)); bb_u8(b, 0xf8 | (uint32_t)(info.chroma_depth - 8)); bb_u8(b, 0);
    }
    box_end(b, p);
}

static void write_hvcc(bb *b, const mtrack *t)
{
    size_t p = box_begin(b, "hvcC");
    const mblob *vps = &t->vps[0], *sps = &t->sps[0];
    ps_info info; hevc_sps_info(sps->d, sps->n, &info);
    bits v; bits_init(&v, vps->d, vps->n);                      /* the general profile/tier/level: 12 bytes at byte 6 of the VPS */
    uint8_t ptl[12] = { 0 };
    if (v.n >= 18) memcpy(ptl, v.d + 6, 12);
    bool nested = v.n >= 4 && (v.d[3] & 0x80) != 0;             /* vps_temporal_id_nesting_flag */
    free(v.d);
    bb_u8(b, 1);
    bb_bytes(b, ptl, 12);                                        /* profile_space/tier/profile_idc, compatibility flags, constraints, level */
    bb_u16(b, 0xf000); bb_u8(b, 0xfc); bb_u8(b, 0xfc | (uint32_t)info.chroma);
    bb_u8(b, 0xf8 | (uint32_t)(info.luma_depth - 8)); bb_u8(b, 0xf8 | (uint32_t)(info.chroma_depth - 8));
    bb_u16(b, 0);                                                /* avgFrameRate */
    bb_u8(b, (1 << 3) | (nested ? 4 : 0) | 3);                   /* one temporal layer, 4-byte NAL lengths */
    bb_u8(b, 3);
    const mblob *arr[3] = { t->vps, t->sps, t->pps }; const int cnt[3] = { t->nvps, t->nsps, t->npps }; const int ty[3] = { 32, 33, 34 };
    for (int k = 0; k < 3; k++) {
        bb_u8(b, 0x80 | (uint32_t)ty[k]); bb_u16(b, (uint32_t)cnt[k]);
        for (int i = 0; i < cnt[k]; i++) { bb_u16(b, (uint32_t)arr[k][i].n); bb_bytes(b, arr[k][i].d, arr[k][i].n); }
    }
    box_end(b, p);
}

static void write_stsd(bb *b, const mtrack *t)
{
    size_t p = fullbox_begin(b, "stsd", 0, 0);
    bb_u32(b, 1);
    if (t->kind == T_AAC) {
        size_t e = box_begin(b, "mp4a");
        bb_zero(b, 6); bb_u16(b, 1);                             /* reserved, data reference index */
        bb_u16(b, 0); bb_u16(b, 0); bb_u32(b, 0);                /* version, revision, vendor */
        bb_u16(b, (uint32_t)(t->channels > 0 ? t->channels : 2)); bb_u16(b, 16); bb_u16(b, 0); bb_u16(b, 0);
        bb_u32(b, (uint32_t)t->rate << 16);
        write_esds(b, t);
        box_end(b, e);
    } else {
        size_t e = box_begin(b, t->kind == T_AVC ? "avc1" : "hvc1");
        bb_zero(b, 6); bb_u16(b, 1);
        bb_u16(b, 0); bb_u16(b, 0); bb_zero(b, 12);              /* pre_defined, reserved, pre_defined */
        bb_u16(b, (uint32_t)t->width); bb_u16(b, (uint32_t)t->height);
        bb_u32(b, 0x00480000); bb_u32(b, 0x00480000); bb_u32(b, 0); bb_u16(b, 1);  /* 72 dpi, reserved, frame count */
        bb_zero(b, 32);                                          /* compressor name */
        bb_u16(b, 0x18); bb_u16(b, 0xffff);                      /* depth, pre_defined */
        if (t->kind == T_AVC) write_avcc(b, t); else write_hvcc(b, t);
        box_end(b, e);
    }
    box_end(b, p);
}

static void write_stbl(bb *b, const mtrack *t, bool co64)
{
    size_t p = box_begin(b, "stbl");
    write_stsd(b, t);
    /* stts: runs of equal durations */
    size_t q = fullbox_begin(b, "stts", 0, 0), cnt_at = b->n; bb_u32(b, 0);
    uint32_t runs = 0;
    for (size_t i = 0; i < t->n;) {
        uint32_t d = sample_delta(t, i); size_t j = i + 1;
        while (j < t->n && sample_delta(t, j) == d) j++;
        bb_u32(b, (uint32_t)(j - i)); bb_u32(b, d); runs++; i = j;
    }
    { uint8_t *c = b->d + cnt_at; c[0] = (uint8_t)(runs >> 24); c[1] = (uint8_t)(runs >> 16); c[2] = (uint8_t)(runs >> 8); c[3] = (uint8_t)runs; }
    box_end(b, q);
    if (t->cto) {
        q = fullbox_begin(b, "ctts", 0, 0); cnt_at = b->n; bb_u32(b, 0); runs = 0;
        for (size_t i = 0; i < t->n;) {
            uint32_t o = t->cto[i]; size_t j = i + 1;
            while (j < t->n && t->cto[j] == o) j++;
            bb_u32(b, (uint32_t)(j - i)); bb_u32(b, o); runs++; i = j;
        }
        uint8_t *c = b->d + cnt_at; c[0] = (uint8_t)(runs >> 24); c[1] = (uint8_t)(runs >> 16); c[2] = (uint8_t)(runs >> 8); c[3] = (uint8_t)runs;
        box_end(b, q);
    }
    if (t->kind != T_AAC) {
        size_t nsync = 0; for (size_t i = 0; i < t->n; i++) nsync += t->s[i].sync;
        if (nsync < t->n) {
            q = fullbox_begin(b, "stss", 0, 0); bb_u32(b, (uint32_t)nsync);
            for (size_t i = 0; i < t->n; i++) if (t->s[i].sync) bb_u32(b, (uint32_t)(i + 1));
            box_end(b, q);
        }
    }
    /* stsc: runs of chunks with the same sample count */
    q = fullbox_begin(b, "stsc", 0, 0); cnt_at = b->n; bb_u32(b, 0); runs = 0;
    for (size_t i = 0; i < t->nc;) {
        uint32_t c = t->c[i].count; size_t j = i + 1;
        while (j < t->nc && t->c[j].count == c) j++;
        bb_u32(b, (uint32_t)(i + 1)); bb_u32(b, c); bb_u32(b, 1); runs++; i = j;
    }
    { uint8_t *c = b->d + cnt_at; c[0] = (uint8_t)(runs >> 24); c[1] = (uint8_t)(runs >> 16); c[2] = (uint8_t)(runs >> 8); c[3] = (uint8_t)runs; }
    box_end(b, q);
    q = fullbox_begin(b, "stsz", 0, 0); bb_u32(b, 0); bb_u32(b, (uint32_t)t->n);
    for (size_t i = 0; i < t->n; i++) bb_u32(b, t->s[i].size);
    box_end(b, q);
    q = fullbox_begin(b, co64 ? "co64" : "stco", 0, 0); bb_u32(b, (uint32_t)t->nc);
    for (size_t i = 0; i < t->nc; i++) { if (co64) bb_u64(b, t->c[i].off); else bb_u32(b, (uint32_t)t->c[i].off); }
    box_end(b, q);
    box_end(b, p);
}

static void write_trak(bb *b, mux *m, const mtrack *t, int id, uint32_t now, int64_t origin_us, bool co64)
{
    bool video = t->kind != T_AAC;
    int64_t late = to_scale(t->start_us - origin_us, MOVIE_TIMESCALE);           /* this track starts after the earliest one */
    uint64_t dur_movie = (uint64_t)to_scale((int64_t)((double)t->duration * 1000000.0 / t->timescale), MOVIE_TIMESCALE);
    uint64_t presented = t->duration > (uint64_t)t->delay ? t->duration - (uint64_t)t->delay : t->duration;
    uint64_t pres_movie = (uint64_t)to_scale((int64_t)((double)presented * 1000000.0 / t->timescale), MOVIE_TIMESCALE);
    size_t p = box_begin(b, "trak");
    size_t q = fullbox_begin(b, "tkhd", 0, 3);
    bb_u32(b, now); bb_u32(b, now); bb_u32(b, (uint32_t)id); bb_u32(b, 0);
    bb_u32(b, (uint32_t)(pres_movie + (late > 0 ? (uint64_t)late : 0)));
    bb_zero(b, 8); bb_u16(b, 0); bb_u16(b, 0);                                      /* reserved, layer, alternate group */
    bb_u16(b, video ? 0 : 0x0100); bb_u16(b, 0);                                   /* volume, reserved */
    uint32_t a = 0x00010000, bq = 0, c = 0, d = 0x00010000;
    if (video) {
        int r = ((m->rotation % 360) + 360) % 360;
        if (r == 90) { a = 0; bq = 0x00010000; c = 0xFFFF0000; d = 0; }
        else if (r == 180) { a = 0xFFFF0000; d = 0xFFFF0000; }
        else if (r == 270) { a = 0; bq = 0xFFFF0000; c = 0x00010000; d = 0; }
    }
    bb_u32(b, a); bb_u32(b, bq); bb_u32(b, 0); bb_u32(b, c); bb_u32(b, d); bb_u32(b, 0); bb_u32(b, 0); bb_u32(b, 0); bb_u32(b, 0x40000000);
    bb_u32(b, video ? (uint32_t)t->width << 16 : 0); bb_u32(b, video ? (uint32_t)t->height << 16 : 0);
    box_end(b, q);
    if (late > 0 || t->delay > 0) {
        q = box_begin(b, "edts");
        size_t e = fullbox_begin(b, "elst", 0, 0);
        bb_u32(b, late > 0 ? 2 : 1);
        if (late > 0) { bb_u32(b, (uint32_t)late); bb_u32(b, 0xffffffff); bb_u16(b, 1); bb_u16(b, 0); }   /* an empty edit */
        bb_u32(b, (uint32_t)pres_movie); bb_u32(b, (uint32_t)t->delay); bb_u16(b, 1); bb_u16(b, 0);
        box_end(b, e);
        box_end(b, q);
    }
    q = box_begin(b, "mdia");
    size_t h = fullbox_begin(b, "mdhd", 0, 0);
    bb_u32(b, now); bb_u32(b, now); bb_u32(b, t->timescale); bb_u32(b, (uint32_t)t->duration); bb_u16(b, 0x55c4); bb_u16(b, 0);
    box_end(b, h);
    h = fullbox_begin(b, "hdlr", 0, 0);
    bb_u32(b, 0); bb_tag(b, video ? "vide" : "soun"); bb_zero(b, 12); bb_bytes(b, video ? "VideoHandler" : "SoundHandler", 13);
    box_end(b, h);
    h = box_begin(b, "minf");
    if (video) { size_t v = fullbox_begin(b, "vmhd", 0, 1); bb_zero(b, 8); box_end(b, v); }
    else { size_t s = fullbox_begin(b, "smhd", 0, 0); bb_u32(b, 0); box_end(b, s); }
    size_t di = box_begin(b, "dinf"), dr = fullbox_begin(b, "dref", 0, 0); bb_u32(b, 1);
    size_t u = fullbox_begin(b, "url ", 0, 1); box_end(b, u);
    box_end(b, dr); box_end(b, di);
    write_stbl(b, t, co64);
    box_end(b, h);
    box_end(b, q);
    box_end(b, p);
    (void)dur_movie;
}

static bool mux_stop(mux *m)
{
    if (!m->started || m->stopped) { snprintf(m->err, sizeof m->err, "stop before start"); return false; }
    m->stopped = true;
    /* tracks that got no samples are left out, so the rest still plays */
    int live = 0;
    for (int i = 0; i < m->nt; i++) if (m->t[i].n && track_ready(&m->t[i])) live++;
    if (!live) { snprintf(m->err, sizeof m->err, "no track got any sample"); return false; }
    long end = ftell(m->f);
    if (end < 0) { snprintf(m->err, sizeof m->err, "cannot tell the file position"); return false; }
    uint64_t mdat = (uint64_t)end - m->mdat_pos;
    bb hdr = { 0 };
    if (mdat < 0xffffffffULL) { bb_u32(&hdr, (uint32_t)mdat); bb_tag(&hdr, "mdat"); fseek(m->f, (long)m->mdat_pos, SEEK_SET); }
    else { bb_u32(&hdr, 1); bb_tag(&hdr, "mdat"); bb_u64(&hdr, mdat + 8); fseek(m->f, (long)m->mdat_pos - 8, SEEK_SET); }
    bool ok = fwrite(hdr.d, 1, hdr.n, m->f) == hdr.n;
    free(hdr.d);
    fseek(m->f, end, SEEK_SET);
    bool co64 = (uint64_t)end > 0xfffffff0ULL;
    uint32_t now = (uint32_t)(time(NULL) + 2082844800LL);
    int64_t origin = 0; bool first = true;
    uint64_t longest = 0;
    for (int i = 0; i < m->nt; i++) {
        mtrack *t = &m->t[i];
        if (!t->n || !track_ready(t)) continue;
        track_time(t);
        t->duration = 0; for (size_t k = 0; k < t->n; k++) t->duration += sample_delta(t, k);
        if (first || t->start_us < origin) origin = t->start_us;
        first = false;
    }
    for (int i = 0; i < m->nt; i++) {
        mtrack *t = &m->t[i];
        if (!t->n || !track_ready(t)) continue;
        uint64_t d = (uint64_t)to_scale((int64_t)((double)(t->duration - (uint64_t)t->delay) * 1000000.0 / t->timescale) + (t->start_us - origin), MOVIE_TIMESCALE);
        if (d > longest) longest = d;
    }
    bb b = { 0 };
    size_t moov = box_begin(&b, "moov");
    size_t p = fullbox_begin(&b, "mvhd", 0, 0);
    bb_u32(&b, now); bb_u32(&b, now); bb_u32(&b, MOVIE_TIMESCALE); bb_u32(&b, (uint32_t)longest);
    bb_u32(&b, 0x00010000); bb_u16(&b, 0x0100); bb_zero(&b, 10);
    bb_u32(&b, 0x00010000); bb_u32(&b, 0); bb_u32(&b, 0); bb_u32(&b, 0); bb_u32(&b, 0x00010000); bb_u32(&b, 0); bb_u32(&b, 0); bb_u32(&b, 0); bb_u32(&b, 0x40000000);
    bb_zero(&b, 24); bb_u32(&b, (uint32_t)live + 1);
    box_end(&b, p);
    int id = 1;
    for (int i = 0; i < m->nt; i++) if (m->t[i].n && track_ready(&m->t[i])) write_trak(&b, m, &m->t[i], id++, now, origin, co64);
    if (m->has_loc) {
        char s[40]; snprintf(s, sizeof s, "%+08.4f%+09.4f/", m->lat, m->lon);
        p = box_begin(&b, "udta");
        size_t x = box_begin(&b, "\xa9xyz");
        bb_u16(&b, (uint32_t)strlen(s)); bb_u16(&b, 0x15c7); bb_bytes(&b, s, strlen(s));
        box_end(&b, x); box_end(&b, p);
    }
    box_end(&b, moov);
    ok = ok && fwrite(b.d, 1, b.n, m->f) == b.n && fflush(m->f) == 0;
    free(b.d);
    if (!ok) snprintf(m->err, sizeof m->err, "cannot write the movie header");
    return ok;
}

static void mux_close(mux *m)
{
    if (!m) return;
    if (m->f) fclose(m->f);
    for (int i = 0; i < m->nt; i++) {
        mtrack *t = &m->t[i];
        for (int k = 0; k < MAX_PS; k++) { free(t->vps[k].d); free(t->sps[k].d); free(t->pps[k].d); }
        free(t->asc.d); free(t->s); free(t->c); free(t->dts); free(t->cto);
    }
    free(m->scratch);
    free(m);
}

#ifndef HUSK_MUXER_STANDALONE
/* ---- the natives (husk.MediaMuxerNative) ---- */
#include "husk-tl-dvm-internal.h"

void tl_log_line(const char *fmt, ...);
#define NAT(fn) static bool fn(jobj *self, const jvalue *a, jvalue *ret)
#define M ((mux *)(uintptr_t)a[0].j)
static jvalue I(int32_t v) { jvalue r; r.j = (uint32_t)v; return r; }
static jvalue J(int64_t v) { jvalue r; r.j = v; return r; }
static jvalue Z(bool v) { jvalue r; r.j = v; return r; }
static jvalue L(void *p) { jvalue r; r.j = 0; r.l = p; return r; }

/* create(path, fd, format) -> handle (0: the file could not be opened) */
NAT(X_create)
{
    (void)self;
    mux *m = mux_open(tl_jni_string(a[0].l), a[1].i, a[2].i);
    if (m) tl_log_line("muxer: writing %s", a[0].l ? tl_jni_string(a[0].l) : "a file descriptor");
    *ret = J((int64_t)(uintptr_t)m);
    return true;
}
/* addTrack(h, mime, width, height, sampleRate, channels, bitrate, frameRate, csd0, csd1) -> track index, or -1 */
NAT(X_addTrack)
{
    (void)self;
    jobj *c0 = a[8].l, *c1 = a[9].l;
    *ret = I(mux_add_track(M, tl_jni_string(a[1].l), a[2].i, a[3].i, a[4].i, a[5].i, a[6].i, a[7].f,
                           c0 ? c0->arr.data : NULL, c0 ? c0->arr.len : 0, c1 ? c1->arr.data : NULL, c1 ? c1->arr.len : 0));
    return true;
}
NAT(X_orientation) { (void)self; (void)ret; mux_orientation(M, a[1].i); return true; }
NAT(X_location) { (void)self; (void)ret; mux_location(M, a[1].f, a[2].f); return true; }
NAT(X_start) { (void)self; *ret = Z(mux_start(M)); return true; }
/* write(h, track, data, offset, len, ptsUs, flags) */
NAT(X_write)
{
    (void)self;
    jobj *b = a[2].l;
    size_t off = b && a[3].i > 0 ? (size_t)a[3].i : 0, n = b && a[4].i > 0 ? (size_t)a[4].i : 0;
    if (b && off > b->arr.len) off = b->arr.len;
    if (b && n > b->arr.len - off) n = b->arr.len - off;
    *ret = Z(mux_write(M, a[1].i, b ? (const uint8_t *)b->arr.data + off : NULL, n, a[5].j, a[6].i));
    return true;
}
NAT(X_stop) { (void)self; bool ok = mux_stop(M); if (!ok) tl_log_line("muxer: %s", M->err); *ret = Z(ok); return true; }
NAT(X_error) { (void)self; *ret = L(M && M->err[0] ? tl_jni_new_string(M->err) : NULL); return true; }
NAT(X_release) { (void)self; (void)ret; mux_close(M); return true; }

static const struct { const char *name, *sig; dvm_native_fn fn; } k_muxer[] = {
    { "create", "(Ljava/lang/String;II)J", X_create },
    { "addTrack", "(JLjava/lang/String;IIIIIF[B[B)I", X_addTrack },
    { "setOrientation", "(JI)V", X_orientation },
    { "setLocation", "(JFF)V", X_location },
    { "start", "(J)Z", X_start },
    { "write", "(JI[BIIJI)Z", X_write },
    { "stop", "(J)Z", X_stop },
    { "error", "(J)Ljava/lang/String;", X_error },
    { "release", "(J)V", X_release },
    { NULL, NULL, NULL },
};
dvm_native_fn tl_muxer_native(const char *name, const char *sig);
dvm_native_fn tl_muxer_native(const char *name, const char *sig)
{
    for (int i = 0; k_muxer[i].name; i++) if (!strcmp(k_muxer[i].name, name) && !strcmp(k_muxer[i].sig, sig)) return k_muxer[i].fn;
    return NULL;
}
#endif
