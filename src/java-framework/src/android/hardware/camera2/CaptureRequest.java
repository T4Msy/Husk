package android.hardware.camera2;

import android.view.Surface;

/**
 * Settings for one capture and the surfaces it goes to. Built from a template with the defaults a camera2 device fills in,
 * which Husk's session reads back (focus, flash, zoom, JPEG orientation and quality) when it runs the request.
 */
public final class CaptureRequest extends CameraMetadata<CaptureRequest.Key<?>> implements android.os.Parcelable {
    public static final int REQUEST_TYPE_COUNT = 3, REQUEST_TYPE_REGULAR = 0, REQUEST_TYPE_REPROCESS = 1, REQUEST_TYPE_ZSL_STILL = 2;
    /** A metadata key: equal to another by name, as the platform's (apps make their own for vendor keys). */
    public static final class Key<T> {
        private final String mName; private final Class<T> mType; private final long mVendorId;
        public Key(String name, Class<T> type) { this(name, type, Long.MIN_VALUE); }
        public Key(String name, Class<T> type, long vendorId) { if (name == null) throw new NullPointerException("key name"); mName = name; mType = type; mVendorId = vendorId; }
        public Key(String name, String fallbackName, Class<T> type) { this(name, type); }
        public Key(String name, android.hardware.camera2.utils.TypeReference typeReference) { this(name, (Class<T>) null); }
        public String getName() { return mName; }
        public long getVendorId() { return mVendorId; }
        public Class<T> huskType() { return mType; }
        @Override public boolean equals(Object o) { return o instanceof Key && ((Key<?>) o).mName.equals(mName); }
        @Override public int hashCode() { return mName.hashCode(); }
        @Override public String toString() { return "%s(%s)".replace("%s(", getClass().getSimpleName() + "(").replace("%s", mName); }
    }
    private static final java.util.LinkedHashMap<String, Key<?>> sKeys = new java.util.LinkedHashMap<>();
    private static <T> Key<T> k(String name, Class<T> type) { Key<T> key = new Key<>(name, type); sKeys.put(name, key); return key; }
    /** The platform's key of that name, or a new one. */
    public static Key<?> huskKey(String name) { Key<?> key = sKeys.get(name); return key != null ? key : new Key<>(name, Object.class); }
    public static final Key<Boolean> BLACK_LEVEL_LOCK = k("android.blackLevel.lock", Boolean.class);
    public static final Key<Integer> COLOR_CORRECTION_ABERRATION_MODE = k("android.colorCorrection.aberrationMode", Integer.class);
    public static final Key<Object> COLOR_CORRECTION_COLOR_TEMPERATURE = k("android.colorCorrection.colorTemperature", Object.class);
    public static final Key<Object> COLOR_CORRECTION_COLOR_TINT = k("android.colorCorrection.colorTint", Object.class);
    public static final Key<Object> COLOR_CORRECTION_GAINS = k("android.colorCorrection.gains", Object.class);
    public static final Key<Integer> COLOR_CORRECTION_MODE = k("android.colorCorrection.mode", Integer.class);
    public static final Key<Object> COLOR_CORRECTION_TRANSFORM = k("android.colorCorrection.transform", Object.class);
    public static final Key<Integer> CONTROL_AE_ANTIBANDING_MODE = k("android.control.aeAntibandingMode", Integer.class);
    public static final Key<Integer> CONTROL_AE_EXPOSURE_COMPENSATION = k("android.control.aeExposureCompensation", Integer.class);
    public static final Key<Boolean> CONTROL_AE_LOCK = k("android.control.aeLock", Boolean.class);
    public static final Key<Integer> CONTROL_AE_MODE = k("android.control.aeMode", Integer.class);
    public static final Key<Integer> CONTROL_AE_PRECAPTURE_TRIGGER = k("android.control.aePrecaptureTrigger", Integer.class);
    public static final Key<Object> CONTROL_AE_PRIORITY_MODE = k("android.control.aePriorityMode", Object.class);
    public static final Key<android.hardware.camera2.params.MeteringRectangle[]> CONTROL_AE_REGIONS = k("android.control.aeRegions", android.hardware.camera2.params.MeteringRectangle[].class);
    public static final Key<Object> CONTROL_AE_REGIONS_SET = k("android.control.aeRegionsSet", Object.class);
    public static final Key<android.util.Range> CONTROL_AE_TARGET_FPS_RANGE = k("android.control.aeTargetFpsRange", android.util.Range.class);
    public static final Key<Integer> CONTROL_AF_MODE = k("android.control.afMode", Integer.class);
    public static final Key<android.hardware.camera2.params.MeteringRectangle[]> CONTROL_AF_REGIONS = k("android.control.afRegions", android.hardware.camera2.params.MeteringRectangle[].class);
    public static final Key<Object> CONTROL_AF_REGIONS_SET = k("android.control.afRegionsSet", Object.class);
    public static final Key<Integer> CONTROL_AF_TRIGGER = k("android.control.afTrigger", Integer.class);
    public static final Key<Object> CONTROL_AUTOFRAMING = k("android.control.autoframing", Object.class);
    public static final Key<Boolean> CONTROL_AWB_LOCK = k("android.control.awbLock", Boolean.class);
    public static final Key<Integer> CONTROL_AWB_MODE = k("android.control.awbMode", Integer.class);
    public static final Key<android.hardware.camera2.params.MeteringRectangle[]> CONTROL_AWB_REGIONS = k("android.control.awbRegions", android.hardware.camera2.params.MeteringRectangle[].class);
    public static final Key<Object> CONTROL_AWB_REGIONS_SET = k("android.control.awbRegionsSet", Object.class);
    public static final Key<Integer> CONTROL_CAPTURE_INTENT = k("android.control.captureIntent", Integer.class);
    public static final Key<Integer> CONTROL_EFFECT_MODE = k("android.control.effectMode", Integer.class);
    public static final Key<Boolean> CONTROL_ENABLE_ZSL = k("android.control.enableZsl", Boolean.class);
    public static final Key<Integer> CONTROL_EXTENDED_SCENE_MODE = k("android.control.extendedSceneMode", Integer.class);
    public static final Key<Integer> CONTROL_MODE = k("android.control.mode", Integer.class);
    public static final Key<Integer> CONTROL_POST_RAW_SENSITIVITY_BOOST = k("android.control.postRawSensitivityBoost", Integer.class);
    public static final Key<Integer> CONTROL_SCENE_MODE = k("android.control.sceneMode", Integer.class);
    public static final Key<Integer> CONTROL_SETTINGS_OVERRIDE = k("android.control.settingsOverride", Integer.class);
    public static final Key<Integer> CONTROL_VIDEO_STABILIZATION_MODE = k("android.control.videoStabilizationMode", Integer.class);
    public static final Key<Object> CONTROL_ZOOM_METHOD = k("android.control.zoomMethod", Object.class);
    public static final Key<Float> CONTROL_ZOOM_RATIO = k("android.control.zoomRatio", Float.class);
    public static final Key<Integer> DISTORTION_CORRECTION_MODE = k("android.distortionCorrection.mode", Integer.class);
    public static final Key<Integer> EDGE_MODE = k("android.edge.mode", Integer.class);
    public static final Key<Object> EXTENSION_STRENGTH = k("android.extension.strength", Object.class);
    public static final Key<Integer> FLASH_MODE = k("android.flash.mode", Integer.class);
    public static final Key<Object> FLASH_STRENGTH_LEVEL = k("android.flash.strengthLevel", Object.class);
    public static final Key<Integer> HOT_PIXEL_MODE = k("android.hotPixel.mode", Integer.class);
    public static final Key<Object> JPEG_GPS_COORDINATES = k("android.jpeg.gpsCoordinates", Object.class);
    public static final Key<android.location.Location> JPEG_GPS_LOCATION = k("android.jpeg.gpsLocation", android.location.Location.class);
    public static final Key<Object> JPEG_GPS_PROCESSING_METHOD = k("android.jpeg.gpsProcessingMethod", Object.class);
    public static final Key<Object> JPEG_GPS_TIMESTAMP = k("android.jpeg.gpsTimestamp", Object.class);
    public static final Key<Integer> JPEG_ORIENTATION = k("android.jpeg.orientation", Integer.class);
    public static final Key<Byte> JPEG_QUALITY = k("android.jpeg.quality", Byte.class);
    public static final Key<Byte> JPEG_THUMBNAIL_QUALITY = k("android.jpeg.thumbnailQuality", Byte.class);
    public static final Key<android.util.Size> JPEG_THUMBNAIL_SIZE = k("android.jpeg.thumbnailSize", android.util.Size.class);
    public static final Key<Object> LED_TRANSMIT = k("android.led.transmit", Object.class);
    public static final Key<Float> LENS_APERTURE = k("android.lens.aperture", Float.class);
    public static final Key<Object> LENS_FILTER_DENSITY = k("android.lens.filterDensity", Object.class);
    public static final Key<Float> LENS_FOCAL_LENGTH = k("android.lens.focalLength", Float.class);
    public static final Key<Float> LENS_FOCUS_DISTANCE = k("android.lens.focusDistance", Float.class);
    public static final Key<Integer> LENS_OPTICAL_STABILIZATION_MODE = k("android.lens.opticalStabilizationMode", Integer.class);
    public static final Key<Integer> NOISE_REDUCTION_MODE = k("android.noiseReduction.mode", Integer.class);
    public static final Key<Object> REPROCESS_EFFECTIVE_EXPOSURE_FACTOR = k("android.reprocess.effectiveExposureFactor", Object.class);
    public static final Key<Integer> REQUEST_ID = k("android.request.id", Integer.class);
    public static final Key<android.graphics.Rect> SCALER_CROP_REGION = k("android.scaler.cropRegion", android.graphics.Rect.class);
    public static final Key<Object> SCALER_CROP_REGION_SET = k("android.scaler.cropRegionSet", Object.class);
    public static final Key<Integer> SCALER_ROTATE_AND_CROP = k("android.scaler.rotateAndCrop", Integer.class);
    public static final Key<Long> SENSOR_EXPOSURE_TIME = k("android.sensor.exposureTime", Long.class);
    public static final Key<Long> SENSOR_FRAME_DURATION = k("android.sensor.frameDuration", Long.class);
    public static final Key<Integer> SENSOR_PIXEL_MODE = k("android.sensor.pixelMode", Integer.class);
    public static final Key<Integer> SENSOR_SENSITIVITY = k("android.sensor.sensitivity", Integer.class);
    public static final Key<Object> SENSOR_TEST_PATTERN_DATA = k("android.sensor.testPatternData", Object.class);
    public static final Key<Integer> SENSOR_TEST_PATTERN_MODE = k("android.sensor.testPatternMode", Integer.class);
    public static final Key<Integer> SHADING_MODE = k("android.shading.mode", Integer.class);
    public static final Key<Integer> STATISTICS_FACE_DETECT_MODE = k("android.statistics.faceDetectMode", Integer.class);
    public static final Key<Boolean> STATISTICS_HOT_PIXEL_MAP_MODE = k("android.statistics.hotPixelMapMode", Boolean.class);
    public static final Key<Integer> STATISTICS_LENS_SHADING_MAP_MODE = k("android.statistics.lensShadingMapMode", Integer.class);
    public static final Key<Object> STATISTICS_OIS_DATA_MODE = k("android.statistics.oisDataMode", Object.class);
    public static final Key<Object> TONEMAP_CURVE = k("android.tonemap.curve", Object.class);
    public static final Key<Object> TONEMAP_CURVE_BLUE = k("android.tonemap.curveBlue", Object.class);
    public static final Key<Object> TONEMAP_CURVE_GREEN = k("android.tonemap.curveGreen", Object.class);
    public static final Key<Object> TONEMAP_CURVE_RED = k("android.tonemap.curveRed", Object.class);
    public static final Key<Object> TONEMAP_GAMMA = k("android.tonemap.gamma", Object.class);
    public static final Key<Integer> TONEMAP_MODE = k("android.tonemap.mode", Integer.class);
    public static final Key<Object> TONEMAP_PRESET_CURVE = k("android.tonemap.presetCurve", Object.class);

