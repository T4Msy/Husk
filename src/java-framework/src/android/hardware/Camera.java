package android.hardware;

import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.SurfaceHolder;
import java.util.ArrayList;
import java.util.List;

/**
 * The old camera API over husk.CameraSource. Camera 0 is the back camera (orientation 90), 1 the front (270); the preview goes
 * to a SurfaceHolder's surface or a SurfaceTexture turned by setDisplayOrientation (the front camera mirrored before the turn,
 * as the platform does), preview callbacks get NV21 frames of the preview size in sensor orientation, takePicture a JPEG
 * turned by the "rotation" parameter. Parameters is the platform's string map: what the camera offers is in the "-values"
 * keys, setParameters applies focus, flash, zoom, exposure compensation, areas and the preview size. Callbacks run on the
 * looper of the thread that opened the camera, as on Android.
 */
public class Camera {
    private static final String TAG = "Camera";
    public static final String ACTION_NEW_PICTURE = "android.hardware.action.NEW_PICTURE", ACTION_NEW_VIDEO = "android.hardware.action.NEW_VIDEO";
    public static final int CAMERA_ERROR_UNKNOWN = 1, CAMERA_ERROR_EVICTED = 2, CAMERA_ERROR_DISABLED = 3, CAMERA_ERROR_SERVER_DIED = 100;
    public static final int CAMERA_HAL_API_VERSION_1_0 = 256, CAMERA_HAL_API_VERSION_3_0 = 768;
    public interface PreviewCallback { void onPreviewFrame(byte[] data, Camera camera); }
    public interface ShutterCallback { void onShutter(); }
    public interface PictureCallback { void onPictureTaken(byte[] data, Camera camera); }
    public interface AutoFocusCallback { void onAutoFocus(boolean success, Camera camera); }
    public interface AutoFocusMoveCallback { void onAutoFocusMoving(boolean start, Camera camera); }
    public interface ErrorCallback { void onError(int error, Camera camera); }
    public interface OnZoomChangeListener { void onZoomChange(int zoomValue, boolean stopped, Camera camera); }
    public interface FaceDetectionListener { void onFaceDetection(Face[] faces, Camera camera); }
    public static class CameraInfo {
        public static final int CAMERA_FACING_BACK = 0, CAMERA_FACING_FRONT = 1;
        public int facing, orientation;
        public boolean canDisableShutterSound = true;
        public CameraInfo() {}
    }
    public static class Face {
        public Rect rect; public int score, id = -1; public android.graphics.Point leftEye, rightEye, mouth;
        public Face() {}
    }
    public static class Area {
        public Rect rect; public int weight;
        public Area(Rect rect, int weight) { this.rect = rect; this.weight = weight; }
        @Override public boolean equals(Object o) { return o instanceof Area && ((Area) o).weight == weight && (rect == null ? ((Area) o).rect == null : rect.equals(((Area) o).rect)); }
        @Override public int hashCode() { return weight; }
    }
    public class Size {
        public int width, height;
        public Size(int w, int h) { width = w; height = h; }
        @Override public boolean equals(Object o) { return o instanceof Size && ((Size) o).width == width && ((Size) o).height == height; }
        @Override public int hashCode() { return width * 32713 + height; }
        @Override public String toString() { return width + "x" + height; }
    }

    private static final Size[] NO_SIZES = {};
    private husk.CameraSource mSource;
    private final int mId, mFacing;
    private final Handler mEvents;
    private Parameters mParams;
    private SurfaceTexture mTexture;
    private Surface mSurface;
    private int mDisplayOrientation;
    private PreviewCallback mPreviewCb, mOneShotCb;
    private boolean mWithBuffer, mPreviewing, mReleased, mConfigured;
    private final ArrayList<byte[]> mBuffers = new ArrayList<>();
    private ErrorCallback mErrorCb;
    private OnZoomChangeListener mZoomListener;
    private AutoFocusMoveCallback mAfMoveCb;
    private FaceDetectionListener mFaceListener;
    private volatile Object[] mPicture;     /* { ShutterCallback, PictureCallback raw, PictureCallback postview, PictureCallback jpeg } while one is due */
    private int mFrames;

