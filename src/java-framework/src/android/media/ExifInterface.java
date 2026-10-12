package android.media;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.LinkedHashMap;

/** The Exif metadata of a JPEG (its APP1 segment's TIFF directories, read on construction) by tag name, as the platform's reader gives it;
 *  attributes set on it are kept but not written back (saveAttributes fails, as the platform's does for a format it cannot write). */
public class ExifInterface {
    public static final int ORIENTATION_FLIP_HORIZONTAL = 2;
    public static final int ORIENTATION_FLIP_VERTICAL = 4;
    public static final int ORIENTATION_NORMAL = 1;
    public static final int ORIENTATION_ROTATE_180 = 3;
    public static final int ORIENTATION_ROTATE_270 = 8;
    public static final int ORIENTATION_ROTATE_90 = 6;
    public static final int ORIENTATION_TRANSPOSE = 5;
    public static final int ORIENTATION_TRANSVERSE = 7;
    public static final int ORIENTATION_UNDEFINED = 0;
    public static final int STREAM_TYPE_EXIF_DATA_ONLY = 1;
    public static final int STREAM_TYPE_FULL_IMAGE_DATA = 0;
    public static final java.lang.String TAG_APERTURE = "FNumber";
    public static final java.lang.String TAG_APERTURE_VALUE = "ApertureValue";
    public static final java.lang.String TAG_ARTIST = "Artist";
    public static final java.lang.String TAG_BITS_PER_SAMPLE = "BitsPerSample";
    public static final java.lang.String TAG_BRIGHTNESS_VALUE = "BrightnessValue";
    public static final java.lang.String TAG_CFA_PATTERN = "CFAPattern";
    public static final java.lang.String TAG_COLOR_SPACE = "ColorSpace";
    public static final java.lang.String TAG_COMPONENTS_CONFIGURATION = "ComponentsConfiguration";
    public static final java.lang.String TAG_COMPRESSED_BITS_PER_PIXEL = "CompressedBitsPerPixel";
    public static final java.lang.String TAG_COMPRESSION = "Compression";
    public static final java.lang.String TAG_CONTRAST = "Contrast";
    public static final java.lang.String TAG_COPYRIGHT = "Copyright";
    public static final java.lang.String TAG_CUSTOM_RENDERED = "CustomRendered";
    public static final java.lang.String TAG_DATETIME = "DateTime";
    public static final java.lang.String TAG_DATETIME_DIGITIZED = "DateTimeDigitized";
    public static final java.lang.String TAG_DATETIME_ORIGINAL = "DateTimeOriginal";
    public static final java.lang.String TAG_DEFAULT_CROP_SIZE = "DefaultCropSize";
    public static final java.lang.String TAG_DEVICE_SETTING_DESCRIPTION = "DeviceSettingDescription";
    public static final java.lang.String TAG_DIGITAL_ZOOM_RATIO = "DigitalZoomRatio";
    public static final java.lang.String TAG_DNG_VERSION = "DNGVersion";
    public static final java.lang.String TAG_EXIF_VERSION = "ExifVersion";
    public static final java.lang.String TAG_EXPOSURE_BIAS_VALUE = "ExposureBiasValue";
    public static final java.lang.String TAG_EXPOSURE_INDEX = "ExposureIndex";
    public static final java.lang.String TAG_EXPOSURE_MODE = "ExposureMode";
    public static final java.lang.String TAG_EXPOSURE_PROGRAM = "ExposureProgram";
    public static final java.lang.String TAG_EXPOSURE_TIME = "ExposureTime";
    public static final java.lang.String TAG_FILE_SOURCE = "FileSource";
    public static final java.lang.String TAG_FLASH = "Flash";
    public static final java.lang.String TAG_FLASHPIX_VERSION = "FlashpixVersion";
    public static final java.lang.String TAG_FLASH_ENERGY = "FlashEnergy";
    public static final java.lang.String TAG_FOCAL_LENGTH = "FocalLength";
    public static final java.lang.String TAG_FOCAL_LENGTH_IN_35MM_FILM = "FocalLengthIn35mmFilm";
    public static final java.lang.String TAG_FOCAL_PLANE_RESOLUTION_UNIT = "FocalPlaneResolutionUnit";
    public static final java.lang.String TAG_FOCAL_PLANE_X_RESOLUTION = "FocalPlaneXResolution";
    public static final java.lang.String TAG_FOCAL_PLANE_Y_RESOLUTION = "FocalPlaneYResolution";
    public static final java.lang.String TAG_F_NUMBER = "FNumber";
    public static final java.lang.String TAG_GAIN_CONTROL = "GainControl";
    public static final java.lang.String TAG_GPS_ALTITUDE = "GPSAltitude";
    public static final java.lang.String TAG_GPS_ALTITUDE_REF = "GPSAltitudeRef";
    public static final java.lang.String TAG_GPS_AREA_INFORMATION = "GPSAreaInformation";
    public static final java.lang.String TAG_GPS_DATESTAMP = "GPSDateStamp";
    public static final java.lang.String TAG_GPS_DEST_BEARING = "GPSDestBearing";
    public static final java.lang.String TAG_GPS_DEST_BEARING_REF = "GPSDestBearingRef";
    public static final java.lang.String TAG_GPS_DEST_DISTANCE = "GPSDestDistance";
    public static final java.lang.String TAG_GPS_DEST_DISTANCE_REF = "GPSDestDistanceRef";
    public static final java.lang.String TAG_GPS_DEST_LATITUDE = "GPSDestLatitude";
    public static final java.lang.String TAG_GPS_DEST_LATITUDE_REF = "GPSDestLatitudeRef";
    public static final java.lang.String TAG_GPS_DEST_LONGITUDE = "GPSDestLongitude";
    public static final java.lang.String TAG_GPS_DEST_LONGITUDE_REF = "GPSDestLongitudeRef";
    public static final java.lang.String TAG_GPS_DIFFERENTIAL = "GPSDifferential";
    public static final java.lang.String TAG_GPS_DOP = "GPSDOP";
    public static final java.lang.String TAG_GPS_IMG_DIRECTION = "GPSImgDirection";
    public static final java.lang.String TAG_GPS_IMG_DIRECTION_REF = "GPSImgDirectionRef";
    public static final java.lang.String TAG_GPS_LATITUDE = "GPSLatitude";
    public static final java.lang.String TAG_GPS_LATITUDE_REF = "GPSLatitudeRef";
    public static final java.lang.String TAG_GPS_LONGITUDE = "GPSLongitude";
    public static final java.lang.String TAG_GPS_LONGITUDE_REF = "GPSLongitudeRef";
    public static final java.lang.String TAG_GPS_MAP_DATUM = "GPSMapDatum";
    public static final java.lang.String TAG_GPS_MEASURE_MODE = "GPSMeasureMode";
    public static final java.lang.String TAG_GPS_PROCESSING_METHOD = "GPSProcessingMethod";
    public static final java.lang.String TAG_GPS_SATELLITES = "GPSSatellites";
    public static final java.lang.String TAG_GPS_SPEED = "GPSSpeed";
    public static final java.lang.String TAG_GPS_SPEED_REF = "GPSSpeedRef";
    public static final java.lang.String TAG_GPS_STATUS = "GPSStatus";
    public static final java.lang.String TAG_GPS_TIMESTAMP = "GPSTimeStamp";
    public static final java.lang.String TAG_GPS_TRACK = "GPSTrack";
    public static final java.lang.String TAG_GPS_TRACK_REF = "GPSTrackRef";
    public static final java.lang.String TAG_GPS_VERSION_ID = "GPSVersionID";
    public static final java.lang.String TAG_IMAGE_DESCRIPTION = "ImageDescription";
    public static final java.lang.String TAG_IMAGE_LENGTH = "ImageLength";
    public static final java.lang.String TAG_IMAGE_UNIQUE_ID = "ImageUniqueID";
    public static final java.lang.String TAG_IMAGE_WIDTH = "ImageWidth";
    public static final java.lang.String TAG_INTEROPERABILITY_INDEX = "InteroperabilityIndex";
    public static final java.lang.String TAG_ISO = "ISOSpeedRatings";
    public static final java.lang.String TAG_ISO_SPEED_RATINGS = "ISOSpeedRatings";
    public static final java.lang.String TAG_JPEG_INTERCHANGE_FORMAT = "JPEGInterchangeFormat";
    public static final java.lang.String TAG_JPEG_INTERCHANGE_FORMAT_LENGTH = "JPEGInterchangeFormatLength";
    public static final java.lang.String TAG_LIGHT_SOURCE = "LightSource";
    public static final java.lang.String TAG_MAKE = "Make";
    public static final java.lang.String TAG_MAKER_NOTE = "MakerNote";
    public static final java.lang.String TAG_MAX_APERTURE_VALUE = "MaxApertureValue";
    public static final java.lang.String TAG_METERING_MODE = "MeteringMode";
    public static final java.lang.String TAG_MODEL = "Model";
    public static final java.lang.String TAG_NEW_SUBFILE_TYPE = "NewSubfileType";
    public static final java.lang.String TAG_OECF = "OECF";
    public static final java.lang.String TAG_OFFSET_TIME = "OffsetTime";
    public static final java.lang.String TAG_OFFSET_TIME_DIGITIZED = "OffsetTimeDigitized";
    public static final java.lang.String TAG_OFFSET_TIME_ORIGINAL = "OffsetTimeOriginal";
    public static final java.lang.String TAG_ORF_ASPECT_FRAME = "AspectFrame";
    public static final java.lang.String TAG_ORF_PREVIEW_IMAGE_LENGTH = "PreviewImageLength";
    public static final java.lang.String TAG_ORF_PREVIEW_IMAGE_START = "PreviewImageStart";
    public static final java.lang.String TAG_ORF_THUMBNAIL_IMAGE = "ThumbnailImage";
    public static final java.lang.String TAG_ORIENTATION = "Orientation";
    public static final java.lang.String TAG_PHOTOMETRIC_INTERPRETATION = "PhotometricInterpretation";
    public static final java.lang.String TAG_PIXEL_X_DIMENSION = "PixelXDimension";
    public static final java.lang.String TAG_PIXEL_Y_DIMENSION = "PixelYDimension";
    public static final java.lang.String TAG_PLANAR_CONFIGURATION = "PlanarConfiguration";
    public static final java.lang.String TAG_PRIMARY_CHROMATICITIES = "PrimaryChromaticities";
    public static final java.lang.String TAG_REFERENCE_BLACK_WHITE = "ReferenceBlackWhite";
    public static final java.lang.String TAG_RELATED_SOUND_FILE = "RelatedSoundFile";
    public static final java.lang.String TAG_RESOLUTION_UNIT = "ResolutionUnit";
    public static final java.lang.String TAG_ROWS_PER_STRIP = "RowsPerStrip";
    public static final java.lang.String TAG_RW2_ISO = "ISO";
    public static final java.lang.String TAG_RW2_JPG_FROM_RAW = "JpgFromRaw";
    public static final java.lang.String TAG_RW2_SENSOR_BOTTOM_BORDER = "SensorBottomBorder";
    public static final java.lang.String TAG_RW2_SENSOR_LEFT_BORDER = "SensorLeftBorder";
    public static final java.lang.String TAG_RW2_SENSOR_RIGHT_BORDER = "SensorRightBorder";
    public static final java.lang.String TAG_RW2_SENSOR_TOP_BORDER = "SensorTopBorder";
    public static final java.lang.String TAG_SAMPLES_PER_PIXEL = "SamplesPerPixel";
    public static final java.lang.String TAG_SATURATION = "Saturation";
    public static final java.lang.String TAG_SCENE_CAPTURE_TYPE = "SceneCaptureType";
    public static final java.lang.String TAG_SCENE_TYPE = "SceneType";
    public static final java.lang.String TAG_SENSING_METHOD = "SensingMethod";
    public static final java.lang.String TAG_SHARPNESS = "Sharpness";
    public static final java.lang.String TAG_SHUTTER_SPEED_VALUE = "ShutterSpeedValue";
    public static final java.lang.String TAG_SOFTWARE = "Software";
    public static final java.lang.String TAG_SPATIAL_FREQUENCY_RESPONSE = "SpatialFrequencyResponse";
    public static final java.lang.String TAG_SPECTRAL_SENSITIVITY = "SpectralSensitivity";
    public static final java.lang.String TAG_STRIP_BYTE_COUNTS = "StripByteCounts";
    public static final java.lang.String TAG_STRIP_OFFSETS = "StripOffsets";
    public static final java.lang.String TAG_SUBFILE_TYPE = "SubfileType";
    public static final java.lang.String TAG_SUBJECT_AREA = "SubjectArea";
    public static final java.lang.String TAG_SUBJECT_DISTANCE = "SubjectDistance";
    public static final java.lang.String TAG_SUBJECT_DISTANCE_RANGE = "SubjectDistanceRange";
    public static final java.lang.String TAG_SUBJECT_LOCATION = "SubjectLocation";
    public static final java.lang.String TAG_SUBSEC_TIME = "SubSecTime";
    public static final java.lang.String TAG_SUBSEC_TIME_DIG = "SubSecTimeDigitized";
    public static final java.lang.String TAG_SUBSEC_TIME_DIGITIZED = "SubSecTimeDigitized";
    public static final java.lang.String TAG_SUBSEC_TIME_ORIG = "SubSecTimeOriginal";
    public static final java.lang.String TAG_SUBSEC_TIME_ORIGINAL = "SubSecTimeOriginal";
    public static final java.lang.String TAG_THUMBNAIL_IMAGE_LENGTH = "ThumbnailImageLength";
    public static final java.lang.String TAG_THUMBNAIL_IMAGE_WIDTH = "ThumbnailImageWidth";
    public static final java.lang.String TAG_THUMBNAIL_ORIENTATION = "ThumbnailOrientation";
    public static final java.lang.String TAG_TRANSFER_FUNCTION = "TransferFunction";
    public static final java.lang.String TAG_USER_COMMENT = "UserComment";
    public static final java.lang.String TAG_WHITE_BALANCE = "WhiteBalance";
    public static final java.lang.String TAG_WHITE_POINT = "WhitePoint";
    public static final java.lang.String TAG_XMP = "Xmp";
    public static final java.lang.String TAG_X_RESOLUTION = "XResolution";
    public static final java.lang.String TAG_Y_CB_CR_COEFFICIENTS = "YCbCrCoefficients";
    public static final java.lang.String TAG_Y_CB_CR_POSITIONING = "YCbCrPositioning";
    public static final java.lang.String TAG_Y_CB_CR_SUB_SAMPLING = "YCbCrSubSampling";
    public static final java.lang.String TAG_Y_RESOLUTION = "YResolution";
    public static final int WHITEBALANCE_AUTO = 0;
    public static final int WHITEBALANCE_MANUAL = 1;
    private static final int FMT_BYTE = 1, FMT_STRING = 2, FMT_USHORT = 3, FMT_ULONG = 4, FMT_URATIONAL = 5, FMT_SBYTE = 6, FMT_UNDEFINED = 7, FMT_SSHORT = 8, FMT_SLONG = 9, FMT_SRATIONAL = 10, FMT_SINGLE = 11, FMT_DOUBLE = 12;
    private static final int[] FMT_SIZE = { 0, 1, 1, 2, 4, 8, 1, 1, 2, 4, 8, 4, 8 };
    private static final HashMap<Integer, String> IFD_TAGS = new HashMap<>(), GPS_TAGS = new HashMap<>(), THUMB_TAGS = new HashMap<>(), INTEROP_TAGS = new HashMap<>();
    static {
        Object[][] ifd = { { 0x0100, "ImageWidth" }, { 0x0101, "ImageLength" }, { 0x0102, "BitsPerSample" }, { 0x0103, "Compression" }, { 0x0106, "PhotometricInterpretation" }, { 0x010E, "ImageDescription" },
            { 0x010F, "Make" }, { 0x0110, "Model" }, { 0x0111, "StripOffsets" }, { 0x0112, "Orientation" }, { 0x0115, "SamplesPerPixel" }, { 0x0116, "RowsPerStrip" }, { 0x0117, "StripByteCounts" },
            { 0x011A, "XResolution" }, { 0x011B, "YResolution" }, { 0x011C, "PlanarConfiguration" }, { 0x0128, "ResolutionUnit" }, { 0x012D, "TransferFunction" }, { 0x0131, "Software" },
            { 0x0132, "DateTime" }, { 0x013B, "Artist" }, { 0x013E, "WhitePoint" }, { 0x013F, "PrimaryChromaticities" }, { 0x0211, "YCbCrCoefficients" }, { 0x0212, "YCbCrSubSampling" },
            { 0x0213, "YCbCrPositioning" }, { 0x0214, "ReferenceBlackWhite" }, { 0x8298, "Copyright" }, { 0x829A, "ExposureTime" }, { 0x829D, "FNumber" }, { 0x8822, "ExposureProgram" },
            { 0x8824, "SpectralSensitivity" }, { 0x8827, "ISOSpeedRatings" }, { 0x8828, "OECF" }, { 0x8830, "SensitivityType" }, { 0x8831, "StandardOutputSensitivity" }, { 0x8832, "RecommendedExposureIndex" },
            { 0x8833, "ISOSpeed" }, { 0x9000, "ExifVersion" }, { 0x9003, "DateTimeOriginal" }, { 0x9004, "DateTimeDigitized" }, { 0x9010, "OffsetTime" }, { 0x9011, "OffsetTimeOriginal" }, { 0x9012, "OffsetTimeDigitized" },
            { 0x9101, "ComponentsConfiguration" }, { 0x9102, "CompressedBitsPerPixel" }, { 0x9201, "ShutterSpeedValue" }, { 0x9202, "ApertureValue" }, { 0x9203, "BrightnessValue" }, { 0x9204, "ExposureBiasValue" },
            { 0x9205, "MaxApertureValue" }, { 0x9206, "SubjectDistance" }, { 0x9207, "MeteringMode" }, { 0x9208, "LightSource" }, { 0x9209, "Flash" }, { 0x920A, "FocalLength" }, { 0x9214, "SubjectArea" },
            { 0x927C, "MakerNote" }, { 0x9286, "UserComment" }, { 0x9290, "SubSecTime" }, { 0x9291, "SubSecTimeOriginal" }, { 0x9292, "SubSecTimeDigitized" }, { 0xA000, "FlashpixVersion" },
            { 0xA001, "ColorSpace" }, { 0xA002, "PixelXDimension" }, { 0xA003, "PixelYDimension" }, { 0xA004, "RelatedSoundFile" }, { 0xA20B, "FlashEnergy" }, { 0xA20C, "SpatialFrequencyResponse" },
            { 0xA20E, "FocalPlaneXResolution" }, { 0xA20F, "FocalPlaneYResolution" }, { 0xA210, "FocalPlaneResolutionUnit" }, { 0xA214, "SubjectLocation" }, { 0xA215, "ExposureIndex" },
            { 0xA217, "SensingMethod" }, { 0xA300, "FileSource" }, { 0xA301, "SceneType" }, { 0xA302, "CFAPattern" }, { 0xA401, "CustomRendered" }, { 0xA402, "ExposureMode" }, { 0xA403, "WhiteBalance" },
            { 0xA404, "DigitalZoomRatio" }, { 0xA405, "FocalLengthIn35mmFilm" }, { 0xA406, "SceneCaptureType" }, { 0xA407, "GainControl" }, { 0xA408, "Contrast" }, { 0xA409, "Saturation" },
            { 0xA40A, "Sharpness" }, { 0xA40B, "DeviceSettingDescription" }, { 0xA40C, "SubjectDistanceRange" }, { 0xA420, "ImageUniqueID" }, { 0xA430, "CameraOwnerName" }, { 0xA431, "BodySerialNumber" },
            { 0xA432, "LensSpecification" }, { 0xA433, "LensMake" }, { 0xA434, "LensModel" }, { 0xA500, "Gamma" }, { 0xC4A5, "PrintImageMatching" }, { 0xC612, "DNGVersion" }, { 0xC61D, "DefaultCropSize" } };
        for (Object[] t : ifd) IFD_TAGS.put((Integer) t[0], (String) t[1]);
        Object[][] gps = { { 0x0000, "GPSVersionID" }, { 0x0001, "GPSLatitudeRef" }, { 0x0002, "GPSLatitude" }, { 0x0003, "GPSLongitudeRef" }, { 0x0004, "GPSLongitude" }, { 0x0005, "GPSAltitudeRef" },
            { 0x0006, "GPSAltitude" }, { 0x0007, "GPSTimeStamp" }, { 0x0008, "GPSSatellites" }, { 0x0009, "GPSStatus" }, { 0x000A, "GPSMeasureMode" }, { 0x000B, "GPSDOP" }, { 0x000C, "GPSSpeedRef" },
            { 0x000D, "GPSSpeed" }, { 0x000E, "GPSTrackRef" }, { 0x000F, "GPSTrack" }, { 0x0010, "GPSImgDirectionRef" }, { 0x0011, "GPSImgDirection" }, { 0x0012, "GPSMapDatum" },
            { 0x0013, "GPSDestLatitudeRef" }, { 0x0014, "GPSDestLatitude" }, { 0x0015, "GPSDestLongitudeRef" }, { 0x0016, "GPSDestLongitude" }, { 0x0017, "GPSDestBearingRef" }, { 0x0018, "GPSDestBearing" },
            { 0x0019, "GPSDestDistanceRef" }, { 0x001A, "GPSDestDistance" }, { 0x001B, "GPSProcessingMethod" }, { 0x001C, "GPSAreaInformation" }, { 0x001D, "GPSDateStamp" }, { 0x001E, "GPSDifferential" },
            { 0x001F, "GPSHPositioningError" } };
        for (Object[] t : gps) GPS_TAGS.put((Integer) t[0], (String) t[1]);
        THUMB_TAGS.putAll(IFD_TAGS);
        THUMB_TAGS.put(0x0100, "ThumbnailImageWidth"); THUMB_TAGS.put(0x0101, "ThumbnailImageLength"); THUMB_TAGS.put(0x0201, "JPEGInterchangeFormat"); THUMB_TAGS.put(0x0202, "JPEGInterchangeFormatLength");
        INTEROP_TAGS.put(0x0001, "InteroperabilityIndex");
    }
    private static final int TAG_EXIF_IFD = 0x8769, TAG_GPS_IFD = 0x8825, TAG_INTEROP_IFD = 0xA005;
    private final LinkedHashMap<String, String> mAttributes = new LinkedHashMap<>();
    private final HashMap<String, long[]> mRanges = new HashMap<>();
    private final HashMap<String, byte[]> mBytes = new HashMap<>();
    private byte[] mExif;               /* the TIFF data (after "Exif\0\0") */
    private long mExifOffset;           /* where it starts in the file */
    private int mThumbOffset = -1, mThumbLength;
    private boolean mLittleEndian;
    private final String mFilename;

