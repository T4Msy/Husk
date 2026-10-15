package android.media;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import java.io.FileDescriptor;
import java.util.Map;

/**
 * Music, voice notes and video: sound is decoded as it plays into a stream of Husk's mixer from a thread of the player's own --
 * through husk.Audio's decoder (Ogg Vorbis, MP3, AAC, WAV...) or, for what that cannot read (Ogg Opus, MP4 with Opus), through
 * MediaExtractor's demuxer and a MediaCodec decoder. A file with a video track gets a second thread that decodes its frames
 * with MediaCodec into the Surface the app set, each shown when the sound's clock (or a wall clock, for silent video) reaches it.
 */
public class MediaPlayer implements AudioRouting {
    public interface OnCompletionListener { void onCompletion(MediaPlayer mp); }
    public interface OnPreparedListener { void onPrepared(MediaPlayer mp); }
    public interface OnErrorListener { boolean onError(MediaPlayer mp, int what, int extra); }
    public interface OnSeekCompleteListener { void onSeekComplete(MediaPlayer mp); }
    public interface OnInfoListener { boolean onInfo(MediaPlayer mp, int what, int extra); }
    public interface OnBufferingUpdateListener { void onBufferingUpdate(MediaPlayer mp, int percent); }
    public interface OnVideoSizeChangedListener { void onVideoSizeChanged(MediaPlayer mp, int w, int h); }
    public interface OnTimedTextListener { void onTimedText(MediaPlayer mp, TimedText text); }
    public static final int MEDIA_ERROR_UNKNOWN = 1, MEDIA_ERROR_SERVER_DIED = 100, MEDIA_ERROR_NOT_VALID_FOR_PROGRESSIVE_PLAYBACK = 200, MEDIA_ERROR_IO = -1004, MEDIA_ERROR_MALFORMED = -1007, MEDIA_ERROR_UNSUPPORTED = -1010, MEDIA_ERROR_TIMED_OUT = -110;
    public static final int MEDIA_INFO_UNKNOWN = 1, MEDIA_INFO_VIDEO_RENDERING_START = 3, MEDIA_INFO_BUFFERING_START = 701, MEDIA_INFO_BUFFERING_END = 702, MEDIA_INFO_NOT_SEEKABLE = 801;
    public static final int SEEK_PREVIOUS_SYNC = 0, SEEK_NEXT_SYNC = 1, SEEK_CLOSEST_SYNC = 2, SEEK_CLOSEST = 3;
    public static final int VIDEO_SCALING_MODE_SCALE_TO_FIT = 1, VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING = 2;
    private static final int IDLE = 0, INITIALIZED = 1, PREPARING = 2, PREPARED = 3, STARTED = 4, PAUSED = 5, COMPLETED = 6, STOPPED = 7, ERROR = 8, END = 9;
    /** Where the sound comes from: 16-bit interleaved frames at rate/channels. */
    private interface Src { int read(short[] buf); void seek(int ms); void close(); }
    private final Object mLock = new Object();
    private final Handler mMain;
    private int mState = IDLE;
    private byte[] mBytes; private int mFd = -1; private long mFdOff, mFdLen;
    private Src mSrc; private int mRate, mChannels, mDuration;
    private int mTrack = -1;
    private Thread mThread;
    private volatile boolean mLooping, mStopThread;
    private volatile int mSeekTo = -1;
    private long mPosBase; private int mPosBaseMs;
    private float mL = 1, mR = 1;
    private int mSession;
    /* video */
    private android.view.Surface mSurface;
    private long mDemux; private int mVideoTrack = -1, mVideoW, mVideoH, mRotation, mScaling = VIDEO_SCALING_MODE_SCALE_TO_FIT;
    private Thread mVideoThread;
    private volatile int mVideoSeekTo = -1;
    private long mWallStartNs; private int mWallBaseMs;                   /* the clock of a video without sound */
    private OnCompletionListener mOnCompletion; private OnPreparedListener mOnPrepared; private OnErrorListener mOnError; private OnSeekCompleteListener mOnSeek; private OnInfoListener mOnInfo; private OnVideoSizeChangedListener mOnVideoSize; private MediaPlayer mNext;
    public MediaPlayer() { Looper l = Looper.myLooper(); mMain = new Handler(l != null ? l : Looper.getMainLooper()); }
    public MediaPlayer(Context c) { this(); }
    public static MediaPlayer create(Context c, int resId) { return create(c, resId, null, 0); }
    public static MediaPlayer create(Context c, int resId, AudioAttributes a, int session) {
        try { MediaPlayer mp = new MediaPlayer(); try (java.io.InputStream in = c.getResources().openRawResource(resId)) { mp.mBytes = husk.Audio.readAll(in); } mp.mState = INITIALIZED; mp.prepare(); return mp; }
        catch (Exception e) { android.util.Log.w("MediaPlayer", "create failed for resource " + resId + ": " + e); return null; }
    }
    public static MediaPlayer create(Context c, Uri u) { return create(c, u, null); }
    public static MediaPlayer create(Context c, Uri u, android.view.SurfaceHolder h) { return create(c, u, h, null, 0); }
    public static MediaPlayer create(Context c, Uri u, android.view.SurfaceHolder h, AudioAttributes a, int session) {
        try { MediaPlayer mp = new MediaPlayer(); mp.setDataSource(c, u); if (h != null) mp.setDisplay(h); mp.prepare(); return mp; }
        catch (Exception e) { android.util.Log.w("MediaPlayer", "create failed for " + u + ": " + e); return null; }
    }
    private void checkIdle() { if (mState != IDLE) throw new IllegalStateException("setDataSource called in state " + mState); }
    public void setDataSource(String path) throws java.io.IOException {
        checkIdle();
        if (path.startsWith("http://") || path.startsWith("https://")) { try (java.io.InputStream in = new java.net.URL(path).openStream()) { mBytes = husk.Audio.readAll(in); } }
        else if (path.startsWith("file://")) setDataSource(path.substring(7));
        else { try (java.io.FileInputStream in = new java.io.FileInputStream(path)) { mBytes = husk.Audio.readAll(in); } }
        mState = INITIALIZED;
    }
    public void setDataSource(String path, Map<String, String> headers) throws java.io.IOException { setDataSource(path); }
    public void setDataSource(Context c, Uri u) throws java.io.IOException {
        checkIdle();
        String s = u.getScheme();
        if (s == null || "file".equals(s)) { setDataSource(u.getPath()); return; }
        if ("http".equals(s) || "https".equals(s)) { setDataSource(u.toString()); return; }
        mBytes = husk.Audio.bytesOf(c, u);
        mState = INITIALIZED;
    }
    public void setDataSource(Context c, Uri u, Map<String, String> headers) throws java.io.IOException { setDataSource(c, u); }
    public void setDataSource(Context c, Uri u, Map<String, String> headers, java.util.List<?> cookies) throws java.io.IOException { setDataSource(c, u); }
    public void setDataSource(FileDescriptor fd) throws java.io.IOException { setDataSource(fd, 0, -1); }
    public void setDataSource(FileDescriptor fd, long offset, long length) throws java.io.IOException {
        checkIdle();
        int n = husk.Audio.fdOf(fd);
        if (n < 0) throw new java.io.IOException("not a file descriptor Husk can read");
        mFd = n; mFdOff = offset; mFdLen = length; mState = INITIALIZED;
    }
    public void setDataSource(AssetFileDescriptor afd) throws java.io.IOException {
        if (afd.getFileDescriptor() != null && husk.Audio.fdOf(afd.getFileDescriptor()) >= 0) setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
        else if (afd.huskAssetName() != null) { checkIdle(); mBytes = husk.Native.readAsset(afd.huskAssetName()); if (mBytes == null) throw new java.io.IOException("no asset " + afd.huskAssetName()); mState = INITIALIZED; }
        else throw new java.io.IOException("unreadable AssetFileDescriptor");
    }
    public void setDataSource(MediaDataSource ds) throws java.io.IOException {
        checkIdle();
        long size = ds.getSize();
        if (size <= 0 || size > (256 << 20)) throw new java.io.IOException("media data source of unknown size");
        mBytes = new byte[(int) size];
        int off = 0;
        while (off < size) { int r = ds.readAt(off, mBytes, off, (int) size - off); if (r <= 0) break; off += r; }
        mState = INITIALIZED;
    }
    public void prepare() throws java.io.IOException {
        if (mState != INITIALIZED && mState != STOPPED) throw new IllegalStateException("prepare called in state " + mState);
        open();
    }
    /** husk.Audio's decoder over the file. */
    private final class NativeSrc implements Src {
        private long mDec;
        NativeSrc(long dec) { mDec = dec; }
        public int read(short[] buf) { return husk.Audio.decRead(mDec, buf); }
        public void seek(int ms) { husk.Audio.decSeek(mDec, ms); }
        public void close() { husk.Audio.decClose(mDec); }
    }
    /** The demuxer and a MediaCodec decoder over an audio track: Opus and whatever else husk.Audio cannot read. */
    private final class CodecSrc implements Src {
        private final long mDm; private final int mTrack; private final MediaCodec mDec; private final byte[] mTmp;
        private final MediaCodec.BufferInfo mInfo = new MediaCodec.BufferInfo();
        private java.nio.ByteBuffer mLeft; private int mLeftIndex = -1; private boolean mInEos, mOutEos;
        final int rate, channels;
        CodecSrc(long dm, int track) throws java.io.IOException {
            mDm = dm; mTrack = track;
            MediaFormat f = husk.MediaDemux.format(dm, track);
            MediaCodec d = MediaCodec.createDecoderByType(f.getString(MediaFormat.KEY_MIME));
            try { d.configure(f, null, null, 0); } catch (RuntimeException e) { d.release(); throw new java.io.IOException("the audio track cannot be decoded: " + e); }
            d.start(); mDec = d;
            mTmp = new byte[Math.max(4096, f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 0))];
            husk.MediaDemux.select(dm, track, true);
            MediaFormat o = d.getOutputFormat();
            rate = o.getInteger(MediaFormat.KEY_SAMPLE_RATE, f.getInteger(MediaFormat.KEY_SAMPLE_RATE, 48000)); channels = o.getInteger(MediaFormat.KEY_CHANNEL_COUNT, f.getInteger(MediaFormat.KEY_CHANNEL_COUNT, 1));
        }
        public int read(short[] buf) {
            int done = 0;
            while (done < buf.length) {
                if (mLeft != null && mLeft.remaining() >= 2) {
                    int n = Math.min(buf.length - done, mLeft.remaining() / 2);
                    mLeft.asShortBuffer().get(buf, done, n); mLeft.position(mLeft.position() + n * 2); done += n;
                    continue;
                }
                if (mLeftIndex >= 0) { mDec.releaseOutputBuffer(mLeftIndex, false); mLeftIndex = -1; mLeft = null; }
                if (mOutEos) break;
                if (!mInEos) {
                    int i = mDec.dequeueInputBuffer(0);
                    if (i >= 0) {
                        long size = husk.MediaDemux.sampleSize(mDm);
                        if (size < 0) { mDec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); mInEos = true; }
                        else {
                            int n = husk.MediaDemux.readSample(mDm, mTmp, 0);
                            if (n > 0) { java.nio.ByteBuffer b = mDec.getInputBuffer(i); b.clear(); b.put(mTmp, 0, Math.min(n, b.capacity())); mDec.queueInputBuffer(i, 0, Math.min(n, b.capacity()), husk.MediaDemux.sampleTime(mDm), 0); }
                            else mDec.queueInputBuffer(i, 0, 0, 0, 0);
                            husk.MediaDemux.advance(mDm);
                        }
                    }
                }
                int o = mDec.dequeueOutputBuffer(mInfo, mInEos ? 10000 : 0);
                if (o >= 0) {
                    if ((mInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) mOutEos = true;
                    java.nio.ByteBuffer b = mDec.getOutputBuffer(o);
                    if (b != null && mInfo.size > 0) { b.position(mInfo.offset); b.limit(mInfo.offset + mInfo.size); mLeft = b.slice().order(java.nio.ByteOrder.nativeOrder()); mLeftIndex = o; }
                    else mDec.releaseOutputBuffer(o, false);
                } else if (o == MediaCodec.INFO_TRY_AGAIN_LATER && mInEos && done == 0) { if (mDec.dequeueOutputBuffer(mInfo, 50000) == MediaCodec.INFO_TRY_AGAIN_LATER) mOutEos = true; }
            }
            return done;
        }
        public void seek(int ms) {
            if (mLeftIndex >= 0) { mDec.releaseOutputBuffer(mLeftIndex, false); mLeftIndex = -1; } mLeft = null;
            mDec.flush(); mInEos = mOutEos = false;
            husk.MediaDemux.seek(mDm, ms * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
        }
        public void close() { mDec.release(); husk.MediaDemux.close(mDm); }
    }
    private long openDemux() {
        return mBytes != null && mBytes.length > 0 ? husk.MediaDemux.openBytes(mBytes) : mFd >= 0 ? husk.MediaDemux.open(mFd, mFdOff, mFdLen > 0 ? mFdLen : 0) : 0;
    }
    private void open() throws java.io.IOException {
        long d = mBytes != null ? husk.Audio.decOpen(mBytes) : mFd >= 0 ? husk.Audio.decOpenFd(mFd, mFdOff, mFdLen) : 0;
        Src src = null; int rate = 0, channels = 0, duration = 0;
        if (d != 0) { int[] info = new int[3]; husk.Audio.decInfo(d, info); src = new NativeSrc(d); rate = info[0]; channels = info[1]; duration = info[2]; }
        long dm = openDemux();
        int vt = dm != 0 ? husk.MediaDemux.trackOfType(dm, husk.MediaDemux.TYPE_VIDEO) : -1;
        if (src == null && dm != 0) {
            int at = husk.MediaDemux.trackOfType(dm, husk.MediaDemux.TYPE_AUDIO);
            long dm2 = at >= 0 ? openDemux() : 0;
            if (dm2 != 0) { try { CodecSrc cs = new CodecSrc(dm2, at); src = cs; rate = cs.rate; channels = cs.channels; } catch (java.io.IOException e) { husk.MediaDemux.close(dm2); android.util.Log.w("MediaPlayer", e.toString()); } }
        }
        if (dm != 0) { duration = Math.max(duration, (int) (husk.MediaDemux.duration(dm) / 1000)); if (vt < 0) { husk.MediaDemux.close(dm); dm = 0; } }
        if (src == null && dm == 0) { mState = ERROR; throw new java.io.IOException("Prepare failed.: status=0x1"); }
        synchronized (mLock) {
            if (mSrc != null) mSrc.close();
            if (mDemux != 0) husk.MediaDemux.close(mDemux);
            mSrc = src; mRate = rate; mChannels = channels; mDuration = duration; mState = PREPARED; mPosBase = 0; mPosBaseMs = 0; mWallBaseMs = 0;
            mDemux = dm; mVideoTrack = vt;
            if (dm != 0) {
                mVideoW = (int) husk.MediaDemux.trackInfo(dm, vt, husk.MediaDemux.WIDTH); mVideoH = (int) husk.MediaDemux.trackInfo(dm, vt, husk.MediaDemux.HEIGHT);
                mRotation = (int) husk.MediaDemux.trackInfo(dm, vt, husk.MediaDemux.ROTATION);
            } else { mVideoW = mVideoH = mRotation = 0; }
        }
        if (dm != 0) mMain.post(() -> { if (mOnVideoSize != null) mOnVideoSize.onVideoSizeChanged(this, getVideoWidth(), getVideoHeight()); });
    }
    public void prepareAsync() {
        if (mState != INITIALIZED && mState != STOPPED) throw new IllegalStateException("prepareAsync called in state " + mState);
        mState = PREPARING;
        new Thread(() -> {
            try { open(); mMain.post(() -> { if (mOnPrepared != null) mOnPrepared.onPrepared(this); }); }
            catch (java.io.IOException e) { mMain.post(() -> fail(MEDIA_ERROR_UNKNOWN, MEDIA_ERROR_IO)); }
        }, "MediaPlayer prepare").start();
    }
    private void fail(int what, int extra) {
        mState = ERROR;
        boolean handled = mOnError != null && mOnError.onError(this, what, extra);
        if (!handled && mOnCompletion != null) mOnCompletion.onCompletion(this);
    }
    public void start() {
        synchronized (mLock) {
            if (mState == COMPLETED) { if (mSrc != null) mSrc.seek(0); resetPosition(0); mVideoSeekTo = 0; }
            if (mState != PREPARED && mState != PAUSED && mState != COMPLETED && mState != STARTED) throw new IllegalStateException("start called in state " + mState);
            if (mSrc != null) {
                if (mTrack < 0) { mTrack = husk.Audio.trackOpen(mRate, mChannels); husk.Audio.trackVolume(mTrack, mL, mR); }
                husk.Audio.trackPlay(mTrack, true);
            }
            if (mState != STARTED) mWallStartNs = System.nanoTime();
            mState = STARTED;
            if (mThread == null && mSrc != null) {
                mStopThread = false;
                mThread = new Thread(this::pump, "MediaPlayer");
                mThread.setDaemon(true);
                mThread.start();
            }
            if (mVideoThread == null && mDemux != 0 && mSurface != null) {
                mStopThread = false;
                final long dm = mDemux; final int vt = mVideoTrack; final android.view.Surface s = mSurface;
                mDemux = 0;                                                /* the thread owns it from here */
                mVideoThread = new Thread(() -> videoPump(dm, vt, s), "MediaPlayer video");
                mVideoThread.setDaemon(true);
                mVideoThread.start();
            }
            mLock.notifyAll();
        }
    }
    /** The player's thread: decode, write (paced by the mixer), loop or finish. */
    private void pump() {
        short[] buf = new short[4096 * Math.max(1, mChannels)];
        for (;;) {
            Src src; int track;
            synchronized (mLock) {
                while (!mStopThread && mState != STARTED) { try { mLock.wait(); } catch (InterruptedException e) { return; } }
                if (mStopThread) return;
                if (mSeekTo >= 0) { husk.Audio.trackFlush(mTrack); mSrc.seek(mSeekTo); resetPosition(mSeekTo); mSeekTo = -1; mMain.post(() -> { if (mOnSeek != null) mOnSeek.onSeekComplete(this); }); }
                src = mSrc; track = mTrack;
            }
            int n = src.read(buf);
            if (n <= 0) {
                if (mLooping) { synchronized (mLock) { mSrc.seek(0); resetPosition(0); mVideoSeekTo = 0; } continue; }
                // let what is queued play out, then finish
                while (husk.Audio.trackPending(track) > 0 && !mStopThread && mState == STARTED) { try { Thread.sleep(20); } catch (InterruptedException e) { return; } }
                synchronized (mLock) { if (mState == STARTED) mState = COMPLETED; mThread = null; }
                mMain.post(() -> {
                    if (mNext != null) { try { mNext.start(); } catch (IllegalStateException e) {} }
                    if (mOnCompletion != null) mOnCompletion.onCompletion(this);
                });
                return;
            }
            int off = 0;
            while (off < n && !mStopThread) {
                int w = husk.Audio.trackWrite(track, buf, off, n - off, true);
                if (w <= 0) { if (mState != STARTED) { synchronized (mLock) { while (!mStopThread && mState != STARTED && mSeekTo < 0) { try { mLock.wait(); } catch (InterruptedException e) { return; } } } if (mSeekTo >= 0) break; continue; } break; }
                off += w;
            }
        }
    }
    /** The video thread: samples through a MediaCodec into the Surface, each frame held until the clock reaches its time. */
    private void videoPump(long dm, int vt, android.view.Surface surface) {
        MediaCodec dec = null; boolean first = true;
        try {
            MediaFormat f = husk.MediaDemux.format(dm, vt);
            dec = MediaCodec.createDecoderByType(f.getString(MediaFormat.KEY_MIME));
            dec.configure(f, surface, null, 0); dec.setVideoScalingMode(mScaling); dec.start();
            husk.MediaDemux.select(dm, vt, true);
            int startMs; synchronized (mLock) { startMs = mVideoSeekTo >= 0 ? mVideoSeekTo : getCurrentPosition(); mVideoSeekTo = -1; }
            if (startMs > 0) husk.MediaDemux.seek(dm, startMs * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            byte[] tmp = new byte[Math.max(4096, f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 0))];
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inEos = false;
            for (;;) {
                synchronized (mLock) {
                    while (!mStopThread && mState != STARTED && mVideoSeekTo < 0) { try { mLock.wait(); } catch (InterruptedException e) { return; } }
                    if (mStopThread) return;
                    if (mVideoSeekTo >= 0) { husk.MediaDemux.seek(dm, mVideoSeekTo * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC); dec.flush(); inEos = false; mVideoSeekTo = -1; if (mState != STARTED) continue; }
                }
                if (!inEos) {
                    int i = dec.dequeueInputBuffer(5000);
                    if (i >= 0) {
                        long size = husk.MediaDemux.sampleSize(dm);
                        if (size < 0) { dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inEos = true; }
                        else {
                            int n = husk.MediaDemux.readSample(dm, tmp, 0);
                            java.nio.ByteBuffer b = dec.getInputBuffer(i);
                            if (n > 0 && b != null) { b.clear(); n = Math.min(n, b.capacity()); b.put(tmp, 0, n); dec.queueInputBuffer(i, 0, n, husk.MediaDemux.sampleTime(dm), 0); }
                            else dec.queueInputBuffer(i, 0, 0, 0, 0);
                            husk.MediaDemux.advance(dm);
                        }
                    }
                }
                int o = dec.dequeueOutputBuffer(info, 5000);
                if (o < 0) continue;
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0 && info.size == 0) {
                    dec.releaseOutputBuffer(o, false);
                    if (mLooping && mSrc == null) { husk.MediaDemux.seek(dm, 0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC); dec.flush(); inEos = false; synchronized (mLock) { resetPosition(0); mWallBaseMs = 0; mWallStartNs = System.nanoTime(); } continue; }
                    if (mSrc == null) {                                    /* no sound: this thread ends the playback */
                        synchronized (mLock) { if (mState == STARTED) mState = COMPLETED; mVideoThread = null; }
                        mMain.post(() -> { if (mNext != null) { try { mNext.start(); } catch (IllegalStateException e) {} } if (mOnCompletion != null) mOnCompletion.onCompletion(this); });
                    }
                    return;
                }
                long ptsMs = info.presentationTimeUs / 1000;
                while (!mStopThread && mState == STARTED && mVideoSeekTo < 0) {
                    long now = getCurrentPosition();
                    if (now >= ptsMs - 5) break;
                    try { Thread.sleep(Math.min(20, ptsMs - now)); } catch (InterruptedException e) { return; }
                }
                boolean show = !mStopThread && mVideoSeekTo < 0 && (first || getCurrentPosition() - ptsMs < 120);
                dec.releaseOutputBuffer(o, show);
                if (show && first) { first = false; mMain.post(() -> { if (mOnInfo != null) mOnInfo.onInfo(this, MEDIA_INFO_VIDEO_RENDERING_START, 0); }); }
            }
        } catch (Exception e) {
            android.util.Log.w("MediaPlayer", "video stopped: " + e);
        } finally {
            if (dec != null) dec.release();
            husk.MediaDemux.close(dm);
            synchronized (mLock) { if (mVideoThread == Thread.currentThread()) mVideoThread = null; }
        }
    }
    private void resetPosition(int ms) { mPosBase = mTrack >= 0 ? husk.Audio.trackPosition(mTrack) : 0; mPosBaseMs = ms; mWallBaseMs = ms; mWallStartNs = System.nanoTime(); }
    public void pause() { synchronized (mLock) { if (mState == STARTED) { mWallBaseMs = wallMs(); mState = PAUSED; if (mTrack >= 0) husk.Audio.trackPlay(mTrack, false); } } }
    public void stop() {
        synchronized (mLock) {
            if (mState == IDLE || mState == END || mState == ERROR) return;
            mStopThread = true; mLock.notifyAll();
            if (mTrack >= 0) { husk.Audio.trackFlush(mTrack); husk.Audio.trackPlay(mTrack, false); }
            if (mSrc != null) { mSrc.close(); mSrc = null; }
            if (mDemux != 0) { husk.MediaDemux.close(mDemux); mDemux = 0; }
            mThread = null; mVideoThread = null;
            mState = STOPPED;
        }
    }
    public void reset() {
        stop();
        synchronized (mLock) { if (mTrack >= 0) { husk.Audio.trackClose(mTrack); mTrack = -1; } mBytes = null; mFd = -1; mState = IDLE; mSeekTo = -1; mVideoSeekTo = -1; mVideoTrack = -1; mVideoW = mVideoH = mRotation = 0; }
    }
    public void release() { reset(); mState = END; mOnCompletion = null; mOnPrepared = null; mOnError = null; mOnSeek = null; mOnVideoSize = null; mSurface = null; }
    public boolean isPlaying() { return mState == STARTED; }
    public boolean isLooping() { return mLooping; }
    public void setLooping(boolean l) { mLooping = l; }
    public void setNextMediaPlayer(MediaPlayer next) { mNext = next; }
    public void setVolume(float l, float r) { mL = Math.max(0, Math.min(1, l)); mR = Math.max(0, Math.min(1, r)); if (mTrack >= 0) husk.Audio.trackVolume(mTrack, mL, mR); }
    public void setVolume(float v) { setVolume(v, v); }
    public void seekTo(int ms) {
        synchronized (mLock) {
            if (mSrc == null && mDemux == 0 && mVideoThread == null) return;
            ms = Math.max(0, mDuration > 0 ? Math.min(ms, mDuration) : ms);
            if (mVideoThread != null) mVideoSeekTo = ms;
            if (mSrc != null && (mState == STARTED || mThread != null)) { mSeekTo = ms; mLock.notifyAll(); }
            else { if (mSrc != null) { mSrc.seek(ms); if (mTrack >= 0) husk.Audio.trackFlush(mTrack); } resetPosition(ms); mLock.notifyAll(); mMain.post(() -> { if (mOnSeek != null) mOnSeek.onSeekComplete(this); }); }
            if (mState == COMPLETED) mState = PAUSED;
        }
    }
    public void seekTo(long ms, int mode) { seekTo((int) ms); }
    private int wallMs() { long p = mWallBaseMs + (mState == STARTED ? (System.nanoTime() - mWallStartNs) / 1000000 : 0); return (int) Math.min(mDuration > 0 ? mDuration : Integer.MAX_VALUE, p); }
    public int getCurrentPosition() {
        synchronized (mLock) {
            if (mSeekTo >= 0) return mSeekTo;
            if (mState == COMPLETED) return mDuration;
            if (mSrc == null) return mVideoSeekTo >= 0 ? mVideoSeekTo : wallMs();
            if (mTrack < 0 || mRate <= 0) return mPosBaseMs;
            long frames = husk.Audio.trackPosition(mTrack) - mPosBase;
            return (int) Math.min(mDuration > 0 ? mDuration : Integer.MAX_VALUE, mPosBaseMs + frames * 1000L / mRate);
        }
    }
    public int getDuration() { return mState == IDLE || mState == INITIALIZED || mState == ERROR ? -1 : mDuration; }
    public int getVideoWidth() { return mRotation == 90 || mRotation == 270 ? mVideoH : mVideoW; }
    public int getVideoHeight() { return mRotation == 90 || mRotation == 270 ? mVideoW : mVideoH; }
    public void setDisplay(android.view.SurfaceHolder h) { setSurface(h != null ? h.getSurface() : null); }
    public void setSurface(android.view.Surface s) {
        synchronized (mLock) { mSurface = s; }
        if (s != null && mState == STARTED) start();                      /* a video given its surface while playing: its thread starts now */
    }
    public void setVideoScalingMode(int m) { mScaling = m; }
    public void setScreenOnWhilePlaying(boolean b) {}
    public void setWakeMode(Context c, int mode) {}
    @Deprecated public void setAudioStreamType(int t) {}
    public void setAudioAttributes(AudioAttributes a) {}
    public void setAudioSessionId(int id) { mSession = id; }
    public int getAudioSessionId() { return mSession; }
    public void setAuxEffectSendLevel(float l) {}
    public void attachAuxEffect(int id) {}
    public void setPlaybackParams(PlaybackParams p) {}
    public PlaybackParams getPlaybackParams() { return new PlaybackParams(); }
    public MediaTimestamp getTimestamp() { return new MediaTimestamp(getCurrentPosition() * 1000L, System.nanoTime(), isPlaying() ? 1f : 0f); }
    public TrackInfo[] getTrackInfo() { return new TrackInfo[] { new TrackInfo() }; }
    public void selectTrack(int i) {} public void deselectTrack(int i) {}
    public void addTimedTextSource(String path, String mime) {}
    public void setOnCompletionListener(OnCompletionListener l) { mOnCompletion = l; }
    public void setOnPreparedListener(OnPreparedListener l) { mOnPrepared = l; }
    public void setOnErrorListener(OnErrorListener l) { mOnError = l; }
    public void setOnSeekCompleteListener(OnSeekCompleteListener l) { mOnSeek = l; }
    public void setOnInfoListener(OnInfoListener l) { mOnInfo = l; }
    public void setOnBufferingUpdateListener(OnBufferingUpdateListener l) {}
    public void setOnVideoSizeChangedListener(OnVideoSizeChangedListener l) { mOnVideoSize = l; }
    public void setOnTimedTextListener(OnTimedTextListener l) {}
    public AudioDeviceInfo getRoutedDevice() { return AudioDeviceInfo.huskSpeaker(); }
    public boolean setPreferredDevice(AudioDeviceInfo d) { return true; }
    public AudioDeviceInfo getPreferredDevice() { return null; }
    public void addOnRoutingChangedListener(AudioRouting.OnRoutingChangedListener l, android.os.Handler h) {}
    public void removeOnRoutingChangedListener(AudioRouting.OnRoutingChangedListener l) {}
    @Override protected void finalize() throws Throwable { try { if (mState != END) release(); } finally { super.finalize(); } }
    public static class TrackInfo { public static final int MEDIA_TRACK_TYPE_UNKNOWN = 0, MEDIA_TRACK_TYPE_VIDEO = 1, MEDIA_TRACK_TYPE_AUDIO = 2, MEDIA_TRACK_TYPE_TIMEDTEXT = 3, MEDIA_TRACK_TYPE_SUBTITLE = 4; public int getTrackType() { return MEDIA_TRACK_TYPE_AUDIO; } public String getLanguage() { return "und"; } public MediaFormat getFormat() { return null; }
        // ---- generated by tools/compat/fillmembers.py (TrackInfo): the platform's members this class does not write (signatures only)
        public static final int MEDIA_TRACK_TYPE_METADATA = 5;
        public int describeContents() { return 0; }
        public boolean hasHapticChannels() { return false; }
        public void writeToParcel(android.os.Parcel p0, int p1) {}
        // ---- end of generated members (TrackInfo)
    }
    // ---- generated by tools/compat/fillmembers.py: the platform's members this class does not write (signatures only)
    private final java.util.HashMap<String, Object> huskFill = new java.util.HashMap<>();
    public static final boolean APPLY_METADATA_FILTER = true;
    public static final boolean BYPASS_METADATA_FILTER = false;
    public static final int MEDIA_ERROR_SYSTEM = -2147483648;
    public static final int MEDIA_INFO_AUDIO_NOT_PLAYING = 804;
    public static final int MEDIA_INFO_BAD_INTERLEAVING = 800;
    public static final int MEDIA_INFO_EXTERNAL_METADATA_UPDATE = 803;
    public static final int MEDIA_INFO_METADATA_UPDATE = 802;
    public static final int MEDIA_INFO_NETWORK_BANDWIDTH = 703;
    public static final int MEDIA_INFO_STARTED_AS_NEXT = 2;
    public static final int MEDIA_INFO_SUBTITLE_TIMED_OUT = 902;
    public static final int MEDIA_INFO_TIMED_TEXT_ERROR = 900;
    public static final int MEDIA_INFO_UNSUPPORTED_SUBTITLE = 901;
    public static final int MEDIA_INFO_VIDEO_NOT_PLAYING = 805;
    public static final int MEDIA_INFO_VIDEO_TRACK_LAGGING = 700;
    public static final java.lang.String MEDIA_MIMETYPE_TEXT_CEA_608 = "text/cea-608";
    public static final java.lang.String MEDIA_MIMETYPE_TEXT_CEA_708 = "text/cea-708";
    public static final java.lang.String MEDIA_MIMETYPE_TEXT_SUBRIP = "application/x-subrip";
    public static final java.lang.String MEDIA_MIMETYPE_TEXT_VTT = "text/vtt";
    public static final boolean METADATA_ALL = false;
    public static final boolean METADATA_UPDATE_ONLY = true;
    public static final int PLAYBACK_RATE_AUDIO_MODE_DEFAULT = 0;
    public static final int PLAYBACK_RATE_AUDIO_MODE_RESAMPLE = 2;
    public static final int PLAYBACK_RATE_AUDIO_MODE_STRETCH = 1;
    public static final int PREPARE_DRM_STATUS_PREPARATION_ERROR = 3;
    public static final int PREPARE_DRM_STATUS_PROVISIONING_NETWORK_ERROR = 1;
    public static final int PREPARE_DRM_STATUS_PROVISIONING_SERVER_ERROR = 2;
    public static final int PREPARE_DRM_STATUS_SUCCESS = 0;
    public static int native_pullBatteryData(android.os.Parcel p0) { return 0; }
    public void addSubtitleSource(java.io.InputStream p0, android.media.MediaFormat p1) {}
    public void addTimedTextSource(android.content.Context p0, android.net.Uri p1, java.lang.String p2) {}
    public void addTimedTextSource(java.io.FileDescriptor p0, long p1, long p2, java.lang.String p3) {}
    public void addTimedTextSource(java.io.FileDescriptor p0, java.lang.String p1) {}
    public void clearOnMediaTimeDiscontinuityListener() {}
    public void clearOnSubtitleDataListener() {}
    public android.media.VolumeShaper createVolumeShaper(android.media.VolumeShaper.Configuration p0) { return null; }
    public android.media.PlaybackParams easyPlaybackParams(float p0, int p1) { return null; }
    public android.media.MediaPlayer.DrmInfo getDrmInfo() { return null; }
    public java.lang.String getDrmPropertyString(java.lang.String p0) { return null; }
    public android.media.MediaDrm.KeyRequest getKeyRequest(byte[] p0, byte[] p1, java.lang.String p2, int p3, java.util.Map p4) { return null; }
    public android.os.PersistableBundle getMetrics() { return null; }
    public java.util.List getRoutedDevices() { return new java.util.ArrayList(); }
    public int getSelectedTrack(int p0) { return 0; }
    public android.media.SyncParams getSyncParams() { return (android.media.SyncParams) huskFill.get("SyncParams"); }
    public void invoke(android.os.Parcel p0, android.os.Parcel p1) {}
    public android.os.Parcel newRequest() { return null; }
    public void notifyAt(long p0) {}
    public void prepareDrm(java.util.UUID p0) {}
    public byte[] provideKeyResponse(byte[] p0, byte[] p1) { return null; }
    public void releaseDrm() {}
    public void restoreKeys(byte[] p0) {}
    public void setDrmPropertyString(java.lang.String p0, java.lang.String p1) {}
    public int setMetadataFilter(java.util.Set p0, java.util.Set p1) { return 0; }
    public void setOnDrmConfigHelper(android.media.MediaPlayer.OnDrmConfigHelper p0) {}
    public void setOnDrmInfoListener(android.media.MediaPlayer.OnDrmInfoListener p0) {}
    public void setOnDrmInfoListener(android.media.MediaPlayer.OnDrmInfoListener p0, android.os.Handler p1) {}
    public void setOnDrmPreparedListener(android.media.MediaPlayer.OnDrmPreparedListener p0) {}
    public void setOnDrmPreparedListener(android.media.MediaPlayer.OnDrmPreparedListener p0, android.os.Handler p1) {}
    public void setOnMediaTimeDiscontinuityListener(android.media.MediaPlayer.OnMediaTimeDiscontinuityListener p0) {}
    public void setOnMediaTimeDiscontinuityListener(android.media.MediaPlayer.OnMediaTimeDiscontinuityListener p0, android.os.Handler p1) {}
    public void setOnRtpRxNoticeListener(android.content.Context p0, java.util.concurrent.Executor p1, android.media.MediaPlayer.OnRtpRxNoticeListener p2) {}
    public void setOnSubtitleDataListener(android.media.MediaPlayer.OnSubtitleDataListener p0) {}
    public void setOnSubtitleDataListener(android.media.MediaPlayer.OnSubtitleDataListener p0, android.os.Handler p1) {}
    public void setOnTimedMetaDataAvailableListener(android.media.MediaPlayer.OnTimedMetaDataAvailableListener p0) {}
    public void setRetransmitEndpoint(java.net.InetSocketAddress p0) {}
    public void setSyncParams(android.media.SyncParams p0) { huskFill.put("SyncParams", p0); }
    // ---- end of generated members
    // ---- generated by tools/compat/genstubs.py: the platform's nested classes this class does not write
    public static final class DrmInfo {
        private final java.util.HashMap<String, Object> huskProps = new java.util.HashMap<>();
        public java.util.Map getPssh() { return (huskProps.get("Pssh") != null ? (java.util.Map) huskProps.get("Pssh") : new java.util.HashMap()); }
        public java.util.UUID[] getSupportedSchemes() { return (java.util.UUID[]) huskProps.get("SupportedSchemes"); }
        protected DrmInfo() {}
    }
    public static final class MetricsConstants {
        private final java.util.HashMap<String, Object> huskProps = new java.util.HashMap<>();
        public static final java.lang.String CODEC_AUDIO = "android.media.mediaplayer.audio.codec";
        public static final java.lang.String CODEC_VIDEO = "android.media.mediaplayer.video.codec";
        public static final java.lang.String DURATION = "android.media.mediaplayer.durationMs";
        public static final java.lang.String ERRORS = "android.media.mediaplayer.err";
        public static final java.lang.String ERROR_CODE = "android.media.mediaplayer.errcode";
        public static final java.lang.String FRAMES = "android.media.mediaplayer.frames";
        public static final java.lang.String FRAMES_DROPPED = "android.media.mediaplayer.dropped";
        public static final java.lang.String HEIGHT = "android.media.mediaplayer.height";
        public static final java.lang.String MIME_TYPE_AUDIO = "android.media.mediaplayer.audio.mime";
        public static final java.lang.String MIME_TYPE_VIDEO = "android.media.mediaplayer.video.mime";
        public static final java.lang.String PLAYING = "android.media.mediaplayer.playingMs";
        public static final java.lang.String WIDTH = "android.media.mediaplayer.width";
        protected MetricsConstants() {}
    }
    public static final class NoDrmSchemeException extends android.media.MediaDrmException {
        private final java.util.HashMap<String, Object> huskProps = new java.util.HashMap<>();
        public NoDrmSchemeException(java.lang.String p0) { super(p0); }
        NoDrmSchemeException() { this((java.lang.String) null); }
    }
    public interface OnDrmConfigHelper {
        void onDrmConfig(android.media.MediaPlayer p0);
    }
    public interface OnDrmInfoListener {
        void onDrmInfo(android.media.MediaPlayer p0, android.media.MediaPlayer.DrmInfo p1);
    }
    public interface OnDrmPreparedListener {
        void onDrmPrepared(android.media.MediaPlayer p0, int p1);
    }
    public interface OnMediaTimeDiscontinuityListener {
        void onMediaTimeDiscontinuity(android.media.MediaPlayer p0, android.media.MediaTimestamp p1);
    }
    public interface OnRtpRxNoticeListener {
        void onRtpRxNotice(android.media.MediaPlayer p0, int p1, int[] p2);
    }
    public interface OnSubtitleDataListener {
        void onSubtitleData(android.media.MediaPlayer p0, android.media.SubtitleData p1);
    }
    public interface OnTimedMetaDataAvailableListener {
        void onTimedMetaDataAvailable(android.media.MediaPlayer p0, android.media.TimedMetaData p1);
    }
    public static final class ProvisioningNetworkErrorException extends android.media.MediaDrmException {
        private final java.util.HashMap<String, Object> huskProps = new java.util.HashMap<>();
        public ProvisioningNetworkErrorException(java.lang.String p0) { super(p0); }
        ProvisioningNetworkErrorException() { this((java.lang.String) null); }
    }
    public static final class ProvisioningServerErrorException extends android.media.MediaDrmException {
        private final java.util.HashMap<String, Object> huskProps = new java.util.HashMap<>();
        public ProvisioningServerErrorException(java.lang.String p0) { super(p0); }
        ProvisioningServerErrorException() { this((java.lang.String) null); }
    }
    // ---- end of generated nested classes
}
