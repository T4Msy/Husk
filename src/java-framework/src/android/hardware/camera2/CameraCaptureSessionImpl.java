package android.hardware.camera2;

import android.graphics.Rect;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.OutputConfiguration;
import android.os.Handler;
import android.util.Range;
import android.view.Surface;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Husk's capture session: each frame the camera thread brings runs the next queued request, else the repeating one, and
 * goes to that request's targets -- a SurfaceTexture or SurfaceView upright (turned by the sensor orientation, the front
 * camera mirrored, as the platform's buffer transform would show it), an ImageReader in sensor orientation, a FrameSink as
 * is. The request's settings that AVFoundation can honour are applied when they change: focus mode and trigger, the
 * metering region, flash as the torch, exposure compensation, zoom (or the crop region), frame rate. Results report the 3A
 * converged, with an AF scan of a few frames after a trigger.
 */
final class CameraCaptureSessionImpl extends CameraCaptureSession implements husk.CameraSource.FrameHandler {
    private static final String TAG = "CameraCaptureSession";
    private static final class Req {
        final CaptureRequest request; final CaptureCallback cb; final Handler h; final Executor e; final int seq; final boolean repeating; int left;
        Req(CaptureRequest r, CaptureCallback cb, Handler h, Executor e, int seq, boolean repeating, int left) { request = r; this.cb = cb; this.h = h; this.e = e; this.seq = seq; this.repeating = repeating; this.left = left; }
    }
    private final CameraDeviceImpl mDevice;
    private final husk.CameraSource mSource;
    private final List<Surface> mOutputs;
    private final StateCallback mCb;
    private final Handler mH;
    private final Executor mE;
    private final ArrayDeque<Req> mQueue = new ArrayDeque<>();
    private List<Req> mRepeating; private int mRepeatIndex;
    private int mSeq;
    private long mFrame;
    private volatile boolean mClosed;
    private boolean mActive, mStarted;
    /* the camera's state, as last applied */
    private int mAfMode = -1, mFlash = -1, mAfScan; private float mZoom = -1, mBias = Float.NaN; private Rect mCrop; private MeteringRectangle mRegion; private Range<Integer> mFps;
    private boolean mAeLock, mAfLocked;

    CameraCaptureSessionImpl(CameraDeviceImpl dev, husk.CameraSource src, List<Surface> outputs, StateCallback cb, Handler h, Executor e) { mDevice = dev; mSource = src; mOutputs = outputs; mCb = cb; mH = h; mE = e; }

    /** The capture at the largest size the outputs want (JPEG readers alone take the camera past 1080p), then frames. */
    boolean huskConfigure() {
        int w = 0, h = 0, jw = 0, jh = 0;
        for (Surface s : mOutputs) {
            int sw = 0, sh = 0;
            if (s.huskReader != null) { sw = s.huskReader.getWidth(); sh = s.huskReader.getHeight(); if (s.huskReader.getImageFormat() == android.graphics.ImageFormat.JPEG) { if ((long) sw * sh > (long) jw * jh) { jw = sw; jh = sh; } continue; } }
            else if (s.huskTexture != null) { int[] sz = s.huskTexture.huskSize(); sw = sz[0]; sh = sz[1]; }
            else if (s.huskView != null) { int[] sz = s.huskView.huskSurfaceSize(); sw = sz[0]; sh = sz[1]; }
            else { sw = s.huskWidth; sh = s.huskHeight; }
            if ((long) sw * sh > (long) w * h) { w = sw; h = sh; }
        }
        if (w == 0) { w = jw; h = jh; }
        if (w < h) { int t = w; w = h; h = t; }
        if (w == 0) { w = 1280; h = 720; }
        mSource.configure(w, h, 0);
        mSource.setHandler(this);
        boolean ok = mSource.start();
        android.util.Log.i(TAG, "session on camera " + mDevice.getId() + ": " + mOutputs.size() + " outputs, capture " + mSource.width() + "x" + mSource.height() + (ok ? "" : " (not running)"));
        return ok;
    }
    boolean huskIsClosed() { return mClosed; }
    public CameraDevice getDevice() { return mDevice; }
    public void prepare(Surface surface) { CameraDeviceImpl.run(mH, mE, () -> mCb.onSurfacePrepared(this, surface)); }
    public void tearDown(Surface surface) {}
    public void finalizeOutputConfigurations(List<OutputConfiguration> outputConfigs) {}
    public boolean isReprocessable() { return false; }
    public Surface getInputSurface() { return null; }

