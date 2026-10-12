/* SPDX-License-Identifier: GPL-2.0-or-later */
/*
 * A demuxer for Java apps' android.media.MediaExtractor and MediaMetadataRetriever (husk.MediaDemux's natives).
 *
 * The first part is pure C with no Apple dependency (tests/translation-layer/demux-test.c compiles it on Linux): it reads a file --
 * through a file descriptor at an offset, as Android's setDataSource(fd, offset, length) hands it, or from memory -- and builds, up
 * front, one flat table of samples per track: offset, size, presentation time in microseconds, sync flag. Everything the Java API
 * asks (seeking by sync sample, interleaving the selected tracks in time order, sizes, durations) is then a walk over those tables,
 * and a sample is read with one pread when it is wanted. The tables are small (a few dozen bytes a sample) and building them costs
 * one read of the headers, which is what Android's extractors do too.
 *
 * Containers: ISO BMFF (MP4, M4A, MOV, 3GP; moov before or after mdat, co64, stz2, edit lists, rotation from tkhd's matrix, iTunes
 * metadata and cover art), Ogg (Opus with exact packet durations from the TOC byte, Vorbis with packet times spread over each page,
 * Vorbis comments and METADATA_BLOCK_PICTURE), MP3 (frame headers, Xing/Info and VBRI, ID3v2 text frames and APIC) and WAV (PCM,
 * in blocks of 1024 frames). Fragmented MP4 is not read. Video samples come out as Annex-B access units, which is what MediaCodec
 * takes; the parameter sets go to csd-0/csd-1 (csd-0 alone for HEVC) and Opus gets Android's three csd buffers.
 *
 * The second part is the natives: the handle Java keeps, MediaMetadataRetriever's frames (decoded through the MediaCodec decoder
 * of husk-tl-dvm-media.c, scaled and turned upright by the track's rotation into a Bitmap) and the small bridge to Java arrays.
 */
#define _DARWIN_C_SOURCE
#include <fcntl.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

/* ======================================================================================================================== demuxer */

enum { DMX_VIDEO = 1, DMX_AUDIO = 2 };
enum { DMX_NONE = 0, DMX_AVC, DMX_HEVC, DMX_AAC, DMX_MP3, DMX_OPUS, DMX_VORBIS, DMX_PCM, DMX_AMR_NB, DMX_AMR_WB, DMX_MPEG4V, DMX_H263, DMX_FLAC, DMX_OTHER };
enum { DMX_SEEK_PREVIOUS_SYNC = 0, DMX_SEEK_NEXT_SYNC = 1, DMX_SEEK_CLOSEST_SYNC = 2, DMX_SEEK_CLOSEST = 3 };
enum { DMX_M_TITLE, DMX_M_ARTIST, DMX_M_ALBUM, DMX_M_ALBUMARTIST, DMX_M_AUTHOR, DMX_M_COMPOSER, DMX_M_WRITER, DMX_M_DATE, DMX_M_YEAR, DMX_M_GENRE,
       DMX_M_TRACK, DMX_M_DISC, DMX_M_COMPILATION, DMX_M_LOCATION, DMX_M_CAPTURE_FPS, DMX_M_N };

typedef struct { uint64_t off; uint32_t size, pieces; int64_t pts; uint8_t sync; } dmx_sample;   /* pieces: Ogg, pieces after the first */
typedef struct { uint64_t off; uint32_t len; } dmx_piece;

typedef struct dmx_track {
    int type, codec;
    const char *mime;
    int width, height, rotation;                    /* video */
    int rate, channels, bits;                       /* audio (bits: PCM) */
    int64_t duration_us;
    int bitrate, fps;
    uint32_t max_size;                              /* the largest sample as read out (Annex-B form) */
    uint8_t *csd[3]; size_t csd_n[3];
    char lang[4];
    int color_standard, color_transfer, color_range; /* 0: unknown */
    int aac_profile;
    int nal_len;                                    /* AVC/HEVC: bytes of length prefix on each NAL unit (0: already Annex-B) */
    dmx_sample *s; uint32_t n, cap;
    /* the cursor */
    bool selected; uint32_t cur;
} dmx_track;

typedef struct dmx {
    int fd; uint8_t *mem; uint64_t base, len;       /* fd >= 0: pread at base; else mem */
    const char *mime;                               /* the container's */
    int64_t duration_us;
    int bitrate;
    dmx_track *t; int nt;
    char *meta[DMX_M_N];
    uint8_t *pic; size_t pic_n;                     /* embedded picture (JPEG/PNG bytes) */
    dmx_piece *pieces; uint32_t npieces, cappieces; /* Ogg: the parts of packets that cross pages */
    int64_t thumb_us;                               /* the frame a thumbnail is taken at when no time is asked, -1: none */
    /* the current sample, read out once */
    int cur_track; uint8_t *cur_data; size_t cur_n; bool cur_eos;
} dmx;

static size_t dmx_read_at(dmx *d, uint64_t off, void *buf, size_t n)
{
    if (off >= d->len) return 0;
    if (n > d->len - off) n = (size_t)(d->len - off);
    if (d->fd < 0) { memcpy(buf, d->mem + off, n); return n; }
    size_t got = 0;
    while (got < n) { ssize_t r = pread(d->fd, (uint8_t *)buf + got, n - got, (off_t)(d->base + off + got)); if (r <= 0) break; got += (size_t)r; }
    return got;
}
static uint8_t *dmx_slurp(dmx *d, uint64_t off, size_t n)   /* n bytes at off, or NULL */
{
    if (off >= d->len || n > d->len - off || n > (512u << 20)) return NULL;
    uint8_t *b = malloc(n ? n : 1);
    if (!b) return NULL;
    if (dmx_read_at(d, off, b, n) != n) { free(b); return NULL; }
    return b;
}

static uint16_t be16(const uint8_t *p) { return (uint16_t)(p[0] << 8 | p[1]); }
static uint32_t be32(const uint8_t *p) { return (uint32_t)p[0] << 24 | (uint32_t)p[1] << 16 | (uint32_t)p[2] << 8 | p[3]; }
static uint64_t be64(const uint8_t *p) { return (uint64_t)be32(p) << 32 | be32(p + 4); }
static uint16_t le16(const uint8_t *p) { return (uint16_t)(p[1] << 8 | p[0]); }
static uint32_t le32(const uint8_t *p) { return (uint32_t)p[3] << 24 | (uint32_t)p[2] << 16 | (uint32_t)p[1] << 8 | p[0]; }
static uint64_t le64(const uint8_t *p) { return (uint64_t)le32(p + 4) << 32 | le32(p); }
static void put16le(uint8_t *p, unsigned v) { p[0] = (uint8_t)v; p[1] = (uint8_t)(v >> 8); }
static void put32le(uint8_t *p, uint32_t v) { put16le(p, v & 0xffff); put16le(p + 2, v >> 16); }
#define FOURCC(a, b, c, d) ((uint32_t)(a) << 24 | (uint32_t)(b) << 16 | (uint32_t)(c) << 8 | (uint32_t)(d))

static dmx_track *track_add(dmx *d, int type)
{
    d->t = realloc(d->t, sizeof(*d->t) * (size_t)(d->nt + 1));
    dmx_track *t = &d->t[d->nt++];
    memset(t, 0, sizeof(*t));
    t->type = type; t->mime = type == DMX_VIDEO ? "video/unknown" : "audio/unknown";
    strcpy(t->lang, "und");
    return t;
}
static dmx_sample *sample_add(dmx_track *t)
{
    if (t->n == t->cap) { t->cap = t->cap ? t->cap * 2 : 1024; t->s = realloc(t->s, sizeof(*t->s) * t->cap); }
    dmx_sample *s = &t->s[t->n++];
    memset(s, 0, sizeof(*s));
    return s;
}
static void csd_set(dmx_track *t, int k, const uint8_t *d, size_t n)
{
    free(t->csd[k]); t->csd[k] = malloc(n ? n : 1); memcpy(t->csd[k], d, n); t->csd_n[k] = n;
}
static void csd_append(dmx_track *t, int k, const uint8_t *d, size_t n)
{
    t->csd[k] = realloc(t->csd[k], t->csd_n[k] + n); memcpy(t->csd[k] + t->csd_n[k], d, n); t->csd_n[k] += n;
}
static void meta_set(dmx *d, int k, const char *s, size_t n)
{
    if (!s || !n || d->meta[k]) return;
    while (n && !s[n - 1]) n--;
    if (!n) return;
    d->meta[k] = malloc(n + 1); memcpy(d->meta[k], s, n); d->meta[k][n] = 0;
}

/* the tables once filled: the track's duration, bitrate and largest sample; the file's duration */
static void track_finish(dmx *d, dmx_track *t)
{
    uint64_t bytes = 0; int64_t last = 0;
    for (uint32_t i = 0; i < t->n; i++) { bytes += t->s[i].size; if (t->s[i].size > t->max_size) t->max_size = t->s[i].size; if (t->s[i].pts > last) last = t->s[i].pts; }
    if (t->duration_us <= 0 && t->n) t->duration_us = last + (t->n > 1 ? last / (t->n - 1) : 0);
    if (t->duration_us > d->duration_us) d->duration_us = t->duration_us;
    if (!t->bitrate && t->duration_us > 0) t->bitrate = (int)(bytes * 8 * 1000000 / (uint64_t)t->duration_us);
    if (!t->fps && t->type == DMX_VIDEO && t->duration_us > 0) t->fps = (int)((int64_t)t->n * 1000000 / t->duration_us);
    if (t->nal_len) t->max_size += t->max_size / 10 + 64;           /* room for start codes in place of short length prefixes */
}

/* ---------------------------------------------------------------------------------------------------------------- ISO BMFF */

typedef struct { uint32_t timescale; int64_t duration; int32_t tkhd_w, tkhd_h; int rotation; int64_t empty_mv, media_time; bool have_elst;   /* empty_mv: an initial empty edit, in movie ticks */
                 uint32_t *stts; uint32_t nstts; uint8_t *ctts; uint32_t nctts; bool ctts_v1; uint8_t *stsc; uint32_t nstsc;
                 uint32_t stsz_fixed; const uint8_t *stsz; uint32_t nstsz; int stz2_bits; const uint8_t *stco; uint32_t nstco; bool co64;
                 const uint8_t *stss; uint32_t nstss; uint32_t mv_timescale; } mp4_trak;

