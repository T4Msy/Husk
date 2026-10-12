package android.animation;

import android.util.Property;
import java.lang.reflect.Method;

public class PropertyValuesHolder implements Cloneable {
    String mName;
    Property mProperty;
    Object[] mValues;
    Class<?> mType;
    TypeEvaluator mEvaluator;
    Object mAnimated;
    private Method mSetter, mGetter;
    private Class<?> mBoundClass;
    TypeConverter mConverter;
    private float[] mPathData;          /* a path to animate along: Path.approximate's (fraction, x, y) triples */
    private int mPathComponent = -1;    /* -1: the point itself (through the converter), 0 / 1: its x / y as a float or int */

    private PropertyValuesHolder(String name) { mName = name; }
    private PropertyValuesHolder(Property p) { mProperty = p; if (p != null) mName = p.getName(); }
    public static PropertyValuesHolder ofFloat(String name, float... v) { PropertyValuesHolder h = new PropertyValuesHolder(name); h.setFloatValues(v); return h; }
    public static PropertyValuesHolder ofFloat(Property<?, Float> p, float... v) { PropertyValuesHolder h = new PropertyValuesHolder(p); h.setFloatValues(v); return h; }
    public static PropertyValuesHolder ofInt(String name, int... v) { PropertyValuesHolder h = new PropertyValuesHolder(name); h.setIntValues(v); return h; }
    public static PropertyValuesHolder ofInt(Property<?, Integer> p, int... v) { PropertyValuesHolder h = new PropertyValuesHolder(p); h.setIntValues(v); return h; }
    public static PropertyValuesHolder ofObject(String name, TypeEvaluator e, Object... v) { PropertyValuesHolder h = new PropertyValuesHolder(name); h.setObjectValues(v); h.mEvaluator = e; return h; }
    public static <V> PropertyValuesHolder ofObject(Property p, TypeEvaluator<V> e, V... v) { PropertyValuesHolder h = new PropertyValuesHolder(p); h.setObjectValues(v); h.mEvaluator = e; return h; }
    public static <V> PropertyValuesHolder ofObject(Property<?, V> p, TypeConverter<android.graphics.PointF, V> c, android.graphics.Path path) { PropertyValuesHolder h = new PropertyValuesHolder(p); h.setPath(path, -1, android.graphics.PointF.class); h.mConverter = c; return h; }
    public static PropertyValuesHolder ofObject(String name, TypeConverter<android.graphics.PointF, ?> c, android.graphics.Path path) { PropertyValuesHolder h = new PropertyValuesHolder(name); h.setPath(path, -1, android.graphics.PointF.class); h.mConverter = c; return h; }
    public static <T, V> PropertyValuesHolder ofObject(Property<?, V> p, TypeConverter<T, V> c, TypeEvaluator<T> e, T... v) { PropertyValuesHolder h = new PropertyValuesHolder(p); h.setObjectValues(v); h.mEvaluator = e; h.mConverter = c; return h; }
    /** Husk: x (0) or y (1) along the path, for ObjectAnimator's two-property path forms. */
    static PropertyValuesHolder ofPathComponent(String name, Property p, android.graphics.Path path, int component, Class<?> type) { PropertyValuesHolder h = p != null ? new PropertyValuesHolder(p) : new PropertyValuesHolder(name); h.setPath(path, component, type); return h; }
    private void setPath(android.graphics.Path path, int component, Class<?> type) { mPathData = path.approximate(0.5f); mPathComponent = component; mType = type; mValues = null; }
    /** The point at fraction f along the path, between the two nearest of its sampled points. */
    private android.graphics.PointF pointAt(float f) {
        float[] d = mPathData; int n = d.length / 3;
        if (f <= 0 || n < 2) return new android.graphics.PointF(d[1], d[2]);
        if (f >= 1) return new android.graphics.PointF(d[3 * (n - 1) + 1], d[3 * (n - 1) + 2]);
        int lo = 0, hi = n - 1;
        while (hi - lo > 1) { int mid = (lo + hi) / 2; if (d[3 * mid] <= f) lo = mid; else hi = mid; }
        float span = d[3 * hi] - d[3 * lo], t = span > 0 ? (f - d[3 * lo]) / span : 0;
        return new android.graphics.PointF(d[3 * lo + 1] + t * (d[3 * hi + 1] - d[3 * lo + 1]), d[3 * lo + 2] + t * (d[3 * hi + 2] - d[3 * lo + 2]));
    }
    public static PropertyValuesHolder ofKeyframe(String name, Keyframe... k) {
        PropertyValuesHolder h = new PropertyValuesHolder(name);
        Object[] v = new Object[k.length]; for (int i = 0; i < k.length; i++) v[i] = k[i].getValue();
        h.mValues = v; h.mType = k.length > 0 && k[0].getValue() instanceof Integer ? int.class : float.class;
        return h;
    }
    public static PropertyValuesHolder ofKeyframe(Property p, Keyframe... k) { PropertyValuesHolder h = ofKeyframe(p.getName(), k); h.mProperty = p; return h; }
    public static PropertyValuesHolder ofMultiFloat(String name, float[][] v) { return ofFloat(name, v.length > 0 ? v[0] : new float[0]); }
    public void setFloatValues(float... v) { mType = float.class; mValues = new Object[v.length]; for (int i = 0; i < v.length; i++) mValues[i] = v[i]; }
    public void setIntValues(int... v) { mType = int.class; mValues = new Object[v.length]; for (int i = 0; i < v.length; i++) mValues[i] = v[i]; }
    public void setObjectValues(Object... v) { mType = v.length > 0 && v[0] != null ? v[0].getClass() : Object.class; mValues = v; }
    public void setEvaluator(TypeEvaluator e) { mEvaluator = e; }
    public void setPropertyName(String n) { mName = n; }
    public String getPropertyName() { return mName; }
    public void setProperty(Property p) { mProperty = p; }
    public Object getAnimatedValue() { return mAnimated; }