    private void check(CaptureRequest r) {
        if (mClosed) throw new IllegalStateException("Session has been closed; further changes are illegal.");
        if (r == null) throw new IllegalArgumentException("request was null");
        if (r.getTargets().isEmpty()) throw new IllegalArgumentException("Each request must have at least one Surface target");
        for (Surface s : r.getTargets()) if (!mOutputs.contains(s)) throw new IllegalArgumentException("Each target must be one of the session's configured outputs");
    }
    public int capture(CaptureRequest request, CaptureCallback cb, Handler handler) { return single(request, cb, handler, null); }
    @Override public int captureSingleRequest(CaptureRequest request, Executor executor, CaptureCallback cb) { return single(request, cb, null, executor); }
    private int single(CaptureRequest request, CaptureCallback cb, Handler h, Executor e) {
        check(request);
        if (cb != null && h == null && e == null) h = CameraDeviceImpl.handlerOrCurrent(null);
        int seq;
        synchronized (mQueue) { seq = ++mSeq; mQueue.add(new Req(request, cb, h, e, seq, false, 1)); }
        return seq;
    }
    public int captureBurst(List<CaptureRequest> requests, CaptureCallback cb, Handler handler) { return burst(requests, cb, handler, null); }
    @Override public int captureBurstRequests(List<CaptureRequest> requests, Executor executor, CaptureCallback cb) { return burst(requests, cb, null, executor); }
    private int burst(List<CaptureRequest> requests, CaptureCallback cb, Handler h, Executor e) {
        if (requests == null || requests.isEmpty()) throw new IllegalArgumentException("Requests must have at least one element");
        for (CaptureRequest r : requests) check(r);
        if (cb != null && h == null && e == null) h = CameraDeviceImpl.handlerOrCurrent(null);
        int seq;
        synchronized (mQueue) { seq = ++mSeq; int n = requests.size(); for (CaptureRequest r : requests) mQueue.add(new Req(r, cb, h, e, seq, false, n)); }
        return seq;
    }
    public int setRepeatingRequest(CaptureRequest request, CaptureCallback cb, Handler handler) { return repeating(java.util.Collections.singletonList(request), cb, handler, null); }
    @Override public int setSingleRepeatingRequest(CaptureRequest request, Executor executor, CaptureCallback cb) { return repeating(java.util.Collections.singletonList(request), cb, null, executor); }
    public int setRepeatingBurst(List<CaptureRequest> requests, CaptureCallback cb, Handler handler) { return repeating(requests, cb, handler, null); }
    @Override public int setRepeatingBurstRequests(List<CaptureRequest> requests, Executor executor, CaptureCallback cb) { return repeating(requests, cb, null, executor); }
    private int repeating(List<CaptureRequest> requests, CaptureCallback cb, Handler h, Executor e) {
        if (requests == null || requests.isEmpty()) throw new IllegalArgumentException("Requests must have at least one element");
        for (CaptureRequest r : requests) check(r);
        if (cb != null && h == null && e == null) h = CameraDeviceImpl.handlerOrCurrent(null);
        int seq; List<Req> old;
        synchronized (mQueue) {
            seq = ++mSeq; old = mRepeating;
            ArrayList<Req> list = new ArrayList<>();
            for (CaptureRequest r : requests) list.add(new Req(r, cb, h, e, seq, true, 0));
            mRepeating = list; mRepeatIndex = 0;
        }
        if (old != null) sequenceDone(old.get(0), mFrame);
        return seq;
    }
    public void stopRepeating() {
        if (mClosed) throw new IllegalStateException("Session has been closed; further changes are illegal.");
        List<Req> old;
        synchronized (mQueue) { old = mRepeating; mRepeating = null; }
        if (old != null) sequenceDone(old.get(0), mFrame);
        maybeReady();
    }
    public void abortCaptures() {
        if (mClosed) throw new IllegalStateException("Session has been closed; further changes are illegal.");
        List<Req> old; ArrayList<Req> dropped = new ArrayList<>();
        synchronized (mQueue) { old = mRepeating; mRepeating = null; dropped.addAll(mQueue); mQueue.clear(); }
        if (old != null) aborted(old.get(0));
        int last = -1;
        for (Req r : dropped) { if (r.seq != last) aborted(r); last = r.seq; }
        maybeReady();
    }
    private void maybeReady() {
        boolean idle;
        synchronized (mQueue) { idle = mQueue.isEmpty() && mRepeating == null; }
        if (idle && !mClosed) { mActive = false; CameraDeviceImpl.run(mH, mE, () -> { if (!mClosed) mCb.onReady(this); }); }
    }
    public void close() {
        if (mClosed) return;
        mClosed = true;
        if (mSource.handler() == this) mSource.setHandler(null);
        mDevice.huskSessionClosed(this);
        abortQuietly();
        CameraDeviceImpl.run(mH, mE, () -> mCb.onClosed(this));
    }
    /** The device replaced or closed this session. */
    void huskClosedBy(CameraDeviceImpl dev) {
        if (mClosed) return;
        mClosed = true;
        if (mSource.handler() == this) mSource.setHandler(null);
        abortQuietly();
        CameraDeviceImpl.run(mH, mE, () -> mCb.onClosed(this));
    }
    private void abortQuietly() {
        List<Req> old; ArrayList<Req> dropped = new ArrayList<>();
        synchronized (mQueue) { old = mRepeating; mRepeating = null; dropped.addAll(mQueue); mQueue.clear(); }
        if (old != null) aborted(old.get(0));
        int last = -1;
        for (Req r : dropped) { if (r.seq != last) aborted(r); last = r.seq; }
    }
    private void aborted(Req r) { if (r.cb != null) CameraDeviceImpl.run(r.h, r.e, () -> r.cb.onCaptureSequenceAborted(this, r.seq)); }
    private void sequenceDone(Req r, long frame) { if (r.cb != null) CameraDeviceImpl.run(r.h, r.e, () -> r.cb.onCaptureSequenceCompleted(this, r.seq, frame)); }

