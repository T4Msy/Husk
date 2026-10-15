package android.hardware.camera2;

import android.hardware.camera2.params.InputConfiguration;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/** Husk's CameraDevice: a husk.CameraSource, and one capture session at a time on it. */
final class CameraDeviceImpl extends CameraDevice {
    private static final String TAG = "CameraDevice";
    private final String mId;
    final CameraCharacteristics mChars;
    private final StateCallback mCallback;
    private final Handler mHandler;
    private final Executor mExecutor;
    private husk.CameraSource mSource;
    private CameraCaptureSessionImpl mSession;
    private boolean mClosed;
    CameraDeviceImpl(String id, CameraCharacteristics chars, StateCallback cb, Handler h, Executor e) { mId = id; mChars = chars; mCallback = cb; mHandler = h; mExecutor = e; }

    /** A callback on its executor, else its handler, else the caller's looper (the main one when it has none). */
    static void run(Handler h, Executor e, Runnable r) {
        if (e != null) e.execute(r);
        else if (h != null) h.post(r);
        else new Handler(Looper.myLooper() != null ? Looper.myLooper() : Looper.getMainLooper()).post(r);
    }
    static Handler handlerOrCurrent(Handler h) { return h != null ? h : new Handler(Looper.myLooper() != null ? Looper.myLooper() : Looper.getMainLooper()); }

    void huskOpened(husk.CameraSource src) {
        synchronized (this) { if (mClosed) { src.close(); return; } mSource = src; }
        android.util.Log.i(TAG, "camera " + mId + " opened (" + src.width() + "x" + src.height() + ")");
        run(mHandler, mExecutor, () -> mCallback.onOpened(this));
    }
    void huskFailed(int error) { synchronized (this) { mClosed = true; } run(mHandler, mExecutor, () -> mCallback.onError(this, error)); }
    /** Another open of the same camera: this one loses it, as camera2 evicts the earlier client. */
    void huskEvict() {
        CameraCaptureSessionImpl s;
        synchronized (this) { if (mClosed) return; mClosed = true; s = mSession; mSession = null; }
        if (s != null) s.huskClosedBy(this);
        husk.CameraSource src = mSource; mSource = null;
        if (src != null) src.close();
        run(mHandler, mExecutor, () -> mCallback.onDisconnected(this));
    }
    husk.CameraSource huskSource() { return mSource; }
    public String getId() { return mId; }
    private void checkOpen() throws CameraAccessException { if (mClosed || mSource == null) throw new IllegalStateException("CameraDevice was already closed"); }
    public CaptureRequest.Builder createCaptureRequest(int templateType) throws CameraAccessException {
        checkOpen();
        if (templateType < TEMPLATE_PREVIEW || templateType > TEMPLATE_MANUAL) throw new IllegalArgumentException("Unsupported template " + templateType);
        return new CaptureRequest.Builder(CaptureRequest.huskTemplate(templateType, mChars));
    }
    public CaptureRequest.Builder createReprocessCaptureRequest(TotalCaptureResult inputResult) throws CameraAccessException { throw new UnsupportedOperationException("reprocessing is not supported"); }
    public void createCaptureSession(List<Surface> outputs, CameraCaptureSession.StateCallback callback, Handler handler) throws CameraAccessException { configure(outputs, callback, handler, null); }
    public void createCaptureSessionByOutputConfigurations(List<OutputConfiguration> configs, CameraCaptureSession.StateCallback callback, Handler handler) throws CameraAccessException { configure(surfacesOf(configs), callback, handler, null); }
    public void createReprocessableCaptureSession(InputConfiguration input, List<Surface> outputs, CameraCaptureSession.StateCallback callback, Handler handler) throws CameraAccessException { configure(outputs, callback, handler, null); }
    public void createReprocessableCaptureSessionByConfigurations(InputConfiguration input, List<OutputConfiguration> configs, CameraCaptureSession.StateCallback callback, Handler handler) throws CameraAccessException { configure(surfacesOf(configs), callback, handler, null); }
    public void createConstrainedHighSpeedCaptureSession(List<Surface> outputs, CameraCaptureSession.StateCallback callback, Handler handler) throws CameraAccessException { throw new IllegalArgumentException("constrained high speed sessions are not supported"); }
    public void createCustomCaptureSession(InputConfiguration input, List<OutputConfiguration> configs, int mode, CameraCaptureSession.StateCallback callback, Handler handler) throws CameraAccessException { configure(surfacesOf(configs), callback, handler, null); }
    @Override public void createCaptureSession(SessionConfiguration config) throws CameraAccessException {
        if (config == null) throw new IllegalArgumentException("session configuration was null");
        configure(surfacesOf(config.getOutputConfigurations()), config.getStateCallback(), null, config.getExecutor());
    }
    private static List<Surface> surfacesOf(List<OutputConfiguration> configs) {
        ArrayList<Surface> r = new ArrayList<>();
        if (configs != null) for (OutputConfiguration c : configs) for (Surface s : c.getSurfaces()) if (!r.contains(s)) r.add(s);
        return r;
    }
    private void configure(List<Surface> outputs, CameraCaptureSession.StateCallback cb, Handler h, Executor e) throws CameraAccessException {
        checkOpen();
        if (cb == null) throw new IllegalArgumentException("callback was null");
        if (h == null && e == null) h = handlerOrCurrent(null);
        CameraCaptureSessionImpl old;
        synchronized (this) { old = mSession; mSession = null; }
        if (old != null) old.huskClosedBy(this);
        ArrayList<Surface> list = new ArrayList<>();
        for (Surface s : outputs) if (s != null && !list.contains(s)) list.add(s);
        CameraCaptureSessionImpl s = new CameraCaptureSessionImpl(this, mSource, list, cb, h, e);
        boolean ok;
        synchronized (this) { ok = !mClosed; if (ok) mSession = s; }
        if (ok) ok = s.huskConfigure();
        if (!ok) { synchronized (this) { if (mSession == s) mSession = null; } run(h, e, () -> cb.onConfigureFailed(s)); return; }
        run(h, e, () -> { cb.onConfigured(s); if (!s.huskIsClosed()) cb.onReady(s); });
    }
    void huskSessionClosed(CameraCaptureSessionImpl s) { synchronized (this) { if (mSession == s) mSession = null; } }
    public void close() {
        CameraCaptureSessionImpl s; husk.CameraSource src;
        synchronized (this) { if (mClosed) return; mClosed = true; s = mSession; mSession = null; src = mSource; mSource = null; }
        if (s != null) s.huskClosedBy(this);
        if (src != null) src.close();
        CameraManager.closed(this);
        run(mHandler, mExecutor, () -> mCallback.onClosed(this));
    }
    @Override public String toString() { return "CameraDevice(" + mId + ")"; }
}
