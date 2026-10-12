package android.hardware.biometrics;

/** The biometrics on this phone as the app may use them: none (the app's own fallback, a PIN or password, runs instead). */
public class BiometricManager {
    public static final int BIOMETRIC_ERROR_HW_UNAVAILABLE = 1;
    public static final int BIOMETRIC_ERROR_IDENTITY_CHECK_NOT_ACTIVE = 20;
    public static final int BIOMETRIC_ERROR_LOCKOUT = 7;
    public static final int BIOMETRIC_ERROR_NONE_ENROLLED = 11;
    public static final int BIOMETRIC_ERROR_NOT_ENABLED_FOR_APPS = 21;
    public static final int BIOMETRIC_ERROR_NO_HARDWARE = 12;
    public static final int BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED = 15;
    public static final long BIOMETRIC_NO_AUTHENTICATION = -1L;
    public static final int BIOMETRIC_SUCCESS = 0;
    public static final java.lang.String EXTRA_ENROLL_REASON = "enroll_reason";
    public static final int ICON_TYPE_ACCOUNT = 2;
    public static final int ICON_TYPE_GENERIC = 3;
    public static final int ICON_TYPE_PASSWORD = 0;
    public static final int ICON_TYPE_QR_CODE = 1;
    public static final int ICON_TYPE_SETTING = 4;
    public static final int TYPE_FACE = 8;
    public static final int TYPE_FINGERPRINT = 2;
    public BiometricManager() {}
    public BiometricManager(android.content.Context c) {}
    public static String authenticatorToStr(int a) { return a == Authenticators.BIOMETRIC_STRONG ? "BIOMETRIC_STRONG" : a == Authenticators.BIOMETRIC_WEAK ? "BIOMETRIC_WEAK" : a == Authenticators.DEVICE_CREDENTIAL ? "DEVICE_CREDENTIAL" : "0x" + Integer.toHexString(a); }
    public int canAuthenticate() { return canAuthenticate(Authenticators.BIOMETRIC_WEAK); }
    public int canAuthenticate(int authenticators) { return BIOMETRIC_ERROR_NO_HARDWARE; }
    public int canAuthenticate(int userId, int authenticators) { return BIOMETRIC_ERROR_NO_HARDWARE; }
    public boolean hasEnrolledBiometrics(int userId) { return false; }
    public long getLastAuthenticationTime(int authenticators) { return BIOMETRIC_NO_AUTHENTICATION; }
    public Strings getStrings(int authenticators) { return new Strings(); }
    public java.util.List<Object> getSensorProperties() { return new java.util.ArrayList<>(); }
    public java.util.Map<Integer, Integer> getEnrollmentStatus() { return new java.util.HashMap<>(); }
    public static class Strings {
        public CharSequence getButtonLabel() { return null; }
        public CharSequence getPromptMessage() { return null; }
        public CharSequence getSettingName() { return null; }
    }
    public interface Authenticators {
        int EMPTY_SET = 0, BIOMETRIC_STRONG = 0x000F, BIOMETRIC_WEAK = 0x00FF, BIOMETRIC_CONVENIENCE = 0x0FFF, DEVICE_CREDENTIAL = 1 << 15, IDENTITY_CHECK = 1 << 16, MANDATORY_BIOMETRICS = 1 << 16;
    }
}
