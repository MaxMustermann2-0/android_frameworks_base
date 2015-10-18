package com.android.systemui.nightmode;

import com.android.systemui.R;
import com.android.systemui.SystemUI;
import com.android.systemui.cm.UserContentObserver;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.provider.Settings;

/**
 * Created by mm20 on 18.10.15.
 */
public class NightModeUI extends SystemUI {
    protected Handler mHandler = new Handler();
    private NotificationManager mNotificationManager;
    private Intent mSettingsIntent;

    @Override
    public void start() {
        mSettingsIntent = new Intent("android.settings.NIGHTMODE_SETTINGS");
        mNotificationManager = (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        SettingsObserver observer = new SettingsObserver(mHandler);
        observer.observe();
        boolean nightMode = Settings.System.getInt(mContext.getContentResolver(), "nightmode_enable_nightmode", 0) == 1;
        if (nightMode) showNightModeNotification();
        else mNotificationManager.cancel("nightmode_enabled", 101);
    }

    private void showNightModeNotification() {
        Notification.Builder builder = new Notification.Builder(mContext)
                .setSmallIcon(R.drawable.stat_sys_nightmode)
                .setShowWhen(false)
                .setOngoing(true)
                .setContentTitle(mContext.getString(R.string.nightmode_enabled))
                .setContentText(mContext.getString(R.string.nightmode_settings))
                .setPriority(Notification.PRIORITY_MAX)
                .setContentIntent(PendingIntent.getActivity(mContext, 0, mSettingsIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT))
                .setColor(mContext.getResources().getColor(
                        com.android.internal.R.color.system_notification_accent_color));
        mNotificationManager.notify("nightmode_enabled", 101, builder.build());
    }

    class SettingsObserver extends UserContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        @Override
        protected void observe() {
            super.observe();
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.System
                    .getUriFor("nightmode_enable_nightmode"), false, this);
        }

        @Override
        protected void update() {
            boolean nightMode = Settings.System.getInt(mContext.getContentResolver(), "nightmode_enable_nightmode", 0) == 1;
            if (nightMode) showNightModeNotification();
            else mNotificationManager.cancel("nightmode_enabled", 101);
        }
    }
}
