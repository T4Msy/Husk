/* SPDX-License-Identifier: GPL-2.0-or-later */
/*
 * The demuxer behind android.media.MediaExtractor, run on a desktop: it has no Apple dependency, so the file is included
 * with its natives left out and driven over real files.
 *
 * Usage: demux_test <file>...     one line per track, then every sample of the selected tracks in the order the
 *                                 extractor would hand them out (summarised), and a seek check.
 *
 * Build (from the repo root):
 *   clang -std=gnu11 -o /tmp/demux_test tests/translation-layer/demux_test.c
 * Exit code: 0 when every file opened and its samples read back, 1 otherwise.
 */
#define HUSK_DEMUX_ONLY
#include "../../src/translation-layer-next/husk-tl-dvm-extractor.c"

static int check(const char *path)
{
    int fd = open(path, O_RDONLY);
    if (fd < 0) { printf("%s: cannot open\n", path); return 1; }
    dmx *d = dmx_open_fd(fd, 0, 0);
    if (!d) { printf("%s: not demuxed\n", path); return 1; }
    printf("%s: %s, %lld us, %d kbit/s, %d tracks%s%s%s\n", path, d->mime, (long long)d->duration_us, d->bitrate / 1000, d->nt,
           d->meta[DMX_M_TITLE] ? ", title=" : "", d->meta[DMX_M_TITLE] ? d->meta[DMX_M_TITLE] : "", d->pic ? ", picture" : "");
    int bad = 0;
    for (int i = 0; i < d->nt; i++) {
        dmx_track *t = &d->t[i];
        printf("  track %d: %s %dx%d rot %d, %d Hz %d ch, %u samples, %lld us, fps %d, max %u, csd %zu/%zu/%zu, lang %s\n", i, t->mime, t->width, t->height, t->rotation,
               t->rate, t->channels, t->n, (long long)t->duration_us, t->fps, t->max_size, t->csd_n[0], t->csd_n[1], t->csd_n[2], t->lang);
        dmx_select(d, i, true);
    }
    /* every sample of every track, interleaved by time: times must not go backwards within a track, and read back as Annex-B */
    uint32_t n = 0, sync = 0; int64_t last[64]; size_t bytes = 0;
    for (int i = 0; i < 64; i++) last[i] = INT64_MIN;
    for (; d->cur_track >= 0; dmx_advance(d)) {
        const dmx_sample *s = dmx_cur(d); int ti = d->cur_track;
        if (!dmx_cur_data(d)) { printf("  sample %u of track %d: not read\n", n, ti); bad = 1; break; }
        if (d->t[ti].nal_len && (d->cur_n < 4 || memcmp(d->cur_data, "\0\0\0\1", 4))) { printf("  sample %u: no start code\n", n); bad = 1; }
        if (s->pts < last[ti] && s->sync) { printf("  sync sample %u of track %d goes back in time (%lld < %lld)\n", n, ti, (long long)s->pts, (long long)last[ti]); bad = 1; }
        if (s->sync) last[ti] = s->pts;
        if (n < 6) printf("  sample %u: track %d, %zu bytes, pts %lld%s\n", n, ti, d->cur_n, (long long)s->pts, s->sync ? ", sync" : "");
        n++; sync += s->sync; bytes += d->cur_n;
    }
    printf("  %u samples (%u sync), %zu bytes\n", n, sync, bytes);
    uint32_t expect = 0; for (int i = 0; i < d->nt; i++) expect += d->t[i].n;
    if (n != expect) { printf("  expected %u samples\n", expect); bad = 1; }
    /* seeking: to the middle, previous sync lands at or before, next sync at or after */
    int64_t mid = d->duration_us / 2;
    dmx_seek(d, mid, DMX_SEEK_PREVIOUS_SYNC);
    const dmx_sample *s = dmx_cur(d);
    if (!s || s->pts > mid) { printf("  seek to previous sync at %lld gave %lld\n", (long long)mid, s ? (long long)s->pts : -1); bad = 1; }
    else printf("  seek %lld: previous sync %lld on track %d", (long long)mid, (long long)s->pts, d->cur_track);
    dmx_seek(d, mid, DMX_SEEK_NEXT_SYNC);
    s = dmx_cur(d);
    bool later_sync = false;
    for (int i = 0; i < d->nt && !later_sync; i++) for (uint32_t k = 0; k < d->t[i].n; k++) if (d->t[i].s[k].sync && d->t[i].s[k].pts >= mid) { later_sync = true; break; }
    if (!s || (s->pts < mid && later_sync)) { printf("\n  seek to next sync at %lld gave %lld\n", (long long)mid, s ? (long long)s->pts : -1); bad = 1; }
    else printf(", next sync %lld\n", (long long)s->pts);
    dmx_close(d);
    return bad;
}

int main(int argc, char **argv)
{
    int bad = 0;
    for (int i = 1; i < argc; i++) bad |= check(argv[i]);
    if (argc < 2) { fprintf(stderr, "usage: demux_test <file>...\n"); return 1; }
    return bad;
}
