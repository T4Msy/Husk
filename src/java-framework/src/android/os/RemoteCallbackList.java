package android.os;

import java.util.ArrayList;
import java.util.LinkedHashMap;

/** A list of registered callback interfaces (keyed by their binders) that a broadcast walks, as the platform's. */
public class RemoteCallbackList<E extends IInterface> {
    public static final int FROZEN_CALLEE_POLICY_DROP = 3;
    public static final int FROZEN_CALLEE_POLICY_ENQUEUE_ALL = 1;
    public static final int FROZEN_CALLEE_POLICY_ENQUEUE_MOST_RECENT = 2;
    public static final int FROZEN_CALLEE_POLICY_UNSET = 0;
    private final LinkedHashMap<IBinder, Object[]> mCallbacks = new LinkedHashMap<>();   /* binder -> { callback, cookie } */
    private Object[][] mActive;
    private int mBroadcastCount = -1;
    private boolean mKilled;
    private java.util.concurrent.Executor mExecutor;
    private int mMaxQueueSize, mFrozenPolicy;
    private Builder.InterfaceDiedCallback mDied;
    public RemoteCallbackList() {}
    public boolean register(E callback) { return register(callback, null); }
    public boolean register(E callback, Object cookie) {
        synchronized (mCallbacks) {
            if (mKilled) return false;
            IBinder b = callback.asBinder();
            mCallbacks.put(b != null ? b : new Binder(), new Object[] { callback, cookie });
            return true;
        }
    }
    public boolean unregister(E callback) { synchronized (mCallbacks) { IBinder b = callback.asBinder(); if (b == null) { for (java.util.Map.Entry<IBinder, Object[]> e : mCallbacks.entrySet()) if (e.getValue()[0] == callback) { mCallbacks.remove(e.getKey()); return true; } return false; } return mCallbacks.remove(b) != null; } }
    public void kill() { synchronized (mCallbacks) { mCallbacks.clear(); mKilled = true; } }
    public void onCallbackDied(E callback) {}
    @SuppressWarnings("unchecked") public void onCallbackDied(E callback, Object cookie) { if (mDied != null) mDied.onInterfaceDied(this, callback, cookie); else onCallbackDied(callback); }
    public int beginBroadcast() {
        synchronized (mCallbacks) {
            if (mBroadcastCount > 0) throw new IllegalStateException("beginBroadcast() called while already in a broadcast");
            int n = mCallbacks.size();
            mActive = mCallbacks.values().toArray(new Object[0][]);
            mBroadcastCount = n;
            return n;
        }
    }
    @SuppressWarnings("unchecked") public E getBroadcastItem(int index) { return (E) mActive[index][0]; }
    public Object getBroadcastCookie(int index) { return mActive[index][1]; }
    public void finishBroadcast() {
        if (mBroadcastCount < 0) throw new IllegalStateException("finishBroadcast() called outside of a broadcast");
        mActive = null; mBroadcastCount = -1;
    }
    public void broadcast(java.util.function.Consumer<E> action) { int n = beginBroadcast(); try { for (int i = 0; i < n; i++) action.accept(getBroadcastItem(i)); } finally { finishBroadcast(); } }
    public <C> void broadcastForEachCookie(java.util.function.Consumer<C> action) { int n = beginBroadcast(); try { for (int i = 0; i < n; i++) action.accept((C) getBroadcastCookie(i)); } finally { finishBroadcast(); } }
    public <C> void broadcast(java.util.function.BiConsumer<E, C> action) { int n = beginBroadcast(); try { for (int i = 0; i < n; i++) action.accept(getBroadcastItem(i), (C) getBroadcastCookie(i)); } finally { finishBroadcast(); } }
    public int getRegisteredCallbackCount() { synchronized (mCallbacks) { return mKilled ? 0 : mCallbacks.size(); } }
    @SuppressWarnings("unchecked") public E getRegisteredCallbackItem(int index) { synchronized (mCallbacks) { return mKilled ? null : (E) new ArrayList<>(mCallbacks.values()).get(index)[0]; } }
    public Object getRegisteredCallbackCookie(int index) { synchronized (mCallbacks) { return mKilled ? null : new ArrayList<>(mCallbacks.values()).get(index)[1]; } }
    public java.util.concurrent.Executor getExecutor() { return mExecutor; }
    public int getFrozenCalleePolicy() { return mFrozenPolicy; }
    public int getMaxQueueSize() { return mMaxQueueSize; }
    public void dump(java.io.PrintWriter pw, String prefix) { synchronized (mCallbacks) { pw.print(prefix); pw.print("callbacks: "); pw.println(mCallbacks.size()); pw.print(prefix); pw.print("killed: "); pw.println(mKilled); pw.print(prefix); pw.print("broadcasts count: "); pw.println(mBroadcastCount); } }
    public static final class Builder<E extends IInterface> {
        private final int mPolicy; private int mMax; private java.util.concurrent.Executor mExec; private InterfaceDiedCallback<E> mCb;
        public Builder(int frozenCalleePolicy) { mPolicy = frozenCalleePolicy; }
        public Builder<E> setMaxQueueSize(int n) { mMax = n; return this; }
        public Builder<E> setExecutor(java.util.concurrent.Executor e) { mExec = e; return this; }
        public Builder<E> setInterfaceDiedCallback(InterfaceDiedCallback<E> cb) { mCb = cb; return this; }
        public RemoteCallbackList<E> build() { RemoteCallbackList<E> l = new RemoteCallbackList<E>(); l.mFrozenPolicy = mPolicy; l.mMaxQueueSize = mMax; l.mExecutor = mExec; l.mDied = mCb; return l; }
        public interface InterfaceDiedCallback<E extends IInterface> { void onInterfaceDied(RemoteCallbackList<E> list, E callback, Object cookie); }
    }
}
