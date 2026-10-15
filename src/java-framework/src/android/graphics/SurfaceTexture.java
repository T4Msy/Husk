package android.graphics;

/**
 * A stream of frames from a producer (a Surface made on it: MediaCodec's video, lockCanvas drawing, an EGL window surface) to a
 * consumer. Husk keeps the frames in software: a back buffer the producer draws, and the front one the consumer (a TextureView)
 * shows. A GL consumer (a texture made on a texture name, sampled through GL_TEXTURE_EXTERNAL_OES, which the GL layer treats as
 * a plain 2D texture) gets frames the camera posts as RGBA in direct buffers (huskPostGL): updateTexImage uploads the newest
 * into the texture on the app's GL thread, and getTransformMatrix is then the vertical flip Android's buffers have, the
 * frames already turned upright.
 */
public class SurfaceTexture {
    public interface OnFrameAvailableListener { void onFrameAvailable(SurfaceTexture t); }
    public interface OnSetFrameRateListener { void onSetFrameRate(SurfaceTexture t, float rate, int compat, int strategy); }
    public static class OutOfResourcesException extends Exception { public OutOfResourcesException() {} public OutOfResourcesException(String s) { super(s); } }
    private int mTex;
    private final Object mLock = new Object();
    private int mW, mH;
    private Bitmap mFront, mBack;
    private Canvas mBackCanvas;
    private boolean mLocked, mReleased;
    private long mTimestamp;
    private OnFrameAvailableListener mListener;
    private android.os.Handler mHandler;
    /** The TextureView showing this texture, told when a frame arrives. */
    public android.view.View huskView;
    public SurfaceTexture(int texName) { this(texName, false); }
    public SurfaceTexture(int texName, boolean singleBufferMode) { mTex = texName; }
    public SurfaceTexture(boolean singleBufferMode) { mTex = 0; }
    public void setOnFrameAvailableListener(OnFrameAvailableListener l) { setOnFrameAvailableListener(l, null); }
    public void setOnFrameAvailableListener(OnFrameAvailableListener l, android.os.Handler h) {
        mListener = l;
        mHandler = l == null ? null : h != null ? h : new android.os.Handler(android.os.Looper.myLooper() != null ? android.os.Looper.myLooper() : android.os.Looper.getMainLooper());
    }
    public void setOnSetFrameRateListener(OnSetFrameRateListener l, android.os.Handler h) {}
    public void setDefaultBufferSize(int w, int h) { synchronized (mLock) { mW = w; mH = h; } }
    /** The buffer size producers draw at (the consumer's size when it never set one). */
    public int[] huskSize() { synchronized (mLock) { return new int[] { mW, mH }; } }
    public Canvas huskLock(Rect dirty) {
        synchronized (mLock) {
            if (mReleased) return null;
            while (mLocked) { try { mLock.wait(); } catch (InterruptedException e) { return null; } if (mReleased) return null; }
            int w = mW > 0 ? mW : 1, h = mH > 0 ? mH : 1;
            if (mBack == null || mBack.getWidth() != w || mBack.getHeight() != h) {
                if (mBackCanvas != null) mBackCanvas.huskRelease();
                mBack = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                mBackCanvas = new Canvas(mBack);
            }
            mLocked = true;
            mBackCanvas.save();
            if (dirty != null) mBackCanvas.clipRect(dirty);
            return mBackCanvas;
        }
    }
    public void huskPost(Canvas c) {
        synchronized (mLock) {
            if (!mLocked) return;
            mBackCanvas.restore();
            Bitmap f = mFront; mFront = mBack; mBack = f;
            if (mBack != null && (mBack.getWidth() != mFront.getWidth() || mBack.getHeight() != mFront.getHeight())) mBack = null;
            mBackCanvas = mBack != null ? new Canvas(mBack) : null;
            mLocked = false; mTimestamp = System.nanoTime();
            mLock.notifyAll();
        }
        android.view.View v = huskView;
        if (v != null) v.postInvalidate();
        OnFrameAvailableListener l = mListener; android.os.Handler h = mHandler;
        if (l != null && h != null) h.post(() -> l.onFrameAvailable(this));
    }
    public boolean huskValid() { return !mReleased; }
    private Bitmap mStage;
    /** A native producer's frame (husk-tl-egl.c's TextureView windows, ANativeWindow_lock): w x h RGBA at addr, bottom row first when flip. */
    public void huskPostRgba(long addr, int w, int h, boolean flip) {
        if (mStage == null || mStage.getWidth() != w || mStage.getHeight() != h) mStage = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        mStage.huskSetRgba(addr);
        Canvas c = huskLock(null);
        if (c == null) return;
        try {
            c.save();
            if (flip) c.scale(1, -1, c.getWidth() / 2f, c.getHeight() / 2f);
            c.drawColor(0, PorterDuff.Mode.CLEAR);
            c.drawBitmap(mStage, null, new Rect(0, 0, c.getWidth(), c.getHeight()), null);
            c.restore();
        } finally { huskPost(c); }
    }
    /** The latest frame (null before the first). */
    public Bitmap huskFront() { synchronized (mLock) { return mFront; } }

