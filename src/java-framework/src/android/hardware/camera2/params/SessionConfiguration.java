package android.hardware.camera2.params;

import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CaptureRequest;
import java.util.concurrent.Executor;

/** The outputs, callback and executor a capture session is made with (CameraDevice.createCaptureSession(SessionConfiguration)). */
public final class SessionConfiguration implements android.os.Parcelable {
    public static final int SESSION_REGULAR = 0, SESSION_HIGH_SPEED = 1, SESSION_SHARED = 2, SESSION_VENDOR_START = 32768;
    private final int mType;
    private final java.util.List<OutputConfiguration> mOutputs;
    private Executor mExecutor;
    private CameraCaptureSession.StateCallback mCallback;
    private InputConfiguration mInput;
    private CaptureRequest mParameters;
    private android.graphics.ColorSpace.Named mColorSpace;
    public SessionConfiguration(int sessionType, java.util.List<OutputConfiguration> outputs) { this(sessionType, outputs, null, null); }
    public SessionConfiguration(int sessionType, java.util.List<OutputConfiguration> outputs, Executor executor, CameraCaptureSession.StateCallback cb) {
        mType = sessionType; mOutputs = outputs == null ? new java.util.ArrayList<OutputConfiguration>() : new java.util.ArrayList<>(outputs); mExecutor = executor; mCallback = cb;
    }
    public int getSessionType() { return mType; }
    public java.util.List<OutputConfiguration> getOutputConfigurations() { return mOutputs; }
    public CameraCaptureSession.StateCallback getStateCallback() { return mCallback; }
    public Executor getExecutor() { return mExecutor; }
    public void setStateCallback(Executor executor, CameraCaptureSession.StateCallback cb) { mExecutor = executor; mCallback = cb; }
    public void setInputConfiguration(InputConfiguration input) { mInput = input; }
    public InputConfiguration getInputConfiguration() { return mInput; }
    public void setSessionParameters(CaptureRequest params) { mParameters = params; }
    public CaptureRequest getSessionParameters() { return mParameters; }
    public void setColorSpace(android.graphics.ColorSpace.Named cs) { mColorSpace = cs; }
    public void clearColorSpace() { mColorSpace = null; }
    public android.graphics.ColorSpace getColorSpace() { return null; }
    public int describeContents() { return 0; }
    public void writeToParcel(android.os.Parcel p, int flags) {}
    public static final Creator<SessionConfiguration> CREATOR = new Creator<SessionConfiguration>() { public SessionConfiguration createFromParcel(android.os.Parcel p) { return new SessionConfiguration(SESSION_REGULAR, null); } public SessionConfiguration[] newArray(int n) { return new SessionConfiguration[n]; } };
}
