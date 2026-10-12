package android.location;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

/** A location fix, as the platform's: which fields are set is remembered, distances are on the WGS84 ellipsoid (Vincenty). */
public class Location implements Parcelable {
    public static final int FORMAT_DEGREES = 0, FORMAT_MINUTES = 1, FORMAT_SECONDS = 2;
    public static final String EXTRA_NO_GPS_LOCATION = "noGPSLocation";
    private static final int HAS_ALTITUDE = 1, HAS_SPEED = 2, HAS_BEARING = 4, HAS_HORIZONTAL_ACCURACY = 8, HAS_MOCK_PROVIDER = 16, HAS_VERTICAL_ACCURACY = 32,
        HAS_SPEED_ACCURACY = 64, HAS_BEARING_ACCURACY = 128, HAS_ELAPSED_REALTIME_UNCERTAINTY = 256, HAS_MSL_ALTITUDE = 512, HAS_MSL_ALTITUDE_ACCURACY = 1024;
    private String mProvider;
    private long mTime, mElapsedRealtimeNanos;
    private double mElapsedRealtimeUncertaintyNanos, mLatitude, mLongitude, mAltitude, mMslAltitude;
    private float mSpeed, mBearing, mHorizontalAccuracy, mVerticalAccuracy, mSpeedAccuracy, mBearingAccuracy, mMslAltitudeAccuracy;
    private int mFields;
    private Bundle mExtras;
    public Location(String provider) { mProvider = provider; }
    public Location(Location l) { set(l); }
    public void set(Location l) {
        mProvider = l.mProvider; mTime = l.mTime; mElapsedRealtimeNanos = l.mElapsedRealtimeNanos; mElapsedRealtimeUncertaintyNanos = l.mElapsedRealtimeUncertaintyNanos;
        mFields = l.mFields; mLatitude = l.mLatitude; mLongitude = l.mLongitude; mAltitude = l.mAltitude; mMslAltitude = l.mMslAltitude;
        mSpeed = l.mSpeed; mBearing = l.mBearing; mHorizontalAccuracy = l.mHorizontalAccuracy; mVerticalAccuracy = l.mVerticalAccuracy;
        mSpeedAccuracy = l.mSpeedAccuracy; mBearingAccuracy = l.mBearingAccuracy; mMslAltitudeAccuracy = l.mMslAltitudeAccuracy;
        mExtras = l.mExtras == null ? null : new Bundle(l.mExtras);
    }
    public void reset() { mProvider = null; mTime = 0; mElapsedRealtimeNanos = 0; mElapsedRealtimeUncertaintyNanos = 0; mFields = 0; mLatitude = mLongitude = mAltitude = mMslAltitude = 0; mSpeed = mBearing = mHorizontalAccuracy = mVerticalAccuracy = mSpeedAccuracy = mBearingAccuracy = mMslAltitudeAccuracy = 0; mExtras = null; }
    private boolean has(int f) { return (mFields & f) != 0; }
    private void set(int f, boolean on) { if (on) mFields |= f; else mFields &= ~f; }
    public String getProvider() { return mProvider; }
    public void setProvider(String p) { mProvider = p; }
    public long getTime() { return mTime; }
    public void setTime(long t) { mTime = t; }
    public long getElapsedRealtimeNanos() { return mElapsedRealtimeNanos; }
    public void setElapsedRealtimeNanos(long t) { mElapsedRealtimeNanos = t; }
    public long getElapsedRealtimeMillis() { return mElapsedRealtimeNanos / 1000000L; }
    public long getElapsedRealtimeAgeMillis() { return getElapsedRealtimeAgeMillis(android.os.SystemClock.elapsedRealtime()); }
    public long getElapsedRealtimeAgeMillis(long referenceRealtimeMs) { return referenceRealtimeMs - getElapsedRealtimeMillis(); }
    public boolean hasElapsedRealtimeUncertaintyNanos() { return has(HAS_ELAPSED_REALTIME_UNCERTAINTY); }
    public double getElapsedRealtimeUncertaintyNanos() { return mElapsedRealtimeUncertaintyNanos; }
    public void setElapsedRealtimeUncertaintyNanos(double n) { mElapsedRealtimeUncertaintyNanos = n; set(HAS_ELAPSED_REALTIME_UNCERTAINTY, true); }
    public void removeElapsedRealtimeUncertaintyNanos() { set(HAS_ELAPSED_REALTIME_UNCERTAINTY, false); }
    public double getLatitude() { return mLatitude; }
    public void setLatitude(double l) { mLatitude = l; }
    public double getLongitude() { return mLongitude; }
    public void setLongitude(double l) { mLongitude = l; }
    public boolean hasAltitude() { return has(HAS_ALTITUDE); }
    public double getAltitude() { return mAltitude; }
    public void setAltitude(double a) { mAltitude = a; set(HAS_ALTITUDE, true); }
    public void removeAltitude() { set(HAS_ALTITUDE, false); }
    public boolean hasSpeed() { return has(HAS_SPEED); }
    public float getSpeed() { return mSpeed; }
    public void setSpeed(float s) { mSpeed = s; set(HAS_SPEED, true); }
    public void removeSpeed() { set(HAS_SPEED, false); }
    public boolean hasBearing() { return has(HAS_BEARING); }
    public float getBearing() { return mBearing; }
    public void setBearing(float b) { while (b < 0) b += 360; while (b >= 360) b -= 360; mBearing = b; set(HAS_BEARING, true); }
    public void removeBearing() { set(HAS_BEARING, false); }
    public boolean hasAccuracy() { return has(HAS_HORIZONTAL_ACCURACY); }
    public float getAccuracy() { return mHorizontalAccuracy; }
    public void setAccuracy(float a) { mHorizontalAccuracy = a; set(HAS_HORIZONTAL_ACCURACY, true); }
    public void removeAccuracy() { set(HAS_HORIZONTAL_ACCURACY, false); }
    public boolean hasVerticalAccuracy() { return has(HAS_VERTICAL_ACCURACY); }
    public float getVerticalAccuracyMeters() { return mVerticalAccuracy; }
    public void setVerticalAccuracyMeters(float a) { mVerticalAccuracy = a; set(HAS_VERTICAL_ACCURACY, true); }
    public void removeVerticalAccuracy() { set(HAS_VERTICAL_ACCURACY, false); }
    public boolean hasSpeedAccuracy() { return has(HAS_SPEED_ACCURACY); }
    public float getSpeedAccuracyMetersPerSecond() { return mSpeedAccuracy; }
    public void setSpeedAccuracyMetersPerSecond(float a) { mSpeedAccuracy = a; set(HAS_SPEED_ACCURACY, true); }
    public void removeSpeedAccuracy() { set(HAS_SPEED_ACCURACY, false); }
    public boolean hasBearingAccuracy() { return has(HAS_BEARING_ACCURACY); }
    public float getBearingAccuracyDegrees() { return mBearingAccuracy; }
    public void setBearingAccuracyDegrees(float a) { mBearingAccuracy = a; set(HAS_BEARING_ACCURACY, true); }
    public void removeBearingAccuracy() { set(HAS_BEARING_ACCURACY, false); }
    public boolean hasMslAltitude() { return has(HAS_MSL_ALTITUDE); }
    public double getMslAltitudeMeters() { return mMslAltitude; }
    public void setMslAltitudeMeters(double a) { mMslAltitude = a; set(HAS_MSL_ALTITUDE, true); }
    public void removeMslAltitude() { set(HAS_MSL_ALTITUDE, false); }
    public boolean hasMslAltitudeAccuracy() { return has(HAS_MSL_ALTITUDE_ACCURACY); }
    public float getMslAltitudeAccuracyMeters() { return mMslAltitudeAccuracy; }
    public void setMslAltitudeAccuracyMeters(float a) { mMslAltitudeAccuracy = a; set(HAS_MSL_ALTITUDE_ACCURACY, true); }
    public void removeMslAltitudeAccuracy() { set(HAS_MSL_ALTITUDE_ACCURACY, false); }
    public boolean isMock() { return has(HAS_MOCK_PROVIDER); }
    public void setMock(boolean m) { set(HAS_MOCK_PROVIDER, m); }
    @Deprecated public boolean isFromMockProvider() { return isMock(); }
    public boolean isComplete() { return mProvider != null && hasAccuracy() && mTime != 0 && mElapsedRealtimeNanos != 0; }
    public void makeComplete() { if (mProvider == null) mProvider = "?"; if (!hasAccuracy()) setAccuracy(100f); if (mTime == 0) mTime = System.currentTimeMillis(); if (mElapsedRealtimeNanos == 0) mElapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos(); }
    public Bundle getExtras() { return mExtras; }
    public void setExtras(Bundle e) { mExtras = e == null ? null : new Bundle(e); }
    public float distanceTo(Location dest) { float[] r = new float[1]; distanceBetween(mLatitude, mLongitude, dest.mLatitude, dest.mLongitude, r); return r[0]; }
    public float bearingTo(Location dest) { float[] r = new float[2]; distanceBetween(mLatitude, mLongitude, dest.mLatitude, dest.mLongitude, r); return r[1]; }
    /** results[0] distance in metres, [1] initial bearing, [2] final bearing: Vincenty's inverse formula on WGS84, as the platform's. */
    public static void distanceBetween(double lat1, double lon1, double lat2, double lon2, float[] results) {
        if (results == null || results.length < 1) throw new IllegalArgumentException("results is null or has length < 1");
        double a = 6378137.0, b = 6356752.3142, f = (a - b) / a, aSqMinusBSqOverBSq = (a * a - b * b) / (b * b);
        double L = Math.toRadians(lon2 - lon1), U1 = Math.atan((1 - f) * Math.tan(Math.toRadians(lat1))), U2 = Math.atan((1 - f) * Math.tan(Math.toRadians(lat2)));
        double cosU1 = Math.cos(U1), cosU2 = Math.cos(U2), sinU1 = Math.sin(U1), sinU2 = Math.sin(U2), cosU1cosU2 = cosU1 * cosU2, sinU1sinU2 = sinU1 * sinU2;
        double sigma = 0, deltaSigma = 0, cosSqAlpha = 0, cos2SM = 0, cosSigma = 0, sinSigma = 0, cosLambda = 0, sinLambda = 0, lambda = L;
        for (int iter = 0; iter < 20; iter++) {
            double lambdaOrig = lambda;
            cosLambda = Math.cos(lambda); sinLambda = Math.sin(lambda);
            double t1 = cosU2 * sinLambda, t2 = cosU1 * sinU2 - sinU1 * cosU2 * cosLambda;
            double sinSqSigma = t1 * t1 + t2 * t2;
            sinSigma = Math.sqrt(sinSqSigma);
            cosSigma = sinU1sinU2 + cosU1cosU2 * cosLambda;
            sigma = Math.atan2(sinSigma, cosSigma);
            double sinAlpha = sinSigma == 0 ? 0 : cosU1cosU2 * sinLambda / sinSigma;
            cosSqAlpha = 1 - sinAlpha * sinAlpha;
            cos2SM = cosSqAlpha == 0 ? 0 : cosSigma - 2 * sinU1sinU2 / cosSqAlpha;
            double uSquared = cosSqAlpha * aSqMinusBSqOverBSq;
            double A = 1 + (uSquared / 16384) * (4096 + uSquared * (-768 + uSquared * (320 - 175 * uSquared)));
            double B = (uSquared / 1024) * (256 + uSquared * (-128 + uSquared * (74 - 47 * uSquared)));
            double C = (f / 16) * cosSqAlpha * (4 + f * (4 - 3 * cosSqAlpha));
            double cos2SMSq = cos2SM * cos2SM;
            deltaSigma = B * sinSigma * (cos2SM + (B / 4) * (cosSigma * (-1 + 2 * cos2SMSq) - (B / 6) * cos2SM * (-3 + 4 * sinSigma * sinSigma) * (-3 + 4 * cos2SMSq)));
            lambda = L + (1 - C) * f * sinAlpha * (sigma + C * sinSigma * (cos2SM + C * cosSigma * (-1 + 2 * cos2SMSq)));
            if (Math.abs((lambda - lambdaOrig) / lambda) < 1.0e-12) break;
        }
        double uSquared = cosSqAlpha * aSqMinusBSqOverBSq;
        double A = 1 + (uSquared / 16384) * (4096 + uSquared * (-768 + uSquared * (320 - 175 * uSquared)));
        results[0] = (float) (b * A * (sigma - deltaSigma));
        if (results.length > 1) results[1] = (float) Math.toDegrees(Math.atan2(cosU2 * sinLambda, cosU1 * sinU2 - sinU1 * cosU2 * cosLambda));
        if (results.length > 2) results[2] = (float) Math.toDegrees(Math.atan2(cosU1 * sinLambda, -sinU1 * cosU2 + cosU1 * sinU2 * cosLambda));
    }
    public static String convert(double coordinate, int outputType) {
        if (coordinate < -180.0 || coordinate > 180.0 || Double.isNaN(coordinate)) throw new IllegalArgumentException("coordinate=" + coordinate);
        if (outputType != FORMAT_DEGREES && outputType != FORMAT_MINUTES && outputType != FORMAT_SECONDS) throw new IllegalArgumentException("outputType=" + outputType);
        StringBuilder sb = new StringBuilder();
        if (coordinate < 0) { sb.append('-'); coordinate = -coordinate; }
        java.text.DecimalFormat df = new java.text.DecimalFormat("###.#####", java.text.DecimalFormatSymbols.getInstance(java.util.Locale.US));
        if (outputType == FORMAT_MINUTES || outputType == FORMAT_SECONDS) {
            int degrees = (int) Math.floor(coordinate); sb.append(degrees).append(':'); coordinate = (coordinate - degrees) * 60.0;
            if (outputType == FORMAT_SECONDS) { int minutes = (int) Math.floor(coordinate); sb.append(minutes).append(':'); coordinate = (coordinate - minutes) * 60.0; }
        }
        sb.append(df.format(coordinate));
        return sb.toString();
    }
    public static double convert(String coordinate) {
        if (coordinate == null) throw new NullPointerException("coordinate");
        boolean negative = false;
        if (coordinate.charAt(0) == '-') { coordinate = coordinate.substring(1); negative = true; }
        java.util.StringTokenizer st = new java.util.StringTokenizer(coordinate, ":");
        int tokens = st.countTokens();
        if (tokens < 1) throw new IllegalArgumentException("coordinate=" + coordinate);
        try {
            String degrees = st.nextToken(); double val;
            if (tokens == 1) { val = Double.parseDouble(degrees); return negative ? -val : val; }
            String minutes = st.nextToken(); int deg = Integer.parseInt(degrees); double min, sec = 0.0; boolean secondsExist = false;
            if (st.hasMoreTokens()) { min = Integer.parseInt(minutes); String seconds = st.nextToken(); sec = Double.parseDouble(seconds); secondsExist = true; } else min = Double.parseDouble(minutes);
            boolean isNegative180 = negative && deg == 180 && min == 0 && sec == 0;
            if (deg < 0.0 || (deg > 179 && !isNegative180)) throw new IllegalArgumentException("coordinate=" + coordinate);
            if (min < 0 || min > 59 || (secondsExist && min > 59.0)) throw new IllegalArgumentException("coordinate=" + coordinate);
            if (sec < 0 || sec > 59) throw new IllegalArgumentException("coordinate=" + coordinate);
            val = deg * 3600.0 + min * 60.0 + sec; val /= 3600.0;
            return negative ? -val : val;
        } catch (NumberFormatException e) { throw new IllegalArgumentException("coordinate=" + coordinate); }
    }
    public void dump(android.util.Printer pw, String prefix) { pw.println(prefix + toString()); }
    @Override public boolean equals(Object o) {
        if (!(o instanceof Location)) return false;
        Location l = (Location) o;
        return mTime == l.mTime && mElapsedRealtimeNanos == l.mElapsedRealtimeNanos && mFields == l.mFields && Double.compare(mLatitude, l.mLatitude) == 0 && Double.compare(mLongitude, l.mLongitude) == 0
            && (!hasAltitude() || Double.compare(mAltitude, l.mAltitude) == 0) && (!hasSpeed() || Float.compare(mSpeed, l.mSpeed) == 0) && (!hasBearing() || Float.compare(mBearing, l.mBearing) == 0)
            && (!hasAccuracy() || Float.compare(mHorizontalAccuracy, l.mHorizontalAccuracy) == 0) && java.util.Objects.equals(mProvider, l.mProvider);
    }
    @Override public int hashCode() { return java.util.Objects.hash(mProvider, mTime, mLatitude, mLongitude); }
    @Override public String toString() {
        StringBuilder s = new StringBuilder("Location[").append(mProvider).append(String.format(java.util.Locale.ROOT, " %.6f,%.6f", mLatitude, mLongitude));
        if (hasAccuracy()) s.append(String.format(java.util.Locale.ROOT, " hAcc=%.0f", mHorizontalAccuracy));
        s.append(" et=").append(getElapsedRealtimeMillis()).append("ms");
        if (hasAltitude()) s.append(" alt=").append(mAltitude);
        if (hasSpeed()) s.append(" vel=").append(mSpeed);
        if (hasBearing()) s.append(" bear=").append(mBearing);
        if (isMock()) s.append(" mock");
        if (mExtras != null) s.append(" {").append(mExtras).append('}');
        return s.append(']').toString();
    }
    public int describeContents() { return 0; }
    public void writeToParcel(Parcel p, int flags) {
        p.writeString(mProvider); p.writeInt(mFields); p.writeLong(mTime); p.writeLong(mElapsedRealtimeNanos); p.writeDouble(mElapsedRealtimeUncertaintyNanos);
        p.writeDouble(mLatitude); p.writeDouble(mLongitude); p.writeDouble(mAltitude); p.writeFloat(mSpeed); p.writeFloat(mBearing); p.writeFloat(mHorizontalAccuracy);
        p.writeFloat(mVerticalAccuracy); p.writeFloat(mSpeedAccuracy); p.writeFloat(mBearingAccuracy); p.writeDouble(mMslAltitude); p.writeFloat(mMslAltitudeAccuracy); p.writeBundle(mExtras);
    }
    public static final Creator<Location> CREATOR = new Creator<Location>() {
        public Location createFromParcel(Parcel p) {
            Location l = new Location(p.readString());
            l.mFields = p.readInt(); l.mTime = p.readLong(); l.mElapsedRealtimeNanos = p.readLong(); l.mElapsedRealtimeUncertaintyNanos = p.readDouble();
            l.mLatitude = p.readDouble(); l.mLongitude = p.readDouble(); l.mAltitude = p.readDouble(); l.mSpeed = p.readFloat(); l.mBearing = p.readFloat(); l.mHorizontalAccuracy = p.readFloat();
            l.mVerticalAccuracy = p.readFloat(); l.mSpeedAccuracy = p.readFloat(); l.mBearingAccuracy = p.readFloat(); l.mMslAltitude = p.readDouble(); l.mMslAltitudeAccuracy = p.readFloat(); l.mExtras = p.readBundle();
            return l;
        }
        public Location[] newArray(int n) { return new Location[n]; }
    };
}