    @SuppressWarnings("unchecked")
    void calculate(float f) {
        if (mPathData != null) {
            android.graphics.PointF p = pointAt(f);
            if (mPathComponent == 0) mAnimated = mType == int.class ? (Object) Math.round(p.x) : (Object) p.x;
            else if (mPathComponent == 1) mAnimated = mType == int.class ? (Object) Math.round(p.y) : (Object) p.y;
            else mAnimated = mConverter != null ? mConverter.convert(p) : p;
            return;
        }
        if (mValues == null || mValues.length == 0) return;
        if (mValues.length == 1) { mAnimated = mValues[0]; return; }
        float scaled = f * (mValues.length - 1);
        int i = Math.max(0, Math.min(mValues.length - 2, (int) Math.floor(scaled)));
        float local = scaled - i;
        Object a = mValues[i], b = mValues[i + 1];
        if (mEvaluator != null) mAnimated = mEvaluator.evaluate(local, a, b);
        else if (a instanceof Float) mAnimated = (Float) a + local * ((Float) b - (Float) a);
        else if (a instanceof Integer) mAnimated = (int) ((Integer) a + local * ((Integer) b - (Integer) a));
        else mAnimated = local < 1 ? a : b;
        if (mConverter != null) mAnimated = mConverter.convert(mAnimated);
    }
    private static String setterName(String prefix, String n) { return prefix + Character.toUpperCase(n.charAt(0)) + n.substring(1); }
    private Method find(Class<?> c, String name, Class<?>... args) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try { Method m = k.getDeclaredMethod(name, args); m.setAccessible(true); return m; } catch (NoSuchMethodException e) {}
        }
        try { return c.getMethod(name, args); } catch (NoSuchMethodException e) { return null; }
    }
    private void bind(Object target) {
        if (mBoundClass == target.getClass() || mProperty != null || mName == null) return;
        mBoundClass = target.getClass();
        Class<?> t = mType == Float.class ? float.class : mType == Integer.class ? int.class : mType;
        mSetter = find(mBoundClass, setterName("set", mName), t);
        if (mSetter == null && t == float.class) mSetter = find(mBoundClass, setterName("set", mName), Float.class);
        if (mSetter == null && t == int.class) mSetter = find(mBoundClass, setterName("set", mName), Integer.class);
        if (mSetter == null) mSetter = find(mBoundClass, setterName("set", mName), Object.class);
        if (mSetter == null) for (Method m : mBoundClass.getMethods()) if (m.getName().equals(setterName("set", mName)) && m.getParameterTypes().length == 1) { mSetter = m; break; }
        mGetter = find(mBoundClass, setterName("get", mName));
    }
    @SuppressWarnings("unchecked")
    void setupStart(Object target) {
        if (target == null || mValues == null || mValues.length != 1) return;
        Object start = null;
        try {
            if (mProperty != null) start = mProperty.get(target);
            else { bind(target); if (mGetter != null) start = mGetter.invoke(target); }
        } catch (Exception e) {}
        if (start != null) mValues = new Object[] { start, mValues[0] };
    }
    @SuppressWarnings("unchecked")
    void apply(Object target) {
        if (target == null || mAnimated == null) return;
        try {
            if (mProperty != null) mProperty.set(target, mAnimated);
            else { bind(target); if (mSetter != null) mSetter.invoke(target, mAnimated); }
        } catch (Exception e) { android.util.Log.w("PropertyValuesHolder", "cannot set " + mName + " on " + target.getClass().getName(), e); }
    }
    @Override public PropertyValuesHolder clone() { try { PropertyValuesHolder h = (PropertyValuesHolder) super.clone(); h.mValues = mValues == null ? null : mValues.clone(); h.mBoundClass = null; h.mSetter = h.mGetter = null; return h; } catch (CloneNotSupportedException e) { throw new AssertionError(); } }
    // ---- generated by tools/compat/fillmembers.py: the platform's members this class does not write (signatures only)
    public static android.animation.PropertyValuesHolder ofMultiFloat(java.lang.String p0, android.animation.TypeConverter p1, android.animation.TypeEvaluator p2, android.animation.Keyframe[] p3) { return null; }
    public static android.animation.PropertyValuesHolder ofMultiFloat(java.lang.String p0, android.animation.TypeConverter p1, android.animation.TypeEvaluator p2, java.lang.Object[] p3) { return null; }
    public static android.animation.PropertyValuesHolder ofMultiFloat(java.lang.String p0, android.graphics.Path p1) { return null; }
    public static android.animation.PropertyValuesHolder ofMultiInt(java.lang.String p0, android.animation.TypeConverter p1, android.animation.TypeEvaluator p2, android.animation.Keyframe[] p3) { return null; }
    public static android.animation.PropertyValuesHolder ofMultiInt(java.lang.String p0, android.animation.TypeConverter p1, android.animation.TypeEvaluator p2, java.lang.Object[] p3) { return null; }
    public static android.animation.PropertyValuesHolder ofMultiInt(java.lang.String p0, android.graphics.Path p1) { return null; }
    public static android.animation.PropertyValuesHolder ofMultiInt(java.lang.String p0, int[][] p1) { return null; }
    public void getPropertyValues(android.animation.PropertyValuesHolder.PropertyValues p0) {}
    public java.lang.Class getValueType() { return mType; }
    public void setConverter(android.animation.TypeConverter c) { mConverter = c; }
    public void setKeyframes(android.animation.Keyframe[] p0) {}
    // ---- end of generated members
    // ---- generated by tools/compat/genstubs.py: the platform's nested classes this class does not write
    public static class PropertyValues {
        private final java.util.HashMap<String, Object> huskProps = new java.util.HashMap<>();
        public android.animation.PropertyValuesHolder.PropertyValues.DataSource dataSource;
        public java.lang.Object endValue;
        public java.lang.String propertyName;
        public java.lang.Object startValue;
        public java.lang.Class type;
        public PropertyValues() {}
        public interface DataSource {
            java.lang.Object getValueAtFraction(float p0);
        }
    }
    // ---- end of generated nested classes
}