    public static int getNumberOfCameras() { return husk.CameraSource.facings().length; }
    public static int getNumberOfCameras(android.content.Context c) { return getNumberOfCameras(); }
    public static void getCameraInfo(int cameraId, CameraInfo info) {
        int[] f = husk.CameraSource.facings();
        if (cameraId < 0 || cameraId >= f.length) throw new RuntimeException("Unknown camera ID " + cameraId);
        info.facing = f[cameraId] == 1 ? CameraInfo.CAMERA_FACING_FRONT : CameraInfo.CAMERA_FACING_BACK;
        info.orientation = f[cameraId] == 1 ? 270 : 90;
        info.canDisableShutterSound = true;
    }
    public static void getCameraInfo(int cameraId, android.content.Context c, Object compat, CameraInfo info) { getCameraInfo(cameraId, info); }
    public static Camera open() { return getNumberOfCameras() > 0 ? open(0) : null; }
    public static Camera open(int cameraId) {
        int[] f = husk.CameraSource.facings();
        if (cameraId < 0 || cameraId >= f.length) throw new RuntimeException("Fail to connect to camera service");
        husk.CameraSource src = husk.CameraSource.open(f[cameraId]);
        if (src == null) throw new RuntimeException("Fail to connect to camera service");
        return new Camera(cameraId, f[cameraId], src);
    }
    public static Camera open(int cameraId, android.content.Context c, Object compat) { return open(cameraId); }
    public static Camera openLegacy(int cameraId, int halVersion) { return open(cameraId); }
    public static Camera openUninitialized() { return null; }
    public static boolean checkInitErrors(int err) { return err != 0; }
    public static boolean shouldExposeAuxCamera() { return false; }
    public static Parameters getEmptyParameters() { return new Parameters(null); }
    public static Parameters getParametersCopy(Parameters p) { Parameters c = new Parameters(p.mCamera); c.mMap.putAll(p.mMap); return c; }
    protected Camera() { mId = 0; mFacing = 0; mEvents = new Handler(Looper.getMainLooper()); }
    private Camera(int id, int facing, husk.CameraSource src) {
        mId = id; mFacing = facing; mSource = src;
        mEvents = new Handler(Looper.myLooper() != null ? Looper.myLooper() : Looper.getMainLooper());
        mParams = new Parameters(this);
        mParams.defaults();
        android.util.Log.i(TAG, "camera " + id + " open (" + (facing == 1 ? "front" : "back") + ", " + src.width() + "x" + src.height() + ")");
    }
    public husk.CameraSource huskSource() { return mSource; }
    private void checkOpen() { if (mReleased || mSource == null) throw new RuntimeException("Method called after release()"); }
    public final void release() {
        if (mReleased) return;
        mReleased = true; mPreviewing = false;
        husk.CameraSource s = mSource; mSource = null;
        if (s != null) s.close();
    }
    public final void unlock() {}
    public final void lock() {}
    public final void reconnect() {}
    public final void setPreviewDisplay(SurfaceHolder holder) { checkOpen(); mTexture = null; mSurface = holder != null ? holder.getSurface() : null; }
    public final void setPreviewSurface(Surface surface) { checkOpen(); mTexture = null; mSurface = surface; }
    public final void setPreviewTexture(SurfaceTexture texture) { checkOpen(); mSurface = null; mTexture = texture; }
    public final void setDisplayOrientation(int degrees) {
        if (degrees != 0 && degrees != 90 && degrees != 180 && degrees != 270) throw new RuntimeException("setDisplayOrientation: invalid degrees " + degrees);
        mDisplayOrientation = degrees;
    }
    public final boolean previewEnabled() { return mPreviewing; }
    public final void startPreview() {
        checkOpen();
        if (mPreviewing) return;
        configure();
        mSource.setHandler(this::frame);
        if (!mSource.start()) { error(CAMERA_ERROR_UNKNOWN); return; }
        mPreviewing = true;
    }
    public final void stopPreview() { checkOpen(); mPreviewing = false; mSource.stop(); }
    private void configure() {
        Size p = mParams.getPreviewSize();
        int[] fps = new int[2]; mParams.getPreviewFpsRange(fps);
        mSource.configure(p.width, p.height, Math.max(1, fps[1] / 1000));
        mConfigured = true;
    }
    public final void setPreviewCallback(PreviewCallback cb) { mPreviewCb = cb; mWithBuffer = false; }
    public final void setPreviewCallbackWithBuffer(PreviewCallback cb) { mPreviewCb = cb; mWithBuffer = true; }
    public final void setOneShotPreviewCallback(PreviewCallback cb) { mOneShotCb = cb; }
    public final void addCallbackBuffer(byte[] buffer) { if (buffer != null) synchronized (mBuffers) { mBuffers.add(buffer); } }
    public final void addRawImageCallbackBuffer(byte[] buffer) {}
    public final void setErrorCallback(ErrorCallback cb) { mErrorCb = cb; }
    public final void setDetailedErrorCallback(ErrorCallback cb) { mErrorCb = cb; }
    public final void setZoomChangeListener(OnZoomChangeListener l) { mZoomListener = l; }
    public final void setAutoFocusMoveCallback(AutoFocusMoveCallback cb) { mAfMoveCb = cb; }
    public final void setFaceDetectionListener(FaceDetectionListener l) { mFaceListener = l; }
    public final void startFaceDetection() {}
    public final void stopFaceDetection() {}
    public final boolean enableShutterSound(boolean on) { return true; }
    public final boolean disableShutterSound() { return true; }
    public final int getAudioRestriction() { return 0; }
    public final void setAudioRestriction(int mode) {}
    private void error(int code) { ErrorCallback cb = mErrorCb; if (cb != null) mEvents.post(() -> cb.onError(code, this)); }

