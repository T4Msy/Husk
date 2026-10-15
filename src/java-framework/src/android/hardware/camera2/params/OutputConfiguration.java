package android.hardware.camera2.params;

import android.util.Size;
import android.view.Surface;

/** One output of a capture session: its surface (or a deferred size and surface class, until addSurface). */
public final class OutputConfiguration implements android.os.Parcelable {
    public static final int MIRROR_MODE_AUTO = 0, MIRROR_MODE_H = 2, MIRROR_MODE_NONE = 1, MIRROR_MODE_V = 3;
    public static final int ROTATION_0 = 0, ROTATION_90 = 1, ROTATION_180 = 2, ROTATION_270 = 3;
    public static final int SURFACE_GROUP_ID_NONE = -1;
    public static final int TIMESTAMP_BASE_DEFAULT = 0, TIMESTAMP_BASE_SENSOR = 1, TIMESTAMP_BASE_MONOTONIC = 2, TIMESTAMP_BASE_REALTIME = 3, TIMESTAMP_BASE_CHOREOGRAPHER_SYNCED = 4, TIMESTAMP_BASE_READOUT_SENSOR = 5;
    private final java.util.ArrayList<Surface> mSurfaces = new java.util.ArrayList<>();
    private final int mGroupId, mRotation;
    private final Size mSize;
    private final Class<?> mClass;
    private boolean mShared;
    private String mPhysicalId;
    private long mDynamicRangeProfile = DynamicRangeProfiles.STANDARD, mStreamUseCase;
    private int mMirrorMode, mTimestampBase, mColorSpace = -1, mFormat;
    private boolean mReadoutTimestamp;
    public OutputConfiguration(Surface surface) { this(SURFACE_GROUP_ID_NONE, surface, ROTATION_0); }
    public OutputConfiguration(int groupId, Surface surface) { this(groupId, surface, ROTATION_0); }
    public OutputConfiguration(Surface surface, int rotation) { this(SURFACE_GROUP_ID_NONE, surface, rotation); }
    public OutputConfiguration(int groupId, Surface surface, int rotation) {
        if (surface == null) throw new NullPointerException("surface");
        mSurfaces.add(surface); mGroupId = groupId; mRotation = rotation; mSize = null; mClass = null;
    }
    public <T> OutputConfiguration(Size size, Class<T> klass) { this(SURFACE_GROUP_ID_NONE, size, klass); }
    public <T> OutputConfiguration(int groupId, Size size, Class<T> klass) { mGroupId = groupId; mRotation = ROTATION_0; mSize = size; mClass = klass; }
    public OutputConfiguration(int format, Size size) { this(SURFACE_GROUP_ID_NONE, format, size); }
    public OutputConfiguration(int groupId, int format, Size size) { mGroupId = groupId; mRotation = ROTATION_0; mSize = size; mClass = null; mFormat = format; }
    public OutputConfiguration(int format, Size size, long usage) { this(format, size); }
    public OutputConfiguration(int groupId, int format, Size size, long usage) { this(groupId, format, size); }
    public OutputConfiguration(OutputConfiguration o) {
        mSurfaces.addAll(o.mSurfaces); mGroupId = o.mGroupId; mRotation = o.mRotation; mSize = o.mSize; mClass = o.mClass; mShared = o.mShared; mPhysicalId = o.mPhysicalId;
        mDynamicRangeProfile = o.mDynamicRangeProfile; mStreamUseCase = o.mStreamUseCase; mMirrorMode = o.mMirrorMode; mTimestampBase = o.mTimestampBase; mFormat = o.mFormat;
    }
    public void enableSurfaceSharing() { mShared = true; }
    public boolean isShared() { return mShared; }
    public void setPhysicalCameraId(String id) { mPhysicalId = id; }
    public String getPhysicalCameraId() { return mPhysicalId; }
    public void addSensorPixelModeUsed(int mode) {}
    public void removeSensorPixelModeUsed(int mode) {}
    public void addSurface(Surface surface) {
        if (surface == null) throw new NullPointerException("surface");
        if (mSurfaces.contains(surface)) throw new IllegalStateException("Surface is already added!");
        if (!mSurfaces.isEmpty() && !mShared) throw new IllegalStateException("Cannot have 2 surfaces for a non-sharing configuration");
        mSurfaces.add(surface);
    }
    public void removeSurface(Surface surface) { if (getSurface() == surface) throw new IllegalArgumentException("Cannot remove surface associated with this output configuration"); if (!mSurfaces.remove(surface)) throw new IllegalArgumentException("Surface is not part of this output configuration"); }
    public Surface getSurface() { return mSurfaces.isEmpty() ? null : mSurfaces.get(0); }
    public java.util.List<Surface> getSurfaces() { return java.util.Collections.unmodifiableList(mSurfaces); }
    public int getRotation() { return mRotation; }
    public int getSurfaceGroupId() { return mGroupId; }
    public int getMaxSharedSurfaceCount() { return 4; }
    public void setDynamicRangeProfile(long profile) { mDynamicRangeProfile = profile; }
    public long getDynamicRangeProfile() { return mDynamicRangeProfile; }
    public void setStreamUseCase(long useCase) { mStreamUseCase = useCase; }
    public long getStreamUseCase() { return mStreamUseCase; }
    public void setMirrorMode(int mode) { mMirrorMode = mode; }
    public int getMirrorMode() { return mMirrorMode; }
    public void setTimestampBase(int base) { mTimestampBase = base; }
    public int getTimestampBase() { return mTimestampBase; }
    public void setReadoutTimestampEnabled(boolean on) { mReadoutTimestamp = on; }
    public boolean isReadoutTimestampEnabled() { return mReadoutTimestamp; }
    public void setColorSpace(android.graphics.ColorSpace.Named cs) { mColorSpace = cs == null ? -1 : cs.ordinal(); }
    public void clearColorSpace() { mColorSpace = -1; }
    public android.graphics.ColorSpace getColorSpace() { return null; }
    public int getConfiguredFormat() { return mFormat; }
    public Size getConfiguredSize() { return mSize; }
    public boolean isDeferredConfiguration() { return mSurfaces.isEmpty(); }
    public boolean isForPhysicalCamera() { return mPhysicalId != null; }
    public Class<?> huskSurfaceClass() { return mClass; }
    public int describeContents() { return 0; }
    public void writeToParcel(android.os.Parcel p, int flags) {}
    public static final Creator<OutputConfiguration> CREATOR = new Creator<OutputConfiguration>() { public OutputConfiguration createFromParcel(android.os.Parcel p) { return new OutputConfiguration(new Size(0, 0), android.graphics.SurfaceTexture.class); } public OutputConfiguration[] newArray(int n) { return new OutputConfiguration[n]; } };
    @Override public boolean equals(Object o) { return o instanceof OutputConfiguration && ((OutputConfiguration) o).mSurfaces.equals(mSurfaces) && ((OutputConfiguration) o).mGroupId == mGroupId && ((OutputConfiguration) o).mRotation == mRotation; }
    @Override public int hashCode() { return mSurfaces.hashCode() * 31 + mGroupId; }
}