static const char *lang_of(uint16_t packed, char out[4])
{
    if (packed == 0 || packed >= 0x8000) { strcpy(out, "und"); return out; }
    out[0] = (char)(((packed >> 10) & 0x1f) + 0x60); out[1] = (char)(((packed >> 5) & 0x1f) + 0x60); out[2] = (char)((packed & 0x1f) + 0x60); out[3] = 0;
    return out;
}

/* MPEG-4 descriptor sizes: 1-4 bytes, 7 bits each, top bit = more */
static uint32_t desc_size(const uint8_t **p, const uint8_t *end)
{
    uint32_t n = 0;
    for (int i = 0; i < 4 && *p < end; i++) { uint8_t b = *(*p)++; n = n << 7 | (b & 0x7f); if (!(b & 0x80)) break; }
    return n;
}

static void parse_asc(dmx_track *t, const uint8_t *asc, size_t n)
{
    static const int rates[16] = { 96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350, 0, 0, 0 };
    if (n < 2) return;
    uint32_t bits = (uint32_t)asc[0] << 24 | (uint32_t)asc[1] << 16 | (n > 2 ? (uint32_t)asc[2] << 8 : 0) | (n > 3 ? asc[3] : 0);
    int pos = 0, aot = (int)(bits >> 27); pos += 5;
    if (aot == 31) { aot = 32 + (int)((bits >> (32 - pos - 6)) & 0x3f); pos += 6; }
    int sfi = (int)((bits >> (32 - pos - 4)) & 0xf); pos += 4;
    int rate = rates[sfi];
    if (sfi == 15) { if (n < 5) return; uint64_t w = (uint64_t)be32(asc) << 8 | asc[4]; rate = (int)((w >> (40 - pos - 24)) & 0xffffff); pos += 24; }
    uint64_t w = n >= 5 ? (uint64_t)be32(asc) << 8 | asc[4] : (uint64_t)bits << 8;
    int ch = (int)((w >> (40 - pos - 4)) & 0xf);
    if (rate) t->rate = rate;
    if (ch) t->channels = ch == 7 ? 8 : ch;
    t->aac_profile = aot;
}

static void parse_esds(dmx_track *t, const uint8_t *p, const uint8_t *end)
{
    if (end - p < 4) return;
    p += 4;                                                         /* version/flags */
    if (p >= end || *p++ != 0x03) return;
    desc_size(&p, end);
    if (end - p < 3) return;
    uint8_t flags = p[2]; p += 3;
    if (flags & 0x80) p += 2;
    if (flags & 0x40) { if (p < end) p += 1 + *p; }
    if (flags & 0x20) p += 2;
    if (p >= end || *p++ != 0x04) return;
    desc_size(&p, end);
    if (end - p < 13) return;
    uint8_t oti = p[0]; uint32_t avg = be32(p + 9); p += 13;
    if (oti == 0x40 || oti == 0x66 || oti == 0x67 || oti == 0x68) { t->codec = DMX_AAC; t->mime = "audio/mp4a-latm"; }
    else if (oti == 0x69 || oti == 0x6b) { t->codec = DMX_MP3; t->mime = "audio/mpeg"; }
    else if (oti == 0x20) { t->codec = DMX_MPEG4V; t->mime = "video/mp4v-es"; }
    if (avg) t->bitrate = (int)avg;
    if (p >= end || *p++ != 0x05) return;
    uint32_t n = desc_size(&p, end);
    if (n > (uint32_t)(end - p)) n = (uint32_t)(end - p);
    if (n) { csd_set(t, 0, p, n); if (t->codec == DMX_AAC) parse_asc(t, p, n); }
}

static const uint8_t k_startcode[4] = { 0, 0, 0, 1 };
static void parse_avcc(dmx_track *t, const uint8_t *p, const uint8_t *end)
{
    if (end - p < 7) return;
    t->nal_len = (p[4] & 3) + 1;
    int nsps = p[5] & 0x1f; p += 6;
    for (int i = 0; i < nsps && end - p >= 2; i++) { uint16_t n = be16(p); p += 2; if (n > end - p) return; csd_append(t, 0, k_startcode, 4); csd_append(t, 0, p, n); p += n; }
    if (p >= end) return;
    int npps = *p++;
    for (int i = 0; i < npps && end - p >= 2; i++) { uint16_t n = be16(p); p += 2; if (n > end - p) return; csd_append(t, 1, k_startcode, 4); csd_append(t, 1, p, n); p += n; }
}
static void parse_hvcc(dmx_track *t, const uint8_t *p, const uint8_t *end)
{
    if (end - p < 23) return;
    t->nal_len = (p[21] & 3) + 1;
    int narr = p[22]; p += 23;
    for (int a = 0; a < narr && end - p >= 3; a++) {
        int nnal = be16(p + 1); p += 3;
        for (int i = 0; i < nnal && end - p >= 2; i++) { uint16_t n = be16(p); p += 2; if (n > end - p) return; csd_append(t, 0, k_startcode, 4); csd_append(t, 0, p, n); p += n; }
    }
}
/* dOps (big-endian) to the OpusHead Android hands out as csd-0, with the pre-skip and 80 ms seek pre-roll as csd-1 and csd-2 (ns) */
static void parse_dops(dmx_track *t, const uint8_t *p, const uint8_t *end)
{
    if (end - p < 11) return;
    int ch = p[1]; unsigned preskip = be16(p + 2); uint32_t rate = be32(p + 4); unsigned gain = be16(p + 8); int family = p[10];
    uint8_t head[8 + 11 + 2 + 255]; size_t n = 0;
    memcpy(head, "OpusHead", 8); n = 8;
    head[n++] = 1; head[n++] = (uint8_t)ch; put16le(head + n, preskip); n += 2; put32le(head + n, rate); n += 4; put16le(head + n, gain); n += 2; head[n++] = (uint8_t)family;
    if (family && end - p >= 13) { int ns = p[11], nc = p[12]; head[n++] = (uint8_t)ns; head[n++] = (uint8_t)nc; int m = ch < 255 ? ch : 255; if (end - p >= 13 + m) { memcpy(head + n, p + 13, (size_t)m); n += (size_t)m; } }
    csd_set(t, 0, head, n);
    uint8_t ns[8]; uint64_t v = (uint64_t)preskip * 1000000000ull / 48000; for (int i = 0; i < 8; i++) ns[i] = (uint8_t)(v >> (8 * i)); csd_set(t, 1, ns, 8);
    v = 80000000ull; for (int i = 0; i < 8; i++) ns[i] = (uint8_t)(v >> (8 * i)); csd_set(t, 2, ns, 8);
    t->codec = DMX_OPUS; t->mime = "audio/opus"; t->rate = 48000; if (ch) t->channels = ch;
}
static void parse_colr(dmx_track *t, const uint8_t *p, const uint8_t *end)
{
    if (end - p < 11 || memcmp(p, "nclx", 4)) return;
    int prim = be16(p + 4), tr = be16(p + 6);
    t->color_standard = prim == 1 ? 1 : prim == 5 ? 2 : prim == 6 ? 4 : prim == 9 ? 6 : 0;
    t->color_transfer = tr == 1 || tr == 6 ? 3 : tr == 16 ? 6 : tr == 18 ? 7 : tr == 8 ? 1 : 0;
    t->color_range = (p[10] & 0x80) ? 1 : 2;
}

static void parse_stsd(dmx *d, dmx_track *t, const uint8_t *p, const uint8_t *end)
{
    (void)d;
    if (end - p < 16) return;
    const uint8_t *e = p + 8;                                       /* the first entry (others are the same stream in another configuration) */
    uint32_t esz = be32(e); uint32_t type = be32(e + 4);
    if (esz < 16 || esz > (uint32_t)(end - e)) return;
    const uint8_t *eend = e + esz, *kids = NULL;
    switch (type) {
    case FOURCC('a', 'v', 'c', '1'): case FOURCC('a', 'v', 'c', '3'): t->codec = DMX_AVC; t->mime = "video/avc"; break;
    case FOURCC('h', 'v', 'c', '1'): case FOURCC('h', 'e', 'v', '1'): t->codec = DMX_HEVC; t->mime = "video/hevc"; break;
    case FOURCC('m', 'p', '4', 'v'): t->codec = DMX_MPEG4V; t->mime = "video/mp4v-es"; break;
    case FOURCC('s', '2', '6', '3'): case FOURCC('h', '2', '6', '3'): t->codec = DMX_H263; t->mime = "video/3gpp"; break;
    case FOURCC('m', 'p', '4', 'a'): t->codec = DMX_AAC; t->mime = "audio/mp4a-latm"; break;
    case FOURCC('O', 'p', 'u', 's'): t->codec = DMX_OPUS; t->mime = "audio/opus"; break;
    case FOURCC('.', 'm', 'p', '3'): case FOURCC('m', 'p', '3', ' '): t->codec = DMX_MP3; t->mime = "audio/mpeg"; break;
    case FOURCC('s', 'a', 'm', 'r'): t->codec = DMX_AMR_NB; t->mime = "audio/3gpp"; t->rate = 8000; t->channels = 1; break;
    case FOURCC('s', 'a', 'w', 'b'): t->codec = DMX_AMR_WB; t->mime = "audio/amr-wb"; t->rate = 16000; t->channels = 1; break;
    case FOURCC('f', 'L', 'a', 'C'): t->codec = DMX_FLAC; t->mime = "audio/flac"; break;
    case FOURCC('t', 'w', 'o', 's'): case FOURCC('s', 'o', 'w', 't'): case FOURCC('l', 'p', 'c', 'm'): case FOURCC('r', 'a', 'w', ' '): t->codec = DMX_PCM; t->mime = "audio/raw"; break;
    default: t->codec = DMX_OTHER; break;
    }
    if (t->type == DMX_VIDEO) {
        if (esz < 86) return;
        t->width = be16(e + 32); t->height = be16(e + 34);
        kids = e + 86;
    } else {
        if (esz < 36) return;
        int version = be16(e + 16);
        t->channels = be16(e + 24); t->bits = be16(e + 26); t->rate = (int)(be32(e + 32) >> 16);
        kids = e + 36;
        if (version == 1) kids += 16;
        else if (version == 2 && esz >= 72) { union { uint64_t u; double f; } r; r.u = be64(e + 44); t->rate = (int)r.f; t->channels = (int)be32(e + 52); t->bits = (int)be32(e + 60); kids = e + 72; }
        if (t->codec == DMX_PCM) { if (!t->bits) t->bits = 16; }
    }
    while (kids && eend - kids >= 8) {
        uint32_t n = be32(kids), kt = be32(kids + 4);
        if (n < 8 || n > (uint32_t)(eend - kids)) break;
        const uint8_t *b = kids + 8, *bend = kids + n;
        if (kt == FOURCC('a', 'v', 'c', 'C')) parse_avcc(t, b, bend);
        else if (kt == FOURCC('h', 'v', 'c', 'C')) parse_hvcc(t, b, bend);
        else if (kt == FOURCC('e', 's', 'd', 's')) parse_esds(t, b, bend);
        else if (kt == FOURCC('d', 'O', 'p', 's')) parse_dops(t, b, bend);
        else if (kt == FOURCC('c', 'o', 'l', 'r')) parse_colr(t, b, bend);
        else if (kt == FOURCC('p', 'a', 's', 'p')) {}
        else if (kt == FOURCC('w', 'a', 'v', 'e')) { kids = b; continue; }     /* QuickTime wraps esds in wave */
        kids = bend;
    }
}

