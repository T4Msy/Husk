package android.graphics;

/** Measures a path contour by contour over its flattened polylines (husk.Gfx.pathFlatten), as Skia's does over the curves. */
public class PathMeasure {
    public static final int POSITION_MATRIX_FLAG = 1, TANGENT_MATRIX_FLAG = 2;
    private Path mPath;
    private boolean mForceClosed;
    private float[] mPts;               /* the contours: NaN-pair separated x,y runs */
    private int mN;
    private int mStart = -1, mEnd;      /* the current contour's points [mStart, mEnd) in mPts */
    private boolean mClosed;
    private float[] mCum;               /* cumulative length at each point of the current contour */
    private float mLength;

    public PathMeasure() {}
    public PathMeasure(Path p, boolean forceClosed) { setPath(p, forceClosed); }
    public void setPath(Path p, boolean forceClosed) {
        mPath = p; mForceClosed = forceClosed;
        mStart = -1; mEnd = 0; mN = 0; mLength = 0; mClosed = false; mCum = null;
        if (p == null || p.n == 0) return;
        mPts = new float[Math.max(64, p.n * 4)];
        int need = husk.Gfx.pathFlatten(p.ops, p.n, mPts);
        if (need > mPts.length) { mPts = new float[need]; need = husk.Gfx.pathFlatten(p.ops, p.n, mPts); }
        mN = Math.min(need, mPts.length);
        nextContour();
    }
    /** Moves to the next contour (the first after setPath); false when there is none. */
    public boolean nextContour() {
        int i = mStart < 0 ? 0 : mEnd;
        /* skip the marker(s) between contours */
        while (i + 1 < mN && Float.isNaN(mPts[i])) i += 2;
        if (i >= mN) { if (mStart < 0) { mStart = 0; mEnd = 0; } else { mStart = mEnd = mN; } mLength = 0; mCum = null; mClosed = false; return false; }
        int s = i;
        while (i + 1 < mN && !Float.isNaN(mPts[i])) i += 2;
        mStart = s; mEnd = i;
        /* a close marker is a NaN pair followed by another marker (the next contour's) or by the end; an open marker is followed by a point */
        boolean marker = i + 1 < mN && Float.isNaN(mPts[i]);
        mClosed = mForceClosed || (marker && (i + 2 >= mN || Float.isNaN(mPts[i + 2])));
        int np = (mEnd - mStart) / 2;
        mCum = new float[np + (mClosed ? 1 : 0)];
        float len = 0;
        for (int k = 1; k < np; k++) { len += dist(k - 1, k); mCum[k] = len; }
        if (mClosed && np > 1) { len += (float) Math.hypot(x(0) - x(np - 1), y(0) - y(np - 1)); mCum[np] = len; }
        mLength = len;
        return true;
    }
    private float x(int k) { return mPts[mStart + 2 * k]; }
    private float y(int k) { return mPts[mStart + 2 * k + 1]; }
    private float dist(int a, int b) { return (float) Math.hypot(x(b) - x(a), y(b) - y(a)); }
    private int count() { return (mEnd - mStart) / 2; }
    public float getLength() { return mLength; }
    public boolean isClosed() { return mClosed; }
    /** The segment at distance d: pos = point, tan = unit tangent. */
    public boolean getPosTan(float d, float[] pos, float[] tan) {
        int np = count();
        if (np == 0) return false;
        if (np == 1 || mLength <= 0) { if (pos != null) { pos[0] = x(0); pos[1] = y(0); } if (tan != null) { tan[0] = 1; tan[1] = 0; } return true; }
        d = Math.max(0, Math.min(d, mLength));
        int segs = mCum.length - 1, k = 1;
        while (k < segs && mCum[k] < d) k++;
        int a = k - 1, b = k == np ? 0 : k;
        float segLen = mCum[k] - mCum[k - 1], t = segLen > 0 ? (d - mCum[k - 1]) / segLen : 0;
        float dx = x(b) - x(a), dy = y(b) - y(a);
        if (pos != null) { pos[0] = x(a) + dx * t; pos[1] = y(a) + dy * t; }
        if (tan != null) { float l = (float) Math.hypot(dx, dy); if (l > 0) { tan[0] = dx / l; tan[1] = dy / l; } else { tan[0] = 1; tan[1] = 0; } }
        return true;
    }
    public boolean getMatrix(float d, Matrix m, int flags) {
        float[] pos = new float[2], tan = new float[2];
        if (!getPosTan(d, pos, tan)) return false;
        m.reset();
        if ((flags & TANGENT_MATRIX_FLAG) != 0) m.setSinCos(tan[1], tan[0]);
        if ((flags & POSITION_MATRIX_FLAG) != 0) m.postTranslate(pos[0], pos[1]);
        return true;
    }
    /** Appends the part of the contour between start and stop (clamped) to dst as a polyline. */
    public boolean getSegment(float start, float stop, Path dst, boolean startWithMoveTo) {
        int np = count();
        if (np == 0 || mLength <= 0) return false;
        start = Math.max(0, start); stop = Math.min(stop, mLength);
        if (start >= stop) return false;
        float[] p = new float[2];
        getPosTan(start, p, null);
        if (startWithMoveTo) dst.moveTo(p[0], p[1]); else dst.lineTo(p[0], p[1]);
        for (int k = 1; k < mCum.length; k++) {
            if (mCum[k] <= start) continue;
            if (mCum[k] >= stop) break;
            int i = k == np ? 0 : k;
            dst.lineTo(x(i), y(i));
        }
        getPosTan(stop, p, null);
        dst.lineTo(p[0], p[1]);
        return true;
    }
    /** For Path: every contour's polyline points (for approximate / isConvex). */
    float[] huskPoints() { return mPts; }
    int huskCount() { return mN; }
}
