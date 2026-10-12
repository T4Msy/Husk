package android.preference;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.TypedArray;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.AttributeSet;
import android.util.Log;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** A DialogPreference choosing any of entries, persisted as the Set<String> of their entryValues, as AOSP's: a multi-choice list with OK. */
@SuppressWarnings({"unchecked", "rawtypes", "deprecation"})
public class MultiSelectListPreference extends DialogPreference {
    private CharSequence[] mEntries, mEntryValues;
    private Set<String> mValues = new HashSet<String>();
    private Set<String> mNewValues = new HashSet<String>();
    private boolean mPreferenceChanged;

    public MultiSelectListPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        if (context == null) return;
        TypedArray a = context.obtainStyledAttributes(attrs, new int[] { android.R.attr.entries, android.R.attr.entryValues }, defStyleAttr, defStyleRes);
        mEntries = a.getTextArray(0);
        mEntryValues = a.getTextArray(1);
        a.recycle();
    }
    public MultiSelectListPreference(Context context, AttributeSet attrs, int defStyleAttr) { this(context, attrs, defStyleAttr, 0); }
    public MultiSelectListPreference(Context context, AttributeSet attrs) { this(context, attrs, android.R.attr.dialogPreferenceStyle); }
    public MultiSelectListPreference(Context context) { this(context, null); }

    public void setEntries(CharSequence[] entries) { mEntries = entries; }
    public void setEntries(int entriesResId) { setEntries(getContext().getResources().getTextArray(entriesResId)); }
    public CharSequence[] getEntries() { return mEntries; }
    public void setEntryValues(CharSequence[] entryValues) { mEntryValues = entryValues; }
    public void setEntryValues(int entryValuesResId) { setEntryValues(getContext().getResources().getTextArray(entryValuesResId)); }
    public CharSequence[] getEntryValues() { return mEntryValues; }
    public void setValues(Set<String> values) {
        final Set<String> copy = values != null ? new HashSet<String>(values) : null;   // values may be mValues itself
        mValues.clear();
        if (copy != null) mValues.addAll(copy);
        persistStringSet(values);
        notifyChanged();
    }
    public Set<String> getValues() { return mValues; }
    public int findIndexOfValue(String value) {
        if (value != null && mEntryValues != null) for (int i = mEntryValues.length - 1; i >= 0; i--) if (mEntryValues[i] != null && mEntryValues[i].toString().equals(value)) return i;
        return -1;
    }
    @Override public void onPrepareDialogBuilder(AlertDialog.Builder builder) {
        super.onPrepareDialogBuilder(builder);
        if (mEntries == null || mEntryValues == null) Log.w("MultiSelectListPreference", "MultiSelectListPreference requires an entries array and an entryValues array: " + getKey());
        boolean[] checkedItems = getSelectedItems();
        builder.setMultiChoiceItems(mEntries != null ? mEntries : new CharSequence[0], checkedItems, new DialogInterface.OnMultiChoiceClickListener() {
            public void onClick(DialogInterface dialog, int which, boolean isChecked) {
                if (mEntryValues == null || which >= mEntryValues.length) return;
                if (isChecked) mPreferenceChanged |= mNewValues.add(mEntryValues[which].toString());
                else mPreferenceChanged |= mNewValues.remove(mEntryValues[which].toString());
            }
        });
        mNewValues.clear();
        mNewValues.addAll(mValues);
    }
    private boolean[] getSelectedItems() {
        final CharSequence[] entries = mEntryValues != null ? mEntryValues : new CharSequence[0];
        final int entryCount = entries.length;
        final Set<String> values = mValues;
        boolean[] result = new boolean[mEntries != null ? Math.max(entryCount, mEntries.length) : entryCount];
        for (int i = 0; i < entryCount; i++) result[i] = values.contains(entries[i].toString());
        return result;
    }
    @Override public void onDialogClosed(boolean positiveResult) {
        super.onDialogClosed(positiveResult);
        if (positiveResult && mPreferenceChanged) {
            final Set<String> values = new HashSet<String>(mNewValues);
            if (callChangeListener(values)) setValues(values);
        }
        mPreferenceChanged = false;
    }
    @Override public Object onGetDefaultValue(TypedArray a, int index) {
        final CharSequence[] defaultValues = a.getTextArray(index);
        final Set<String> result = new HashSet<String>();
        if (defaultValues != null) for (CharSequence v : defaultValues) result.add(v.toString());
        return result;
    }
    @Override public void onSetInitialValue(boolean restoreValue, Object defaultValue) {
        setValues(restoreValue ? getPersistedStringSet(mValues) : (Set<String>) defaultValue);
    }

    @Override public Parcelable onSaveInstanceState() {
        final Parcelable superState = super.onSaveInstanceState();
        if (isPersistent()) return superState;
        final SavedState myState = new SavedState(superState);
        myState.values = getValues();
        return myState;
    }
    private static class SavedState extends BaseSavedState {
        Set<String> values;
        public SavedState(Parcel source) {
            super(source);
            values = new HashSet<String>();
            String[] strings = source.readStringArray();
            if (strings != null) Collections.addAll(values, strings);
        }
        public SavedState(Parcelable superState) { super(superState); }
        @Override public void writeToParcel(Parcel dest, int flags) { super.writeToParcel(dest, flags); dest.writeStringArray(values.toArray(new String[0])); }
        public static final Parcelable.Creator<SavedState> CREATOR = new Parcelable.Creator<SavedState>() {
            public SavedState createFromParcel(Parcel in) { return new SavedState(in); }
            public SavedState[] newArray(int size) { return new SavedState[size]; }
        };
    }
}
