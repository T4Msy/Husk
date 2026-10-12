package android.media;

import android.graphics.Bitmap;
import java.io.IOException;

/**
 * What a media file says about itself (husk.MediaDemux): durations, sizes, rotation, tags, cover art, and video frames decoded
 * through the MediaCodec decoder and handed out upright, as Android does. HEIF images (getImageAtIndex, getPrimaryImage) are not
 * read: those return null.
 */
@SuppressWarnings({"unchecked", "rawtypes", "deprecation"})
public class MediaMetadataRetriever implements AutoCloseable {
    public static final int METADATA_KEY_CD_TRACK_NUMBER = 0, METADATA_KEY_ALBUM = 1, METADATA_KEY_ARTIST = 2, METADATA_KEY_AUTHOR = 3, METADATA_KEY_COMPOSER = 4, METADATA_KEY_DATE = 5,
        METADATA_KEY_GENRE = 6, METADATA_KEY_TITLE = 7, METADATA_KEY_YEAR = 8, METADATA_KEY_DURATION = 9, METADATA_KEY_NUM_TRACKS = 10, METADATA_KEY_WRITER = 11, METADATA_KEY_MIMETYPE = 12,
        METADATA_KEY_ALBUMARTIST = 13, METADATA_KEY_DISC_NUMBER = 14, METADATA_KEY_COMPILATION = 15, METADATA_KEY_HAS_AUDIO = 16, METADATA_KEY_HAS_VIDEO = 17, METADATA_KEY_VIDEO_WIDTH = 18,
        METADATA_KEY_VIDEO_HEIGHT = 19, METADATA_KEY_BITRATE = 20, METADATA_KEY_TIMED_TEXT_LANGUAGES = 21, METADATA_KEY_IS_DRM = 22, METADATA_KEY_LOCATION = 23, METADATA_KEY_VIDEO_ROTATION = 24,
        METADATA_KEY_CAPTURE_FRAMERATE = 25, METADATA_KEY_HAS_IMAGE = 26, METADATA_KEY_IMAGE_COUNT = 27, METADATA_KEY_IMAGE_PRIMARY = 28, METADATA_KEY_IMAGE_WIDTH = 29, METADATA_KEY_IMAGE_HEIGHT = 30,
        METADATA_KEY_IMAGE_ROTATION = 31, METADATA_KEY_VIDEO_FRAME_COUNT = 32, METADATA_KEY_EXIF_OFFSET = 33, METADATA_KEY_EXIF_LENGTH = 34, METADATA_KEY_COLOR_STANDARD = 35,
        METADATA_KEY_COLOR_TRANSFER = 36, METADATA_KEY_COLOR_RANGE = 37, METADATA_KEY_SAMPLERATE = 38, METADATA_KEY_BITS_PER_SAMPLE = 39, METADATA_KEY_VIDEO_CODEC_MIME_TYPE = 40,
        METADATA_KEY_XMP_OFFSET = 41, METADATA_KEY_XMP_LENGTH = 42;
    public static final int OPTION_PREVIOUS_SYNC = 0, OPTION_NEXT_SYNC = 1, OPTION_CLOSEST_SYNC = 2, OPTION_CLOSEST = 3;
    private long mNative;

    public MediaMetadataRetriever() {}
    private long h() { if (mNative == 0) throw new IllegalStateException("no data source set"); return mNative; }
    private void set(long h) { if (mNative != 0) husk.MediaDemux.close(mNative); mNative = h; }
    private static IllegalArgumentException bad(IOException e) { return new IllegalArgumentException(e.getMessage() != null ? e.getMessage() : "setDataSource failed"); }
    public void setDataSource(String path) { try { set(husk.MediaDemux.openFile(path)); } catch (IOException e) { throw bad(e); } }
    public void setDataSource(String url, java.util.Map<String, String> headers) {
        try { set(url != null && (url.startsWith("http://") || url.startsWith("https://")) ? husk.MediaDemux.openUrl(url, headers) : husk.MediaDemux.openFile(url)); } catch (IOException e) { throw bad(e); }
    }
    public void setDataSource(java.io.FileDescriptor fd) { try { set(husk.MediaDemux.openFd(fd, 0, 0)); } catch (IOException e) { throw bad(e); } }
    public void setDataSource(java.io.FileDescriptor fd, long offset, long length) { try { set(husk.MediaDemux.openFd(fd, offset, length)); } catch (IOException e) { throw bad(e); } }
    public void setDataSource(android.content.Context c, android.net.Uri u) { try { set(husk.MediaDemux.openUri(c, u, null)); } catch (IOException e) { throw bad(e); } }
    public void setDataSource(MediaDataSource ds) { try { set(husk.MediaDemux.openDataSource(ds)); } catch (IOException e) { throw bad(e); } }

