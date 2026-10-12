package husk;

/** Codecs behind android.media.MediaCodec (husk-tl-dvm-media.c): VideoToolbox for H.264/HEVC, AudioToolbox for AAC/MP3. */
public final class MediaCodecNative {
    private MediaCodecNative() {}
    public static native boolean supported(String mime);
    public static native long create(String mime);
    public static native boolean configure(long h, int width, int height, int rate, int channels, byte[] csd0, byte[] csd1, byte[] csd2);
    public static native int decode(long h, byte[] data, int len, long ptsUs, int flags);
    /** The next decoded frame in display order, 0 when none; info = { ptsUs, width, height }. */
    public static native long nextFrame(long h, long[] info);
    /** The next decoded 16-bit PCM, null when none; info = { ptsUs, sampleRate, channels }. */
    public static native byte[] nextPcm(long h, long[] info);
    public static native boolean renderFrame(long frame, android.graphics.Bitmap dst);
    public static native void freeFrame(long frame);
    public static native void flush(long h);
    public static native void destroy(long h);
    public static native int size(long h);

    /* ---- encoders: H.264 (VTCompressionSession) and AAC-LC (AudioConverter) */
    public static native long createEncoder(String mime);
    public static native boolean configureEncoder(long h, int width, int height, int bitrate, float frameRate, float iFrameInterval, int profile, int level,
                                                  int bitrateMode, int colorFormat, int sampleRate, int channels, int aacProfile);
    /** A YUV420 frame (planar, or NV12 for COLOR_FormatYUV420SemiPlanar) or 16-bit PCM; null with BUFFER_FLAG_END_OF_STREAM drains the encoder. */
    public static native boolean encode(long h, byte[] data, int offset, int len, long ptsUs, int flags);
    /** The input Surface's frame as read back from GL: RGBA, bottom row first when flip. */
    public static native boolean encodeRgba(long h, java.nio.ByteBuffer pixels, int width, int height, boolean flip, long ptsUs);
    /** The next encoded packet, waiting up to timeoutUs for one; null when none; info = { ptsUs, flags }. */
    public static native byte[] nextPacket(long h, long[] info, long timeoutUs);
    /** key 1: request a sync frame; key 2: a new video bitrate. */
    public static native void setEncoderParameter(long h, int key, int value);
    public static native void flushEncoder(long h);
    public static native void destroyEncoder(long h);
    /** The AAC encoder's AudioSpecificConfig (csd-0); null for the video encoder, whose parameter sets come as its first packet. */
    public static native byte[] encoderCsd(long h);
    public static native int encoderBitrate(long h);
}
