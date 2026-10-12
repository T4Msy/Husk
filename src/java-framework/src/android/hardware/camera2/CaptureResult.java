package android.hardware.camera2;

/** What a capture turned out as: the request's settings echoed, with the state keys a converged 3A reports. */
public class CaptureResult extends CameraMetadata<CaptureResult.Key<?>> {
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
    public static final Key<Integer> CONTROL_AE_STATE = k("android.control.aeState", Integer.class);
    public static final Key<android.util.Range> CONTROL_AE_TARGET_FPS_RANGE = k("android.control.aeTargetFpsRange", android.util.Range.class);
    public static final Key<Integer> CONTROL_AF_MODE = k("android.control.afMode", Integer.class);
    public static final Key<android.hardware.camera2.params.MeteringRectangle[]> CONTROL_AF_REGIONS = k("android.control.afRegions", android.hardware.camera2.params.MeteringRectangle[].class);
    public static final Key<Integer> CONTROL_AF_SCENE_CHANGE = k("android.control.afSceneChange", Integer.class);
    public static final Key<Integer> CONTROL_AF_STATE = k("android.control.afState", Integer.class);
    public static final Key<Integer> CONTROL_AF_TRIGGER = k("android.control.afTrigger", Integer.class);
    public static final Key<Object> CONTROL_AUTOFRAMING = k("android.control.autoframing", Object.class);
    public static final Key<Object> CONTROL_AUTOFRAMING_STATE = k("android.control.autoframingState", Object.class);
    public static final Key<Boolean> CONTROL_AWB_LOCK = k("android.control.awbLock", Boolean.class);
    public static final Key<Integer> CONTROL_AWB_MODE = k("android.control.awbMode", Integer.class);
    public static final Key<android.hardware.camera2.params.MeteringRectangle[]> CONTROL_AWB_REGIONS = k("android.control.awbRegions", android.hardware.camera2.params.MeteringRectangle[].class);
    public static final Key<Integer> CONTROL_AWB_STATE = k("android.control.awbState", Integer.class);
    public static final Key<Integer> CONTROL_CAPTURE_INTENT = k("android.control.captureIntent", Integer.class);
    public static final Key<Integer> CONTROL_EFFECT_MODE = k("android.control.effectMode", Integer.class);
    public static final Key<Boolean> CONTROL_ENABLE_ZSL = k("android.control.enableZsl", Boolean.class);
    public static final Key<Integer> CONTROL_EXTENDED_SCENE_MODE = k("android.control.extendedSceneMode", Integer.class);
    public static final Key<Object> CONTROL_LOW_LIGHT_BOOST_STATE = k("android.control.lowLightBoostState", Object.class);
    public static final Key<Integer> CONTROL_MODE = k("android.control.mode", Integer.class);
    public static final Key<Integer> CONTROL_POST_RAW_SENSITIVITY_BOOST = k("android.control.postRawSensitivityBoost", Integer.class);
    public static final Key<Integer> CONTROL_SCENE_MODE = k("android.control.sceneMode", Integer.class);
    public static final Key<Integer> CONTROL_SETTINGS_OVERRIDE = k("android.control.settingsOverride", Integer.class);
    public static final Key<Integer> CONTROL_VIDEO_STABILIZATION_MODE = k("android.control.videoStabilizationMode", Integer.class);
    public static final Key<Object> CONTROL_ZOOM_METHOD = k("android.control.zoomMethod", Object.class);
    public static final Key<Float> CONTROL_ZOOM_RATIO = k("android.control.zoomRatio", Float.class);
    public static final Key<Integer> DISTORTION_CORRECTION_MODE = k("android.distortionCorrection.mode", Integer.class);
    public static final Key<Integer> EDGE_MODE = k("android.edge.mode", Integer.class);
    public static final Key<Object> EXTENSION_CURRENT_TYPE = k("android.extension.currentType", Object.class);
    public static final Key<Object> EXTENSION_NIGHT_MODE_INDICATOR = k("android.extension.nightModeIndicator", Object.class);
    public static final Key<Object> EXTENSION_STRENGTH = k("android.extension.strength", Object.class);
    public static final Key<Integer> FLASH_MODE = k("android.flash.mode", Integer.class);
    public static final Key<Integer> FLASH_STATE = k("android.flash.state", Integer.class);
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
    public static final Key<float[]> LENS_DISTORTION = k("android.lens.distortion", float[].class);
    public static final Key<Object> LENS_FILTER_DENSITY = k("android.lens.filterDensity", Object.class);
    public static final Key<Float> LENS_FOCAL_LENGTH = k("android.lens.focalLength", Float.class);
    public static final Key<Float> LENS_FOCUS_DISTANCE = k("android.lens.focusDistance", Float.class);
    public static final Key<Object> LENS_FOCUS_RANGE = k("android.lens.focusRange", Object.class);
    public static final Key<float[]> LENS_INTRINSIC_CALIBRATION = k("android.lens.intrinsicCalibration", float[].class);
    public static final Key<Integer> LENS_OPTICAL_STABILIZATION_MODE = k("android.lens.opticalStabilizationMode", Integer.class);
    public static final Key<float[]> LENS_POSE_ROTATION = k("android.lens.poseRotation", float[].class);
    public static final Key<float[]> LENS_POSE_TRANSLATION = k("android.lens.poseTranslation", float[].class);
    public static final Key<float[]> LENS_RADIAL_DISTORTION = k("android.lens.radialDistortion", float[].class);
    public static final Key<Integer> LENS_STATE = k("android.lens.state", Integer.class);
    public static final Key<String> LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID = k("android.logicalMultiCamera.activePhysicalId", String.class);
    public static final Key<Object> LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_SENSOR_CROP_REGION = k("android.logicalMultiCamera.activePhysicalSensorCropRegion", Object.class);
    public static final Key<Integer> NOISE_REDUCTION_MODE = k("android.noiseReduction.mode", Integer.class);
    public static final Key<Object> QUIRKS_PARTIAL_RESULT = k("android.quirks.partialResult", Object.class);
    public static final Key<Object> REPROCESS_EFFECTIVE_EXPOSURE_FACTOR = k("android.reprocess.effectiveExposureFactor", Object.class);
    public static final Key<Integer> REQUEST_FRAME_COUNT = k("android.request.frameCount", Integer.class);
    public static final Key<Integer> REQUEST_ID = k("android.request.id", Integer.class);
    public static final Key<Byte> REQUEST_PIPELINE_DEPTH = k("android.request.pipelineDepth", Byte.class);
    public static final Key<android.graphics.Rect> SCALER_CROP_REGION = k("android.scaler.cropRegion", android.graphics.Rect.class);
    public static final Key<Object> SCALER_RAW_CROP_REGION = k("android.scaler.rawCropRegion", Object.class);
    public static final Key<Integer> SCALER_ROTATE_AND_CROP = k("android.scaler.rotateAndCrop", Integer.class);
    public static final Key<Object> SENSOR_DYNAMIC_BLACK_LEVEL = k("android.sensor.dynamicBlackLevel", Object.class);
    public static final Key<Object> SENSOR_DYNAMIC_WHITE_LEVEL = k("android.sensor.dynamicWhiteLevel", Object.class);
    public static final Key<Long> SENSOR_EXPOSURE_TIME = k("android.sensor.exposureTime", Long.class);
    public static final Key<Long> SENSOR_FRAME_DURATION = k("android.sensor.frameDuration", Long.class);
    public static final Key<Object> SENSOR_GREEN_SPLIT = k("android.sensor.greenSplit", Object.class);
    public static final Key<Object> SENSOR_NEUTRAL_COLOR_POINT = k("android.sensor.neutralColorPoint", Object.class);
    public static final Key<Object> SENSOR_NOISE_PROFILE = k("android.sensor.noiseProfile", Object.class);
    public static final Key<Integer> SENSOR_PIXEL_MODE = k("android.sensor.pixelMode", Integer.class);
    public static final Key<Object> SENSOR_RAW_BINNING_FACTOR_USED = k("android.sensor.rawBinningFactorUsed", Object.class);
    public static final Key<Long> SENSOR_ROLLING_SHUTTER_SKEW = k("android.sensor.rollingShutterSkew", Long.class);
    public static final Key<Integer> SENSOR_SENSITIVITY = k("android.sensor.sensitivity", Integer.class);
    public static final Key<Object> SENSOR_TEST_PATTERN_DATA = k("android.sensor.testPatternData", Object.class);
    public static final Key<Integer> SENSOR_TEST_PATTERN_MODE = k("android.sensor.testPatternMode", Integer.class);
    public static final Key<Long> SENSOR_TIMESTAMP = k("android.sensor.timestamp", Long.class);
    public static final Key<Integer> SHADING_MODE = k("android.shading.mode", Integer.class);
    public static final Key<android.hardware.camera2.params.Face[]> STATISTICS_FACES = k("android.statistics.faces", android.hardware.camera2.params.Face[].class);
    public static final Key<Integer> STATISTICS_FACE_DETECT_MODE = k("android.statistics.faceDetectMode", Integer.class);
    public static final Key<Object> STATISTICS_FACE_IDS = k("android.statistics.faceIds", Object.class);
    public static final Key<Object> STATISTICS_FACE_LANDMARKS = k("android.statistics.faceLandmarks", Object.class);
    public static final Key<Object> STATISTICS_FACE_RECTANGLES = k("android.statistics.faceRectangles", Object.class);
    public static final Key<Object> STATISTICS_FACE_SCORES = k("android.statistics.faceScores", Object.class);
    public static final Key<Object> STATISTICS_HOT_PIXEL_MAP = k("android.statistics.hotPixelMap", Object.class);
    public static final Key<Boolean> STATISTICS_HOT_PIXEL_MAP_MODE = k("android.statistics.hotPixelMapMode", Boolean.class);
    public static final Key<Object> STATISTICS_LENS_INTRINSICS_SAMPLES = k("android.statistics.lensIntrinsicsSamples", Object.class);
    public static final Key<Object> STATISTICS_LENS_INTRINSIC_SAMPLES = k("android.statistics.lensIntrinsicSamples", Object.class);
    public static final Key<Object> STATISTICS_LENS_INTRINSIC_TIMESTAMPS = k("android.statistics.lensIntrinsicTimestamps", Object.class);
    public static final Key<Object> STATISTICS_LENS_SHADING_CORRECTION_MAP = k("android.statistics.lensShadingCorrectionMap", Object.class);
    public static final Key<Object> STATISTICS_LENS_SHADING_MAP = k("android.statistics.lensShadingMap", Object.class);
    public static final Key<Integer> STATISTICS_LENS_SHADING_MAP_MODE = k("android.statistics.lensShadingMapMode", Integer.class);
    public static final Key<Object> STATISTICS_OIS_DATA_MODE = k("android.statistics.oisDataMode", Object.class);
    public static final Key<Object> STATISTICS_OIS_SAMPLES = k("android.statistics.oisSamples", Object.class);
    public static final Key<Object> STATISTICS_OIS_TIMESTAMPS = k("android.statistics.oisTimestamps", Object.class);
    public static final Key<Object> STATISTICS_OIS_X_SHIFTS = k("android.statistics.oisXShifts", Object.class);
    public static final Key<Object> STATISTICS_OIS_Y_SHIFTS = k("android.statistics.oisYShifts", Object.class);
    public static final Key<Object> STATISTICS_PREDICTED_COLOR_GAINS = k("android.statistics.predictedColorGains", Object.class);
    public static final Key<Object> STATISTICS_PREDICTED_COLOR_TRANSFORM = k("android.statistics.predictedColorTransform", Object.class);
    public static final Key<Integer> STATISTICS_SCENE_FLICKER = k("android.statistics.sceneFlicker", Integer.class);
    public static final Key<Long> SYNC_FRAME_NUMBER = k("android.sync.frameNumber", Long.class);
    public static final Key<Object> TONEMAP_CURVE = k("android.tonemap.curve", Object.class);
    public static final Key<Object> TONEMAP_CURVE_BLUE = k("android.tonemap.curveBlue", Object.class);
    public static final Key<Object> TONEMAP_CURVE_GREEN = k("android.tonemap.curveGreen", Object.class);
    public static final Key<Object> TONEMAP_CURVE_RED = k("android.tonemap.curveRed", Object.class);
    public static final Key<Object> TONEMAP_GAMMA = k("android.tonemap.gamma", Object.class);
    public static final Key<Integer> TONEMAP_MODE = k("android.tonemap.mode", Integer.class);
    public static final Key<Object> TONEMAP_PRESET_CURVE = k("android.tonemap.presetCurve", Object.class);

