/* SPDX-License-Identifier: GPL-2.0-or-later */
/*
 * The phone's cameras for Java apps (husk.Camera's natives): AVFoundation capture behind android.hardware.Camera and
 * android.hardware.camera2, both of which husk.CameraSource drives from Java.
 *
 * AVFoundation is Objective-C only, so it is reached through the runtime (objc_msgSend cast to each call's real type, the way
 * husk-tl-audio.c reaches AVAudioSession); the sample-buffer delegate is a class made at run time with one method. One
 * AVCaptureSession per open camera: a device input for the wanted position and a video data output delivering
 * 4:2:0 full-range frames (what Android's YUV_420_888 and NV21 want) on a serial queue. The delegate keeps only the newest
 * frame; the Java side's camera thread takes it (waitFrame) and asks for it as RGBA (into a Bitmap, or a direct ByteBuffer
 * for a GL texture), as NV12/NV21 planes, or as a JPEG through ImageIO. Frames never cross into Java on the capture queue.
 *
 * Orientation: Android describes a camera's buffers by SENSOR_ORIENTATION, the clockwise turn that makes them upright in
 * portrait, and apps do that turn themselves. The back camera's buffers are asked for in AVFoundation's landscape-right
 * orientation, which is that same 90-degree case; the front camera's in landscape-left, 270 degrees, as Android's front
 * cameras report, and unmirrored, as their raw buffers are. Mirroring, display rotation and scaling are applied per
 * consumer in frameToBitmap/frameToBuffer (vImage), so a request for a preview, a JPEG or a YUV frame each gets the geometry
 * its Android counterpart would have.
 */
#ifndef _DARWIN_C_SOURCE
#define _DARWIN_C_SOURCE
#endif
#include <Accelerate/Accelerate.h>
#include <CoreGraphics/CoreGraphics.h>
#include <CoreMedia/CoreMedia.h>
#include <CoreVideo/CoreVideo.h>
#include <ImageIO/ImageIO.h>
#include <dispatch/dispatch.h>
#include <dlfcn.h>
#include <objc/message.h>
#include <objc/runtime.h>
/* the autorelease pool entry points live in the ObjC runtime but are not declared by its public headers */
extern void *objc_autoreleasePoolPush(void);
extern void objc_autoreleasePoolPop(void *pool);
#include <pthread.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "husk-tl-dvm-gl.h"
#include "husk-tl-dvm-internal.h"

void tl_log_line(const char *fmt, ...);
typedef struct { uint32_t width, height, stride; int32_t format; uint32_t flags; } tl_abitmap_info;
int tl_AndroidBitmap_getInfo(void *env, void *bitmap, tl_abitmap_info *info);
int tl_AndroidBitmap_lockPixels(void *env, void *bitmap, void **addr);

#define NAT(fn) static bool fn(jobj *self, const jvalue *a, jvalue *ret)
static jvalue I(int32_t v) { jvalue r; r.j = (uint32_t)v; return r; }
static jvalue J(int64_t v) { jvalue r; r.j = v; return r; }
static jvalue Z(bool v) { jvalue r; r.j = v; return r; }
static jvalue F(float v) { jvalue r; r.j = 0; r.f = v; return r; }
static jvalue L(void *p) { jvalue r; r.j = 0; r.l = p; return r; }

/* ---- Objective-C from C */

typedef id (*msg_id)(id, SEL);
typedef id (*msg_id_id)(id, SEL, id);
typedef id (*msg_id_s)(id, SEL, const char *);
typedef bool (*msg_bool)(id, SEL);
typedef bool (*msg_bool_id)(id, SEL, id);
typedef bool (*msg_bool_l)(id, SEL, long);
typedef bool (*msg_bool_p)(id, SEL, void *);
typedef long (*msg_long)(id, SEL);
typedef double (*msg_double)(id, SEL);
typedef float (*msg_float)(id, SEL);
typedef void (*msg_void)(id, SEL);
typedef void (*msg_void_id)(id, SEL, id);
typedef void (*msg_void_bool)(id, SEL, bool);
typedef void (*msg_void_l)(id, SEL, long);
typedef void (*msg_void_d)(id, SEL, double);
typedef void (*msg_void_pt)(id, SEL, CGPoint);
typedef void (*msg_void_tm)(id, SEL, CMTime);
typedef void (*msg_void_f_p)(id, SEL, float, void *);
typedef void (*msg_void_id_p)(id, SEL, id, void *);
typedef void (*msg_void_id_q)(id, SEL, id, dispatch_queue_t);
typedef bool (*msg_bool_d)(id, SEL, double);

#define SEL(s) sel_registerName(s)
#define MSG(T) ((T)objc_msgSend)
static id cls(const char *n) { return (id)objc_getClass(n); }
static id nsstr(const char *s) { return MSG(msg_id_s)(cls("NSString"), SEL("stringWithUTF8String:"), s); }
static bool responds(id o, const char *sel) { return o && MSG(msg_bool_p)(o, SEL("respondsToSelector:"), (void *)SEL(sel)); }
/* An AVFoundation NSString constant by its symbol (the library's own object, when it is exported; its name otherwise, which
 * is the value of every constant used here). */
static id avf_const(const char *sym)
{
    void **p = dlsym(RTLD_DEFAULT, sym);
    return p && *p ? (id)*p : nsstr(sym);
}

/* A block without the block syntax: the ABI's literal, as the compiler would lay it out for a global block. */
struct blk_desc { unsigned long reserved, size; };
struct blk { void *isa; int flags, reserved; void (*invoke)(struct blk *, bool); struct blk_desc *desc; };
extern void *_NSConcreteGlobalBlock[32];
enum { BLOCK_IS_GLOBAL = 1 << 28 };

/* ---- the cameras */

