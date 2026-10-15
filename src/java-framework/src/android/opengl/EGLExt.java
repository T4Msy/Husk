package android.opengl;

/** EGL's Android extensions: the presentation time of the next frame swapped into an encoder's input Surface; no native fences. */
public class EGLExt {
    public static final int EGL_CONTEXT_FLAGS_KHR = 12540;
    public static final int EGL_CONTEXT_MAJOR_VERSION_KHR = 12440;
    public static final int EGL_CONTEXT_MINOR_VERSION_KHR = 12539;
    public static final int EGL_NO_NATIVE_FENCE_FD_ANDROID = -1;
    public static final int EGL_OPENGL_ES3_BIT_KHR = 64;
    public static final int EGL_RECORDABLE_ANDROID = 12610;
    public static final int EGL_SYNC_NATIVE_FENCE_ANDROID = 12612;
    public static final int EGL_SYNC_NATIVE_FENCE_FD_ANDROID = 12613;
    public static final int EGL_SYNC_NATIVE_FENCE_SIGNALED_ANDROID = 12614;
    public EGLExt() {}
    public static android.hardware.SyncFence eglDupNativeFenceFDANDROID(EGLDisplay dpy, EGLSync sync) { return null; }
    public static boolean eglPresentationTimeANDROID(EGLDisplay dpy, EGLSurface surface, long timeNs) {
        return javax.microedition.khronos.egl.HuskEGL.huskPresentationTime(EGL14.huskUnwrap(surface), timeNs);
    }
}
