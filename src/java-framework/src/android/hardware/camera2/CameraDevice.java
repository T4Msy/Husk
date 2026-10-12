package android.hardware.camera2;

import android.hardware.camera2.params.InputConfiguration;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.os.Handler;
import android.view.Surface;
import java.util.List;
import java.util.concurrent.Executor;

/** An open camera, as camera2 hands it out (CameraDeviceImpl is Husk's). */
public abstract class CameraDevice implements AutoCloseable {
    public static final int TEMPLATE_PREVIEW = 1, TEMPLATE_STILL_CAPTURE = 2, TEMPLATE_RECORD = 3, TEMPLATE_VIDEO_SNAPSHOT = 4, TEMPLATE_ZERO_SHUTTER_LAG = 5, TEMPLATE_MANUAL = 6;
    public static final int AUDIO_RESTRICTION_NONE = 0, AUDIO_RESTRICTION_VIBRATION = 1, AUDIO_RESTRICTION_VIBRATION_SOUND = 3;
    public static final int SESSION_OPERATION_MODE_NORMAL = 0, SESSION_OPERATION_MODE_CONSTRAINED_HIGH_SPEED = 1, SESSION_OPERATION_MODE_SHARED = 2, SESSION_OPERATION_MODE_VENDOR_START = 32768;
    public static abstract class StateCallback {
        public static final int ERROR_CAMERA_IN_USE = 1, ERROR_MAX_CAMERAS_IN_USE = 2, ERROR_CAMERA_DISABLED = 3, ERROR_CAMERA_DEVICE = 4, ERROR_CAMERA_SERVICE = 5;
        public StateCallback() {}
        public abstract void onOpened(CameraDevice camera);
        public void onClosed(CameraDevice camera) {}
        public abstract void onDisconnected(CameraDevice camera);
        public abstract void onError(CameraDevice camera, int error);
    }
    public static abstract class CameraDeviceSetup {
        public abstract String getId();
        public abstract CaptureRequest.Builder createCaptureRequest(int templateType) throws CameraAccessException;
        public abstract boolean isSessionConfigurationSupported(SessionConfiguration config) throws CameraAccessException;
        public abstract CameraCharacteristics getSessionCharacteristics(SessionConfiguration config) throws CameraAccessException;
        public abstract void openCamera(Executor executor, StateCallback callback) throws CameraAccessException;
    }
    public CameraDevice() {}
    public abstract String getId();
    public abstract void createCaptureSession(List<Surface> outputs, CameraCaptureSession.StateCallback callback, Handler handler) throws CameraAccessException;
    public abstract void createCaptureSessionByOutputConfigurations(List<OutputConfiguration> outputConfigurations, CameraCaptureSession.StateCallback callback, Handler handler) throws CameraAccessException;
    public abstract void createReprocessableCaptureSession(InputConfiguration inputConfig, List<Surface> outputs, CameraCaptureSession.StateCallback callback, Handler handler) throws CameraAccessException;
    public abstract void createReprocessableCaptureSessionByConfigurations(InputConfiguration inputConfig, List<OutputConfiguration> outputs, CameraCaptureSession.StateCallback callback, Handler handler) throws CameraAccessException;
    public abstract void createConstrainedHighSpeedCaptureSession(List<Surface> outputs, CameraCaptureSession.StateCallback callback, Handler handler) throws CameraAccessException;
    public abstract void createCustomCaptureSession(InputConfiguration inputConfig, List<OutputConfiguration> outputs, int operatingMode, CameraCaptureSession.StateCallback callback, Handler handler) throws CameraAccessException;
    public void createCaptureSession(SessionConfiguration config) throws CameraAccessException { throw new UnsupportedOperationException("No default implementation"); }
    public abstract CaptureRequest.Builder createCaptureRequest(int templateType) throws CameraAccessException;
    public CaptureRequest.Builder createCaptureRequest(int templateType, java.util.Set<String> physicalCameraIdSet) throws CameraAccessException { return createCaptureRequest(templateType); }
    public abstract CaptureRequest.Builder createReprocessCaptureRequest(TotalCaptureResult inputResult) throws CameraAccessException;
    public boolean isSessionConfigurationSupported(SessionConfiguration sessionConfig) throws CameraAccessException { return true; }
    public void createExtensionSession(android.hardware.camera2.params.ExtensionSessionConfiguration config) throws CameraAccessException { throw new CameraAccessException(CameraAccessException.CAMERA_ERROR, "camera extensions are not supported"); }
    public int getCameraAudioRestriction() throws CameraAccessException { return AUDIO_RESTRICTION_NONE; }
    public void setCameraAudioRestriction(int mode) throws CameraAccessException {}
    public abstract void close();
}
