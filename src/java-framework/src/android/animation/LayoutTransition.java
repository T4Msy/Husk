package android.animation;

/** A view group's layout-change animations: the configuration is kept and read back as the platform's; the changes themselves apply at once. */
public class LayoutTransition {
    public static final int CHANGE_APPEARING = 0, CHANGE_DISAPPEARING = 1, APPEARING = 2, DISAPPEARING = 3, CHANGING = 4;
    public interface TransitionListener { void startTransition(LayoutTransition t, android.view.ViewGroup c, android.view.View v, int type); void endTransition(LayoutTransition t, android.view.ViewGroup c, android.view.View v, int type); }
    private static final int DEFAULT_DURATION = 300;
    private final long[] mDuration = { DEFAULT_DURATION, DEFAULT_DURATION, DEFAULT_DURATION, DEFAULT_DURATION, DEFAULT_DURATION };
    private final long[] mStartDelay = { 0, DEFAULT_DURATION, DEFAULT_DURATION, 0, 0 }, mStagger = new long[5];
    private final TimeInterpolator[] mInterpolator = { new android.view.animation.DecelerateInterpolator(), new android.view.animation.DecelerateInterpolator(),
        new android.view.animation.AccelerateDecelerateInterpolator(), new android.view.animation.AccelerateDecelerateInterpolator(), new android.view.animation.DecelerateInterpolator() };
    private final Animator[] mAnimator = new Animator[5];
    private int mTransitionTypes = 1 | 2 | 4 | 8;       /* CHANGING is off by default, as the platform's */
    private java.util.ArrayList<TransitionListener> mListeners;
    private boolean mAnimateParentHierarchy = true;
    public void setDuration(long d) { for (int i = 0; i < 5; i++) mDuration[i] = d; }
    public void setDuration(int t, long d) { mDuration[t] = d; }
    public long getDuration(int t) { return mDuration[t]; }
    public void enableTransitionType(int t) { mTransitionTypes |= 1 << t; }
    public void disableTransitionType(int t) { mTransitionTypes &= ~(1 << t); }
    public boolean isTransitionTypeEnabled(int t) { return (mTransitionTypes & (1 << t)) != 0; }
    public void setAnimator(int t, Animator a) { mAnimator[t] = a; }
    public Animator getAnimator(int t) { return mAnimator[t]; }
    public void setInterpolator(int t, TimeInterpolator i) { mInterpolator[t] = i; }
    public TimeInterpolator getInterpolator(int t) { return mInterpolator[t]; }
    public void setStartDelay(int t, long d) { mStartDelay[t] = d; }
    public long getStartDelay(int t) { return mStartDelay[t]; }
    public void setStagger(int t, long d) { mStagger[t] = d; }
    public long getStagger(int t) { return mStagger[t]; }
    public void addTransitionListener(TransitionListener l) { if (mListeners == null) mListeners = new java.util.ArrayList<>(); mListeners.add(l); }
    public void removeTransitionListener(TransitionListener l) { if (mListeners != null) mListeners.remove(l); }
    public java.util.List<TransitionListener> getTransitionListeners() { return mListeners; }
    public void setAnimateParentHierarchy(boolean b) { mAnimateParentHierarchy = b; }
    public boolean isRunning() { return false; }
    public boolean isChangingLayout() { return false; }
    public void addChild(android.view.ViewGroup p, android.view.View c) {}
    public void showChild(android.view.ViewGroup p, android.view.View c) {}
    public void showChild(android.view.ViewGroup p, android.view.View c, int oldVisibility) {}
    public void removeChild(android.view.ViewGroup p, android.view.View c) {}
    public void hideChild(android.view.ViewGroup p, android.view.View c) {}
    public void hideChild(android.view.ViewGroup p, android.view.View c, int newVisibility) {}
    public void layoutChange(android.view.ViewGroup p) {}
    public void startChangingAnimations() {}
    public void endChangingAnimations() {}
    public void cancel() {}
    public void cancel(int t) {}
}