    // ---- a GL consumer: RGBA frames in direct buffers, uploaded by updateTexImage
    private java.nio.ByteBuffer mGLPending;
    private final java.nio.ByteBuffer[] mGLPool = new java.nio.ByteBuffer[3];
    private int mGLNext, mGLW, mGLH, mTexW, mTexH;
    private boolean mGLFrames;
    /** Made on a texture name and shown by no TextureView: frames go to the GL texture. */
    public boolean huskIsGL() { return mTex != 0 && huskView == null; }
    /** A direct buffer of at least bytes for a producer to fill: never the one waiting for updateTexImage, which holds the lock while it uploads. */
    public java.nio.ByteBuffer huskGLBuffer(int bytes) {
        synchronized (mLock) {
            for (int i = 0; i < mGLPool.length; i++) {
                int k = (mGLNext + i) % mGLPool.length;
                if (mGLPool[k] != null && mGLPool[k] == mGLPending) continue;
                if (mGLPool[k] == null || mGLPool[k].capacity() < bytes) mGLPool[k] = java.nio.ByteBuffer.allocateDirect(bytes).order(java.nio.ByteOrder.nativeOrder());
                mGLNext = (k + 1) % mGLPool.length;
                mGLPool[k].rewind();
                return mGLPool[k];
            }
            return null;
        }
    }
    public java.nio.ByteBuffer huskPendingGL() { synchronized (mLock) { return mGLPending; } }
    /** A producer's frame for the GL texture: w x h RGBA rows, top first, in a buffer from huskGLBuffer. */
    public void huskPostGL(java.nio.ByteBuffer rgba, int w, int h) {
        synchronized (mLock) {
            if (mReleased) return;
            mGLPending = rgba; mGLW = w; mGLH = h; mGLFrames = true; mTimestamp = System.nanoTime();
        }
        OnFrameAvailableListener l = mListener; android.os.Handler h2 = mHandler;
        if (l != null && h2 != null) h2.post(() -> l.onFrameAvailable(this));
    }
    public void updateTexImage() {
        if (mReleased) throw new IllegalStateException("updateTexImage on a released SurfaceTexture");
        synchronized (mLock) {
            java.nio.ByteBuffer b = mGLPending;
            if (b == null || mTex == 0) return;
            int w = mGLW, h = mGLH;
            b.rewind();
            android.opengl.GLES20.glBindTexture(android.opengl.GLES20.GL_TEXTURE_2D, mTex);
            android.opengl.GLES20.glPixelStorei(android.opengl.GLES20.GL_UNPACK_ALIGNMENT, 4);
            if (w != mTexW || h != mTexH) {
                android.opengl.GLES20.glTexImage2D(android.opengl.GLES20.GL_TEXTURE_2D, 0, android.opengl.GLES20.GL_RGBA, w, h, 0, android.opengl.GLES20.GL_RGBA, android.opengl.GLES20.GL_UNSIGNED_BYTE, b);
                android.opengl.GLES20.glTexParameteri(android.opengl.GLES20.GL_TEXTURE_2D, android.opengl.GLES20.GL_TEXTURE_MIN_FILTER, android.opengl.GLES20.GL_LINEAR);
                android.opengl.GLES20.glTexParameteri(android.opengl.GLES20.GL_TEXTURE_2D, android.opengl.GLES20.GL_TEXTURE_MAG_FILTER, android.opengl.GLES20.GL_LINEAR);
                android.opengl.GLES20.glTexParameteri(android.opengl.GLES20.GL_TEXTURE_2D, android.opengl.GLES20.GL_TEXTURE_WRAP_S, android.opengl.GLES20.GL_CLAMP_TO_EDGE);
                android.opengl.GLES20.glTexParameteri(android.opengl.GLES20.GL_TEXTURE_2D, android.opengl.GLES20.GL_TEXTURE_WRAP_T, android.opengl.GLES20.GL_CLAMP_TO_EDGE);
                mTexW = w; mTexH = h;
            } else android.opengl.GLES20.glTexSubImage2D(android.opengl.GLES20.GL_TEXTURE_2D, 0, 0, 0, w, h, android.opengl.GLES20.GL_RGBA, android.opengl.GLES20.GL_UNSIGNED_BYTE, b);
            mGLPending = null;
        }
    }
    public void releaseTexImage() {}
    public void detachFromGLContext() { mTex = 0; mTexW = mTexH = 0; }
    public void attachToGLContext(int tex) { mTex = tex; mTexW = mTexH = 0; }
    /** Identity, or for frames uploaded by updateTexImage the vertical flip of a top-down buffer sampled by GL. */
    public void getTransformMatrix(float[] m) {
        android.opengl.Matrix.setIdentityM(m, 0);
        if (mGLFrames) { m[5] = -1; m[13] = 1; }
    }
    public long getTimestamp() { return mTimestamp; }
    public int getDataSpace() { return 0; }
    public boolean isSingleBuffered() { return false; }
    public void release() { synchronized (mLock) { mReleased = true; mLock.notifyAll(); } }
    public boolean isReleased() { return mReleased; }
}
