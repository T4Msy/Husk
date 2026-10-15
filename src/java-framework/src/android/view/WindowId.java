package android.view;

/** A window's identity: its token (androidx transitions compare the ids of the windows their running animators belong to). */
public class WindowId implements android.os.Parcelable {
    private final android.os.IBinder mToken;
    public WindowId(android.os.IBinder token) { mToken = token; }
    public WindowId(IWindowId target) { mToken = null; }
    public IWindowId getTarget() { return null; }
    public boolean isFocused() { for (husk.ViewRoot r : husk.ViewRoot.roots()) if (r.token() == mToken) return r.hasWindowFocus(); return false; }
    public void registerFocusObserver(FocusObserver o) {}
    public void unregisterFocusObserver(FocusObserver o) {}
    @Override public boolean equals(Object o) { return o instanceof WindowId && ((WindowId) o).mToken == mToken; }
    @Override public int hashCode() { return mToken == null ? 0 : mToken.hashCode(); }
    @Override public String toString() { return "WindowId{" + Integer.toHexString(System.identityHashCode(this)) + ": " + mToken + "}"; }
    public int describeContents() { return 0; }
    public void writeToParcel(android.os.Parcel p, int flags) { p.writeStrongBinder(mToken); }
    public static final Creator<WindowId> CREATOR = new Creator<WindowId>() {
        public WindowId createFromParcel(android.os.Parcel p) { return new WindowId(p.readStrongBinder()); }
        public WindowId[] newArray(int n) { return new WindowId[n]; }
    };
    public static abstract class FocusObserver {
        public abstract void onFocusGained(WindowId id);
        public abstract void onFocusLost(WindowId id);
    }
}
