package android.hardware.camera2;

import android.graphics.Rect;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.util.Range;
import android.util.Rational;
import android.util.Size;
import android.util.SizeF;

/**
 * What a camera can do. Husk's cameras are the iPhone's, described as a LIMITED camera2 device: the back camera's buffers need
 * a 90-degree turn to be upright and the front one's 270 (husk-tl-dvm-camera.c delivers them so), a 4:3 sensor, streams up to
 * 1080p for preview/analysis and 4032x3024 for JPEG, continuous and one-shot autofocus, a torch on the back camera, zoom.
 */
public final class CameraCharacteristics extends CameraMetadata<CameraCharacteristics.Key<?>> {
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
    public static final Key<Object> AUTOMOTIVE_LENS_FACING = k("android.automotive.lens.facing", Object.class);
    public static final Key<Object> AUTOMOTIVE_LOCATION = k("android.automotive.location", Object.class);
    public static final Key<int[]> COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES = k("android.colorCorrection.availableAberrationModes", int[].class);
    public static final Key<Object> COLOR_CORRECTION_AVAILABLE_MODES = k("android.colorCorrection.availableModes", Object.class);
    public static final Key<Object> COLOR_CORRECTION_COLOR_TEMPERATURE_RANGE = k("android.colorCorrection.colorTemperatureRange", Object.class);
    public static final Key<int[]> CONTROL_AE_AVAILABLE_ANTIBANDING_MODES = k("android.control.aeAvailableAntibandingModes", int[].class);
    public static final Key<int[]> CONTROL_AE_AVAILABLE_MODES = k("android.control.aeAvailableModes", int[].class);
    public static final Key<Object> CONTROL_AE_AVAILABLE_PRIORITY_MODES = k("android.control.aeAvailablePriorityModes", Object.class);
    public static final Key<android.util.Range[]> CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES = k("android.control.aeAvailableTargetFpsRanges", android.util.Range[].class);
    public static final Key<android.util.Range> CONTROL_AE_COMPENSATION_RANGE = k("android.control.aeCompensationRange", android.util.Range.class);
    public static final Key<android.util.Rational> CONTROL_AE_COMPENSATION_STEP = k("android.control.aeCompensationStep", android.util.Rational.class);
    public static final Key<Boolean> CONTROL_AE_LOCK_AVAILABLE = k("android.control.aeLockAvailable", Boolean.class);
    public static final Key<int[]> CONTROL_AF_AVAILABLE_MODES = k("android.control.afAvailableModes", int[].class);
    public static final Key<Object> CONTROL_AUTOFRAMING_AVAILABLE = k("android.control.autoframingAvailable", Object.class);
    public static final Key<int[]> CONTROL_AVAILABLE_EFFECTS = k("android.control.availableEffects", int[].class);
    public static final Key<Object> CONTROL_AVAILABLE_EXTENDED_SCENE_MODE_CAPABILITIES = k("android.control.availableExtendedSceneModeCapabilities", Object.class);
    public static final Key<Object> CONTROL_AVAILABLE_EXTENDED_SCENE_MODE_MAX_SIZES = k("android.control.availableExtendedSceneModeMaxSizes", Object.class);
    public static final Key<Object> CONTROL_AVAILABLE_EXTENDED_SCENE_MODE_ZOOM_RATIO_RANGES = k("android.control.availableExtendedSceneModeZoomRatioRanges", Object.class);
    public static final Key<Object> CONTROL_AVAILABLE_HIGH_SPEED_VIDEO_CONFIGURATIONS = k("android.control.availableHighSpeedVideoConfigurations", Object.class);
    public static final Key<Object> CONTROL_AVAILABLE_HIGH_SPEED_VIDEO_CONFIGURATIONS_MAXIMUM_RESOLUTION = k("android.control.availableHighSpeedVideoConfigurationsMaximumResolution", Object.class);
    public static final Key<int[]> CONTROL_AVAILABLE_MODES = k("android.control.availableModes", int[].class);
    public static final Key<int[]> CONTROL_AVAILABLE_SCENE_MODES = k("android.control.availableSceneModes", int[].class);
    public static final Key<Object> CONTROL_AVAILABLE_SETTINGS_OVERRIDES = k("android.control.availableSettingsOverrides", Object.class);
    public static final Key<int[]> CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES = k("android.control.availableVideoStabilizationModes", int[].class);
    public static final Key<int[]> CONTROL_AWB_AVAILABLE_MODES = k("android.control.awbAvailableModes", int[].class);
    public static final Key<Boolean> CONTROL_AWB_LOCK_AVAILABLE = k("android.control.awbLockAvailable", Boolean.class);
    public static final Key<Object> CONTROL_LOW_LIGHT_BOOST_INFO_LUMINANCE_RANGE = k("android.control.lowLightBoostInfoLuminanceRange", Object.class);
    public static final Key<int[]> CONTROL_MAX_REGIONS = k("android.control.maxRegions", int[].class);
    public static final Key<Integer> CONTROL_MAX_REGIONS_AE = k("android.control.maxRegionsAe", Integer.class);
    public static final Key<Integer> CONTROL_MAX_REGIONS_AF = k("android.control.maxRegionsAf", Integer.class);
    public static final Key<Integer> CONTROL_MAX_REGIONS_AWB = k("android.control.maxRegionsAwb", Integer.class);
    public static final Key<android.util.Range> CONTROL_POST_RAW_SENSITIVITY_BOOST_RANGE = k("android.control.postRawSensitivityBoostRange", android.util.Range.class);
    public static final Key<android.util.Range> CONTROL_ZOOM_RATIO_RANGE = k("android.control.zoomRatioRange", android.util.Range.class);
    public static final Key<Object> DEPTH_AVAILABLE_DEPTH_MIN_FRAME_DURATIONS = k("android.depth.availableDepthMinFrameDurations", Object.class);
    public static final Key<Object> DEPTH_AVAILABLE_DEPTH_MIN_FRAME_DURATIONS_MAXIMUM_RESOLUTION = k("android.depth.availableDepthMinFrameDurationsMaximumResolution", Object.class);
    public static final Key<Object> DEPTH_AVAILABLE_DEPTH_STALL_DURATIONS = k("android.depth.availableDepthStallDurations", Object.class);
    public static final Key<Object> DEPTH_AVAILABLE_DEPTH_STALL_DURATIONS_MAXIMUM_RESOLUTION = k("android.depth.availableDepthStallDurationsMaximumResolution", Object.class);
    public static final Key<Object> DEPTH_AVAILABLE_DEPTH_STREAM_CONFIGURATIONS = k("android.depth.availableDepthStreamConfigurations", Object.class);
    public static final Key<Object> DEPTH_AVAILABLE_DEPTH_STREAM_CONFIGURATIONS_MAXIMUM_RESOLUTION = k("android.depth.availableDepthStreamConfigurationsMaximumResolution", Object.class);
    public static final Key<Object> DEPTH_AVAILABLE_DYNAMIC_DEPTH_MIN_FRAME_DURATIONS = k("android.depth.availableDynamicDepthMinFrameDurations", Object.class);
    public static final Key<Object> DEPTH_AVAILABLE_DYNAMIC_DEPTH_MIN_FRAME_DURATIONS_MAXIMUM_RESOLUTION = k("android.depth.availableDynamicDepthMinFrameDurationsMaximumResolution", Object.class);
    public static final Key<Object> DEPTH_AVAILABLE_DYNAMIC_DEPTH_STALL_DURATIONS = k("android.depth.availableDynamicDepthStallDurations", Object.class);
    public static final Key<Object> DEPTH_AVAILABLE_DYNAMIC_DEPTH_STALL_DURATIONS_MAXIMUM_RESOLUTION = k("android.depth.availableDynamicDepthStallDurationsMaximumResolution", Object.class);
    public static final Key<Object> DEPTH_AVAILABLE_DYNAMIC_DEPTH_STREAM_CONFIGURATIONS = k("android.depth.availableDynamicDepthStreamConfigurations", Object.class);
    public static final Key<Object> DEPTH_AVAILABLE_DYNAMIC_DEPTH_STREAM_CONFIGURATIONS_MAXIMUM_RESOLUTION = k("android.depth.availableDynamicDepthStreamConfigurationsMaximumResolution", Object.class);
    public static final Key<Object> DEPTH_AVAILABLE_RECOMMENDED_DEPTH_STREAM_CONFIGURATIONS = k("android.depth.availableRecommendedDepthStreamConfigurations", Object.class);
    public static final Key<Boolean> DEPTH_DEPTH_IS_EXCLUSIVE = k("android.depth.depthIsExclusive", Boolean.class);
    public static final Key<int[]> DISTORTION_CORRECTION_AVAILABLE_MODES = k("android.distortionCorrection.availableModes", int[].class);
    public static final Key<int[]> EDGE_AVAILABLE_EDGE_MODES = k("android.edge.availableEdgeModes", int[].class);
    public static final Key<Boolean> FLASH_INFO_AVAILABLE = k("android.flash.info.available", Boolean.class);
    public static final Key<Object> FLASH_INFO_STRENGTH_DEFAULT_LEVEL = k("android.flash.info.strengthDefaultLevel", Object.class);
    public static final Key<Object> FLASH_INFO_STRENGTH_MAXIMUM_LEVEL = k("android.flash.info.strengthMaximumLevel", Object.class);
    public static final Key<Object> FLASH_SINGLE_STRENGTH_DEFAULT_LEVEL = k("android.flash.singleStrengthDefaultLevel", Object.class);
    public static final Key<Object> FLASH_SINGLE_STRENGTH_MAX_LEVEL = k("android.flash.singleStrengthMaxLevel", Object.class);
    public static final Key<Object> FLASH_TORCH_STRENGTH_DEFAULT_LEVEL = k("android.flash.torchStrengthDefaultLevel", Object.class);
    public static final Key<Object> FLASH_TORCH_STRENGTH_MAX_LEVEL = k("android.flash.torchStrengthMaxLevel", Object.class);
    public static final Key<Object> HEIC_AVAILABLE_HEIC_MIN_FRAME_DURATIONS = k("android.heic.availableHeicMinFrameDurations", Object.class);
    public static final Key<Object> HEIC_AVAILABLE_HEIC_MIN_FRAME_DURATIONS_MAXIMUM_RESOLUTION = k("android.heic.availableHeicMinFrameDurationsMaximumResolution", Object.class);
    public static final Key<Object> HEIC_AVAILABLE_HEIC_STALL_DURATIONS = k("android.heic.availableHeicStallDurations", Object.class);
    public static final Key<Object> HEIC_AVAILABLE_HEIC_STALL_DURATIONS_MAXIMUM_RESOLUTION = k("android.heic.availableHeicStallDurationsMaximumResolution", Object.class);
    public static final Key<Object> HEIC_AVAILABLE_HEIC_STREAM_CONFIGURATIONS = k("android.heic.availableHeicStreamConfigurations", Object.class);
    public static final Key<Object> HEIC_AVAILABLE_HEIC_STREAM_CONFIGURATIONS_MAXIMUM_RESOLUTION = k("android.heic.availableHeicStreamConfigurationsMaximumResolution", Object.class);
    public static final Key<Object> HEIC_AVAILABLE_HEIC_ULTRA_HDR_MIN_FRAME_DURATIONS = k("android.heic.availableHeicUltraHdrMinFrameDurations", Object.class);
    public static final Key<Object> HEIC_AVAILABLE_HEIC_ULTRA_HDR_MIN_FRAME_DURATIONS_MAXIMUM_RESOLUTION = k("android.heic.availableHeicUltraHdrMinFrameDurationsMaximumResolution", Object.class);
    public static final Key<Object> HEIC_AVAILABLE_HEIC_ULTRA_HDR_STALL_DURATIONS = k("android.heic.availableHeicUltraHdrStallDurations", Object.class);
    public static final Key<Object> HEIC_AVAILABLE_HEIC_ULTRA_HDR_STALL_DURATIONS_MAXIMUM_RESOLUTION = k("android.heic.availableHeicUltraHdrStallDurationsMaximumResolution", Object.class);
    public static final Key<Object> HEIC_AVAILABLE_HEIC_ULTRA_HDR_STREAM_CONFIGURATIONS = k("android.heic.availableHeicUltraHdrStreamConfigurations", Object.class);
    public static final Key<Object> HEIC_AVAILABLE_HEIC_ULTRA_HDR_STREAM_CONFIGURATIONS_MAXIMUM_RESOLUTION = k("android.heic.availableHeicUltraHdrStreamConfigurationsMaximumResolution", Object.class);
    public static final Key<int[]> HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES = k("android.hotPixel.availableHotPixelModes", int[].class);
    public static final Key<Object> INFO_DEVICE_ID = k("android.info.deviceId", Object.class);
    public static final Key<Object> INFO_DEVICE_STATE_ORIENTATIONS = k("android.info.deviceStateOrientations", Object.class);
    public static final Key<android.hardware.camera2.params.DeviceStateSensorOrientationMap> INFO_DEVICE_STATE_SENSOR_ORIENTATION_MAP = k("android.info.deviceStateSensorOrientationMap", android.hardware.camera2.params.DeviceStateSensorOrientationMap.class);
    public static final Key<Object> INFO_SESSION_CONFIGURATION_QUERY_VERSION = k("android.info.sessionConfigurationQueryVersion", Object.class);
    public static final Key<Integer> INFO_SUPPORTED_HARDWARE_LEVEL = k("android.info.supportedHardwareLevel", Integer.class);
    public static final Key<String> INFO_VERSION = k("android.info.version", String.class);
    public static final Key<Object> JPEGR_AVAILABLE_JPEG_R_MIN_FRAME_DURATIONS = k("android.jpegr.availableJpegRMinFrameDurations", Object.class);
    public static final Key<Object> JPEGR_AVAILABLE_JPEG_R_MIN_FRAME_DURATIONS_MAXIMUM_RESOLUTION = k("android.jpegr.availableJpegRMinFrameDurationsMaximumResolution", Object.class);
    public static final Key<Object> JPEGR_AVAILABLE_JPEG_R_STALL_DURATIONS = k("android.jpegr.availableJpegRStallDurations", Object.class);
    public static final Key<Object> JPEGR_AVAILABLE_JPEG_R_STALL_DURATIONS_MAXIMUM_RESOLUTION = k("android.jpegr.availableJpegRStallDurationsMaximumResolution", Object.class);
    public static final Key<Object> JPEGR_AVAILABLE_JPEG_R_STREAM_CONFIGURATIONS = k("android.jpegr.availableJpegRStreamConfigurations", Object.class);
    public static final Key<Object> JPEGR_AVAILABLE_JPEG_R_STREAM_CONFIGURATIONS_MAXIMUM_RESOLUTION = k("android.jpegr.availableJpegRStreamConfigurationsMaximumResolution", Object.class);
    public static final Key<android.util.Size[]> JPEG_AVAILABLE_THUMBNAIL_SIZES = k("android.jpeg.availableThumbnailSizes", android.util.Size[].class);
    public static final Key<Object> LED_AVAILABLE_LEDS = k("android.led.availableLeds", Object.class);
    public static final Key<float[]> LENS_DISTORTION = k("android.lens.distortion", float[].class);
    public static final Key<Object> LENS_DISTORTION_MAXIMUM_RESOLUTION = k("android.lens.distortionMaximumResolution", Object.class);
    public static final Key<Integer> LENS_FACING = k("android.lens.facing", Integer.class);
    public static final Key<float[]> LENS_INFO_AVAILABLE_APERTURES = k("android.lens.info.availableApertures", float[].class);
    public static final Key<float[]> LENS_INFO_AVAILABLE_FILTER_DENSITIES = k("android.lens.info.availableFilterDensities", float[].class);
    public static final Key<float[]> LENS_INFO_AVAILABLE_FOCAL_LENGTHS = k("android.lens.info.availableFocalLengths", float[].class);
    public static final Key<int[]> LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION = k("android.lens.info.availableOpticalStabilization", int[].class);
    public static final Key<Integer> LENS_INFO_FOCUS_DISTANCE_CALIBRATION = k("android.lens.info.focusDistanceCalibration", Integer.class);
    public static final Key<Float> LENS_INFO_HYPERFOCAL_DISTANCE = k("android.lens.info.hyperfocalDistance", Float.class);
    public static final Key<Float> LENS_INFO_MINIMUM_FOCUS_DISTANCE = k("android.lens.info.minimumFocusDistance", Float.class);
    public static final Key<Object> LENS_INFO_SHADING_MAP_SIZE = k("android.lens.info.shadingMapSize", Object.class);
    public static final Key<float[]> LENS_INTRINSIC_CALIBRATION = k("android.lens.intrinsicCalibration", float[].class);
    public static final Key<Object> LENS_INTRINSIC_CALIBRATION_MAXIMUM_RESOLUTION = k("android.lens.intrinsicCalibrationMaximumResolution", Object.class);
    public static final Key<Integer> LENS_POSE_REFERENCE = k("android.lens.poseReference", Integer.class);
    public static final Key<float[]> LENS_POSE_ROTATION = k("android.lens.poseRotation", float[].class);
    public static final Key<float[]> LENS_POSE_TRANSLATION = k("android.lens.poseTranslation", float[].class);
    public static final Key<float[]> LENS_RADIAL_DISTORTION = k("android.lens.radialDistortion", float[].class);
    public static final Key<Object> LOGICAL_MULTI_CAMERA_PHYSICAL_IDS = k("android.logicalMultiCamera.physicalIds", Object.class);
    public static final Key<Integer> LOGICAL_MULTI_CAMERA_SENSOR_SYNC_TYPE = k("android.logicalMultiCamera.sensorSyncType", Integer.class);
    public static final Key<int[]> NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES = k("android.noiseReduction.availableNoiseReductionModes", int[].class);
    public static final Key<Object> QUIRKS_USE_PARTIAL_RESULT = k("android.quirks.usePartialResult", Object.class);
    public static final Key<Integer> REPROCESS_MAX_CAPTURE_STALL = k("android.reprocess.maxCaptureStall", Integer.class);
    public static final Key<int[]> REQUEST_AVAILABLE_CAPABILITIES = k("android.request.availableCapabilities", int[].class);
    public static final Key<Object> REQUEST_AVAILABLE_CHARACTERISTICS_KEYS = k("android.request.availableCharacteristicsKeys", Object.class);
    public static final Key<Object> REQUEST_AVAILABLE_COLOR_SPACE_PROFILES = k("android.request.availableColorSpaceProfiles", Object.class);
    public static final Key<Object> REQUEST_AVAILABLE_COLOR_SPACE_PROFILES_MAP = k("android.request.availableColorSpaceProfilesMap", Object.class);
    public static final Key<android.hardware.camera2.params.DynamicRangeProfiles> REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES = k("android.request.availableDynamicRangeProfiles", android.hardware.camera2.params.DynamicRangeProfiles.class);
    public static final Key<Object> REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES_MAP = k("android.request.availableDynamicRangeProfilesMap", Object.class);
    public static final Key<Object> REQUEST_AVAILABLE_PHYSICAL_CAMERA_REQUEST_KEYS = k("android.request.availablePhysicalCameraRequestKeys", Object.class);
    public static final Key<Object> REQUEST_AVAILABLE_REQUEST_KEYS = k("android.request.availableRequestKeys", Object.class);
    public static final Key<Object> REQUEST_AVAILABLE_RESULT_KEYS = k("android.request.availableResultKeys", Object.class);
    public static final Key<Object> REQUEST_AVAILABLE_SESSION_KEYS = k("android.request.availableSessionKeys", Object.class);
    public static final Key<Object> REQUEST_CHARACTERISTIC_KEYS_NEEDING_PERMISSION = k("android.request.characteristicKeysNeedingPermission", Object.class);
    public static final Key<Integer> REQUEST_MAX_NUM_INPUT_STREAMS = k("android.request.maxNumInputStreams", Integer.class);
    public static final Key<Integer> REQUEST_MAX_NUM_OUTPUT_PROC = k("android.request.maxNumOutputProc", Integer.class);
    public static final Key<Integer> REQUEST_MAX_NUM_OUTPUT_PROC_STALLING = k("android.request.maxNumOutputProcStalling", Integer.class);
    public static final Key<Integer> REQUEST_MAX_NUM_OUTPUT_RAW = k("android.request.maxNumOutputRaw", Integer.class);
    public static final Key<int[]> REQUEST_MAX_NUM_OUTPUT_STREAMS = k("android.request.maxNumOutputStreams", int[].class);
    public static final Key<Integer> REQUEST_PARTIAL_RESULT_COUNT = k("android.request.partialResultCount", Integer.class);
    public static final Key<Byte> REQUEST_PIPELINE_MAX_DEPTH = k("android.request.pipelineMaxDepth", Byte.class);
    public static final Key<Object> REQUEST_RECOMMENDED_TEN_BIT_DYNAMIC_RANGE_PROFILE = k("android.request.recommendedTenBitDynamicRangeProfile", Object.class);
    public static final Key<Object> SCALER_AVAILABLE_FORMATS = k("android.scaler.availableFormats", Object.class);
    public static final Key<Object> SCALER_AVAILABLE_INPUT_OUTPUT_FORMATS_MAP = k("android.scaler.availableInputOutputFormatsMap", Object.class);
    public static final Key<Object> SCALER_AVAILABLE_INPUT_OUTPUT_FORMATS_MAP_MAXIMUM_RESOLUTION = k("android.scaler.availableInputOutputFormatsMapMaximumResolution", Object.class);
    public static final Key<Object> SCALER_AVAILABLE_JPEG_MIN_DURATIONS = k("android.scaler.availableJpegMinDurations", Object.class);
    public static final Key<Object> SCALER_AVAILABLE_JPEG_SIZES = k("android.scaler.availableJpegSizes", Object.class);
    public static final Key<Float> SCALER_AVAILABLE_MAX_DIGITAL_ZOOM = k("android.scaler.availableMaxDigitalZoom", Float.class);
    public static final Key<Object> SCALER_AVAILABLE_MIN_FRAME_DURATIONS = k("android.scaler.availableMinFrameDurations", Object.class);
    public static final Key<Object> SCALER_AVAILABLE_MIN_FRAME_DURATIONS_MAXIMUM_RESOLUTION = k("android.scaler.availableMinFrameDurationsMaximumResolution", Object.class);
    public static final Key<Object> SCALER_AVAILABLE_PROCESSED_MIN_DURATIONS = k("android.scaler.availableProcessedMinDurations", Object.class);
    public static final Key<Object> SCALER_AVAILABLE_PROCESSED_SIZES = k("android.scaler.availableProcessedSizes", Object.class);
    public static final Key<Object> SCALER_AVAILABLE_RECOMMENDED_INPUT_OUTPUT_FORMATS_MAP = k("android.scaler.availableRecommendedInputOutputFormatsMap", Object.class);
    public static final Key<Object> SCALER_AVAILABLE_RECOMMENDED_STREAM_CONFIGURATIONS = k("android.scaler.availableRecommendedStreamConfigurations", Object.class);
    public static final Key<int[]> SCALER_AVAILABLE_ROTATE_AND_CROP_MODES = k("android.scaler.availableRotateAndCropModes", int[].class);
    public static final Key<Object> SCALER_AVAILABLE_STALL_DURATIONS = k("android.scaler.availableStallDurations", Object.class);
    public static final Key<Object> SCALER_AVAILABLE_STALL_DURATIONS_MAXIMUM_RESOLUTION = k("android.scaler.availableStallDurationsMaximumResolution", Object.class);
    public static final Key<Object> SCALER_AVAILABLE_STREAM_CONFIGURATIONS = k("android.scaler.availableStreamConfigurations", Object.class);
    public static final Key<Object> SCALER_AVAILABLE_STREAM_CONFIGURATIONS_MAXIMUM_RESOLUTION = k("android.scaler.availableStreamConfigurationsMaximumResolution", Object.class);
    public static final Key<long[]> SCALER_AVAILABLE_STREAM_USE_CASES = k("android.scaler.availableStreamUseCases", long[].class);
    public static final Key<Integer> SCALER_CROPPING_TYPE = k("android.scaler.croppingType", Integer.class);
    public static final Key<Object> SCALER_DEFAULT_SECURE_IMAGE_SIZE = k("android.scaler.defaultSecureImageSize", Object.class);
    public static final Key<Object> SCALER_MANDATORY_CONCURRENT_STREAM_COMBINATIONS = k("android.scaler.mandatoryConcurrentStreamCombinations", Object.class);
    public static final Key<Object> SCALER_MANDATORY_MAXIMUM_RESOLUTION_STREAM_COMBINATIONS = k("android.scaler.mandatoryMaximumResolutionStreamCombinations", Object.class);
    public static final Key<Object> SCALER_MANDATORY_PREVIEW_STABILIZATION_OUTPUT_STREAM_COMBINATIONS = k("android.scaler.mandatoryPreviewStabilizationOutputStreamCombinations", Object.class);
    public static final Key<Object> SCALER_MANDATORY_STREAM_COMBINATIONS = k("android.scaler.mandatoryStreamCombinations", Object.class);
    public static final Key<Object> SCALER_MANDATORY_TEN_BIT_OUTPUT_STREAM_COMBINATIONS = k("android.scaler.mandatoryTenBitOutputStreamCombinations", Object.class);
    public static final Key<Object> SCALER_MANDATORY_USE_CASE_STREAM_COMBINATIONS = k("android.scaler.mandatoryUseCaseStreamCombinations", Object.class);
    public static final Key<Object> SCALER_MULTI_RESOLUTION_STREAM_CONFIGURATION_MAP = k("android.scaler.multiResolutionStreamConfigurationMap", Object.class);
    public static final Key<Object> SCALER_MULTI_RESOLUTION_STREAM_SUPPORTED = k("android.scaler.multiResolutionStreamSupported", Object.class);
    public static final Key<Object> SCALER_PHYSICAL_CAMERA_MULTI_RESOLUTION_STREAM_CONFIGURATIONS = k("android.scaler.physicalCameraMultiResolutionStreamConfigurations", Object.class);
    public static final Key<android.hardware.camera2.params.StreamConfigurationMap> SCALER_STREAM_CONFIGURATION_MAP = k("android.scaler.streamConfigurationMap", android.hardware.camera2.params.StreamConfigurationMap.class);
    public static final Key<Object> SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION = k("android.scaler.streamConfigurationMapMaximumResolution", Object.class);
    public static final Key<int[]> SENSOR_AVAILABLE_TEST_PATTERN_MODES = k("android.sensor.availableTestPatternModes", int[].class);
    public static final Key<Object> SENSOR_BLACK_LEVEL_PATTERN = k("android.sensor.blackLevelPattern", Object.class);
    public static final Key<Object> SENSOR_CALIBRATION_TRANSFORM1 = k("android.sensor.calibrationTransform1", Object.class);
    public static final Key<Object> SENSOR_CALIBRATION_TRANSFORM2 = k("android.sensor.calibrationTransform2", Object.class);
    public static final Key<Object> SENSOR_COLOR_TRANSFORM1 = k("android.sensor.colorTransform1", Object.class);
    public static final Key<Object> SENSOR_COLOR_TRANSFORM2 = k("android.sensor.colorTransform2", Object.class);
    public static final Key<Object> SENSOR_FORWARD_MATRIX1 = k("android.sensor.forwardMatrix1", Object.class);
    public static final Key<Object> SENSOR_FORWARD_MATRIX2 = k("android.sensor.forwardMatrix2", Object.class);
    public static final Key<android.graphics.Rect> SENSOR_INFO_ACTIVE_ARRAY_SIZE = k("android.sensor.info.activeArraySize", android.graphics.Rect.class);
    public static final Key<Object> SENSOR_INFO_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION = k("android.sensor.info.activeArraySizeMaximumResolution", Object.class);
    public static final Key<Object> SENSOR_INFO_BINNING_FACTOR = k("android.sensor.info.binningFactor", Object.class);
    public static final Key<Integer> SENSOR_INFO_COLOR_FILTER_ARRANGEMENT = k("android.sensor.info.colorFilterArrangement", Integer.class);
    public static final Key<android.util.Range> SENSOR_INFO_EXPOSURE_TIME_RANGE = k("android.sensor.info.exposureTimeRange", android.util.Range.class);
    public static final Key<Boolean> SENSOR_INFO_LENS_SHADING_APPLIED = k("android.sensor.info.lensShadingApplied", Boolean.class);
    public static final Key<Long> SENSOR_INFO_MAX_FRAME_DURATION = k("android.sensor.info.maxFrameDuration", Long.class);
    public static final Key<android.util.SizeF> SENSOR_INFO_PHYSICAL_SIZE = k("android.sensor.info.physicalSize", android.util.SizeF.class);
    public static final Key<android.util.Size> SENSOR_INFO_PIXEL_ARRAY_SIZE = k("android.sensor.info.pixelArraySize", android.util.Size.class);
    public static final Key<Object> SENSOR_INFO_PIXEL_ARRAY_SIZE_MAXIMUM_RESOLUTION = k("android.sensor.info.pixelArraySizeMaximumResolution", Object.class);
    public static final Key<android.graphics.Rect> SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE = k("android.sensor.info.preCorrectionActiveArraySize", android.graphics.Rect.class);
    public static final Key<Object> SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION = k("android.sensor.info.preCorrectionActiveArraySizeMaximumResolution", Object.class);
    public static final Key<android.util.Range> SENSOR_INFO_SENSITIVITY_RANGE = k("android.sensor.info.sensitivityRange", android.util.Range.class);
    public static final Key<Integer> SENSOR_INFO_TIMESTAMP_SOURCE = k("android.sensor.info.timestampSource", Integer.class);
    public static final Key<Integer> SENSOR_INFO_WHITE_LEVEL = k("android.sensor.info.whiteLevel", Integer.class);
    public static final Key<Integer> SENSOR_MAX_ANALOG_SENSITIVITY = k("android.sensor.maxAnalogSensitivity", Integer.class);
    public static final Key<Object> SENSOR_OPTICAL_BLACK_REGIONS = k("android.sensor.opticalBlackRegions", Object.class);
    public static final Key<Integer> SENSOR_ORIENTATION = k("android.sensor.orientation", Integer.class);
    public static final Key<Object> SENSOR_READOUT_TIMESTAMP = k("android.sensor.readoutTimestamp", Object.class);
    public static final Key<Object> SENSOR_REFERENCE_ILLUMINANT1 = k("android.sensor.referenceIlluminant1", Object.class);
    public static final Key<Object> SENSOR_REFERENCE_ILLUMINANT2 = k("android.sensor.referenceIlluminant2", Object.class);
    public static final Key<int[]> SHADING_AVAILABLE_MODES = k("android.shading.availableModes", int[].class);
    public static final Key<Object> SHARED_SESSION_COLOR_SPACE = k("android.sharedSession.colorSpace", Object.class);
    public static final Key<Object> SHARED_SESSION_CONFIGURATION = k("android.sharedSession.configuration", Object.class);
    public static final Key<Object> SHARED_SESSION_OUTPUT_CONFIGURATIONS = k("android.sharedSession.outputConfigurations", Object.class);
    public static final Key<int[]> STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES = k("android.statistics.info.availableFaceDetectModes", int[].class);
    public static final Key<boolean[]> STATISTICS_INFO_AVAILABLE_HOT_PIXEL_MAP_MODES = k("android.statistics.info.availableHotPixelMapModes", boolean[].class);
    public static final Key<int[]> STATISTICS_INFO_AVAILABLE_LENS_SHADING_MAP_MODES = k("android.statistics.info.availableLensShadingMapModes", int[].class);
    public static final Key<Object> STATISTICS_INFO_AVAILABLE_OIS_DATA_MODES = k("android.statistics.info.availableOisDataModes", Object.class);
    public static final Key<Integer> STATISTICS_INFO_MAX_FACE_COUNT = k("android.statistics.info.maxFaceCount", Integer.class);
    public static final Key<Integer> SYNC_MAX_LATENCY = k("android.sync.maxLatency", Integer.class);
    public static final Key<int[]> TONEMAP_AVAILABLE_TONE_MAP_MODES = k("android.tonemap.availableToneMapModes", int[].class);
    public static final Key<Integer> TONEMAP_MAX_CURVE_POINTS = k("android.tonemap.maxCurvePoints", Integer.class);

