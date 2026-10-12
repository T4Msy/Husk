package android.media;

import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * An MP4 writer (husk.MediaMuxerNative, husk-tl-dvm-muxer.c): H.264/HEVC and AAC tracks, as MediaCodec's encoders output them, into
 * one interleaved mdat with the movie header at stop. MPEG-4 and 3GPP come out as MP4 files; WebM, Ogg and HEIF are refused the way
 * Android refuses an unknown format, since nothing Husk encodes could go in them.
 */
public final class MediaMuxer {
    public static final class OutputFormat {
        public static final int MUXER_OUTPUT_FIRST = 0;
        public static final int MUXER_OUTPUT_MPEG_4 = 0;
        public static final int MUXER_OUTPUT_WEBM = 1;
        public static final int MUXER_OUTPUT_3GPP = 2;
        public static final int MUXER_OUTPUT_HEIF = 3;
        public static final int MUXER_OUTPUT_OGG = 4;
        public static final int MUXER_OUTPUT_LAST = 4;
        private OutputFormat() {}
    }
    private static final int ST_UNINITIALIZED = 0, ST_INITIALIZED = 1, ST_STARTED = 2, ST_STOPPED = 3;
    private long mNative;
    private int mState = ST_UNINITIALIZED, mTracks;

    public MediaMuxer(String path, int format) throws IOException {
        if (path == null) throw new IllegalArgumentException("path must not be null");
        checkFormat(format);
        mNative = husk.MediaMuxerNative.create(path, -1, format);
        if (mNative == 0) throw new IOException("Failed to open " + path + " for writing");
        mState = ST_INITIALIZED;
    }
    public MediaMuxer(FileDescriptor fd, int format) throws IOException {
        if (fd == null || !fd.valid()) throw new IllegalArgumentException("fd must be a valid FileDescriptor");
        checkFormat(format);
        int n = fdOf(fd);
        if (n < 0) throw new IllegalArgumentException("fd must be a valid FileDescriptor");
        mNative = husk.MediaMuxerNative.create(null, n, format);
        if (mNative == 0) throw new IOException("Failed to open the file descriptor for writing");
        mState = ST_INITIALIZED;
    }
    private static void checkFormat(int format) {
        if (format == OutputFormat.MUXER_OUTPUT_MPEG_4 || format == OutputFormat.MUXER_OUTPUT_3GPP) return;
        if (format < OutputFormat.MUXER_OUTPUT_FIRST || format > OutputFormat.MUXER_OUTPUT_LAST) throw new IllegalArgumentException("format: " + format + " is invalid");
        throw new IllegalArgumentException("format " + format + " is not supported: Husk's MediaMuxer writes MPEG-4 and 3GPP");
    }
    private static int fdOf(FileDescriptor fd) {
        try { java.lang.reflect.Field d = FileDescriptor.class.getDeclaredField("descriptor"); d.setAccessible(true); return d.getInt(fd); } catch (Exception e) { return -1; }
    }
    private String err() { String e = mNative != 0 ? husk.MediaMuxerNative.error(mNative) : null; return e != null ? e : "unknown error"; }

