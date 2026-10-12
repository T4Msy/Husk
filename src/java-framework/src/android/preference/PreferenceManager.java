package android.preference;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.XmlResourceParser;
import android.os.Bundle;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;

/**
 * As AOSP's: the storage behind a preference hierarchy (a SharedPreferences file, "<package>_preferences" by default, or a
 * PreferenceDataStore), its inflation from XML, and the activity events (result, stop, destroy) its preferences listen to.
 */
@SuppressWarnings({"unchecked", "rawtypes", "deprecation"})
public class PreferenceManager {
    private static final String TAG = "PreferenceManager";
    public static final String KEY_HAS_SET_DEFAULT_VALUES = "_has_set_default_values";
    public static final String METADATA_KEY_PREFERENCES = "android.preference";
    private static final int STORAGE_DEFAULT = 0, STORAGE_DEVICE_PROTECTED = 1, STORAGE_CREDENTIAL_PROTECTED = 2;

    private Activity mActivity;
    private PreferenceFragment mFragment;
    private Context mContext;
    private long mNextId = 0;
    private int mNextRequestCode;
    private SharedPreferences mSharedPreferences;
    private PreferenceDataStore mPreferenceDataStore;
    /** While inflating, the edits default values make go into one editor, applied at the end. */
    private SharedPreferences.Editor mEditor;
    private boolean mNoCommit;
    private String mSharedPreferencesName;
    private int mSharedPreferencesMode;
    private int mStorage = STORAGE_DEFAULT;
    private PreferenceScreen mPreferenceScreen;
    private List<OnActivityResultListener> mActivityResultListeners;
    private List<OnActivityStopListener> mActivityStopListeners;
    private List<OnActivityDestroyListener> mActivityDestroyListeners;
    /** Nested screens' dialogs showing, dismissed with the activity. */
    private List<DialogInterface> mPreferencesScreens;
    private OnPreferenceTreeClickListener mOnPreferenceTreeClickListener;

    public PreferenceManager(Activity activity, int firstRequestCode) { mActivity = activity; mNextRequestCode = firstRequestCode; init(activity); }
    PreferenceManager(Context context) { init(context); }
    private void init(Context context) { mContext = context; if (context != null) setSharedPreferencesName(getDefaultSharedPreferencesName(context)); }

    void setFragment(PreferenceFragment fragment) { mFragment = fragment; }
    PreferenceFragment getFragment() { return mFragment; }
    Activity getActivity() { return mActivity; }
    Context getContext() { return mContext; }
    public void setPreferenceDataStore(PreferenceDataStore dataStore) { mPreferenceDataStore = dataStore; }
    public PreferenceDataStore getPreferenceDataStore() { return mPreferenceDataStore; }

    /** The preferences in activities matching the intent (their "android.preference" meta-data XML), under one screen. */
    PreferenceScreen inflateFromIntent(Intent queryIntent, PreferenceScreen rootPreferences) {
        final List<ResolveInfo> activities = queryIntentActivities(queryIntent);
        setNoCommit(true);
        try {
            final PreferenceInflater inflater = new PreferenceInflater(mContext, this);
            for (int i = activities.size() - 1; i >= 0; i--) {
                final ActivityInfo activityInfo = activities.get(i).activityInfo;
                final Bundle metaData = activityInfo != null ? activityInfo.metaData : null;
                if (metaData == null || !metaData.containsKey(METADATA_KEY_PREFERENCES)) continue;
                final XmlResourceParser parser = activityInfo.loadXmlMetaData(mContext.getPackageManager(), METADATA_KEY_PREFERENCES);
                if (parser == null) continue;
                try { rootPreferences = asScreen(inflater.inflate(parser, rootPreferences)); } finally { parser.close(); }
            }
            if (rootPreferences == null) rootPreferences = createPreferenceScreen(mContext);
            rootPreferences.onAttachedToHierarchy(this);
        } finally { setNoCommit(false); }
        return rootPreferences;
    }
    private List<ResolveInfo> queryIntentActivities(Intent queryIntent) {
        List<ResolveInfo> l = null;
        try { l = mContext.getPackageManager().queryIntentActivities(queryIntent, PackageManager.GET_META_DATA); } catch (RuntimeException e) { Log.w(TAG, "queryIntentActivities: " + e); }
        return l != null ? l : new ArrayList<ResolveInfo>();
    }
    /** Inflate preference XML: into rootPreferences (its root element's children), or as a new screen when that is null. */
    public PreferenceScreen inflateFromResource(Context context, int resId, PreferenceScreen rootPreferences) {
        setNoCommit(true);   // block commits: the default values set while inflating go out in one
        try {
            final PreferenceInflater inflater = new PreferenceInflater(context, this);
            rootPreferences = asScreen(inflater.inflate(resId, rootPreferences));
            rootPreferences.onAttachedToHierarchy(this);
        } finally { setNoCommit(false); }
        return rootPreferences;
    }
    /** Android casts the XML's root to a screen; a root that is another preference ends up as the only child of a new screen. */
    private PreferenceScreen asScreen(Preference p) {
        if (p instanceof PreferenceScreen || p == null) return (PreferenceScreen) p;
        Log.w(TAG, "preference XML's root is a " + p.getClass().getName() + ", not a PreferenceScreen");
        PreferenceScreen s = createPreferenceScreen(p.getContext() != null ? p.getContext() : mContext);
        s.addPreference(p);
        return s;
    }
    public PreferenceScreen createPreferenceScreen(Context context) {
        final PreferenceScreen preferenceScreen = new PreferenceScreen(context, null);
        preferenceScreen.onAttachedToHierarchy(this);
        return preferenceScreen;
    }
    long getNextId() { synchronized (this) { return mNextId++; } }
    int getNextRequestCode() { synchronized (this) { return mNextRequestCode++; } }

