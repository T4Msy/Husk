package android.graphics.drawable;

import android.graphics.*;

public class DrawableContainer extends Drawable implements Drawable.Callback {
    final java.util.ArrayList<Drawable> mChildren = new java.util.ArrayList<>();
    int mCurIndex = -1;
    private int mAlpha = 255;
    private ColorFilter mFilter;
    public int addChild(Drawable d) { mChildren.add(d); if (d != null) { d.setCallback(this); d.setBounds(getBounds()); } return mChildren.size() - 1; }
    private int mEnterFade, mExitFade;
    /** Cross-fades between states: changes here are immediate, the durations kept for whoever asks. */
    public void setEnterFadeDuration(int ms) { mEnterFade = ms; }
    public void setExitFadeDuration(int ms) { mExitFade = ms; }
    int getEnterFadeDurationHusk() { return mEnterFade; }
    int getExitFadeDurationHusk() { return mExitFade; }
    public boolean selectDrawable(int i) {
        if (i == mCurIndex) return false;
        mCurIndex = i < mChildren.size() ? i : -1;
        Drawable d = getCurrent();
        if (d != null) { d.setBounds(getBounds()); d.setState(getState()); d.setLevel(getLevel()); d.setAlpha(mAlpha); if (mFilter != null) d.setColorFilter(mFilter); }
        invalidateSelf();
        return true;
    }
    @Override public Drawable getCurrent() { return mCurIndex >= 0 && mCurIndex < mChildren.size() ? mChildren.get(mCurIndex) : null; }
    @Override public void draw(Canvas c) { Drawable d = getCurrent(); if (d != null) d.draw(c); }
    @Override protected void onBoundsChange(Rect b) { Drawable d = getCurrent(); if (d != null) d.setBounds(b); }
    @Override public int getIntrinsicWidth() { Drawable d = getCurrent(); return d == null ? -1 : d.getIntrinsicWidth(); }
    @Override public int getIntrinsicHeight() { Drawable d = getCurrent(); return d == null ? -1 : d.getIntrinsicHeight(); }
    @Override public boolean getPadding(Rect p) { Drawable d = getCurrent(); return d != null ? d.getPadding(p) : super.getPadding(p); }
    @Override public void setAlpha(int a) { mAlpha = a; Drawable d = getCurrent(); if (d != null) d.setAlpha(a); }
    @Override public int getAlpha() { return mAlpha; }
    @Override public void setColorFilter(ColorFilter f) { mFilter = f; for (Drawable d : mChildren) if (d != null) d.setColorFilter(f); }
    @Override public void setTintList(android.content.res.ColorStateList t) { for (Drawable d : mChildren) if (d != null) d.setTintList(t); }
    @Override protected boolean onLevelChange(int l) { Drawable d = getCurrent(); return d != null && d.setLevel(l); }
    @Override protected boolean onStateChange(int[] s) { Drawable d = getCurrent(); return d != null && d.setState(s); }
    @Override public boolean isStateful() { return true; }
    public void invalidateDrawable(Drawable who) { if (who == getCurrent()) invalidateSelf(); }
    public void scheduleDrawable(Drawable who, Runnable what, long when) { scheduleSelf(what, when); }
    public void unscheduleDrawable(Drawable who, Runnable what) { unscheduleSelf(what); }
    /** A copy of this container's kind with its children (new drawables from their constant states) and the subclass's own tables. */
    @Override public ConstantState getConstantState() { return new HuskState(this); }
    protected DrawableContainer huskNewInstance() { try { return getClass().getDeclaredConstructor().newInstance(); } catch (Exception e) { return null; } }
    static Drawable huskCopyChild(Drawable d, android.content.res.Resources res) { ConstantState cs = d == null ? null : d.getConstantState(); return cs != null ? cs.newDrawable(res) : d; }
    protected void huskCopyFrom(DrawableContainer src, android.content.res.Resources res) {
        for (Drawable d : src.mChildren) addChild(huskCopyChild(d, res));
        mEnterFade = src.mEnterFade; mExitFade = src.mExitFade;
        selectDrawable(src.mCurIndex);
    }
    /** The container's children, as the platform's state exposes them (androidx's DrawableUtils walks them to fix vector tinting). */
    public static abstract class DrawableContainerState extends android.graphics.drawable.Drawable.ConstantState {
        DrawableContainer mOwner;
        private boolean mConstantSize, mVariablePadding;
        protected DrawableContainerState() { super(); }
        protected DrawableContainerState(DrawableContainerState orig, DrawableContainer owner, android.content.res.Resources res) { this(); mOwner = owner != null ? owner : orig != null ? orig.mOwner : null; }
        public int addChild(Drawable d) { return mOwner.addChild(d); }
        public boolean canConstantState() { for (Drawable d : mOwner.mChildren) if (d != null && d.getConstantState() == null) return false; return true; }
        protected void computeConstantSize() {}
        public Drawable getChild(int i) { return i >= 0 && i < mOwner.mChildren.size() ? mOwner.mChildren.get(i) : null; }
        public int getChildCount() { return mOwner.mChildren.size(); }
        public Drawable[] getChildren() { return mOwner.mChildren.toArray(new Drawable[0]); }
        public int getConstantHeight() { int h = -1; for (Drawable d : mOwner.mChildren) if (d != null) h = Math.max(h, d.getIntrinsicHeight()); return h; }
        public int getConstantWidth() { int w = -1; for (Drawable d : mOwner.mChildren) if (d != null) w = Math.max(w, d.getIntrinsicWidth()); return w; }
        public int getConstantMinimumHeight() { int h = 0; for (Drawable d : mOwner.mChildren) if (d != null) h = Math.max(h, d.getMinimumHeight()); return h; }
        public int getConstantMinimumWidth() { int w = 0; for (Drawable d : mOwner.mChildren) if (d != null) w = Math.max(w, d.getMinimumWidth()); return w; }
        public Rect getConstantPadding() { Rect r = new Rect(), p = new Rect(); boolean any = false; for (Drawable d : mOwner.mChildren) if (d != null && d.getPadding(p)) { any = true; r.left = Math.max(r.left, p.left); r.top = Math.max(r.top, p.top); r.right = Math.max(r.right, p.right); r.bottom = Math.max(r.bottom, p.bottom); } return any ? r : null; }
        public int getEnterFadeDuration() { return mOwner.mEnterFade; }
        public int getExitFadeDuration() { return mOwner.mExitFade; }
        public int getOpacity() { int o = PixelFormat.TRANSPARENT; boolean first = true; for (Drawable d : mOwner.mChildren) if (d != null) { o = first ? d.getOpacity() : Drawable.resolveOpacity(o, d.getOpacity()); first = false; } return o; }
        public void growArray(int oldSize, int newSize) {}
        public boolean isConstantSize() { return mConstantSize; }
        public boolean isStateful() { for (Drawable d : mOwner.mChildren) if (d != null && d.isStateful()) return true; return false; }
        public void setConstantSize(boolean c) { mConstantSize = c; }
        public void setEnterFadeDuration(int ms) { mOwner.mEnterFade = ms; }
        public void setExitFadeDuration(int ms) { mOwner.mExitFade = ms; }
        public void setVariablePadding(boolean v) { mVariablePadding = v; }
        @Override public int getChangingConfigurations() { int c = 0; for (Drawable d : mOwner.mChildren) if (d != null) c |= d.getChangingConfigurations(); return c; }
    }
    private static final class HuskState extends DrawableContainerState {
        HuskState(DrawableContainer owner) { super(null, owner, null); }
        @Override public Drawable newDrawable() { return newDrawable(null); }
        @Override public Drawable newDrawable(android.content.res.Resources res) { DrawableContainer c = mOwner.huskNewInstance(); if (c == null) return mOwner; c.huskCopyFrom(mOwner, res); return c; }
    }
    // ---- generated by tools/compat/fillmembers.py: the platform's members this class does not write (signatures only)
    private final java.util.HashMap<String, Object> huskFill = new java.util.HashMap<>();
    public int getCurrentIndex() { return mCurIndex; }
    public void setCurrentIndex(int i) { selectDrawable(i); }
    protected void updateDensity(android.content.res.Resources p0) {}
    // ---- end of generated members
}
