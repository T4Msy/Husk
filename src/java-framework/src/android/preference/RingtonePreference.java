package android.preference;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.TypedArray;
import android.net.Uri;
import android.text.TextUtils;
import android.util.AttributeSet;
import java.util.ArrayList;

/**
 * A preference holding a ringtone's URI (persisted as its string; "" for silent), as AOSP's. Android picks it with the system's
 * ringtone picker activity; Husk has no system sounds to list, so the click offers what the picker would besides them: the
 * default sound for the type and silent. A result from a picker activity (onActivityResult) is still taken as Android takes it.
 */
@SuppressWarnings({"unchecked", "rawtypes", "deprecation"})
public class RingtonePreference extends Preference implements PreferenceManager.OnActivityResultListener {
    /* RingtoneManager's constants (its types and picker extras) */
    private static final int TYPE_RINGTONE = 1, TYPE_NOTIFICATION = 2, TYPE_ALARM = 4;
    private static final String ACTION_RINGTONE_PICKER = "android.intent.action.RINGTONE_PICKER", EXTRA_RINGTONE_EXISTING_URI = "android.intent.extra.ringtone.EXISTING_URI",
        EXTRA_RINGTONE_SHOW_DEFAULT = "android.intent.extra.ringtone.SHOW_DEFAULT", EXTRA_RINGTONE_DEFAULT_URI = "android.intent.extra.ringtone.DEFAULT_URI",
        EXTRA_RINGTONE_SHOW_SILENT = "android.intent.extra.ringtone.SHOW_SILENT", EXTRA_RINGTONE_TYPE = "android.intent.extra.ringtone.TYPE",
        EXTRA_RINGTONE_TITLE = "android.intent.extra.ringtone.TITLE", EXTRA_RINGTONE_PICKED_URI = "android.intent.extra.ringtone.PICKED_URI";
    private int mRingtoneType;
    private boolean mShowDefault, mShowSilent;
    private int mRequestCode;