/* the sample tables into one flat table: chunks by stsc, offsets by stco, sizes by stsz, times by stts/ctts, sync by stss */
static bool build_samples(dmx_track *t, mp4_trak *m)
{
    if (!m->timescale || !m->nstsc || !m->nstco || (!m->stsz_fixed && !m->nstsz)) return false;
    uint32_t total = m->stsz_fixed ? 0 : m->nstsz;
    if (m->stsz_fixed) { for (uint32_t i = 0; i < m->nstts; i++) total += m->stts[2 * i]; }
    if (total > (64u << 20)) return false;
    t->s = malloc(sizeof(*t->s) * (total ? total : 1)); t->cap = total; t->n = 0;
    if (!t->s) return false;
    uint32_t si = 0;                                                /* sample index */
    for (uint32_t ci = 0; ci < m->nstsc && si < total; ci++) {
        uint32_t first = be32(m->stsc + 12 * ci), per = be32(m->stsc + 12 * ci + 4);
        uint32_t next = ci + 1 < m->nstsc ? be32(m->stsc + 12 * (ci + 1)) : m->nstco + 1;
        if (first < 1 || next <= first || per == 0) continue;
        for (uint32_t ch = first; ch < next && ch <= m->nstco && si < total; ch++) {
            uint64_t off = m->co64 ? be64(m->stco + 8 * (ch - 1)) : be32(m->stco + 4 * (ch - 1));
            for (uint32_t k = 0; k < per && si < total; k++, si++) {
                uint32_t size = m->stsz_fixed ? m->stsz_fixed
                              : m->stz2_bits == 4 ? (m->stsz[si / 2] >> (si & 1 ? 0 : 4)) & 0xf
                              : m->stz2_bits == 8 ? m->stsz[si] : m->stz2_bits == 16 ? be16(m->stsz + 2 * si) : be32(m->stsz + 4 * si);
                dmx_sample *s = sample_add(t);
                s->off = off; s->size = size; s->sync = m->nstss == 0;
                off += size;
            }
        }
    }
    t->n = si < total ? si : total;
    /* times */
    int64_t dts = 0; uint32_t k = 0, ci = 0, cleft = 0; int64_t coff = 0;
    int64_t shift = m->media_time > 0 ? m->media_time : 0, empty = m->mv_timescale ? m->empty_mv * (int64_t)m->timescale / (int64_t)m->mv_timescale : 0;
    for (uint32_t ei = 0; ei < m->nstts && k < t->n; ei++) {
        uint32_t cnt = m->stts[2 * ei], delta = m->stts[2 * ei + 1];
        for (uint32_t i = 0; i < cnt && k < t->n; i++, k++) {
            if (!cleft && ci < m->nctts) { cleft = be32(m->ctts + 8 * ci); coff = m->ctts_v1 ? (int32_t)be32(m->ctts + 8 * ci + 4) : (int64_t)be32(m->ctts + 8 * ci + 4); ci++; }
            int64_t cts = dts + (cleft ? coff : 0) - shift + empty;
            if (cleft) cleft--;
            t->s[k].pts = cts * 1000000 / (int64_t)m->timescale;
            dts += delta;
        }
    }
    for (; k < t->n; k++) t->s[k].pts = (dts - shift + empty) * 1000000 / (int64_t)m->timescale;
    for (uint32_t i = 0; i < m->nstss; i++) { uint32_t n = be32(m->stss + 4 * i); if (n >= 1 && n <= t->n) t->s[n - 1].sync = 1; }
    if (m->duration > 0) t->duration_us = m->duration * 1000000 / (int64_t)m->timescale;
    return t->n > 0;
}

static void parse_ilst(dmx *d, const uint8_t *p, const uint8_t *end)
{
    while (end - p >= 8) {
        uint32_t n = be32(p), key = be32(p + 4);
        if (n < 8 || n > (uint32_t)(end - p)) break;
        const uint8_t *q = p + 8, *qend = p + n;
        if (qend - q >= 16 && be32(q + 4) == FOURCC('d', 'a', 't', 'a')) {
            uint32_t dn = be32(q); if (dn > (uint32_t)(qend - q)) dn = (uint32_t)(qend - q);
            uint32_t dtype = be32(q + 8) & 0xffffff; const char *v = (const char *)q + 16; size_t vn = dn >= 16 ? dn - 16 : 0;
            switch (key) {
            case 0xa96e616d: meta_set(d, DMX_M_TITLE, v, vn); break;                     /* ©nam */
            case 0xa9415254: meta_set(d, DMX_M_ARTIST, v, vn); break;                    /* ©ART */
            case 0xa9616c62: meta_set(d, DMX_M_ALBUM, v, vn); break;                     /* ©alb */
            case FOURCC('a', 'A', 'R', 'T'): meta_set(d, DMX_M_ALBUMARTIST, v, vn); break;
            case 0xa9617574: meta_set(d, DMX_M_AUTHOR, v, vn); break;                    /* ©aut */
            case 0xa9777274: meta_set(d, DMX_M_COMPOSER, v, vn); meta_set(d, DMX_M_WRITER, v, vn); break;   /* ©wrt */
            case 0xa9646179: meta_set(d, DMX_M_DATE, v, vn); if (vn >= 4) meta_set(d, DMX_M_YEAR, v, 4); break;   /* ©day */
            case 0xa967656e: meta_set(d, DMX_M_GENRE, v, vn); break;                     /* ©gen */
            case 0xa978797a: meta_set(d, DMX_M_LOCATION, v, vn); break;                  /* ©xyz */
            case FOURCC('t', 'r', 'k', 'n'): if (vn >= 4) { char b[24]; snprintf(b, sizeof(b), vn >= 6 && be16(q + 20) ? "%u/%u" : "%u", be16(q + 18), vn >= 6 ? be16(q + 20) : 0); meta_set(d, DMX_M_TRACK, b, strlen(b)); } break;
            case FOURCC('d', 'i', 's', 'k'): if (vn >= 4) { char b[24]; snprintf(b, sizeof(b), vn >= 6 && be16(q + 20) ? "%u/%u" : "%u", be16(q + 18), vn >= 6 ? be16(q + 20) : 0); meta_set(d, DMX_M_DISC, b, strlen(b)); } break;
            case FOURCC('c', 'p', 'i', 'l'): if (vn >= 1) meta_set(d, DMX_M_COMPILATION, v[0] ? "1" : "0", 1); break;
            case FOURCC('c', 'o', 'v', 'r'): if (vn && !d->pic && (dtype == 13 || dtype == 14 || dtype == 0)) { d->pic = malloc(vn); memcpy(d->pic, v, vn); d->pic_n = vn; } break;
            case FOURCC('-', '-', '-', '-'): {                                           /* com.android.capture.fps: a float */
                const uint8_t *nm = q; bool fps = false;
                while (qend - nm >= 8) { uint32_t nn = be32(nm); if (nn < 8 || nn > (uint32_t)(qend - nm)) break; if (be32(nm + 4) == FOURCC('n', 'a', 'm', 'e') && nn >= 12 + 23 && !memcmp(nm + 12, "com.android.capture.fps", 23)) fps = true; nm += nn; }
                if (fps && vn >= 4 && dtype == 23) { union { uint32_t u; float f; } r; r.u = be32((const uint8_t *)v); char b[32]; snprintf(b, sizeof(b), "%g", (double)r.f); meta_set(d, DMX_M_CAPTURE_FPS, b, strlen(b)); }
                break; }
            default: break;
            }
        }
        p = qend;
    }
}

static void parse_meta(dmx *d, const uint8_t *p, const uint8_t *end)
{
    if (end - p >= 8 && be32(p + 4) != FOURCC('h', 'd', 'l', 'r')) p += 4;            /* ISO meta is a full box; QuickTime's is not */
    while (end - p >= 8) {
        uint32_t n = be32(p), type = be32(p + 4);
        if (n < 8 || n > (uint32_t)(end - p)) break;
        if (type == FOURCC('i', 'l', 's', 't')) parse_ilst(d, p + 8, p + n);
        p += n;
    }
}

