package android.hardware.camera2;

public class CameraAccessException extends android.util.AndroidException {
    public static final int CAMERA_IN_USE = 4, MAX_CAMERAS_IN_USE = 5, CAMERA_DISABLED = 1, CAMERA_DISCONNECTED = 2, CAMERA_ERROR = 3, CAMERA_DEPRECATED_HAL = 1000;
    private final int mReason;
    public CameraAccessException(int problem) { this(problem, getDefaultMessage(problem)); }
    public CameraAccessException(int problem, String message) { super(message); mReason = problem; }
    public CameraAccessException(int problem, String message, Throwable cause) { super(message, cause); mReason = problem; }
    public CameraAccessException(int problem, Throwable cause) { this(problem, getDefaultMessage(problem), cause); }
    public final int getReason() { return mReason; }
    public static String getDefaultMessage(int problem) {
        switch (problem) {
            case CAMERA_IN_USE: return "The camera device is in use already";
            case MAX_CAMERAS_IN_USE: return "The system-wide limit for number of open cameras has been reached, and more camera devices cannot be opened until previous instances are closed.";
            case CAMERA_DISCONNECTED: return "The camera device is removable and has been disconnected from the Android device, or the camera service has shut down the connection due to a higher-priority access request for the camera device.";
            case CAMERA_DISABLED: return "The camera is disabled due to a device policy, and cannot be opened.";
            case CAMERA_ERROR: return "The camera device is currently in the error state; no further calls to it will succeed.";
        }
        return null;
    }
}
