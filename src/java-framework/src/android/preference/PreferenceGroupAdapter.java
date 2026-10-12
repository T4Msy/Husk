package android.preference;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * AOSP's (hidden) PreferenceGroupAdapter: a screen's hierarchy flattened for its ListView, a category's children after it and a
 * nested screen as a single row. Rows are recycled between preferences of the same class and layouts; a preference's change
 * rebinds the rows, a change in the hierarchy (added, removed, reordered) flattens it again on the next turn.
 */
class PreferenceGroupAdapter extends BaseAdapter implements Preference.OnPreferenceChangeInternalListener {
    private final PreferenceGroup mPreferenceGroup;
    private List<Preference> mPreferenceList;
    /** The kinds of row (class, layout, widget layout), sorted: a row's view type is its kind's index. */
    private final ArrayList<PreferenceLayout> mPreferenceLayouts;
    private PreferenceLayout mTempPreferenceLayout = new PreferenceLayout();
    /** Once the list asked for the number of view types it cannot take new ones: new kinds are not recycled. */
    private boolean mHasReturnedViewTypeCount = false;
    private volatile boolean mIsSyncing = false;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mSyncRunnable = new Runnable() { public void run() { syncMyPreferences(); } };

    private static class PreferenceLayout implements Comparable<PreferenceLayout> {
        private int resId, widgetResId;
        private String name;
        public int compareTo(PreferenceLayout other) {
            int compareNames = name.compareTo(other.name);
            if (compareNames != 0) return compareNames;
            if (resId != other.resId) return resId < other.resId ? -1 : 1;
            if (widgetResId != other.widgetResId) return widgetResId < other.widgetResId ? -1 : 1;
            return 0;
        }
    }

    PreferenceGroupAdapter(PreferenceGroup preferenceGroup) {
        mPreferenceGroup = preferenceGroup;
        // the group's own changes (an addPreference on the screen) reach this adapter too
        mPreferenceGroup.setOnPreferenceChangeInternalListener(this);
        mPreferenceList = new ArrayList<Preference>();
        mPreferenceLayouts = new ArrayList<PreferenceLayout>();
        syncMyPreferences();
    }
    private void syncMyPreferences() {
        synchronized (this) { if (mIsSyncing) return; mIsSyncing = true; }
        List<Preference> newPreferenceList = new ArrayList<Preference>(mPreferenceList.size());
        flattenPreferenceGroup(newPreferenceList, mPreferenceGroup);
        mPreferenceList = newPreferenceList;
        notifyDataSetChanged();
        synchronized (this) { mIsSyncing = false; notifyAll(); }
    }
    private void flattenPreferenceGroup(List<Preference> preferences, PreferenceGroup group) {
        group.sortPreferences();
        final int groupSize = group.getPreferenceCount();
        for (int i = 0; i < groupSize; i++) {
            final Preference preference = group.getPreference(i);
            preferences.add(preference);
            if (!mHasReturnedViewTypeCount && preference.isRecycleEnabled()) addPreferenceClassName(preference);
            if (preference instanceof PreferenceGroup) {
                final PreferenceGroup preferenceAsGroup = (PreferenceGroup) preference;
                if (preferenceAsGroup.isOnSameScreenAsChildren()) flattenPreferenceGroup(preferences, preferenceAsGroup);
            }
            preference.setOnPreferenceChangeInternalListener(this);
        }
    }
    private PreferenceLayout createPreferenceLayout(Preference preference, PreferenceLayout in) {
        PreferenceLayout pl = in != null ? in : new PreferenceLayout();
        pl.name = preference.getClass().getName();
        pl.resId = preference.getLayoutResource();
        pl.widgetResId = preference.getWidgetLayoutResource();
        return pl;
    }
    private void addPreferenceClassName(Preference preference) {
        final PreferenceLayout pl = createPreferenceLayout(preference, null);
        int insertPos = Collections.binarySearch(mPreferenceLayouts, pl);
        if (insertPos < 0) { insertPos = insertPos * -1 - 1; mPreferenceLayouts.add(insertPos, pl); }
    }

    public int getCount() { return mPreferenceList.size(); }
    public Preference getItem(int position) { if (position < 0 || position >= getCount()) return null; return mPreferenceList.get(position); }
    public long getItemId(int position) {
        if (position < 0 || position >= getCount()) return ListView.INVALID_ROW_ID;
        return this.getItem(position).getId();
    }
    public View getView(int position, View convertView, ViewGroup parent) {
        final Preference preference = this.getItem(position);
        mTempPreferenceLayout = createPreferenceLayout(preference, mTempPreferenceLayout);
        // a row of another kind (or one not recycled) is made again by the preference
        if (Collections.binarySearch(mPreferenceLayouts, mTempPreferenceLayout) < 0 || !preference.isRecycleEnabled()) convertView = null;
        return preference.getView(convertView, parent);
    }
    @Override public boolean isEnabled(int position) {
        if (position < 0 || position >= getCount()) return true;
        return this.getItem(position).isSelectable();
    }
    @Override public boolean areAllItemsEnabled() { return false; }
    public void onPreferenceChange(Preference preference) { notifyDataSetChanged(); }
    public void onPreferenceHierarchyChange(Preference preference) { mHandler.removeCallbacks(mSyncRunnable); mHandler.post(mSyncRunnable); }
    @Override public boolean hasStableIds() { return true; }
    @Override public int getItemViewType(int position) {
        if (!mHasReturnedViewTypeCount) mHasReturnedViewTypeCount = true;
        final Preference preference = this.getItem(position);
        if (preference == null || !preference.isRecycleEnabled()) return IGNORE_ITEM_VIEW_TYPE;
        mTempPreferenceLayout = createPreferenceLayout(preference, mTempPreferenceLayout);
        int viewType = Collections.binarySearch(mPreferenceLayouts, mTempPreferenceLayout);
        return viewType < 0 ? IGNORE_ITEM_VIEW_TYPE : viewType;
    }
    @Override public int getViewTypeCount() {
        if (!mHasReturnedViewTypeCount) mHasReturnedViewTypeCount = true;
        return Math.max(1, mPreferenceLayouts.size());
    }
}