static void parse_trak(dmx *d, const uint8_t *p, const uint8_t *end, uint32_t mv_timescale);
static void mp4_walk(dmx *d, const uint8_t *p, const uint8_t *end, dmx_track *t, mp4_trak *m, int depth)
{
    while (end - p >= 8) {
        uint64_t n = be32(p); uint32_t type = be32(p + 4); const uint8_t *b = p + 8;
        if (n == 1) { if (end - p < 16) break; n = be64(p + 8); b = p + 16; }
        if (n == 0) n = (uint64_t)(end - p);
        if (n < (uint64_t)(b - p) || n > (uint64_t)(end - p)) break;
        const uint8_t *bend = p + n;
        switch (type) {
        case FOURCC('m', 'v', 'h', 'd'):
            if (bend - b >= 20) { int v = b[0]; uint32_t ts = v ? be32(b + 20) : be32(b + 12); int64_t dur = v ? (int64_t)be64(b + 24) : be32(b + 16);
                if (ts) { m->mv_timescale = ts; if (dur > 0 && (uint64_t)dur != (v ? ~0ull : 0xffffffffull)) d->duration_us = dur * 1000000 / ts; }
                uint64_t created = v ? be64(b + 4) : be32(b + 4);
                if (created > 2082844800ull) { time_t tt = (time_t)(created - 2082844800ull); struct tm tm; char buf[40]; if (gmtime_r(&tt, &tm)) { strftime(buf, sizeof(buf), "%Y%m%dT%H%M%S.000Z", &tm); meta_set(d, DMX_M_DATE, buf, strlen(buf)); } } }
            break;
        case FOURCC('t', 'r', 'a', 'k'): parse_trak(d, b, bend, m->mv_timescale); break;
        case FOURCC('u', 'd', 't', 'a'): mp4_walk(d, b, bend, t, m, depth + 1); break;
        case FOURCC('m', 'e', 't', 'a'): parse_meta(d, b, bend); break;
        case FOURCC('t', 'k', 'h', 'd'):
            if (t && bend - b >= 84) { int v = b[0]; const uint8_t *mx = b + (v ? 48 : 36);
                if (bend - mx >= 36 + 8) { int32_t a = (int32_t)be32(mx), bb = (int32_t)be32(mx + 4), c = (int32_t)be32(mx + 12), dd = (int32_t)be32(mx + 16);
                    int r = 0; if (a == 0 && dd == 0) r = (bb > 0 && c < 0) ? 90 : (bb < 0 && c > 0) ? 270 : 0; else if (a < 0 && dd < 0) r = 180;
                    m->rotation = r; m->tkhd_w = (int32_t)(be32(mx + 36) >> 16); m->tkhd_h = (int32_t)(be32(mx + 40) >> 16); } }
            break;
        case FOURCC('e', 'd', 't', 's'): mp4_walk(d, b, bend, t, m, depth + 1); break;
        case FOURCC('e', 'l', 's', 't'):
            if (t && bend - b >= 8) { int v = b[0]; uint32_t cnt = be32(b + 4); const uint8_t *e = b + 8; int esz = v ? 20 : 12; m->have_elst = true;
                for (uint32_t i = 0; i < cnt && bend - e >= esz; i++, e += esz) {
                    int64_t segdur = v ? (int64_t)be64(e) : be32(e), mt = v ? (int64_t)be64(e + 8) : (int32_t)be32(e + 4);
                    if (i == 0 && mt == -1) m->empty_mv = segdur;
                    else if (mt >= 0 && !m->media_time) { m->media_time = mt; break; }
                } }
            break;
        case FOURCC('m', 'd', 'i', 'a'): case FOURCC('m', 'i', 'n', 'f'): case FOURCC('s', 't', 'b', 'l'): mp4_walk(d, b, bend, t, m, depth + 1); break;
        case FOURCC('m', 'd', 'h', 'd'):
            if (t && bend - b >= 24) { int v = b[0]; m->timescale = v ? be32(b + 20) : be32(b + 12); m->duration = v ? (int64_t)be64(b + 24) : be32(b + 16);
                if (v ? (uint64_t)m->duration == ~0ull : m->duration == 0xffffffffll) m->duration = 0;
                lang_of(be16(b + (v ? 32 : 20)), t->lang); }
            break;
        case FOURCC('h', 'd', 'l', 'r'):
            if (t && bend - b >= 12) { uint32_t h = be32(b + 8); t->type = h == FOURCC('v', 'i', 'd', 'e') ? DMX_VIDEO : h == FOURCC('s', 'o', 'u', 'n') ? DMX_AUDIO : 0; }
            break;
        case FOURCC('s', 't', 's', 'd'): if (t) parse_stsd(d, t, b, bend); break;
        case FOURCC('s', 't', 't', 's'):
            if (t && bend - b >= 8) { uint32_t cnt = be32(b + 4); if (cnt > (uint32_t)(bend - b - 8) / 8) cnt = (uint32_t)(bend - b - 8) / 8;
                m->stts = malloc(cnt * 8 + 8); m->nstts = cnt; for (uint32_t i = 0; i < cnt; i++) { m->stts[2 * i] = be32(b + 8 + 8 * i); m->stts[2 * i + 1] = be32(b + 12 + 8 * i); } }
            break;
        case FOURCC('c', 't', 't', 's'):
            if (t && bend - b >= 8) { uint32_t cnt = be32(b + 4); if (cnt > (uint32_t)(bend - b - 8) / 8) cnt = (uint32_t)(bend - b - 8) / 8; m->ctts = (uint8_t *)b + 8; m->nctts = cnt; m->ctts_v1 = b[0] == 1; }
            break;
        case FOURCC('s', 't', 's', 'c'):
            if (t && bend - b >= 8) { uint32_t cnt = be32(b + 4); if (cnt > (uint32_t)(bend - b - 8) / 12) cnt = (uint32_t)(bend - b - 8) / 12; m->stsc = (uint8_t *)b + 8; m->nstsc = cnt; }
            break;
        case FOURCC('s', 't', 's', 'z'):
            if (t && bend - b >= 12) { m->stsz_fixed = be32(b + 4); uint32_t cnt = be32(b + 8); if (!m->stsz_fixed) { if (cnt > (uint32_t)(bend - b - 12) / 4) cnt = (uint32_t)(bend - b - 12) / 4; m->stsz = b + 12; m->nstsz = cnt; m->stz2_bits = 32; } }
            break;
        case FOURCC('s', 't', 'z', '2'):
            if (t && bend - b >= 12) { int bits = b[7]; uint32_t cnt = be32(b + 8); if (bits == 4 || bits == 8 || bits == 16) { uint32_t maxc = (uint32_t)((bend - b - 12) * 8 / bits); if (cnt > maxc) cnt = maxc; m->stsz = b + 12; m->nstsz = cnt; m->stz2_bits = bits; m->stsz_fixed = 0; } }
            break;
        case FOURCC('s', 't', 'c', 'o'): case FOURCC('c', 'o', '6', '4'):
            if (t && bend - b >= 8) { m->co64 = type == FOURCC('c', 'o', '6', '4'); uint32_t cnt = be32(b + 4), esz = m->co64 ? 8 : 4; if (cnt > (uint32_t)(bend - b - 8) / esz) cnt = (uint32_t)(bend - b - 8) / esz; m->stco = b + 8; m->nstco = cnt; }
            break;
        case FOURCC('s', 't', 's', 's'):
            if (t && bend - b >= 8) { uint32_t cnt = be32(b + 4); if (cnt > (uint32_t)(bend - b - 8) / 4) cnt = (uint32_t)(bend - b - 8) / 4; m->stss = b + 8; m->nstss = cnt; }
            break;
        default: break;
        }
        p = bend;
    }
}

static void parse_trak(dmx *d, const uint8_t *p, const uint8_t *end, uint32_t mv_timescale)
{
    dmx_track *t = track_add(d, 0);
    mp4_trak m; memset(&m, 0, sizeof(m)); m.mv_timescale = mv_timescale;
    mp4_walk(d, p, end, t, &m, 1);
    t = &d->t[d->nt - 1];                                           /* track_add may have moved the array */
    bool ok = (t->type == DMX_VIDEO || t->type == DMX_AUDIO) && t->codec != DMX_NONE && build_samples(t, &m);
    free(m.stts);
    if (!ok) { for (int i = 0; i < 3; i++) free(t->csd[i]); free(t->s); d->nt--; return; }
    t->rotation = m.rotation;
    if (t->type == DMX_VIDEO && (!t->width || !t->height) && m.tkhd_w > 0 && m.tkhd_h > 0) { t->width = m.tkhd_w; t->height = m.tkhd_h; }
    track_finish(d, t);
}

static bool open_mp4(dmx *d)
{
    uint64_t off = 0; bool any = false;
    uint8_t hdr[16];
    while (off + 8 <= d->len) {
        if (dmx_read_at(d, off, hdr, 16) < 8) break;
        uint64_t n = be32(hdr); uint32_t type = be32(hdr + 4); size_t hs = 8;
        if (n == 1) { n = be64(hdr + 8); hs = 16; }
        if (n == 0) n = d->len - off;
        if (n < hs) break;
        if (type == FOURCC('m', 'o', 'o', 'v') && n - hs <= (256u << 20)) {
            uint8_t *moov = dmx_slurp(d, off + hs, (size_t)(n - hs));
            if (moov) { mp4_trak m; memset(&m, 0, sizeof(m)); mp4_walk(d, moov, moov + (n - hs), NULL, &m, 0); free(moov); any = true; }
        }
        off += n;
    }
    if (!any || !d->nt) return false;
    bool video = false; for (int i = 0; i < d->nt; i++) video |= d->t[i].type == DMX_VIDEO;
    d->mime = video ? "video/mp4" : "audio/mp4";
    return true;
}

/* --------------------------------------------------------------------------------------------------------------------- Ogg */

static int opus_frames_48k(const uint8_t *p, size_t n)
{
    if (!n) return 0;
    int cfg = p[0] >> 3, code = p[0] & 3, size;
    if (cfg < 12) size = (const int[]){ 480, 960, 1920, 2880 }[cfg & 3];
    else if (cfg < 16) size = (const int[]){ 480, 960 }[cfg & 1];
    else size = (const int[]){ 120, 240, 480, 960 }[cfg & 3];
    int count = code == 0 ? 1 : code < 3 ? 2 : n > 1 ? (p[1] & 0x3f) : 0;
    return size * count;
}