    private int video() { return husk.MediaDemux.trackOfType(h(), husk.MediaDemux.TYPE_VIDEO); }
    private int audio() { return husk.MediaDemux.trackOfType(h(), husk.MediaDemux.TYPE_AUDIO); }
    private String str(long v) { return Long.toString(v); }
    private String meta(int key) { return husk.MediaDemux.meta(h(), key); }
    public String extractMetadata(int key) {
        long h = h();
        int v = video(), a = audio();
        switch (key) {
        case METADATA_KEY_DURATION: { long us = husk.MediaDemux.duration(h); return us > 0 ? str((us + 500) / 1000) : "0"; }
        case METADATA_KEY_NUM_TRACKS: return str(husk.MediaDemux.trackCount(h));
        case METADATA_KEY_MIMETYPE: return husk.MediaDemux.container(h);
        case METADATA_KEY_BITRATE: { int b = husk.MediaDemux.bitrate(h); return b > 0 ? str(b) : null; }
        case METADATA_KEY_HAS_AUDIO: return a >= 0 ? "yes" : null;
        case METADATA_KEY_HAS_VIDEO: return v >= 0 ? "yes" : null;
        case METADATA_KEY_VIDEO_WIDTH: return v >= 0 ? str(husk.MediaDemux.trackInfo(h, v, husk.MediaDemux.WIDTH)) : null;
        case METADATA_KEY_VIDEO_HEIGHT: return v >= 0 ? str(husk.MediaDemux.trackInfo(h, v, husk.MediaDemux.HEIGHT)) : null;
        case METADATA_KEY_VIDEO_ROTATION: return v >= 0 ? str(husk.MediaDemux.trackInfo(h, v, husk.MediaDemux.ROTATION)) : null;
        case METADATA_KEY_VIDEO_FRAME_COUNT: return v >= 0 ? str(husk.MediaDemux.trackInfo(h, v, husk.MediaDemux.SAMPLES)) : null;
        case METADATA_KEY_VIDEO_CODEC_MIME_TYPE: return v >= 0 ? husk.MediaDemux.trackMime(h, v) : null;
        case METADATA_KEY_CAPTURE_FRAMERATE: return v >= 0 ? meta(husk.MediaDemux.META_CAPTURE_FPS) : null;
        case METADATA_KEY_COLOR_STANDARD: case METADATA_KEY_COLOR_TRANSFER: case METADATA_KEY_COLOR_RANGE: {
            if (v < 0) return null;
            long c = husk.MediaDemux.trackInfo(h, v, key == METADATA_KEY_COLOR_STANDARD ? husk.MediaDemux.COLOR_STANDARD : key == METADATA_KEY_COLOR_TRANSFER ? husk.MediaDemux.COLOR_TRANSFER : husk.MediaDemux.COLOR_RANGE);
            return c != 0 ? str(c) : null; }
        case METADATA_KEY_SAMPLERATE: return a >= 0 ? str(husk.MediaDemux.trackInfo(h, a, husk.MediaDemux.SAMPLE_RATE)) : null;
        case METADATA_KEY_BITS_PER_SAMPLE: { if (a < 0) return null; long b = husk.MediaDemux.trackInfo(h, a, husk.MediaDemux.BITS); return b > 0 ? str(b) : null; }
        case METADATA_KEY_TITLE: return meta(husk.MediaDemux.META_TITLE);
        case METADATA_KEY_ARTIST: return meta(husk.MediaDemux.META_ARTIST);
        case METADATA_KEY_ALBUM: return meta(husk.MediaDemux.META_ALBUM);
        case METADATA_KEY_ALBUMARTIST: return meta(husk.MediaDemux.META_ALBUMARTIST);
        case METADATA_KEY_AUTHOR: return meta(husk.MediaDemux.META_AUTHOR);
        case METADATA_KEY_COMPOSER: return meta(husk.MediaDemux.META_COMPOSER);
        case METADATA_KEY_WRITER: return meta(husk.MediaDemux.META_WRITER);
        case METADATA_KEY_DATE: return meta(husk.MediaDemux.META_DATE);
        case METADATA_KEY_YEAR: return meta(husk.MediaDemux.META_YEAR);
        case METADATA_KEY_GENRE: return meta(husk.MediaDemux.META_GENRE);
        case METADATA_KEY_CD_TRACK_NUMBER: return meta(husk.MediaDemux.META_TRACK);
        case METADATA_KEY_DISC_NUMBER: return meta(husk.MediaDemux.META_DISC);
        case METADATA_KEY_COMPILATION: return meta(husk.MediaDemux.META_COMPILATION);
        case METADATA_KEY_LOCATION: return meta(husk.MediaDemux.META_LOCATION);
        default: return null;                                      /* images, DRM, timed text, EXIF, XMP: none of them here */
        }
    }

