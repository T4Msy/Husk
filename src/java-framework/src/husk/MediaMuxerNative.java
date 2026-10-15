package husk;

/** The MP4 writer behind android.media.MediaMuxer (husk-tl-dvm-muxer.c). */
public final class MediaMuxerNative {
    private MediaMuxerNative() {}
    /** A writer on the path, or on the descriptor when fd >= 0; 0 when the file cannot be opened. */
    public static native long create(String path, int fd, int format);
    /** The new track's index, or -1 (error() says why). */
    public static native int addTrack(long h, String mime, int width, int height, int sampleRate, int channels, int bitrate, float frameRate, byte[] csd0, byte[] csd1);
    public static native void setOrientation(long h, int degrees);
    public static native void setLocation(long h, float latitude, float longitude);
    public static native boolean start(long h);
    /** One sample: an Annex-B access unit or an AAC frame; flags as MediaCodec.BufferInfo's. */
    public static native boolean write(long h, int track, byte[] data, int offset, int len, long ptsUs, int flags);
    public static native boolean stop(long h);
    public static native String error(long h);
    public static native void release(long h);
}
