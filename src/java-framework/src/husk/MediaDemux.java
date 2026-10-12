package husk;

import android.media.MediaFormat;
import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * The demuxer behind android.media.MediaExtractor and MediaMetadataRetriever (husk-tl-dvm-extractor.c): MP4/M4A/MOV/3GP, Ogg
 * (Opus, Vorbis), MP3 and WAV, opened from a file descriptor at an offset, a path or bytes. A handle's tracks are numbered from 0;
 * samples come out interleaved across the selected tracks in time order. Frames are decoded for MediaMetadataRetriever through
 * the MediaCodec decoders and drawn into a Bitmap already turned upright.
 */
public final class MediaDemux {
    private MediaDemux() {}
    /** trackInfo's keys. */
    public static final int TYPE = 0, WIDTH = 1, HEIGHT = 2, ROTATION = 3, DURATION_US = 4, FPS = 5, BITRATE = 6, MAX_INPUT = 7, SAMPLE_RATE = 8, CHANNELS = 9,
        SAMPLES = 10, BITS = 11, COLOR_STANDARD = 12, COLOR_TRANSFER = 13, COLOR_RANGE = 14, AAC_PROFILE = 15;
    public static final int TYPE_VIDEO = 1, TYPE_AUDIO = 2;
    /** meta's keys. */
    public static final int META_TITLE = 0, META_ARTIST = 1, META_ALBUM = 2, META_ALBUMARTIST = 3, META_AUTHOR = 4, META_COMPOSER = 5, META_WRITER = 6, META_DATE = 7,
        META_YEAR = 8, META_GENRE = 9, META_TRACK = 10, META_DISC = 11, META_COMPILATION = 12, META_LOCATION = 13, META_CAPTURE_FPS = 14;

    public static native long open(int fd, long offset, long length);          // the descriptor is dup'd; length 0: to the end
    public static native long openPath(String path);
    public static native long openBytes(byte[] data);
    public static native void close(long h);
    public static native String container(long h);                               // the file's mime type
    public static native int trackCount(long h);
    public static native String trackMime(long h, int track);
    public static native String trackLanguage(long h, int track);
    public static native long trackInfo(long h, int track, int what);
    public static native byte[] trackCsd(long h, int track, int k);
    public static native long duration(long h);                                  // microseconds
    public static native int bitrate(long h);
    public static native String meta(long h, int key);
    public static native byte[] picture(long h);
    public static native void select(long h, int track, boolean on);
    public static native void seek(long h, long timeUs, int mode);               // MediaExtractor.SEEK_TO_*
    public static native boolean advance(long h);
    public static native int sampleTrack(long h);                                // -1 at the end
    public static native long sampleTime(long h);
    public static native int sampleFlags(long h);
    public static native long sampleSize(long h);
    /** The current sample into dst at off: its size, -1 at the end, -2 when dst is too small. */
    public static native int readSample(long h, byte[] dst, int off);
    /** A decoded frame of a video track near timeUs (MediaMetadataRetriever.OPTION_*), 0 when none; timeUs < 0: the file's thumbnail frame. */
    public static native long frameAt(long h, int track, long timeUs, int option);
    public static native long frameAtIndex(long h, int track, int index);
    public static native int frameSize(long frame);                              // width << 16 | height, before rotation
    public static native boolean frameDraw(long frame, android.graphics.Bitmap dst, int rotation);
    public static native void frameFree(long frame);

