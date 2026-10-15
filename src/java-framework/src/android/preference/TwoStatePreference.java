package android.preference;

import android.content.Context;
import android.content.res.TypedArray;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;

/** A preference with an on/off state, persisted as a boolean, as AOSP's: a click toggles it (if the change listener agrees). */
@SuppressWarnings({"unchecked", "rawtypes", "deprecation"})
public abstract class TwoStatePreference extends Preference {
    private CharSequence mSummaryOn, mSummaryOff;
    boolean mChecked;
    private boolean mCheckedSet;
    private boolean mDisableDependentsState;

    public TwoStatePreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) { super(context, attrs, defStyleAttr, defStyleRes); }
    public TwoStatePreference(Context context, AttributeSet attrs, int defStyleAttr) { this(context, attrs, defStyleAttr, 0); }
    public TwoStatePreference(Context context, AttributeSet attrs) { this(context, attrs, 0); }
    public TwoStatePreference(Context context) { this(context, null); }

    @Override public void onClick() {
        super.onClick();
        final boolean newValue = !isChecked();
        if (callChangeListener(newValue)) setChecked(newValue);
    }
    public void setChecked(boolean checked) {
        // always persist/notify the first time; don't assume the field's default of false
        final boolean changed = mChecked != checked;
        if (changed || !mCheckedSet) {
            mChecked = checked;
            mCheckedSet = true;
            persistBoolean(checked);
            if (changed) { notifyDependencyChange(shouldDisableDependents()); notifyChanged(); }
        }
    }
    public boolean isChecked() { return mChecked; }
    @Override public boolean shouldDisableDependents() {
        boolean shouldDisable = mDisableDependentsState ? mChecked : !mChecked;
        return shouldDisable || super.shouldDisableDependents();
    }
    public void setSummaryOn(CharSequence summary) { mSummaryOn = summary; if (isChecked()) notifyChanged(); }
    public void setSummaryOn(int summaryResId) { setSummaryOn(getContext().getString(summaryResId)); }
    public CharSequence getSummaryOn() { return mSummaryOn; }
    public void setSummaryOff(CharSequence summary) { mSummaryOff = summary; if (!isChecked()) notifyChanged(); }
    public void setSummaryOff(int summaryResId) { setSummaryOff(getContext().getString(summaryResId)); }
    public CharSequence getSummaryOff() { return mSummaryOff; }
    /** Whether dependents are disabled when this is checked (true) or when unchecked (false, the default). */
    public boolean getDisableDependentsState() { return mDisableDependentsState; }
    public void setDisableDependentsState(boolean disableDependentsState) { mDisableDependentsState = disableDependentsState; }
    /** Read summaryOn, summaryOff and disableDependentsState (the subclasses' styleables carry them). */
    void readTwoStateAttrs(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        if (context == null) return;
        TypedArray a = context.obtainStyledAttributes(attrs, new int[] { android.R.attr.summaryOn, android.R.attr.summaryOff, android.R.attr.disableDependentsState }, defStyleAttr, defStyleRes);
        setSummaryOn(a.getString(0));
        setSummaryOff(a.getString(1));
        setDisableDependentsState(a.getBoolean(2, false));
        a.recycle();
    }

    @Override public Object onGetDefaultValue(TypedArray a, int index) { return a.getBoolean(index, false); }
    @Override public void onSetInitialValue(boolean restoreValue, Object defaultValue) {
        setChecked(restoreValue ? getPersistedBoolean(mChecked) : defaultValue instanceof Boolean ? (Boolean) defaultValue : false);
    }
    /** The summary for the state: summaryOn / summaryOff, else the plain summary; hidden when there is none. */
    void syncSummaryView(View view) {
        View v = view.findViewById(android.R.id.summary);
        if (v instanceof TextView) {
            TextView summaryView = (TextView) v;
            boolean useDefaultSummary = true;
            if (mChecked && !TextUtils.isEmpty(mSummaryOn)) { summaryView.setText(mSummaryOn); useDefaultSummary = false; }
            else if (!mChecked && !TextUtils.isEmpty(mSummaryOff)) { summaryView.setText(mSummaryOff); useDefaultSummary = false; }
            if (useDefaultSummary) { final CharSequence summary = getSummary(); if (!TextUtils.isEmpty(summary)) { summaryView.setText(summary); useDefaultSummary = false; } }
            int newVisibility = useDefaultSummary ? View.GONE : View.VISIBLE;
            if (newVisibility != summaryView.getVisibility()) summaryView.setVisibility(newVisibility);
        }
    }

    @Override public Parcelable onSaveInstanceState() {
        final Parcelable superState = super.onSaveInstanceState();
        if (isPersistent()) return superState;   // no need to save, it is persisted
        final SavedState myState = new SavedState(superState);
        myState.checked = isChecked();
        return myState;
    }
    @Override public void onRestoreInstanceState(Parcelable state) {
        if (state == null || !state.getClass().equals(SavedState.class)) { super.onRestoreInstanceState(state); return; }
        SavedState myState = (SavedState) state;
        super.onRestoreInstanceState(myState.getSuperState());
        setChecked(myState.checked);
    }
    static class SavedState extends BaseSavedState {
        boolean checked;
        public SavedState(Parcel source) { super(source); checked = source.readInt() == 1; }
        public SavedState(Parcelable superState) { super(superState); }
        @Override public void writeToParcel(Parcel dest, int flags) { super.writeToParcel(dest, flags); dest.writeInt(checked ? 1 : 0); }
        public static final Parcelable.Creator<SavedState> CREATOR = new Parcelable.Creator<SavedState>() {
            public SavedState createFromParcel(Parcel in) { return new SavedState(in); }
            public SavedState[] newArray(int size) { return new SavedState[size]; }
        };
    }
}
