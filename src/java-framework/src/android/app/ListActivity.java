package android.app;

import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ListAdapter;
import android.widget.ListView;

/**
 * An activity showing a ListView, as AOSP's: the content's android:id/list (and android:id/empty), or a plain full-window list
 * when the activity sets no content; its item clicks go to onListItemClick.
 */
public class ListActivity extends Activity {
    protected ListAdapter mAdapter;
    protected ListView mList;
    private boolean mFinishedStart = false;
    private final AdapterView.OnItemClickListener mOnClickListener = new AdapterView.OnItemClickListener() {
        public void onItemClick(AdapterView<?> parent, View v, int position, long id) { onListItemClick((ListView) parent, v, position, id); }
    };

    public ListActivity() { super(); }
    protected void onListItemClick(ListView l, View v, int position, long id) {}
    @Override protected void onRestoreInstanceState(Bundle state) { ensureList(); super.onRestoreInstanceState(state); }
    /** The content changed (setContentView): pick up its list and empty view. */
    @Override public void onContentChanged() {
        super.onContentChanged();
        View emptyView = findViewById(android.R.id.empty);
        View list = findViewById(android.R.id.list);
        /* Android throws for content without a list; here the activity keeps none until it needs one (ensureList) */
        if (!(list instanceof ListView)) { android.util.Log.w("ListActivity", "content has no ListView with id android.R.id.list"); mList = null; return; }
        mList = (ListView) list;
        if (emptyView != null) mList.setEmptyView(emptyView);
        mList.setOnItemClickListener(mOnClickListener);
        if (mFinishedStart) setListAdapter(mAdapter);
        mFinishedStart = true;
    }
    public void setListAdapter(ListAdapter adapter) {
        synchronized (this) { ensureList(); mAdapter = adapter; mList.setAdapter(adapter); }
    }
    public void setSelection(int position) { ensureList(); mList.setSelection(position); }
    public int getSelectedItemPosition() { ensureList(); return mList.getSelectedItemPosition(); }
    public long getSelectedItemId() { ensureList(); return mList.getSelectedItemId(); }
    public ListView getListView() { ensureList(); return mList; }
    public ListAdapter getListAdapter() { return mAdapter; }
    /** No list yet: the content becomes one (Android's list_content_simple). */
    private void ensureList() {
        if (mList != null) return;
        ListView lv = new ListView(this);
        lv.setId(android.R.id.list);
        setContentView(lv);
        if (mList == null) { mList = lv; lv.setOnItemClickListener(mOnClickListener); }
    }
}