    static java.util.List<Key<?>> huskAvailableKeys() { return new java.util.ArrayList<Key<?>>(sKeys.values()); }
    final java.util.ArrayList<Surface> mTargets = new java.util.ArrayList<>();
    private Object mTag;
    private int mTemplate;
    private String mCameraId;
    CaptureRequest() {}
    CaptureRequest(CaptureRequest o) { huskValues.putAll(o.huskValues); mTargets.addAll(o.mTargets); mTag = o.mTag; mTemplate = o.mTemplate; mCameraId = o.mCameraId; }
    @SuppressWarnings("unchecked")
    public <T> T get(Key<T> key) { if (key == null) throw new NullPointerException("key"); return (T) huskValues.get(key.getName()); }
    @Override protected Class<Key<?>> getKeyClass() { Class<?> c = Key.class; return (Class<Key<?>>) c; }
    @Override protected <T> T getProtected(Key<?> key) { return (T) huskValues.get(key.getName()); }
    @Override public java.util.List<Key<?>> getKeys() { java.util.ArrayList<Key<?>> r = new java.util.ArrayList<>(); for (String n : huskValues.keySet()) r.add(huskKey(n)); return r; }
    public Object getTag() { return mTag; }
    public boolean containsTarget(Surface s) { return mTargets.contains(s); }
    public java.util.Collection<Surface> getTargets() { return java.util.Collections.unmodifiableList(mTargets); }
    public boolean isReprocess() { return false; }
    public boolean isPartOfCRequestList() { return false; }
    public int getReprocessableSessionId() { return -1; }
    public int getRequestType() { return REQUEST_TYPE_REGULAR; }
    public String getLogicalCameraId() { return mCameraId; }
    public int huskTemplate() { return mTemplate; }
    public int describeContents() { return 0; }
    public void writeToParcel(android.os.Parcel p, int flags) {}
    public static final Creator<CaptureRequest> CREATOR = new Creator<CaptureRequest>() { public CaptureRequest createFromParcel(android.os.Parcel p) { return new CaptureRequest(); } public CaptureRequest[] newArray(int n) { return new CaptureRequest[n]; } };
    @Override public boolean equals(Object o) { return o == this; }
    @Override public int hashCode() { return System.identityHashCode(this); }