enum { POS_BACK = 1, POS_FRONT = 2 };            /* AVCaptureDevicePosition */
enum { ORIENT_LANDSCAPE_RIGHT = 3, ORIENT_LANDSCAPE_LEFT = 4 };   /* AVCaptureVideoOrientation */
enum { FOCUS_LOCKED = 0, FOCUS_AUTO = 1, FOCUS_CONTINUOUS = 2 };  /* AVCaptureFocusMode; exposure modes have the same values */

typedef struct frame { CVPixelBufferRef pb; int64_t ts; } frame;

typedef struct cam {
    bool used;
    int facing;                                     /* 0 back, 1 front */
    id device, session, input, output, delegate;
    dispatch_queue_t queue;
    pthread_mutex_t lock;
    pthread_cond_t cv;
    CVPixelBufferRef latest; int64_t latest_ts;
    int width, height;                              /* the configured frames' size */
    long frames, dropped;
    bool running, trace;
} cam;

#define MAX_CAMS 2
static cam g_cams[MAX_CAMS];
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static Class g_delegate_class;
static bool g_avf_loaded;

static int64_t now_ns(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return (int64_t)t.tv_sec * 1000000000ll + t.tv_nsec; }
static bool trace_on(void) { static int t = -1; if (t < 0) t = getenv("TL_CAMERA_TRACE") != NULL; return t; }

static void avf_load(void)
{
    if (g_avf_loaded) return;
    g_avf_loaded = true;
    if (!objc_getClass("AVCaptureSession")) dlopen("/System/Library/Frameworks/AVFoundation.framework/AVFoundation", RTLD_NOW);
    if (!objc_getClass("AVCaptureSession")) tl_log_line("camera: AVFoundation is not loaded");
}

/* captureOutput:didOutputSampleBuffer:fromConnection: on the capture queue: keep the frame, wake the camera thread */
static void did_output(id self, SEL _cmd, id output, CMSampleBufferRef sb, id conn)
{
    (void)self; (void)_cmd; (void)conn;
    CVImageBufferRef pb = CMSampleBufferGetImageBuffer(sb);
    if (!pb) return;
    for (int i = 0; i < MAX_CAMS; i++) {
        cam *c = &g_cams[i];
        if (!c->used || c->output != output) continue;
        pthread_mutex_lock(&c->lock);
        if (c->latest) { CVPixelBufferRelease(c->latest); c->dropped++; }
        c->latest = CVPixelBufferRetain(pb);
        c->latest_ts = now_ns();
        c->frames++;
        pthread_cond_broadcast(&c->cv);
        pthread_mutex_unlock(&c->lock);
        return;
    }
}

static Class delegate_class(void)
{
    if (g_delegate_class) return g_delegate_class;
    Class k = objc_allocateClassPair(objc_getClass("NSObject"), "HuskCameraDelegate", 0);
    if (!k) return NULL;
    class_addMethod(k, SEL("captureOutput:didOutputSampleBuffer:fromConnection:"), (IMP)did_output, "v@:@@@");
    Protocol *p = objc_getProtocol("AVCaptureVideoDataOutputSampleBufferDelegate");
    if (p) class_addProtocol(k, p);
    objc_registerClassPair(k);
    g_delegate_class = k;
    return k;
}

static id device_at(int facing)
{
    avf_load();
    id avdev = cls("AVCaptureDevice");
    if (!avdev) return NULL;
    typedef id (*msg_dev)(id, SEL, id, id, long);
    return MSG(msg_dev)(avdev, SEL("defaultDeviceWithDeviceType:mediaType:position:"), avf_const("AVCaptureDeviceTypeBuiltInWideAngleCamera"),
                        nsstr("vide"), facing ? POS_FRONT : POS_BACK);
}

/* ---- permission: AVCaptureDevice's request takes a completion block */
static pthread_mutex_t g_perm_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t g_perm_cv = PTHREAD_COND_INITIALIZER;
static int g_perm_answer;                           /* 0 pending, 1 granted, -1 denied */
static void perm_done(struct blk *b, bool granted)
{
    (void)b;
    pthread_mutex_lock(&g_perm_lock);
    g_perm_answer = granted ? 1 : -1;
    pthread_cond_broadcast(&g_perm_cv);
    pthread_mutex_unlock(&g_perm_lock);
}
static struct blk_desc g_perm_desc = { 0, sizeof(struct blk) };
static struct blk g_perm_block = { _NSConcreteGlobalBlock, BLOCK_IS_GLOBAL, 0, perm_done, &g_perm_desc };

/* true when the camera may be used; asks the user the first time and waits for the answer */
static bool permitted(void)
{
    id avdev = cls("AVCaptureDevice");
    if (!avdev) return false;
    typedef long (*msg_status)(id, SEL, id);
    long st = MSG(msg_status)(avdev, SEL("authorizationStatusForMediaType:"), nsstr("vide"));
    if (st == 3) return true;                       /* authorized */
    if (st != 0) { tl_log_line("camera: access is %s: allow the camera for Husk in Settings", st == 2 ? "denied" : "restricted"); return false; }
    pthread_mutex_lock(&g_perm_lock);
    g_perm_answer = 0;
    MSG(msg_void_id_p)(avdev, SEL("requestAccessForMediaType:completionHandler:"), nsstr("vide"), &g_perm_block);
    struct timespec until; clock_gettime(CLOCK_REALTIME, &until); until.tv_sec += 120;
    while (!g_perm_answer) if (pthread_cond_timedwait(&g_perm_cv, &g_perm_lock, &until)) break;
    int ans = g_perm_answer;
    pthread_mutex_unlock(&g_perm_lock);
    if (ans <= 0) tl_log_line("camera: the user did not allow the camera");
    return ans > 0;
}

/* ---- the session */

static bool lock_device(cam *c) { return MSG(msg_bool_p)(c->device, SEL("lockForConfiguration:"), NULL); }
static void unlock_device(cam *c) { MSG(msg_void)(c->device, SEL("unlockForConfiguration")); }