    static java.util.List<Key<?>> huskAvailableKeys() { return new java.util.ArrayList<Key<?>>(sKeys.values()); }
    private final CaptureRequest mRequest;
    private final int mSequenceId;
    private final long mFrameNumber;
    public CaptureResult(android.hardware.camera2.impl.CameraMetadataNative results, int sequenceId) { mRequest = null; mSequenceId = sequenceId; mFrameNumber = 0; }
    public CaptureResult(String cameraId, android.hardware.camera2.impl.CameraMetadataNative results, CaptureRequest parent, int sequenceId, long frameNumber) { mRequest = parent; mSequenceId = sequenceId; mFrameNumber = frameNumber; }
    CaptureResult(CaptureRequest request, int sequenceId, long frameNumber) {
        mRequest = request; mSequenceId = sequenceId; mFrameNumber = frameNumber;
        if (request != null) huskValues.putAll(request.huskValues);
    }
    @SuppressWarnings("unchecked")
    public <T> T get(Key<T> key) { if (key == null) throw new NullPointerException("key"); return (T) huskValues.get(key.getName()); }
    @Override protected Class<Key<?>> getKeyClass() { Class<?> c = Key.class; return (Class<Key<?>>) c; }
    @Override protected <T> T getProtected(Key<?> key) { return (T) huskValues.get(key.getName()); }
    @Override public java.util.List<Key<?>> getKeys() { java.util.ArrayList<Key<?>> r = new java.util.ArrayList<>(); for (String n : huskValues.keySet()) r.add(huskKey(n)); return r; }
    public CaptureRequest getRequest() { return mRequest; }
    public int getSequenceId() { return mSequenceId; }
    public long getFrameNumber() { return mFrameNumber; }
    public String getCameraId() { return mRequest != null ? mRequest.getLogicalCameraId() : null; }
    public android.hardware.camera2.impl.CameraMetadataNative getNativeCopy() { return null; }
    public android.hardware.camera2.impl.CameraMetadataNative getNativeMetadata() { return null; }
    public <T> void huskSet(Key<T> key, T value) { huskValues.put(key.getName(), value); }
}