static void vorbis_comments(dmx *d, const uint8_t *p, const uint8_t *end)
{
    if (end - p < 8) return;
    uint32_t vl = le32(p); p += 4; if (vl > (uint32_t)(end - p)) return; p += vl;
    uint32_t cnt = le32(p); p += 4;
    for (uint32_t i = 0; i < cnt && end - p >= 4; i++) {
        uint32_t l = le32(p); p += 4; if (l > (uint32_t)(end - p)) break;
        const char *s = (const char *)p, *eq = memchr(s, '=', l); p += l;
        if (!eq) continue;
        size_t kl = (size_t)(eq - s), vn = l - kl - 1; const char *v = eq + 1;
        char key[32]; if (kl >= sizeof(key)) continue;
        for (size_t k = 0; k < kl; k++) key[k] = (char)((s[k] >= 'a' && s[k] <= 'z') ? s[k] - 32 : s[k]); key[kl] = 0;
        if (!strcmp(key, "TITLE")) meta_set(d, DMX_M_TITLE, v, vn);
        else if (!strcmp(key, "ARTIST")) meta_set(d, DMX_M_ARTIST, v, vn);
        else if (!strcmp(key, "ALBUM")) meta_set(d, DMX_M_ALBUM, v, vn);
        else if (!strcmp(key, "ALBUMARTIST")) meta_set(d, DMX_M_ALBUMARTIST, v, vn);
        else if (!strcmp(key, "COMPOSER")) meta_set(d, DMX_M_COMPOSER, v, vn);
        else if (!strcmp(key, "DATE")) { meta_set(d, DMX_M_DATE, v, vn); if (vn >= 4) meta_set(d, DMX_M_YEAR, v, 4); }
        else if (!strcmp(key, "GENRE")) meta_set(d, DMX_M_GENRE, v, vn);
        else if (!strcmp(key, "TRACKNUMBER")) meta_set(d, DMX_M_TRACK, v, vn);
        else if (!strcmp(key, "DISCNUMBER")) meta_set(d, DMX_M_DISC, v, vn);
        else if (!strcmp(key, "METADATA_BLOCK_PICTURE") && !d->pic) {
            /* base64 of a FLAC picture block: type(4) mime len(4) mime desc len(4) desc w h depth colors len(4) data */
            uint8_t *raw = malloc(vn); size_t rn = 0; uint32_t acc = 0; int bits = 0;
            for (size_t k = 0; k < vn; k++) { char c = v[k]; int x = c >= 'A' && c <= 'Z' ? c - 'A' : c >= 'a' && c <= 'z' ? c - 'a' + 26 : c >= '0' && c <= '9' ? c - '0' + 52 : c == '+' ? 62 : c == '/' ? 63 : -1;
                if (x < 0) continue; acc = acc << 6 | (uint32_t)x; bits += 6; if (bits >= 8) { bits -= 8; raw[rn++] = (uint8_t)(acc >> bits); } }
            if (rn >= 32) { const uint8_t *q = raw, *qe = raw + rn; uint32_t ml = be32(q + 4); q += 8; if (ml <= (uint32_t)(qe - q)) { q += ml; if (qe - q >= 4) { uint32_t dl = be32(q); q += 4; if (dl <= (uint32_t)(qe - q)) { q += dl + 16; if (qe - q >= 4) { uint32_t pl = be32(q); q += 4; if (pl <= (uint32_t)(qe - q) && pl) { d->pic = malloc(pl); memcpy(d->pic, q, pl); d->pic_n = pl; } } } } } }
            free(raw);
        }
    }
}

static bool open_ogg(dmx *d)
{
    enum { BUF = 1 << 16 };
    uint8_t *buf = malloc(BUF); uint64_t off = 0;
    dmx_track *t = NULL; uint32_t serial = 0; int npk = 0;
    uint8_t *pk = NULL; size_t pkn = 0, pkcap = 0;                 /* the header packets, assembled */
    uint32_t first_piece = 0, npiece = 0; bool continued = false;
    int64_t granule_pos = -1; uint32_t page_first = 0; int preskip = 0; bool opus = false, vorbis = false;
    while (off + 27 <= d->len) {
        uint8_t h[27 + 255];
        if (dmx_read_at(d, off, h, 27) < 27) break;
        if (memcmp(h, "OggS", 4)) { off++; continue; }                 /* resync */
        int nseg = h[26]; int64_t granule = (int64_t)le64(h + 6); uint32_t ser = le32(h + 14); bool bos = h[5] & 2;
        if (dmx_read_at(d, off + 27, h + 27, (size_t)nseg) < (size_t)nseg) break;
        uint64_t data = off + 27 + (uint64_t)nseg, pageend = data;
        for (int i = 0; i < nseg; i++) pageend += h[27 + i];
        if (!t && bos) { serial = ser; }
        if (!t || ser == serial) {
            uint64_t p = data; size_t seglen = 0; uint64_t segstart = data;
            for (int i = 0; i < nseg; i++) {
                seglen += h[27 + i]; p += h[27 + i];
                if (h[27 + i] == 255 && i + 1 < nseg) continue;
                bool complete = h[27 + i] < 255;
                /* [segstart, segstart+seglen) is a piece of the current packet */
                if (npk < 3 && !(opus && npk >= 2)) {
                    if (pkn + seglen > pkcap) { pkcap = (pkn + seglen) * 2; pk = realloc(pk, pkcap); }
                    dmx_read_at(d, segstart, pk + pkn, seglen); pkn += seglen;
                    if (complete) {
                        if (npk == 0) {
                            if (pkn >= 19 && !memcmp(pk, "OpusHead", 8)) { opus = true; t = track_add(d, DMX_AUDIO); t->codec = DMX_OPUS; t->mime = "audio/opus"; t->channels = pk[9]; preskip = le16(pk + 10); t->rate = 48000;
                                csd_set(t, 0, pk, pkn); uint8_t ns[8]; uint64_t v = (uint64_t)preskip * 1000000000ull / 48000; for (int k = 0; k < 8; k++) ns[k] = (uint8_t)(v >> (8 * k)); csd_set(t, 1, ns, 8);
                                v = 80000000ull; for (int k = 0; k < 8; k++) ns[k] = (uint8_t)(v >> (8 * k)); csd_set(t, 2, ns, 8); }
                            else if (pkn >= 30 && !memcmp(pk, "\x01vorbis", 7)) { vorbis = true; t = track_add(d, DMX_AUDIO); t->codec = DMX_VORBIS; t->mime = "audio/vorbis"; t->channels = pk[11]; t->rate = (int)le32(pk + 12); t->bitrate = (int)le32(pk + 20); csd_set(t, 0, pk, pkn); }
                            else { free(pk); free(buf); return false; }
                        } else if (npk == 1) { if (pkn > 8) vorbis_comments(d, pk + (opus ? 8 : 7), pk + pkn); }
                        else if (npk == 2 && vorbis) csd_set(t, 1, pk, pkn);
                        npk++; pkn = 0;
                    }
                } else {
                    if (!continued) { first_piece = d->npieces; npiece = 0; }
                    if (d->npieces == d->cappieces) { d->cappieces = d->cappieces ? d->cappieces * 2 : 4096; d->pieces = realloc(d->pieces, sizeof(*d->pieces) * d->cappieces); }
                    d->pieces[d->npieces++] = (dmx_piece){ segstart, (uint32_t)seglen }; npiece++;
                    if (complete) {
                        dmx_sample *s = sample_add(t);
                        s->off = first_piece; s->pieces = npiece - 1; s->sync = 1;
                        uint32_t total = 0; for (uint32_t k = 0; k < npiece; k++) total += d->pieces[first_piece + k].len;
                        s->size = total; s->pts = -1;
                        if (opus) { uint8_t toc[2] = { 0, 0 }; dmx_read_at(d, d->pieces[first_piece].off, toc, 2); s->pts = opus_frames_48k(toc, d->pieces[first_piece].len >= 2 ? 2 : 1); }   /* duration for now */
                    }
                    continued = !complete;
                }
                segstart = p; seglen = 0;
            }
            /* packet times: Opus from the TOC durations anchored on the first page's granule; Vorbis spread over the page */
            if (t && t->n > page_first) {
                if (opus) {
                    if (granule_pos < 0) { int64_t in_page = 0; for (uint32_t k = page_first; k < t->n; k++) in_page += t->s[k].pts; granule_pos = granule - in_page; if (granule_pos < 0) granule_pos = 0; }
                    for (uint32_t k = page_first; k < t->n; k++) { int64_t dur = t->s[k].pts; int64_t g = granule_pos > preskip ? granule_pos - preskip : 0; t->s[k].pts = g * 1000000 / 48000; granule_pos += dur; }
                } else {
                    int64_t start = granule_pos < 0 ? 0 : granule_pos, endg = granule >= start ? granule : start, cnt = t->n - page_first;
                    for (uint32_t k = page_first; k < t->n; k++) t->s[k].pts = (start + (endg - start) * (int64_t)(k - page_first) / cnt) * 1000000 / t->rate;
                    granule_pos = endg;
                }
                page_first = t->n;
            }
        }
        off = pageend;
    }
    free(pk); free(buf);
    if (!t || !t->n) return false;
    if (granule_pos > 0) t->duration_us = (opus ? (granule_pos > preskip ? granule_pos - preskip : 0) * 1000000 / 48000 : granule_pos * 1000000 / t->rate);
    track_finish(d, t);
    d->mime = "audio/ogg";
    return true;
}

/* --------------------------------------------------------------------------------------------------------------------- MP3 */

static int mp3_frame(const uint8_t *h, int *rate, int *channels, int *spf, int *bitrate)
{
    static const int kbps[2][3][16] = { { { 0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448, 0 }, { 0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384, 0 }, { 0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0 } },
                                        { { 0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256, 0 }, { 0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0 }, { 0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0 } } };
    static const int rates[4][3] = { { 11025, 12000, 8000 }, { 0, 0, 0 }, { 22050, 24000, 16000 }, { 44100, 48000, 32000 } };
    if (h[0] != 0xff || (h[1] & 0xe0) != 0xe0) return 0;
    int ver = (h[1] >> 3) & 3, layer = 4 - ((h[1] >> 1) & 3), bi = h[2] >> 4, ri = (h[2] >> 2) & 3, pad = (h[2] >> 1) & 1;
    if (ver == 1 || layer == 4 || bi == 0 || bi == 15 || ri == 3) return 0;
    int mpeg1 = ver == 3, br = kbps[mpeg1 ? 0 : 1][layer - 1][bi] * 1000, sr = rates[ver][ri];
    *rate = sr; *channels = ((h[3] >> 6) & 3) == 3 ? 1 : 2; *bitrate = br;
    *spf = layer == 1 ? 384 : layer == 2 ? 1152 : mpeg1 ? 1152 : 576;
    if (layer == 1) return (12 * br / sr + pad) * 4;
    return (*spf / 8) * br / sr + pad;
}