    /* ---- the camera thread */
    public void handleFrame(long frame, int fw, int fh, long ts) {
        if (mClosed) return;
        Req r; boolean lastOfBurst = false, idleAfter = false;
        synchronized (mQueue) {
            r = mQueue.poll();
            if (r != null) { lastOfBurst = --r.left == 0 || mQueue.isEmpty() || mQueue.peek().seq != r.seq; }
            else if (mRepeating != null) { r = mRepeating.get(mRepeatIndex); mRepeatIndex = (mRepeatIndex + 1) % mRepeating.size(); }
            idleAfter = mQueue.isEmpty() && mRepeating == null;
        }
        if (r == null) { if (mActive) { mActive = false; CameraDeviceImpl.run(mH, mE, () -> { if (!mClosed) mCb.onReady(this); }); } return; }
        if (!mActive) { mActive = true; CameraDeviceImpl.run(mH, mE, () -> { if (!mClosed) mCb.onActive(this); }); }
        final Req req = r;
        final long fn = ++mFrame;
        apply(req.request);
        if (req.cb != null) CameraDeviceImpl.run(req.h, req.e, () -> req.cb.onCaptureStarted(this, req.request, ts, fn));
        for (Surface s : req.request.getTargets()) deliver(s, req.request, frame, fw, fh, ts);
        final TotalCaptureResult result = result(req.request, fn, ts);
        if (req.cb != null) CameraDeviceImpl.run(req.h, req.e, () -> req.cb.onCaptureCompleted(this, req.request, result));
        if (!req.repeating && lastOfBurst) sequenceDone(req, fn);
        if (idleAfter && !req.repeating) { mActive = false; CameraDeviceImpl.run(mH, mE, () -> { if (!mClosed) mCb.onReady(this); }); }
    }
    private void deliver(Surface s, CaptureRequest req, long frame, int fw, int fh, long ts) {
        try {
            if (s.huskReader != null) {
                Integer o = req.get(CaptureRequest.JPEG_ORIENTATION); Byte q = req.get(CaptureRequest.JPEG_QUALITY);
                s.huskReader.huskDeliver(mSource, frame, fw, fh, ts, q != null ? q & 0xff : 95, o != null ? o : 0);
            } else if (s.huskSink != null) {
                s.huskSink.onCameraFrame(mSource, frame, fw, fh, ts);
            } else {
                mSource.toSurface(s, frame, fw, fh, (mSource.orientation / 90) | (mSource.facing == 1 ? husk.CameraSource.FLIP_Y : 0));
            }
        } catch (RuntimeException e) { android.util.Log.e(TAG, "a frame was not delivered", e); }
    }
    /** A region's centre in the active array as a point across the frame (0..1). */
    private float[] point(MeteringRectangle[] regions) {
        if (regions == null || regions.length == 0 || regions[0].getMeteringWeight() == 0) return null;
        Rect a = mDevice.mChars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        Rect r = regions[0].getRect();
        return new float[] { (r.centerX() - a.left) / (float) a.width(), (r.centerY() - a.top) / (float) a.height() };
    }
    private void apply(CaptureRequest r) {
        Integer af = r.get(CaptureRequest.CONTROL_AF_MODE), trigger = r.get(CaptureRequest.CONTROL_AF_TRIGGER);
        MeteringRectangle[] regions = r.get(CaptureRequest.CONTROL_AF_REGIONS);
        if (regions == null) regions = r.get(CaptureRequest.CONTROL_AE_REGIONS);
        MeteringRectangle region = regions != null && regions.length > 0 ? regions[0] : null;
        boolean regionChanged = region == null ? mRegion != null : !region.equals(mRegion);
        int afMode = af != null ? af : CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE;
        float[] p = point(regions);
        if (trigger != null && trigger == CameraMetadata.CONTROL_AF_TRIGGER_START) {
            mSource.focus(p != null ? p[0] : -1, p != null ? p[1] : -1, afMode == CameraMetadata.CONTROL_AF_MODE_OFF ? 0 : 1);
            mSource.exposure(p != null ? p[0] : -1, p != null ? p[1] : -1, 2, bias(r));
            mAfScan = 6; mAfLocked = true;
        } else if (trigger != null && trigger == CameraMetadata.CONTROL_AF_TRIGGER_CANCEL) {
            mSource.focus(-1, -1, 2); mAfScan = 0; mAfLocked = false; mAfMode = -1;
        }
        if (afMode != mAfMode || regionChanged) {
            if (!mAfLocked) mSource.focus(p != null ? p[0] : -1, p != null ? p[1] : -1, afMode == CameraMetadata.CONTROL_AF_MODE_OFF ? 0 : afMode == CameraMetadata.CONTROL_AF_MODE_AUTO || afMode == CameraMetadata.CONTROL_AF_MODE_MACRO ? 1 : 2);
            mAfMode = afMode; mRegion = region;
        }
        Boolean aeLock = r.get(CaptureRequest.CONTROL_AE_LOCK);
        float bias = bias(r);
        boolean lock = aeLock != null && aeLock;
        if (bias != mBias || lock != mAeLock || regionChanged) { mSource.exposure(p != null ? p[0] : -1, p != null ? p[1] : -1, lock ? 0 : 2, bias); mBias = bias; mAeLock = lock; }
        Integer flash = r.get(CaptureRequest.FLASH_MODE), ae = r.get(CaptureRequest.CONTROL_AE_MODE);
        int fl = flash != null ? flash : CameraMetadata.FLASH_MODE_OFF;
        boolean torch = fl == CameraMetadata.FLASH_MODE_TORCH || (ae != null && ae == CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH) || (fl == CameraMetadata.FLASH_MODE_SINGLE && r.huskTemplate() == CameraDevice.TEMPLATE_STILL_CAPTURE);
        int want = torch ? 1 : 0;
        if (want != mFlash) { mSource.torch(torch); mFlash = want; }
        Float zoom = r.get(CaptureRequest.CONTROL_ZOOM_RATIO); Rect crop = r.get(CaptureRequest.SCALER_CROP_REGION);
        float z = zoom != null && zoom > 0 ? zoom : 1;
        if (z == 1 && crop != null && crop.width() > 0) { Rect a = mDevice.mChars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE); z = Math.max(1f, a.width() / (float) crop.width()); }
        if (z != mZoom) { mSource.zoom(z); mZoom = z; }
        Range<Integer> fps = r.get(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE);
        if (fps != null && !fps.equals(mFps) && (mFps == null || !fps.getLower().equals(mFps.getLower()) || !fps.getUpper().equals(mFps.getUpper()))) { mSource.fps(fps.getLower(), fps.getUpper()); mFps = fps; }
    }
    private static float bias(CaptureRequest r) { Integer c = r.get(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION); return c != null ? c / 6f : 0; }
    private TotalCaptureResult result(CaptureRequest req, long fn, long ts) {
        TotalCaptureResult res = new TotalCaptureResult(req, 0, fn);
        Integer af = req.get(CaptureRequest.CONTROL_AF_MODE);
        int afMode = af != null ? af : CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE;
        int afState;
        if (afMode == CameraMetadata.CONTROL_AF_MODE_OFF) afState = CameraMetadata.CONTROL_AF_STATE_INACTIVE;
        else if (mAfScan > 0) { mAfScan--; afState = mSource.focusing() || mAfScan > 3 ? CameraMetadata.CONTROL_AF_STATE_ACTIVE_SCAN : CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED; if (afState == CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED) mAfScan = 0; }
        else if (mAfLocked) afState = CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED;
        else if (afMode == CameraMetadata.CONTROL_AF_MODE_AUTO || afMode == CameraMetadata.CONTROL_AF_MODE_MACRO) afState = CameraMetadata.CONTROL_AF_STATE_INACTIVE;
        else afState = mSource.focusing() ? CameraMetadata.CONTROL_AF_STATE_PASSIVE_SCAN : CameraMetadata.CONTROL_AF_STATE_PASSIVE_FOCUSED;
        res.huskSet(CaptureResult.CONTROL_AF_STATE, afState);
        Integer pre = req.get(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER);
        res.huskSet(CaptureResult.CONTROL_AE_STATE, mAeLock ? CameraMetadata.CONTROL_AE_STATE_LOCKED : pre != null && pre == CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START ? CameraMetadata.CONTROL_AE_STATE_PRECAPTURE : CameraMetadata.CONTROL_AE_STATE_CONVERGED);
        Boolean awbLock = req.get(CaptureRequest.CONTROL_AWB_LOCK);
        res.huskSet(CaptureResult.CONTROL_AWB_STATE, awbLock != null && awbLock ? CameraMetadata.CONTROL_AWB_STATE_LOCKED : CameraMetadata.CONTROL_AWB_STATE_CONVERGED);
        res.huskSet(CaptureResult.SENSOR_TIMESTAMP, ts);
        res.huskSet(CaptureResult.LENS_STATE, afState == CameraMetadata.CONTROL_AF_STATE_ACTIVE_SCAN || afState == CameraMetadata.CONTROL_AF_STATE_PASSIVE_SCAN ? CameraMetadata.LENS_STATE_MOVING : CameraMetadata.LENS_STATE_STATIONARY);
        res.huskSet(CaptureResult.LENS_FOCUS_DISTANCE, 0.5f);
        res.huskSet(CaptureResult.FLASH_STATE, mSource.hasFlash() ? (mFlash == 1 ? CameraMetadata.FLASH_STATE_FIRED : CameraMetadata.FLASH_STATE_READY) : CameraMetadata.FLASH_STATE_UNAVAILABLE);
        res.huskSet(CaptureResult.REQUEST_PIPELINE_DEPTH, (byte) 2);
        res.huskSet(CaptureResult.REQUEST_FRAME_COUNT, (int) fn);
        res.huskSet(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW, 16000000L);
        res.huskSet(CaptureResult.STATISTICS_SCENE_FLICKER, 0);
        res.huskSet(CaptureResult.CONTROL_AF_SCENE_CHANGE, 0);
        res.huskSet(CaptureResult.STATISTICS_FACES, new android.hardware.camera2.params.Face[0]);
        return res;
    }
}
