package android.transition;

import android.animation.Animator;
import android.animation.TimeInterpolator;
import android.view.View;
import android.view.ViewGroup;
import java.util.ArrayList;
import java.util.List;

/** Transitions are accepted and their listeners told they ran; the change itself is applied at once (no animation). */
public abstract class Transition implements Cloneable {
    public static final int MATCH_INSTANCE = 1, MATCH_NAME = 2, MATCH_ID = 3, MATCH_ITEM_ID = 4;
    public interface TransitionListener { void onTransitionStart(Transition t); void onTransitionEnd(Transition t); void onTransitionCancel(Transition t); void onTransitionPause(Transition t); void onTransitionResume(Transition t); }
    public static abstract class EpicenterCallback { public abstract android.graphics.Rect onGetEpicenter(Transition t); }
    long mDuration = -1, mStartDelay = -1;
    TimeInterpolator mInterpolator;
    final ArrayList<TransitionListener> mListeners = new ArrayList<>();
    final ArrayList<Integer> mTargetIds = new ArrayList<>();
    final ArrayList<View> mTargets = new ArrayList<>();
    ArrayList<String> mTargetNames;
    ArrayList<Class<?>> mTargetTypes;
    private PathMotion mPathMotion = STRAIGHT_PATH_MOTION;
    private EpicenterCallback mEpicenterCallback;
    private TransitionPropagation mPropagation;
    private static final PathMotion STRAIGHT_PATH_MOTION = new PathMotion() {
        @Override public android.graphics.Path getPath(float sx, float sy, float ex, float ey) { android.graphics.Path p = new android.graphics.Path(); p.moveTo(sx, sy); p.lineTo(ex, ey); return p; }
    };
    public Transition() {}
    public Transition(android.content.Context c, android.util.AttributeSet a) {}
    public Transition setDuration(long d) { mDuration = d; return this; }
    public long getDuration() { return mDuration; }
    public Transition setStartDelay(long d) { mStartDelay = d; return this; }
    public long getStartDelay() { return mStartDelay; }
    public Transition setInterpolator(TimeInterpolator i) { mInterpolator = i; return this; }
    public TimeInterpolator getInterpolator() { return mInterpolator; }
    public String[] getTransitionProperties() { return null; }
    public Animator createAnimator(ViewGroup root, TransitionValues start, TransitionValues end) { return null; }
    public void setMatchOrder(int... m) {}
    public abstract void captureStartValues(TransitionValues v);
    public abstract void captureEndValues(TransitionValues v);
    public Transition addTarget(int id) { mTargetIds.add(id); return this; }
    public Transition addTarget(String name) { if (name != null) { if (mTargetNames == null) mTargetNames = new ArrayList<>(); mTargetNames.add(name); } return this; }
    public Transition addTarget(Class<?> c) { if (c != null) { if (mTargetTypes == null) mTargetTypes = new ArrayList<>(); mTargetTypes.add(c); } return this; }
    public Transition addTarget(View v) { mTargets.add(v); return this; }
    public Transition removeTarget(int id) { mTargetIds.remove((Integer) id); return this; }
    public Transition removeTarget(String n) { if (mTargetNames != null) mTargetNames.remove(n); return this; }
    public Transition removeTarget(View v) { mTargets.remove(v); return this; }
    public Transition removeTarget(Class<?> c) { if (mTargetTypes != null) mTargetTypes.remove(c); return this; }
    public Transition excludeTarget(int id, boolean e) { return this; }
    public Transition excludeTarget(String n, boolean e) { return this; }
    public Transition excludeChildren(int id, boolean e) { return this; }
    public Transition excludeTarget(View v, boolean e) { return this; }
    public Transition excludeChildren(View v, boolean e) { return this; }
    public Transition excludeTarget(Class<?> c, boolean e) { return this; }
    public Transition excludeChildren(Class<?> c, boolean e) { return this; }
    public List<Integer> getTargetIds() { return mTargetIds; }
    public List<View> getTargets() { return mTargets; }
    public List<String> getTargetNames() { return mTargetNames; }
    public List<Class<?>> getTargetTypes() { return mTargetTypes; }
    public TransitionValues getTransitionValues(View v, boolean start) { return null; }
    public boolean isTransitionRequired(TransitionValues s, TransitionValues e) { return false; }
    public Transition addListener(TransitionListener l) { mListeners.add(l); return this; }
    public Transition removeListener(TransitionListener l) { mListeners.remove(l); return this; }
    public void setEpicenterCallback(EpicenterCallback c) { mEpicenterCallback = c; }
    public EpicenterCallback getEpicenterCallback() { return mEpicenterCallback; }
    public android.graphics.Rect getEpicenter() { return mEpicenterCallback == null ? null : mEpicenterCallback.onGetEpicenter(this); }
    public void setPathMotion(PathMotion m) { mPathMotion = m == null ? STRAIGHT_PATH_MOTION : m; }
    public PathMotion getPathMotion() { return mPathMotion; }
    public void setPropagation(TransitionPropagation p) { mPropagation = p; }
    public TransitionPropagation getPropagation() { return mPropagation; }
    public boolean canRemoveViews() { return false; }
    public String getName() { return getClass().getName(); }
    @Override public Transition clone() { try { Transition t = (Transition) super.clone(); if (mTargetNames != null) t.mTargetNames = new ArrayList<>(mTargetNames); if (mTargetTypes != null) t.mTargetTypes = new ArrayList<>(mTargetTypes); return t; } catch (CloneNotSupportedException e) { throw new RuntimeException(e); } }
    /** Husk: tell the listeners it started and ended (nothing is animated). */
    void huskRun() { for (TransitionListener l : new ArrayList<>(mListeners)) l.onTransitionStart(this); for (TransitionListener l : new ArrayList<>(mListeners)) l.onTransitionEnd(this); }
    // ---- generated by tools/compat/fillmembers.py: the platform's members this class does not write (signatures only)
    private final java.util.HashMap<String, Object> huskFill = new java.util.HashMap<>();
    protected void animate(android.animation.Animator p0) {}
    protected void cancel() {}
    protected void end() {}
    public android.util.ArrayMap getNameOverrides() { return (android.util.ArrayMap) huskFill.get("NameOverrides"); }
    public java.util.List getTargetViewNames() { return new java.util.ArrayList(); }
    public boolean isValidTarget(android.view.View p0) { return false; }
    public void pause(android.view.View p0) {}
    public void resume(android.view.View p0) {}
    protected void runAnimators() {}
    public void setNameOverrides(android.util.ArrayMap p0) { huskFill.put("NameOverrides", p0); }
    protected void start() {}
    // ---- end of generated members
    // ---- generated by tools/compat/genstubs.py: the platform's nested classes this class does not write
    public static class AnimationInfo {
        private final java.util.HashMap<String, Object> huskProps = new java.util.HashMap<>();
        public android.view.View view;
        protected AnimationInfo() {}
    }
    // ---- end of generated nested classes
}
