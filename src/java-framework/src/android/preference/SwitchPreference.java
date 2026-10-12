package android.preference;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.View;
import android.widget.Checkable;
import android.widget.CompoundButton;
import android.widget.Switch;

/** A two-state preference shown with a switch at the row's end, as AOSP's. */
@SuppressWarnings({"unchecked", "rawtypes", "deprecation"})
public class SwitchPreference extends TwoStatePreference {
    private final Listener mListener = new Listener();
    private CharSequence mSwitchOn, mSwitchOff;

    /** The switch moved by itself (a drag, when an app's layout makes it clickable): the change listener can refuse it. */
    private class Listener implements CompoundButton.OnCheckedChangeListener {
        public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
            if (!callChangeListener(isChecked)) { buttonView.setChecked(!isChecked); return; }
            SwitchPreference.this.setChecked(isChecked);
        }
    }

    public SwitchPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        readTwoStateAttrs(context, attrs, defStyleAttr, defStyleRes);
        if (context != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, new int[] { android.R.attr.switchTextOn, android.R.attr.switchTextOff }, defStyleAttr, defStyleRes);
            setSwitchTextOn(a.getString(0));
            setSwitchTextOff(a.getString(1));
            a.recycle();
        }
    }
    public SwitchPreference(Context context, AttributeSet attrs, int defStyleAttr) { this(context, attrs, defStyleAttr, 0); }
    public SwitchPreference(Context context, AttributeSet attrs) { this(context, attrs, android.R.attr.switchPreferenceStyle); }
    public SwitchPreference(Context context) { this(context, null); }
    @Override public void onBindView(View view) {
        super.onBindView(view);
        View checkableView = view.findViewById(android.R.id.switch_widget);
        if (checkableView instanceof Checkable) {
            if (checkableView instanceof Switch) ((Switch) checkableView).setOnCheckedChangeListener(null);
            ((Checkable) checkableView).setChecked(mChecked);
            if (checkableView instanceof Switch) {
                final Switch switchView = (Switch) checkableView;
                switchView.setTextOn(mSwitchOn);
                switchView.setTextOff(mSwitchOff);
                switchView.setOnCheckedChangeListener(mListener);
            }
        }
        syncSummaryView(view);
    }
    /** preference_widget_switch: the row's click toggles it, so the switch takes no clicks or focus. */
    @Override View huskDefaultWidget(Context c) {
        Switch s = new Switch(c);
        s.setId(android.R.id.switch_widget);
        s.setFocusable(false);
        s.setClickable(false);
        s.setBackground(null);
        return s;
    }
    public void setSwitchTextOn(CharSequence onText) { mSwitchOn = onText; notifyChanged(); }
    public void setSwitchTextOff(CharSequence offText) { mSwitchOff = offText; notifyChanged(); }
    public void setSwitchTextOn(int resId) { setSwitchTextOn(getContext().getString(resId)); }
    public void setSwitchTextOff(int resId) { setSwitchTextOff(getContext().getString(resId)); }
    public CharSequence getSwitchTextOn() { return mSwitchOn; }
    public CharSequence getSwitchTextOff() { return mSwitchOff; }
}