static void orient_connection(cam *c)
{
    id conn = MSG(msg_id_id)(c->output, SEL("connectionWithMediaType:"), nsstr("vide"));
    if (!conn) return;
    if (responds(conn, "setAutomaticallyAdjustsVideoMirroring:")) MSG(msg_void_bool)(conn, SEL("setAutomaticallyAdjustsVideoMirroring:"), false);
    if (MSG(msg_bool)(conn, SEL("isVideoMirroringSupported"))) MSG(msg_void_bool)(conn, SEL("setVideoMirrored:"), false);
    /* iOS 17 names the orientation as an angle: 0 is landscape right, 180 landscape left */
    double angle = c->facing ? 180 : 0;
    if (responds(conn, "setVideoRotationAngle:") && MSG(msg_bool_d)(conn, SEL("isVideoRotationAngleSupported:"), angle)) {
        MSG(msg_void_d)(conn, SEL("setVideoRotationAngle:"), angle);
    } else if (MSG(msg_bool)(conn, SEL("isVideoOrientationSupported"))) {
        MSG(msg_void_l)(conn, SEL("setVideoOrientation:"), c->facing ? ORIENT_LANDSCAPE_LEFT : ORIENT_LANDSCAPE_RIGHT);
    }
}

static void read_size(cam *c)
{
    id fmt = MSG(msg_id)(c->device, SEL("activeFormat"));
    CMVideoFormatDescriptionRef desc = fmt ? (CMVideoFormatDescriptionRef)MSG(msg_id)(fmt, SEL("formatDescription")) : NULL;
    if (!desc) return;
    CMVideoDimensions d = CMVideoFormatDescriptionGetDimensions(desc);
    if (d.width > 0 && d.height > 0) { c->width = d.width; c->height = d.height; }
}

static bool session_open(cam *c)
{
    void *pool = objc_autoreleasePoolPush();
    bool ok = false;
    c->device = device_at(c->facing);
    if (!c->device) { tl_log_line("camera: no %s camera", c->facing ? "front" : "back"); goto out; }
    MSG(msg_id)(c->device, SEL("retain"));
    typedef id (*msg_input)(id, SEL, id, void *);
    c->input = MSG(msg_input)(cls("AVCaptureDeviceInput"), SEL("deviceInputWithDevice:error:"), c->device, NULL);
    if (!c->input) { tl_log_line("camera: the %s camera gave no input", c->facing ? "front" : "back"); goto out; }
    MSG(msg_id)(c->input, SEL("retain"));
    c->session = MSG(msg_id)(MSG(msg_id)(cls("AVCaptureSession"), SEL("alloc")), SEL("init"));
    if (responds(c->session, "setAutomaticallyConfiguresApplicationAudioSession:"))
        MSG(msg_void_bool)(c->session, SEL("setAutomaticallyConfiguresApplicationAudioSession:"), false);
    c->output = MSG(msg_id)(MSG(msg_id)(cls("AVCaptureVideoDataOutput"), SEL("alloc")), SEL("init"));
    int pf = kCVPixelFormatType_420YpCbCr8BiPlanarFullRange;
    typedef id (*msg_num)(id, SEL, int);
    id num = MSG(msg_num)(cls("NSNumber"), SEL("numberWithInt:"), pf);
    typedef id (*msg_dict)(id, SEL, id, id);
    id settings = MSG(msg_dict)(cls("NSDictionary"), SEL("dictionaryWithObject:forKey:"), num, (id)kCVPixelBufferPixelFormatTypeKey);
    MSG(msg_void_id)(c->output, SEL("setVideoSettings:"), settings);
    MSG(msg_void_bool)(c->output, SEL("setAlwaysDiscardsLateVideoFrames:"), true);
    c->queue = dispatch_queue_create("husk.camera", DISPATCH_QUEUE_SERIAL);
    Class dk = delegate_class();
    if (!dk) goto out;
    c->delegate = MSG(msg_id)(MSG(msg_id)((id)dk, SEL("alloc")), SEL("init"));
    MSG(msg_void_id_q)(c->output, SEL("setSampleBufferDelegate:queue:"), c->delegate, c->queue);
    MSG(msg_void)(c->session, SEL("beginConfiguration"));
    if (!MSG(msg_bool_id)(c->session, SEL("canAddInput:"), c->input)) { MSG(msg_void)(c->session, SEL("commitConfiguration")); tl_log_line("camera: the session refused the %s camera", c->facing ? "front" : "back"); goto out; }
    MSG(msg_void_id)(c->session, SEL("addInput:"), c->input);
    if (!MSG(msg_bool_id)(c->session, SEL("canAddOutput:"), c->output)) { MSG(msg_void)(c->session, SEL("commitConfiguration")); tl_log_line("camera: the session refused a video output"); goto out; }
    MSG(msg_void_id)(c->session, SEL("addOutput:"), c->output);
    MSG(msg_void)(c->session, SEL("commitConfiguration"));
    orient_connection(c);
    read_size(c);
    ok = true;
out:
    objc_autoreleasePoolPop(pool);
    return ok;
}

static void queue_drained(void *arg) { (void)arg; }

static void session_close(cam *c)
{
    void *pool = objc_autoreleasePoolPush();
    if (c->session) {
        if (c->running) MSG(msg_void)(c->session, SEL("stopRunning"));
        if (c->output) { MSG(msg_void_id_q)(c->output, SEL("setSampleBufferDelegate:queue:"), NULL, NULL); MSG(msg_void_id)(c->session, SEL("removeOutput:"), c->output); }
        if (c->queue) dispatch_sync_f(c->queue, NULL, queue_drained);     /* a delivery still running on the queue finishes first */
        if (c->input) MSG(msg_void_id)(c->session, SEL("removeInput:"), c->input);
        MSG(msg_id)(c->session, SEL("release"));
    }
    if (c->output) MSG(msg_id)(c->output, SEL("release"));
    if (c->delegate) MSG(msg_id)(c->delegate, SEL("release"));
    if (c->input) MSG(msg_id)(c->input, SEL("release"));
    if (c->device) MSG(msg_id)(c->device, SEL("release"));
    if (c->queue) dispatch_release(c->queue);
    objc_autoreleasePoolPop(pool);
    pthread_mutex_lock(&c->lock);
    if (c->latest) CVPixelBufferRelease(c->latest);
    c->latest = NULL;
    pthread_mutex_unlock(&c->lock);
    c->session = c->output = c->delegate = c->input = c->device = NULL; c->queue = NULL;
    c->running = false;
}

