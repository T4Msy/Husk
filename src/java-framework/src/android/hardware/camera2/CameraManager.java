package android.hardware.camera2;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.Executor;

/**
 * The cameras as camera2 lists them: "0" the back camera, "1" the front (a device without a back camera has its front one as
 * "0"). openCamera opens the host camera on a worker thread, since the first use asks the user for permission, and reports
 * through the StateCallback on the given handler or executor. The torch can be lit without the camera open: a capture session
 * is kept running for it, as AVFoundation wants, until it is put out.
 */
public final class CameraManager {
    private static final String TAG = "CameraManager";
    public static abstract class AvailabilityCallback {
        public void onCameraAvailable(String cameraId) {}
        public void onCameraUnavailable(String cameraId) {}
        public void onCameraAccessPrioritiesChanged() {}
        public void onPhysicalCameraAvailable(String cameraId, String physicalCameraId) {}
        public void onPhysicalCameraUnavailable(String cameraId, String physicalCameraId) {}
        public void onCameraOpened(String cameraId, String packageId) {}
        public void onCameraClosed(String cameraId) {}
    }
    public static abstract class TorchCallback {
        public void onTorchModeUnavailable(String cameraId) {}
        public void onTorchModeChanged(String cameraId, boolean enabled) {}
        public void onTorchStrengthLevelChanged(String cameraId, int newStrengthLevel) {}
    }
    private static int[] sFacings;
    private static final java.util.HashMap<String, CameraCharacteristics> sChars = new java.util.HashMap<>();
    private static final java.util.HashMap<String, husk.CameraSource> sTorches = new java.util.HashMap<>();
    static final java.util.HashMap<String, CameraDeviceImpl> sOpen = new java.util.HashMap<>();
    private final java.util.ArrayList<Object[]> mAvail = new java.util.ArrayList<>(), mTorch = new java.util.ArrayList<>();
    public CameraManager(Context context) {}
    private static synchronized int[] facings() { if (sFacings == null) sFacings = husk.CameraSource.facings(); return sFacings; }
    static int facingOf(String id) {
        int[] f = facings();
        if (id == null) throw new IllegalArgumentException("cameraId was null");
        int i;
        try { i = Integer.parseInt(id); } catch (NumberFormatException e) { i = -1; }
        if (i < 0 || i >= f.length) throw new IllegalArgumentException("Camera id " + id + " does not match any known camera device");
        return f[i];
    }
    public String[] getCameraIdList() throws CameraAccessException { int[] f = facings(); String[] ids = new String[f.length]; for (int i = 0; i < f.length; i++) ids[i] = Integer.toString(i); return ids; }
    public String[] getCameraIdListNoLazy() throws CameraAccessException { return getCameraIdList(); }
    public java.util.Set<java.util.Set<String>> getConcurrentCameraIds() throws CameraAccessException { return new java.util.HashSet<>(); }
    public boolean isConcurrentSessionConfigurationSupported(java.util.Map<String, android.hardware.camera2.params.SessionConfiguration> configs) { return false; }
    public CameraCharacteristics getCameraCharacteristics(String cameraId) throws CameraAccessException {
        int facing = facingOf(cameraId);
        synchronized (sChars) { CameraCharacteristics c = sChars.get(cameraId); if (c == null) sChars.put(cameraId, c = new CameraCharacteristics(cameraId, facing)); return c; }
    }
    public CameraExtensionCharacteristics getCameraExtensionCharacteristics(String cameraId) throws CameraAccessException { facingOf(cameraId); return new CameraExtensionCharacteristics(null, cameraId, null); }
    public boolean isCameraDeviceSetupSupported(String cameraId) throws CameraAccessException { facingOf(cameraId); return false; }
    public CameraDevice.CameraDeviceSetup getCameraDeviceSetup(String cameraId) throws CameraAccessException { facingOf(cameraId); throw new UnsupportedOperationException("CameraDeviceSetup is not supported"); }
    public void openCamera(String cameraId, CameraDevice.StateCallback callback, Handler handler) throws CameraAccessException { open(cameraId, callback, handler, null); }
    public void openCamera(String cameraId, Executor executor, CameraDevice.StateCallback callback) throws CameraAccessException { open(cameraId, callback, null, executor); }
    public void openCamera(String cameraId, int oomScoreOffset, Executor executor, CameraDevice.StateCallback callback) throws CameraAccessException { open(cameraId, callback, null, executor); }
    private void open(String cameraId, CameraDevice.StateCallback callback, Handler handler, Executor executor) throws CameraAccessException {
        if (callback == null) throw new IllegalArgumentException("callback was null");
        int facing = facingOf(cameraId);
        if (handler == null && executor == null) { if (Looper.myLooper() == null) throw new IllegalArgumentException("Looper doesn't exist in the calling thread"); handler = new Handler(Looper.myLooper()); }
        CameraCharacteristics chars = getCameraCharacteristics(cameraId);
        CameraDeviceImpl dev = new CameraDeviceImpl(cameraId, chars, callback, handler, executor);
        Thread t = new Thread(() -> {
            CameraDeviceImpl old;
            synchronized (sOpen) { old = sOpen.get(cameraId); }
            if (old != null) old.huskEvict();
            husk.CameraSource src;
            synchronized (sTorches) { src = sTorches.remove(cameraId); }
            if (src == null) src = husk.CameraSource.open(facing);
            if (src == null) { dev.huskFailed(CameraDevice.StateCallback.ERROR_CAMERA_DEVICE); return; }
            synchronized (sOpen) { sOpen.put(cameraId, dev); }
            dev.huskOpened(src);
        }, "CameraOpen-" + cameraId);
        t.setDaemon(true);
        t.start();
    }
    static void closed(CameraDeviceImpl dev) { synchronized (sOpen) { if (sOpen.get(dev.getId()) == dev) sOpen.remove(dev.getId()); } }
    public void registerAvailabilityCallback(AvailabilityCallback callback, Handler handler) { register(mAvail, callback, handler, null); fireAvailable(callback, handler, null); }
    public void registerAvailabilityCallback(Executor executor, AvailabilityCallback callback) { register(mAvail, callback, null, executor); fireAvailable(callback, null, executor); }
    public void unregisterAvailabilityCallback(AvailabilityCallback callback) { unregister(mAvail, callback); }
    public void registerTorchCallback(TorchCallback callback, Handler handler) { register(mTorch, callback, handler, null); fireTorch(callback, handler, null); }
    public void registerTorchCallback(Executor executor, TorchCallback callback) { register(mTorch, callback, null, executor); fireTorch(callback, null, executor); }
    public void unregisterTorchCallback(TorchCallback callback) { unregister(mTorch, callback); }
    private void register(java.util.ArrayList<Object[]> list, Object cb, Handler h, Executor e) { synchronized (list) { for (Object[] o : list) if (o[0] == cb) return; list.add(new Object[] { cb, h, e }); } }
    private void unregister(java.util.ArrayList<Object[]> list, Object cb) { synchronized (list) { for (int i = list.size() - 1; i >= 0; i--) if (list.get(i)[0] == cb) list.remove(i); } }
    private void fireAvailable(AvailabilityCallback cb, Handler h, Executor e) {
        String[] ids; try { ids = getCameraIdList(); } catch (CameraAccessException x) { return; }
        CameraDeviceImpl.run(h, e, () -> { for (String id : ids) cb.onCameraAvailable(id); });
    }
    private void fireTorch(TorchCallback cb, Handler h, Executor e) {
        String[] ids; try { ids = getCameraIdList(); } catch (CameraAccessException x) { return; }
        CameraDeviceImpl.run(h, e, () -> { for (String id : ids) { try { if (Boolean.TRUE.equals(getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE))) cb.onTorchModeChanged(id, false); else cb.onTorchModeUnavailable(id); } catch (CameraAccessException x) {} } });
    }
    public void setTorchMode(String cameraId, boolean enabled) throws CameraAccessException {
        int facing = facingOf(cameraId);
        if (!Boolean.TRUE.equals(getCameraCharacteristics(cameraId).get(CameraCharacteristics.FLASH_INFO_AVAILABLE))) throw new IllegalArgumentException("Camera " + cameraId + " has no flash unit");
        CameraDeviceImpl dev;
        synchronized (sOpen) { dev = sOpen.get(cameraId); }
        if (dev != null && dev.huskSource() != null) { dev.huskSource().torch(enabled); }
        else synchronized (sTorches) {
            husk.CameraSource src = sTorches.get(cameraId);
            if (enabled) {
                if (src == null) { src = husk.CameraSource.open(facing); if (src == null) throw new CameraAccessException(CameraAccessException.CAMERA_ERROR, "the camera could not be opened for its torch"); src.configure(640, 480, 0); src.start(); sTorches.put(cameraId, src); }
                src.torch(true);
            } else if (src != null) { src.torch(false); src.close(); sTorches.remove(cameraId); }
        }
        java.util.ArrayList<Object[]> cbs; synchronized (mTorch) { cbs = new java.util.ArrayList<>(mTorch); }
        for (Object[] o : cbs) { TorchCallback cb = (TorchCallback) o[0]; CameraDeviceImpl.run((Handler) o[1], (Executor) o[2], () -> cb.onTorchModeChanged(cameraId, enabled)); }
    }
    public void turnOnTorchWithStrengthLevel(String cameraId, int level) throws CameraAccessException { setTorchMode(cameraId, level > 0); }
    public int getTorchStrengthLevel(String cameraId) throws CameraAccessException { facingOf(cameraId); return 1; }
    public static boolean isHiddenPhysicalCamera(String cameraId) { return false; }
}
