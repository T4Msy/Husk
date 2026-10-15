package android.preference;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Husk: the views Android inflates from its private preference layouts (preference_material, preference_category_material,
 * preference_dialog_edittext, preference_list_content), built in code with the same metrics. Those layouts use internal
 * widgets (PreferenceImageView...) that apps never see, so only the public ids they carry matter: title, summary, icon,
 * widget_frame, checkbox, switch_widget, message, list.
 */
final class PreferenceViews {
    private PreferenceViews() {}
    /** Tags for the internal ids (icon_frame, edittext_container) apps cannot name. */
    static final String TAG_ICON_FRAME = "husk:icon_frame", TAG_EDITTEXT_CONTAINER = "husk:edittext_container";

    static int dp(Context c, float v) { return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f); }
    /** Framework resource ids from the theme's preference styles name private layouts; only the app's own are inflated. */
    static int appRes(int id) { return (id >>> 24) == 0x01 ? 0 : id; }

    static int color(Context c, int attr, int def) {
        TypedValue v = new TypedValue();
        try {
            if (!c.getTheme().resolveAttribute(attr, v, true)) return def;
            if (v.type >= TypedValue.TYPE_FIRST_COLOR_INT && v.type <= TypedValue.TYPE_LAST_COLOR_INT) return v.data;
            if (v.resourceId != 0) return c.getColorStateList(v.resourceId).getDefaultColor();
        } catch (RuntimeException e) {}
        return def;
    }
    /** A theme text color as its state list when it is one (so disabled rows dim as Android's do). */
    static void textColor(TextView t, int attr, int def) {
        Context c = t.getContext();
        TypedValue v = new TypedValue();
        try {
            if (c.getTheme().resolveAttribute(attr, v, true)) {
                if (v.type >= TypedValue.TYPE_FIRST_COLOR_INT && v.type <= TypedValue.TYPE_LAST_COLOR_INT) { t.setTextColor(v.data); return; }
                if (v.resourceId != 0) { ColorStateList l = c.getColorStateList(v.resourceId); if (l != null) { t.setTextColor(l); return; } }
            }
        } catch (RuntimeException e) {}
        t.setTextColor(def);
    }
    static int dimen(Context c, int attr, int defDp) {
        TypedValue v = new TypedValue();
        try { if (c.getTheme().resolveAttribute(attr, v, true) && v.type == TypedValue.TYPE_DIMENSION) return TypedValue.complexToDimensionPixelSize(v.data, c.getResources().getDisplayMetrics()); }
        catch (RuntimeException e) {}
        return dp(c, defDp);
    }

    /** preference_material: [icon frame] [title over summary] [widget frame], 16dp from the edges, at least a small list item tall. */
    static View row(Context c) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dimen(c, android.R.attr.listPreferredItemHeightSmall, 48));
        row.setPadding(dimen(c, android.R.attr.listPreferredItemPaddingStart, 16), 0, dimen(c, android.R.attr.listPreferredItemPaddingEnd, 16), 0);
        row.setClipToPadding(false);

        LinearLayout iconFrame = new LinearLayout(c);
        iconFrame.setTag(TAG_ICON_FRAME);
        iconFrame.setOrientation(LinearLayout.HORIZONTAL);
        iconFrame.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        iconFrame.setMinimumWidth(dp(c, 56));
        iconFrame.setPadding(0, dp(c, 4), dp(c, 8), dp(c, 4));
        ImageView icon = new ImageView(c);
        icon.setId(android.R.id.icon);
        icon.setAdjustViewBounds(true);
        icon.setMaxWidth(dp(c, 48)); icon.setMaxHeight(dp(c, 48));
        iconFrame.addView(icon, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(iconFrame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(0, dp(c, 16), 0, dp(c, 16));
        TextView title = new TextView(c);
        title.setId(android.R.id.title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        textColor(title, android.R.attr.textColorPrimary, 0xDE000000);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView summary = new TextView(c);
        summary.setId(android.R.id.summary);
        summary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        textColor(summary, android.R.attr.textColorSecondary, 0x8A000000);
        summary.setMaxLines(10);
        texts.addView(summary, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout widgetFrame = new LinearLayout(c);
        widgetFrame.setId(android.R.id.widget_frame);
        widgetFrame.setOrientation(LinearLayout.VERTICAL);
        widgetFrame.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        widgetFrame.setPadding(dp(c, 16), 0, 0, 0);
        row.addView(widgetFrame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return row;
    }

    /** preference_category_material: the title alone, in the accent color, medium weight. */
    static View category(Context c) {
        TextView t = new TextView(c);
        t.setId(android.R.id.title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setTextColor(color(c, android.R.attr.colorAccent, 0xFF6750A4));
        t.setPadding(dimen(c, android.R.attr.listPreferredItemPaddingStart, 16), dp(c, 16), dimen(c, android.R.attr.listPreferredItemPaddingEnd, 16), dp(c, 8));
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return t;
    }

    /** preference_dialog_edittext: the dialog message over the container the EditText goes in. */
    static View editTextDialog(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(c, 20), dp(c, 4), dp(c, 20), dp(c, 4));
        TextView message = new TextView(c);
        message.setId(android.R.id.message);
        message.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        textColor(message, android.R.attr.textColorSecondary, 0x8A000000);
        message.setPadding(dp(c, 4), 0, dp(c, 4), dp(c, 12));
        l.addView(message, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout container = new LinearLayout(c);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setTag(TAG_EDITTEXT_CONTAINER);
        l.addView(container, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return l;
    }

    /** A list for a screen of preferences: no dividers between rows (Material's preference lists draw none). */
    static android.widget.ListView list(Context c) {
        android.widget.ListView lv = new android.widget.ListView(c);
        lv.setId(android.R.id.list);
        lv.setDivider(null);
        lv.setClipToPadding(false);
        return lv;
    }

    /** A title bar for a nested screen's dialog when its window shows none (a theme without an action bar). */
    static View titleBar(Context c, CharSequence title) {
        TextView t = new TextView(c);
        t.setText(title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        textColor(t, android.R.attr.textColorPrimary, 0xDE000000);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setMinimumHeight(dp(c, 56));
        t.setPadding(dp(c, 16), 0, dp(c, 16), 0);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return t;
    }

    static FrameLayout frame(Context c, int id) { FrameLayout f = new FrameLayout(c); if (id != 0) f.setId(id); return f; }
}
