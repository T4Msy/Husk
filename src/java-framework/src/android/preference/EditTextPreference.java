package android.preference;

import android.content.Context;
import android.content.res.TypedArray;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.EditText;

/**
 * A DialogPreference editing a String in an EditText, as AOSP's. The EditText is made from the preference's own attributes, so
 * android:inputType, android:hint, android:singleLine... in the preference XML apply to it.
 */
@SuppressWarnings({"unchecked", "rawtypes", "deprecation"})
public class EditTextPreference extends DialogPreference {
    private EditText mEditText;
    private String mText;

    public EditTextPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        if (context == null) return;
        mEditText = new EditText(context, attrs);
        // an id, so its state can be saved and restored
        mEditText.setId(android.R.id.edit);
        /* the preference's android:enabled is the preference's, not the view's */
        mEditText.setEnabled(true);
    }
    public EditTextPreference(Context context, AttributeSet attrs, int defStyleAttr) { this(context, attrs, defStyleAttr, 0); }
    public EditTextPreference(Context context, AttributeSet attrs) { this(context, attrs, android.R.attr.editTextPreferenceStyle); }
    public EditTextPreference(Context context) { this(context, null); }

    public void setText(String text) {
        final boolean wasBlocking = shouldDisableDependents();
        final boolean changed = !TextUtils.equals(mText, text);
        mText = text;
        persistString(text);
        final boolean isBlocking = shouldDisableDependents();
        if (isBlocking != wasBlocking) notifyDependencyChange(isBlocking);
        if (changed) notifyChanged();
    }
    public String getText() { return mText; }
    @Override public void onBindDialogView(View view) {
        super.onBindDialogView(view);
        EditText editText = mEditText;
        editText.setText(getText());
        ViewParent oldParent = editText.getParent();
        if (oldParent != view) {
            if (oldParent != null) ((ViewGroup) oldParent).removeView(editText);
            onAddEditTextToDialogView(view, editText);
        }
    }
    /** Put the EditText in the dialog's view: in its edit text container (Android's internal edittext_container). */
    protected void onAddEditTextToDialogView(View dialogView, EditText editText) {
        View container = dialogView.findViewWithTag(PreferenceViews.TAG_EDITTEXT_CONTAINER);
        if (container instanceof ViewGroup) ((ViewGroup) container).addView(editText, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
    @Override View huskDefaultDialogView(Context c) { return PreferenceViews.editTextDialog(c); }
    @Override public void onDialogClosed(boolean positiveResult) {
        super.onDialogClosed(positiveResult);
        if (positiveResult) {
            String value = mEditText.getText().toString();
            if (callChangeListener(value)) setText(value);
        }
    }
    @Override public Object onGetDefaultValue(TypedArray a, int index) { return a.getString(index); }
    @Override public void onSetInitialValue(boolean restoreValue, Object defaultValue) {
        setText(restoreValue ? getPersistedString(mText) : (String) defaultValue);
    }
    @Override public boolean shouldDisableDependents() { return TextUtils.isEmpty(mText) || super.shouldDisableDependents(); }
    public EditText getEditText() { return mEditText; }
    @Override boolean needInputMethod() { return true; }
    @Override public void showDialog(android.os.Bundle state) { super.showDialog(state); }

    @Override public Parcelable onSaveInstanceState() {
        final Parcelable superState = super.onSaveInstanceState();
        if (isPersistent()) return superState;
        final SavedState myState = new SavedState(superState);
        myState.text = getText();
        return myState;
    }
    @Override public void onRestoreInstanceState(Parcelable state) {
        if (state == null || !state.getClass().equals(SavedState.class)) { super.onRestoreInstanceState(state); return; }
        SavedState myState = (SavedState) state;
        super.onRestoreInstanceState(myState.getSuperState());
        setText(myState.text);
    }
    private static class SavedState extends BaseSavedState {
        String text;
        public SavedState(Parcel source) { super(source); text = source.readString(); }
        public SavedState(Parcelable superState) { super(superState); }
        @Override public void writeToParcel(Parcel dest, int flags) { super.writeToParcel(dest, flags); dest.writeString(text); }
        public static final Parcelable.Creator<SavedState> CREATOR = new Parcelable.Creator<SavedState>() {
            public SavedState createFromParcel(Parcel in) { return new SavedState(in); }
            public SavedState[] newArray(int size) { return new SavedState[size]; }
        };
    }
}
