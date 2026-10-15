package husk;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.view.Surface;

/**
 * One open camera, shared by the two Android camera APIs: the native capture (husk.Camera), a thread that takes each frame
 * as it arrives and hands it to the API's handler, and the ways a frame reaches a consumer. A Surface made on a
 * SurfaceTexture gets the frame as RGBA: a TextureView's texture as its next buffer, a GL texture (SurfaceTexture(texName))
 * as a direct buffer that updateTexImage uploads; a SurfaceView's surface gets it scaled onto its buffer; an ImageReader's
 * gets planes or a JPEG (ImageReader.huskDeliver); an encoder's input surface (a husk.FrameSink) gets RGBA in sensor
 * orientation at the size it asks for, as a recording stream would.
 */
public final class CameraSource {
    /** What the API layer does with each frame, on the camera thread; the frame is freed after it returns. */
    public interface FrameHandler { void handleFrame(long frame, int width, int height, long timestampNs); }
    public static final int FLIP_X = Camera.FLIP_X, FLIP_Y = Camera.FLIP_Y;
    /** 0 back, 1 front, and the clockwise turn that makes its frames upright (Android's SENSOR_ORIENTATION). */
    public final int facing, orientation;
    private long mH;
    private int mW, mHgt;
    private Thread mThread;
    private volatile boolean mRun;
    private volatile FrameHandler mHandler;
    private Bitmap mBitmap;
    private final Paint mPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private static final String TAG = "CameraSource";

