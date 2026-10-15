package android.preference;

/**
 * AOSP's generic XML-to-item inflater (hidden but for this shape). Husk inflates preference XML with PreferenceInflater; this
 * keeps the public type and its Parent interface, which PreferenceGroup implements to receive inflated children.
 */
@SuppressWarnings({"unchecked", "rawtypes", "deprecation"})
public abstract class GenericInflater {
    protected GenericInflater() {}
    public interface Parent<T> {
        void addItemFromInflater(T child);
    }
}
