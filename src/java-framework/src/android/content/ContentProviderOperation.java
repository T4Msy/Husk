package android.content;

import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/** One insert, update, delete or assert of a batch (ContentResolver.applyBatch), as the platform's: values and selection
 *  arguments may refer back to earlier operations' results. */
public class ContentProviderOperation {
    public static final int TYPE_INSERT = 1, TYPE_UPDATE = 2, TYPE_DELETE = 3, TYPE_ASSERT = 4, TYPE_CALL = 5;
    private final int mType;
    private final Uri mUri;
    private final String mMethod, mArg;
    private final ContentValues mValues, mValuesBackRefs;
    private final Bundle mExtras;
    private final String mSelection;
    private final String[] mSelectionArgs;
    private final java.util.Map<Integer, Integer> mSelectionArgsBackRefs;
    private final Integer mExpectedCount;
    private final boolean mYieldAllowed, mExceptionAllowed;

    public ContentProviderOperation() { this(new Builder(TYPE_INSERT, null)); }
    public ContentProviderOperation(ContentProviderOperation o, Uri uri) {
        mType = o.mType; mUri = uri; mMethod = o.mMethod; mArg = o.mArg; mValues = o.mValues; mValuesBackRefs = o.mValuesBackRefs; mExtras = o.mExtras;
        mSelection = o.mSelection; mSelectionArgs = o.mSelectionArgs; mSelectionArgsBackRefs = o.mSelectionArgsBackRefs; mExpectedCount = o.mExpectedCount;
        mYieldAllowed = o.mYieldAllowed; mExceptionAllowed = o.mExceptionAllowed;
    }
    private ContentProviderOperation(Builder b) {
        mType = b.mType; mUri = b.mUri; mMethod = b.mMethod; mArg = b.mArg; mValues = b.mValues; mValuesBackRefs = b.mValuesBackRefs; mExtras = b.mExtras;
        mSelection = b.mSelection; mSelectionArgs = b.mSelectionArgs; mSelectionArgsBackRefs = b.mSelectionArgsBackRefs; mExpectedCount = b.mExpectedCount;
        mYieldAllowed = b.mYieldAllowed; mExceptionAllowed = b.mExceptionAllowed;
    }
    public static Builder newInsert(Uri uri) { return new Builder(TYPE_INSERT, uri); }
    public static Builder newUpdate(Uri uri) { return new Builder(TYPE_UPDATE, uri); }
    public static Builder newDelete(Uri uri) { return new Builder(TYPE_DELETE, uri); }
    public static Builder newAssertQuery(Uri uri) { return new Builder(TYPE_ASSERT, uri); }
    public static Builder newCall(Uri uri, String method, String arg) { Builder b = new Builder(TYPE_CALL, uri); b.mMethod = method; b.mArg = arg; return b; }
    public static String typeToString(int t) {
        switch (t) { case TYPE_INSERT: return "insert"; case TYPE_UPDATE: return "update"; case TYPE_DELETE: return "delete"; case TYPE_ASSERT: return "assert"; case TYPE_CALL: return "call"; default: return Integer.toString(t); }
    }
    public Uri getUri() { return mUri; }
    public int getType() { return mType; }
    public boolean isYieldAllowed() { return mYieldAllowed; }
    public boolean isExceptionAllowed() { return mExceptionAllowed; }
    public boolean isInsert() { return mType == TYPE_INSERT; }
    public boolean isUpdate() { return mType == TYPE_UPDATE; }
    public boolean isDelete() { return mType == TYPE_DELETE; }
    public boolean isAssertQuery() { return mType == TYPE_ASSERT; }
    public boolean isCall() { return mType == TYPE_CALL; }
    public boolean isWriteOperation() { return mType == TYPE_INSERT || mType == TYPE_UPDATE || mType == TYPE_DELETE; }
    public boolean isReadOperation() { return mType == TYPE_ASSERT; }

    private static Object backRef(ContentProviderResult[] results, int index, int ref) {
        if (ref >= index) throw new ArrayIndexOutOfBoundsException("asked for back ref " + ref + " but there are only " + index + " back refs");
        ContentProviderResult r = results[ref];
        return r.uri != null ? (Object) ContentUris.parseId(r.uri) : r.count;
    }
    public ContentValues resolveValueBackReferences(ContentProviderResult[] results, int index) {
        if (mValuesBackRefs == null) return mValues;
        ContentValues v = mValues == null ? new ContentValues() : new ContentValues(mValues);
        for (String k : mValuesBackRefs.keySet()) {
            Object o = backRef(results, index, mValuesBackRefs.getAsInteger(k));
            if (o instanceof Long) v.put(k, (Long) o); else if (o instanceof Integer) v.put(k, (Integer) o); else v.putNull(k);
        }
        return v;
    }
    public String[] resolveSelectionArgsBackReferences(ContentProviderResult[] results, int index) {
        if (mSelectionArgsBackRefs == null || mSelectionArgs == null) return mSelectionArgs;
        String[] a = mSelectionArgs.clone();
        for (java.util.Map.Entry<Integer, Integer> e : mSelectionArgsBackRefs.entrySet()) a[e.getKey()] = String.valueOf(backRef(results, index, e.getValue()));
        return a;
    }
    public Bundle resolveExtrasBackReferences(ContentProviderResult[] results, int index) { return mExtras; }

