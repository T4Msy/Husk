package android.graphics.drawable;

import android.graphics.*;
import android.os.SystemClock;

/**
 * {@code <animated-rotate>}: the wrapped drawable turned a step further every frameDuration, framesCount steps per turn, while
 * it is running (it starts when it becomes visible, as the platform's does). Hidden in the platform API, but inflated from XML by
 * progress indicators.
 */
public class AnimatedRotateDrawable extends DrawableWrapper implements Animatable, Runnable {
    private float mPx = 0.5f, mPy = 0.5f, mAngle;
    private boolean mPxRel = true, mPyRel = true, mRunning;
    private int mFrameDuration = 150, mFramesCount = 12;
    public AnimatedRotateDrawable() { super(null); }
    public AnimatedRotateDrawable(Drawable d) { super(d); }
    public void setFramesCount(int n) { mFramesCount = Math.max(1, n); }
    public void setFramesDuration(int ms) { mFrameDuration = Math.max(1, ms); }
    public void setPivotX(float x, boolean relative) { mPx = x; mPxRel = relative; }
    public void setPivotY(float y, boolean relative) { mPy = y; mPyRel = relative; }
    @Override public void draw(Canvas c) {
        Drawable d = getDrawable(); if (d == null) return;
        Rect b = getBounds();
        float px = mPxRel ? b.left + b.width() * mPx : b.left + mPx, py = mPyRel ? b.top + b.height() * mPy : b.top + mPy;
        int save = c.save();
        c.rotate(mAngle, px, py);
        d.draw(c);
        c.restoreToCount(save);
    }
    @Override public void start() { if (!mRunning) { mRunning = true; scheduleSelf(this, SystemClock.uptimeMillis() + mFrameDuration); } }
    @Override public void stop() { mRunning = false; unscheduleSelf(this); }
    @Override public boolean isRunning() { return mRunning; }
    @Override public void run() {
        if (!mRunning) return;
        mAngle = (mAngle + 360f / mFramesCount) % 360f;
        invalidateSelf();
        scheduleSelf(this, SystemClock.uptimeMillis() + mFrameDuration);
    }
    @Override public boolean setVisible(boolean visible, boolean restart) {
        boolean changed = super.setVisible(visible, restart);
        if (visible) { if (changed || restart) { mAngle = 0; start(); } } else stop();
        return changed;
    }
}
