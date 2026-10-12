package android.hardware.camera2;

/** The complete result of a capture: Husk reports everything in one, so there are no partial results. */
public final class TotalCaptureResult extends CaptureResult {
    public TotalCaptureResult(android.hardware.camera2.impl.CameraMetadataNative results, int sequenceId) { super(results, sequenceId); }
    public TotalCaptureResult(String cameraId, android.hardware.camera2.impl.CameraMetadataNative results, CaptureRequest parent, int sequenceId, long frameNumber, java.util.List<CaptureResult> partials, int sessionId, android.hardware.camera2.impl.PhysicalCaptureResultInfo[] physical) { super(cameraId, results, parent, sequenceId, frameNumber); }
    TotalCaptureResult(CaptureRequest request, int sequenceId, long frameNumber) { super(request, sequenceId, frameNumber); }
    public java.util.List<CaptureResult> getPartialResults() { return new java.util.ArrayList<>(); }
    public java.util.Map<String, CaptureResult> getPhysicalCameraResults() { return new java.util.HashMap<>(); }
    public java.util.Map<String, TotalCaptureResult> getPhysicalCameraTotalResults() { return new java.util.HashMap<>(); }
    public int getSessionId() { return 0; }
}
