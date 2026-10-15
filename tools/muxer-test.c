/* SPDX-License-Identifier: GPL-2.0-or-later */
/*
 * Host test for the MP4 writer behind android.media.MediaMuxer (husk-tl-dvm-muxer.c), on any Linux or Mac: feeds it an Annex-B
 * H.264 stream and raw AAC frames the way an app's MediaCodec outputs would arrive, then the file is checked with ffprobe.
 *
 *   muxer-test <out.mp4> [video.h264 video.txt] [audio.aac audio.txt] [rotation]
 *
 * video.txt / audio.txt: one sample per line, "pts_us flags size" in decode order (flags: 1 sync), the sizes slicing the
 * stream file in order. The audio stream is ADTS (as ffmpeg writes raw AAC); the 7-byte headers are stripped and csd-0 is made
 * from the first one. Audio-only and video-only runs are fine (an M4A for the former).
 */
#define HUSK_MUXER_STANDALONE
#include "../src/translation-layer-next/husk-tl-dvm-muxer.c"

static uint8_t *slurp(const char *path, size_t *n)
{
    FILE *f = fopen(path, "rb");
    if (!f) { perror(path); exit(1); }
    fseek(f, 0, SEEK_END); long len = ftell(f); fseek(f, 0, SEEK_SET);
    uint8_t *d = malloc((size_t)len + 1);
    *n = fread(d, 1, (size_t)len, f);
    fclose(f);
    return d;
}

typedef struct { int64_t pts; int flags; size_t size; } entry;
static entry *read_list(const char *path, size_t *n)
{
    FILE *f = fopen(path, "r");
    if (!f) { perror(path); exit(1); }
    entry *e = NULL; size_t cap = 0; *n = 0;
    long long pts; int flags; unsigned long size;
    while (fscanf(f, "%lld %d %lu", &pts, &flags, &size) == 3) {
        if (*n == cap) { cap = cap ? cap * 2 : 64; e = realloc(e, cap * sizeof *e); }
        e[(*n)++] = (entry){ pts, flags, size };
    }
    fclose(f);
    return e;
}

int main(int argc, char **argv)
{
    if (argc < 2) { fprintf(stderr, "usage: muxer-test out.mp4 [video.h264 video.txt] [audio.aac audio.txt] [rotation]\n"); return 2; }
    const char *out = argv[1];
    const char *vpath = NULL, *vlist = NULL, *apath = NULL, *alist = NULL; int rotation = 0;
    for (int i = 2; i < argc; i++) {
        size_t l = strlen(argv[i]);
        if (l > 5 && !strcmp(argv[i] + l - 5, ".h264")) vpath = argv[i];
        else if (l > 4 && !strcmp(argv[i] + l - 4, ".aac")) apath = argv[i];
        else if (l > 4 && !strcmp(argv[i] + l - 4, ".txt")) { if (vpath && !vlist) vlist = argv[i]; else alist = argv[i]; }
        else rotation = atoi(argv[i]);
    }
    mux *m = mux_open(out, -1, MUX_MPEG4);
    if (!m) { perror(out); return 1; }
    int vt = -1, at = -1;
    uint8_t *vd = NULL, *ad = NULL; size_t vn = 0, an = 0, nv = 0, na = 0;
    entry *ve = NULL, *ae = NULL;
    if (vpath && vlist) {
        vd = slurp(vpath, &vn); ve = read_list(vlist, &nv);
        /* the parameter sets come as a CODEC_CONFIG buffer after addTrack, as MediaCodec's first output does, not as csd-0 here */
        vt = mux_add_track(m, "video/avc", 320, 240, 0, 0, 400000, 30, NULL, 0, NULL, 0);
        if (vt < 0) { fprintf(stderr, "addTrack: %s\n", m->err); return 1; }
        mux_orientation(m, rotation);
    }
    int rate = 44100, channels = 2;
    if (apath && alist) {
        ad = slurp(apath, &an); ae = read_list(alist, &na);
        if (an < 7) { fprintf(stderr, "no ADTS header\n"); return 1; }
        int profile = (ad[2] >> 6) + 1, sri = (ad[2] >> 2) & 0xf, ch = ((ad[2] & 1) << 2) | (ad[3] >> 6);
        static const int rates[] = { 96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350 };
        rate = rates[sri]; channels = ch;
        uint8_t asc[2] = { (uint8_t)(profile << 3 | sri >> 1), (uint8_t)((sri & 1) << 7 | ch << 3) };
        at = mux_add_track(m, "audio/mp4a-latm", 0, 0, rate, channels, 128000, 0, asc, 2, NULL, 0);
        if (at < 0) { fprintf(stderr, "addTrack: %s\n", m->err); return 1; }
    }
    mux_location(m, 37.3317f, -122.0302f);
    if (!mux_start(m)) { fprintf(stderr, "start: %s\n", m->err); return 1; }
    /* interleave: whichever track's next sample is earlier */
    size_t vi = 0, ai = 0, vo = 0, ao = 0;
    while (vi < nv || ai < na) {
        bool video = vi < nv && (ai >= na || ve[vi].pts <= ae[ai].pts);
        if (video) {
            if (vo + ve[vi].size > vn) { fprintf(stderr, "video list runs past the stream\n"); return 1; }
            if (!mux_write(m, vt, vd + vo, ve[vi].size, ve[vi].pts, ve[vi].flags)) { fprintf(stderr, "write: %s\n", m->err); return 1; }
            vo += ve[vi].size; vi++;
        } else {
            if (ao + ae[ai].size > an || ae[ai].size < 7) { fprintf(stderr, "audio list runs past the stream\n"); return 1; }
            int hdr = (ad[ao + 1] & 1) ? 7 : 9;                       /* protection_absent: no CRC */
            if (!mux_write(m, at, ad + ao + hdr, ae[ai].size - (size_t)hdr, ae[ai].pts, ae[ai].flags)) { fprintf(stderr, "write: %s\n", m->err); return 1; }
            ao += ae[ai].size; ai++;
        }
    }
    if (!mux_stop(m)) { fprintf(stderr, "stop: %s\n", m->err); return 1; }
    mux_close(m);
    printf("wrote %s: %zu video samples, %zu audio samples\n", out, nv, na);
    free(vd); free(ad); free(ve); free(ae);
    return 0;
}
