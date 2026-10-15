package husk;

/**
 * The phone's cameras (husk-tl-dvm-camera.c): AVFoundation capture behind android.hardware.Camera and android.hardware.camera2,
 * driven through CameraSource. A camera handle captures 4:2:0 frames; waitFrame hands out the newest as a frame handle that
 * frameTo* read as RGBA (into a Bitmap, or a direct ByteBuffer for a GL texture), as NV12/NV21 planes, or as a JPEG.
 */
public final class Camera {
    private Camera() {}
    /** Transform bits for frameToBitmap/frameToBuffer/frameToJpeg: the low two the clockwise quarter turns, then a horizontal
     *  or vertical flip applied to the frame before the turn. */
    public static final int FLIP_X = 4, FLIP_Y = 8;
    public static native int count();
    public static native boolean has(int facing);                        // 0 back, 1 front
    public static native long open(int facing);                          // 0: no such camera, or not allowed
    public static native boolean configure(long h, int width, int height, int fps);
    public static native int size(long h);                               // width << 16 | height of the frames
    public static native boolean start(long h);
    public static native void stop(long h);
    public static native void close(long h);
    public static native long waitFrame(long h, int timeoutMs);          // the newest frame, 0 when none came in time
    public static native void frameInfo(long frame, long[] info);        // { timestampNs, width, height }
    public static native void freeFrame(long frame);
    public static native boolean frameToBitmap(long frame, android.graphics.Bitmap dst, int transform);
    public static native boolean frameToBuffer(long frame, java.nio.ByteBuffer dst, int width, int height, int transform);
    public static native boolean frameToYuv(long frame, byte[] dst, int width, int height, boolean nv21);
    public static native byte[] frameToJpeg(long frame, int transform, int quality, int maxWidth, int maxHeight);
    public static native boolean hasFlash(long h);
    public static native boolean torch(long h, boolean on);
    /** mode 0 locked, 1 a one-time scan, 2 continuous; x, y the point of interest across the frame (0..1), or -1 for none */
    public static native void focus(long h, float x, float y, int mode);
    public static native boolean focusing(long h);
    public static native boolean canFocus(long h);
    public static native void exposure(long h, float x, float y, int mode, float biasEv);
    public static native float maxZoom(long h);
    public static native void zoom(long h, float ratio);
    public static native void fps(long h, int min, int max);
}