    public ExifInterface(String filename) throws IOException { this(new File(filename)); }
    public ExifInterface(File file) throws IOException { mFilename = file.getPath(); try (InputStream in = new FileInputStream(file)) { load(in); } }
    public ExifInterface(FileDescriptor fd) throws IOException { mFilename = null; try (InputStream in = new FileInputStream(fd)) { load(in); } }
    public ExifInterface(InputStream in) throws IOException { mFilename = null; load(in); }
    public ExifInterface(InputStream in, int streamType) throws IOException { this(in); }

    private void load(InputStream in) throws IOException {
        InputStream s = in.markSupported() ? in : new java.io.BufferedInputStream(in, 1 << 16);
        s.mark(16);
        byte[] head = new byte[12];
        int got = readFully(s, head, 0, 12);
        s.reset();
        if (got >= 2 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8) loadJpeg(s);
        else if (got >= 4 && ((head[0] == 'I' && head[1] == 'I' && head[2] == 42 && head[3] == 0) || (head[0] == 'M' && head[1] == 'M' && head[2] == 0 && head[3] == 42))) { byte[] all = readAll(s); parseTiff(all, 0); }
        else if (got >= 6 && head[0] == 'E' && head[1] == 'x' && head[2] == 'i' && head[3] == 'f' && head[4] == 0 && head[5] == 0) { byte[] all = readAll(s); parseTiff(all, 6); }
        /* PNG, WebP, HEIF: their Exif chunks are not read here; no attributes, as a file without any */
    }
    private static int readFully(InputStream in, byte[] b, int off, int len) throws IOException { int n = 0; while (n < len) { int r = in.read(b, off + n, len - n); if (r < 0) break; n += r; } return n; }
    private static byte[] readAll(InputStream in) throws IOException { ByteArrayOutputStream o = new ByteArrayOutputStream(); byte[] b = new byte[8192]; int r; while ((r = in.read(b)) > 0) o.write(b, 0, r); return o.toByteArray(); }
    /* the segments up to the scan: APP1 with "Exif" holds the TIFF data */
    private void loadJpeg(InputStream in) throws IOException {
        long pos = 2;
        if (in.skip(2) != 2) return;
        while (true) {
            int m = in.read();
            if (m < 0) return;
            pos++;
            if (m != 0xFF) continue;
            int marker;
            do { marker = in.read(); pos++; } while (marker == 0xFF);
            if (marker < 0 || marker == 0xD9 || marker == 0xDA) return;        /* EOI, SOS: no Exif before the image data */
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) continue;    /* no length */
            int l1 = in.read(), l2 = in.read();
            if (l1 < 0 || l2 < 0) return;
            int len = ((l1 << 8) | l2) - 2;
            pos += 2;
            if (marker == 0xE1 && len >= 6) {
                byte[] seg = new byte[len];
                if (readFully(in, seg, 0, len) < len) return;
                if (seg[0] == 'E' && seg[1] == 'x' && seg[2] == 'i' && seg[3] == 'f' && seg[4] == 0 && seg[5] == 0) { mExifOffset = pos + 6; parseTiff(seg, 6); return; }
                pos += len;
            } else { long sk = 0; while (sk < len) { long r = in.skip(len - sk); if (r <= 0) { if (in.read() < 0) return; r = 1; } sk += r; } pos += len; }
        }
    }
    private void parseTiff(byte[] data, int start) {
        if (data.length - start < 8) return;
        mExif = start == 0 ? data : java.util.Arrays.copyOfRange(data, start, data.length);
        byte[] d = mExif;
        if (d[0] == 'I' && d[1] == 'I') mLittleEndian = true; else if (d[0] == 'M' && d[1] == 'M') mLittleEndian = false; else { mExif = null; return; }
        if (u16(2) != 42) { mExif = null; return; }
        long ifd0 = u32(4);
        java.util.HashSet<Long> seen = new java.util.HashSet<>();
        long ifd1 = parseIfd(ifd0, IFD_TAGS, seen);
        if (ifd1 > 0 && ifd1 < d.length) parseIfd(ifd1, THUMB_TAGS, seen);
        String tOff = mAttributes.get("JPEGInterchangeFormat"), tLen = mAttributes.get("JPEGInterchangeFormatLength");
        if (tOff != null && tLen != null) { try { int o = Integer.parseInt(tOff), l = Integer.parseInt(tLen); if (o >= 0 && l > 0 && o + l <= d.length) { mThumbOffset = o; mThumbLength = l; } } catch (NumberFormatException e) {} }
    }
    /* reads one directory, following the Exif / GPS / Interop pointers; returns the next directory's offset (0 when none) */
    private long parseIfd(long off, HashMap<Integer, String> names, java.util.HashSet<Long> seen) {
        byte[] d = mExif;
        if (off < 0 || off + 2 > d.length || !seen.add(off)) return 0;
        int n = u16((int) off);
        int p = (int) off + 2;
        for (int i = 0; i < n && p + 12 <= d.length; i++, p += 12) {
            int tag = u16(p), fmt = u16(p + 2); long count = u32(p + 4);
            if (fmt < 1 || fmt > 12 || count < 0 || count > 0x7FFFFFFF) continue;
            long size = FMT_SIZE[fmt] * count;
            int vp = size <= 4 ? p + 8 : (int) u32(p + 8);
            if (vp < 0 || vp + size > d.length || size < 0) continue;
            if ((tag == TAG_EXIF_IFD || tag == TAG_GPS_IFD || tag == TAG_INTEROP_IFD) && (fmt == FMT_ULONG || fmt == FMT_SLONG) && names == IFD_TAGS) {
                parseIfd(u32(vp), tag == TAG_GPS_IFD ? GPS_TAGS : tag == TAG_INTEROP_IFD ? INTEROP_TAGS : IFD_TAGS, seen);
                continue;
            }
            String name = names.get(tag);
            if (name == null) continue;
            byte[] raw = java.util.Arrays.copyOfRange(d, vp, (int) (vp + size));
            mBytes.put(name, raw); mRanges.put(name, new long[] { mExifOffset + vp, size });
            mAttributes.put(name, format(fmt, (int) count, vp));
        }
        return p + 4 <= d.length ? u32(p) : 0;
    }
    private String format(int fmt, int count, int vp) {
        StringBuilder b = new StringBuilder();
        switch (fmt) {
        case FMT_STRING: { int end = vp; while (end < vp + count && mExif[end] != 0) end++; return new String(mExif, vp, end - vp, java.nio.charset.StandardCharsets.US_ASCII); }
        case FMT_BYTE: case FMT_SBYTE: case FMT_UNDEFINED: {
            boolean printable = count > 0;
            for (int i = 0; i < count; i++) { int c = mExif[vp + i] & 0xFF; if (c != 0 && (c < 0x20 || c > 0x7E)) { printable = false; break; } }
            if (printable) { int end = vp + count; while (end > vp && mExif[end - 1] == 0) end--; return new String(mExif, vp, end - vp, java.nio.charset.StandardCharsets.US_ASCII); }
            for (int i = 0; i < count; i++) { if (i > 0) b.append(','); b.append(fmt == FMT_SBYTE ? mExif[vp + i] : mExif[vp + i] & 0xFF); }
            return b.toString(); }
        case FMT_USHORT: case FMT_SSHORT: for (int i = 0; i < count; i++) { if (i > 0) b.append(','); int v = u16(vp + 2 * i); b.append(fmt == FMT_SSHORT ? (short) v : v); } return b.toString();
        case FMT_ULONG: case FMT_SLONG: for (int i = 0; i < count; i++) { if (i > 0) b.append(','); long v = u32(vp + 4 * i); b.append(fmt == FMT_SLONG ? (int) v : v); } return b.toString();
        case FMT_URATIONAL: case FMT_SRATIONAL: for (int i = 0; i < count; i++) { if (i > 0) b.append(','); long num = u32(vp + 8 * i), den = u32(vp + 8 * i + 4); if (fmt == FMT_SRATIONAL) { num = (int) num; den = (int) den; } b.append(num).append('/').append(den); } return b.toString();
        case FMT_SINGLE: for (int i = 0; i < count; i++) { if (i > 0) b.append(','); b.append(Float.intBitsToFloat((int) u32(vp + 4 * i))); } return b.toString();
        case FMT_DOUBLE: for (int i = 0; i < count; i++) { if (i > 0) b.append(','); long hi = u32(vp + 8 * i), lo = u32(vp + 8 * i + 4); b.append(Double.longBitsToDouble(mLittleEndian ? (lo << 32) | hi : (hi << 32) | lo)); } return b.toString();
        }
        return "";
    }
    private int u16(int p) { int a = mExif[p] & 0xFF, b = mExif[p + 1] & 0xFF; return mLittleEndian ? (b << 8) | a : (a << 8) | b; }
    private long u32(int p) { long a = mExif[p] & 0xFF, b = mExif[p + 1] & 0xFF, c = mExif[p + 2] & 0xFF, d = mExif[p + 3] & 0xFF; return mLittleEndian ? (d << 24) | (c << 16) | (b << 8) | a : (a << 24) | (b << 16) | (c << 8) | d; }

    public String getAttribute(String tag) { if (tag == null) throw new NullPointerException("tag shouldn't be null"); return mAttributes.get(tag); }
    public boolean hasAttribute(String tag) { return mAttributes.containsKey(tag); }
    public byte[] getAttributeBytes(String tag) { byte[] b = mBytes.get(tag); if (b != null) return b; String s = mAttributes.get(tag); return s == null ? null : s.getBytes(java.nio.charset.StandardCharsets.US_ASCII); }
    public long[] getAttributeRange(String tag) { return mRanges.get(tag); }
    public int getAttributeInt(String tag, int defaultValue) {
        String v = getAttribute(tag);
        if (v == null) return defaultValue;
        try { return (int) Math.round(parseFirst(v)); } catch (NumberFormatException e) { return defaultValue; }
    }
    public double getAttributeDouble(String tag, double defaultValue) {
        String v = getAttribute(tag);
        if (v == null) return defaultValue;
        try { return parseFirst(v); } catch (NumberFormatException e) { return defaultValue; }
    }
    private static double parseFirst(String v) {
        int c = v.indexOf(','); if (c >= 0) v = v.substring(0, c);
        int s = v.indexOf('/');
        if (s >= 0) { double num = Double.parseDouble(v.substring(0, s)), den = Double.parseDouble(v.substring(s + 1)); if (den == 0) throw new NumberFormatException("zero denominator"); return num / den; }
        return Double.parseDouble(v);
    }
    public void setAttribute(String tag, String value) { if (tag == null) throw new NullPointerException("tag shouldn't be null"); if (value == null) mAttributes.remove(tag); else mAttributes.put(tag, value); }
    public void saveAttributes() throws IOException { throw new IOException("ExifInterface cannot write attributes here" + (mFilename != null ? ": " + mFilename : "")); }
    public boolean hasThumbnail() { return mThumbOffset >= 0; }
    public byte[] getThumbnail() { return hasThumbnail() ? java.util.Arrays.copyOfRange(mExif, mThumbOffset, mThumbOffset + mThumbLength) : null; }
    public byte[] getThumbnailBytes() { return getThumbnail(); }
    public android.graphics.Bitmap getThumbnailBitmap() { byte[] b = getThumbnail(); return b == null ? null : android.graphics.BitmapFactory.decodeByteArray(b, 0, b.length); }
    public long[] getThumbnailRange() { return hasThumbnail() ? new long[] { mExifOffset + mThumbOffset, mThumbLength } : null; }
    public boolean isThumbnailCompressed() { return hasThumbnail(); }
    public boolean getLatLong(float[] output) {
        String lat = getAttribute("GPSLatitude"), latRef = getAttribute("GPSLatitudeRef"), lon = getAttribute("GPSLongitude"), lonRef = getAttribute("GPSLongitudeRef");
        if (lat == null || latRef == null || lon == null || lonRef == null) return false;
        try { output[0] = convertRationalLatLonToFloat(lat, latRef); output[1] = convertRationalLatLonToFloat(lon, lonRef); return true; } catch (IllegalArgumentException e) { return false; }
    }
    public double getAltitude(double defaultValue) {
        double altitude = getAttributeDouble("GPSAltitude", -1);
        int ref = getAttributeInt("GPSAltitudeRef", -1);
        if (altitude >= 0 && ref >= 0) return altitude * (ref == 1 ? -1 : 1);
        return defaultValue;
    }
    public long getDateTime() { return parseDateTime(getAttribute("DateTime"), getAttribute("SubSecTime"), getAttribute("OffsetTime")); }
    public long getDateTimeOriginal() { return parseDateTime(getAttribute("DateTimeOriginal"), getAttribute("SubSecTimeOriginal"), getAttribute("OffsetTimeOriginal")); }
    public long getDateTimeDigitized() { return parseDateTime(getAttribute("DateTimeDigitized"), getAttribute("SubSecTimeDigitized"), getAttribute("OffsetTimeDigitized")); }
    private static long parseDateTime(String dateTime, String subSec, String offset) {
        if (dateTime == null || !dateTime.matches("\\d{4}:\\d{2}:\\d{2} \\d{2}:\\d{2}:\\d{2}")) return -1;
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US);
            f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            if (offset != null && offset.matches("[+-]\\d{2}:\\d{2}")) { f = new java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss XXX", java.util.Locale.US); dateTime = dateTime + " " + offset; }
            long ms = f.parse(dateTime).getTime();
            if (subSec != null) { try { int sub = Integer.parseInt(subSec.trim()); while (sub > 1000) sub /= 10; ms += sub; } catch (NumberFormatException e) {} }
            return ms;
        } catch (java.text.ParseException e) { return -1; }
    }
    public long getGpsDateTime() {
        String date = getAttribute("GPSDateStamp"), time = getAttribute("GPSTimeStamp");
        if (date == null || time == null || !date.matches("\\d{4}:\\d{2}:\\d{2}")) return -1;
        String[] parts = time.split(",");
        if (parts.length < 3) return -1;
        try {
            int h = (int) parseFirst(parts[0]), m = (int) parseFirst(parts[1]), s = (int) parseFirst(parts[2]);
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US);
            f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            return f.parse(String.format(java.util.Locale.US, "%s %02d:%02d:%02d", date, h, m, s)).getTime();
        } catch (Exception e) { return -1; }
    }
    public static float convertRationalLatLonToFloat(String rationalString, String ref) {
        try {
            String[] parts = rationalString.split(",", -1);
            double degrees = parseFirst(parts[0]), minutes = parts.length > 1 ? parseFirst(parts[1]) : 0, seconds = parts.length > 2 ? parseFirst(parts[2]) : 0;
            double result = degrees + minutes / 60.0 + seconds / 3600.0;
            if ("S".equals(ref) || "W".equals(ref)) return (float) -result;
            else if ("N".equals(ref) || "E".equals(ref)) return (float) result;
            throw new IllegalArgumentException();
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) { throw new IllegalArgumentException(); }
    }
    public static boolean isSupportedMimeType(String mimeType) {
        if (mimeType == null) throw new NullPointerException("mimeType shouldn't be null");
        switch (mimeType.toLowerCase(java.util.Locale.ROOT)) {
        case "image/jpeg": case "image/x-adobe-dng": case "image/x-canon-cr2": case "image/x-nikon-nef": case "image/x-nikon-nrw": case "image/x-sony-arw": case "image/x-panasonic-rw2":
        case "image/x-olympus-orf": case "image/x-pentax-pef": case "image/x-samsung-srw": case "image/x-fuji-raf": case "image/heic": case "image/heif": case "image/png": case "image/webp": case "image/avif":
            return true;
        default: return false;
        }
    }
}