/* The preset whose frames are at least the wanted size (the app's largest stream), capped at 1080p unless more is asked. */
static const char *preset_for(int w, int h)
{
    long px = (long)w * h;
    if (px <= 640 * 480) return "AVCaptureSessionPreset640x480";
    if (px <= 1280 * 720) return "AVCaptureSessionPreset1280x720";
    if (px <= 1920 * 1080) return "AVCaptureSessionPreset1920x1080";
    return "AVCaptureSessionPreset3840x2160";
}

static bool session_configure(cam *c, int w, int h, int fps)
{
    void *pool = objc_autoreleasePoolPush();
    const char *names[] = { preset_for(w, h), "AVCaptureSessionPreset1920x1080", "AVCaptureSessionPreset1280x720", "AVCaptureSessionPresetHigh", NULL };
    bool set = false;
    MSG(msg_void)(c->session, SEL("beginConfiguration"));
    for (int i = 0; names[i] && !set; i++) {
        id p = avf_const(names[i]);
        if (MSG(msg_bool_id)(c->session, SEL("canSetSessionPreset:"), p)) { MSG(msg_void_id)(c->session, SEL("setSessionPreset:"), p); set = true; }
    }
    MSG(msg_void)(c->session, SEL("commitConfiguration"));
    orient_connection(c);
    if (fps > 0 && lock_device(c)) {
        CMTime d = CMTimeMake(1, fps);
        MSG(msg_void_tm)(c->device, SEL("setActiveVideoMinFrameDuration:"), d);
        MSG(msg_void_tm)(c->device, SEL("setActiveVideoMaxFrameDuration:"), d);
        unlock_device(c);
    }
    read_size(c);
    if (c->trace) tl_log_line("camera: %s camera configured for %dx%d (%s), frames %dx%d", c->facing ? "front" : "back", w, h, set ? preset_for(w, h) : "default preset", c->width, c->height);
    objc_autoreleasePoolPop(pool);
    return set;
}

/* ---- pixels: the frame as RGBA at the source size */

static bool g_conv_ready[2];
static vImage_YpCbCrToARGB g_conv[2];               /* 0 video range, 1 full range */
static const vImage_YpCbCrToARGB *conversion(bool full)
{
    int i = full ? 1 : 0;
    if (!g_conv_ready[i]) {
        vImage_YpCbCrPixelRange video = { 16, 128, 235, 240, 235, 16, 240, 16 }, fullr = { 0, 128, 255, 255, 255, 1, 255, 0 };
        if (vImageConvert_YpCbCrToARGB_GenerateConversion(kvImage_YpCbCrToARGBMatrix_ITU_R_601_4, full ? &fullr : &video, &g_conv[i], kvImage420Yp8_CbCr8, kvImageARGB8888, kvImageNoFlags) != kvImageNoError) return NULL;
        g_conv_ready[i] = true;
    }
    return &g_conv[i];
}

/* dst: w x h RGBA rows of rowBytes; the frame locked by the caller */
static bool frame_rgba(CVPixelBufferRef pb, vImage_Buffer *dst)
{
    OSType pf = CVPixelBufferGetPixelFormatType(pb);
    size_t w = CVPixelBufferGetWidth(pb), h = CVPixelBufferGetHeight(pb);
    if (dst->width != w || dst->height != h) return false;
    if (pf == kCVPixelFormatType_32BGRA) {
        vImage_Buffer s = { CVPixelBufferGetBaseAddress(pb), h, w, CVPixelBufferGetBytesPerRow(pb) };
        const uint8_t map[4] = { 2, 1, 0, 3 };
        return vImagePermuteChannels_ARGB8888(&s, dst, map, kvImageNoFlags) == kvImageNoError;
    }
    if (pf != kCVPixelFormatType_420YpCbCr8BiPlanarFullRange && pf != kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange) return false;
    const vImage_YpCbCrToARGB *conv = conversion(pf == kCVPixelFormatType_420YpCbCr8BiPlanarFullRange);
    if (!conv) return false;
    vImage_Buffer yp = { CVPixelBufferGetBaseAddressOfPlane(pb, 0), h, w, CVPixelBufferGetBytesPerRowOfPlane(pb, 0) };
    vImage_Buffer cbcr = { CVPixelBufferGetBaseAddressOfPlane(pb, 1), CVPixelBufferGetHeightOfPlane(pb, 1), CVPixelBufferGetWidthOfPlane(pb, 1), CVPixelBufferGetBytesPerRowOfPlane(pb, 1) };
    const uint8_t map[4] = { 1, 2, 3, 0 };          /* ARGB -> RGBA */
    return vImageConvert_420Yp8_CbCr8ToARGB8888(&yp, &cbcr, dst, conv, map, 255, kvImageNoFlags) == kvImageNoError;
}

static vImage_Buffer vbuf(int w, int h) { vImage_Buffer b = { malloc((size_t)w * h * 4), (vImagePixelCount)h, (vImagePixelCount)w, (size_t)w * 4 }; return b; }

/* transform: bits 0-1 the clockwise quarter turns, bit 2 a horizontal flip and bit 3 a vertical flip (both in the frame's own
 * orientation, before the turn); then scaled to dst's size. */
