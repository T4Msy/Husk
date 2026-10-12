package android.preference;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

/** A titled group whose children show in the same list under its header, as AOSP's: the header is never enabled or clickable. */
@SuppressWarnings({"unchecked", "rawtypes", "deprecation"})
public class PreferenceCategory extends PreferenceGroup {
    public PreferenceCategory(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) { super(context, attrs, defStyleAttr, defStyleRes); }
    public PreferenceCategory(Context context, AttributeSet attrs, int defStyleAttr) { this(context, attrs, defStyleAttr, 0); }
    public PreferenceCategory(Context context, AttributeSet attrs) { this(context, attrs, android.R.attr.preferenceCategoryStyle); }
    public PreferenceCategory(Context context) { this(context, null); }
    @Override public boolean onPrepareAddPreference(Preference preference) {
        if (preference instanceof PreferenceCategory) throw new IllegalArgumentException("Cannot add a " + PreferenceCategory.class.getSimpleName() + " directly to a " + PreferenceCategory.class.getSimpleName());
        return super.onPrepareAddPreference(preference);
    }
    @Override public boolean isEnabled() { return false; }
    @Override public boolean shouldDisableDependents() { return !super.isEnabled(); }
    @Override View huskDefaultView(Context c) { return PreferenceViews.category(c); }
}
