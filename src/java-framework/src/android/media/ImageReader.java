package android.media;

import android.graphics.ImageFormat;
import android.os.Handler;
import android.view.Surface;
import java.nio.ByteBuffer;
import java.util.concurrent.Executor;

/**
 * Frames from a producer as Images. The camera (husk.CameraSource, through huskDeliver) is the producer Husk has: a
 * YUV_420_888 reader gets the frame's luma and interleaved chroma in sensor orientation, scaled to the reader's size; a JPEG
 * reader the frame encoded, turned by the request's JPEG_ORIENTATION; other formats an Image with no planes. Up to maxImages
 * wait to be acquired; past that the newest frame is dropped, where the platform would stall the camera.
 */
public class ImageReader implements AutoCloseable {
    public interface OnImageAvailableListener { void onImageAvailable(ImageReader reader); }
    private static final String TAG = "ImageReader";
    private final int mWidth, mHeight, mFormat, mMaxImages;
    private final long mUsage;
    private final Surface mSurface;
    private final java.util.ArrayDeque<Image> mQueue = new java.util.ArrayDeque<>();
    private OnImageAvailableListener mListener;
    private Handler mHandler;
    private Executor mExecutor;
    private boolean mClosed, mWarned;
    protected ImageReader() { this(1, 1, ImageFormat.PRIVATE, 1, 0); }
    protected ImageReader(int width, int height, int format, int maxImages, long usage) {
        if (width < 1 || height < 1) throw new IllegalArgumentException("The image dimensions must be positive");
        if (maxImages < 1) throw new IllegalArgumentException("Maximum outstanding image count must be at least 1");
        mWidth = width; mHeight = height; mFormat = format; mMaxImages = maxImages; mUsage = usage;
        mSurface = new Surface();
        mSurface.huskReader = this;
        mSurface.huskWidth = width; mSurface.huskHeight = height;
    }
    public static ImageReader newInstance(int width, int height, int format, int maxImages) { return new ImageReader(width, height, format, maxImages, 0); }
    public static ImageReader newInstance(int width, int height, int format, int maxImages, long usage) { return new ImageReader(width, height, format, maxImages, usage); }
    public static ImageReader newInstance(int width, int height, int format, int maxImages, android.hardware.camera2.MultiResolutionImageReader parent) { return newInstance(width, height, format, maxImages); }
    public int getWidth() { return mWidth; }
    public int getHeight() { return mHeight; }
    public int getImageFormat() { return mFormat; }
    public int getHardwareBufferFormat() { return mFormat; }
    public int getDataSpace() { return 0; }
    public int getMaxImages() { return mMaxImages; }
    public long getUsage() { return mUsage; }
    public Surface getSurface() { return mSurface; }
    public Image acquireLatestImage() {
        synchronized (mQueue) {
            if (mClosed) throw new IllegalStateException("ImageReader is closed");
            Image last = mQueue.pollLast();
            for (Image i : mQueue) i.close();
            mQueue.clear();
            return last;
        }
    }
    public Image acquireNextImage() { synchronized (mQueue) { if (mClosed) throw new IllegalStateException("ImageReader is closed"); return mQueue.poll(); } }
    public Image acquireNextImageNoThrowISE() { synchronized (mQueue) { return mClosed ? null : mQueue.poll(); } }
    public void setOnImageAvailableListener(OnImageAvailableListener l, Handler h) {
        synchronized (mQueue) { mListener = l; mExecutor = null; mHandler = l == null ? null : h != null ? h : new Handler(android.os.Looper.myLooper() != null ? android.os.Looper.myLooper() : android.os.Looper.getMainLooper()); }
    }
    public void setOnImageAvailableListenerWithExecutor(OnImageAvailableListener l, Executor e) { synchronized (mQueue) { mListener = l; mExecutor = e; mHandler = null; } }
    public void detachImage(Image image) {}
    public void discardFreeBuffers() {}
    public void close() { synchronized (mQueue) { mClosed = true; mQueue.clear(); mListener = null; } mSurface.release(); }
    protected void finalize() { close(); }
    public boolean huskClosed() { return mClosed; }