enum { TF_ROT_MASK = 3, TF_FLIP_X = 4, TF_FLIP_Y = 8 };
static bool render_rgba(CVPixelBufferRef pb, int transform, vImage_Buffer *dst)
{
    int w = (int)CVPixelBufferGetWidth(pb), h = (int)CVPixelBufferGetHeight(pb);
    if (w <= 0 || h <= 0 || !dst->data) return false;
    int rot = transform & TF_ROT_MASK;
    int rw = rot & 1 ? h : w, rh = rot & 1 ? w : h;      /* size after the turn */
    bool direct = !(transform & (TF_FLIP_X | TF_FLIP_Y)) && rot == 0 && (int)dst->width == w && (int)dst->height == h;
    CVPixelBufferLockBaseAddress(pb, kCVPixelBufferLock_ReadOnly);
    bool ok;
    if (direct) ok = frame_rgba(pb, dst);
    else {
        /* a: the frame; b: flipped; c: turned -- each step from the last one's buffer, none in place */
        vImage_Buffer a = vbuf(w, h), b = { NULL, 0, 0, 0 }, c = { NULL, 0, 0, 0 }, *cur = &a;
        ok = a.data && frame_rgba(pb, &a);
        if (ok && (transform & (TF_FLIP_X | TF_FLIP_Y))) {
            b = vbuf(w, h);
            ok = b.data != NULL;
            if (ok && (transform & TF_FLIP_X)) ok = vImageHorizontalReflect_ARGB8888(cur, &b, kvImageNoFlags) == kvImageNoError, cur = &b;
            if (ok && (transform & TF_FLIP_Y)) {
                if (cur == &b) { vImage_Buffer t = a; ok = vImageVerticalReflect_ARGB8888(&b, &t, kvImageNoFlags) == kvImageNoError; cur = &a; }
                else ok = vImageVerticalReflect_ARGB8888(&a, &b, kvImageNoFlags) == kvImageNoError, cur = &b;
            }
        }
        if (ok && rot) {
            c = vbuf(rw, rh);
            static const uint8_t k[4] = { kRotate0DegreesClockwise, kRotate90DegreesClockwise, kRotate180DegreesClockwise, kRotate270DegreesClockwise };
            Pixel_8888 black = { 0, 0, 0, 255 };
            ok = c.data && vImageRotate90_ARGB8888(cur, &c, k[rot], black, kvImageNoFlags) == kvImageNoError;
            cur = &c;
        }
        if (ok) {
            if ((int)dst->width == rw && (int)dst->height == rh) for (int y = 0; y < rh; y++) memcpy((uint8_t *)dst->data + (size_t)y * dst->rowBytes, (uint8_t *)cur->data + (size_t)y * cur->rowBytes, (size_t)rw * 4);
            else ok = vImageScale_ARGB8888(cur, dst, NULL, kvImageNoFlags) == kvImageNoError;
        }
        free(a.data); free(b.data); free(c.data);
    }
    CVPixelBufferUnlockBaseAddress(pb, kCVPixelBufferLock_ReadOnly);
    return ok;
}

/* The frame's luma and interleaved chroma as NV12 (or NV21: Cr first) of dw x dh at dst: a centred crop to the wanted aspect,
 * nearest-neighbour when the sizes differ, as a camera HAL's scaler would give for a smaller stream. */
static bool render_nv(CVPixelBufferRef pb, int dw, int dh, bool nv21, uint8_t *dst)
{
    OSType pf = CVPixelBufferGetPixelFormatType(pb);
    if (pf != kCVPixelFormatType_420YpCbCr8BiPlanarFullRange && pf != kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange) return false;
    CVPixelBufferLockBaseAddress(pb, kCVPixelBufferLock_ReadOnly);
    int sw = (int)CVPixelBufferGetWidth(pb), sh = (int)CVPixelBufferGetHeight(pb);
    const uint8_t *y = CVPixelBufferGetBaseAddressOfPlane(pb, 0), *c = CVPixelBufferGetBaseAddressOfPlane(pb, 1);
    size_t ys = CVPixelBufferGetBytesPerRowOfPlane(pb, 0), cs = CVPixelBufferGetBytesPerRowOfPlane(pb, 1);
    uint8_t *dy = dst, *dc = dst + (size_t)dw * dh;
    /* the crop: the largest dw:dh region of the source */
    int cw = sw, ch = sh;
    if ((long)sw * dh > (long)dw * sh) cw = (int)((long)sh * dw / dh); else ch = (int)((long)sw * dh / dw);
    int cx = (sw - cw) / 2 & ~1, cy = (sh - ch) / 2 & ~1;
    if (cw == dw && ch == dh) {
        for (int r = 0; r < dh; r++) memcpy(dy + (size_t)r * dw, y + (size_t)(cy + r) * ys + cx, (size_t)dw);
        for (int r = 0; r < dh / 2; r++) {
            const uint8_t *s = c + (size_t)(cy / 2 + r) * cs + cx; uint8_t *d = dc + (size_t)r * dw;
            if (!nv21) memcpy(d, s, (size_t)dw);
            else for (int i = 0; i + 1 < dw; i += 2) { d[i] = s[i + 1]; d[i + 1] = s[i]; }
        }
    } else {
        for (int r = 0; r < dh; r++) {
            const uint8_t *s = y + (size_t)(cy + (long)r * ch / dh) * ys + cx; uint8_t *d = dy + (size_t)r * dw;
            for (int i = 0; i < dw; i++) d[i] = s[(long)i * cw / dw];
        }
        for (int r = 0; r < dh / 2; r++) {
            const uint8_t *s = c + (size_t)(cy / 2 + (long)r * (ch / 2) / (dh / 2)) * cs + cx; uint8_t *d = dc + (size_t)r * dw;
            for (int i = 0; i + 1 < dw; i += 2) {
                long sx = ((long)i * cw / dw) & ~1L;
                d[i] = s[sx + (nv21 ? 1 : 0)]; d[i + 1] = s[sx + (nv21 ? 0 : 1)];
            }
        }
    }
    CVPixelBufferUnlockBaseAddress(pb, kCVPixelBufferLock_ReadOnly);
    return true;
}

static void free_data(void *info, const void *data, size_t size) { (void)info; (void)size; free((void *)data); }

