package android.text.method;

import android.text.Layout;
import android.text.NoCopySpan;
import android.text.Spannable;
import android.text.Spanned;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.TextView;

/** Scrolls a TextView's text under a dragging finger (the scrolling movement methods' touch handling). */
public class Touch {
    private Touch() {}
    private static class DragState implements NoCopySpan { float mX, mY; int mScrollX, mScrollY; boolean mFarEnough, mUsed; DragState(float x, float y, int sx, int sy) { mX = x; mY = y; mScrollX = sx; mScrollY = sy; } }
    /** Scrolls the view so the given layout-relative point is at its top left, kept within the text, as the platform's. */
    public static void scrollTo(TextView widget, Layout layout, int x, int y) {
        int hpad = widget.getTotalPaddingLeft() + widget.getTotalPaddingRight(), vpad = widget.getTotalPaddingTop() + widget.getTotalPaddingBottom();
        int top = layout.getLineForVertical(y), bottom = layout.getLineForVertical(y + widget.getHeight() - vpad);
        int left = Integer.MAX_VALUE, right = 0;
        for (int i = top; i <= bottom; i++) { left = (int) Math.min(left, layout.getLineLeft(i)); right = (int) Math.max(right, layout.getLineRight(i)); }
        int availW = widget.getWidth() - hpad, availH = widget.getHeight() - vpad;
        if (right - left < availW) {
            Layout.Alignment a = layout.getParagraphAlignment(top);
            if (a == Layout.Alignment.ALIGN_CENTER) x = left - (availW - (right - left)) / 2;
            else if (a == Layout.Alignment.ALIGN_OPPOSITE) x = right - availW;
            else x = left;
        } else x = Math.min(Math.max(x, left), right - availW);
        y = Math.min(Math.max(y, 0), Math.max(0, layout.getHeight() - availH));
        if (x != widget.getScrollX() || y != widget.getScrollY()) widget.scrollTo(x, y);
    }
    public static boolean onTouchEvent(TextView widget, Spannable buffer, MotionEvent event) {
        DragState[] ds;
        switch (event.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
            for (DragState d : buffer.getSpans(0, buffer.length(), DragState.class)) buffer.removeSpan(d);
            buffer.setSpan(new DragState(event.getX(), event.getY(), widget.getScrollX(), widget.getScrollY()), 0, 0, Spanned.SPAN_MARK_MARK);
            return true;
        case MotionEvent.ACTION_UP:
            ds = buffer.getSpans(0, buffer.length(), DragState.class);
            for (DragState d : ds) buffer.removeSpan(d);
            return ds.length > 0 && ds[0].mUsed;
        case MotionEvent.ACTION_MOVE:
            ds = buffer.getSpans(0, buffer.length(), DragState.class);
            if (ds.length > 0) {
                DragState d = ds[0];
                if (!d.mFarEnough) {
                    int slop = ViewConfiguration.get(widget.getContext()).getScaledTouchSlop();
                    if (Math.abs(event.getX() - d.mX) >= slop || Math.abs(event.getY() - d.mY) >= slop) d.mFarEnough = true;
                }
                if (d.mFarEnough) {
                    d.mUsed = true;
                    float dx, dy;
                    if ((event.getMetaState() & android.view.KeyEvent.META_SHIFT_ON) != 0) { dx = event.getY() - d.mY; dy = event.getX() - d.mX; }
                    else { dx = event.getX() - d.mX; dy = event.getY() - d.mY; }
                    d.mX = event.getX(); d.mY = event.getY();
                    int nx = widget.getScrollX() + (int) -dx, ny = widget.getScrollY() + (int) -dy;
                    Layout layout = widget.getLayout();
                    if (layout == null) return true;
                    int padding = widget.getTotalPaddingTop() + widget.getTotalPaddingBottom();
                    ny = Math.min(ny, layout.getHeight() - (widget.getHeight() - padding));
                    ny = Math.max(ny, 0);
                    int oldX = widget.getScrollX(), oldY = widget.getScrollY();
                    scrollTo(widget, layout, nx, ny);
                    if (oldX != widget.getScrollX() || oldY != widget.getScrollY()) widget.cancelLongPress();
                    return true;
                }
            }
        }
        return false;
    }
    public static int getInitialScrollX(TextView widget, Spannable buffer) { DragState[] ds = buffer.getSpans(0, buffer.length(), DragState.class); return ds.length > 0 ? ds[0].mScrollX : -1; }
    public static int getInitialScrollY(TextView widget, Spannable buffer) { DragState[] ds = buffer.getSpans(0, buffer.length(), DragState.class); return ds.length > 0 ? ds[0].mScrollY : -1; }
}