    public ContentProviderResult apply(ContentProvider provider, ContentProviderResult[] results, int index) throws OperationApplicationException {
        ContentValues values = resolveValueBackReferences(results, index);
        String[] args = resolveSelectionArgsBackReferences(results, index);
        switch (mType) {
        case TYPE_INSERT: {
            Uri u = provider.insert(mUri, values);
            if (u == null && !mExceptionAllowed) throw new OperationApplicationException("insert failed");
            return new ContentProviderResult(u);
        }
        case TYPE_CALL: return new ContentProviderResult(provider.call(mMethod, mArg, mExtras));
        }
        int n;
        if (mType == TYPE_DELETE) n = provider.delete(mUri, mSelection, args);
        else if (mType == TYPE_UPDATE) n = provider.update(mUri, values, mSelection, args);
        else {
            String[] proj = values != null ? values.keySet().toArray(new String[0]) : null;
            Cursor c = provider.query(mUri, proj, mSelection, args, null);
            try {
                n = c != null ? c.getCount() : 0;
                if (proj != null && c != null) while (c.moveToNext()) for (int i = 0; i < proj.length; i++) {
                    String want = values.getAsString(proj[i]), got = c.getString(i);
                    if (!android.text.TextUtils.equals(want, got)) throw new OperationApplicationException("Found value " + got + " when expected " + want + " for column " + proj[i]);
                }
            } finally { if (c != null) c.close(); }
        }
        if (mExpectedCount != null && mExpectedCount != n) throw new OperationApplicationException("wrong number of rows: " + n);
        return new ContentProviderResult(n);
    }
    @Override public String toString() { return "ContentProviderOperation(type=" + typeToString(mType) + " uri=" + mUri + ")"; }

    public static class BackReference implements android.os.Parcelable {
        public static android.os.Parcelable.Creator CREATOR;
        private final int mIndex;
        public BackReference(android.os.Parcel p) { mIndex = p != null ? p.readInt() : 0; }
        public int describeContents() { return 0; }
        public Object resolve(ContentProviderResult[] results, int index) { return backRef(results, index, mIndex); }
        public void writeToParcel(android.os.Parcel p, int f) { p.writeInt(mIndex); }
    }

    public static class Builder {
        private final int mType;
        private final Uri mUri;
        private String mMethod, mArg, mSelection;
        private ContentValues mValues, mValuesBackRefs;
        private Bundle mExtras;
        private String[] mSelectionArgs;
        private java.util.Map<Integer, Integer> mSelectionArgsBackRefs;
        private Integer mExpectedCount;
        private boolean mYieldAllowed, mExceptionAllowed;
        protected Builder() { this(TYPE_INSERT, null); }
        private Builder(int type, Uri uri) { mType = type; mUri = uri; }
        public ContentProviderOperation build() {
            if (mType == TYPE_UPDATE && (mValues == null || mValues.size() == 0) && (mValuesBackRefs == null || mValuesBackRefs.size() == 0))
                throw new IllegalArgumentException("Empty values");
            return new ContentProviderOperation(this);
        }
        private ContentValues values() { if (mValues == null) mValues = new ContentValues(); return mValues; }
        public Builder withValues(ContentValues v) { if (v != null) values().putAll(v); return this; }
        public Builder withValue(String k, Object v) {
            ContentValues c = values();
            if (v == null) c.putNull(k);
            else if (v instanceof String) c.put(k, (String) v); else if (v instanceof Byte) c.put(k, (Byte) v); else if (v instanceof Short) c.put(k, (Short) v);
            else if (v instanceof Integer) c.put(k, (Integer) v); else if (v instanceof Long) c.put(k, (Long) v); else if (v instanceof Float) c.put(k, (Float) v);
            else if (v instanceof Double) c.put(k, (Double) v); else if (v instanceof Boolean) c.put(k, (Boolean) v); else if (v instanceof byte[]) c.put(k, (byte[]) v);
            else throw new IllegalArgumentException("bad value type: " + v.getClass().getName());
            return this;
        }
        public Builder withValueBackReferences(ContentValues b) { if (mValuesBackRefs == null) mValuesBackRefs = new ContentValues(); if (b != null) mValuesBackRefs.putAll(b); return this; }
        public Builder withValueBackReference(String k, int previous) { if (mValuesBackRefs == null) mValuesBackRefs = new ContentValues(); mValuesBackRefs.put(k, previous); return this; }
        public Builder withValueBackReference(String k, int previous, String col) { return withValueBackReference(k, previous); }
        public Builder withSelection(String sel, String[] args) { mSelection = sel; mSelectionArgs = args; return this; }
        public Builder withSelectionBackReference(int arg, int previous) { if (mSelectionArgsBackRefs == null) mSelectionArgsBackRefs = new java.util.HashMap<>(); mSelectionArgsBackRefs.put(arg, previous); return this; }
        public Builder withSelectionBackReference(int arg, int previous, String col) { return withSelectionBackReference(arg, previous); }
        public Builder withExtras(Bundle b) { if (mExtras == null) mExtras = new Bundle(); if (b != null) mExtras.putAll(b); return this; }
        public Builder withExtra(String k, Object v) { if (mExtras == null) mExtras = new Bundle(); mExtras.putString(k, v == null ? null : String.valueOf(v)); return this; }
        public Builder withExtraBackReference(String k, int previous) { return this; }
        public Builder withExtraBackReference(String k, int previous, String col) { return this; }
        public Builder withExpectedCount(int n) { mExpectedCount = n; return this; }
        public Builder withYieldAllowed(boolean y) { mYieldAllowed = y; return this; }
        public Builder withExceptionAllowed(boolean e) { mExceptionAllowed = e; return this; }
        public Builder withFailureAllowed(boolean e) { return withExceptionAllowed(e); }
    }
}
