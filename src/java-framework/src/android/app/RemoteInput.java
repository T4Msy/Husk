package android.app;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.Intent;
import android.os.Bundle;

/** A free-form or choice answer collected for a notification action (WhatsApp's "Reply"), as the platform's: the results travel
 *  back in the action's intent as a ClipData holding a Bundle. */
public final class RemoteInput implements android.os.Parcelable {
    public static final String RESULTS_CLIP_LABEL = "android.remoteinput.results", EXTRA_RESULTS_DATA = "android.remoteinput.resultsData";
    private static final String EXTRA_DATA_TYPE_RESULTS_DATA = "android.remoteinput.dataTypeResultsData", EXTRA_RESULTS_SOURCE = "android.remoteinput.resultsSource";
    public static android.os.Parcelable.Creator CREATOR;
    public static final int EDIT_CHOICES_BEFORE_SENDING_AUTO = 0;
    public static final int EDIT_CHOICES_BEFORE_SENDING_DISABLED = 1;
    public static final int EDIT_CHOICES_BEFORE_SENDING_ENABLED = 2;
    public static final int SOURCE_CHOICE = 1;
    public static int SOURCE_FREE_FORM_INPUT = 0;

    private final String mResultKey;
    private final CharSequence mLabel;
    private final CharSequence[] mChoices;
    private final boolean mAllowFreeForm;
    private final int mEditChoices;
    private final Bundle mExtras;
    private final java.util.Set<String> mDataTypes;

    private RemoteInput(Builder b) {
        mResultKey = b.mKey; mLabel = b.mLabel; mChoices = b.mChoices; mAllowFreeForm = b.mFree; mEditChoices = b.mEdit; mExtras = b.mExtras; mDataTypes = b.mTypes;
    }
    public String getResultKey() { return mResultKey; }
    public CharSequence getLabel() { return mLabel; }
    public CharSequence[] getChoices() { return mChoices; }
    public boolean getAllowFreeFormInput() { return mAllowFreeForm; }
    public java.util.Set getAllowedDataTypes() { return mDataTypes; }
    public boolean isDataOnly() { return !mAllowFreeForm && (mChoices == null || mChoices.length == 0) && !mDataTypes.isEmpty(); }
    public int getEditChoicesBeforeSending() { return mEditChoices; }
    public Bundle getExtras() { return mExtras; }
    public int describeContents() { return 0; }
    public void writeToParcel(android.os.Parcel d, int f) {}

    private static Intent clipIntent(Intent i) {
        ClipData c = i.getClipData();
        if (c == null) return null;
        ClipDescription d = c.getDescription();
        if (d == null || !d.hasMimeType(ClipDescription.MIMETYPE_TEXT_INTENT) || !RESULTS_CLIP_LABEL.equals(String.valueOf(d.getLabel()))) return null;
        return c.getItemCount() > 0 ? c.getItemAt(0).getIntent() : null;
    }
    public static Bundle getResultsFromIntent(Intent i) {
        Intent c = clipIntent(i);
        return c == null || c.getExtras() == null ? null : (Bundle) c.getExtras().getParcelable(EXTRA_RESULTS_DATA);
    }
    public static java.util.Map getDataResultsFromIntent(Intent i, String key) {
        Intent c = clipIntent(i);
        java.util.HashMap<String, android.net.Uri> r = new java.util.HashMap<>();
        Bundle e = c != null ? c.getExtras() : null;
        if (e == null) return null;
        for (String k : e.keySet()) if (k.startsWith(EXTRA_DATA_TYPE_RESULTS_DATA)) {
            Bundle b = e.getBundle(k);
            String uri = b != null ? b.getString(key) : null;
            if (uri != null) r.put(k.substring(EXTRA_DATA_TYPE_RESULTS_DATA.length()), android.net.Uri.parse(uri));
        }
        return r.isEmpty() ? null : r;
    }
    public static void addResultsToIntent(RemoteInput[] inputs, Intent i, Bundle results) {
        Intent c = clipIntent(i);
        if (c == null) c = new Intent();
        Bundle all = c.getExtras() != null ? (Bundle) c.getExtras().getParcelable(EXTRA_RESULTS_DATA) : null;
        if (all == null) all = new Bundle();
        for (RemoteInput r : inputs) {
            Object v = results.get(r.getResultKey());
            if (v instanceof CharSequence) all.putCharSequence(r.getResultKey(), (CharSequence) v);
        }
        c.putExtra(EXTRA_RESULTS_DATA, all);
        i.setClipData(ClipData.newIntent(RESULTS_CLIP_LABEL, c));
    }
    public static void addDataResultToIntent(RemoteInput r, Intent i, java.util.Map results) {
        Intent c = clipIntent(i);
        if (c == null) c = new Intent();
        for (Object o : results.entrySet()) {
            java.util.Map.Entry e = (java.util.Map.Entry) o;
            String k = EXTRA_DATA_TYPE_RESULTS_DATA + e.getKey();
            Bundle b = c.getBundleExtra(k);
            if (b == null) b = new Bundle();
            b.putString(r.getResultKey(), String.valueOf(e.getValue()));
            c.putExtra(k, b);
        }
        i.setClipData(ClipData.newIntent(RESULTS_CLIP_LABEL, c));
    }
    public static int getResultsSource(Intent i) { Intent c = clipIntent(i); return c == null ? SOURCE_FREE_FORM_INPUT : c.getIntExtra(EXTRA_RESULTS_SOURCE, SOURCE_FREE_FORM_INPUT); }
    public static void setResultsSource(Intent i, int source) {
        Intent c = clipIntent(i);
        if (c == null) c = new Intent();
        c.putExtra(EXTRA_RESULTS_SOURCE, source);
        i.setClipData(ClipData.newIntent(RESULTS_CLIP_LABEL, c));
    }

    public static final class Builder {
        private final String mKey;
        private CharSequence mLabel; private CharSequence[] mChoices; private boolean mFree = true; private int mEdit;
        private final Bundle mExtras = new Bundle();
        private final java.util.Set<String> mTypes = new java.util.HashSet<>();
        public Builder(String key) { if (key == null) throw new IllegalArgumentException("Result key can't be null"); mKey = key; }
        public Builder setLabel(CharSequence l) { mLabel = l; return this; }
        public Builder setChoices(CharSequence[] c) { mChoices = c; return this; }
        public Builder setAllowFreeFormInput(boolean b) { mFree = b; return this; }
        public Builder setAllowDataType(String mime, boolean allow) { if (allow) mTypes.add(mime); else mTypes.remove(mime); return this; }
        public Builder setEditChoicesBeforeSending(int e) { mEdit = e; return this; }
        public Builder addExtras(Bundle b) { if (b != null) mExtras.putAll(b); return this; }
        public Bundle getExtras() { return mExtras; }
        public RemoteInput build() { return new RemoteInput(this); }
    }
}
