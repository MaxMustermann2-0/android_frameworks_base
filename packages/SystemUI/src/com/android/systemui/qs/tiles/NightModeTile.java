package com.android.systemui.qs.tiles;

import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.net.Uri;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.R;
import com.android.systemui.qs.QSDetailItemsList;
import com.android.systemui.qs.QSTile;

import cyanogenmod.app.StatusBarPanelCustomTile;

/**
 * Created by android on 18.10.15.
 */
public class NightModeTile extends QSTile<QSTile.BooleanState> {
    private static final Intent NIGHT_MODE_SETTINGS = new Intent("android.settings.NIGHTMODE_SETTINGS");
    private String[] mEntries;
    private ContentObserver mObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            refreshState();
        }
    };

    public NightModeTile(Host host) {
        super(host);
        mEntries = mContext.getResources().getStringArray(R.array.qs_nightmode_detail_list);
    }

    @Override
    protected BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    protected void handleClick() {
        mHost.collapsePanels();
        boolean nightmodeEnabled = Settings.System.getInt(mContext.getContentResolver(),
                "nightmode_enable_nightmode", 0) == 1;
        if (!nightmodeEnabled) {
            Settings.System.putInt(mContext.getContentResolver(), "nightmode_enable_nightmode", 1);
        } else {
            Settings.System.putInt(mContext.getContentResolver(), "nightmode_enable_nightmode", 0);
        }
    }

    @Override
    protected void handleLongClick() {
        showDetail(true);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        state.visible = true;
        state.value = Settings.System.getInt(mContext.getContentResolver(),
                "nightmode_enable_nightmode", 0) == 1;
        if (state.value) {
            state.icon = ResourceIcon.get(R.drawable.ic_qs_nightmode_on);
        } else {
            state.icon = ResourceIcon.get(R.drawable.ic_qs_nightmode_off);
        }
        state.label = mContext.getString(R.string.qs_nightmode_label);
    }

    @Override
    public int getMetricsCategory() {
        return MetricsLogger.DONT_TRACK_ME_BRO;
    }

    @Override
    public void setListening(boolean listening) {
        if (listening) {
            mContext.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor("nightmode_enable_nightmode"),
                    false, mObserver);
        } else {
            mContext.getContentResolver().unregisterContentObserver(mObserver);
        }
    }

    @Override
    public DetailAdapter getDetailAdapter() {
        return new NightModeDetailAdapter();
    }

    private final class NightModeDetailAdapter implements DetailAdapter, AdapterView.OnItemClickListener {
        private QSDetailItemsList mItems;
        private CheckBoxAdapter mAdapter;

        @Override
        public int getTitle() {
            return R.string.qs_nightmode_label;
        }

        @Override
        public Boolean getToggleState() {
            return Settings.System.getInt(mContext.getContentResolver(),
                    "nightmode_enable_nightmode", 0) == 1;
        }

        @Override
        public void setToggleState(boolean state) {
            mHost.collapsePanels();
            Settings.System.putInt(mContext.getContentResolver(), "nightmode_enable_nightmode",
                    state ? 1 : 0);
            fireToggleStateChanged(state);
            mItems.getListView().setAdapter(state ? null : mAdapter);
            if (!state) updateCheckedItems();
        }

        @Override
        public int getMetricsCategory() {
            return MetricsLogger.DONT_TRACK_ME_BRO;
        }

        private void updateCheckedItems() {
            boolean lowBrightness = Settings.System.getInt(mContext.getContentResolver(),
                    "nightmode_low_brightness", 0) == 1;
            boolean disableBatteryLight = Settings.System.getInt(mContext.getContentResolver(),
                    "nightmode_disable_battery_light", 0) == 1;
            boolean ambientDisplay = Settings.System.getInt(mContext.getContentResolver(),
                    "nightmode_ambient_display", 0) == 1;
            boolean disableNotificationLight = Settings.System.getInt(mContext.getContentResolver(),
                    "nightmode_disable_notification_light", 0) == 1;
            boolean liveDisplayNightMode = Settings.System.getInt(mContext.getContentResolver(),
                    "nightmode_live_display_night", 0) == 1;
            boolean useNightTheme = Settings.System.getInt(mContext.getContentResolver(),
                    "nightmode_use_night_theme", 0) == 1;
            int interruptions = Settings.System.getInt(mContext.getContentResolver(),
                    "nightmode_interruptions", -1);
            boolean muteMedia = Settings.System.getInt(mContext.getContentResolver(),
                    "nightmode_mute_media_sound", 0) == 1;

            mItems.getListView().setItemChecked(0, useNightTheme);
            mItems.getListView().setItemChecked(1, lowBrightness);
            mItems.getListView().setItemChecked(2, liveDisplayNightMode);
            mItems.getListView().setItemChecked(3, ambientDisplay);
            mItems.getListView().setItemChecked(4, interruptions > 0);
            mItems.getListView().setItemChecked(5, muteMedia);
            mItems.getListView().setItemChecked(6, disableNotificationLight);
            mItems.getListView().setItemChecked(7, disableBatteryLight);
        }

        @Override
        public View createDetailView(Context context, View convertView, ViewGroup parent) {
            boolean state = Settings.System.getInt(mContext.getContentResolver(),
                    "nightmode_enable_nightmode", 0) == 1;
            mItems = QSDetailItemsList.convertOrInflate(context, convertView, parent);
            ListView listView = mItems.getListView();
            listView.setOnItemClickListener(this);
            listView.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
            listView.setDivider(null);
            mAdapter = new CheckBoxAdapter(context,
                    android.R.layout.simple_list_item_multiple_choice, mEntries);
            listView.setAdapter(state ? null : mAdapter);
            if (!state) updateCheckedItems();
            mItems.setEmptyState(R.drawable.ic_qs_nightmode_on, R.string.nightmode_enabled);
            return mItems;
        }

        @Override
        public Intent getSettingsIntent() {
            return NIGHT_MODE_SETTINGS;
        }

        @Override
        public StatusBarPanelCustomTile getCustomTile() {
            return null;
        }

        @Override
        public void onItemClick(AdapterView<?> adapterView, View view, int i, long l) {
            Settings.System.putInt(mContext.getContentResolver(),
                    "nightmode_use_night_theme", mItems.getListView().isItemChecked(0) ? 1 : 0);
            Settings.System.putInt(mContext.getContentResolver(),
                    "nightmode_low_brightness", mItems.getListView().isItemChecked(1) ? 1 : 0);
            Settings.System.putInt(mContext.getContentResolver(),
                    "nightmode_live_display_night", mItems.getListView().isItemChecked(2) ? 1 : 0);
            Settings.System.putInt(mContext.getContentResolver(),
                    "nightmode_ambient_display", mItems.getListView().isItemChecked(3) ? 1 : 0);
            Settings.System.putInt(mContext.getContentResolver(),
                    "nightmode_interruptions", mItems.getListView().isItemChecked(4) ? 2 : -1);
            Settings.System.putInt(mContext.getContentResolver(),
                    "nightmode_mute_media_sound", mItems.getListView().isItemChecked(5) ? 1 : 0);
            Settings.System.putInt(mContext.getContentResolver(),
                    "nightmode_disable_notification_light", mItems.getListView().isItemChecked(6) ?
                            1 : 0);
            Settings.System.putInt(mContext.getContentResolver(),
                    "nightmode_disable_battery_light", mItems.getListView().isItemChecked(7) ? 1
                            : 0);
        }

    }

    private class CheckBoxAdapter extends ArrayAdapter<String> {

        public CheckBoxAdapter(Context context, int resource, String[] objects) {
            super(context, resource, objects);
        }

        public CheckBoxAdapter(Context context, int resource,
                               int textViewResourceId, String[] objects) {
            super(context, resource, textViewResourceId, objects);
        }

        @Override
        public View getView(int position, View view, ViewGroup parent) {
            view = super.getView(position, view, parent);

            view.setMinimumHeight(mContext.getResources().getDimensionPixelSize(
                    R.dimen.qs_detail_item_height));

            return view;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }
    }
}