    /** A track's format as MediaExtractor.getTrackFormat hands it out. */
    public static MediaFormat format(long h, int t) {
        MediaFormat f = new MediaFormat();
        String mime = trackMime(h, t);
        f.setString(MediaFormat.KEY_MIME, mime);
        f.setInteger(MediaFormat.KEY_TRACK_ID, t + 1);
        long dur = trackInfo(h, t, DURATION_US);
        if (dur > 0) f.setLong(MediaFormat.KEY_DURATION, dur);
        String lang = trackLanguage(h, t);
        if (lang != null) f.setString(MediaFormat.KEY_LANGUAGE, lang);
        f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, (int) Math.max(trackInfo(h, t, MAX_INPUT), 1));
        int bitrate = (int) trackInfo(h, t, BITRATE);
        if (bitrate > 0) f.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        if (trackInfo(h, t, TYPE) == TYPE_VIDEO) {
            f.setInteger(MediaFormat.KEY_WIDTH, (int) trackInfo(h, t, WIDTH));
            f.setInteger(MediaFormat.KEY_HEIGHT, (int) trackInfo(h, t, HEIGHT));
            int rot = (int) trackInfo(h, t, ROTATION);
            if (rot != 0) f.setInteger(MediaFormat.KEY_ROTATION, rot);
            int fps = (int) trackInfo(h, t, FPS);
            if (fps > 0) f.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            int cs = (int) trackInfo(h, t, COLOR_STANDARD), ct = (int) trackInfo(h, t, COLOR_TRANSFER), cr = (int) trackInfo(h, t, COLOR_RANGE);
            if (cs != 0) f.setInteger(MediaFormat.KEY_COLOR_STANDARD, cs);
            if (ct != 0) f.setInteger(MediaFormat.KEY_COLOR_TRANSFER, ct);
            if (cr != 0) f.setInteger(MediaFormat.KEY_COLOR_RANGE, cr);
        } else {
            f.setInteger(MediaFormat.KEY_SAMPLE_RATE, (int) trackInfo(h, t, SAMPLE_RATE));
            f.setInteger(MediaFormat.KEY_CHANNEL_COUNT, (int) trackInfo(h, t, CHANNELS));
            int bits = (int) trackInfo(h, t, BITS);
            if (MediaFormat.MIMETYPE_AUDIO_RAW.equals(mime)) f.setInteger(MediaFormat.KEY_PCM_ENCODING, bits == 8 ? android.media.AudioFormat.ENCODING_PCM_8BIT : bits == 32 ? android.media.AudioFormat.ENCODING_PCM_FLOAT : android.media.AudioFormat.ENCODING_PCM_16BIT);
            if (MediaFormat.MIMETYPE_AUDIO_AAC.equals(mime)) { int p = (int) trackInfo(h, t, AAC_PROFILE); if (p > 0) f.setInteger(MediaFormat.KEY_AAC_PROFILE, p); f.setInteger(MediaFormat.KEY_IS_ADTS, 0); }
        }
        for (int k = 0; k < 3; k++) { byte[] csd = trackCsd(h, t, k); if (csd != null) f.setByteBuffer("csd-" + k, ByteBuffer.wrap(csd)); }
        return f;
    }
    /** The first track of a type, or -1. */
    public static int trackOfType(long h, int type) {
        int n = trackCount(h);
        for (int i = 0; i < n; i++) if (trackInfo(h, i, TYPE) == type) return i;
        return -1;
    }

    // ---- the data sources MediaExtractor, MediaMetadataRetriever and MediaPlayer take; each returns a handle or throws

    public static long openFd(FileDescriptor fd, long offset, long length) throws IOException {
        int n = Audio.fdOf(fd);
        if (n < 0) throw new IOException("not a file descriptor Husk can read");
        long h = open(n, Math.max(0, offset), Math.max(0, length));
        if (h == 0) throw new IOException("Failed to instantiate extractor.");
        return h;
    }
    public static long openFile(String path) throws IOException {
        if (path == null) throw new NullPointerException("path");
        if (path.startsWith("http://") || path.startsWith("https://")) return openUrl(path, null);
        if (path.startsWith("file://")) path = android.net.Uri.parse(path).getPath();
        else if (path.startsWith("content://") || path.startsWith("android.resource://")) return openUri(ContextImpl.app(), android.net.Uri.parse(path), null);
        if (!new java.io.File(path).canRead()) throw new java.io.FileNotFoundException(path);
        long h = openPath(path);
        if (h == 0) throw new IOException("Failed to instantiate extractor for " + path);
        return h;
    }
    public static long openUri(android.content.Context c, android.net.Uri u, java.util.Map<String, String> headers) throws IOException {
        String s = u.getScheme();
        if (s == null || "file".equals(s)) return openFile(u.getPath());
        if ("http".equals(s) || "https".equals(s)) return openUrl(u.toString(), headers);
        android.content.res.AssetFileDescriptor afd = c.getContentResolver().openAssetFileDescriptor(u, "r");
        if (afd == null) throw new java.io.FileNotFoundException(u.toString());
        try { return openAsset(afd); } finally { afd.close(); }
    }
    public static long openAsset(android.content.res.AssetFileDescriptor afd) throws IOException {
        if (afd.getFileDescriptor() != null && Audio.fdOf(afd.getFileDescriptor()) >= 0) return openFd(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength() == android.content.res.AssetFileDescriptor.UNKNOWN_LENGTH ? 0 : afd.getLength());
        if (afd.huskAssetName() != null) { byte[] b = Native.readAsset(afd.huskAssetName()); if (b != null) return openMemory(b); }
        throw new IOException("unreadable AssetFileDescriptor");
    }
    public static long openUrl(String url, java.util.Map<String, String> headers) throws IOException {
        java.net.URLConnection conn = new java.net.URL(url).openConnection();
        if (headers != null) for (java.util.Map.Entry<String, String> e : headers.entrySet()) conn.setRequestProperty(e.getKey(), e.getValue());
        try (java.io.InputStream in = conn.getInputStream()) { return openMemory(Audio.readAll(in)); }
    }
    public static long openDataSource(android.media.MediaDataSource ds) throws IOException {
        long size = ds.getSize();
        if (size > (512L << 20)) throw new IOException("media data source too large: " + size);
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream(size > 0 ? (int) size : 65536);
        byte[] b = new byte[65536]; long pos = 0;
        for (;;) { int r = ds.readAt(pos, b, 0, b.length); if (r <= 0) break; o.write(b, 0, r); pos += r; if (size > 0 && pos >= size) break; }
        return openMemory(o.toByteArray());
    }
    public static long openMemory(byte[] data) throws IOException {
        long h = data != null && data.length > 0 ? openBytes(data) : 0;
        if (h == 0) throw new IOException("Failed to instantiate extractor.");
        return h;
    }
}
