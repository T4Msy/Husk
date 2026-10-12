package android.preference;

import android.app.Activity;
import android.app.Fragment;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

/**
 * The (deprecated) framework fragment showing a preference hierarchy in its ListView, as AOSP's: the hierarchy is bound once the
 * activity is created (and again, on the next turn, whenever it is replaced).
 */
@SuppressWarnings({"unchecked", "rawtypes", "deprecation"})
public abstract class PreferenceFragment extends Fragment implements PreferenceManager.OnPreferenceTreeClickListener {
    private static final String PREFERENCES_TAG = "android:preferences";
    /** The request codes preferences (a RingtonePreference) start activities with. */
    private static final int FIRST_REQUEST_CODE = 100;
    private PreferenceManager mPreferenceManager;
    private ListView mList;
    private boolean mHavePrefs, mInitDone;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean mBindPosted;
    private final Runnable mBindPreferences = new Runnable() { public void run() { mBindPosted = false; bindPreferences(); } };

    /** A preference with a fragment was clicked: the activity (a PreferenceActivity) shows that fragment. */
    public interface OnPreferenceStartFragmentCallback {
        boolean onPreferenceStartFragment(PreferenceFragment caller, Preference pref);
    }

    public PreferenceFragment() { super(); }

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ensurePreferenceManager();
    }
    private void ensurePreferenceManager() {
        if (mPreferenceManager == null && getActivity() != null) { mPreferenceManager = new PreferenceManager(getActivity(), FIRST_REQUEST_CODE); mPreferenceManager.setFragment(this); }
    }
    /** preference_list_fragment: the list, filling the fragment. */
    @Override public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        LinearLayout root = new LinearLayout(getActivity());
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(PreferenceViews.list(getActivity()), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }
    @Override public void onViewCreated(View view, Bundle savedInstanceState) { super.onViewCreated(view, savedInstanceState); }
    @Override public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        if (mHavePrefs) bindPreferences();
        mInitDone = true;
        if (savedInstanceState != null) {
            Bundle container = savedInstanceState.getBundle(PREFERENCES_TAG);
            if (container != null) { final PreferenceScreen preferenceScreen = getPreferenceScreen(); if (preferenceScreen != null) preferenceScreen.restoreHierarchyState(container); }
        }
    }
    @Override public void onStart() { super.onStart(); ensurePreferenceManager(); if (mPreferenceManager != null) mPreferenceManager.setOnPreferenceTreeClickListener(this); }
    @Override public void onStop() {
        super.onStop();
        if (mPreferenceManager != null) { mPreferenceManager.dispatchActivityStop(); mPreferenceManager.setOnPreferenceTreeClickListener(null); }
    }
    @Override public void onDestroyView() {
        mList = null;
        mHandler.removeCallbacks(mBindPreferences);
        mBindPosted = false;
        super.onDestroyView();
    }
    @Override public void onDestroy() { super.onDestroy(); if (mPreferenceManager != null) mPreferenceManager.dispatchActivityDestroy(); }
    @Override public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        final PreferenceScreen preferenceScreen = getPreferenceScreen();
        if (preferenceScreen != null) { Bundle container = new Bundle(); preferenceScreen.saveHierarchyState(container); outState.putBundle(PREFERENCES_TAG, container); }
    }
    @Override public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (mPreferenceManager != null) mPreferenceManager.dispatchActivityResult(requestCode, resultCode, data);
    }

    public PreferenceManager getPreferenceManager() { return mPreferenceManager; }
    /** Show this hierarchy (bound now if the view is ready, else when it is). */
    public void setPreferenceScreen(PreferenceScreen preferenceScreen) {
        requirePreferenceManager();
        if (mPreferenceManager.setPreferences(preferenceScreen) && preferenceScreen != null) {
            onUnbindPreferences();
            mHavePrefs = true;
            if (mInitDone) postBindPreferences();
        }
    }
    public PreferenceScreen getPreferenceScreen() { return mPreferenceManager != null ? mPreferenceManager.getPreferenceScreen() : null; }
    public void addPreferencesFromIntent(Intent intent) {
        requirePreferenceManager();
        setPreferenceScreen(mPreferenceManager.inflateFromIntent(intent, getPreferenceScreen()));
    }
    /** Inflate preference XML into the current screen (a new one if there is none) and show it. */
    public void addPreferencesFromResource(int preferencesResId) {
        requirePreferenceManager();
        setPreferenceScreen(mPreferenceManager.inflateFromResource(getActivity(), preferencesResId, getPreferenceScreen()));
    }
    public boolean onPreferenceTreeClick(PreferenceScreen preferenceScreen, Preference preference) {
        Activity a = getActivity();
        if (preference.getFragment() != null && a instanceof OnPreferenceStartFragmentCallback) return ((OnPreferenceStartFragmentCallback) a).onPreferenceStartFragment(this, preference);
        return false;
    }
    public Preference findPreference(CharSequence key) { return mPreferenceManager == null ? null : mPreferenceManager.findPreference(key); }
    private void requirePreferenceManager() {
        ensurePreferenceManager();
        if (mPreferenceManager == null) throw new RuntimeException("This should be called after super.onCreate.");
    }
    private void postBindPreferences() { if (mBindPosted) return; mBindPosted = true; mHandler.post(mBindPreferences); }
    private void bindPreferences() {
        final PreferenceScreen preferenceScreen = getPreferenceScreen();
        if (preferenceScreen != null) {
            View root = getView();
            if (root != null) {
                View titleView = root.findViewById(android.R.id.title);
                if (titleView instanceof TextView) {
                    CharSequence title = preferenceScreen.getTitle();
                    if (TextUtils.isEmpty(title)) titleView.setVisibility(View.GONE); else ((TextView) titleView).setText(title);
                }
            }
            if (hasListView()) preferenceScreen.bind(getListView());
        }
        onBindPreferences();
    }
    /** The preferences were bound to the list. */
    protected void onBindPreferences() {}
    /** The preferences are about to be unbound (a new screen replaces them). */
    protected void onUnbindPreferences() {}
    public ListView getListView() { ensureList(); return mList; }
    public boolean hasListView() {
        if (mList != null) return true;
        View root = getView();
        if (root == null) return false;
        View rawListView = root.findViewById(android.R.id.list);
        if (!(rawListView instanceof ListView)) return false;
        mList = (ListView) rawListView;
        return true;
    }
    private void ensureList() {
        if (mList != null) return;
        View root = getView();
        if (root == null) throw new IllegalStateException("Content view not yet created");
        View rawListView = root.findViewById(android.R.id.list);
        if (!(rawListView instanceof ListView)) throw new RuntimeException("Content has view with id attribute 'android.R.id.list' that is not a ListView class");
        mList = (ListView) rawListView;
    }
}
