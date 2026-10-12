package android.view.inputmethod;

/** Rich content a keyboard commits: its content URI, description and optional link. */
public final class InputContentInfo implements android.os.Parcelable {
    private final android.net.Uri mContentUri, mLinkUri;
    private final android.content.ClipDescription mDescription;
    public InputContentInfo(android.net.Uri contentUri, android.content.ClipDescription description) { this(contentUri, description, null); }
    public InputContentInfo(android.net.Uri contentUri, android.content.ClipDescription description, android.net.Uri linkUri) {
        if (contentUri == null) throw new NullPointerException("contentUri");
        if (description == null) throw new NullPointerException("description");
        if (!"content".equals(contentUri.getScheme())) throw new IllegalArgumentException("contentUri must have content scheme");
        if (linkUri != null && !"http".equals(linkUri.getScheme()) && !"https".equals(linkUri.getScheme())) throw new IllegalArgumentException("linkUri must have either http or https scheme");
        mContentUri = contentUri; mDescription = description; mLinkUri = linkUri;
    }
    public android.net.Uri getContentUri() { return mContentUri; }
    public android.content.ClipDescription getDescription() { return mDescription; }
    public android.net.Uri getLinkUri() { return mLinkUri; }
    public boolean validate() { return true; }
    public void requestPermission() {}
    public void releasePermission() {}
    public int describeContents() { return 0; }
    public void writeToParcel(android.os.Parcel p, int flags) {
        p.writeString(mContentUri.toString()); p.writeCharSequence(mDescription.getLabel());
        String[] types = new String[mDescription.getMimeTypeCount()]; for (int i = 0; i < types.length; i++) types[i] = mDescription.getMimeType(i);
        p.writeStringArray(types); p.writeString(mLinkUri == null ? null : mLinkUri.toString());
    }
    public static final Creator<InputContentInfo> CREATOR = new Creator<InputContentInfo>() {
        public InputContentInfo createFromParcel(android.os.Parcel p) {
            android.net.Uri c = android.net.Uri.parse(p.readString()); CharSequence label = p.readCharSequence(); String[] types = p.createStringArray(); String link = p.readString();
            return new InputContentInfo(c, new android.content.ClipDescription(label, types), link == null ? null : android.net.Uri.parse(link));
        }
        public InputContentInfo[] newArray(int n) { return new InputContentInfo[n]; }
    };
}