    public static int count() { return Camera.count(); }
    public static boolean has(int facing) { return Camera.has(facing); }
    /** The cameras as Android numbers them: id 0 the back camera and 1 the front; a device with no back camera gives its front one as 0. */
    public static int[] facings() {
        boolean back = Camera.has(0), front = Camera.has(1);
        return back && front ? new int[] { 0, 1 } : back ? new int[] { 0 } : front ? new int[] { 1 } : new int[0];
    }
    public static CameraSource open(int facing) {
        long h = Camera.open(facing);
        if (h == 0) { android.util.Log.w(TAG, "camera " + facing + " did not open"); return null; }
        return new CameraSource(facing, h);
    }
    private CameraSource(int facing, long h) { this.facing = facing; orientation = facing == 1 ? 270 : 90; mH = h; size(); }
    private void size() { int s = mH != 0 ? Camera.size(mH) : 0; mW = s >>> 16; mHgt = s & 0xffff; }
    /** Frames of about the wanted size (the session preset that covers it), at fps (0: the camera's choice). */
    public synchronized boolean configure(int width, int height, int fps) { if (mH == 0) return false; boolean ok = Camera.configure(mH, width, height, fps); size(); return ok; }
    public int width() { return mW; }
    public int height() { return mHgt; }
    public boolean isOpen() { return mH != 0; }
    public void setHandler(FrameHandler h) { mHandler = h; }
    public FrameHandler handler() { return mHandler; }
    public synchronized boolean start() {
        if (mH == 0 || !Camera.start(mH)) return false;
        size();
        if (mThread == null) { mRun = true; mThread = new Thread(this::loop, "CameraThread-" + facing); mThread.setDaemon(true); mThread.start(); }
        return true;
    }
    public synchronized void stop() { if (mH != 0) Camera.stop(mH); }
    public void close() {
        long h; Thread t;
        synchronized (this) { h = mH; mH = 0; mRun = false; t = mThread; mThread = null; }
        if (t != null && t != Thread.currentThread()) { t.interrupt(); try { t.join(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
        if (h != 0) Camera.close(h);
    }
    private void loop() {
        long[] info = new long[3];
        while (mRun) {
            long h = mH;
            if (h == 0) break;
            long f = Camera.waitFrame(h, 250);
            if (f == 0) continue;
            try {
                Camera.frameInfo(f, info);
                FrameHandler fh = mHandler;
                if (fh != null && mRun) fh.handleFrame(f, (int) info[1], (int) info[2], info[0]);
            } catch (Throwable e) {
                android.util.Log.e(TAG, "frame handler failed", e);
            } finally { Camera.freeFrame(f); }
        }
    }

    /** The frame, flipped and turned per transform, to a Surface: a texture's next buffer, or scaled onto a view's. */
    public boolean toSurface(Surface s, long frame, int fw, int fh, int transform) {
        if (s == null || !s.isValid()) return false;
        SurfaceTexture t = s.huskTexture;
        if (t != null) return toTexture(t, frame, fw, fh, transform);
        if (s.huskSink != null) return toSink(s.huskSink, frame, fw, fh, transform, 0);
        return draw(s, frame, fw, fh, transform);
    }
    /** The frame as RGBA of the sink's size (the frame's when it names none), in a buffer the sink keeps. */
    public boolean toSink(FrameSink sink, long frame, int fw, int fh, int transform, long ptsNs) {
        int w = sink.huskWidth(), h = sink.huskHeight();
        if (w <= 0 || h <= 0) { w = (transform & 1) != 0 ? fh : fw; h = (transform & 1) != 0 ? fw : fh; }
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocateDirect(w * h * 4).order(java.nio.ByteOrder.nativeOrder());
        if (!Camera.frameToBuffer(frame, b, w, h, transform)) return false;
        sink.huskFrame(b, w, h, false, ptsNs);
        return true;
    }
    public boolean toTexture(SurfaceTexture t, long frame, int fw, int fh, int transform) {
        if (t == null || t.isReleased()) return false;
        int w = (transform & 1) != 0 ? fh : fw, h = (transform & 1) != 0 ? fw : fh;
        if (t.huskIsGL()) {
            java.nio.ByteBuffer b = t.huskGLBuffer(w * h * 4);
            if (b == null || !Camera.frameToBuffer(frame, b, w, h, transform)) return false;
            t.huskPostGL(b, w, h);
            return true;
        }
        t.setDefaultBufferSize(w, h);
        return draw(new Surface(t), frame, fw, fh, transform);
    }
    private boolean draw(Surface s, long frame, int fw, int fh, int transform) {
        int w = (transform & 1) != 0 ? fh : fw, h = (transform & 1) != 0 ? fw : fh;
        if (mBitmap == null || mBitmap.getWidth() != w || mBitmap.getHeight() != h) mBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        if (!Camera.frameToBitmap(frame, mBitmap, transform)) return false;
        Canvas c;
        try { c = s.lockCanvas(null); } catch (RuntimeException e) { return false; }
        if (c == null) return false;
        try {
            if (c.getWidth() == w && c.getHeight() == h) c.drawBitmap(mBitmap, 0, 0, null);
            else c.drawBitmap(mBitmap, null, new Rect(0, 0, c.getWidth(), c.getHeight()), mPaint);
        } finally { s.unlockCanvasAndPost(c); }
        return true;
    }
    public byte[] jpeg(long frame, int transform, int quality, int maxWidth, int maxHeight) { return Camera.frameToJpeg(frame, transform, quality, maxWidth, maxHeight); }
    public boolean yuv(long frame, byte[] dst, int width, int height, boolean nv21) { return Camera.frameToYuv(frame, dst, width, height, nv21); }

    public boolean hasFlash() { return mH != 0 && Camera.hasFlash(mH); }
    public boolean torch(boolean on) { return mH != 0 && Camera.torch(mH, on); }
    public boolean canFocus() { return mH != 0 && Camera.canFocus(mH); }
    /** mode 0 locked, 1 a one-time scan at the point, 2 continuous; x, y in 0..1 across the frame, or -1 for the centre */
    public void focus(float x, float y, int mode) { if (mH != 0) Camera.focus(mH, x, y, mode); }
    public boolean focusing() { return mH != 0 && Camera.focusing(mH); }
    public void exposure(float x, float y, int mode, float biasEv) { if (mH != 0) Camera.exposure(mH, x, y, mode, biasEv); }
    public float maxZoom() { return mH != 0 ? Camera.maxZoom(mH) : 1; }
    public void zoom(float ratio) { if (mH != 0) Camera.zoom(mH, ratio); }
    public void fps(int min, int max) { if (mH != 0) Camera.fps(mH, min, max); }
}
