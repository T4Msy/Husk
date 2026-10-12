package android.preference;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.net.Uri;
import android.os.Bundle;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.util.Xml;
import android.view.InflateException;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

/**
 * AOSP's (hidden) PreferenceInflater over GenericInflater: preference XML into a hierarchy. Each element is a class: a bare name
 * in android.preference, a qualified one (the app's own preferences) through the app's class loader, made with its (Context,
 * AttributeSet) constructor. <intent> and <extra> set the parent's intent and extras. Unlike Android, an element that cannot be
 * made (an unknown class, a constructor that throws) is logged and skipped instead of failing the whole screen.
 */
final class PreferenceInflater {
    private static final String TAG = "PreferenceInflater";
    private static final String DEFAULT_PACKAGE = "android.preference.", INTENT_TAG_NAME = "intent", EXTRA_TAG_NAME = "extra", CATEGORIES_TAG_NAME = "categories";
    private static final Class<?>[] CONSTRUCTOR_SIGNATURE = { Context.class, AttributeSet.class };
    private static final HashMap<String, Constructor<?>> sConstructorMap = new HashMap<String, Constructor<?>>();
    private static final int[] INTENT_ATTRS = { android.R.attr.action, android.R.attr.data, android.R.attr.mimeType, android.R.attr.targetPackage, android.R.attr.targetClass };
    private static final int[] EXTRA_ATTRS = { android.R.attr.name, android.R.attr.value };
    private static final int[] NAME_ATTRS = { android.R.attr.name };

    private final Context mContext;
    private final PreferenceManager mPreferenceManager;

    PreferenceInflater(Context context, PreferenceManager preferenceManager) { mContext = context; mPreferenceManager = preferenceManager; }

    Preference inflate(int resource, PreferenceGroup root) {
        XmlResourceParser parser = mContext.getResources().getXml(resource);
        try { return inflate(parser, root); } finally { parser.close(); }
    }
    Preference inflate(XmlPullParser parser, PreferenceGroup root) {
        final AttributeSet attrs = Xml.asAttributeSet(parser);
        try {
            int type;
            while ((type = parser.next()) != XmlPullParser.START_TAG && type != XmlPullParser.END_DOCUMENT) {}
            if (type != XmlPullParser.START_TAG) throw new InflateException(parser.getPositionDescription() + ": No start tag found!");
            Preference xmlRoot = null;
            try { xmlRoot = createItemFromTag(parser.getName(), attrs); }
            catch (RuntimeException e) { if (root == null) throw e; Log.w(TAG, "root <" + parser.getName() + ">: " + e + "; inflating its children into the given root"); }
            final Preference result = onMergeRoots(root, xmlRoot);
            rInflate(parser, result, attrs);
            return result;
        } catch (XmlPullParserException e) {
            InflateException ie = new InflateException(e.getMessage()); ie.initCause(e); throw ie;
        } catch (IOException e) {
            InflateException ie = new InflateException(parser.getPositionDescription() + ": " + e.getMessage()); ie.initCause(e); throw ie;
        }
    }
    /** Into a given root, the XML's root element only carries the children; else it is the new root, attached to the manager. */
    private Preference onMergeRoots(PreferenceGroup givenRoot, Preference xmlRoot) {
        if (givenRoot == null) { xmlRoot.onAttachedToHierarchy(mPreferenceManager); return xmlRoot; }
        return givenRoot;
    }
    private void rInflate(XmlPullParser parser, Preference parent, AttributeSet attrs) throws XmlPullParserException, IOException {
        final int depth = parser.getDepth();
        int type;
        while (((type = parser.next()) != XmlPullParser.END_TAG || parser.getDepth() > depth) && type != XmlPullParser.END_DOCUMENT) {
            if (type != XmlPullParser.START_TAG) continue;
            if (onCreateCustomFromTag(parser, parent, attrs)) continue;
            final String name = parser.getName();
            if (!(parent instanceof PreferenceGroup)) { Log.w(TAG, parser.getPositionDescription() + ": <" + name + "> inside " + parent.getClass().getName() + ", which is not a PreferenceGroup: skipped"); skipCurrentTag(parser); continue; }
            Preference item;
            try { item = createItemFromTag(name, attrs); }
            catch (RuntimeException e) { Log.w(TAG, "skipping <" + name + ">: " + e, e.getCause() != null ? e.getCause() : e); skipCurrentTag(parser); continue; }
            try { ((PreferenceGroup) parent).addItemFromInflater(item); }
            catch (RuntimeException e) { Log.w(TAG, "could not add <" + name + "> to " + parent.getClass().getName() + ": " + e); skipCurrentTag(parser); continue; }
            rInflate(parser, item, attrs);
        }
    }
    /** <intent> and <extra>: the parent's intent and extras rather than items. */
    private boolean onCreateCustomFromTag(XmlPullParser parser, Preference parentPreference, AttributeSet attrs) throws XmlPullParserException, IOException {
        final String tag = parser.getName();
        if (INTENT_TAG_NAME.equals(tag)) {
            Intent intent = parseIntent(parser, attrs);
            if (intent != null) parentPreference.setIntent(intent);
            return true;
        } else if (EXTRA_TAG_NAME.equals(tag)) {
            parseBundleExtra(attrs, parentPreference.getExtras());
            skipCurrentTag(parser);
            return true;
        }
        return false;
    }

