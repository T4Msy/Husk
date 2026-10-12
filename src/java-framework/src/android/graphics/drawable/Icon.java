package android.graphics.drawable;
import android.graphics.Bitmap;
public final class Icon {
    private Bitmap mBitmap; private int mRes; private String mPkg; private int mType;
    private byte[] mData; private int mDataOff, mDataLen; private String mUri;
    private android.content.res.ColorStateList mTint; private android.graphics.PorterDuff.Mode mTintMode;
    private Icon() {}
    private static Icon of(int type) { Icon i = new Icon(); i.mType = type; return i; }
    public static Icon createWithBitmap(Bitmap b) { if (b == null) throw new IllegalArgumentException("Bitmap must not be null."); Icon i = of(TYPE_BITMAP); i.mBitmap = b; return i; }
    public static Icon createWithAdaptiveBitmap(Bitmap b) { Icon i = createWithBitmap(b); i.mType = TYPE_ADAPTIVE_BITMAP; return i; }
    public static Icon createWithResource(android.content.Context c, int res) { Icon i = of(TYPE_RESOURCE); i.mRes = res; i.mPkg = c != null ? c.getPackageName() : null; return i; }
    public static Icon createWithResource(String pkg, int res) { if (pkg == null) throw new IllegalArgumentException("Resource package name must not be null."); Icon i = of(TYPE_RESOURCE); i.mRes = res; i.mPkg = pkg; return i; }
    public static Icon createWithData(byte[] data, int off, int len) { if (data == null) throw new IllegalArgumentException("Data must not be null."); Icon i = of(TYPE_DATA); i.mData = data; i.mDataOff = off; i.mDataLen = len; return i; }
    public static Icon createWithContentUri(String uri) { if (uri == null) throw new IllegalArgumentException("Uri must not be null."); Icon i = of(TYPE_URI); i.mUri = uri; return i; }
    public static Icon createWithContentUri(android.net.Uri uri) { if (uri == null) throw new IllegalArgumentException("Uri must not be null."); return createWithContentUri(uri.toString()); }
    public static Icon createWithAdaptiveBitmapContentUri(String uri) { Icon i = createWithContentUri(uri); i.mType = TYPE_URI_ADAPTIVE_BITMAP; return i; }
    public static Icon createWithAdaptiveBitmapContentUri(android.net.Uri uri) { return createWithAdaptiveBitmapContentUri(uri.toString()); }
    public static Icon createWithFilePath(String path) { if (path == null) throw new IllegalArgumentException("Path must not be null."); Icon i = of(TYPE_URI); i.mUri = path; return i; }
    public static Icon createWithResource(android.content.res.Resources r, int res) { Icon i = of(TYPE_RESOURCE); i.mRes = res; return i; }
    public static Icon createFromStream(java.io.InputStream in) { Bitmap b = android.graphics.BitmapFactory.decodeStream(in); return b != null ? createWithBitmap(b) : null; }
    public Drawable loadDrawable(android.content.Context c) {
        Drawable d = null;
        try {
            switch (mType) {
            case TYPE_BITMAP: case TYPE_ADAPTIVE_BITMAP: d = new BitmapDrawable(c.getResources(), mBitmap); break;
            case TYPE_RESOURCE: d = mRes != 0 ? c.getDrawable(mRes) : null; break;
            case TYPE_DATA: { Bitmap b = android.graphics.BitmapFactory.decodeByteArray(mData, mDataOff, mDataLen); d = b != null ? new BitmapDrawable(c.getResources(), b) : null; break; }
            case TYPE_URI: case TYPE_URI_ADAPTIVE_BITMAP: {
                android.net.Uri u = android.net.Uri.parse(mUri);
                java.io.InputStream in = "content".equals(u.getScheme()) || "android.resource".equals(u.getScheme()) ? c.getContentResolver().openInputStream(u)
                                       : new java.io.FileInputStream("file".equals(u.getScheme()) ? u.getPath() : mUri);
                try { Bitmap b = android.graphics.BitmapFactory.decodeStream(in); d = b != null ? new BitmapDrawable(c.getResources(), b) : null; } finally { in.close(); }
                break;
            }
            }
        } catch (Exception e) { return null; }
        if (d != null && mTint != null) { d = d.mutate(); d.setTintList(mTint); if (mTintMode != null) d.setTintMode(mTintMode); }
        return d;
    }
    public Icon setTint(int t) { return setTintList(android.content.res.ColorStateList.valueOf(t)); }
    public Icon setTintList(android.content.res.ColorStateList t) { mTint = t; return this; }
    public Icon setTintMode(android.graphics.PorterDuff.Mode m) { mTintMode = m; return this; }
    public int getResId() { if (mType != TYPE_RESOURCE) throw new IllegalStateException("called getResId() on " + this); return mRes; }
    public String getResPackage() { if (mType != TYPE_RESOURCE) throw new IllegalStateException("called getResPackage() on " + this); return mPkg; }
    public int getType() { return mType; }
    public android.net.Uri getUri() { if (mType != TYPE_URI && mType != TYPE_URI_ADAPTIVE_BITMAP) throw new IllegalStateException("called getUri() on " + this); return android.net.Uri.parse(mUri); }
    public String getUriString() { return mUri; }
    public Bitmap getBitmap() { return mBitmap; }
    public byte[] getDataBytes() { return mData; } public int getDataLength() { return mDataLen; } public int getDataOffset() { return mDataOff; }
    public android.content.res.ColorStateList getTintList() { return mTint; }
    public boolean hasTint() { return mTint != null; }
    @Override public String toString() { return "Icon(typ=" + mType + (mType == TYPE_RESOURCE ? " pkg=" + mPkg + " id=0x" + Integer.toHexString(mRes) : mUri != null ? " uri=" + mUri : "") + ")"; }
    // ---- generated by tools/compat/fillmembers.py: the platform's members this class does not write (signatures only)
    private final java.util.HashMap<String, Object> huskFill = new java.util.HashMap<>();
    public static android.os.Parcelable.Creator CREATOR;
    public static final int MIN_ASHMEM_ICON_SIZE = 131072;
    public static final int TYPE_ADAPTIVE_BITMAP = 5;
    public static final int TYPE_BITMAP = 1;
    public static final int TYPE_DATA = 3;
    public static final int TYPE_RESOURCE = 2;
    public static final int TYPE_URI = 4;
    public static final int TYPE_URI_ADAPTIVE_BITMAP = 6;
    public static android.graphics.drawable.Icon createWithResourceAdaptiveDrawable(java.lang.String p0, int p1, boolean p2, float p3) { return null; }
    public static android.graphics.Bitmap scaleDownIfNecessary(android.graphics.Bitmap p0, int p1, int p2) { return null; }
    public void convertToAshmem() {}
    public int describeContents() { return 0; }
    public android.content.res.Resources getResources() { return null; }
    public android.graphics.BlendMode getTintBlendMode() { return (android.graphics.BlendMode) huskFill.get("TintBlendMode"); }
    public android.graphics.drawable.Drawable loadDrawableAsUser(android.content.Context p0, int p1) { return null; }
    public void loadDrawableAsync(android.content.Context p0, android.graphics.drawable.Icon.OnDrawableLoadedListener p1, android.os.Handler p2) {}
    public void loadDrawableAsync(android.content.Context p0, android.os.Message p1) {}
    public boolean sameAs(android.graphics.drawable.Icon p0) { return false; }
    public void scaleDownIfNecessary(int p0, int p1) {}
    public android.graphics.drawable.Icon setTintBlendMode(android.graphics.BlendMode p0) { return this; }
    public void writeToParcel(android.os.Parcel p0, int p1) {}
    public void writeToStream(java.io.OutputStream p0) {}
    // ---- end of generated members
    // ---- generated by tools/compat/genstubs.py: the platform's nested classes this class does not write
    public interface OnDrawableLoadedListener {
        void onDrawableLoaded(android.graphics.drawable.Drawable p0);
    }
    // ---- end of generated nested classes
}
