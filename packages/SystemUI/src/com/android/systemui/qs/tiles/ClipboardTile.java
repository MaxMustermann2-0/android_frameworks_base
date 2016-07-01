package com.android.systemui.qs.tiles;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.Toast;

import com.android.systemui.R;
import com.android.systemui.qs.QSDetailItemsList;
import com.android.systemui.qs.QSTile;
import com.android.systemui.statusbar.policy.KeyguardMonitor;

import cyanogenmod.app.StatusBarPanelCustomTile;

import org.cyanogenmod.internal.logging.CMMetricsLogger;

import java.util.ArrayList;

public class ClipboardTile extends QSTile<QSTile.BooleanState> implements ClipboardManager.OnPrimaryClipChangedListener {

    private KeyguardMonitor mKeyguardMonitor;
    private ArrayList<String> mClipboardHistory;
    private ClipboardManager mClipboardManager;
    private KeyguardMonitor.Callback mCallback = new KeyguardMonitor.Callback() {
        @Override
        public void onKeyguardChanged() {
            refreshState();
        }
    };

    public ClipboardTile(Host host) {
        super(host);
        mKeyguardMonitor = host.getKeyguardMonitor();
        mClipboardManager = (ClipboardManager) mContext.getSystemService(Context.CLIPBOARD_SERVICE);
        mClipboardHistory = new ArrayList<>();
        mClipboardManager.addPrimaryClipChangedListener(this);
    }

    @Override
    protected BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    protected void handleClick() {
        showDetail(true);
    }

    @Override
    protected void handleLongClick() {
        showDetail(true);
    }

    @Override
    protected void handleSecondaryClick() {
        showDetail(true);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        state.visible = !mKeyguardMonitor.isShowing() || !mKeyguardMonitor.isSecure();
        state.value = true;
        state.label = mContext.getString(R.string.quick_settings_clipboard_label);
        state.icon = ResourceIcon.get(R.drawable.ic_qs_clipboard);
    }

    @Override
    public int getMetricsCategory() {
        return CMMetricsLogger.ANONYMOUS_STATS;
    }

    @Override
    public void setListening(boolean listening) {
        if (listening) {
            mKeyguardMonitor.addCallback(mCallback);
        } else {
            mKeyguardMonitor.removeCallback(mCallback);
        }
    }

    @Override
    public DetailAdapter getDetailAdapter() {
        return new ClipboardDetailAdapter();
    }

    @Override
    public void onPrimaryClipChanged() {
        ClipData.Item clipDataItem = mClipboardManager.getPrimaryClip().getItemAt(0);
        String text = clipDataItem.coerceToText(mContext).toString();
        mClipboardHistory.remove(text);
        mClipboardHistory.add(0, text);
    }

    private final class ClipboardDetailAdapter implements DetailAdapter, AdapterView.OnItemClickListener {

        QSDetailItemsList mItems;

        @Override
        public int getTitle() {
            return R.string.quick_settings_clipboard_label;
        }

        @Override
        public Boolean getToggleState() {
            return null;
        }

        @Override
        public View createDetailView(Context context, View convertView, ViewGroup parent) {
            mItems = QSDetailItemsList.convertOrInflate(context, convertView, parent);
            ListView listView = mItems.getListView();
            listView.setOnItemClickListener(this);
            listView.setChoiceMode(ListView.CHOICE_MODE_NONE);
            listView.setDivider(null);
            ClipboardAdapter adapter = new ClipboardAdapter(context,
                    R.layout.clipboard_detail_item, android.R.id.text1,
                    mClipboardHistory.toArray(new String[mClipboardHistory.size()]));
            listView.setAdapter(mClipboardHistory.isEmpty() ? null : adapter);
            mItems.setEmptyState(R.drawable.ic_qs_clipboard, R.string.clipboard_empty_label);
            return mItems;
        }

        @Override
        public Intent getSettingsIntent() {
            return null;
        }

        @Override
        public StatusBarPanelCustomTile getCustomTile() {
            return null;
        }

        @Override
        public void setToggleState(boolean state) {

        }

        @Override
        public int getMetricsCategory() {
            return CMMetricsLogger.ANONYMOUS_STATS;
        }

        @Override
        public void onItemClick(AdapterView<?> adapterView, View view, int i, long l) {
            mClipboardManager.setPrimaryClip(ClipData.newPlainText("", mClipboardHistory.get(i)));
            showDetail(false);
            Toast.makeText(mContext, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show();
        }
    }

    private class ClipboardAdapter extends ArrayAdapter<String> {

        public ClipboardAdapter(Context context, int resource, String[] objects) {
            super(context, resource, objects);
        }

        public ClipboardAdapter(Context context, int resource,
                               int textViewResourceId, String[] objects) {
            super(context, resource, textViewResourceId, objects);
        }

        @Override
        public View getView(final int position, View view, ViewGroup parent) {
            view = super.getView(position, view, parent);

            view.setMinimumHeight(mContext.getResources().getDimensionPixelSize(
                    R.dimen.qs_detail_item_height));
            ImageView closeBtn = (ImageView) view.findViewById(R.id.close_btn);
            closeBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    mClipboardHistory.remove(position);
                    showDetail(false);
                }
            });
            return view;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }
    }
}