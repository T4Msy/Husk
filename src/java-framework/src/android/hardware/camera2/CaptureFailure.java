package android.hardware.camera2;

/** A capture that produced no result: the session was closed or its requests aborted under it. */
public class CaptureFailure {
    public static final int REASON_ERROR = 0, REASON_FLUSHED = 1;
    private final CaptureRequest mRequest; private final int mReason, mSequenceId; private final boolean mDropped; private final long mFrameNumber; private final String mPhysicalId;
    public CaptureFailure(CaptureRequest request, int reason, boolean dropped, int sequenceId, long frameNumber, String physicalId) { mRequest = request; mReason = reason; mDropped = dropped; mSequenceId = sequenceId; mFrameNumber = frameNumber; mPhysicalId = physicalId; }
    public CaptureRequest getRequest() { return mRequest; }
    public long getFrameNumber() { return mFrameNumber; }
    public int getReason() { return mReason; }
    public int getSequenceId() { return mSequenceId; }
    public boolean wasImageCaptured() { return !mDropped; }
    public String getPhysicalCameraId() { return mPhysicalId; }
}