    /** autoFocus: a one-time scan at the focus area (or the centre); the callback once the lens settles. */
    public final void autoFocus(AutoFocusCallback cb) {
        checkOpen();
        float[] p = mParams.focusPoint();
        boolean can = mSource.canFocus();
        if (can) mSource.focus(p != null ? p[0] : -1, p != null ? p[1] : -1, 1);
        AutoFocusMoveCallback mv = mAfMoveCb;
        if (mv != null && can) mEvents.post(() -> mv.onAutoFocusMoving(true, this));
        Thread t = new Thread(() -> {
            long until = System.currentTimeMillis() + 2000;
            try { Thread.sleep(can ? 150 : 30); while (can && System.currentTimeMillis() < until && mSource != null && mSource.focusing()) Thread.sleep(30); } catch (InterruptedException e) { return; }
            if (mv != null && can) mEvents.post(() -> mv.onAutoFocusMoving(false, this));
            if (cb != null) mEvents.post(() -> cb.onAutoFocus(true, this));
        }, "CameraAutoFocus");
        t.setDaemon(true); t.start();
    }
    public final void cancelAutoFocus() { checkOpen(); mParams.applyFocus(mSource, true); }
    public final void startSmoothZoom(int value) {
        checkOpen();
        mParams.setZoom(value); mParams.applyZoom(mSource);
        OnZoomChangeListener l = mZoomListener;
        if (l != null) mEvents.post(() -> l.onZoomChange(value, true, this));
    }
    public final void stopSmoothZoom() {}
    public final void takePicture(ShutterCallback shutter, PictureCallback raw, PictureCallback jpeg) { takePicture(shutter, raw, null, jpeg); }
    public final void takePicture(ShutterCallback shutter, PictureCallback raw, PictureCallback postview, PictureCallback jpeg) {
        checkOpen();
        if (!mPreviewing) throw new RuntimeException("takePicture failed");
        String flash = mParams.getFlashMode();
        if (Parameters.FLASH_MODE_ON.equals(flash) || Parameters.FLASH_MODE_AUTO.equals(flash)) mSource.torch(true);
        mPicture = new Object[] { shutter, raw, postview, jpeg };
    }
    public Parameters getParameters() { checkOpen(); Parameters p = new Parameters(this); p.mMap.putAll(mParams.mMap); return p; }
    public void setParameters(Parameters params) {
        checkOpen();
        if (params == null) throw new RuntimeException("setParameters failed: null");
        Size oldPreview = mParams.getPreviewSize();
        int[] oldFps = new int[2]; mParams.getPreviewFpsRange(oldFps);
        mParams.mMap.clear(); mParams.mMap.putAll(params.mMap);
        Size preview = mParams.getPreviewSize();
        int[] fps = new int[2]; mParams.getPreviewFpsRange(fps);
        if (mPreviewing && (!preview.equals(oldPreview) || fps[1] != oldFps[1])) configure();
        mParams.apply(mSource);
    }

    /* ---- the camera thread: each frame to the preview, the callbacks, and a picture when one is due */
    private void frame(long frame, int fw, int fh, long ts) {
        husk.CameraSource src = mSource;
        if (src == null || mReleased) return;
        Object[] pic = mPicture;
        if (pic != null) {
            mPicture = null;
            mPreviewing = false;                                   /* as the platform: preview stops, startPreview starts it again */
            ShutterCallback shutter = (ShutterCallback) pic[0]; PictureCallback jpegCb = (PictureCallback) pic[3];
            if (shutter != null) mEvents.post(shutter::onShutter);
            if (Parameters.FLASH_MODE_ON.equals(mParams.getFlashMode()) || Parameters.FLASH_MODE_AUTO.equals(mParams.getFlashMode())) { try { Thread.sleep(250); } catch (InterruptedException e) {} }
            Size ps = mParams.getPictureSize();
            int rot = (mParams.getInt("rotation", 0) / 90) & 3;
            byte[] jpeg = src.jpeg(frame, rot, mParams.getJpegQuality(), (rot & 1) != 0 ? ps.height : ps.width, (rot & 1) != 0 ? ps.width : ps.height);
            if (Parameters.FLASH_MODE_ON.equals(mParams.getFlashMode()) || Parameters.FLASH_MODE_AUTO.equals(mParams.getFlashMode())) src.torch(false);
            if (jpegCb != null) mEvents.post(() -> jpegCb.onPictureTaken(jpeg, this));
            return;
        }
        if (!mPreviewing) return;
        mFrames++;
        int transform = (mDisplayOrientation / 90) | (mFacing == 1 ? husk.CameraSource.FLIP_X : 0);
        if (mTexture != null) src.toTexture(mTexture, frame, fw, fh, transform);
        else if (mSurface != null) src.toSurface(mSurface, frame, fw, fh, transform);
        PreviewCallback cb = mPreviewCb, once = mOneShotCb;
        if (cb == null && once == null) return;
        Size p = mParams.getPreviewSize();
        int w = p.width & ~1, h = p.height & ~1, n = w * h + w * (h / 2);
        byte[] buf = null;
        if (cb != null && mWithBuffer) { synchronized (mBuffers) { for (int i = 0; i < mBuffers.size(); i++) if (mBuffers.get(i).length >= n) { buf = mBuffers.remove(i); break; } } if (buf == null) cb = null; }
        if (cb == null && once == null) return;
        if (buf == null) buf = new byte[n];
        if (!src.yuv(frame, buf, w, h, true)) { if (mWithBuffer && cb != null) addCallbackBuffer(buf); return; }
        final byte[] data = buf; final PreviewCallback c1 = cb, c2 = once;
        if (c2 != null) mOneShotCb = null;
        mEvents.post(() -> { if (c1 != null) c1.onPreviewFrame(data, this); if (c2 != null) c2.onPreviewFrame(data, this); });
    }

