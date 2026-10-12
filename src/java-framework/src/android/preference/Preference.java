package android.preference;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.AbsSavedState;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The (deprecated) framework Preference, as AOSP's: a keyed value persisted in the PreferenceManager's SharedPreferences (or a
 * PreferenceDataStore), shown as a row of a PreferenceScreen's list. Subclasses hook the same places Android calls:
 * onCreateView/onBindView for the row, onClick, onGetDefaultValue/onSetInitialValue, onSave/RestoreInstanceState.
 */
@SuppressWarnings({"unchecked", "rawtypes", "deprecation"})
public class Preference implements Comparable<Preference> {
    private static final String TAG = "Preference";
    public static final int DEFAULT_ORDER = Integer.MAX_VALUE;
    /** com.android.internal.R.styleable.Preference's attributes this class reads (by index below). */
    private static final int[] ATTRS = {
        android.R.attr.icon, android.R.attr.key, android.R.attr.title, android.R.attr.summary, android.R.attr.order, android.R.attr.fragment,
        android.R.attr.layout, android.R.attr.widgetLayout, android.R.attr.enabled, android.R.attr.selectable, android.R.attr.persistent,
        android.R.attr.dependency, android.R.attr.defaultValue, android.R.attr.shouldDisableView, android.R.attr.recycleEnabled,
        android.R.attr.singleLineTitle, android.R.attr.iconSpaceReserved };
    private static final int A_ICON = 0, A_KEY = 1, A_TITLE = 2, A_SUMMARY = 3, A_ORDER = 4, A_FRAGMENT = 5, A_LAYOUT = 6, A_WIDGET_LAYOUT = 7,
        A_ENABLED = 8, A_SELECTABLE = 9, A_PERSISTENT = 10, A_DEPENDENCY = 11, A_DEFAULT_VALUE = 12, A_SHOULD_DISABLE_VIEW = 13,
        A_RECYCLE_ENABLED = 14, A_SINGLE_LINE_TITLE = 15, A_ICON_SPACE_RESERVED = 16;

    /** How the adapter showing this preference hears about changes (AOSP's hidden OnPreferenceChangeInternalListener). */
    interface OnPreferenceChangeInternalListener {
        void onPreferenceChange(Preference preference);
        void onPreferenceHierarchyChange(Preference preference);
    }

    private final Context mContext;
    private PreferenceManager mPreferenceManager;
    private PreferenceDataStore mPreferenceDataStore;
    private long mId;
    private OnPreferenceChangeListener mOnChangeListener;
    private OnPreferenceClickListener mOnClickListener;
    private int mOrder = DEFAULT_ORDER;
    private CharSequence mTitle;
    private int mTitleRes;
    private CharSequence mSummary;
    private int mIconResId;
    private Drawable mIcon;
    private String mKey;
    private Intent mIntent;
    private String mFragment;
    private Bundle mExtras;
    private boolean mEnabled = true, mSelectable = true, mPersistent = true;
    private String mDependencyKey;
    private Object mDefaultValue;
    private boolean mDependencyMet = true, mParentDependencyMet = true;
    private boolean mRecycleEnabled = true, mHasSingleLineTitleAttr, mSingleLineTitle = true, mIconSpaceReserved, mShouldDisableView = true;
    /** 0: Husk's built-in row (Android's private preference_material); otherwise the app's own layout. */
    private int mLayoutResId, mWidgetLayoutResId;
    private OnPreferenceChangeInternalListener mListener;
    private List<Preference> mDependents;
    private PreferenceGroup mParentGroup;