    public String getSharedPreferencesName() { return mSharedPreferencesName; }
    public void setSharedPreferencesName(String sharedPreferencesName) { mSharedPreferencesName = sharedPreferencesName; mSharedPreferences = null; }
    public int getSharedPreferencesMode() { return mSharedPreferencesMode; }
    public void setSharedPreferencesMode(int sharedPreferencesMode) { mSharedPreferencesMode = sharedPreferencesMode; mSharedPreferences = null; }
    /* one storage on Husk: device- and credential-protected storage are the app's own */
    public void setStorageDefault() { if (mStorage != STORAGE_DEFAULT) { mStorage = STORAGE_DEFAULT; mSharedPreferences = null; } }
    public void setStorageDeviceProtected() { if (mStorage != STORAGE_DEVICE_PROTECTED) { mStorage = STORAGE_DEVICE_PROTECTED; mSharedPreferences = null; } }
    public void setStorageCredentialProtected() { if (mStorage != STORAGE_CREDENTIAL_PROTECTED) { mStorage = STORAGE_CREDENTIAL_PROTECTED; mSharedPreferences = null; } }
    public boolean isStorageDefault() { return mStorage == STORAGE_DEFAULT; }
    public boolean isStorageDeviceProtected() { return mStorage == STORAGE_DEVICE_PROTECTED; }
    public boolean isStorageCredentialProtected() { return mStorage == STORAGE_CREDENTIAL_PROTECTED; }
    /** The hierarchy's SharedPreferences (null when a PreferenceDataStore is used instead). */
    public SharedPreferences getSharedPreferences() {
        if (mPreferenceDataStore != null) return null;
        if (mSharedPreferences == null && mContext != null) mSharedPreferences = mContext.getSharedPreferences(mSharedPreferencesName, mSharedPreferencesMode);
        return mSharedPreferences;
    }
    public static SharedPreferences getDefaultSharedPreferences(Context context) { return context.getSharedPreferences(getDefaultSharedPreferencesName(context), getDefaultSharedPreferencesMode()); }
    public static String getDefaultSharedPreferencesName(Context context) { return context.getPackageName() + "_preferences"; }
    private static int getDefaultSharedPreferencesMode() { return Context.MODE_PRIVATE; }
    PreferenceScreen getPreferenceScreen() { return mPreferenceScreen; }
    /** Set the root shown; true when it changed. */
    boolean setPreferences(PreferenceScreen preferenceScreen) {
        if (preferenceScreen != mPreferenceScreen) { mPreferenceScreen = preferenceScreen; return true; }
        return false;
    }
    public Preference findPreference(CharSequence key) {
        if (mPreferenceScreen == null || key == null) return null;
        return mPreferenceScreen.findPreference(key);
    }

    /** Write the default values of the preferences in the XML to the default SharedPreferences (once, unless readAgain). */
    public static void setDefaultValues(Context context, int resId, boolean readAgain) {
        setDefaultValues(context, getDefaultSharedPreferencesName(context), getDefaultSharedPreferencesMode(), resId, readAgain);
    }
    public static void setDefaultValues(Context context, String sharedPreferencesName, int sharedPreferencesMode, int resId, boolean readAgain) {
        final SharedPreferences defaultValueSp = context.getSharedPreferences(KEY_HAS_SET_DEFAULT_VALUES, Context.MODE_PRIVATE);
        if (readAgain || !defaultValueSp.getBoolean(KEY_HAS_SET_DEFAULT_VALUES, false)) {
            final PreferenceManager pm = new PreferenceManager(context);
            pm.setSharedPreferencesName(sharedPreferencesName);
            pm.setSharedPreferencesMode(sharedPreferencesMode);
            pm.inflateFromResource(context, resId, null);
            SharedPreferences.Editor editor = defaultValueSp.edit().putBoolean(KEY_HAS_SET_DEFAULT_VALUES, true);
            try { editor.apply(); } catch (AbstractMethodError unused) { editor.commit(); }
        }
    }
    SharedPreferences.Editor getEditor() {
        if (mPreferenceDataStore != null) return null;
        SharedPreferences sp = getSharedPreferences();
        if (sp == null) return null;
        if (mNoCommit) { if (mEditor == null) mEditor = sp.edit(); return mEditor; }
        return sp.edit();
    }
    boolean shouldCommit() { return !mNoCommit; }
    private void setNoCommit(boolean noCommit) {
        if (!noCommit && mEditor != null) { try { mEditor.apply(); } catch (AbstractMethodError unused) { mEditor.commit(); } mEditor = null; }
        mNoCommit = noCommit;
    }