static void id3_text(dmx *d, int key, const uint8_t *p, size_t n)
{
    if (!n) return;
    int enc = p[0]; p++; n--;
    char *out = malloc(n * 3 + 1); size_t o = 0;
    if (enc == 0) { for (size_t i = 0; i < n; i++) { unsigned c = p[i]; if (c < 0x80) out[o++] = (char)c; else { out[o++] = (char)(0xc0 | c >> 6); out[o++] = (char)(0x80 | (c & 0x3f)); } } }
    else if (enc == 3) { memcpy(out, p, n); o = n; }
    else { bool le = enc == 1; if (enc == 1 && n >= 2) { if (p[0] == 0xfe && p[1] == 0xff) le = false; p += 2; n -= 2; }
        for (size_t i = 0; i + 1 < n; i += 2) { unsigned c = le ? (unsigned)(p[i] | p[i + 1] << 8) : (unsigned)(p[i] << 8 | p[i + 1]);
            if (c >= 0xd800 && c < 0xdc00 && i + 3 < n) { unsigned c2 = le ? (unsigned)(p[i + 2] | p[i + 3] << 8) : (unsigned)(p[i + 2] << 8 | p[i + 3]); c = 0x10000 + ((c - 0xd800) << 10) + (c2 - 0xdc00); i += 2; }
            if (c < 0x80) out[o++] = (char)c; else if (c < 0x800) { out[o++] = (char)(0xc0 | c >> 6); out[o++] = (char)(0x80 | (c & 0x3f)); }
            else if (c < 0x10000) { out[o++] = (char)(0xe0 | c >> 12); out[o++] = (char)(0x80 | ((c >> 6) & 0x3f)); out[o++] = (char)(0x80 | (c & 0x3f)); }
            else { out[o++] = (char)(0xf0 | c >> 18); out[o++] = (char)(0x80 | ((c >> 12) & 0x3f)); out[o++] = (char)(0x80 | ((c >> 6) & 0x3f)); out[o++] = (char)(0x80 | (c & 0x3f)); } } }
    out[o] = 0;
    meta_set(d, key, out, strlen(out));
    if (key == DMX_M_DATE && o >= 4) meta_set(d, DMX_M_YEAR, out, 4);
    free(out);
}
static size_t parse_id3(dmx *d, const uint8_t *p, size_t n)       /* the tag's size, 0 when there is none */
{
    if (n < 10 || memcmp(p, "ID3", 3)) return 0;
    int ver = p[3]; size_t size = (size_t)(p[6] & 0x7f) << 21 | (size_t)(p[7] & 0x7f) << 14 | (size_t)(p[8] & 0x7f) << 7 | (p[9] & 0x7f);
    size_t total = 10 + size + ((p[5] & 0x10) ? 10 : 0);
    if (ver < 3 || total > n) return total;
    const uint8_t *q = p + 10, *end = p + 10 + size;
    if (p[5] & 0x40) { if (end - q < 4) return total; q += ver == 4 ? ((size_t)(q[0] & 0x7f) << 21 | (size_t)(q[1] & 0x7f) << 14 | (size_t)(q[2] & 0x7f) << 7 | (q[3] & 0x7f)) : be32(q) + 4; }
    while (end - q >= 10 && q[0]) {
        size_t fn = ver == 4 ? ((size_t)(q[4] & 0x7f) << 21 | (size_t)(q[5] & 0x7f) << 14 | (size_t)(q[6] & 0x7f) << 7 | (q[7] & 0x7f)) : be32(q + 4);
        const uint8_t *f = q + 10; int flags = q[9];
        if (fn > (size_t)(end - f)) break;
        if (!(flags & (ver == 4 ? 0x0c : 0xc0))) {                  /* not compressed or encrypted */
            if (!memcmp(q, "TIT2", 4)) id3_text(d, DMX_M_TITLE, f, fn);
            else if (!memcmp(q, "TPE1", 4)) id3_text(d, DMX_M_ARTIST, f, fn);
            else if (!memcmp(q, "TPE2", 4)) id3_text(d, DMX_M_ALBUMARTIST, f, fn);
            else if (!memcmp(q, "TALB", 4)) id3_text(d, DMX_M_ALBUM, f, fn);
            else if (!memcmp(q, "TCOM", 4)) id3_text(d, DMX_M_COMPOSER, f, fn);
            else if (!memcmp(q, "TEXT", 4)) id3_text(d, DMX_M_WRITER, f, fn);
            else if (!memcmp(q, "TCON", 4)) id3_text(d, DMX_M_GENRE, f, fn);
            else if (!memcmp(q, "TRCK", 4)) id3_text(d, DMX_M_TRACK, f, fn);
            else if (!memcmp(q, "TPOS", 4)) id3_text(d, DMX_M_DISC, f, fn);
            else if (!memcmp(q, "TYER", 4) || !memcmp(q, "TDRC", 4)) id3_text(d, DMX_M_DATE, f, fn);
            else if (!memcmp(q, "TCMP", 4)) id3_text(d, DMX_M_COMPILATION, f, fn);
            else if (!memcmp(q, "APIC", 4) && fn > 4 && !d->pic) {
                const uint8_t *a = f + 1, *ae = f + fn; while (a < ae && *a) a++; if (a < ae) a++;     /* mime */
                if (a < ae) { a++; if (f[0] == 1 || f[0] == 2) { while (ae - a >= 2 && (a[0] || a[1])) a += 2; a += 2; } else { while (a < ae && *a) a++; a++; } }   /* type, description */
                if (a < ae) { d->pic_n = (size_t)(ae - a); d->pic = malloc(d->pic_n); memcpy(d->pic, a, d->pic_n); }
            }
        }
        q = f + fn;
    }
    return total;
}

static bool open_mp3(dmx *d, const uint8_t *head, size_t hn)
{
    size_t skip = parse_id3(d, head, hn);
    uint64_t off = skip; int rate = 0, ch = 0, spf = 0, br = 0;
    uint8_t h[64];
    /* the first frame: within the first 64 KB after the tag, and four more must follow it (a sync word is 11 bits, any file has some) */
    bool found = false;
    for (uint64_t lim = off + 65536; off + 4 <= d->len && off < lim && !found; off++) {
        if (dmx_read_at(d, off, h, 4) < 4) return false;
        int n = mp3_frame(h, &rate, &ch, &spf, &br);
        if (n <= 4) continue;
        uint64_t q = off + (uint64_t)n; int k;
        for (k = 0; k < 4; k++) { uint8_t nx[4]; int r2, c2, s2, b2, n2; if (dmx_read_at(d, q, nx, 4) < 4 || (n2 = mp3_frame(nx, &r2, &c2, &s2, &b2)) <= 4 || r2 != rate || s2 != spf || c2 != ch) break; q += (uint64_t)n2; }
        found = k == 4;
        if (!found) rate = 0;
    }
    if (!found) return false;
    off--;
    dmx_track *t = track_add(d, DMX_AUDIO);
    t->codec = DMX_MP3; t->mime = "audio/mpeg"; t->rate = rate; t->channels = ch;
    /* Xing/Info or VBRI: the frame count and whether the first frame is only that header */
    uint32_t xing_frames = 0; bool info_frame = false;
    if (dmx_read_at(d, off, h, 64) == 64) {
        int mpeg1 = ((h[1] >> 3) & 3) == 3, side = mpeg1 ? (ch == 1 ? 17 : 32) : (ch == 1 ? 9 : 17);
        const uint8_t *x = h + 4 + side;
        if (!memcmp(x, "Xing", 4) || !memcmp(x, "Info", 4)) { info_frame = true; if (be32(x + 4) & 1) xing_frames = be32(x + 8); }
        else if (!memcmp(h + 36, "VBRI", 4)) { info_frame = true; xing_frames = be32(h + 36 + 14); }
    }
    int64_t frames = 0;
    uint8_t *chunk = malloc(1 << 20);
    uint64_t p = off; bool first = true;
    while (p + 4 <= d->len) {
        size_t got = dmx_read_at(d, p, chunk, 1 << 20); size_t i = 0;
        if (got < 4) break;
        while (i + 4 <= got) {
            int r2, c2, s2, b2, n = mp3_frame(chunk + i, &r2, &c2, &s2, &b2);
            if (n <= 4 || r2 != rate) { i++; continue; }
            if ((uint64_t)n > got - i && got == (1u << 20)) break;    /* straddles the chunk: read on from here */
            if (first && info_frame) { first = false; i += (size_t)n; continue; }
            first = false;
            dmx_sample *s = sample_add(t);
            s->off = p + i; s->size = (uint32_t)((uint64_t)n <= got - i ? (size_t)n : got - i); s->sync = 1; s->pts = frames * 1000000 / rate;
            frames += s2; i += (size_t)n;
        }
        if (i == 0) break;
        p += i;
    }
    free(chunk);
    if (!t->n) { d->nt--; return false; }
    t->duration_us = (xing_frames ? (int64_t)xing_frames * spf : frames) * 1000000 / rate;
    track_finish(d, t);
    d->mime = "audio/mpeg";
    return true;
}

/* --------------------------------------------------------------------------------------------------------------------- WAV */

static bool open_wav(dmx *d, const uint8_t *head, size_t hn)
{
    if (hn < 12 || memcmp(head, "RIFF", 4) || memcmp(head + 8, "WAVE", 4)) return false;
    uint64_t off = 12; int fmt = 0, ch = 0, rate = 0, bits = 0, align = 0;
    uint8_t h[64];
    while (off + 8 <= d->len) {
        if (dmx_read_at(d, off, h, 8) < 8) break;
        uint32_t n = le32(h + 4); uint64_t body = off + 8;
        if (!memcmp(h, "fmt ", 4) && n >= 16 && dmx_read_at(d, body, h, 40) >= 16) {
            fmt = le16(h); ch = le16(h + 2); rate = (int)le32(h + 4); align = le16(h + 12); bits = le16(h + 14);
            if (fmt == 0xfffe && n >= 40) fmt = le16(h + 24);
        } else if (!memcmp(h, "data", 4)) {
            if (!rate || !ch || !align || (fmt != 1 && fmt != 3 && fmt != 6 && fmt != 7)) return false;
            uint64_t len = n; if (len > d->len - body) len = d->len - body;
            dmx_track *t = track_add(d, DMX_AUDIO);
            t->codec = DMX_PCM; t->mime = fmt == 6 ? "audio/g711-alaw" : fmt == 7 ? "audio/g711-mlaw" : "audio/raw"; t->rate = rate; t->channels = ch; t->bits = fmt == 3 ? 32 : bits;
            t->bitrate = rate * align * 8;
            uint64_t frames = len / (uint64_t)align, block = 1024, done = 0;
            while (done < frames) {
                uint64_t k = frames - done < block ? frames - done : block;
                dmx_sample *s = sample_add(t);
                s->off = body + done * (uint64_t)align; s->size = (uint32_t)(k * (uint64_t)align); s->sync = 1; s->pts = (int64_t)done * 1000000 / rate;
                done += k;
            }
            t->duration_us = (int64_t)frames * 1000000 / rate;
            track_finish(d, t);
            d->mime = "audio/x-wav";
            return t->n > 0;
        }
        off = body + n + (n & 1);
    }
    return false;
}

/* ---------------------------------------------------------------------------------------------------------------- the API */

