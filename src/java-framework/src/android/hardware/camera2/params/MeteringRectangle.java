package android.hardware.camera2.params;

import android.graphics.Point;
import android.graphics.Rect;
import android.util.Size;

/** A weighted region of the sensor's active array, for focus and exposure metering. */
public final class MeteringRectangle {
    public static final int METERING_WEIGHT_MIN = 0, METERING_WEIGHT_MAX = 1000, METERING_WEIGHT_DONT_CARE = 0;
    private final int mX, mY, mWidth, mHeight, mWeight;
    public MeteringRectangle(int x, int y, int width, int height, int meteringWeight) {
        if (x < 0 || y < 0 || width < 0 || height < 0) throw new IllegalArgumentException("x, y, width and height must be non-negative");
        if (meteringWeight < METERING_WEIGHT_MIN || meteringWeight > METERING_WEIGHT_MAX) throw new IllegalArgumentException("meteringWeight out of range");
        mX = x; mY = y; mWidth = width; mHeight = height; mWeight = meteringWeight;
    }
    public MeteringRectangle(Point xy, Size dimensions, int meteringWeight) { this(xy.x, xy.y, dimensions.getWidth(), dimensions.getHeight(), meteringWeight); }
    public MeteringRectangle(Rect rect, int meteringWeight) { this(rect.left, rect.top, rect.width(), rect.height(), meteringWeight); }
    public int getX() { return mX; }
    public int getY() { return mY; }
    public int getWidth() { return mWidth; }
    public int getHeight() { return mHeight; }
    public int getMeteringWeight() { return mWeight; }
    public Point getUpperLeftPoint() { return new Point(mX, mY); }
    public Size getSize() { return new Size(mWidth, mHeight); }
    public Rect getRect() { return new Rect(mX, mY, mX + mWidth, mY + mHeight); }
    public boolean equals(MeteringRectangle o) { return o != null && o.mX == mX && o.mY == mY && o.mWidth == mWidth && o.mHeight == mHeight && o.mWeight == mWeight; }
    @Override public boolean equals(Object o) { return o instanceof MeteringRectangle && equals((MeteringRectangle) o); }
    @Override public int hashCode() { return ((mX * 31 + mY) * 31 + mWidth) * 31 + mHeight; }
    @Override public String toString() { return "(x:" + mX + ", y:" + mY + ", w:" + mWidth + ", h:" + mHeight + ", wt:" + mWeight + ")"; }
}