    private Preference createItemFromTag(String name, AttributeSet attrs) {
        final String full = name.indexOf('.') == -1 ? DEFAULT_PACKAGE + name : name;
        Constructor<?> constructor;
        synchronized (sConstructorMap) { constructor = sConstructorMap.get(full); }
        try {
            if (constructor == null) {
                ClassLoader cl = mContext.getClassLoader();
                Class<?> clazz = Class.forName(full, false, cl != null ? cl : PreferenceInflater.class.getClassLoader());
                if (!Preference.class.isAssignableFrom(clazz)) throw new InflateException(attrs.getPositionDescription() + ": Class is not a Preference " + full);
                try { constructor = clazz.getConstructor(CONSTRUCTOR_SIGNATURE); }
                catch (NoSuchMethodException e) { constructor = clazz.getDeclaredConstructor(CONSTRUCTOR_SIGNATURE); }
                constructor.setAccessible(true);
                synchronized (sConstructorMap) { sConstructorMap.put(full, constructor); }
            }
            return (Preference) constructor.newInstance(mContext, attrs);
        } catch (InflateException e) {
            throw e;
        } catch (InvocationTargetException e) {
            Throwable t = e.getCause();
            InflateException ie = new InflateException(attrs.getPositionDescription() + ": Error inflating class " + full + (t != null ? ": " + t : ""));
            ie.initCause(t != null ? t : e);
            throw ie;
        } catch (ClassNotFoundException e) {
            InflateException ie = new InflateException(attrs.getPositionDescription() + ": Error inflating class (not found) " + full);
            ie.initCause(e);
            throw ie;
        } catch (Exception e) {
            InflateException ie = new InflateException(attrs.getPositionDescription() + ": Error inflating class " + full + ": " + e);
            ie.initCause(e);
            throw ie;
        } catch (LinkageError e) {
            InflateException ie = new InflateException(attrs.getPositionDescription() + ": Error inflating class " + full + ": " + e);
            ie.initCause(e);
            throw ie;
        }
    }

    /** As Intent.parseIntent: action, data, mimeType, targetPackage/targetClass, then <categories> and <extra> children. */
    private Intent parseIntent(XmlPullParser parser, AttributeSet attrs) throws XmlPullParserException, IOException {
        Intent intent = new Intent();
        TypedArray sa = mContext.getResources().obtainAttributes(attrs, INTENT_ATTRS);
        intent.setAction(sa.getString(0));
        String data = sa.getString(1), mimeType = sa.getString(2);
        intent.setDataAndType(data != null ? Uri.parse(data) : null, mimeType);
        String packageName = sa.getString(3), className = sa.getString(4);
        if (packageName != null && className != null) intent.setComponent(new ComponentName(packageName, className));
        sa.recycle();
        final int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) continue;
            String nodeName = parser.getName();
            if (CATEGORIES_TAG_NAME.equals(nodeName)) {
                sa = mContext.getResources().obtainAttributes(attrs, NAME_ATTRS);
                String cat = sa.getString(0);
                sa.recycle();
                if (cat != null) intent.addCategory(cat);
            } else if (EXTRA_TAG_NAME.equals(nodeName)) {
                Bundle extras = new Bundle();
                parseBundleExtra(attrs, extras);
                intent.putExtras(extras);
            }
            skipCurrentTag(parser);
        }
        return intent;
    }
    /** As Resources.parseBundleExtra: <extra android:name android:value>, the value's type giving the bundle entry's. */
    private void parseBundleExtra(AttributeSet attrs, Bundle outBundle) {
        TypedArray sa = mContext.getResources().obtainAttributes(attrs, EXTRA_ATTRS);
        try {
            String name = sa.getString(0);
            if (name == null) { Log.w(TAG, "<extra> requires an android:name attribute at " + attrs.getPositionDescription()); return; }
            TypedValue v = sa.peekValue(1);
            if (v == null) { Log.w(TAG, "<extra> requires an android:value or android:resource attribute at " + attrs.getPositionDescription()); return; }
            if (v.type == TypedValue.TYPE_STRING) outBundle.putCharSequence(name, v.coerceToString());
            else if (v.type == TypedValue.TYPE_INT_BOOLEAN) outBundle.putBoolean(name, v.data != 0);
            else if (v.type >= TypedValue.TYPE_FIRST_INT && v.type <= TypedValue.TYPE_LAST_INT) outBundle.putInt(name, v.data);
            else if (v.type == TypedValue.TYPE_FLOAT) outBundle.putFloat(name, v.getFloat());
            else Log.w(TAG, "<extra> only supports string, integer, float, color, and boolean at " + attrs.getPositionDescription());
        } finally { sa.recycle(); }
    }
    private static void skipCurrentTag(XmlPullParser parser) throws XmlPullParserException, IOException {
        final int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {}
    }
}
