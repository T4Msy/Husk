package android.hardware.camera2;

import android.hardware.camera2.params.OutputConfiguration;
import android.os.Handler;
import android.view.Surface;
import java.util.List;
import java.util.concurrent.Executor;

/** A configured set of outputs taking capture requests (CameraCaptureSessionImpl is Husk's). */
public abstract class CameraCaptureSession implements AutoCloseable {
    public static final int SESSION_ID_NONE = -1;
    public static abstract class StateCallback {
        protected StateCallback() {}
        public abstract void onConfigured(CameraCaptureSession session);
        public abstract void onConfigureFailed(CameraCaptureSession session);
        public void onReady(CameraCaptureSession session) {}
        public void onActive(CameraCaptureSession session) {}
        public void onCaptureQueueEmpty(CameraCaptureSession session) {}
        public void onClosed(CameraCaptureSession session) {}
        public void onSurfacePrepared(CameraCaptureSession session, Surface surface) {}
    }
    public static abstract class CaptureCallback {
        public static final int NO_FRAMES_CAPTURED = -1;
        public CaptureCallback() {}
        public void onCaptureStarted(CameraCaptureSession session, CaptureRequest request, long timestamp, long frameNumber) {}
        public void onReadoutStarted(CameraCaptureSession session, CaptureRequest request, long timestamp, long frameNumber) {}
        public void onCapturePartial(CameraCaptureSession session, CaptureRequest request, CaptureResult result) {}
        public void onCaptureProgressed(CameraCaptureSession session, CaptureRequest request, CaptureResult partialResult) {}
        public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {}
        public void onCaptureFailed(CameraCaptureSession session, CaptureRequest request, CaptureFailure failure) {}
        public void onCaptureSequenceCompleted(CameraCaptureSession session, int sequenceId, long frameNumber) {}
        public void onCaptureSequenceAborted(CameraCaptureSession session, int sequenceId) {}
        public void onCaptureBufferLost(CameraCaptureSession session, CaptureRequest request, Surface target, long frameNumber) {}
    }
    public CameraCaptureSession() {}
    public abstract CameraDevice getDevice();
    public abstract void prepare(Surface surface) throws CameraAccessException;
    public void prepare(int maxCount, Surface surface) throws CameraAccessException { prepare(surface); }
    public abstract void tearDown(Surface surface) throws CameraAccessException;
    public abstract void finalizeOutputConfigurations(List<OutputConfiguration> outputConfigs) throws CameraAccessException;
    public abstract int capture(CaptureRequest request, CaptureCallback listener, Handler handler) throws CameraAccessException;
    public int captureSingleRequest(CaptureRequest request, Executor executor, CaptureCallback listener) throws CameraAccessException { throw new UnsupportedOperationException("subclasses must override this method"); }
    public abstract int captureBurst(List<CaptureRequest> requests, CaptureCallback listener, Handler handler) throws CameraAccessException;
    public int captureBurstRequests(List<CaptureRequest> requests, Executor executor, CaptureCallback listener) throws CameraAccessException { throw new UnsupportedOperationException("subclasses must override this method"); }
    public abstract int setRepeatingRequest(CaptureRequest request, CaptureCallback listener, Handler handler) throws CameraAccessException;
    public int setSingleRepeatingRequest(CaptureRequest request, Executor executor, CaptureCallback listener) throws CameraAccessException { throw new UnsupportedOperationException("subclasses must override this method"); }
    public abstract int setRepeatingBurst(List<CaptureRequest> requests, CaptureCallback listener, Handler handler) throws CameraAccessException;
    public int setRepeatingBurstRequests(List<CaptureRequest> requests, Executor executor, CaptureCallback listener) throws CameraAccessException { throw new UnsupportedOperationException("subclasses must override this method"); }
    public abstract void stopRepeating() throws CameraAccessException;
    public abstract void abortCaptures() throws CameraAccessException;
    public void updateOutputConfiguration(OutputConfiguration config) throws CameraAccessException {}
    public CameraOfflineSession switchToOffline(java.util.Collection<Surface> offlineSurfaces, Executor executor, CameraOfflineSession.CameraOfflineSessionCallback listener) throws CameraAccessException { throw new UnsupportedOperationException("offline sessions are not supported"); }
    public boolean supportsOfflineProcessing(Surface surface) { return false; }
    public abstract boolean isReprocessable();
    public abstract Surface getInputSurface();
    public abstract void close();
}