    private final String mId;
    private final int mFacing;
    public CameraCharacteristics(android.hardware.camera2.impl.CameraMetadataNative properties) { mId = "0"; mFacing = 0; }
    CameraCharacteristics(String id, int facing) { mId = id; mFacing = facing; fill(facing); }
    public String huskId() { return mId; }
    /** 0 back, 1 front: what husk.Camera opens. */
    public int huskFacing() { return mFacing; }
    private static final Size[] YUV_SIZES = { new Size(1920, 1080), new Size(1280, 720), new Size(960, 720), new Size(640, 480), new Size(352, 288), new Size(320, 240) };
    private static final Size[] JPEG_SIZES = { new Size(4032, 3024), new Size(3840, 2160), new Size(1920, 1440), new Size(1920, 1080), new Size(1280, 960), new Size(1280, 720), new Size(640, 480) };
    private void fill(int facing) {
        boolean front = facing == 1;
        put(LENS_FACING, front ? LENS_FACING_FRONT : LENS_FACING_BACK);
        put(SENSOR_ORIENTATION, front ? 270 : 90);
        put(INFO_SUPPORTED_HARDWARE_LEVEL, INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED);
        put(INFO_VERSION, "husk");
        put(SYNC_MAX_LATENCY, SYNC_MAX_LATENCY_UNKNOWN);
        put(REQUEST_AVAILABLE_CAPABILITIES, new int[] { REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE });
        put(REQUEST_MAX_NUM_OUTPUT_STREAMS, new int[] { 1, 3, 1 });
        put(REQUEST_MAX_NUM_OUTPUT_RAW, 1); put(REQUEST_MAX_NUM_OUTPUT_PROC, 3); put(REQUEST_MAX_NUM_OUTPUT_PROC_STALLING, 1); put(REQUEST_MAX_NUM_INPUT_STREAMS, 0);
        put(REQUEST_PIPELINE_MAX_DEPTH, (byte) 4); put(REQUEST_PARTIAL_RESULT_COUNT, 1);
        put(SCALER_STREAM_CONFIGURATION_MAP, new StreamConfigurationMap(YUV_SIZES, JPEG_SIZES));
        put(SCALER_AVAILABLE_MAX_DIGITAL_ZOOM, 8f); put(CONTROL_ZOOM_RATIO_RANGE, new Range<Float>(1f, 8f)); put(SCALER_CROPPING_TYPE, SCALER_CROPPING_TYPE_CENTER_ONLY);
        put(SCALER_AVAILABLE_ROTATE_AND_CROP_MODES, new int[] { SCALER_ROTATE_AND_CROP_NONE });
        Rect active = new Rect(0, 0, 4032, 3024);
        put(SENSOR_INFO_ACTIVE_ARRAY_SIZE, active); put(SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE, new Rect(active)); put(SENSOR_INFO_PIXEL_ARRAY_SIZE, new Size(4032, 3024));
        put(SENSOR_INFO_PHYSICAL_SIZE, front ? new SizeF(3.6f, 2.7f) : new SizeF(7.6f, 5.7f));
        put(SENSOR_INFO_TIMESTAMP_SOURCE, SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN); put(SENSOR_INFO_SENSITIVITY_RANGE, new Range<Integer>(32, 6400));
        put(SENSOR_INFO_EXPOSURE_TIME_RANGE, new Range<Long>(12500L, 333333333L)); put(SENSOR_INFO_MAX_FRAME_DURATION, 333333333L);
        put(SENSOR_INFO_WHITE_LEVEL, 4095); put(SENSOR_MAX_ANALOG_SENSITIVITY, 1600); put(SENSOR_INFO_COLOR_FILTER_ARRANGEMENT, SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB);
        put(SENSOR_AVAILABLE_TEST_PATTERN_MODES, new int[] { SENSOR_TEST_PATTERN_MODE_OFF }); put(SENSOR_INFO_LENS_SHADING_APPLIED, false);
        put(LENS_INFO_AVAILABLE_FOCAL_LENGTHS, new float[] { front ? 2.7f : 5.1f }); put(LENS_INFO_AVAILABLE_APERTURES, new float[] { front ? 1.9f : 1.6f });
        put(LENS_INFO_AVAILABLE_FILTER_DENSITIES, new float[] { 0f }); put(LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION, front ? new int[] { 0 } : new int[] { 0, 1 });
        put(LENS_INFO_MINIMUM_FOCUS_DISTANCE, front ? 0f : 10f); put(LENS_INFO_HYPERFOCAL_DISTANCE, front ? 0f : 0.5f);
        put(LENS_INFO_FOCUS_DISTANCE_CALIBRATION, LENS_INFO_FOCUS_DISTANCE_CALIBRATION_APPROXIMATE);
        put(LENS_INTRINSIC_CALIBRATION, new float[] { 3000f, 3000f, 2016f, 1512f, 0f }); put(LENS_POSE_REFERENCE, LENS_POSE_REFERENCE_PRIMARY_CAMERA);
        put(LENS_POSE_ROTATION, new float[] { 0f, 0f, 0f, 1f }); put(LENS_POSE_TRANSLATION, new float[] { 0f, 0f, 0f });
        put(FLASH_INFO_AVAILABLE, !front);
        put(CONTROL_AE_AVAILABLE_MODES, front ? new int[] { CONTROL_AE_MODE_ON } : new int[] { CONTROL_AE_MODE_ON, CONTROL_AE_MODE_ON_AUTO_FLASH, CONTROL_AE_MODE_ON_ALWAYS_FLASH });
        put(CONTROL_AF_AVAILABLE_MODES, front ? new int[] { CONTROL_AF_MODE_OFF } : new int[] { CONTROL_AF_MODE_OFF, CONTROL_AF_MODE_AUTO, CONTROL_AF_MODE_MACRO, CONTROL_AF_MODE_CONTINUOUS_VIDEO, CONTROL_AF_MODE_CONTINUOUS_PICTURE });
        put(CONTROL_AWB_AVAILABLE_MODES, new int[] { CONTROL_AWB_MODE_AUTO });
        put(CONTROL_AVAILABLE_SCENE_MODES, new int[] { CONTROL_SCENE_MODE_DISABLED });
        put(CONTROL_AVAILABLE_EFFECTS, new int[] { CONTROL_EFFECT_MODE_OFF }); put(CONTROL_AVAILABLE_MODES, new int[] { CONTROL_MODE_OFF, CONTROL_MODE_AUTO });
        put(CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES, new int[] { CONTROL_VIDEO_STABILIZATION_MODE_OFF });
        put(CONTROL_AE_AVAILABLE_ANTIBANDING_MODES, new int[] { CONTROL_AE_ANTIBANDING_MODE_OFF, CONTROL_AE_ANTIBANDING_MODE_50HZ, CONTROL_AE_ANTIBANDING_MODE_60HZ, CONTROL_AE_ANTIBANDING_MODE_AUTO });
        put(CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES, new Range[] { new Range<Integer>(15, 30), new Range<Integer>(24, 30), new Range<Integer>(30, 30), new Range<Integer>(15, 15), new Range<Integer>(24, 24) });
        put(CONTROL_AE_COMPENSATION_RANGE, new Range<Integer>(-12, 12)); put(CONTROL_AE_COMPENSATION_STEP, new Rational(1, 6));
        put(CONTROL_AE_LOCK_AVAILABLE, true); put(CONTROL_AWB_LOCK_AVAILABLE, true);
        put(CONTROL_MAX_REGIONS, new int[] { 1, 1, 0 }); put(CONTROL_MAX_REGIONS_AE, 1); put(CONTROL_MAX_REGIONS_AF, front ? 0 : 1); put(CONTROL_MAX_REGIONS_AWB, 0);
        put(CONTROL_POST_RAW_SENSITIVITY_BOOST_RANGE, new Range<Integer>(100, 100));
        put(JPEG_AVAILABLE_THUMBNAIL_SIZES, new Size[] { new Size(0, 0), new Size(160, 120), new Size(320, 240) });
        put(STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES, new int[] { STATISTICS_FACE_DETECT_MODE_OFF }); put(STATISTICS_INFO_MAX_FACE_COUNT, 0);
        put(STATISTICS_INFO_AVAILABLE_HOT_PIXEL_MAP_MODES, new boolean[] { false }); put(STATISTICS_INFO_AVAILABLE_LENS_SHADING_MAP_MODES, new int[] { STATISTICS_LENS_SHADING_MAP_MODE_OFF });
        put(NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES, new int[] { NOISE_REDUCTION_MODE_OFF, NOISE_REDUCTION_MODE_FAST, NOISE_REDUCTION_MODE_HIGH_QUALITY });
        put(EDGE_AVAILABLE_EDGE_MODES, new int[] { EDGE_MODE_OFF, EDGE_MODE_FAST, EDGE_MODE_HIGH_QUALITY });
        put(TONEMAP_AVAILABLE_TONE_MAP_MODES, new int[] { TONEMAP_MODE_FAST, TONEMAP_MODE_HIGH_QUALITY }); put(TONEMAP_MAX_CURVE_POINTS, 64);
        put(SHADING_AVAILABLE_MODES, new int[] { SHADING_MODE_OFF, SHADING_MODE_FAST, SHADING_MODE_HIGH_QUALITY });
        put(HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES, new int[] { HOT_PIXEL_MODE_OFF, HOT_PIXEL_MODE_FAST, HOT_PIXEL_MODE_HIGH_QUALITY });
        put(COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES, new int[] { COLOR_CORRECTION_ABERRATION_MODE_OFF, COLOR_CORRECTION_ABERRATION_MODE_FAST, COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY });
        put(DISTORTION_CORRECTION_AVAILABLE_MODES, new int[] { DISTORTION_CORRECTION_MODE_OFF });
        put(DEPTH_DEPTH_IS_EXCLUSIVE, false); put(REPROCESS_MAX_CAPTURE_STALL, 0);
    }
    private <T> void put(Key<T> key, T value) { huskValues.put(key.getName(), value); }
    @SuppressWarnings("unchecked")
    public <T> T get(Key<T> key) { if (key == null) throw new NullPointerException("key"); return (T) huskValues.get(key.getName()); }
    @Override protected Class<Key<?>> getKeyClass() { Class<?> c = Key.class; return (Class<Key<?>>) c; }
    @Override protected <T> T getProtected(Key<?> key) { return (T) huskValues.get(key.getName()); }
    @Override public java.util.List<Key<?>> getKeys() { java.util.ArrayList<Key<?>> r = new java.util.ArrayList<>(); for (String n : huskValues.keySet()) r.add(huskKey(n)); return r; }
    public java.util.List<CaptureRequest.Key<?>> getAvailableCaptureRequestKeys() { return CaptureRequest.huskAvailableKeys(); }
    public java.util.List<CaptureResult.Key<?>> getAvailableCaptureResultKeys() { return CaptureResult.huskAvailableKeys(); }
    public java.util.List<CaptureRequest.Key<?>> getAvailableSessionKeys() { return new java.util.ArrayList<>(); }
    public java.util.List<CaptureRequest.Key<?>> getAvailablePhysicalCameraRequestKeys() { return new java.util.ArrayList<>(); }
    public java.util.List<Key<?>> getKeysNeedingPermission() { return new java.util.ArrayList<>(); }
    public java.util.Set<String> getPhysicalCameraIds() { return new java.util.HashSet<>(); }
    public android.hardware.camera2.params.RecommendedStreamConfigurationMap getRecommendedStreamConfigurationMap(int usecase) { return null; }
    public java.util.Set<java.util.Set<String>> getAvailablePhysicalCameraStreamCombinations() { return new java.util.HashSet<>(); }
    public android.hardware.camera2.impl.CameraMetadataNative getNativeCopy() { return null; }
    public android.hardware.camera2.impl.CameraMetadataNative getNativeMetadata() { return null; }
    public boolean isLogicalMultiCamera() { return false; }
    public void setHasMandatoryConcurrentStreams(boolean b) {}
}
