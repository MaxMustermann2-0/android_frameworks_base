package com.android.systemui.qs.tiles;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.R;
import com.android.systemui.qs.QSTile;

import android.database.ContentObserver;
import android.net.Uri;
import android.provider.Settings;

/**
 * Created by android on 18.10.15.
 */
public class NightModeTile extends QSTile<QSTile.BooleanState> {
    private ContentObserver mObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            refreshState();
        }
    };

    public NightModeTile(Host host) {
        super(host);
    }

    @Override
    protected BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    protected void handleClick() {
        boolean nightmodeEnabled = Settings.System.getInt(mContext.getContentResolver(),
                "nightmode_enable_nightmode", 0) == 1;
        if (!nightmodeEnabled) {
            Settings.System.putInt(mContext.getContentResolver(), "nightmode_enable_nightmode", 1);
        } else {
            Settings.System.putInt(mContext.getContentResolver(), "nightmode_enable_nightmode", 0);
        }
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
}
