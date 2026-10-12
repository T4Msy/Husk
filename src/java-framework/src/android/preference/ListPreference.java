package android.preference;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.TypedArray;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;

/**
 * A DialogPreference choosing one of entries (shown) for one of entryValues (persisted String), as AOSP's: a single-choice list
 * that closes on a pick. A summary with "%s" shows the current entry.
 */
@SuppressWarnings({"unchecked", "rawtypes", "deprecation"})
public class ListPreference extends DialogPreference {
    private CharSequence[] mEntries, mEntryValues;
    private String mValue, mSummary;
    private int mClickedDialogEntryIndex;
    private boolean mValueSet;

    public ListPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        if (context == null) return;
        TypedArray a = context.obtainStyledAttributes(attrs, new int[] { android.R.attr.entries, android.R.attr.entryValues }, defStyleAttr, defStyleRes);
        mEntries = a.getTextArray(0);
        mEntryValues = a.getTextArray(1);
        a.recycle();
        /* the summary again, as a format for the entry ("%s") */
        a = context.obtainStyledAttributes(attrs, new int[] { android.R.attr.summary }, defStyleAttr, defStyleRes);
        mSummary = a.getString(0);
        a.recycle();
    }
    public ListPreference(Context context, AttributeSet attrs, int defStyleAttr) { this(context, attrs, defStyleAttr, 0); }
    public ListPreference(Context context, AttributeSet attrs) { this(context, attrs, android.R.attr.dialogPreferenceStyle); }
    public ListPreference(Context context) { this(context, null); }

    public void setEntries(CharSequence[] entries) { mEntries = entries; }
    public void setEntries(int entriesResId) { setEntries(getContext().getResources().getTextArray(entriesResId)); }
    public CharSequence[] getEntries() { return mEntries; }
    public void setEntryValues(CharSequence[] entryValues) { mEntryValues = entryValues; }
    public void setEntryValues(int entryValuesResId) { setEntryValues(getContext().getResources().getTextArray(entryValuesResId)); }
    public CharSequence[] getEntryValues() { return mEntryValues; }
    public void setValue(String value) {
        // always persist/notify the first time
        final boolean changed = !TextUtils.equals(mValue, value);
        if (changed || !mValueSet) {
            mValue = value;
            mValueSet = true;
            persistString(value);
            if (changed) notifyChanged();
        }
    }
    @Override public CharSequence getSummary() {
        final CharSequence entry = getEntry();
        if (mSummary == null) return super.getSummary();
        try { return String.format(mSummary, entry == null ? "" : entry); }
        catch (java.util.IllegalFormatException e) { return mSummary; }   // a stray '%': Android throws, the text shows here
    }
    @Override public void setSummary(CharSequence summary) {
        super.setSummary(summary);
        if (summary == null && mSummary != null) mSummary = null;
        else if (summary != null && !summary.equals(mSummary)) mSummary = summary.toString();
    }
    public void setValueIndex(int index) { if (mEntryValues != null) setValue(mEntryValues[index].toString()); }
    public String getValue() { return mValue; }
    public CharSequence getEntry() { int index = getValueIndex(); return index >= 0 && mEntries != null && index < mEntries.length ? mEntries[index] : null; }
    public int findIndexOfValue(String value) {
        if (value != null && mEntryValues != null) for (int i = mEntryValues.length - 1; i >= 0; i--) if (mEntryValues[i] != null && mEntryValues[i].toString().equals(value)) return i;
        return -1;
    }
    private int getValueIndex() { return findIndexOfValue(mValue); }

    @Override public void onPrepareDialogBuilder(AlertDialog.Builder builder) {
        super.onPrepareDialogBuilder(builder);
        if (mEntries == null || mEntryValues == null) Log.w("ListPreference", "ListPreference requires an entries array and an entryValues array: " + getKey());
        mClickedDialogEntryIndex = getValueIndex();
        builder.setSingleChoiceItems(mEntries != null ? mEntries : new CharSequence[0], mClickedDialogEntryIndex, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                mClickedDialogEntryIndex = which;
                /* a click on an item stands for the positive button, and closes the dialog */
                ListPreference.this.onClick(dialog, DialogInterface.BUTTON_POSITIVE);
                dialog.dismiss();
            }
        });
        /* list dialogs close on a pick: no OK button */
        builder.setPositiveButton(null, null);
    }
    @Override public void onDialogClosed(boolean positiveResult) {
        super.onDialogClosed(positiveResult);
        if (positiveResult && mClickedDialogEntryIndex >= 0 && mEntryValues != null && mClickedDialogEntryIndex < mEntryValues.length) {
            String value = mEntryValues[mClickedDialogEntryIndex].toString();
            if (callChangeListener(value)) setValue(value);
        }
    }
    @Override public Object onGetDefaultValue(TypedArray a, int index) { return a.getString(index); }
    @Override public void onSetInitialValue(boolean restoreValue, Object defaultValue) { setValue(restoreValue ? getPersistedString(mValue) : (String) defaultValue); }

    @Override public Parcelable onSaveInstanceState() {
        final Parcelable superState = super.onSaveInstanceState();
        if (isPersistent()) return superState;
        final SavedState myState = new SavedState(superState);
        myState.value = getValue();
        return myState;
    }
    @Override public void onRestoreInstanceState(Parcelable state) {
        if (state == null || !state.getClass().equals(SavedState.class)) { super.onRestoreInstanceState(state); return; }
        SavedState myState = (SavedState) state;
        super.onRestoreInstanceState(myState.getSuperState());
        setValue(myState.value);
    }
    private static class SavedState extends BaseSavedState {
        String value;
        public SavedState(Parcel source) { super(source); value = source.readString(); }
        public SavedState(Parcelable superState) { super(superState); }
        @Override public void writeToParcel(Parcel dest, int flags) { super.writeToParcel(dest, flags); dest.writeString(value); }
        public static final Parcelable.Creator<SavedState> CREATOR = new Parcelable.Creator<SavedState>() {
            public SavedState createFromParcel(Parcel in) { return new SavedState(in); }
            public SavedState[] newArray(int size) { return new SavedState[size]; }
        };
    }
}
