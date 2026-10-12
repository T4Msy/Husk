package android.media;

import android.graphics.Rect;

/** A frame with planes the app reads or writes: MediaCodec's input images (getInputImage) are the only ones Husk makes. */
public abstract class Image implements AutoCloseable {
    protected boolean mIsImageValid = true;
    private Rect mCropRect;
    private int mDataSpace;
    private android.hardware.SyncFence mFence;
    protected Image() {}
    public abstract void close();
    public abstract int getFormat();
    public abstract int getWidth();
    public abstract int getHeight();
    public abstract long getTimestamp();
    public abstract Plane[] getPlanes();
    public int getScalingMode() { return 0; }
    public int getTransform() { return 0; }
    public int getPlaneCount() { Plane[] p = getPlanes(); return p != null ? p.length : 0; }
    public Rect getCropRect() { throwISEIfImageIsInvalid(); return mCropRect != null ? new Rect(mCropRect) : new Rect(0, 0, getWidth(), getHeight()); }
    public void setCropRect(Rect r) { throwISEIfImageIsInvalid(); mCropRect = r != null ? new Rect(r) : null; if (mCropRect != null && !mCropRect.intersect(0, 0, getWidth(), getHeight())) mCropRect.setEmpty(); }
    public int getDataSpace() { return mDataSpace; }
    public void setDataSpace(int d) { mDataSpace = d; }
    public android.hardware.SyncFence getFence() { return mFence; }
    public void setFence(android.hardware.SyncFence f) { mFence = f; }
    public android.hardware.HardwareBuffer getHardwareBuffer() { return null; }
    public boolean isAttachable() { return false; }
    public void setTimestamp(long t) {}
    protected void throwISEIfImageIsInvalid() { if (!mIsImageValid) throw new IllegalStateException("Image is already closed"); }
    public static abstract class Plane {
        protected Plane() {}
        public abstract java.nio.ByteBuffer getBuffer();
        public abstract int getPixelStride();
        public abstract int getRowStride();
    }
}
