package android.media;

/** A frame from an ImageReader: its planes as byte buffers (YUV_420_888: Y, then U and V interleaved with pixel stride 2; JPEG: one). */
public abstract class Image implements AutoCloseable {
    public static abstract class Plane {
        protected Plane() {}
        public abstract int getRowStride();
        public abstract int getPixelStride();
        public abstract java.nio.ByteBuffer getBuffer();
    }
    protected boolean mIsImageValid;
    protected Image() {}
    public abstract int getFormat();
    public abstract int getWidth();
    public abstract int getHeight();
    public abstract long getTimestamp();
    public void setTimestamp(long timestamp) {}
    public int getTransform() { return 0; }
    public int getScalingMode() { return 0; }
    public int getPlaneCount() { return getPlanes().length; }
    public android.hardware.HardwareBuffer getHardwareBuffer() { return null; }
    public int getDataSpace() { return 0; }
    public void setDataSpace(int dataSpace) {}
    public android.graphics.Rect getCropRect() { return new android.graphics.Rect(0, 0, getWidth(), getHeight()); }
    public void setCropRect(android.graphics.Rect r) {}
    public android.hardware.SyncFence getFence() { return null; }
    public void setFence(android.hardware.SyncFence f) {}
    public boolean isAttachable() { return false; }
    public abstract Plane[] getPlanes();
    public abstract void close();
    protected void throwISEIfImageIsInvalid() { if (!mIsImageValid) throw new IllegalStateException("Image is already closed"); }
}