static bool dmx_probe(dmx *d)
{
    uint8_t head[4096]; size_t hn = dmx_read_at(d, 0, head, sizeof(head));
    if (hn < 12) return false;
    d->thumb_us = -1; d->cur_track = -1;
    uint32_t t4 = be32(head + 4);
    bool ok = false;
    if (t4 == FOURCC('f', 't', 'y', 'p') || t4 == FOURCC('m', 'o', 'o', 'v') || t4 == FOURCC('m', 'd', 'a', 't') || t4 == FOURCC('f', 'r', 'e', 'e') || t4 == FOURCC('w', 'i', 'd', 'e') || t4 == FOURCC('s', 'k', 'i', 'p')) ok = open_mp4(d);
    else if (!memcmp(head, "OggS", 4)) ok = open_ogg(d);
    else if (!memcmp(head, "RIFF", 4)) ok = open_wav(d, head, hn);
    else ok = open_mp3(d, head, hn);
    if (!ok) return false;
    for (int i = 0; i < d->nt; i++) { dmx_track *t = &d->t[i]; if (t->bitrate) d->bitrate += t->bitrate; }
    /* the frame Android shows for a video when no time is asked: the largest of the first 20 sync samples */
    for (int i = 0; i < d->nt; i++) {
        dmx_track *t = &d->t[i];
        if (t->type != DMX_VIDEO) continue;
        uint32_t best = 0, seen = 0; bool any = false;
        for (uint32_t k = 0; k < t->n && seen < 20; k++) if (t->s[k].sync) { seen++; if (!any || t->s[k].size > t->s[best].size) { best = k; any = true; } }
        if (any) d->thumb_us = t->s[best].pts;
        break;
    }
    return true;
}

static void dmx_close(dmx *d)
{
    if (!d) return;
    if (d->fd >= 0) close(d->fd);
    free(d->mem);
    for (int i = 0; i < d->nt; i++) { for (int k = 0; k < 3; k++) free(d->t[i].csd[k]); free(d->t[i].s); }
    free(d->t);
    for (int i = 0; i < DMX_M_N; i++) free(d->meta[i]);
    free(d->pic); free(d->pieces); free(d->cur_data);
    free(d);
}
/* fd: taken over (closed with the demuxer); length 0: to the end of the file */
static dmx *dmx_open_fd(int fd, uint64_t off, uint64_t len)
{
    struct stat st;
    if (fd < 0) return NULL;
    if (fstat(fd, &st) != 0) { close(fd); return NULL; }
    if (!len || off + len > (uint64_t)st.st_size) len = (uint64_t)st.st_size > off ? (uint64_t)st.st_size - off : 0;
    dmx *d = calloc(1, sizeof(*d));
    d->fd = fd; d->base = off; d->len = len;
    if (!dmx_probe(d)) { dmx_close(d); return NULL; }
    return d;
}
static dmx *dmx_open_mem(uint8_t *mem, size_t len)                /* mem: taken over */
{
    dmx *d = calloc(1, sizeof(*d));
    d->fd = -1; d->mem = mem; d->len = len;
    if (!dmx_probe(d)) { dmx_close(d); return NULL; }
    return d;
}

/* a sample's bytes as MediaExtractor hands them out: Ogg packets reassembled, video as Annex-B */
static bool dmx_read_sample(dmx *d, int ti, uint32_t idx, uint8_t **out, size_t *outn)
{
    if (ti < 0 || ti >= d->nt || idx >= d->t[ti].n) return false;
    dmx_track *t = &d->t[ti]; dmx_sample *s = &t->s[idx];
    uint8_t *b;
    if (d->pieces) {                                                /* Ogg: the packet's pieces, one per page it is on */
        b = malloc(s->size ? s->size : 1); size_t o = 0;
        for (uint32_t k = 0; k <= s->pieces && s->off + k < d->npieces; k++) { dmx_piece *p = &d->pieces[s->off + k]; if (o + p->len > s->size) break; if (dmx_read_at(d, p->off, b + o, p->len) != p->len) { free(b); return false; } o += p->len; }
        *out = b; *outn = o;
        return true;
    }
    b = dmx_slurp(d, s->off, s->size);
    if (!b) return false;
    if (!t->nal_len) { *out = b; *outn = s->size; return true; }
    /* length-prefixed NAL units to start codes */
    size_t cap = s->size + s->size / 10 + 64, o = 0, i = 0;
    uint8_t *ab = malloc(cap);
    while (i + (size_t)t->nal_len <= s->size) {
        uint32_t n = 0; for (int k = 0; k < t->nal_len; k++) n = n << 8 | b[i + (size_t)k];
        i += (size_t)t->nal_len;
        if (n == 0 || n > s->size - i) break;
        if (o + n + 4 > cap) { cap = (o + n + 4) * 2; ab = realloc(ab, cap); }
        memcpy(ab + o, k_startcode, 4); memcpy(ab + o + 4, b + i, n); o += n + 4; i += n;
    }
    free(b);
    *out = ab; *outn = o;
    return true;
}

/* the sample index a seek lands on */
static uint32_t dmx_seek_index(const dmx_track *t, int64_t time_us, int mode)
{
    if (!t->n) return 0;
    uint32_t prev = 0, next = t->n; bool have_prev = false;
    for (uint32_t i = 0; i < t->n; i++) {
        if (!t->s[i].sync) continue;
        if (t->s[i].pts <= time_us) { prev = i; have_prev = true; }
        else { next = i; break; }
    }
    if (mode == DMX_SEEK_CLOSEST) {                                 /* the nearest sample of any kind, for frame grabbing */
        uint32_t best = 0; int64_t bd = INT64_MAX;
        for (uint32_t i = 0; i < t->n; i++) { int64_t dd = t->s[i].pts > time_us ? t->s[i].pts - time_us : time_us - t->s[i].pts; if (dd < bd) { bd = dd; best = i; } }
        return best;
    }
    if (!have_prev) return next < t->n ? next : 0;
    if (mode == DMX_SEEK_NEXT_SYNC) return next;                    /* t->n past the last sync: the track is at its end, as Android's */
    if (next >= t->n) return prev;
    if (mode == DMX_SEEK_CLOSEST_SYNC) return (time_us - t->s[prev].pts) <= (t->s[next].pts - time_us) ? prev : next;
    return prev;
}

static void cur_drop(dmx *d) { free(d->cur_data); d->cur_data = NULL; d->cur_n = 0; d->cur_track = -1; d->cur_eos = false; }
/* the selected track whose next sample is earliest in time becomes current */
static void dmx_pick(dmx *d)
{
    cur_drop(d);
    int best = -1;
    for (int i = 0; i < d->nt; i++) { dmx_track *t = &d->t[i]; if (!t->selected || t->cur >= t->n) continue; if (best < 0 || t->s[t->cur].pts < d->t[best].s[d->t[best].cur].pts) best = i; }
    d->cur_track = best;
    if (best < 0) d->cur_eos = true;
}
static void dmx_select(dmx *d, int ti, bool on)
{
    if (ti < 0 || ti >= d->nt) return;
    d->t[ti].selected = on;
    if (on) { d->t[ti].cur = 0; if (d->cur_track >= 0 && d->cur_track != ti && d->t[d->cur_track].cur > 0) { /* a track picked up mid-stream starts near the current time, as Android's does */
        int64_t now = d->t[d->cur_track].s[d->t[d->cur_track].cur].pts; d->t[ti].cur = dmx_seek_index(&d->t[ti], now, DMX_SEEK_PREVIOUS_SYNC); } }
    dmx_pick(d);
}
static void dmx_seek(dmx *d, int64_t time_us, int mode)
{
    for (int i = 0; i < d->nt; i++) if (d->t[i].selected) d->t[i].cur = dmx_seek_index(&d->t[i], time_us, mode);
    dmx_pick(d);
}
static bool dmx_advance(dmx *d)
{
    if (d->cur_track < 0) return false;
    d->t[d->cur_track].cur++;
    dmx_pick(d);
    return d->cur_track >= 0;
}
static const dmx_sample *dmx_cur(dmx *d) { return d->cur_track >= 0 ? &d->t[d->cur_track].s[d->t[d->cur_track].cur] : NULL; }
static bool dmx_cur_data(dmx *d)
{
    if (d->cur_track < 0) return false;
    if (d->cur_data) return true;
    return dmx_read_sample(d, d->cur_track, d->t[d->cur_track].cur, &d->cur_data, &d->cur_n);
}

#ifndef HUSK_DEMUX_ONLY
/* ======================================================================================================================= natives */

#include "husk-tl-dvm-internal.h"

void tl_log_line(const char *fmt, ...);
typedef struct { uint32_t width, height, stride; int32_t format; uint32_t flags; } tl_abitmap_info;
int tl_AndroidBitmap_getInfo(void *env, void *bitmap, tl_abitmap_info *info);
int tl_AndroidBitmap_lockPixels(void *env, void *bitmap, void **addr);
/* husk-tl-dvm-media.c: the MediaCodec decoders, driven from C */
void *tl_media_open(const char *mime, const uint8_t *const *csd, const size_t *csd_n, int ncsd);
void tl_media_feed(void *h, const uint8_t *d, size_t n, int64_t pts, bool eos);
void *tl_media_take_frame(void *h, int64_t *pts);
bool tl_media_frame_dims(void *f, int *w, int *h);
bool tl_media_frame_draw(void *f, int rotation, uint8_t *rgba, int w, int h, size_t stride);
void tl_media_frame_free(void *f);
void tl_media_close(void *h);

#define NAT(fn) static bool fn(jobj *self, const jvalue *a, jvalue *ret)
static jvalue I(int32_t v) { jvalue r; r.j = (uint32_t)v; return r; }
static jvalue J(int64_t v) { jvalue r; r.j = v; return r; }
static jvalue Z(bool v) { jvalue r; r.j = v; return r; }
static jvalue L(void *p) { jvalue r; r.j = 0; r.l = p; return r; }
static jobj *jstr(const char *s) { return s ? dvm_new_string_utf8(s) : NULL; }
static jobj *jbytes(const uint8_t *d, size_t n)
{
    if (!d) return NULL;
    jobj *arr = tl_jni_new_prim_array('B', (uint32_t)n);
    arr->refs = 1u << 30;
    memcpy(arr->arr.data, d, n);
    return arr;
}
#define D ((dmx *)(uintptr_t)a[0].j)
#define T(i) ((i) >= 0 && (i) < D->nt ? &D->t[i] : NULL)