/* JPEG bytes of the frame after transform, no larger than maxw x maxh (0: as is); the pixels are turned, so EXIF says upright */
static CFDataRef render_jpeg(CVPixelBufferRef pb, int transform, int quality, int maxw, int maxh)
{
    int w = (int)CVPixelBufferGetWidth(pb), h = (int)CVPixelBufferGetHeight(pb);
    if (transform & 1) { int t = w; w = h; h = t; }
    if (maxw > 0 && maxh > 0 && (w > maxw || h > maxh)) {
        double s = (double)maxw / w < (double)maxh / h ? (double)maxw / w : (double)maxh / h;
        w = (int)(w * s) & ~1; h = (int)(h * s) & ~1;
    }
    vImage_Buffer px = vbuf(w, h);
    if (!px.data || !render_rgba(pb, transform, &px)) { free(px.data); return NULL; }
    CGDataProviderRef prov = CGDataProviderCreateWithData(NULL, px.data, (size_t)w * h * 4, free_data);
    CGColorSpaceRef cs = CGColorSpaceCreateDeviceRGB();
    CGImageRef img = CGImageCreate((size_t)w, (size_t)h, 8, 32, (size_t)w * 4, cs, kCGImageAlphaNoneSkipLast | kCGBitmapByteOrderDefault, prov, NULL, false, kCGRenderingIntentDefault);
    CFMutableDataRef out = CFDataCreateMutable(NULL, 0);
    CGImageDestinationRef dest = img ? CGImageDestinationCreateWithData(out, CFSTR("public.jpeg"), 1, NULL) : NULL;
    bool ok = false;
    if (dest) {
        float q = (quality > 0 && quality <= 100 ? quality : 90) / 100.0f; int upright = 1;
        CFNumberRef qn = CFNumberCreate(NULL, kCFNumberFloatType, &q), on = CFNumberCreate(NULL, kCFNumberIntType, &upright);
        const void *k[] = { kCGImageDestinationLossyCompressionQuality, kCGImagePropertyOrientation }, *v[] = { qn, on };
        CFDictionaryRef props = CFDictionaryCreate(NULL, k, v, 2, &kCFTypeDictionaryKeyCallBacks, &kCFTypeDictionaryValueCallBacks);
        CGImageDestinationAddImage(dest, img, props);
        ok = CGImageDestinationFinalize(dest);
        CFRelease(props); CFRelease(qn); CFRelease(on); CFRelease(dest);
    }
    if (img) CGImageRelease(img);
    CGColorSpaceRelease(cs); CGDataProviderRelease(prov);
    if (!ok) { CFRelease(out); return NULL; }
    return out;
}

/* ---- natives */

#define CAM ((cam *)(uintptr_t)a[0].j)
#define FR ((frame *)(uintptr_t)a[0].j)

