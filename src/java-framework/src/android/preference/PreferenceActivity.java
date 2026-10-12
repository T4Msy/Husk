package android.preference;

import android.app.Fragment;
import android.app.FragmentTransaction;
import android.app.ListActivity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.util.Xml;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

/**
 * The (deprecated) settings activity, as AOSP's on a phone (single pane). Either the old way: a preference hierarchy
 * (addPreferencesFromResource / setPreferenceScreen) bound to the activity's list; or headers (onBuildHeaders /
 * loadHeadersFromResource) listed, a click showing the header's fragment in place of the list (back returns to it); an intent
 * with EXTRA_SHOW_FRAGMENT opens on that fragment alone.
 */
@SuppressWarnings({"unchecked", "rawtypes", "deprecation"})
public abstract class PreferenceActivity extends ListActivity implements PreferenceManager.OnPreferenceTreeClickListener, PreferenceFragment.OnPreferenceStartFragmentCallback {
    private static final String TAG = "PreferenceActivity";
    private static final String HEADERS_TAG = ":android:headers", CUR_HEADER_TAG = ":android:cur_header", PREFERENCES_TAG = ":android:preferences", BACK_STACK_PREFS = ":android:prefs";
    public static final String EXTRA_SHOW_FRAGMENT = ":android:show_fragment";
    public static final String EXTRA_SHOW_FRAGMENT_ARGUMENTS = ":android:show_fragment_args";
    public static final String EXTRA_SHOW_FRAGMENT_TITLE = ":android:show_fragment_title";
    public static final String EXTRA_SHOW_FRAGMENT_SHORT_TITLE = ":android:show_fragment_short_title";
    public static final String EXTRA_NO_HEADERS = ":android:no_headers";
    private static final String EXTRA_PREFS_SHOW_BUTTON_BAR = "extra_prefs_show_button_bar";
    public static final long HEADER_ID_UNDEFINED = -1;
    /** The request codes preferences (a RingtonePreference) start activities with. */
    private static final int FIRST_REQUEST_CODE = 100;

    private final ArrayList<Header> mHeaders = new ArrayList<Header>();
    private FrameLayout mListFooter;
    private ViewGroup mPrefsContainer, mHeadersContainer;
    private int mPrefsContainerId;
    private boolean mSinglePane = true;
    private Header mCurHeader;
    private CharSequence mActivityTitle;
    private PreferenceManager mPreferenceManager;
    private Bundle mSavedInstanceState;
    private Button mNextButton;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean mBindPosted;
    private final Runnable mBindPreferences = new Runnable() { public void run() { mBindPosted = false; bindPreferences(); } };