    /** A decoded frame as a Bitmap, scaled to fit dstW x dstH when given (never enlarged), turned by the track's rotation. */
    private Bitmap bitmap(long frame, int rotation, int dstW, int dstH) {
        if (frame == 0) return null;
        try {
            int s = husk.MediaDemux.frameSize(frame);
            int w = s >>> 16, h = s & 0xffff;
            if (w <= 0 || h <= 0) return null;
            boolean swap = rotation == 90 || rotation == 270;
            int ow = swap ? h : w, oh = swap ? w : h;
            if (dstW > 0 && dstH > 0 && (ow > dstW || oh > dstH)) {
                float sc = Math.min(dstW / (float) ow, dstH / (float) oh);
                ow = Math.max(1, Math.round(ow * sc)); oh = Math.max(1, Math.round(oh * sc));
            }
            Bitmap b = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888);
            return husk.MediaDemux.frameDraw(frame, b, rotation) ? b : null;
        } finally { husk.MediaDemux.frameFree(frame); }
    }
    private Bitmap frameAt(long timeUs, int option, int dstW, int dstH) {
        if (option < OPTION_PREVIOUS_SYNC || option > OPTION_CLOSEST) throw new IllegalArgumentException("Unsupported option: " + option);
        long h = h();
        int v = video();
        if (v < 0) return null;
        return bitmap(husk.MediaDemux.frameAt(h, v, timeUs, option), (int) husk.MediaDemux.trackInfo(h, v, husk.MediaDemux.ROTATION), dstW, dstH);
    }
    public Bitmap getFrameAtTime(long timeUs, int option) { return frameAt(timeUs, option, 0, 0); }
    public Bitmap getFrameAtTime(long timeUs, int option, BitmapParams params) { return frameAt(timeUs, option, 0, 0); }
    public Bitmap getFrameAtTime(long timeUs) { return frameAt(timeUs, OPTION_CLOSEST_SYNC, 0, 0); }
    public Bitmap getFrameAtTime() { return frameAt(-1, OPTION_CLOSEST_SYNC, 0, 0); }
    public Bitmap getScaledFrameAtTime(long timeUs, int option, int dstWidth, int dstHeight) {
        if (dstWidth <= 0 || dstHeight <= 0) throw new IllegalArgumentException("Invalid width or height: " + dstWidth + "x" + dstHeight);
        return frameAt(timeUs, option, dstWidth, dstHeight);
    }
    public Bitmap getScaledFrameAtTime(long timeUs, int option, int dstWidth, int dstHeight, BitmapParams params) { return getScaledFrameAtTime(timeUs, option, dstWidth, dstHeight); }
    public Bitmap getFrameAtIndex(int index) { return getFrameAtIndex(index, null); }
    public Bitmap getFrameAtIndex(int index, BitmapParams params) {
        long h = h();
        int v = video();
        if (v < 0) throw new IllegalStateException("no video track");
        if (index < 0 || index >= husk.MediaDemux.trackInfo(h, v, husk.MediaDemux.SAMPLES)) throw new IllegalArgumentException("Invalid frame index: " + index);
        return bitmap(husk.MediaDemux.frameAtIndex(h, v, index), (int) husk.MediaDemux.trackInfo(h, v, husk.MediaDemux.ROTATION), 0, 0);
    }
    public java.util.List<Bitmap> getFramesAtIndex(int index, int count) { return getFramesAtIndex(index, count, null); }
    public java.util.List<Bitmap> getFramesAtIndex(int index, int count, BitmapParams params) {
        if (count <= 0) throw new IllegalArgumentException("Invalid number of frames: " + count);
        java.util.ArrayList<Bitmap> l = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) { Bitmap b = getFrameAtIndex(index + i, params); if (b == null) break; l.add(b); }
        return l;
    }
    public Bitmap getImageAtIndex(int index) { h(); return null; }
    public Bitmap getImageAtIndex(int index, BitmapParams params) { h(); return null; }
    public Bitmap getPrimaryImage() { h(); return null; }
    public Bitmap getPrimaryImage(BitmapParams params) { h(); return null; }
    public Bitmap getThumbnailImageAtIndex(int index, BitmapParams params, int targetSize, int maxPixels) { h(); return null; }
    public byte[] getEmbeddedPicture() { return husk.MediaDemux.picture(h()); }
    public void release() { if (mNative != 0) { husk.MediaDemux.close(mNative); mNative = 0; } }
    public void close() { release(); }
    @Override protected void finalize() { release(); }

    public static final class BitmapParams {
        private Bitmap.Config mPreferred = Bitmap.Config.ARGB_8888;
        public BitmapParams() {}
        public Bitmap.Config getPreferredConfig() { return mPreferred; }
        public void setPreferredConfig(Bitmap.Config c) { if (c == null) throw new IllegalArgumentException("null config"); mPreferred = c; }
        /** Frames are always decoded to ARGB_8888 here. */
        public Bitmap.Config getActualConfig() { return Bitmap.Config.ARGB_8888; }
    }
}