    public RingtonePreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        if (context == null) { mRingtoneType = TYPE_RINGTONE; mShowDefault = mShowSilent = true; return; }
        final TypedArray a = context.obtainStyledAttributes(attrs, new int[] { android.R.attr.ringtoneType, android.R.attr.showDefault, android.R.attr.showSilent }, defStyleAttr, defStyleRes);
        mRingtoneType = a.getInt(0, TYPE_RINGTONE);
        mShowDefault = a.getBoolean(1, true);
        mShowSilent = a.getBoolean(2, true);
        a.recycle();
    }
    public RingtonePreference(Context context, AttributeSet attrs, int defStyleAttr) { this(context, attrs, defStyleAttr, 0); }
    public RingtonePreference(Context context, AttributeSet attrs) { this(context, attrs, android.R.attr.ringtonePreferenceStyle); }
    public RingtonePreference(Context context) { this(context, null); }

    public int getRingtoneType() { return mRingtoneType; }
    public void setRingtoneType(int type) { mRingtoneType = type; }
    public boolean getShowDefault() { return mShowDefault; }
    public void setShowDefault(boolean showDefault) { mShowDefault = showDefault; }
    public boolean getShowSilent() { return mShowSilent; }
    public void setShowSilent(boolean showSilent) { mShowSilent = showSilent; }

    private static Uri defaultUri(int type) {
        /* Settings.System's DEFAULT_RINGTONE_URI / DEFAULT_NOTIFICATION_URI / DEFAULT_ALARM_ALERT_URI, as RingtoneManager.getDefaultUri */
        if ((type & TYPE_RINGTONE) != 0) return Uri.parse("content://settings/system/ringtone");
        if ((type & TYPE_NOTIFICATION) != 0) return Uri.parse("content://settings/system/notification_sound");
        if ((type & TYPE_ALARM) != 0) return Uri.parse("content://settings/system/alarm_alert");
        return null;
    }
    @Override public void onClick() {
        // the picker's intent is still prepared (apps fill it in onPrepareRingtonePickerIntent); its choices are offered in a dialog
        final Intent intent = new Intent(ACTION_RINGTONE_PICKER);
        onPrepareRingtonePickerIntent(intent);
        final ArrayList<CharSequence> labels = new ArrayList<CharSequence>();
        final ArrayList<Uri> uris = new ArrayList<Uri>();
        final Uri def = defaultUri(mRingtoneType);
        if (mShowDefault && def != null) { labels.add((mRingtoneType & TYPE_NOTIFICATION) != 0 ? "Default notification sound" : (mRingtoneType & TYPE_ALARM) != 0 ? "Default alarm sound" : "Default ringtone"); uris.add(def); }
        if (mShowSilent) { labels.add("None"); uris.add(null); }
        if (labels.isEmpty()) return;
        final Uri current = onRestoreRingtone();
        int checked = -1;
        for (int i = 0; i < uris.size(); i++) { Uri u = uris.get(i); if (u == null ? current == null : u.equals(current)) checked = i; }
        CharSequence title = getTitle();
        new AlertDialog.Builder(getContext())
            .setTitle(title)
            .setSingleChoiceItems(labels.toArray(new CharSequence[0]), checked, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int which) {
                    Uri uri = uris.get(which);
                    if (callChangeListener(uri != null ? uri.toString() : "")) onSaveRingtone(uri);
                    dialog.dismiss();
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }
    /** Fill the picker's intent (the current ringtone, whether to offer default and silent, the type and title). */
    protected void onPrepareRingtonePickerIntent(Intent ringtonePickerIntent) {
        ringtonePickerIntent.putExtra(EXTRA_RINGTONE_EXISTING_URI, onRestoreRingtone());
        ringtonePickerIntent.putExtra(EXTRA_RINGTONE_SHOW_DEFAULT, mShowDefault);
        if (mShowDefault) ringtonePickerIntent.putExtra(EXTRA_RINGTONE_DEFAULT_URI, defaultUri(getRingtoneType()));
        ringtonePickerIntent.putExtra(EXTRA_RINGTONE_SHOW_SILENT, mShowSilent);
        ringtonePickerIntent.putExtra(EXTRA_RINGTONE_TYPE, mRingtoneType);
        ringtonePickerIntent.putExtra(EXTRA_RINGTONE_TITLE, getTitle());
    }
    /** Persist the chosen ringtone (null: silent, stored as ""). */
    protected void onSaveRingtone(Uri ringtoneUri) { persistString(ringtoneUri != null ? ringtoneUri.toString() : ""); }
    protected Uri onRestoreRingtone() {
        final String uriString = getPersistedString(null);
        return !TextUtils.isEmpty(uriString) ? Uri.parse(uriString) : null;
    }
    @Override public Object onGetDefaultValue(TypedArray a, int index) { return a.getString(index); }
    @Override public void onSetInitialValue(boolean restorePersistedValue, Object defaultValueObj) {
        String defaultValue = (String) defaultValueObj;
        /* nothing is shown until the picker opens and there is no local state: only a default value needs persisting */
        if (restorePersistedValue) return;
        if (!TextUtils.isEmpty(defaultValue)) onSaveRingtone(Uri.parse(defaultValue));
    }
    @Override public void onAttachedToHierarchy(PreferenceManager preferenceManager) {
        super.onAttachedToHierarchy(preferenceManager);
        if (preferenceManager == null) return;
        preferenceManager.registerOnActivityResultListener(this);
        mRequestCode = preferenceManager.getNextRequestCode();
    }
    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == mRequestCode) {
            if (data != null) {
                Uri uri = data.getParcelableExtra(EXTRA_RINGTONE_PICKED_URI);
                if (callChangeListener(uri != null ? uri.toString() : "")) onSaveRingtone(uri);
            }
            return true;
        }
        return false;
    }
}