    public Preference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        mContext = context;
        // as the Preference.Category style: a category is not selectable, and its title does not dim with it
        if (this instanceof PreferenceCategory) { mSelectable = false; mShouldDisableView = false; }
        if (context == null) return;
        final TypedArray a = context.obtainStyledAttributes(attrs, ATTRS, defStyleAttr, defStyleRes);
        for (int i = a.getIndexCount() - 1; i >= 0; i--) {
            int attr = a.getIndex(i);
            switch (attr) {
            case A_ICON: mIconResId = a.getResourceId(attr, 0); break;
            case A_KEY: mKey = a.getString(attr); break;
            case A_TITLE: mTitleRes = a.getResourceId(attr, 0); mTitle = a.getText(attr); break;
            case A_SUMMARY: mSummary = a.getText(attr); break;
            case A_ORDER: mOrder = a.getInt(attr, mOrder); break;
            case A_FRAGMENT: mFragment = a.getString(attr); break;
            /* the theme's preference styles name Android's private layouts (built in code here); the app's own are inflated */
            case A_LAYOUT: mLayoutResId = PreferenceViews.appRes(a.getResourceId(attr, mLayoutResId)); break;
            case A_WIDGET_LAYOUT: mWidgetLayoutResId = PreferenceViews.appRes(a.getResourceId(attr, mWidgetLayoutResId)); break;
            case A_ENABLED: mEnabled = a.getBoolean(attr, true); break;
            case A_SELECTABLE: mSelectable = a.getBoolean(attr, true); break;
            case A_PERSISTENT: mPersistent = a.getBoolean(attr, mPersistent); break;
            case A_DEPENDENCY: mDependencyKey = a.getString(attr); break;
            case A_DEFAULT_VALUE: mDefaultValue = onGetDefaultValue(a, attr); break;
            case A_SHOULD_DISABLE_VIEW: mShouldDisableView = a.getBoolean(attr, mShouldDisableView); break;
            case A_RECYCLE_ENABLED: mRecycleEnabled = a.getBoolean(attr, mRecycleEnabled); break;
            case A_SINGLE_LINE_TITLE: mSingleLineTitle = a.getBoolean(attr, mSingleLineTitle); mHasSingleLineTitleAttr = true; break;
            case A_ICON_SPACE_RESERVED: mIconSpaceReserved = a.getBoolean(attr, mIconSpaceReserved); break;
            }
        }
        a.recycle();
    }
    public Preference(Context context, AttributeSet attrs, int defStyleAttr) { this(context, attrs, defStyleAttr, 0); }
    public Preference(Context context, AttributeSet attrs) { this(context, attrs, android.R.attr.preferenceStyle); }
    public Preference(Context context) { this(context, null); }

    /** The value android:defaultValue gives, read as this type's value (a String, Boolean, Set...). */
    protected Object onGetDefaultValue(TypedArray a, int index) { return null; }

    public void setIntent(Intent intent) { mIntent = intent; }
    public Intent getIntent() { return mIntent; }
    public void setFragment(String fragment) { mFragment = fragment; }
    public String getFragment() { return mFragment; }
    public void setPreferenceDataStore(PreferenceDataStore dataStore) { mPreferenceDataStore = dataStore; }
    public PreferenceDataStore getPreferenceDataStore() {
        if (mPreferenceDataStore != null) return mPreferenceDataStore;
        if (mPreferenceManager != null) return mPreferenceManager.getPreferenceDataStore();
        return null;
    }
    public Bundle getExtras() { if (mExtras == null) mExtras = new Bundle(); return mExtras; }
    public Bundle peekExtras() { return mExtras; }
    public void setLayoutResource(int layoutResId) { if (layoutResId != mLayoutResId) mRecycleEnabled = false; mLayoutResId = layoutResId; }
    public int getLayoutResource() { return mLayoutResId; }
    public void setWidgetLayoutResource(int widgetLayoutResId) { if (widgetLayoutResId != mWidgetLayoutResId) mRecycleEnabled = false; mWidgetLayoutResId = widgetLayoutResId; }
    public int getWidgetLayoutResource() { return mWidgetLayoutResId; }

    /** The row for the list: made once (onCreateView) and bound each time it shows (onBindView), as AOSP's. */
    public View getView(View convertView, ViewGroup parent) {
        if (convertView == null) convertView = onCreateView(parent);
        onBindView(convertView);
        return convertView;
    }
    protected View onCreateView(ViewGroup parent) {
        final Context c = mContext;
        final LayoutInflater inflater = LayoutInflater.from(c);
        View layout = null;
        if (mLayoutResId != 0) {
            try { layout = inflater.inflate(mLayoutResId, parent, false); }
            catch (RuntimeException e) { Log.w(TAG, "could not inflate layout 0x" + Integer.toHexString(mLayoutResId) + " of " + this + ": " + e); }
        }
        if (layout == null) layout = huskDefaultView(c);
        final View frame = layout.findViewById(android.R.id.widget_frame);
        if (frame instanceof ViewGroup) {
            final ViewGroup widgetFrame = (ViewGroup) frame;
            View widget = null;
            if (mWidgetLayoutResId != 0) {
                try { inflater.inflate(mWidgetLayoutResId, widgetFrame); widget = widgetFrame; }
                catch (RuntimeException e) { Log.w(TAG, "could not inflate widget layout of " + this + ": " + e); }
            }
            if (widget == null) {
                /* the widget the theme's style names (preference_widget_checkbox / _switch), built in code */
                View w = huskDefaultWidget(widgetFrame.getContext());
                if (w != null) widgetFrame.addView(w);
                else widgetFrame.setVisibility(View.GONE);
            }
        }
        return layout;
    }
    /** Husk: Android's default row layout for this type (preference_material; a category's own). */
    View huskDefaultView(Context c) { return PreferenceViews.row(c); }
    /** Husk: Android's default widget layout for this type (none; a check box or switch for two-state ones). */
    View huskDefaultWidget(Context c) { return null; }

    protected void onBindView(View view) {
        final View tv = view.findViewById(android.R.id.title);
        if (tv instanceof TextView) {
            final TextView titleView = (TextView) tv;
            final CharSequence title = getTitle();
            if (!TextUtils.isEmpty(title)) {
                titleView.setText(title);
                titleView.setVisibility(View.VISIBLE);
                if (mHasSingleLineTitleAttr) titleView.setSingleLine(mSingleLineTitle);
            } else titleView.setVisibility(View.GONE);
        }
        final View sv = view.findViewById(android.R.id.summary);
        if (sv instanceof TextView) {
            final TextView summaryView = (TextView) sv;
            final CharSequence summary = getSummary();
            if (!TextUtils.isEmpty(summary)) { summaryView.setText(summary); summaryView.setVisibility(View.VISIBLE); }
            else summaryView.setVisibility(View.GONE);
        }
        final View iv = view.findViewById(android.R.id.icon);
        if (iv instanceof ImageView) {
            final ImageView imageView = (ImageView) iv;
            if (mIconResId != 0 || mIcon != null) {
                if (mIcon == null) { try { mIcon = getContext().getDrawable(mIconResId); } catch (RuntimeException e) {} }
                if (mIcon != null) imageView.setImageDrawable(mIcon);
            }
            if (mIcon != null) imageView.setVisibility(View.VISIBLE);
            else imageView.setVisibility(mIconSpaceReserved ? View.INVISIBLE : View.GONE);
        }
        final View imageFrame = view.findViewWithTag(PreferenceViews.TAG_ICON_FRAME);
        if (imageFrame != null) imageFrame.setVisibility(mIcon != null ? View.VISIBLE : mIconSpaceReserved ? View.INVISIBLE : View.GONE);
        if (mShouldDisableView) setEnabledStateOnViews(view, isEnabled());
    }
    private void setEnabledStateOnViews(View v, boolean enabled) {
        v.setEnabled(enabled);
        if (v instanceof ViewGroup) { final ViewGroup vg = (ViewGroup) v; for (int i = vg.getChildCount() - 1; i >= 0; i--) setEnabledStateOnViews(vg.getChildAt(i), enabled); }
    }

    public void setOrder(int order) { if (order != mOrder) { mOrder = order; notifyHierarchyChanged(); } }
    public int getOrder() { return mOrder; }
    public void setTitle(CharSequence title) {
        if (title == null && mTitle != null || title != null && !title.equals(mTitle)) { mTitleRes = 0; mTitle = title; notifyChanged(); }
    }
    public void setTitle(int titleResId) { setTitle(mContext.getString(titleResId)); mTitleRes = titleResId; }
    public int getTitleRes() { return mTitleRes; }
    public CharSequence getTitle() { return mTitle; }
    public void setIcon(Drawable icon) { if ((icon == null && mIcon != null) || (icon != null && mIcon != icon)) { mIcon = icon; notifyChanged(); } }
    public void setIcon(int iconResId) { if (mIconResId != iconResId) { mIconResId = iconResId; setIcon(iconResId == 0 ? null : mContext.getDrawable(iconResId)); } }
    public Drawable getIcon() {
        if (mIcon == null && mIconResId != 0) { try { mIcon = getContext().getDrawable(mIconResId); } catch (RuntimeException e) {} }
        return mIcon;
    }
    public CharSequence getSummary() { return mSummary; }
    public void setSummary(CharSequence summary) {
        if (summary == null && mSummary != null || summary != null && !summary.equals(mSummary)) { mSummary = summary; notifyChanged(); }
    }
    public void setSummary(int summaryResId) { setSummary(mContext.getString(summaryResId)); }
    public void setEnabled(boolean enabled) {
        if (mEnabled != enabled) { mEnabled = enabled; notifyDependencyChange(shouldDisableDependents()); notifyChanged(); }
    }
    public boolean isEnabled() { return mEnabled && mDependencyMet && mParentDependencyMet; }
    public void setSelectable(boolean selectable) { if (mSelectable != selectable) { mSelectable = selectable; notifyChanged(); } }
    public boolean isSelectable() { return mSelectable; }
    public void setShouldDisableView(boolean shouldDisableView) { mShouldDisableView = shouldDisableView; notifyChanged(); }
    public boolean getShouldDisableView() { return mShouldDisableView; }
    public void setRecycleEnabled(boolean enabled) { mRecycleEnabled = enabled; notifyChanged(); }
    public boolean isRecycleEnabled() { return mRecycleEnabled; }
    public void setSingleLineTitle(boolean singleLineTitle) { mHasSingleLineTitleAttr = true; mSingleLineTitle = singleLineTitle; notifyChanged(); }
    public boolean isSingleLineTitle() { return mSingleLineTitle; }
    public void setIconSpaceReserved(boolean iconSpaceReserved) { mIconSpaceReserved = iconSpaceReserved; notifyChanged(); }
    public boolean isIconSpaceReserved() { return mIconSpaceReserved; }
    long getId() { return mId; }

    /** A click on the row: nothing here (two-state ones toggle, dialog ones open their dialog). */
    protected void onClick() {}
    public void setKey(String key) { mKey = key; }
    public String getKey() { return mKey; }
    public boolean hasKey() { return !TextUtils.isEmpty(mKey); }
    public boolean isPersistent() { return mPersistent; }
    protected boolean shouldPersist() { return mPreferenceManager != null && isPersistent() && hasKey(); }
    public void setPersistent(boolean persistent) { mPersistent = persistent; }
    /** Whether the new value is accepted: the app's OnPreferenceChangeListener decides (none: yes). */
    protected boolean callChangeListener(Object newValue) { return mOnChangeListener == null || mOnChangeListener.onPreferenceChange(this, newValue); }
    public void setOnPreferenceChangeListener(OnPreferenceChangeListener l) { mOnChangeListener = l; }
    public OnPreferenceChangeListener getOnPreferenceChangeListener() { return mOnChangeListener; }
    public void setOnPreferenceClickListener(OnPreferenceClickListener l) { mOnClickListener = l; }
    public OnPreferenceClickListener getOnPreferenceClickListener() { return mOnClickListener; }

    /** As AOSP's performClick: onClick, then the app's click listener, the tree's (the activity or fragment), else the intent. */
    public void performClick(PreferenceScreen preferenceScreen) {
        if (!isEnabled()) return;
        onClick();
        if (mOnClickListener != null && mOnClickListener.onPreferenceClick(this)) return;
        PreferenceManager preferenceManager = getPreferenceManager();
        if (preferenceManager != null) {
            PreferenceManager.OnPreferenceTreeClickListener listener = preferenceManager.getOnPreferenceTreeClickListener();
            if (preferenceScreen != null && listener != null && listener.onPreferenceTreeClick(preferenceScreen, this)) return;
        }
        if (mIntent != null) {
            try { getContext().startActivity(mIntent); }
            catch (RuntimeException e) { Log.w(TAG, "could not start " + mIntent + " for " + this + ": " + e); }
        }
    }
    public boolean onKey(View v, int keyCode, KeyEvent event) { return false; }
    public Context getContext() { return mContext; }
    public SharedPreferences getSharedPreferences() {
        if (mPreferenceManager == null || getPreferenceDataStore() != null) return null;
        return mPreferenceManager.getSharedPreferences();
    }
    public SharedPreferences.Editor getEditor() {
        if (mPreferenceManager == null || getPreferenceDataStore() != null) return null;
        return mPreferenceManager.getEditor();
    }
    public boolean shouldCommit() { return mPreferenceManager != null && mPreferenceManager.shouldCommit(); }

    public int compareTo(Preference another) {
        if (mOrder != another.mOrder) return Integer.compare(mOrder, another.mOrder);
        if (mTitle == another.mTitle) return 0;
        if (mTitle == null) return 1;
        if (another.mTitle == null) return -1;
        return mTitle.toString().compareToIgnoreCase(another.mTitle.toString());
    }

    final void setOnPreferenceChangeInternalListener(OnPreferenceChangeInternalListener listener) { mListener = listener; }
    /** Shown values changed: the list rebinds the rows. */
    protected void notifyChanged() { if (mListener != null) mListener.onPreferenceChange(this); }
    /** Preferences were added, removed or reordered: the list flattens the hierarchy again. */
    protected void notifyHierarchyChanged() { if (mListener != null) mListener.onPreferenceHierarchyChange(this); }
    public PreferenceManager getPreferenceManager() { return mPreferenceManager; }

    /** Added to a hierarchy: gets its manager (so its storage) and its initial value. */
    protected void onAttachedToHierarchy(PreferenceManager preferenceManager) {
        mPreferenceManager = preferenceManager;
        if (preferenceManager != null) mId = preferenceManager.getNextId();
        dispatchSetInitialValue();
    }
    /** The hierarchy is bound to the activity's list: dependencies can be looked up now. */
    protected void onAttachedToActivity() { registerDependency(); }
    final void assignParent(PreferenceGroup parentGroup) { mParentGroup = parentGroup; }
    public PreferenceGroup getParent() { return mParentGroup; }

    private void registerDependency() {
        if (TextUtils.isEmpty(mDependencyKey)) return;
        Preference preference = findPreferenceInHierarchy(mDependencyKey);
        if (preference != null) preference.registerDependent(this);
        /* Android throws here; a missing dependency only leaves this preference independent */
        else Log.w(TAG, "Dependency \"" + mDependencyKey + "\" not found for preference \"" + mKey + "\" (title: \"" + mTitle + "\")");
    }
    private void unregisterDependency() {
        if (mDependencyKey != null) { final Preference oldDependency = findPreferenceInHierarchy(mDependencyKey); if (oldDependency != null) oldDependency.unregisterDependent(this); }
    }
    protected Preference findPreferenceInHierarchy(String key) {
        if (TextUtils.isEmpty(key) || mPreferenceManager == null) return null;
        return mPreferenceManager.findPreference(key);
    }
    private void registerDependent(Preference dependent) {
        if (mDependents == null) mDependents = new ArrayList<Preference>();
        if (!mDependents.contains(dependent)) mDependents.add(dependent);
        dependent.onDependencyChanged(this, shouldDisableDependents());
    }
    private void unregisterDependent(Preference dependent) { if (mDependents != null) mDependents.remove(dependent); }
    public void notifyDependencyChange(boolean disableDependents) {
        final List<Preference> dependents = mDependents;
        if (dependents == null) return;
        for (int i = 0; i < dependents.size(); i++) dependents.get(i).onDependencyChanged(this, disableDependents);
    }
    public void onDependencyChanged(Preference dependency, boolean disableDependent) {
        if (mDependencyMet == disableDependent) { mDependencyMet = !disableDependent; notifyDependencyChange(shouldDisableDependents()); notifyChanged(); }
    }
    public void onParentChanged(Preference parent, boolean disableChild) {
        if (mParentDependencyMet == disableChild) { mParentDependencyMet = !disableChild; notifyDependencyChange(shouldDisableDependents()); notifyChanged(); }
    }
    /** Whether the preferences depending on this one are disabled (by default: when this one is). */
    public boolean shouldDisableDependents() { return !isEnabled(); }
    public void setDependency(String dependencyKey) { unregisterDependency(); mDependencyKey = dependencyKey; registerDependency(); }
    public String getDependency() { return mDependencyKey; }
    protected void onPrepareForRemoval() { unregisterDependency(); }

    public void setDefaultValue(Object defaultValue) { mDefaultValue = defaultValue; }
    private void dispatchSetInitialValue() {
        if (getPreferenceDataStore() != null) { onSetInitialValue(true, mDefaultValue); return; }
        final boolean shouldPersist = shouldPersist();
        final SharedPreferences sp = shouldPersist ? getSharedPreferences() : null;
        if (!shouldPersist || sp == null || !sp.contains(mKey)) { if (mDefaultValue != null) onSetInitialValue(false, mDefaultValue); }
        else onSetInitialValue(true, null);
    }
    /** Take the persisted value (restoreValue) or the default one (and persist it). */
    protected void onSetInitialValue(boolean restorePersistedValue, Object defaultValue) {}

    private void tryCommit(SharedPreferences.Editor editor) {
        if (mPreferenceManager.shouldCommit()) { try { editor.apply(); } catch (AbstractMethodError unused) { editor.commit(); } }
    }
    private SharedPreferences prefs() { return mPreferenceManager != null ? mPreferenceManager.getSharedPreferences() : null; }
    private SharedPreferences.Editor editor() { return mPreferenceManager != null ? mPreferenceManager.getEditor() : null; }
    protected boolean persistString(String value) {
        if (!shouldPersist()) return false;
        if (TextUtils.equals(value, getPersistedString(null))) return true;    // already there
        PreferenceDataStore dataStore = getPreferenceDataStore();
        if (dataStore != null) dataStore.putString(mKey, value);
        else { SharedPreferences.Editor e = editor(); if (e == null) return false; e.putString(mKey, value); tryCommit(e); }
        return true;
    }
    protected String getPersistedString(String defaultReturnValue) {
        if (!shouldPersist()) return defaultReturnValue;
        PreferenceDataStore dataStore = getPreferenceDataStore();
        if (dataStore != null) return dataStore.getString(mKey, defaultReturnValue);
        SharedPreferences sp = prefs();
        return sp == null ? defaultReturnValue : sp.getString(mKey, defaultReturnValue);
    }
    public boolean persistStringSet(Set<String> values) {
        if (!shouldPersist()) return false;
        if (values != null && values.equals(getPersistedStringSet(null))) return true;
        PreferenceDataStore dataStore = getPreferenceDataStore();
        if (dataStore != null) dataStore.putStringSet(mKey, values);
        else { SharedPreferences.Editor e = editor(); if (e == null) return false; e.putStringSet(mKey, values); tryCommit(e); }
        return true;
    }
    public Set<String> getPersistedStringSet(Set<String> defaultReturnValue) {
        if (!shouldPersist()) return defaultReturnValue;
        PreferenceDataStore dataStore = getPreferenceDataStore();
        if (dataStore != null) return dataStore.getStringSet(mKey, defaultReturnValue);
        SharedPreferences sp = prefs();
        return sp == null ? defaultReturnValue : sp.getStringSet(mKey, defaultReturnValue);
    }
    protected boolean persistInt(int value) {
        if (!shouldPersist()) return false;
        if (value == getPersistedInt(~value)) return true;
        PreferenceDataStore dataStore = getPreferenceDataStore();
        if (dataStore != null) dataStore.putInt(mKey, value);
        else { SharedPreferences.Editor e = editor(); if (e == null) return false; e.putInt(mKey, value); tryCommit(e); }
        return true;
    }
    protected int getPersistedInt(int defaultReturnValue) {
        if (!shouldPersist()) return defaultReturnValue;
        PreferenceDataStore dataStore = getPreferenceDataStore();
        if (dataStore != null) return dataStore.getInt(mKey, defaultReturnValue);
        SharedPreferences sp = prefs();
        return sp == null ? defaultReturnValue : sp.getInt(mKey, defaultReturnValue);
    }
    protected boolean persistFloat(float value) {
        if (!shouldPersist()) return false;
        if (value == getPersistedFloat(Float.NaN)) return true;
        PreferenceDataStore dataStore = getPreferenceDataStore();
        if (dataStore != null) dataStore.putFloat(mKey, value);
        else { SharedPreferences.Editor e = editor(); if (e == null) return false; e.putFloat(mKey, value); tryCommit(e); }
        return true;
    }
    protected float getPersistedFloat(float defaultReturnValue) {
        if (!shouldPersist()) return defaultReturnValue;
        PreferenceDataStore dataStore = getPreferenceDataStore();
        if (dataStore != null) return dataStore.getFloat(mKey, defaultReturnValue);
        SharedPreferences sp = prefs();
        return sp == null ? defaultReturnValue : sp.getFloat(mKey, defaultReturnValue);
    }
    protected boolean persistLong(long value) {
        if (!shouldPersist()) return false;
        if (value == getPersistedLong(~value)) return true;
        PreferenceDataStore dataStore = getPreferenceDataStore();
        if (dataStore != null) dataStore.putLong(mKey, value);
        else { SharedPreferences.Editor e = editor(); if (e == null) return false; e.putLong(mKey, value); tryCommit(e); }
        return true;
    }
    protected long getPersistedLong(long defaultReturnValue) {
        if (!shouldPersist()) return defaultReturnValue;
        PreferenceDataStore dataStore = getPreferenceDataStore();
        if (dataStore != null) return dataStore.getLong(mKey, defaultReturnValue);
        SharedPreferences sp = prefs();
        return sp == null ? defaultReturnValue : sp.getLong(mKey, defaultReturnValue);
    }
    protected boolean persistBoolean(boolean value) {
        if (!shouldPersist()) return false;
        if (value == getPersistedBoolean(!value)) return true;
        PreferenceDataStore dataStore = getPreferenceDataStore();
        if (dataStore != null) dataStore.putBoolean(mKey, value);
        else { SharedPreferences.Editor e = editor(); if (e == null) return false; e.putBoolean(mKey, value); tryCommit(e); }
        return true;
    }
    protected boolean getPersistedBoolean(boolean defaultReturnValue) {
        if (!shouldPersist()) return defaultReturnValue;
        PreferenceDataStore dataStore = getPreferenceDataStore();
        if (dataStore != null) return dataStore.getBoolean(mKey, defaultReturnValue);
        SharedPreferences sp = prefs();
        return sp == null ? defaultReturnValue : sp.getBoolean(mKey, defaultReturnValue);
    }

    @Override public String toString() { return getFilterableStringBuilder().toString(); }
    StringBuilder getFilterableStringBuilder() {
        StringBuilder sb = new StringBuilder();
        CharSequence title = getTitle();
        if (!TextUtils.isEmpty(title)) sb.append(title).append(' ');
        CharSequence summary = getSummary();
        if (!TextUtils.isEmpty(summary)) sb.append(summary).append(' ');
        if (sb.length() > 0) sb.setLength(sb.length() - 1);
        return sb;
    }

    /** State across a recreate, by key (only keyed preferences save any). */
    public void saveHierarchyState(Bundle container) { dispatchSaveInstanceState(container); }
    void dispatchSaveInstanceState(Bundle container) {
        if (hasKey()) { Parcelable state = onSaveInstanceState(); if (state != null) container.putParcelable(mKey, state); }
    }
    protected Parcelable onSaveInstanceState() { return BaseSavedState.EMPTY_STATE; }
    public void restoreHierarchyState(Bundle container) { dispatchRestoreInstanceState(container); }
    void dispatchRestoreInstanceState(Bundle container) {
        if (hasKey()) { Parcelable state = container.getParcelable(mKey); if (state != null) onRestoreInstanceState(state); }
    }
    protected void onRestoreInstanceState(Parcelable state) {
        if (state != BaseSavedState.EMPTY_STATE && state != null) Log.w(TAG, "Wrong state class -- expecting Preference State: " + state.getClass().getName());
    }

    public static class BaseSavedState extends AbsSavedState {
        public BaseSavedState(Parcel source) { super(source); }
        public BaseSavedState(Parcelable superState) { super(superState); }
        public static final Parcelable.Creator<BaseSavedState> CREATOR = new Parcelable.Creator<BaseSavedState>() {
            public BaseSavedState createFromParcel(Parcel in) { return new BaseSavedState(in); }
            public BaseSavedState[] newArray(int size) { return new BaseSavedState[size]; }
        };
    }
    public interface OnPreferenceChangeListener {
        boolean onPreferenceChange(Preference preference, Object newValue);
    }
    public interface OnPreferenceClickListener {
        boolean onPreferenceClick(Preference preference);
    }
}
