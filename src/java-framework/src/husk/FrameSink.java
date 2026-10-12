package husk;

/**
 * Where the frames drawn into a Surface with no view behind it go: an encoder's input Surface (MediaCodec.createInputSurface).
 * EGL (javax.microedition.khronos.egl.HuskEGL) makes such a Surface a pbuffer of the sink's size and, at each eglSwapBuffers, reads
 * the framebuffer back and hands the pixels here; Surface.lockCanvas draws into a Bitmap that is handed over the same way.
 */
public interface FrameSink {
    int huskWidth();
    int huskHeight();
    /** RGBA pixels, the bottom row first (GL's order) when flip; ptsNs from EGLExt.eglPresentationTimeANDROID, or 0 when the app set none. */
    void huskFrame(java.nio.ByteBuffer rgba, int width, int height, boolean flip, long ptsNs);
}