    /** The camera's frame for this reader: as the reader's format and size, then the listener is told. */
    public void huskDeliver(husk.CameraSource source, long frame, int fw, int fh, long timestampNs, int jpegQuality, int jpegRotation) {
        if (mClosed) return;
        HuskImage img;
        if (mFormat == ImageFormat.JPEG || mFormat == ImageFormat.HEIC) {
            byte[] d = source.jpeg(frame, (jpegRotation / 90) & 3, jpegQuality, mWidth, mHeight);
            if (d == null) return;
            img = new HuskImage(this, ImageFormat.JPEG, mWidth, mHeight, timestampNs, new Image.Plane[] { new HuskPlane(ByteBuffer.wrap(d), 0, 0) });
        } else if (mFormat == ImageFormat.YUV_420_888 || mFormat == ImageFormat.NV21 || mFormat == ImageFormat.YV12) {
            int w = mWidth & ~1, h = mHeight & ~1;
            byte[] d = new byte[w * h + w * (h / 2)];
            if (!source.yuv(frame, d, w, h, mFormat == ImageFormat.NV21)) return;
            Image.Plane[] planes = mFormat == ImageFormat.YUV_420_888
                ? new Image.Plane[] { new HuskPlane(ByteBuffer.wrap(d, 0, w * h).slice(), w, 1), new HuskPlane(ByteBuffer.wrap(d, w * h, w * (h / 2) - 1).slice(), w, 2), new HuskPlane(ByteBuffer.wrap(d, w * h + 1, w * (h / 2) - 1).slice(), w, 2) }
                : new Image.Plane[] { new HuskPlane(ByteBuffer.wrap(d), w, 1) };
            img = new HuskImage(this, mFormat, w, h, timestampNs, planes);
        } else {
            img = new HuskImage(this, mFormat, mWidth, mHeight, timestampNs, new Image.Plane[0]);
        }
        OnImageAvailableListener l; Handler h; Executor e;
        synchronized (mQueue) {
            if (mClosed) return;
            if (mQueue.size() >= mMaxImages) {
                if (!mWarned) { mWarned = true; android.util.Log.w(TAG, "the app holds " + mMaxImages + " images of a " + mWidth + "x" + mHeight + " reader: frames are dropped"); }
                return;
            }
            mQueue.add(img);
            l = mListener; h = mHandler; e = mExecutor;
        }
        if (l == null) return;
        Runnable r = () -> { OnImageAvailableListener cur; synchronized (mQueue) { cur = mListener; } if (cur != null && !mClosed) cur.onImageAvailable(this); };
        if (e != null) e.execute(r); else if (h != null) h.post(r); else r.run();
    }

    static final class HuskPlane extends Image.Plane {
        private final ByteBuffer mBuffer; private final int mRowStride, mPixelStride;
        HuskPlane(ByteBuffer b, int rowStride, int pixelStride) { mBuffer = b; mRowStride = rowStride; mPixelStride = pixelStride; }
        public ByteBuffer getBuffer() { return mBuffer; }
        public int getRowStride() { return mRowStride; }
        public int getPixelStride() { return mPixelStride; }
    }
    public static class ImagePlane extends Image.Plane {
        protected ImagePlane() {}
        public ByteBuffer getBuffer() { return null; }
        public int getRowStride() { return 0; }
        public int getPixelStride() { return 0; }
    }
    static final class HuskImage extends Image {
        private final ImageReader mReader; private final int mFormat, mWidth, mHeight; private long mTimestamp; private final Plane[] mPlanes;
        HuskImage(ImageReader r, int format, int w, int h, long ts, Plane[] planes) { mReader = r; mFormat = format; mWidth = w; mHeight = h; mTimestamp = ts; mPlanes = planes; mIsImageValid = true; }
        public int getFormat() { return mFormat; }
        public int getWidth() { return mWidth; }
        public int getHeight() { return mHeight; }
        public long getTimestamp() { return mTimestamp; }
        @Override public void setTimestamp(long t) { mTimestamp = t; }
        public Plane[] getPlanes() { throwISEIfImageIsInvalid(); return mPlanes; }
        public void close() { mIsImageValid = false; }
    }
    public static final class Builder {
        private final int mW, mH; private int mFormat = ImageFormat.PRIVATE, mMax = 1; private long mUsage;
        public Builder(int width, int height) { mW = width; mH = height; }
        public Builder setImageFormat(int format) { mFormat = format; return this; }
        public Builder setMaxImages(int max) { mMax = max; return this; }
        public Builder setUsage(long usage) { mUsage = usage; return this; }
        public Builder setDefaultDataSpace(int ds) { return this; }
        public Builder setDefaultHardwareBufferFormat(int f) { mFormat = f; return this; }
        public ImageReader build() { return new ImageReader(mW, mH, mFormat, mMax, mUsage); }
    }
}
