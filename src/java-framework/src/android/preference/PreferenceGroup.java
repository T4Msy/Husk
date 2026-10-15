package android.preference;

import android.content.Context;
import android.content.res.TypedArray;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.AttributeSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A container of preferences, kept sorted by order (the order they were added in, unless android:orderingFromXml="false"), as AOSP's. */
@SuppressWarnings({"unchecked", "rawtypes", "deprecation"})
public abstract class PreferenceGroup extends Preference implements GenericInflater.Parent<Preference> {
    private List<Preference> mPreferenceList = new ArrayList<Preference>();
    private boolean mOrderingAsAdded = true;
    private int mCurrentPreferenceOrder = 0;
    private boolean mAttachedToActivity = false;

    public PreferenceGroup(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        if (context != null) {
            final TypedArray a = context.obtainStyledAttributes(attrs, new int[] { android.R.attr.orderingFromXml }, defStyleAttr, defStyleRes);
            mOrderingAsAdded = a.getBoolean(0, mOrderingAsAdded);
            a.recycle();
        }
    }
    public PreferenceGroup(Context context, AttributeSet attrs, int defStyleAttr) { this(context, attrs, defStyleAttr, 0); }
    public PreferenceGroup(Context context, AttributeSet attrs) { this(context, attrs, 0); }

    /** Whether children without an explicit order get one by insertion (true) or sort by title (false). */
    public void setOrderingAsAdded(boolean orderingAsAdded) { mOrderingAsAdded = orderingAsAdded; }
    public boolean isOrderingAsAdded() { return mOrderingAsAdded; }
    public void addItemFromInflater(Preference preference) { addPreference(preference); }
    public int getPreferenceCount() { return mPreferenceList.size(); }
    public Preference getPreference(int index) { return mPreferenceList.get(index); }

    public boolean addPreference(Preference preference) {
        if (mPreferenceList.contains(preference)) return true;
        if (preference.getOrder() == Preference.DEFAULT_ORDER) {
            if (mOrderingAsAdded) preference.setOrder(mCurrentPreferenceOrder++);
            if (preference instanceof PreferenceGroup) ((PreferenceGroup) preference).setOrderingAsAdded(mOrderingAsAdded);
        }
        int insertionIndex = Collections.binarySearch(mPreferenceList, preference);
        if (insertionIndex < 0) insertionIndex = insertionIndex * -1 - 1;
        if (!onPrepareAddPreference(preference)) return false;
        synchronized (this) { mPreferenceList.add(insertionIndex, preference); }
        preference.onAttachedToHierarchy(getPreferenceManager());
        preference.assignParent(this);
        if (mAttachedToActivity) preference.onAttachedToActivity();
        notifyHierarchyChanged();
        return true;
    }
    public boolean removePreference(Preference preference) {
        final boolean returnValue = removePreferenceInt(preference);
        notifyHierarchyChanged();
        return returnValue;
    }
    private boolean removePreferenceInt(Preference preference) {
        synchronized (this) {
            preference.onPrepareForRemoval();
            if (preference.getParent() == this) preference.assignParent(null);
            return mPreferenceList.remove(preference);
        }
    }
    public void removeAll() {
        synchronized (this) {
            List<Preference> preferenceList = mPreferenceList;
            for (int i = preferenceList.size() - 1; i >= 0; i--) removePreferenceInt(preferenceList.get(0));
        }
        notifyHierarchyChanged();
    }
    /** About to add a preference: it inherits this group's enabled state; false refuses it. */
    protected boolean onPrepareAddPreference(Preference preference) {
        preference.onParentChanged(this, shouldDisableDependents());
        return true;
    }
    /** The preference with the key, this group or any below it. */
    public Preference findPreference(CharSequence key) {
        if (key == null) throw new IllegalArgumentException("Key cannot be null");
        if (TextUtils.equals(getKey(), key)) return this;
        final int preferenceCount = getPreferenceCount();
        for (int i = 0; i < preferenceCount; i++) {
            final Preference preference = getPreference(i);
            final String curKey = preference.getKey();
            if (curKey != null && TextUtils.equals(curKey, key)) return preference;
            if (preference instanceof PreferenceGroup) {
                final Preference returnedPreference = ((PreferenceGroup) preference).findPreference(key);
                if (returnedPreference != null) return returnedPreference;
            }
        }
        return null;
    }
    /** Whether the children show in the same list as this group (a category) or on their own screen (a nested screen). */
    protected boolean isOnSameScreenAsChildren() { return true; }

    @Override protected void onAttachedToHierarchy(PreferenceManager preferenceManager) {
        super.onAttachedToHierarchy(preferenceManager);
        /* Android crashes on children added to a group before the group joined a hierarchy (they get no manager); here they
           get it, and their initial value, when the group does */
        if (preferenceManager != null) for (int i = 0; i < getPreferenceCount(); i++) { Preference p = getPreference(i); if (p.getPreferenceManager() == null) p.onAttachedToHierarchy(preferenceManager); }
    }
    @Override public void onAttachedToActivity() {
        super.onAttachedToActivity();
        mAttachedToActivity = true;
        final int preferenceCount = getPreferenceCount();
        for (int i = 0; i < preferenceCount; i++) getPreference(i).onAttachedToActivity();
    }
    @Override public void onPrepareForRemoval() { super.onPrepareForRemoval(); mAttachedToActivity = false; }
    @Override public void notifyDependencyChange(boolean disableDependents) {
        super.notifyDependencyChange(disableDependents);
        // children have an implicit dependency on their group
        final int preferenceCount = getPreferenceCount();
        for (int i = 0; i < preferenceCount; i++) getPreference(i).onParentChanged(this, disableDependents);
    }
    void sortPreferences() { synchronized (this) { Collections.sort(mPreferenceList); } }
    @Override protected void dispatchSaveInstanceState(Bundle container) {
        super.dispatchSaveInstanceState(container);
        final int preferenceCount = getPreferenceCount();
        for (int i = 0; i < preferenceCount; i++) getPreference(i).dispatchSaveInstanceState(container);
    }
    @Override protected void dispatchRestoreInstanceState(Bundle container) {
        super.dispatchRestoreInstanceState(container);
        final int preferenceCount = getPreferenceCount();
        for (int i = 0; i < preferenceCount; i++) getPreference(i).dispatchRestoreInstanceState(container);
    }
}