NAT(C_count)
{
    (void)self; (void)a;
    void *pool = objc_autoreleasePoolPush();
    int n = (device_at(0) ? 1 : 0) + (device_at(1) ? 1 : 0);
    objc_autoreleasePoolPop(pool);
    *ret = I(n);
    return true;
}
NAT(C_has) { (void)self; void *pool = objc_autoreleasePoolPush(); bool ok = device_at(a[0].i) != NULL; objc_autoreleasePoolPop(pool); *ret = Z(ok); return true; }
/* open(facing) -> a camera handle, 0 when there is no such camera or the user does not allow it */
NAT(C_open)
{
    (void)self;
    avf_load();
    if (!permitted()) { *ret = J(0); return true; }
    pthread_mutex_lock(&g_lock);
    cam *c = NULL;
    for (int i = 0; i < MAX_CAMS && !c; i++) if (!g_cams[i].used) c = &g_cams[i];
    if (c) { memset(c, 0, sizeof(*c)); c->used = true; }
    pthread_mutex_unlock(&g_lock);
    if (!c) { tl_log_line("camera: too many cameras open"); *ret = J(0); return true; }
    c->facing = a[0].i ? 1 : 0; c->trace = trace_on();
    pthread_mutex_init(&c->lock, NULL); pthread_cond_init(&c->cv, NULL);
    if (!session_open(c)) { session_close(c); pthread_mutex_destroy(&c->lock); pthread_cond_destroy(&c->cv); c->used = false; *ret = J(0); return true; }
    tl_log_line("camera: %s camera open, %dx%d", c->facing ? "front" : "back", c->width, c->height);
    *ret = J((int64_t)(uintptr_t)c);
    return true;
}
NAT(C_configure) { (void)self; cam *c = CAM; *ret = Z(c && session_configure(c, a[1].i, a[2].i, a[3].i)); return true; }
NAT(C_size) { (void)self; cam *c = CAM; *ret = I(c ? (c->width << 16) | (c->height & 0xffff) : 0); return true; }
NAT(C_start)
{
    (void)self;
    cam *c = CAM;
    if (!c || !c->session) { *ret = Z(false); return true; }
    if (!c->running) {
        void *pool = objc_autoreleasePoolPush();
        MSG(msg_void)(c->session, SEL("startRunning"));
        c->running = MSG(msg_bool)(c->session, SEL("isRunning"));
        objc_autoreleasePoolPop(pool);
        read_size(c);
        if (!c->running) tl_log_line("camera: the %s camera did not start", c->facing ? "front" : "back");
        else if (c->trace) tl_log_line("camera: %s camera running at %dx%d", c->facing ? "front" : "back", c->width, c->height);
    }
    *ret = Z(c->running);
    return true;
}
NAT(C_stop)
{
    (void)self; (void)ret;
    cam *c = CAM;
    if (c && c->session && c->running) {
        void *pool = objc_autoreleasePoolPush();
        MSG(msg_void)(c->session, SEL("stopRunning"));
        objc_autoreleasePoolPop(pool);
        c->running = false;
        pthread_mutex_lock(&c->lock);
        if (c->latest) CVPixelBufferRelease(c->latest);
        c->latest = NULL;
        pthread_cond_broadcast(&c->cv);
        pthread_mutex_unlock(&c->lock);
    }
    return true;
}
NAT(C_close)
{
    (void)self; (void)ret;
    cam *c = CAM;
    if (!c || !c->used) return true;
    session_close(c);
    if (c->trace) tl_log_line("camera: %s camera closed after %ld frames (%ld not taken)", c->facing ? "front" : "back", c->frames, c->dropped);
    pthread_mutex_destroy(&c->lock); pthread_cond_destroy(&c->cv);
    pthread_mutex_lock(&g_lock); c->used = false; pthread_mutex_unlock(&g_lock);
    return true;
}
/* waitFrame(h, timeoutMs) -> the newest frame (a handle to free), 0 when none came in time */
NAT(C_waitFrame)
{
    (void)self;
    cam *c = CAM;
    if (!c) { *ret = J(0); return true; }
    struct timespec until; clock_gettime(CLOCK_REALTIME, &until);
    int64_t ns = (int64_t)a[1].i * 1000000ll + until.tv_nsec; until.tv_sec += ns / 1000000000ll; until.tv_nsec = ns % 1000000000ll;
    pthread_mutex_lock(&c->lock);
    while (!c->latest) if (pthread_cond_timedwait(&c->cv, &c->lock, &until)) break;
    frame *f = NULL;
    if (c->latest) { f = calloc(1, sizeof(*f)); f->pb = c->latest; f->ts = c->latest_ts; c->latest = NULL; }
    pthread_mutex_unlock(&c->lock);
    *ret = J((int64_t)(uintptr_t)f);
    return true;
}
/* frameInfo(frame, info): info = { timestampNs, width, height } */
NAT(C_frameInfo)
{
    (void)self; (void)ret;
    frame *f = FR; int64_t *info = a[1].l ? (int64_t *)((jobj *)a[1].l)->arr.data : NULL;
    if (f && info && ((jobj *)a[1].l)->arr.len >= 3) { info[0] = f->ts; info[1] = (int64_t)CVPixelBufferGetWidth(f->pb); info[2] = (int64_t)CVPixelBufferGetHeight(f->pb); }
    return true;
}
NAT(C_freeFrame) { (void)self; (void)ret; frame *f = FR; if (f) { CVPixelBufferRelease(f->pb); free(f); } return true; }
/* frameToBitmap(frame, bitmap, transform): the frame transformed and scaled into the Bitmap (RGBA) */
NAT(C_frameToBitmap)
{
    (void)self;
    frame *f = FR;
    tl_abitmap_info bi; void *px = NULL;
    if (!f || !a[1].l || tl_AndroidBitmap_getInfo(NULL, a[1].l, &bi) || tl_AndroidBitmap_lockPixels(NULL, a[1].l, &px) || !px) { *ret = Z(false); return true; }
    vImage_Buffer d = { px, bi.height, bi.width, bi.stride };
    *ret = Z(render_rgba(f->pb, a[2].i, &d));
    return true;
}
/* frameToBuffer(frame, ByteBuffer dst, w, h, transform): RGBA rows of w pixels into a direct buffer (a GL texture's upload) */
NAT(C_frameToBuffer)
{
    (void)self;
    frame *f = FR; void *px = gl_buffer(a[1].l);
    int w = a[2].i, h = a[3].i;
    if (!f || !px || w <= 0 || h <= 0) { *ret = Z(false); return true; }
    vImage_Buffer d = { px, (vImagePixelCount)h, (vImagePixelCount)w, (size_t)w * 4 };
    *ret = Z(render_rgba(f->pb, a[4].i, &d));
    return true;
}
/* frameToYuv(frame, byte[] dst, w, h, nv21): Y then interleaved chroma of w x h (w*h*3/2 bytes) */
NAT(C_frameToYuv)
{
    (void)self;
    frame *f = FR; jobj *b = a[1].l; int w = a[2].i, h = a[3].i;
    if (!f || !b || w <= 0 || h <= 0 || b->arr.len < (uint32_t)(w * h + w * (h / 2))) { *ret = Z(false); return true; }
    *ret = Z(render_nv(f->pb, w, h, a[4].z, b->arr.data));
    return true;
}
/* frameToJpeg(frame, transform, quality, maxW, maxH) -> the encoded bytes, null when encoding failed */
NAT(C_frameToJpeg)
{
    (void)self;
    frame *f = FR;
    CFDataRef d = f ? render_jpeg(f->pb, a[1].i, a[2].i, a[3].i, a[4].i) : NULL;
    if (!d) { *ret = L(NULL); return true; }
    jobj *arr = tl_jni_new_prim_array('B', (uint32_t)CFDataGetLength(d));
    arr->refs = 1u << 30;
    memcpy(arr->arr.data, CFDataGetBytePtr(d), (size_t)CFDataGetLength(d));
    CFRelease(d);
    *ret = L(arr);
    return true;
}
/* ---- controls */
NAT(C_hasFlash) { (void)self; cam *c = CAM; *ret = Z(c && c->device && MSG(msg_bool)(c->device, SEL("hasTorch"))); return true; }
NAT(C_torch)
{
    (void)self;
    cam *c = CAM; bool ok = false;
    if (c && c->device && MSG(msg_bool)(c->device, SEL("hasTorch")) && lock_device(c)) {
        if (!a[1].z || MSG(msg_bool)(c->device, SEL("isTorchAvailable"))) { MSG(msg_void_l)(c->device, SEL("setTorchMode:"), a[1].z ? 1 : 0); ok = true; }
        unlock_device(c);
    }
    *ret = Z(ok);
    return true;
}
/* The point of interest in the frame (0..1 across, 0..1 down); AVFoundation's is in landscape-right coordinates, so the front
 * camera's landscape-left frame is turned around. */