    public PreferenceActivity() { super(); }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        /* preference_list_content: the headers (the list, a footer) and the frame a header's fragment shows in */
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        LinearLayout headers = new LinearLayout(this);
        headers.setOrientation(LinearLayout.VERTICAL);
        ListView list = PreferenceViews.list(this);
        headers.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        mListFooter = PreferenceViews.frame(this, 0);
        headers.addView(mListFooter, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(headers, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        mPrefsContainerId = View.generateViewId();
        FrameLayout prefs = PreferenceViews.frame(this, mPrefsContainerId);
        prefs.setVisibility(View.GONE);
        root.addView(prefs, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        mHeadersContainer = headers;
        mPrefsContainer = prefs;
        setContentView(root);

        mSinglePane = onIsHidingHeaders() || !onIsMultiPane();
        mActivityTitle = getTitle();
        final Intent intent = getIntent();
        final String initialFragment = intent != null ? intent.getStringExtra(EXTRA_SHOW_FRAGMENT) : null;
        final Bundle initialArguments = intent != null ? intent.getBundleExtra(EXTRA_SHOW_FRAGMENT_ARGUMENTS) : null;
        final int initialTitle = intent != null ? intent.getIntExtra(EXTRA_SHOW_FRAGMENT_TITLE, 0) : 0;
        final int initialShortTitle = intent != null ? intent.getIntExtra(EXTRA_SHOW_FRAGMENT_SHORT_TITLE, 0) : 0;
        ArrayList<Header> saved = savedInstanceState != null ? savedInstanceState.<Header>getParcelableArrayList(HEADERS_TAG) : null;
        if (saved != null) {
            mHeaders.addAll(saved);
            int curHeader = savedInstanceState.getInt(CUR_HEADER_TAG, (int) HEADER_ID_UNDEFINED);
            if (curHeader >= 0 && curHeader < mHeaders.size()) setSelectedHeader(mHeaders.get(curHeader));
        } else if (!onIsHidingHeaders()) onBuildHeaders(mHeaders);
        /* Husk keeps no fragments across a recreate: the fragment asked for is shown again either way */
        if (initialFragment != null) switchToHeader(initialFragment, initialArguments);

        if (mHeaders.size() > 0) {
            setListAdapter(new HeaderAdapter(this, mHeaders));
        }
        if (initialFragment != null && mSinglePane) {
            // a header's fragment on its own: it replaces the headers
            mHeadersContainer.setVisibility(View.GONE);
            mPrefsContainer.setVisibility(View.VISIBLE);
            if (initialTitle != 0) {
                CharSequence initialTitleStr = getText(initialTitle);
                CharSequence initialShortTitleStr = initialShortTitle != 0 ? getText(initialShortTitle) : null;
                showBreadCrumbs(initialTitleStr, initialShortTitleStr);
            }
        } else if (mHeaders.size() == 0) {
            // no headers: the old "a screen of preferences" mode, in the list
            mPreferenceManager = new PreferenceManager(this, FIRST_REQUEST_CODE);
            mPreferenceManager.setOnPreferenceTreeClickListener(this);
            mHeadersContainer = null;
        }
        if (intent != null && intent.getBooleanExtra(EXTRA_PREFS_SHOW_BUTTON_BAR, false)) Log.w(TAG, "the button bar (extra_prefs_show_button_bar) is not shown");
    }

    /** Whether headers are shown (rather than a screen of preferences or one header's fragment). */
    public boolean hasHeaders() { return mHeadersContainer != null && mHeadersContainer.getVisibility() == View.VISIBLE; }
    public List<Header> getHeaders() { return mHeaders; }
    public boolean isMultiPane() { return !mSinglePane; }
    /** A phone: one pane. */
    public boolean onIsMultiPane() { return false; }
    public boolean onIsHidingHeaders() { Intent i = getIntent(); return i != null && i.getBooleanExtra(EXTRA_NO_HEADERS, false); }
    public Header onGetInitialHeader() {
        for (int i = 0; i < mHeaders.size(); i++) { Header h = mHeaders.get(i); if (h.fragment != null) return h; }
        throw new IllegalStateException("Must have at least one header with a fragment");
    }
    public Header onGetNewHeader() { return null; }
    /** Fill the headers (onCreate, invalidateHeaders); none by default (the old preference-screen mode). */
    public void onBuildHeaders(List<Header> target) {}
    public void invalidateHeaders() {
        mHeaders.clear();
        onBuildHeaders(mHeaders);
        if (getListAdapter() instanceof BaseAdapter) ((BaseAdapter) getListAdapter()).notifyDataSetChanged();
    }

    /** Headers from XML: <preference-headers> of <header android:title android:summary android:fragment ...> with <extra>/<intent>. */
    public void loadHeadersFromResource(int resid, List<Header> target) {
        XmlResourceParser parser = null;
        try {
            parser = getResources().getXml(resid);
            AttributeSet attrs = Xml.asAttributeSet(parser);
            int type;
            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT && type != XmlPullParser.START_TAG) {}
            String nodeName = parser.getName();
            if (!"preference-headers".equals(nodeName)) throw new RuntimeException("XML document must start with <preference-headers> tag; found" + nodeName + " at " + parser.getPositionDescription());
            Bundle curBundle = null;
            final int outerDepth = parser.getDepth();
            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
                if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) continue;
                nodeName = parser.getName();
                if ("header".equals(nodeName)) {
                    Header header = new Header();
                    TypedArray sa = obtainStyledAttributes(attrs, new int[] { android.R.attr.id, android.R.attr.title, android.R.attr.summary,
                        android.R.attr.breadCrumbTitle, android.R.attr.breadCrumbShortTitle, android.R.attr.icon, android.R.attr.fragment });
                    header.id = sa.getResourceId(0, (int) HEADER_ID_UNDEFINED);
                    TypedValue tv = sa.peekValue(1);
                    if (tv != null && tv.type == TypedValue.TYPE_STRING) { if (tv.resourceId != 0) header.titleRes = tv.resourceId; else header.title = tv.string; }
                    tv = sa.peekValue(2);
                    if (tv != null && tv.type == TypedValue.TYPE_STRING) { if (tv.resourceId != 0) header.summaryRes = tv.resourceId; else header.summary = tv.string; }
                    tv = sa.peekValue(3);
                    if (tv != null && tv.type == TypedValue.TYPE_STRING) { if (tv.resourceId != 0) header.breadCrumbTitleRes = tv.resourceId; else header.breadCrumbTitle = tv.string; }
                    tv = sa.peekValue(4);
                    if (tv != null && tv.type == TypedValue.TYPE_STRING) { if (tv.resourceId != 0) header.breadCrumbShortTitleRes = tv.resourceId; else header.breadCrumbShortTitle = tv.string; }
                    header.iconRes = sa.getResourceId(5, 0);
                    header.fragment = sa.getString(6);
                    sa.recycle();
                    if (curBundle == null) curBundle = new Bundle();
                    final int innerDepth = parser.getDepth();
                    while ((type = parser.next()) != XmlPullParser.END_DOCUMENT && (type != XmlPullParser.END_TAG || parser.getDepth() > innerDepth)) {
                        if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) continue;
                        String innerNodeName = parser.getName();
                        if ("extra".equals(innerNodeName)) { parseExtra(attrs, curBundle); skipCurrentTag(parser); }
                        else if ("intent".equals(innerNodeName)) header.intent = parseIntent(parser, attrs);
                        else skipCurrentTag(parser);
                    }
                    if (curBundle.size() > 0) { header.fragmentArguments = curBundle; curBundle = null; }
                    target.add(header);
                } else skipCurrentTag(parser);
            }
        } catch (XmlPullParserException e) {
            throw new RuntimeException("Error parsing headers", e);
        } catch (java.io.IOException e) {
            throw new RuntimeException("Error parsing headers", e);
        } finally {
            if (parser != null) parser.close();
        }
    }
    private void parseExtra(AttributeSet attrs, Bundle out) {
        TypedArray sa = getResources().obtainAttributes(attrs, new int[] { android.R.attr.name, android.R.attr.value });
        try {
            String name = sa.getString(0);
            TypedValue v = sa.peekValue(1);
            if (name == null || v == null) return;
            if (v.type == TypedValue.TYPE_STRING) out.putCharSequence(name, v.coerceToString());
            else if (v.type == TypedValue.TYPE_INT_BOOLEAN) out.putBoolean(name, v.data != 0);
            else if (v.type >= TypedValue.TYPE_FIRST_INT && v.type <= TypedValue.TYPE_LAST_INT) out.putInt(name, v.data);
            else if (v.type == TypedValue.TYPE_FLOAT) out.putFloat(name, v.getFloat());
        } finally { sa.recycle(); }
    }
    private Intent parseIntent(XmlPullParser parser, AttributeSet attrs) throws XmlPullParserException, java.io.IOException {
        TypedArray sa = getResources().obtainAttributes(attrs, new int[] { android.R.attr.action, android.R.attr.data, android.R.attr.mimeType, android.R.attr.targetPackage, android.R.attr.targetClass });
        Intent intent = new Intent();
        intent.setAction(sa.getString(0));
        String data = sa.getString(1), mimeType = sa.getString(2), pkg = sa.getString(3), cls = sa.getString(4);
        sa.recycle();
        intent.setDataAndType(data != null ? android.net.Uri.parse(data) : null, mimeType);
        if (pkg != null && cls != null) intent.setClassName(pkg, cls);
        final int depth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT && (type != XmlPullParser.END_TAG || parser.getDepth() > depth)) {
            if (type != XmlPullParser.START_TAG) continue;
            if ("extra".equals(parser.getName())) { Bundle b = new Bundle(); parseExtra(attrs, b); intent.putExtras(b); }
            else if ("categories".equals(parser.getName())) { TypedArray c = getResources().obtainAttributes(attrs, new int[] { android.R.attr.name }); String cat = c.getString(0); c.recycle(); if (cat != null) intent.addCategory(cat); }
            skipCurrentTag(parser);
        }
        return intent;
    }
    private static void skipCurrentTag(XmlPullParser parser) throws XmlPullParserException, java.io.IOException {
        final int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {}
    }

    /** Which fragments this activity may show from an intent (EXTRA_SHOW_FRAGMENT). Android requires an override; Husk allows any. */
    protected boolean isValidFragment(String fragmentName) { return true; }
    public void setListFooter(View view) {
        mListFooter.removeAllViews();
        mListFooter.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    @Override protected void onStop() { super.onStop(); if (mPreferenceManager != null) mPreferenceManager.dispatchActivityStop(); }
    @Override protected void onDestroy() {
        mHandler.removeCallbacks(mBindPreferences);
        mBindPosted = false;
        super.onDestroy();
        if (mPreferenceManager != null) mPreferenceManager.dispatchActivityDestroy();
    }
    @Override protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mHeaders.size() > 0) {
            outState.putParcelableArrayList(HEADERS_TAG, mHeaders);
            if (mCurHeader != null) { int index = mHeaders.indexOf(mCurHeader); if (index >= 0) outState.putInt(CUR_HEADER_TAG, index); }
        }
        if (mPreferenceManager != null) {
            final PreferenceScreen preferenceScreen = getPreferenceScreen();
            if (preferenceScreen != null) { Bundle container = new Bundle(); preferenceScreen.saveHierarchyState(container); outState.putBundle(PREFERENCES_TAG, container); }
        }
    }
    @Override protected void onRestoreInstanceState(Bundle state) {
        if (mPreferenceManager != null) {
            Bundle container = state.getBundle(PREFERENCES_TAG);
            if (container != null) {
                final PreferenceScreen preferenceScreen = getPreferenceScreen();
                if (preferenceScreen != null) { preferenceScreen.restoreHierarchyState(container); mSavedInstanceState = state; return; }
            }
        }
        super.onRestoreInstanceState(state);
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (mPreferenceManager != null) mPreferenceManager.dispatchActivityResult(requestCode, resultCode, data);
    }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); if (mPreferenceManager != null) mPreferenceManager.dispatchNewIntent(intent); }
    /** New content (the app's own layout with an android:id/list): the preferences are bound to its list. */
    @Override public void onContentChanged() {
        super.onContentChanged();
        if (mPreferenceManager != null) postBindPreferences();
    }

    @Override public void onListItemClick(ListView l, View v, int position, long id) {
        super.onListItemClick(l, v, position, id);
        if (getListAdapter() != null) {
            Object item = getListAdapter().getItem(position);
            if (item instanceof Header) onHeaderClick((Header) item, position);
        }
    }
    /** A header clicked: its fragment (in place of the headers, on a phone) or its intent. */
    public void onHeaderClick(Header header, int position) {
        if (header.fragment != null) switchToHeader(header);
        else if (header.intent != null) startActivity(header.intent);
    }
    public Intent onBuildStartFragmentIntent(String fragmentName, Bundle args, int titleRes, int shortTitleRes) {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setClass(this, getClass());
        intent.putExtra(EXTRA_SHOW_FRAGMENT, fragmentName);
        intent.putExtra(EXTRA_SHOW_FRAGMENT_ARGUMENTS, args);
        intent.putExtra(EXTRA_SHOW_FRAGMENT_TITLE, titleRes);
        intent.putExtra(EXTRA_SHOW_FRAGMENT_SHORT_TITLE, shortTitleRes);
        intent.putExtra(EXTRA_NO_HEADERS, true);
        return intent;
    }
    public void startWithFragment(String fragmentName, Bundle args, Fragment resultTo, int resultRequestCode) { startWithFragment(fragmentName, args, resultTo, resultRequestCode, 0, 0); }
    public void startWithFragment(String fragmentName, Bundle args, Fragment resultTo, int resultRequestCode, int titleRes, int shortTitleRes) {
        Intent intent = onBuildStartFragmentIntent(fragmentName, args, titleRes, shortTitleRes);
        if (resultTo == null) startActivity(intent);
        else resultTo.startActivityForResult(intent, resultRequestCode);
    }
    public void showBreadCrumbs(CharSequence title, CharSequence shortTitle) { if (title != null) setTitle(title); }
    public void setParentTitle(CharSequence title, CharSequence shortTitle, View.OnClickListener listener) {}
    void setSelectedHeader(Header header) {
        mCurHeader = header;
        int index = mHeaders.indexOf(header);
        if (index >= 0 && !mSinglePane) getListView().setItemChecked(index, true);
        showBreadCrumbs(header);
    }
    void showBreadCrumbs(Header header) {
        if (header != null) { CharSequence title = header.getBreadCrumbTitle(getResources()); if (title == null) title = header.getTitle(getResources()); if (title == null) title = getTitle(); showBreadCrumbs(title, header.getBreadCrumbShortTitle(getResources())); }
        else showBreadCrumbs(getTitle(), null);
    }
    private void switchToHeaderInner(String fragmentName, Bundle args) {
        if (!isValidFragment(fragmentName)) throw new IllegalArgumentException("Invalid fragment for this activity: " + fragmentName);
        Fragment f;
        try {
            f = (Fragment) Class.forName(fragmentName, true, getClassLoader()).newInstance();
            if (args != null) f.setArguments(args);
        } catch (Exception e) { throw new Fragment.InstantiationException("Unable to instantiate fragment " + fragmentName, e); }
        popPrefsBackStack();
        FragmentTransaction transaction = getFragmentManager().beginTransaction();
        transaction.setTransition(mSinglePane ? FragmentTransaction.TRANSIT_NONE : FragmentTransaction.TRANSIT_FRAGMENT_FADE);
        transaction.replace(mPrefsContainerId, f);
        transaction.commitAllowingStateLoss();
        if (mSinglePane && mPrefsContainer != null && mPrefsContainer.getVisibility() == View.GONE) {
            mPrefsContainer.setVisibility(View.VISIBLE);
            if (mHeadersContainer != null) mHeadersContainer.setVisibility(View.GONE);
        }
    }
    /** Back to the first preference panel pushed (startPreferenceFragment), if any (Husk's pop by an absent name would pop all). */
    private void popPrefsBackStack() {
        android.app.FragmentManager fm = getFragmentManager();
        for (int i = 0; i < fm.getBackStackEntryCount(); i++) if (BACK_STACK_PREFS.equals(fm.getBackStackEntryAt(i).getName())) { fm.popBackStack(BACK_STACK_PREFS, android.app.FragmentManager.POP_BACK_STACK_INCLUSIVE); return; }
    }
    /** Show a header's fragment in place of the headers (back returns to them), as Android since L does on a phone. */
    public void switchToHeader(Header header) {
        if (mCurHeader == header) { popPrefsBackStack(); return; }
        if (header.fragment == null) throw new IllegalStateException("can't switch to header that has no fragment");
        switchToHeaderInner(header.fragment, header.fragmentArguments);
        setSelectedHeader(header);
    }
    @Override public void onBackPressed() {
        if (mCurHeader != null && mSinglePane && getFragmentManager().getBackStackEntryCount() == 0 && (getIntent() == null || getIntent().getStringExtra(EXTRA_SHOW_FRAGMENT) == null)) {
            mCurHeader = null;
            mPrefsContainer.setVisibility(View.GONE);
            if (mHeadersContainer != null) mHeadersContainer.setVisibility(View.VISIBLE);
            if (mActivityTitle != null) showBreadCrumbs(mActivityTitle, null);
            getListView().clearChoices();
        } else super.onBackPressed();
    }
    Header findBestMatchingHeader(Header cur, ArrayList<Header> from) {
        for (int j = 0; j < from.size(); j++) { Header oh = from.get(j); if (cur == oh || (cur.id != HEADER_ID_UNDEFINED && cur.id == oh.id)) return oh; }
        for (int j = 0; j < from.size(); j++) { Header oh = from.get(j); if (cur.fragment != null && cur.fragment.equals(oh.fragment)) return oh; }
        return null;
    }
    public void switchToHeader(String fragmentName, Bundle args) {
        Header selectedHeader = null;
        for (int i = 0; i < mHeaders.size(); i++) if (fragmentName.equals(mHeaders.get(i).fragment)) { selectedHeader = mHeaders.get(i); break; }
        if (selectedHeader != null) mCurHeader = selectedHeader;
        switchToHeaderInner(fragmentName, args);
    }
    public void startPreferenceFragment(Fragment fragment, boolean push) {
        FragmentTransaction transaction = getFragmentManager().beginTransaction();
        transaction.replace(mPrefsContainerId, fragment);
        if (push) { transaction.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN); transaction.addToBackStack(BACK_STACK_PREFS); }
        else transaction.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE);
        transaction.commitAllowingStateLoss();
        if (mPrefsContainer != null && mPrefsContainer.getVisibility() == View.GONE) { mPrefsContainer.setVisibility(View.VISIBLE); if (mHeadersContainer != null) mHeadersContainer.setVisibility(View.GONE); }
    }
    public void startPreferencePanel(String fragmentClass, Bundle args, int titleRes, CharSequence titleText, Fragment resultTo, int resultRequestCode) {
        // a phone: a new instance of this activity shows the panel
        startWithFragment(fragmentClass, args, resultTo, resultRequestCode, titleRes, 0);
    }
    public void finishPreferencePanel(Fragment caller, int resultCode, Intent resultData) {
        setResult(resultCode, resultData);
        finish();
    }
    public boolean onPreferenceStartFragment(PreferenceFragment caller, Preference pref) {
        startPreferencePanel(pref.getFragment(), pref.getExtras(), pref.getTitleRes(), pref.getTitle(), null, 0);
        return true;
    }

    private void postBindPreferences() { if (mBindPosted) return; mBindPosted = true; mHandler.post(mBindPreferences); }
    private void bindPreferences() {
        final PreferenceScreen preferenceScreen = getPreferenceScreen();
        if (preferenceScreen != null) {
            preferenceScreen.bind(getListView());
            if (mSavedInstanceState != null) { super.onRestoreInstanceState(mSavedInstanceState); mSavedInstanceState = null; }
        }
    }
    public PreferenceManager getPreferenceManager() { return mPreferenceManager; }
    private void requirePreferenceManager() {
        if (mPreferenceManager == null) {
            if (mHeadersContainer == null) throw new RuntimeException("This should be called after super.onCreate.");
            throw new RuntimeException("Modern two-pane PreferenceActivity requires use of a PreferenceFragment");
        }
    }
    /** Show this hierarchy in the list (bound on the next turn, so it can still be changed), titled with its title. */
    public void setPreferenceScreen(PreferenceScreen preferenceScreen) {
        requirePreferenceManager();
        if (mPreferenceManager.setPreferences(preferenceScreen) && preferenceScreen != null) {
            postBindPreferences();
            CharSequence title = getPreferenceScreen().getTitle();
            if (title != null) setTitle(title);
        }
    }
    public PreferenceScreen getPreferenceScreen() { return mPreferenceManager != null ? mPreferenceManager.getPreferenceScreen() : null; }
    public void addPreferencesFromIntent(Intent intent) {
        requirePreferenceManager();
        setPreferenceScreen(mPreferenceManager.inflateFromIntent(intent, getPreferenceScreen()));
    }
    /** Inflate preference XML into the current screen (a new one if there is none) and show it. */
    public void addPreferencesFromResource(int preferencesResId) {
        requirePreferenceManager();
        setPreferenceScreen(mPreferenceManager.inflateFromResource(this, preferencesResId, getPreferenceScreen()));
    }
    public boolean onPreferenceTreeClick(PreferenceScreen preferenceScreen, Preference preference) { return false; }
    public Preference findPreference(CharSequence key) { return mPreferenceManager == null ? null : mPreferenceManager.findPreference(key); }
    protected boolean hasNextButton() { return mNextButton != null; }
    protected Button getNextButton() { return mNextButton; }

    /** The list of headers: icon, title, summary rows. */
    private static class HeaderAdapter extends BaseAdapter {
        private final Context mContext;
        private final List<Header> mHeaders;
        HeaderAdapter(Context context, List<Header> objects) { mContext = context; mHeaders = objects; }
        public int getCount() { return mHeaders.size(); }
        public Header getItem(int position) { return mHeaders.get(position); }
        public long getItemId(int position) { return position; }
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView != null ? convertView : PreferenceViews.row(mContext);
            Header header = getItem(position);
            Resources res = mContext.getResources();
            ImageView icon = (ImageView) view.findViewById(android.R.id.icon);
            View iconFrame = view.findViewWithTag(PreferenceViews.TAG_ICON_FRAME);
            if (header.iconRes != 0) { icon.setImageResource(header.iconRes); icon.setVisibility(View.VISIBLE); if (iconFrame != null) iconFrame.setVisibility(View.VISIBLE); }
            else { icon.setImageDrawable(null); icon.setVisibility(View.GONE); if (iconFrame != null) iconFrame.setVisibility(View.GONE); }
            ((TextView) view.findViewById(android.R.id.title)).setText(header.getTitle(res));
            TextView summary = (TextView) view.findViewById(android.R.id.summary);
            CharSequence s = header.getSummary(res);
            if (!TextUtils.isEmpty(s)) { summary.setVisibility(View.VISIBLE); summary.setText(s); } else summary.setVisibility(View.GONE);
            view.findViewById(android.R.id.widget_frame).setVisibility(View.GONE);
            return view;
        }
    }

    /** One header: a title (and summary, icon) that opens a fragment or starts an intent. */
    public static final class Header implements Parcelable {
        public long id = HEADER_ID_UNDEFINED;
        public int titleRes;
        public CharSequence title;
        public int summaryRes;
        public CharSequence summary;
        public int breadCrumbTitleRes;
        public CharSequence breadCrumbTitle;
        public int breadCrumbShortTitleRes;
        public CharSequence breadCrumbShortTitle;
        public int iconRes;
        public String fragment;
        public Bundle fragmentArguments;
        public Intent intent;
        public Bundle extras;
        public Header() {}
        Header(Parcel in) { readFromParcel(in); }
        public CharSequence getTitle(Resources res) { if (titleRes != 0) { CharSequence t = res.getText(titleRes); if (t != null) return t; } return title; }
        public CharSequence getSummary(Resources res) { if (summaryRes != 0) { CharSequence t = res.getText(summaryRes); if (t != null) return t; } return summary; }
        public CharSequence getBreadCrumbTitle(Resources res) { if (breadCrumbTitleRes != 0) { CharSequence t = res.getText(breadCrumbTitleRes); if (t != null) return t; } return breadCrumbTitle; }
        public CharSequence getBreadCrumbShortTitle(Resources res) { if (breadCrumbShortTitleRes != 0) { CharSequence t = res.getText(breadCrumbShortTitleRes); if (t != null) return t; } return breadCrumbShortTitle; }
        public int describeContents() { return 0; }
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeLong(id);
            dest.writeInt(titleRes); dest.writeCharSequence(title);
            dest.writeInt(summaryRes); dest.writeCharSequence(summary);
            dest.writeInt(breadCrumbTitleRes); dest.writeCharSequence(breadCrumbTitle);
            dest.writeInt(breadCrumbShortTitleRes); dest.writeCharSequence(breadCrumbShortTitle);
            dest.writeInt(iconRes);
            dest.writeString(fragment);
            dest.writeBundle(fragmentArguments);
            dest.writeParcelable(intent, flags);
            dest.writeBundle(extras);
        }
        public void readFromParcel(Parcel in) {
            id = in.readLong();
            titleRes = in.readInt(); title = in.readCharSequence();
            summaryRes = in.readInt(); summary = in.readCharSequence();
            breadCrumbTitleRes = in.readInt(); breadCrumbTitle = in.readCharSequence();
            breadCrumbShortTitleRes = in.readInt(); breadCrumbShortTitle = in.readCharSequence();
            iconRes = in.readInt();
            fragment = in.readString();
            fragmentArguments = in.readBundle();
            intent = in.readParcelable(Intent.class.getClassLoader());
            extras = in.readBundle();
        }
        public static final Creator<Header> CREATOR = new Creator<Header>() {
            public Header createFromParcel(Parcel source) { return new Header(source); }
            public Header[] newArray(int size) { return new Header[size]; }
        };
    }
}
