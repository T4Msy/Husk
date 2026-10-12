package android.hardware.camera2.params;

import android.graphics.ImageFormat;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

/**
 * The streams a Husk camera offers: PRIVATE and YUV_420_888 at the sizes the capture session can run (up to 1080p), JPEG up
 * to the sensor's full size, all at 30 frames a second; a surface class (SurfaceTexture, SurfaceHolder, MediaCodec,
 * MediaRecorder, ImageReader) gets the PRIVATE sizes, as the platform does.
 */
public final class StreamConfigurationMap {
    public static final int HAL_DATASPACE_ARBITRARY = 1, HAL_DATASPACE_DEPTH = 4096, HAL_DATASPACE_DYNAMIC_DEPTH = 4098, HAL_DATASPACE_HEIF = 4100, HAL_DATASPACE_JFIF = 146931712, HAL_DATASPACE_JPEG_R = 4101, HAL_DATASPACE_V0_JFIF = 146931712, HAL_PIXEL_FORMAT_BLOB = 33;
    private static final long FRAME_NS = 33333333L, JPEG_STALL_NS = 100000000L;
    private final Size[] mYuv, mJpeg;
    public StreamConfigurationMap(Size[] yuv, Size[] jpeg) { mYuv = yuv; mJpeg = jpeg; }
    public StreamConfigurationMap(StreamConfiguration[] p0, StreamConfigurationDuration[] p1, StreamConfigurationDuration[] p2, StreamConfiguration[] p3, StreamConfigurationDuration[] p4, StreamConfigurationDuration[] p5, StreamConfiguration[] p6, StreamConfigurationDuration[] p7, StreamConfigurationDuration[] p8, StreamConfiguration[] p9, StreamConfigurationDuration[] p10, StreamConfigurationDuration[] p11, StreamConfiguration[] p12, StreamConfigurationDuration[] p13, StreamConfigurationDuration[] p14, StreamConfiguration[] p15, StreamConfigurationDuration[] p16, StreamConfigurationDuration[] p17, HighSpeedVideoConfiguration[] p18, ReprocessFormatsMap p19, boolean p20) { this(new Size[0], new Size[0]); }
    public int[] getOutputFormats() { return new int[] { ImageFormat.PRIVATE, ImageFormat.YUV_420_888, ImageFormat.JPEG }; }
    public int[] getValidOutputFormatsForInput(int inputFormat) { return new int[0]; }
    public int[] getInputFormats() { return new int[0]; }
    public Size[] getInputSizes(int format) { return null; }
    public boolean isOutputSupportedFor(int format) { return format == ImageFormat.PRIVATE || format == ImageFormat.YUV_420_888 || format == ImageFormat.JPEG; }
    public static <T> boolean isOutputSupportedFor(Class<T> klass) {
        return klass == android.graphics.SurfaceTexture.class || klass == android.view.SurfaceHolder.class || klass == android.media.MediaCodec.class || klass == android.media.MediaRecorder.class || klass == android.media.ImageReader.class || klass == android.renderscript.Allocation.class;
    }
    public boolean isOutputSupportedFor(Surface surface) { return surface != null; }
    public boolean isOutputSupportedFor(Size size, int format) { for (Size s : getOutputSizes(format)) if (s.equals(size)) return true; return false; }
    public <T> Size[] getOutputSizes(Class<T> klass) { return isOutputSupportedFor(klass) ? mYuv.clone() : null; }
    public Size[] getOutputSizes(int format) { return format == ImageFormat.JPEG ? mJpeg.clone() : isOutputSupportedFor(format) ? mYuv.clone() : null; }
    public Size[] getHighResolutionOutputSizes(int format) { return new Size[0]; }
    public Size[] getHighSpeedVideoSizes() { return new Size[0]; }
    public Range<Integer>[] getHighSpeedVideoFpsRangesFor(Size size) { throw new IllegalArgumentException("Size " + size + " does not support high speed video recording"); }
    @SuppressWarnings("unchecked") public Range<Integer>[] getHighSpeedVideoFpsRanges() { return new Range[0]; }
    public Size[] getHighSpeedVideoSizesFor(Range<Integer> fpsRange) { throw new IllegalArgumentException("FPS range " + fpsRange + " does not support high speed video recording"); }
    public long getOutputMinFrameDuration(int format, Size size) { return FRAME_NS; }
    public <T> long getOutputMinFrameDuration(Class<T> klass, Size size) { return FRAME_NS; }
    public long getOutputStallDuration(int format, Size size) { return format == ImageFormat.JPEG ? JPEG_STALL_NS : 0; }
    public <T> long getOutputStallDuration(Class<T> klass, Size size) { return 0; }
    public static int imageFormatToPublic(int format) { return format == HAL_PIXEL_FORMAT_BLOB ? ImageFormat.JPEG : format; }
    public static int[] imageFormatToInternal(int[] formats) { return formats; }
    public static int depthFormatToPublic(int format) { return format; }
    public static String formatToString(int format) { return format == ImageFormat.JPEG ? "JPEG" : format == ImageFormat.YUV_420_888 ? "YUV_420_888" : format == ImageFormat.PRIVATE ? "PRIVATE" : "0x" + Integer.toHexString(format); }
    public static int compareSizes(int w1, int h1, int w2, int h2) { long a = (long) w1 * h1, b = (long) w2 * h2; return a == b ? Integer.compare(w1, w2) : Long.compare(a, b); }
    @Override public boolean equals(Object o) { return o == this; }
    @Override public int hashCode() { return System.identityHashCode(this); }
    @Override public String toString() { return "StreamConfigurationMap(husk: " + mYuv.length + " sizes, " + mJpeg.length + " JPEG sizes)"; }
}
