package android.graphics.drawable;

public class StateListDrawable extends DrawableContainer {
    private final java.util.ArrayList<int[]> mSets = new java.util.ArrayList<>();
    public StateListDrawable() {}
    public void addState(int[] set, Drawable d) { mSets.add(set); addChild(d); onStateChange(getState()); }
    public int getStateCount() { return mSets.size(); }
    public int[] getStateSet(int i) { return mSets.get(i); }
    public Drawable getStateDrawable(int i) { return mChildren.get(i); }
    public int findStateDrawableIndex(int[] s) { for (int i = 0; i < mSets.size(); i++) if (android.util.StateSet.stateSetMatches(mSets.get(i), s)) return i; return -1; }
    @Override protected void huskCopyFrom(DrawableContainer src, android.content.res.Resources res) {
        StateListDrawable o = (StateListDrawable) src;
        for (int i = 0; i < o.mSets.size(); i++) addState(o.mSets.get(i), huskCopyChild(o.mChildren.get(i), res));
        setEnterFadeDuration(o.getEnterFadeDurationHusk()); setExitFadeDuration(o.getExitFadeDurationHusk());
    }
    @Override protected boolean onStateChange(int[] s) {
        int i = findStateDrawableIndex(s);
        boolean ch = selectDrawable(i);
        return super.onStateChange(s) || ch;
    }
}