static CGPoint poi(cam *c, float x, float y)
{
    CGPoint p = { x, y };
    if (c->facing) { p.x = 1 - x; p.y = 1 - y; }
    if (p.x < 0) p.x = 0; if (p.x > 1) p.x = 1; if (p.y < 0) p.y = 0; if (p.y > 1) p.y = 1;
    return p;
}
/* focus(h, x, y, mode): mode 0 locked, 1 a one-time scan at the point, 2 continuous (x, y < 0: the centre, no point) */
NAT(C_focus)
{
    (void)self; (void)ret;
    cam *c = CAM; int mode = a[3].i;
    if (!c || !c->device || !lock_device(c)) return true;
    if (a[1].f >= 0 && MSG(msg_bool)(c->device, SEL("isFocusPointOfInterestSupported"))) MSG(msg_void_pt)(c->device, SEL("setFocusPointOfInterest:"), poi(c, a[1].f, a[2].f));
    long m = mode == 0 ? FOCUS_LOCKED : mode == 1 ? FOCUS_AUTO : FOCUS_CONTINUOUS;
    if (MSG(msg_bool_l)(c->device, SEL("isFocusModeSupported:"), m)) MSG(msg_void_l)(c->device, SEL("setFocusMode:"), m);
    unlock_device(c);
    return true;
}
NAT(C_focusing) { (void)self; cam *c = CAM; *ret = Z(c && c->device && MSG(msg_bool)(c->device, SEL("isAdjustingFocus"))); return true; }
NAT(C_canFocus) { (void)self; cam *c = CAM; *ret = Z(c && c->device && MSG(msg_bool_l)(c->device, SEL("isFocusModeSupported:"), FOCUS_AUTO)); return true; }
/* exposure(h, x, y, mode, bias): mode as focus; bias in EV */
NAT(C_exposure)
{
    (void)self; (void)ret;
    cam *c = CAM; int mode = a[3].i;
    if (!c || !c->device || !lock_device(c)) return true;
    if (a[1].f >= 0 && MSG(msg_bool)(c->device, SEL("isExposurePointOfInterestSupported"))) MSG(msg_void_pt)(c->device, SEL("setExposurePointOfInterest:"), poi(c, a[1].f, a[2].f));
    long m = mode == 0 ? 0 : mode == 1 ? 1 : 2;
    if (MSG(msg_bool_l)(c->device, SEL("isExposureModeSupported:"), m)) MSG(msg_void_l)(c->device, SEL("setExposureMode:"), m);
    float bias = a[4].f, lo = MSG(msg_float)(c->device, SEL("minExposureTargetBias")), hi = MSG(msg_float)(c->device, SEL("maxExposureTargetBias"));
    if (bias < lo) bias = lo; if (bias > hi) bias = hi;
    MSG(msg_void_f_p)(c->device, SEL("setExposureTargetBias:completionHandler:"), bias, NULL);
    unlock_device(c);
    return true;
}
NAT(C_maxZoom)
{
    (void)self;
    cam *c = CAM; float z = 1;
    if (c && c->device) { id fmt = MSG(msg_id)(c->device, SEL("activeFormat")); if (fmt) z = (float)MSG(msg_double)(fmt, SEL("videoMaxZoomFactor")); }
    *ret = F(z > 1 ? z : 1);
    return true;
}
NAT(C_zoom)
{
    (void)self; (void)ret;
    cam *c = CAM; double z = a[1].f;
    if (!c || !c->device || !lock_device(c)) return true;
    id fmt = MSG(msg_id)(c->device, SEL("activeFormat"));
    double max = fmt ? MSG(msg_double)(fmt, SEL("videoMaxZoomFactor")) : 1;
    if (z < 1) z = 1; if (z > max) z = max;
    MSG(msg_void_d)(c->device, SEL("setVideoZoomFactor:"), z);
    unlock_device(c);
    return true;
}
NAT(C_fps)
{
    (void)self; (void)ret;
    cam *c = CAM; int lo = a[1].i, hi = a[2].i;
    if (!c || !c->device || lo <= 0 || hi < lo || !lock_device(c)) return true;
    MSG(msg_void_tm)(c->device, SEL("setActiveVideoMinFrameDuration:"), CMTimeMake(1, hi));
    MSG(msg_void_tm)(c->device, SEL("setActiveVideoMaxFrameDuration:"), CMTimeMake(1, lo));
    unlock_device(c);
    return true;
}

static const struct { const char *name, *sig; dvm_native_fn fn; } k_camera[] = {
    { "count", "()I", C_count },
    { "has", "(I)Z", C_has },
    { "open", "(I)J", C_open },
    { "configure", "(JIII)Z", C_configure },
    { "size", "(J)I", C_size },
    { "start", "(J)Z", C_start },
    { "stop", "(J)V", C_stop },
    { "close", "(J)V", C_close },
    { "waitFrame", "(JI)J", C_waitFrame },
    { "frameInfo", "(J[J)V", C_frameInfo },
    { "freeFrame", "(J)V", C_freeFrame },
    { "frameToBitmap", "(JLandroid/graphics/Bitmap;I)Z", C_frameToBitmap },
    { "frameToBuffer", "(JLjava/nio/ByteBuffer;III)Z", C_frameToBuffer },
    { "frameToYuv", "(J[BIIZ)Z", C_frameToYuv },
    { "frameToJpeg", "(JIIII)[B", C_frameToJpeg },
    { "hasFlash", "(J)Z", C_hasFlash },
    { "torch", "(JZ)Z", C_torch },
    { "focus", "(JFFI)V", C_focus },
    { "focusing", "(J)Z", C_focusing },
    { "canFocus", "(J)Z", C_canFocus },
    { "exposure", "(JFFIF)V", C_exposure },
    { "maxZoom", "(J)F", C_maxZoom },
    { "zoom", "(JF)V", C_zoom },
    { "fps", "(JII)V", C_fps },
    { NULL, NULL, NULL },
};
dvm_native_fn tl_camera_native(const char *name, const char *sig);
dvm_native_fn tl_camera_native(const char *name, const char *sig)
{
    for (int i = 0; k_camera[i].name; i++) if (!strcmp(k_camera[i].name, name) && !strcmp(k_camera[i].sig, sig)) return k_camera[i].fn;
    return NULL;
}