    /** The platform's parameter map: "key=value;key=value" with the camera's offer in the "-values" keys. */
    public static class Parameters {
        public static final String WHITE_BALANCE_AUTO = "auto", WHITE_BALANCE_INCANDESCENT = "incandescent", WHITE_BALANCE_FLUORESCENT = "fluorescent", WHITE_BALANCE_WARM_FLUORESCENT = "warm-fluorescent", WHITE_BALANCE_DAYLIGHT = "daylight", WHITE_BALANCE_CLOUDY_DAYLIGHT = "cloudy-daylight", WHITE_BALANCE_TWILIGHT = "twilight", WHITE_BALANCE_SHADE = "shade";
        public static final String EFFECT_NONE = "none", EFFECT_MONO = "mono", EFFECT_NEGATIVE = "negative", EFFECT_SOLARIZE = "solarize", EFFECT_SEPIA = "sepia", EFFECT_POSTERIZE = "posterize", EFFECT_WHITEBOARD = "whiteboard", EFFECT_BLACKBOARD = "blackboard", EFFECT_AQUA = "aqua";
        public static final String ANTIBANDING_AUTO = "auto", ANTIBANDING_50HZ = "50hz", ANTIBANDING_60HZ = "60hz", ANTIBANDING_OFF = "off";
        public static final String FLASH_MODE_OFF = "off", FLASH_MODE_AUTO = "auto", FLASH_MODE_ON = "on", FLASH_MODE_RED_EYE = "red-eye", FLASH_MODE_TORCH = "torch";
        public static final String SCENE_MODE_AUTO = "auto", SCENE_MODE_ACTION = "action", SCENE_MODE_PORTRAIT = "portrait", SCENE_MODE_LANDSCAPE = "landscape", SCENE_MODE_NIGHT = "night", SCENE_MODE_NIGHT_PORTRAIT = "night-portrait", SCENE_MODE_THEATRE = "theatre", SCENE_MODE_BEACH = "beach", SCENE_MODE_SNOW = "snow", SCENE_MODE_SUNSET = "sunset", SCENE_MODE_STEADYPHOTO = "steadyphoto", SCENE_MODE_FIREWORKS = "fireworks", SCENE_MODE_SPORTS = "sports", SCENE_MODE_PARTY = "party", SCENE_MODE_CANDLELIGHT = "candlelight", SCENE_MODE_BARCODE = "barcode", SCENE_MODE_HDR = "hdr";
        public static final String FOCUS_MODE_AUTO = "auto", FOCUS_MODE_INFINITY = "infinity", FOCUS_MODE_MACRO = "macro", FOCUS_MODE_FIXED = "fixed", FOCUS_MODE_EDOF = "edof", FOCUS_MODE_CONTINUOUS_VIDEO = "continuous-video", FOCUS_MODE_CONTINUOUS_PICTURE = "continuous-picture";
        public static final int FOCUS_DISTANCE_NEAR_INDEX = 0, FOCUS_DISTANCE_OPTIMAL_INDEX = 1, FOCUS_DISTANCE_FAR_INDEX = 2, PREVIEW_FPS_MIN_INDEX = 0, PREVIEW_FPS_MAX_INDEX = 1;
        private static final String PIXEL_FORMAT_YUV422SP = "yuv422sp", PIXEL_FORMAT_YUV420SP = "yuv420sp", PIXEL_FORMAT_YUV422I = "yuv422i-yuyv", PIXEL_FORMAT_YUV420P = "yuv420p", PIXEL_FORMAT_RGB565 = "rgb565", PIXEL_FORMAT_JPEG = "jpeg";
        private final Camera mCamera;
        private final java.util.LinkedHashMap<String, String> mMap = new java.util.LinkedHashMap<>();
        Parameters(Camera c) { mCamera = c; }
        void defaults() {
            boolean front = mCamera != null && mCamera.mFacing == 1;
            boolean flash = mCamera != null && mCamera.mSource != null && mCamera.mSource.hasFlash();
            set("preview-size", "1280x720"); set("preview-size-values", "1920x1080,1280x720,960x720,640x480,352x288,320x240");
            set("picture-size", "4032x3024"); set("picture-size-values", "4032x3024,3840x2160,1920x1440,1920x1080,1280x960,1280x720,640x480");
            set("video-size", "1920x1080"); set("video-size-values", "1920x1080,1280x720,640x480"); set("preferred-preview-size-for-video", "1920x1080");
            set("preview-format", PIXEL_FORMAT_YUV420SP); set("preview-format-values", PIXEL_FORMAT_YUV420SP + "," + PIXEL_FORMAT_YUV420P);
            set("picture-format", PIXEL_FORMAT_JPEG); set("picture-format-values", PIXEL_FORMAT_JPEG);
            set("preview-frame-rate", 30); set("preview-frame-rate-values", "15,24,30"); set("preview-fps-range", "15000,30000"); set("preview-fps-range-values", "(15000,30000),(24000,30000),(30000,30000)");
            set("focus-mode", front ? FOCUS_MODE_FIXED : FOCUS_MODE_CONTINUOUS_PICTURE);
            set("focus-mode-values", front ? FOCUS_MODE_FIXED : FOCUS_MODE_AUTO + "," + FOCUS_MODE_CONTINUOUS_PICTURE + "," + FOCUS_MODE_CONTINUOUS_VIDEO + "," + FOCUS_MODE_MACRO + "," + FOCUS_MODE_INFINITY + "," + FOCUS_MODE_FIXED);
            set("focus-distances", "0.1,0.5,Infinity"); set("max-num-focus-areas", front ? 0 : 1); set("max-num-metering-areas", 1); set("max-num-detected-faces-hw", 0);
            set("flash-mode", FLASH_MODE_OFF); if (flash) set("flash-mode-values", FLASH_MODE_OFF + "," + FLASH_MODE_AUTO + "," + FLASH_MODE_ON + "," + FLASH_MODE_TORCH);
            set("scene-mode", SCENE_MODE_AUTO); set("scene-mode-values", SCENE_MODE_AUTO);
            set("whitebalance", WHITE_BALANCE_AUTO); set("whitebalance-values", WHITE_BALANCE_AUTO);
            set("effect", EFFECT_NONE); set("effect-values", EFFECT_NONE);
            set("antibanding", ANTIBANDING_AUTO); set("antibanding-values", ANTIBANDING_AUTO + "," + ANTIBANDING_50HZ + "," + ANTIBANDING_60HZ + "," + ANTIBANDING_OFF);
            set("zoom", 0); set("max-zoom", 30); set("zoom-supported", "true"); set("smooth-zoom-supported", "true");
            StringBuilder ratios = new StringBuilder(); for (int i = 0; i <= 30; i++) { if (i > 0) ratios.append(','); ratios.append(100 + i * 10); } set("zoom-ratios", ratios.toString());
            set("focal-length", front ? "2.7" : "5.1"); set("horizontal-view-angle", front ? "72" : "70"); set("vertical-view-angle", front ? "57" : "55");
            set("exposure-compensation", 0); set("max-exposure-compensation", 12); set("min-exposure-compensation", -12); set("exposure-compensation-step", "0.166667");
            set("auto-exposure-lock-supported", "true"); set("auto-exposure-lock", "false"); set("auto-whitebalance-lock-supported", "true"); set("auto-whitebalance-lock", "false");
            set("jpeg-quality", 95); set("jpeg-thumbnail-quality", 85); set("jpeg-thumbnail-width", 320); set("jpeg-thumbnail-height", 240); set("jpeg-thumbnail-size-values", "320x240,0x0");
            set("rotation", 0); set("video-stabilization", "false"); set("video-stabilization-supported", "false"); set("video-snapshot-supported", "true"); set("recording-hint", "false");
        }
        public String flatten() { StringBuilder b = new StringBuilder(); for (java.util.Map.Entry<String, String> e : mMap.entrySet()) { if (b.length() > 0) b.append(';'); b.append(e.getKey()).append('=').append(e.getValue()); } return b.toString(); }
        public void unflatten(String flattened) { mMap.clear(); if (flattened == null) return; for (String kv : flattened.split(";")) { int i = kv.indexOf('='); if (i > 0) mMap.put(kv.substring(0, i), kv.substring(i + 1)); } }
        public void remove(String key) { mMap.remove(key); }
        public void set(String key, String value) {
            if (key == null || key.indexOf('=') >= 0 || key.indexOf(';') >= 0) throw new IllegalArgumentException("Key \"" + key + "\" contains invalid character (= or ;)");
            if (value == null) { mMap.remove(key); return; }
            if (value.indexOf('=') >= 0 || value.indexOf(';') >= 0) throw new IllegalArgumentException("Value \"" + value + "\" contains invalid character (= or ;)");
            mMap.put(key, value);
        }
        public void set(String key, int value) { mMap.put(key, Integer.toString(value)); }
        public String get(String key) { return mMap.get(key); }
        public int getInt(String key) { String v = mMap.get(key); if (v == null) throw new NumberFormatException("no " + key); return Integer.parseInt(v.trim()); }
        private int getInt(String key, int dflt) { String v = mMap.get(key); if (v == null) return dflt; try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return dflt; } }
        private float getFloat(String key, float dflt) { String v = mMap.get(key); if (v == null) return dflt; try { return Float.parseFloat(v.trim()); } catch (NumberFormatException e) { return dflt; } }
        public void dump() { android.util.Log.d(TAG, "dump: " + mMap.size() + " parameters: " + flatten()); }
        private Size size(String key) { String v = mMap.get(key); return v == null ? null : parseSize(v); }
        private Size parseSize(String v) { int i = v.indexOf('x'); if (i < 0) return null; try { return mCamera.new Size(Integer.parseInt(v.substring(0, i).trim()), Integer.parseInt(v.substring(i + 1).trim())); } catch (NumberFormatException e) { return null; } }
        private List<Size> sizes(String key) { String v = mMap.get(key); if (v == null) return null; ArrayList<Size> r = new ArrayList<>(); for (String s : v.split(",")) { Size z = parseSize(s); if (z != null) r.add(z); } return r; }
        private List<String> list(String key) { String v = mMap.get(key); if (v == null) return null; ArrayList<String> r = new ArrayList<>(); for (String s : v.split(",")) if (!s.isEmpty()) r.add(s); return r; }
        private List<Integer> ints(String key) { String v = mMap.get(key); if (v == null) return null; ArrayList<Integer> r = new ArrayList<>(); for (String s : v.split(",")) try { r.add(Integer.parseInt(s.trim())); } catch (NumberFormatException e) {} return r; }
        public void setPreviewSize(int width, int height) { set("preview-size", width + "x" + height); }
        public Size getPreviewSize() { Size s = size("preview-size"); return s != null ? s : mCamera.new Size(1280, 720); }
        public List<Size> getSupportedPreviewSizes() { return sizes("preview-size-values"); }
        public List<Size> getSupportedVideoSizes() { return sizes("video-size-values"); }
        public Size getPreferredPreviewSizeForVideo() { return size("preferred-preview-size-for-video"); }
        public void setJpegThumbnailSize(int width, int height) { set("jpeg-thumbnail-width", width); set("jpeg-thumbnail-height", height); }
        public Size getJpegThumbnailSize() { return mCamera.new Size(getInt("jpeg-thumbnail-width", 0), getInt("jpeg-thumbnail-height", 0)); }
        public List<Size> getSupportedJpegThumbnailSizes() { return sizes("jpeg-thumbnail-size-values"); }
        public void setJpegThumbnailQuality(int quality) { set("jpeg-thumbnail-quality", quality); }
        public int getJpegThumbnailQuality() { return getInt("jpeg-thumbnail-quality", 85); }
        public void setJpegQuality(int quality) { set("jpeg-quality", quality); }
        public int getJpegQuality() { return getInt("jpeg-quality", 95); }
        public void setPreviewFrameRate(int fps) { set("preview-frame-rate", fps); }
        public int getPreviewFrameRate() { return getInt("preview-frame-rate", 30); }
        public List<Integer> getSupportedPreviewFrameRates() { return ints("preview-frame-rate-values"); }
        public void setPreviewFpsRange(int min, int max) { set("preview-fps-range", min + "," + max); }
        public void getPreviewFpsRange(int[] range) {
            if (range == null || range.length != 2) throw new IllegalArgumentException("range must be an array with two elements.");
            List<Integer> r = ints("preview-fps-range");
            range[0] = r != null && r.size() == 2 ? r.get(0) : 15000; range[1] = r != null && r.size() == 2 ? r.get(1) : 30000;
        }
        public List<int[]> getSupportedPreviewFpsRange() {
            String v = mMap.get("preview-fps-range-values"); if (v == null) return null;
            ArrayList<int[]> r = new ArrayList<>();
            for (String s : v.split("\\),\\(")) { String[] p = s.replace("(", "").replace(")", "").split(","); if (p.length == 2) try { r.add(new int[] { Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()) }); } catch (NumberFormatException e) {} }
            return r;
        }
        public void setPreviewFormat(int pixelFormat) { String s = formatName(pixelFormat); if (s == null) throw new IllegalArgumentException("Invalid pixel_format=" + pixelFormat); set("preview-format", s); }
        public int getPreviewFormat() { return formatOf(mMap.get("preview-format")); }
        public List<Integer> getSupportedPreviewFormats() { List<String> l = list("preview-format-values"); if (l == null) return null; ArrayList<Integer> r = new ArrayList<>(); for (String s : l) { int f = formatOf(s); if (f != ImageFormat.UNKNOWN) r.add(f); } return r; }
        public void setPictureSize(int width, int height) { set("picture-size", width + "x" + height); }
        public Size getPictureSize() { Size s = size("picture-size"); return s != null ? s : mCamera.new Size(4032, 3024); }
        public List<Size> getSupportedPictureSizes() { return sizes("picture-size-values"); }
        public void setPictureFormat(int pixelFormat) { String s = formatName(pixelFormat); if (s == null) throw new IllegalArgumentException("Invalid pixel_format=" + pixelFormat); set("picture-format", s); }
        public int getPictureFormat() { return formatOf(mMap.get("picture-format")); }
        public List<Integer> getSupportedPictureFormats() { List<String> l = list("picture-format-values"); if (l == null) return null; ArrayList<Integer> r = new ArrayList<>(); for (String s : l) r.add(formatOf(s)); return r; }
        private static String formatName(int f) {
            switch (f) { case ImageFormat.NV16: return PIXEL_FORMAT_YUV422SP; case ImageFormat.NV21: return PIXEL_FORMAT_YUV420SP; case ImageFormat.YUY2: return PIXEL_FORMAT_YUV422I; case ImageFormat.YV12: return PIXEL_FORMAT_YUV420P; case ImageFormat.RGB_565: return PIXEL_FORMAT_RGB565; case ImageFormat.JPEG: return PIXEL_FORMAT_JPEG; }
            return null;
        }
        private static int formatOf(String s) {
            if (s == null) return ImageFormat.UNKNOWN;
            if (s.equals(PIXEL_FORMAT_YUV422SP)) return ImageFormat.NV16; if (s.equals(PIXEL_FORMAT_YUV420SP)) return ImageFormat.NV21; if (s.equals(PIXEL_FORMAT_YUV422I)) return ImageFormat.YUY2;
            if (s.equals(PIXEL_FORMAT_YUV420P)) return ImageFormat.YV12; if (s.equals(PIXEL_FORMAT_RGB565)) return ImageFormat.RGB_565; if (s.equals(PIXEL_FORMAT_JPEG)) return ImageFormat.JPEG;
            return ImageFormat.UNKNOWN;
        }
        public void setRotation(int rotation) { if (rotation != 0 && rotation != 90 && rotation != 180 && rotation != 270) throw new IllegalArgumentException("Invalid rotation=" + rotation); set("rotation", rotation); }
        public void setGpsLatitude(double v) { set("gps-latitude", Double.toString(v)); }
        public void setGpsLongitude(double v) { set("gps-longitude", Double.toString(v)); }
        public void setGpsAltitude(double v) { set("gps-altitude", Double.toString(v)); }
        public void setGpsTimestamp(long v) { set("gps-timestamp", Long.toString(v)); }
        public void setGpsProcessingMethod(String v) { set("gps-processing-method", v); }
        public void removeGpsData() { remove("gps-latitude"); remove("gps-longitude"); remove("gps-altitude"); remove("gps-timestamp"); remove("gps-processing-method"); }
        public String getWhiteBalance() { return mMap.get("whitebalance"); }
        public void setWhiteBalance(String v) { if (v != null && !v.equals(getWhiteBalance())) { set("whitebalance", v); set("auto-whitebalance-lock", "false"); } }
        public List<String> getSupportedWhiteBalance() { return list("whitebalance-values"); }
        public String getColorEffect() { return mMap.get("effect"); }
        public void setColorEffect(String v) { set("effect", v); }
        public List<String> getSupportedColorEffects() { return list("effect-values"); }
        public String getAntibanding() { return mMap.get("antibanding"); }
        public void setAntibanding(String v) { set("antibanding", v); }
        public List<String> getSupportedAntibanding() { return list("antibanding-values"); }
        public String getSceneMode() { return mMap.get("scene-mode"); }
        public void setSceneMode(String v) { set("scene-mode", v); }
        public List<String> getSupportedSceneModes() { return list("scene-mode-values"); }
        public String getFlashMode() { return mMap.get("flash-mode"); }
        public void setFlashMode(String v) { set("flash-mode", v); }
        public List<String> getSupportedFlashModes() { return list("flash-mode-values"); }
        public String getFocusMode() { return mMap.get("focus-mode"); }
        public void setFocusMode(String v) { set("focus-mode", v); }
        public List<String> getSupportedFocusModes() { return list("focus-mode-values"); }
        public float getFocalLength() { return getFloat("focal-length", 4.2f); }
        public float getHorizontalViewAngle() { return getFloat("horizontal-view-angle", 70); }
        public float getVerticalViewAngle() { return getFloat("vertical-view-angle", 55); }
        public int getExposureCompensation() { return getInt("exposure-compensation", 0); }
        public void setExposureCompensation(int v) { set("exposure-compensation", v); }
        public int getMaxExposureCompensation() { return getInt("max-exposure-compensation", 0); }
        public int getMinExposureCompensation() { return getInt("min-exposure-compensation", 0); }
        public float getExposureCompensationStep() { return getFloat("exposure-compensation-step", 0); }
        public void setAutoExposureLock(boolean v) { set("auto-exposure-lock", v ? "true" : "false"); }
        public boolean getAutoExposureLock() { return "true".equals(mMap.get("auto-exposure-lock")); }
        public boolean isAutoExposureLockSupported() { return "true".equals(mMap.get("auto-exposure-lock-supported")); }
        public void setAutoWhiteBalanceLock(boolean v) { set("auto-whitebalance-lock", v ? "true" : "false"); }
        public boolean getAutoWhiteBalanceLock() { return "true".equals(mMap.get("auto-whitebalance-lock")); }
        public boolean isAutoWhiteBalanceLockSupported() { return "true".equals(mMap.get("auto-whitebalance-lock-supported")); }
        public int getZoom() { return getInt("zoom", 0); }
        public void setZoom(int v) { set("zoom", v); }
        public boolean isZoomSupported() { return "true".equals(mMap.get("zoom-supported")); }
        public int getMaxZoom() { return getInt("max-zoom", 0); }
        public List<Integer> getZoomRatios() { return ints("zoom-ratios"); }
        public boolean isSmoothZoomSupported() { return "true".equals(mMap.get("smooth-zoom-supported")); }
        public void getFocusDistances(float[] output) {
            if (output == null || output.length != 3) throw new IllegalArgumentException("output must be a float array with three elements.");
            String v = mMap.get("focus-distances"); String[] p = v != null ? v.split(",") : new String[0];
            for (int i = 0; i < 3; i++) output[i] = i < p.length ? Float.parseFloat(p[i].trim()) : Float.POSITIVE_INFINITY;
        }
        public int getMaxNumFocusAreas() { return getInt("max-num-focus-areas", 0); }
        public List<Area> getFocusAreas() { return areas("focus-areas"); }
        public void setFocusAreas(List<Area> areas) { set("focus-areas", areasString(areas)); }
        public int getMaxNumMeteringAreas() { return getInt("max-num-metering-areas", 0); }
        public List<Area> getMeteringAreas() { return areas("metering-areas"); }
        public void setMeteringAreas(List<Area> areas) { set("metering-areas", areasString(areas)); }
        public int getMaxNumDetectedFaces() { return getInt("max-num-detected-faces-hw", 0); }
        public void setRecordingHint(boolean hint) { set("recording-hint", hint ? "true" : "false"); }
        public boolean isVideoSnapshotSupported() { return "true".equals(mMap.get("video-snapshot-supported")); }
        public void setVideoStabilization(boolean on) { set("video-stabilization", on ? "true" : "false"); }
        public boolean getVideoStabilization() { return "true".equals(mMap.get("video-stabilization")); }
        public boolean isVideoStabilizationSupported() { return "true".equals(mMap.get("video-stabilization-supported")); }
        private List<Area> areas(String key) {
            String v = mMap.get(key); if (v == null) return null;
            ArrayList<Area> r = new ArrayList<>();
            for (String s : v.split("\\),\\(")) { String[] p = s.replace("(", "").replace(")", "").split(","); if (p.length == 5) try { r.add(new Area(new Rect(Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim()), Integer.parseInt(p[3].trim())), Integer.parseInt(p[4].trim()))); } catch (NumberFormatException e) {} }
            return r.isEmpty() ? null : r;
        }
        private static String areasString(List<Area> areas) {
            if (areas == null || areas.isEmpty()) return "(0,0,0,0,0)";
            StringBuilder b = new StringBuilder();
            for (Area a : areas) { if (b.length() > 0) b.append(','); b.append('(').append(a.rect.left).append(',').append(a.rect.top).append(',').append(a.rect.right).append(',').append(a.rect.bottom).append(',').append(a.weight).append(')'); }
            return b.toString();
        }
        /** The first focus (else metering) area's centre across the sensor frame (0..1), or null: Android's areas run -1000..1000. */
        float[] focusPoint() {
            List<Area> a = getFocusAreas(); if (a == null) a = getMeteringAreas();
            if (a == null || a.isEmpty() || a.get(0).rect == null || a.get(0).weight == 0) return null;
            Rect r = a.get(0).rect;
            return new float[] { (r.centerX() + 1000) / 2000f, (r.centerY() + 1000) / 2000f };
        }
        void applyFocus(husk.CameraSource src, boolean cancel) {
            String m = getFocusMode(); float[] p = focusPoint();
            int mode = FOCUS_MODE_FIXED.equals(m) || FOCUS_MODE_INFINITY.equals(m) || FOCUS_MODE_EDOF.equals(m) ? 0 : FOCUS_MODE_AUTO.equals(m) || FOCUS_MODE_MACRO.equals(m) ? (cancel ? 2 : 1) : 2;
            src.focus(p != null ? p[0] : -1, p != null ? p[1] : -1, mode);
        }
        void applyZoom(husk.CameraSource src) {
            List<Integer> ratios = getZoomRatios(); int z = getZoom();
            float ratio = ratios != null && z >= 0 && z < ratios.size() ? ratios.get(z) / 100f : 1 + z * 0.1f;
            src.zoom(ratio);
        }
        void apply(husk.CameraSource src) {
            if (src == null) return;
            applyFocus(src, true);
            applyZoom(src);
            String flash = getFlashMode();
            src.torch(FLASH_MODE_TORCH.equals(flash));
            float[] p = focusPoint();
            src.exposure(p != null ? p[0] : -1, p != null ? p[1] : -1, getAutoExposureLock() ? 0 : 2, getExposureCompensation() * getExposureCompensationStep());
            int[] fps = new int[2]; getPreviewFpsRange(fps);
            if (fps[0] > 0 && fps[1] >= fps[0]) src.fps(Math.max(1, fps[0] / 1000), Math.max(1, fps[1] / 1000));
        }
    }
}