    void registerOnActivityResultListener(OnActivityResultListener listener) {
        synchronized (this) { if (mActivityResultListeners == null) mActivityResultListeners = new ArrayList<OnActivityResultListener>(); if (!mActivityResultListeners.contains(listener)) mActivityResultListeners.add(listener); }
    }
    void unregisterOnActivityResultListener(OnActivityResultListener listener) { synchronized (this) { if (mActivityResultListeners != null) mActivityResultListeners.remove(listener); } }
    void dispatchActivityResult(int requestCode, int resultCode, Intent data) {
        List<OnActivityResultListener> list;
        synchronized (this) { if (mActivityResultListeners == null) return; list = new ArrayList<OnActivityResultListener>(mActivityResultListeners); }
        for (int i = 0; i < list.size(); i++) if (list.get(i).onActivityResult(requestCode, resultCode, data)) break;
    }
    public void registerOnActivityStopListener(OnActivityStopListener listener) {
        synchronized (this) { if (mActivityStopListeners == null) mActivityStopListeners = new ArrayList<OnActivityStopListener>(); if (!mActivityStopListeners.contains(listener)) mActivityStopListeners.add(listener); }
    }
    public void unregisterOnActivityStopListener(OnActivityStopListener listener) { synchronized (this) { if (mActivityStopListeners != null) mActivityStopListeners.remove(listener); } }
    void dispatchActivityStop() {
        List<OnActivityStopListener> list;
        synchronized (this) { if (mActivityStopListeners == null) return; list = new ArrayList<OnActivityStopListener>(mActivityStopListeners); }
        for (int i = 0; i < list.size(); i++) list.get(i).onActivityStop();
    }
    void registerOnActivityDestroyListener(OnActivityDestroyListener listener) {
        synchronized (this) { if (mActivityDestroyListeners == null) mActivityDestroyListeners = new ArrayList<OnActivityDestroyListener>(); if (!mActivityDestroyListeners.contains(listener)) mActivityDestroyListeners.add(listener); }
    }
    void unregisterOnActivityDestroyListener(OnActivityDestroyListener listener) { synchronized (this) { if (mActivityDestroyListeners != null) mActivityDestroyListeners.remove(listener); } }
    void dispatchActivityDestroy() {
        List<OnActivityDestroyListener> list = null;
        synchronized (this) { if (mActivityDestroyListeners != null) list = new ArrayList<OnActivityDestroyListener>(mActivityDestroyListeners); }
        if (list != null) for (int i = 0; i < list.size(); i++) list.get(i).onActivityDestroy();
        dismissAllScreens();
    }
    void addPreferencesScreen(DialogInterface screen) {
        synchronized (this) { if (mPreferencesScreens == null) mPreferencesScreens = new ArrayList<DialogInterface>(); mPreferencesScreens.add(screen); }
    }
    void removePreferencesScreen(DialogInterface screen) { synchronized (this) { if (mPreferencesScreens != null) mPreferencesScreens.remove(screen); } }
    void dispatchNewIntent(Intent intent) { dismissAllScreens(); }
    private void dismissAllScreens() {
        ArrayList<DialogInterface> screensToDismiss;
        synchronized (this) { if (mPreferencesScreens == null) return; screensToDismiss = new ArrayList<DialogInterface>(mPreferencesScreens); mPreferencesScreens.clear(); }
        for (int i = screensToDismiss.size() - 1; i >= 0; i--) screensToDismiss.get(i).dismiss();
    }
    void setOnPreferenceTreeClickListener(OnPreferenceTreeClickListener listener) { mOnPreferenceTreeClickListener = listener; }
    OnPreferenceTreeClickListener getOnPreferenceTreeClickListener() { return mOnPreferenceTreeClickListener; }

    public interface OnActivityDestroyListener {
        void onActivityDestroy();
    }
    public interface OnActivityResultListener {
        boolean onActivityResult(int requestCode, int resultCode, Intent data);
    }
    public interface OnActivityStopListener {
        void onActivityStop();
    }
    public interface OnPreferenceTreeClickListener {
        boolean onPreferenceTreeClick(PreferenceScreen preferenceScreen, Preference preference);
    }
}
