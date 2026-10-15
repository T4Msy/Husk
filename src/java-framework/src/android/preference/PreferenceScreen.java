package android.preference;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.LinearLayout;
import android.widget.ListAdapter;
import android.widget.ListView;

/**
 * The root of a preference hierarchy, as AOSP's: bound to a ListView (the activity's or fragment's) through its root adapter. As
 * a child of another screen it is one row that, clicked, opens its own preferences in a full-window dialog.
 */
@SuppressWarnings({"unchecked", "rawtypes", "deprecation"})
public final class PreferenceScreen extends PreferenceGroup implements AdapterView.OnItemClickListener, DialogInterface.OnDismissListener {
    private ListAdapter mRootAdapter;
    private Dialog mDialog;
    private ListView mListView;
    private boolean mDialogFitsSystemWindows = false;

    public PreferenceScreen(Context context, AttributeSet attrs) { super(context, attrs, android.R.attr.preferenceScreenStyle); }

    /** The adapter for the list showing this screen: its preferences flattened (categories inline, nested screens as rows). */
    public ListAdapter getRootAdapter() { if (mRootAdapter == null) mRootAdapter = onCreateRootAdapter(); return mRootAdapter; }
    protected ListAdapter onCreateRootAdapter() { return new PreferenceGroupAdapter(this); }
    /** Show this screen in the list: its rows, its clicks; and the hierarchy is attached (dependencies resolve now). */
    public void bind(ListView listView) {
        listView.setOnItemClickListener(this);
        listView.setAdapter(getRootAdapter());
        onAttachedToActivity();
    }
    @Override public void onClick() {
        if (getIntent() != null || getFragment() != null || getPreferenceCount() == 0) return;
        showDialog(null);
    }
    private void showDialog(Bundle state) {
        Context context = getContext();
        if (mListView != null) mListView.setAdapter(null);
        mListView = PreferenceViews.list(context);
        bind(mListView);
        // as Android: a dialog in the activity's own (full-window) theme, titled with this screen's title
        final CharSequence title = getTitle();
        int theme = context.getThemeResId();
        if (theme == 0) theme = android.R.style.Theme_DeviceDefault_Light;
        Dialog dialog = mDialog = new Dialog(context, theme);
        View content = mListView;
        if (TextUtils.isEmpty(title)) dialog.getWindow().requestFeature(Window.FEATURE_NO_TITLE);
        else {
            dialog.setTitle(title);
            dialog.getWindow().getAttributes();   // reads the theme's window style (whether it has an action bar to show the title in)
            if (!dialog.getWindow().hasFeature(Window.FEATURE_ACTION_BAR)) {
                LinearLayout l = new LinearLayout(context);
                l.setOrientation(LinearLayout.VERTICAL);
                l.addView(PreferenceViews.titleBar(context, title), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                l.addView(mListView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
                content = l;
            }
        }
        if (mDialogFitsSystemWindows) content.setFitsSystemWindows(true);
        dialog.setContentView(content);
        dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        dialog.setOnDismissListener(this);
        if (state != null) dialog.onRestoreInstanceState(state);
        PreferenceManager pm = getPreferenceManager();
        if (pm != null) pm.addPreferencesScreen(dialog);
        dialog.show();
    }
    public void onDismiss(DialogInterface dialog) {
        mDialog = null;
        PreferenceManager pm = getPreferenceManager();
        if (pm != null) pm.removePreferencesScreen(dialog);
    }
    public Dialog getDialog() { return mDialog; }
    public void onItemClick(AdapterView parent, View view, int position, long id) {
        // the list's own header rows come before the adapter's
        if (parent instanceof ListView) position -= ((ListView) parent).getHeaderViewsCount();
        Object item = getRootAdapter().getItem(position);
        if (!(item instanceof Preference)) return;
        final Preference preference = (Preference) item;
        preference.performClick(this);
    }
    @Override public boolean isOnSameScreenAsChildren() { return false; }
    public void setDialogFitsSystemWindows(boolean fitsSystemWindows) { mDialogFitsSystemWindows = fitsSystemWindows; }

    @Override public Parcelable onSaveInstanceState() {
        final Parcelable superState = super.onSaveInstanceState();
        final Dialog dialog = mDialog;
        if (dialog == null || !dialog.isShowing()) return superState;
        final SavedState myState = new SavedState(superState);
        myState.isDialogShowing = true;
        myState.dialogBundle = dialog.onSaveInstanceState();
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
        SavedState(Parcel source) { super(source); isDialogShowing = source.readInt() == 1; dialogBundle = source.readBundle(); }
        SavedState(Parcelable superState) { super(superState); }
        @Override public void writeToParcel(Parcel dest, int flags) { super.writeToParcel(dest, flags); dest.writeInt(isDialogShowing ? 1 : 0); dest.writeBundle(dialogBundle); }
        public static final Parcelable.Creator<SavedState> CREATOR = new Parcelable.Creator<SavedState>() {
            public SavedState createFromParcel(Parcel in) { return new SavedState(in); }
            public SavedState[] newArray(int size) { return new SavedState[size]; }
        };
    }
}
