package android.preference;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * A preference whose click opens an AlertDialog, as AOSP's: title, message or the app's view, OK / Cancel. Subclasses shape it
 * in onPrepareDialogBuilder / onCreateDialogView / onBindDialogView and take the result in onDialogClosed(positiveResult).
 */
@SuppressWarnings({"unchecked", "rawtypes", "deprecation"})
public abstract class DialogPreference extends Preference implements DialogInterface.OnClickListener, DialogInterface.OnDismissListener, PreferenceManager.OnActivityDestroyListener {
    private static final int[] ATTRS = { android.R.attr.dialogTitle, android.R.attr.dialogMessage, android.R.attr.dialogIcon,
        android.R.attr.positiveButtonText, android.R.attr.negativeButtonText, android.R.attr.dialogLayout };
    private AlertDialog.Builder mBuilder;
    private CharSequence mDialogTitle, mDialogMessage;
    private Drawable mDialogIcon;
    private CharSequence mPositiveButtonText, mNegativeButtonText;
    private int mDialogLayoutResId;
    private Dialog mDialog;
    /** Which button closed the dialog (dismissed any other way: negative). */
    private int mWhichButtonClicked;

    public DialogPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        if (context == null) return;
        final TypedArray a = context.obtainStyledAttributes(attrs, ATTRS, defStyleAttr, defStyleRes);
        mDialogTitle = a.getString(0);
        if (mDialogTitle == null) mDialogTitle = getTitle();
        mDialogMessage = a.getString(1);
        mDialogIcon = a.getDrawable(2);
        /* the DialogPreference style gives OK / Cancel; without it in the theme, the same */
        mPositiveButtonText = a.hasValue(3) ? a.getString(3) : text(context, android.R.string.ok, "OK");
        mNegativeButtonText = a.hasValue(4) ? a.getString(4) : text(context, android.R.string.cancel, "Cancel");
        mDialogLayoutResId = PreferenceViews.appRes(a.getResourceId(5, mDialogLayoutResId));
        a.recycle();
    }
    public DialogPreference(Context context, AttributeSet attrs, int defStyleAttr) { this(context, attrs, defStyleAttr, 0); }
    public DialogPreference(Context context, AttributeSet attrs) { this(context, attrs, android.R.attr.dialogPreferenceStyle); }
    public DialogPreference(Context context) { this(context, null); }
    private static CharSequence text(Context c, int id, String def) { try { CharSequence t = c.getText(id); return t != null ? t : def; } catch (RuntimeException e) { return def; } }

    public void setDialogTitle(CharSequence dialogTitle) { mDialogTitle = dialogTitle; }
    public void setDialogTitle(int dialogTitleResId) { setDialogTitle(getContext().getString(dialogTitleResId)); }
    public CharSequence getDialogTitle() { return mDialogTitle; }
    public void setDialogMessage(CharSequence dialogMessage) { mDialogMessage = dialogMessage; }
    public void setDialogMessage(int dialogMessageResId) { setDialogMessage(getContext().getString(dialogMessageResId)); }
    public CharSequence getDialogMessage() { return mDialogMessage; }
    public void setDialogIcon(Drawable dialogIcon) { mDialogIcon = dialogIcon; }
    public void setDialogIcon(int dialogIconRes) { mDialogIcon = dialogIconRes == 0 ? null : getContext().getDrawable(dialogIconRes); }
    public Drawable getDialogIcon() { return mDialogIcon; }
    public void setPositiveButtonText(CharSequence positiveButtonText) { mPositiveButtonText = positiveButtonText; }
    public void setPositiveButtonText(int positiveButtonTextResId) { setPositiveButtonText(getContext().getString(positiveButtonTextResId)); }
    public CharSequence getPositiveButtonText() { return mPositiveButtonText; }
    public void setNegativeButtonText(CharSequence negativeButtonText) { mNegativeButtonText = negativeButtonText; }
    public void setNegativeButtonText(int negativeButtonTextResId) { setNegativeButtonText(getContext().getString(negativeButtonTextResId)); }
    public CharSequence getNegativeButtonText() { return mNegativeButtonText; }
    public void setDialogLayoutResource(int dialogLayoutResId) { mDialogLayoutResId = dialogLayoutResId; }
    public int getDialogLayoutResource() { return mDialogLayoutResId; }

    /** Shape the dialog before it is made (ListPreference puts its items here). */
    protected void onPrepareDialogBuilder(AlertDialog.Builder builder) {}
    @Override public void onClick() {
        if (mDialog != null && mDialog.isShowing()) return;
        showDialog(null);
    }
    protected void showDialog(Bundle state) {
        Context context = getContext();
        mWhichButtonClicked = DialogInterface.BUTTON_NEGATIVE;
        mBuilder = new AlertDialog.Builder(context)
            .setTitle(mDialogTitle)
            .setIcon(mDialogIcon)
            .setPositiveButton(mPositiveButtonText, this)
            .setNegativeButton(mNegativeButtonText, this);
        View contentView = onCreateDialogView();
        if (contentView != null) { onBindDialogView(contentView); mBuilder.setView(contentView); }
        else mBuilder.setMessage(mDialogMessage);
        onPrepareDialogBuilder(mBuilder);
        PreferenceManager pm = getPreferenceManager();
        if (pm != null) pm.registerOnActivityDestroyListener(this);
        final Dialog dialog = mDialog = mBuilder.create();
        if (state != null) dialog.onRestoreInstanceState(state);
        if (needInputMethod()) requestInputMethod(dialog);
        dialog.setOnDismissListener(this);
        dialog.show();
    }
    /** Whether the dialog wants the keyboard up when shown (an EditTextPreference's). */
    boolean needInputMethod() { return false; }
    private void requestInputMethod(Dialog dialog) { Window window = dialog.getWindow(); window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE); }
    /** The dialog's view: the dialog layout when there is one (else the message is shown). */
    protected View onCreateDialogView() {
        if (mDialogLayoutResId == 0) return huskDefaultDialogView(mBuilder != null ? mBuilder.getContext() : getContext());
        LayoutInflater inflater = LayoutInflater.from(mBuilder != null ? mBuilder.getContext() : getContext());
        return inflater.inflate(mDialogLayoutResId, null);
    }
    /** Husk: Android's default dialog layout for this type (none; EditTextPreference's preference_dialog_edittext). */
    View huskDefaultDialogView(Context c) { return null; }
    /** Puts the dialog message in the view's android:id/message, hidden when there is none. */
    protected void onBindDialogView(View view) {
        View dialogMessageView = view.findViewById(android.R.id.message);
        if (dialogMessageView != null) {
            final CharSequence message = getDialogMessage();
            int newVisibility = View.GONE;
            if (!TextUtils.isEmpty(message)) {
                if (dialogMessageView instanceof TextView) ((TextView) dialogMessageView).setText(message);
                newVisibility = View.VISIBLE;
            }
            if (dialogMessageView.getVisibility() != newVisibility) dialogMessageView.setVisibility(newVisibility);
        }
    }
    public void onClick(DialogInterface dialog, int which) { mWhichButtonClicked = which; }
    public void onDismiss(DialogInterface dialog) {
        PreferenceManager pm = getPreferenceManager();
        if (pm != null) pm.unregisterOnActivityDestroyListener(this);
        mDialog = null;
        onDialogClosed(mWhichButtonClicked == DialogInterface.BUTTON_POSITIVE);
    }
    /** The dialog closed: positiveResult when by its positive button (or a list item that stands for it). */
    protected void onDialogClosed(boolean positiveResult) {}
    public Dialog getDialog() { return mDialog; }
    public void onActivityDestroy() { if (mDialog == null || !mDialog.isShowing()) return; mDialog.dismiss(); }

    @Override public Parcelable onSaveInstanceState() {
        final Parcelable superState = super.onSaveInstanceState();
        if (mDialog == null || !mDialog.isShowing()) return superState;
        final SavedState myState = new SavedState(superState);
        myState.isDialogShowing = true;
        myState.dialogBundle = mDialog.onSaveInstanceState();
        return myState;
    }
    @Override public void onRestoreInstanceState(Parcelable state) {
        if (state == null || !state.getClass().equals(SavedState.class)) { super.onRestoreInstanceState(state); return; }
        SavedState myState = (SavedState) state;
        super.onRestoreInstanceState(myState.getSuperState());
        if (myState.isDialogShowing) showDialog(myState.dialogBundle);
    }
    private static class SavedState extends BaseSavedState {
        boolean isDialogShowing;
        Bundle dialogBundle;
        public SavedState(Parcel source) { super(source); isDialogShowing = source.readInt() == 1; dialogBundle = source.readBundle(); }
        public SavedState(Parcelable superState) { super(superState); }
        @Override public void writeToParcel(Parcel dest, int flags) { super.writeToParcel(dest, flags); dest.writeInt(isDialogShowing ? 1 : 0); dest.writeBundle(dialogBundle); }
        public static final Parcelable.Creator<SavedState> CREATOR = new Parcelable.Creator<SavedState>() {
            public SavedState createFromParcel(Parcel in) { return new SavedState(in); }
            public SavedState[] newArray(int size) { return new SavedState[size]; }
        };
    }
}
