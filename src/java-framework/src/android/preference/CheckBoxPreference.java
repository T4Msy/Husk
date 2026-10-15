package android.preference;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.CheckBox;
import android.widget.Checkable;

/** A two-state preference shown with a check box at the row's end, as AOSP's. */
@SuppressWarnings({"unchecked", "rawtypes", "deprecation"})
public class CheckBoxPreference extends TwoStatePreference {
    public CheckBoxPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        readTwoStateAttrs(context, attrs, defStyleAttr, defStyleRes);
    }
    public CheckBoxPreference(Context context, AttributeSet attrs, int defStyleAttr) { this(context, attrs, defStyleAttr, 0); }
    public CheckBoxPreference(Context context, AttributeSet attrs) { this(context, attrs, android.R.attr.checkBoxPreferenceStyle); }
    public CheckBoxPreference(Context context) { this(context, null); }
    @Override public void onBindView(View view) {
        super.onBindView(view);
        View checkboxView = view.findViewById(android.R.id.checkbox);
        if (checkboxView instanceof Checkable) ((Checkable) checkboxView).setChecked(mChecked);
        syncSummaryView(view);
    }
    /** preference_widget_checkbox: the row's click toggles it, so the box itself takes no clicks or focus. */
    @Override View huskDefaultWidget(Context c) {
        CheckBox b = new CheckBox(c);
        b.setId(android.R.id.checkbox);
        b.setFocusable(false);
        b.setClickable(false);
        return b;
    }
}