NAT(X_open)
{
    (void)self;
    int fd = a[0].i >= 0 ? dup(a[0].i) : -1;
    dmx *d = fd >= 0 ? dmx_open_fd(fd, (uint64_t)(a[1].j > 0 ? a[1].j : 0), (uint64_t)(a[2].j > 0 ? a[2].j : 0)) : NULL;
    if (!d && fd >= 0) tl_log_line("extractor: fd %d is not a media file this demuxer reads", a[0].i);
    *ret = J((int64_t)(uintptr_t)d);
    return true;
}
NAT(X_openPath)
{
    (void)self;
    const char *path = tl_jni_string(a[0].l);
    int fd = path ? open(path, O_RDONLY | O_CLOEXEC) : -1;
    dmx *d = fd >= 0 ? dmx_open_fd(fd, 0, 0) : NULL;
    if (!d) tl_log_line("extractor: cannot read %s", path ? path : "(null)");
    *ret = J((int64_t)(uintptr_t)d);
    return true;
}
NAT(X_openBytes)
{
    (void)self;
    jobj *b = a[0].l;
    dmx *d = NULL;
    if (b && b->arr.len) { uint8_t *m = malloc(b->arr.len); memcpy(m, b->arr.data, b->arr.len); d = dmx_open_mem(m, b->arr.len); }
    *ret = J((int64_t)(uintptr_t)d);
    return true;
}
NAT(X_close) { (void)self; (void)ret; dmx_close(D); return true; }
NAT(X_container) { (void)self; *ret = L(jstr(D->mime)); return true; }
NAT(X_trackCount) { (void)self; *ret = I(D->nt); return true; }
NAT(X_trackMime) { (void)self; dmx_track *t = T(a[1].i); *ret = L(t ? jstr(t->mime) : NULL); return true; }
NAT(X_trackLanguage) { (void)self; dmx_track *t = T(a[1].i); *ret = L(t ? jstr(t->lang) : NULL); return true; }
/* trackInfo(h, track, what): the numbers of a track, see MediaDemux's constants */
NAT(X_trackInfo)
{
    (void)self;
    dmx_track *t = T(a[1].i);
    int64_t v = 0;
    if (t) switch (a[2].i) {
        case 0: v = t->type; break; case 1: v = t->width; break; case 2: v = t->height; break; case 3: v = t->rotation; break;
        case 4: v = t->duration_us; break; case 5: v = t->fps; break; case 6: v = t->bitrate; break; case 7: v = t->max_size; break;
        case 8: v = t->rate; break; case 9: v = t->channels; break; case 10: v = t->n; break; case 11: v = t->bits; break;
        case 12: v = t->color_standard; break; case 13: v = t->color_transfer; break; case 14: v = t->color_range; break; case 15: v = t->aac_profile; break;
        default: break; }
    *ret = J(v);
    return true;
}
NAT(X_trackCsd) { (void)self; dmx_track *t = T(a[1].i); int k = a[2].i; *ret = L(t && k >= 0 && k < 3 && t->csd_n[k] ? jbytes(t->csd[k], t->csd_n[k]) : NULL); return true; }
NAT(X_duration) { (void)self; *ret = J(D->duration_us); return true; }
NAT(X_bitrate) { (void)self; *ret = I(D->bitrate); return true; }
NAT(X_meta) { (void)self; int k = a[1].i; *ret = L(k >= 0 && k < DMX_M_N ? jstr(D->meta[k]) : NULL); return true; }
NAT(X_picture) { (void)self; *ret = L(D->pic ? jbytes(D->pic, D->pic_n) : NULL); return true; }
NAT(X_select) { (void)self; (void)ret; dmx_select(D, a[1].i, a[2].z); return true; }
NAT(X_seek) { (void)self; (void)ret; dmx_seek(D, a[1].j, a[2].i); return true; }
NAT(X_advance) { (void)self; *ret = Z(dmx_advance(D)); return true; }
NAT(X_sampleTrack) { (void)self; *ret = I(D->cur_track); return true; }
NAT(X_sampleTime) { (void)self; const dmx_sample *s = dmx_cur(D); *ret = J(s ? s->pts : -1); return true; }
NAT(X_sampleFlags) { (void)self; const dmx_sample *s = dmx_cur(D); *ret = I(s && s->sync ? 1 : 0); return true; }
NAT(X_sampleSize) { (void)self; *ret = J(dmx_cur_data(D) ? (int64_t)D->cur_n : -1); return true; }
/* readSample(h, dst, off): the current sample into dst at off; its size, -1 past the end, -2 when dst is too small */
NAT(X_readSample)
{
    (void)self;
    jobj *dst = a[1].l; int off = a[2].i;
    if (!dmx_cur_data(D)) { *ret = I(-1); return true; }
    if (!dst || off < 0 || (size_t)off + D->cur_n > dst->arr.len) { *ret = I(-2); return true; }
    memcpy((uint8_t *)dst->arr.data + off, D->cur_data, D->cur_n);
    *ret = I((int32_t)D->cur_n);
    return true;
}

/* ---- frames for MediaMetadataRetriever: decode from the sync sample before the time, pick the nearest frame ---- */

typedef struct { void *f; int64_t pts; } picked;
static void *frame_at(dmx *d, int ti, int64_t time_us, int option)
{
    if (ti < 0 || ti >= d->nt) return NULL;
    dmx_track *t = &d->t[ti];
    if (t->type != DMX_VIDEO || (t->codec != DMX_AVC && t->codec != DMX_HEVC) || !t->n) return NULL;
    if (time_us < 0) time_us = d->thumb_us >= 0 ? d->thumb_us : 0;
    uint32_t target = dmx_seek_index(t, time_us, option);
    if (target >= t->n) { target = t->n - 1; while (target > 0 && !t->s[target].sync) target--; }
    uint32_t start = target; while (start > 0 && !t->s[start].sync) start--;
    uint32_t end = option == DMX_SEEK_CLOSEST ? target : start;      /* the last sample fed */
    if (option == DMX_SEEK_CLOSEST) { uint32_t k = end; int extra = 0; while (k + 1 < t->n && extra < 6) { k++; extra++; if (t->s[k].pts > time_us && t->s[k].sync) break; } end = k; }
    if (end - start > 600) end = start + 600;
    const uint8_t *csd[3] = { t->csd[0], t->csd[1], t->csd[2] }; size_t csd_n[3] = { t->csd_n[0], t->csd_n[1], t->csd_n[2] };
    void *dec = tl_media_open(t->mime, csd, csd_n, 3);
    if (!dec) return NULL;
    for (uint32_t i = start; i <= end; i++) {
        uint8_t *b; size_t n;
        if (!dmx_read_sample(d, ti, i, &b, &n)) break;
        tl_media_feed(dec, b, n, t->s[i].pts, i == end);
        free(b);
    }
    void *best = NULL; int64_t bd = INT64_MAX, pts;
    for (void *f; (f = tl_media_take_frame(dec, &pts));) {
        int64_t dd = pts > time_us ? pts - time_us : time_us - pts;
        if (option != DMX_SEEK_CLOSEST) dd = pts > t->s[target].pts ? pts - t->s[target].pts : t->s[target].pts - pts;
        if (dd < bd) { if (best) tl_media_frame_free(best); best = f; bd = dd; } else tl_media_frame_free(f);
    }
    tl_media_close(dec);
    return best;
}
NAT(X_frameAt) { (void)self; *ret = J((int64_t)(uintptr_t)frame_at(D, a[1].i, a[2].j, a[3].i)); return true; }
NAT(X_frameAtIndex)
{
    (void)self;
    dmx_track *t = T(a[1].i); int idx = a[2].i;
    *ret = J(t && idx >= 0 && (uint32_t)idx < t->n ? (int64_t)(uintptr_t)frame_at(D, a[1].i, t->s[idx].pts, DMX_SEEK_CLOSEST) : 0);
    return true;
}
NAT(X_frameSize) { (void)self; int w = 0, h = 0; tl_media_frame_dims((void *)(uintptr_t)a[0].j, &w, &h); *ret = I(w << 16 | (h & 0xffff)); return true; }
/* frameDraw(frame, bitmap, rotation): the frame scaled to the bitmap and turned by the track's rotation, so it is upright */
NAT(X_frameDraw)
{
    (void)self;
    void *f = (void *)(uintptr_t)a[0].j;
    tl_abitmap_info bi; void *px = NULL;
    if (!f || !a[1].l || tl_AndroidBitmap_getInfo(NULL, a[1].l, &bi) || tl_AndroidBitmap_lockPixels(NULL, a[1].l, &px) || !px) { *ret = Z(false); return true; }
    *ret = Z(tl_media_frame_draw(f, a[2].i, px, (int)bi.width, (int)bi.height, bi.stride));
    return true;
}
NAT(X_frameFree) { (void)self; (void)ret; tl_media_frame_free((void *)(uintptr_t)a[0].j); return true; }

static const struct { const char *name, *sig; dvm_native_fn fn; } k_demux[] = {
    { "open", "(IJJ)J", X_open },
    { "openPath", "(Ljava/lang/String;)J", X_openPath },
    { "openBytes", "([B)J", X_openBytes },
    { "close", "(J)V", X_close },
    { "container", "(J)Ljava/lang/String;", X_container },
    { "trackCount", "(J)I", X_trackCount },
    { "trackMime", "(JI)Ljava/lang/String;", X_trackMime },
    { "trackLanguage", "(JI)Ljava/lang/String;", X_trackLanguage },
    { "trackInfo", "(JII)J", X_trackInfo },
    { "trackCsd", "(JII)[B", X_trackCsd },
    { "duration", "(J)J", X_duration },
    { "bitrate", "(J)I", X_bitrate },
    { "meta", "(JI)Ljava/lang/String;", X_meta },
    { "picture", "(J)[B", X_picture },
    { "select", "(JIZ)V", X_select },
    { "seek", "(JJI)V", X_seek },
    { "advance", "(J)Z", X_advance },
    { "sampleTrack", "(J)I", X_sampleTrack },
    { "sampleTime", "(J)J", X_sampleTime },
    { "sampleFlags", "(J)I", X_sampleFlags },
    { "sampleSize", "(J)J", X_sampleSize },
    { "readSample", "(J[BI)I", X_readSample },
    { "frameAt", "(JIJI)J", X_frameAt },
    { "frameAtIndex", "(JII)J", X_frameAtIndex },
    { "frameSize", "(J)I", X_frameSize },
    { "frameDraw", "(JLandroid/graphics/Bitmap;I)Z", X_frameDraw },
    { "frameFree", "(J)V", X_frameFree },
    { NULL, NULL, NULL },
};
dvm_native_fn tl_demux_native(const char *name, const char *sig);
dvm_native_fn tl_demux_native(const char *name, const char *sig)
{
    for (int i = 0; k_demux[i].name; i++) if (!strcmp(k_demux[i].name, name) && !strcmp(k_demux[i].sig, sig)) return k_demux[i].fn;
    return NULL;
}
#endif /* HUSK_DEMUX_ONLY */
