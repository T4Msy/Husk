package android.media;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Samples out of a media file, track by track (husk.MediaDemux): what ExoPlayer and transcoders feed MediaCodec. The selected
 * tracks' samples interleave in time order; seeking lands each selected track on a sync sample by the mode asked. Video samples
 * are Annex-B access units with the parameter sets in csd-0/csd-1 of the track's format. No DRM, no CAS, no network cache.
 */
@SuppressWarnings({"unchecked", "rawtypes", "deprecation"})
public final class MediaExtractor {
    public static final int SAMPLE_FLAG_SYNC = 1, SAMPLE_FLAG_ENCRYPTED = 2, SAMPLE_FLAG_PARTIAL_FRAME = 4;
    public static final int SEEK_TO_PREVIOUS_SYNC = 0, SEEK_TO_NEXT_SYNC = 1, SEEK_TO_CLOSEST_SYNC = 2;
    private long mNative;
    private final java.util.BitSet mSelected = new java.util.BitSet();
    private android.media.metrics.LogSessionId mLogSessionId = android.media.metrics.LogSessionId.LOG_SESSION_ID_NONE;

    public MediaExtractor() {}
    private long h() { if (mNative == 0) throw new IllegalStateException("no data source, or released"); return mNative; }
    private void set(long h) { if (mNative != 0) husk.MediaDemux.close(mNative); mNative = h; mSelected.clear(); }
    public void setDataSource(String path) throws IOException { set(husk.MediaDemux.openFile(path)); }
    public void setDataSource(String path, java.util.Map<String, String> headers) throws IOException {
        set(path != null && (path.startsWith("http://") || path.startsWith("https://")) ? husk.MediaDemux.openUrl(path, headers) : husk.MediaDemux.openFile(path));
    }
    public void setDataSource(android.content.Context c, android.net.Uri u, java.util.Map<String, String> headers) throws IOException { set(husk.MediaDemux.openUri(c, u, headers)); }
    public void setDataSource(java.io.FileDescriptor fd) throws IOException { set(husk.MediaDemux.openFd(fd, 0, 0)); }
    public void setDataSource(java.io.FileDescriptor fd, long offset, long length) throws IOException { set(husk.MediaDemux.openFd(fd, offset, length)); }
    public void setDataSource(android.content.res.AssetFileDescriptor afd) throws IOException { set(husk.MediaDemux.openAsset(afd)); }
    public void setDataSource(MediaDataSource ds) throws IOException { set(husk.MediaDemux.openDataSource(ds)); }
    public void setMediaCas(MediaCas cas) {}
    public CasInfo getCasInfo(int track) { return null; }
    public DrmInitData getDrmInitData() { return null; }
    public java.util.Map<java.util.UUID, byte[]> getPsshInfo() { return null; }
    public java.util.List<AudioPresentation> getAudioPresentations(int track) { return new java.util.ArrayList<>(); }

    public int getTrackCount() { return husk.MediaDemux.trackCount(h()); }
    public MediaFormat getTrackFormat(int track) {
        if (track < 0 || track >= getTrackCount()) throw new IllegalArgumentException("Invalid track index " + track);
        return husk.MediaDemux.format(h(), track);
    }
    public void selectTrack(int track) {
        if (track < 0 || track >= getTrackCount()) throw new IllegalArgumentException("Invalid track index " + track);
        if (mSelected.get(track)) return;
        mSelected.set(track); husk.MediaDemux.select(h(), track, true);
    }
    public void unselectTrack(int track) {
        if (track < 0 || track >= getTrackCount()) throw new IllegalArgumentException("Invalid track index " + track);
        if (!mSelected.get(track)) return;
        mSelected.clear(track); husk.MediaDemux.select(h(), track, false);
    }
    public void seekTo(long timeUs, int mode) {
        if (mode < SEEK_TO_PREVIOUS_SYNC || mode > SEEK_TO_CLOSEST_SYNC) throw new IllegalArgumentException("Unknown seek mode " + mode);
        husk.MediaDemux.seek(h(), timeUs, mode);
    }
    public boolean advance() { return husk.MediaDemux.advance(h()); }
    /** The current sample at offset of the buffer (position set to offset, limit to its end); -1 when there is none. */
    public int readSampleData(ByteBuffer buf, int offset) {
        long h = h();
        if (buf == null) throw new NullPointerException("buffer");
        if (offset < 0 || offset > buf.capacity()) throw new IllegalArgumentException("bad offset " + offset);
        long size = husk.MediaDemux.sampleSize(h);
        if (size < 0) return -1;
        if (size > buf.capacity() - offset) throw new IllegalArgumentException("the buffer holds " + (buf.capacity() - offset) + " bytes, the sample is " + size);
        int n;
        if (buf.hasArray()) n = husk.MediaDemux.readSample(h, buf.array(), buf.arrayOffset() + offset);
        else { byte[] tmp = new byte[(int) size]; n = husk.MediaDemux.readSample(h, tmp, 0); if (n > 0) { ByteBuffer d = buf.duplicate(); d.clear(); d.position(offset); d.put(tmp, 0, n); } }
        if (n < 0) return -1;
        buf.limit(offset + n); buf.position(offset);
        return n;
    }
    public int getSampleTrackIndex() { return husk.MediaDemux.sampleTrack(h()); }
    public long getSampleTime() { return husk.MediaDemux.sampleTime(h()); }
    public long getSampleSize() { return husk.MediaDemux.sampleSize(h()); }
    public int getSampleFlags() { return husk.MediaDemux.sampleTrack(h()) < 0 ? -1 : husk.MediaDemux.sampleFlags(mNative); }
    public boolean getSampleCryptoInfo(MediaCodec.CryptoInfo info) { return false; }
    public long getCachedDuration() { return -1; }
    public boolean hasCacheReachedEndOfStream() { return true; }
    public android.os.PersistableBundle getMetrics() {
        android.os.PersistableBundle b = new android.os.PersistableBundle();
        if (mNative != 0) { b.putString(MetricsConstants.FORMAT, husk.MediaDemux.container(mNative)); b.putInt(MetricsConstants.TRACKS, husk.MediaDemux.trackCount(mNative)); int n = husk.MediaDemux.trackCount(mNative); if (n > 0) b.putString(MetricsConstants.MIME_TYPE, husk.MediaDemux.trackMime(mNative, 0)); }
        return b;
    }
    public android.media.metrics.LogSessionId getLogSessionId() { return mLogSessionId; }
    public void setLogSessionId(android.media.metrics.LogSessionId id) { mLogSessionId = id == null ? android.media.metrics.LogSessionId.LOG_SESSION_ID_NONE : id; }
    public void release() { if (mNative != 0) { husk.MediaDemux.close(mNative); mNative = 0; } mSelected.clear(); }
    public void close() { release(); }
    @Override protected void finalize() { release(); }

    public static final class CasInfo {
        private final int mSystemId; private final MediaCas.Session mSession; private final byte[] mPrivateData;
        CasInfo(int systemId, MediaCas.Session session, byte[] privateData) { mSystemId = systemId; mSession = session; mPrivateData = privateData; }
        public int getSystemId() { return mSystemId; }
        public MediaCas.Session getSession() { return mSession; }
        public byte[] getPrivateData() { return mPrivateData; }
    }
    public static final class MetricsConstants {
        public static final String FORMAT = "android.media.mediaextractor.fmt", MIME_TYPE = "android.media.mediaextractor.mime", TRACKS = "android.media.mediaextractor.ntrk";
        private MetricsConstants() {}
    }
}