    public void setOrientationHint(int degrees) {
        if (degrees != 0 && degrees != 90 && degrees != 180 && degrees != 270) throw new IllegalArgumentException("Unsupported angle: " + degrees);
        if (mState != ST_INITIALIZED) throw new IllegalStateException("Can't set orientation due to wrong state.");
        husk.MediaMuxerNative.setOrientation(mNative, degrees);
    }
    public void setLocation(float latitude, float longitude) {
        int lat = (int) (latitude * 10000 + 0.5), lon = (int) (longitude * 10000 + 0.5);
        if (lat > 900000 || lat < -900000) throw new IllegalArgumentException("Latitude: " + latitude + " out of range.");
        if (lon > 1800000 || lon < -1800000) throw new IllegalArgumentException("Longitude: " + longitude + " out of range");
        if (mState != ST_INITIALIZED) throw new IllegalStateException("Can't set location due to wrong state.");
        husk.MediaMuxerNative.setLocation(mNative, latitude, longitude);
    }
    private static byte[] bytes(ByteBuffer b) {
        if (b == null) return null;
        ByteBuffer d = b.duplicate(); d.position(0); d.limit(b.limit());
        byte[] r = new byte[d.remaining()]; d.get(r); return r;
    }
    public int addTrack(MediaFormat format) {
        if (format == null) throw new IllegalArgumentException("format must not be null.");
        if (mState != ST_INITIALIZED) throw new IllegalStateException("Muxer is not initialized.");
        String mime = format.getString(MediaFormat.KEY_MIME);
        if (mime == null) throw new IllegalArgumentException("format has no mime type");
        float fps = 0; try { if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) fps = format.getNumber(MediaFormat.KEY_FRAME_RATE).floatValue(); } catch (RuntimeException e) {}
        int t = husk.MediaMuxerNative.addTrack(mNative, mime, format.getInteger(MediaFormat.KEY_WIDTH, 0), format.getInteger(MediaFormat.KEY_HEIGHT, 0),
            format.getInteger(MediaFormat.KEY_SAMPLE_RATE, 0), format.getInteger(MediaFormat.KEY_CHANNEL_COUNT, 0), format.getInteger(MediaFormat.KEY_BIT_RATE, 0), fps,
            bytes(format.getByteBuffer("csd-0")), bytes(format.getByteBuffer("csd-1")));
        if (t < 0) throw new IllegalStateException("Failed to add the track to the muxer: " + err());
        mTracks = t + 1;
        return t;
    }
    public void start() {
        if (mState != ST_INITIALIZED) throw new IllegalStateException("Can't start due to wrong state.");
        if (mTracks == 0) throw new IllegalStateException("No tracks added");
        if (!husk.MediaMuxerNative.start(mNative)) throw new IllegalStateException("Failed to start the muxer: " + err());
        mState = ST_STARTED;
    }
    public void writeSampleData(int trackIndex, ByteBuffer byteBuf, MediaCodec.BufferInfo bufferInfo) {
        if (trackIndex < 0 || trackIndex >= mTracks) throw new IllegalArgumentException("trackIndex is invalid");
        if (byteBuf == null) throw new IllegalArgumentException("byteBuffer must not be null");
        if (bufferInfo == null) throw new IllegalArgumentException("bufferInfo must not be null");
        if (bufferInfo.size < 0 || bufferInfo.offset < 0 || bufferInfo.offset + bufferInfo.size > byteBuf.capacity())
            throw new IllegalArgumentException("bufferInfo must specify a valid buffer offset, size and presentation time");
        if (mState != ST_STARTED) throw new IllegalStateException("Can't write, muxer is not started");
        byte[] d = new byte[bufferInfo.size];
        ByteBuffer src = byteBuf.duplicate(); src.clear(); src.position(bufferInfo.offset); src.limit(bufferInfo.offset + bufferInfo.size); src.get(d);
        if (!husk.MediaMuxerNative.write(mNative, trackIndex, d, 0, d.length, bufferInfo.presentationTimeUs, bufferInfo.flags))
            throw new IllegalStateException("writeSampleData returned an error: " + err());
    }
    public void stop() {
        if (mState != ST_STARTED) throw new IllegalStateException("Can't stop due to wrong state.");
        boolean ok = husk.MediaMuxerNative.stop(mNative);
        mState = ST_STOPPED;
        if (!ok) throw new IllegalStateException("Failed to stop the muxer: " + err());
    }
    public void release() {
        if (mState == ST_STARTED) { try { stop(); } catch (IllegalStateException e) {} }
        long n = mNative; mNative = 0; mState = ST_UNINITIALIZED;
        if (n != 0) husk.MediaMuxerNative.release(n);
    }
    @Override protected void finalize() { release(); }
}