    /** A device's template with its defaults; the camera's active array is the default crop. */
    static CaptureRequest huskTemplate(int template, CameraCharacteristics c) {
        CaptureRequest r = new CaptureRequest();
        r.mTemplate = template; r.mCameraId = c.huskId();
        boolean front = c.huskFacing() == 1;
        int intent = template == CameraDevice.TEMPLATE_STILL_CAPTURE ? CONTROL_CAPTURE_INTENT_STILL_CAPTURE : template == CameraDevice.TEMPLATE_RECORD ? CONTROL_CAPTURE_INTENT_VIDEO_RECORD
            : template == CameraDevice.TEMPLATE_VIDEO_SNAPSHOT ? CONTROL_CAPTURE_INTENT_VIDEO_SNAPSHOT : template == CameraDevice.TEMPLATE_ZERO_SHUTTER_LAG ? CONTROL_CAPTURE_INTENT_ZERO_SHUTTER_LAG
            : template == CameraDevice.TEMPLATE_MANUAL ? CONTROL_CAPTURE_INTENT_MANUAL : CONTROL_CAPTURE_INTENT_PREVIEW;
        r.set(CONTROL_CAPTURE_INTENT, intent);
        r.set(CONTROL_MODE, template == CameraDevice.TEMPLATE_MANUAL ? CONTROL_MODE_OFF : CONTROL_MODE_AUTO);
        r.set(CONTROL_AF_MODE, front ? CONTROL_AF_MODE_OFF : template == CameraDevice.TEMPLATE_RECORD || template == CameraDevice.TEMPLATE_VIDEO_SNAPSHOT ? CONTROL_AF_MODE_CONTINUOUS_VIDEO : CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        r.set(CONTROL_AE_MODE, CONTROL_AE_MODE_ON); r.set(CONTROL_AWB_MODE, CONTROL_AWB_MODE_AUTO);
        r.set(CONTROL_AE_LOCK, false); r.set(CONTROL_AWB_LOCK, false); r.set(CONTROL_AE_EXPOSURE_COMPENSATION, 0);
        r.set(CONTROL_AE_ANTIBANDING_MODE, CONTROL_AE_ANTIBANDING_MODE_AUTO); r.set(CONTROL_AE_PRECAPTURE_TRIGGER, CONTROL_AE_PRECAPTURE_TRIGGER_IDLE);
        r.set(CONTROL_AF_TRIGGER, CONTROL_AF_TRIGGER_IDLE); r.set(CONTROL_SCENE_MODE, CONTROL_SCENE_MODE_DISABLED); r.set(CONTROL_EFFECT_MODE, CONTROL_EFFECT_MODE_OFF);
        r.set(CONTROL_VIDEO_STABILIZATION_MODE, CONTROL_VIDEO_STABILIZATION_MODE_OFF);
        r.set(CONTROL_AE_TARGET_FPS_RANGE, new android.util.Range<Integer>(template == CameraDevice.TEMPLATE_RECORD ? 30 : 15, 30));
        r.set(CONTROL_ZOOM_RATIO, 1f); r.set(SCALER_CROP_REGION, new android.graphics.Rect(c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)));
        r.set(FLASH_MODE, FLASH_MODE_OFF); r.set(JPEG_QUALITY, (byte) 95); r.set(JPEG_THUMBNAIL_QUALITY, (byte) 85); r.set(JPEG_ORIENTATION, 0);
        r.set(JPEG_THUMBNAIL_SIZE, new android.util.Size(320, 240));
        r.set(LENS_FOCUS_DISTANCE, 0f); r.set(LENS_FOCAL_LENGTH, c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)[0]);
        r.set(LENS_APERTURE, c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)[0]); r.set(LENS_OPTICAL_STABILIZATION_MODE, 0);
        boolean quality = template == CameraDevice.TEMPLATE_STILL_CAPTURE;
        r.set(NOISE_REDUCTION_MODE, quality ? NOISE_REDUCTION_MODE_HIGH_QUALITY : NOISE_REDUCTION_MODE_FAST); r.set(EDGE_MODE, quality ? EDGE_MODE_HIGH_QUALITY : EDGE_MODE_FAST);
        r.set(COLOR_CORRECTION_ABERRATION_MODE, quality ? 2 : 1); r.set(COLOR_CORRECTION_MODE, 1); r.set(TONEMAP_MODE, quality ? TONEMAP_MODE_HIGH_QUALITY : TONEMAP_MODE_FAST);
        r.set(SHADING_MODE, 1); r.set(HOT_PIXEL_MODE, 1); r.set(STATISTICS_FACE_DETECT_MODE, STATISTICS_FACE_DETECT_MODE_OFF); r.set(STATISTICS_LENS_SHADING_MAP_MODE, 0);
        r.set(STATISTICS_HOT_PIXEL_MAP_MODE, false); r.set(BLACK_LEVEL_LOCK, false); r.set(SENSOR_TEST_PATTERN_MODE, 0); r.set(DISTORTION_CORRECTION_MODE, 0);
        r.set(SENSOR_EXPOSURE_TIME, 16666666L); r.set(SENSOR_FRAME_DURATION, 33333333L); r.set(SENSOR_SENSITIVITY, 100);
        return r;
    }
    private <T> void set(Key<T> key, T value) { huskValues.put(key.getName(), value); }

    public static final class Builder {
        private final CaptureRequest mRequest;
        Builder(CaptureRequest template) { mRequest = template; }
        public Builder(android.hardware.camera2.impl.CameraMetadataNative template, boolean reprocess, int sessionId, String id, java.util.Set<String> physicalIds) { mRequest = new CaptureRequest(); }
        public void addTarget(Surface s) { if (s == null) throw new IllegalArgumentException("Surface must not be null"); if (!mRequest.mTargets.contains(s)) mRequest.mTargets.add(s); }
        public void removeTarget(Surface s) { mRequest.mTargets.remove(s); }
        public <T> void set(Key<T> key, T value) { if (key == null) throw new NullPointerException("key"); mRequest.huskPut(key.getName(), value); }
        public <T> T get(Key<T> key) { return mRequest.get(key); }
        public <T> Builder setPhysicalCameraKey(Key<T> key, T value, String id) { set(key, value); return this; }
        public <T> T getPhysicalCameraKey(Key<T> key, String id) { return get(key); }
        public void setTag(Object tag) { mRequest.mTag = tag; }
        public void setPartOfCHSRequestList(boolean b) {}
        public boolean isEmpty() { return mRequest.huskValues.isEmpty(); }
        public CaptureRequest build() { return new CaptureRequest(mRequest); }
    }
}
