package android.animation;

/** Converts the values an animation produces (T) into the ones a property takes (V), as PropertyValuesHolder.setConverter uses it. */
public abstract class TypeConverter<T, V> {
    private final Class<T> mFromClass;
    private final Class<V> mToClass;
    public TypeConverter(Class<T> fromClass, Class<V> toClass) { mFromClass = fromClass; mToClass = toClass; }
    Class<T> getSourceType() { return mFromClass; }
    Class<V> getTargetType() { return mToClass; }
    public abstract V convert(T value);
}
