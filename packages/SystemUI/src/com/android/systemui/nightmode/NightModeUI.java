package com.android.systemui.nightmode;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.media.AudioManager;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;

import com.android.systemui.R;
import com.android.systemui.SystemUI;
import com.android.systemui.cm.UserContentObserver;
import com.android.systemui.statusbar.policy.ZenModeController;
import com.android.systemui.volume.VolumeComponent;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

import cyanogenmod.hardware.LiveDisplayManager;
import cyanogenmod.providers.CMSettings;
import cyanogenmod.themes.ThemeChangeRequest;
import cyanogenmod.themes.ThemeManager;

/**
 * Created by mm20 on 18.10.15.
 */
public class NightModeUI extends SystemUI {
    private static final String TAG = "NightModeUI";
    protected Handler mHandler = new Handler();
    AudioManager mAudioManager;
    ThemeManager mThemeManager;
    private NotificationManager mNotificationManager;
    private Intent mSettingsIntent;
    private ContentResolver mContentResolver;
    private ZenModeController mZenController;
    private DismissNightModeReceiver mDismissReceiver;

    @Override
    public void start() {
        mSettingsIntent = new Intent("android.settings.NIGHTMODE_SETTINGS");
        mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        mThemeManager = ThemeManager.getInstance(mContext);
        mZenController = getComponent(VolumeComponent.class).getZenController();
        mContentResolver = mContext.getContentResolver();
        mNotificationManager = (NotificationManager) mContext.getSystemService(
                Context.NOTIFICATION_SERVICE);
        SettingsObserver observer = new SettingsObserver(mHandler);
        observer.observe();
        boolean nightMode = Settings.System.getInt(mContext.getContentResolver(),
                "nightmode_enable_nightmode", 0) == 1;
        if (nightMode) {
            showNightModeNotification();
        } else {
            mNotificationManager.cancel("nightmode_enabled", 101);
            try {
                mContext.unregisterReceiver(mDismissReceiver);
            } catch (IllegalArgumentException e) {
                //Ignore
            }
        }
    }

    private void showNightModeNotification() {
        Intent intent = new Intent("dismiss_nightmode");
        PendingIntent disableNightmode = PendingIntent.getBroadcast(mContext, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT);
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
                        com.android.internal.R.color.system_notification_accent_color))
                .addAction(new Notification.Action.Builder(R.drawable.ic_close,
                        mContext.getString(R.string.turn_off), disableNightmode).build());
        mNotificationManager.notify("nightmode_enabled", 101, builder.build());
        if (mDismissReceiver == null) mDismissReceiver = new DismissNightModeReceiver();
        mContext.registerReceiver(mDismissReceiver, new IntentFilter("dismiss_nightmode"));
    }

    private void enableNightMode() {
        showNightModeNotification();
        boolean lowBrightness = Settings.System.getInt(mContentResolver, "nightmode_low_brightness",
                0) == 1;
        boolean disableBatteryLight = Settings.System.getInt(mContentResolver,
                "nightmode_disable_battery_light", 0) == 1;
        boolean disableNotificationLight = Settings.System.getInt(mContentResolver,
                "nightmode_disable_notification_light", 0) == 1;
        boolean ambientDisplay = Settings.System.getInt(mContentResolver,
                "nightmode_ambient_display", 0) == 1;
        boolean liveDisplayNightMode = Settings.System.getInt(mContentResolver,
                "nightmode_live_display_night", 0) == 1;
        boolean useNightTheme = Settings.System.getInt(mContentResolver,
                "nightmode_use_night_theme", 0) == 1;
        int interruptions = Settings.System.getInt(mContentResolver, "nightmode_interruptions", -1);
        boolean muteMedia = Settings.System.getInt(mContentResolver,
                "nightmode_mute_media_sound", 0) == 1;

        if (lowBrightness) {
            setNightModePreference(Settings.System.SCREEN_BRIGHTNESS, 1, 100);
            setNightModePreference(Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
        }
        if (disableBatteryLight) {
            setCMNightModePreference("battery_light_enabled", 0, 1);
        }
        if (disableNotificationLight) {
            setNightModePreference("notification_light_pulse", 0, 1);
        }
        if (liveDisplayNightMode) {
            setCMNightModePreference(CMSettings.System.DISPLAY_TEMPERATURE_MODE,
                    LiveDisplayManager.MODE_NIGHT, 0);
        }
        if (useNightTheme) {
            applyNightTheme();
        }
        if (ambientDisplay) {
            int currentValue = Settings.Secure.getInt(mContentResolver,
                    Settings.Secure.DOZE_ENABLED, 0);
            Settings.System.putInt(mContentResolver, "daymode_doze_enabled",
                    currentValue);
            Settings.Secure.putInt(mContentResolver, Settings.Secure.DOZE_ENABLED, 0);
        }

        if (interruptions != -1) {
            int currentValue = mZenController.getZen();
            Settings.System.putInt(mContentResolver, "daymode_zen_mode", currentValue);
            mZenController.setZen(interruptions, null, TAG);
        }
        if (muteMedia) {
            int currentVolume = mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            Settings.System.putInt(mContentResolver, "daymode_media_volume", currentVolume);
            mAudioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
        }
    }

    private void disableNightMode() {
        boolean lowBrightness = Settings.System.getInt(mContentResolver,
                "nightmode_low_brightness", 0) == 1;
        boolean disableBatteryLight = Settings.System.getInt(mContentResolver,
                "nightmode_disable_battery_light", 0) == 1;
        boolean disableNotificationLight = Settings.System.getInt(mContentResolver,
                "nightmode_disable_notification_light", 0) == 1;
        boolean ambientDisplay = Settings.System.getInt(mContentResolver,
                "nightmode_ambient_display", 0) == 1;
        boolean liveDisplayNightMode = Settings.System.getInt(mContentResolver,
                "nightmode_live_display_night", 0) == 1;
        boolean useNightTheme = Settings.System.getInt(mContentResolver,
                "nightmode_use_night_theme", 0) == 1;
        int interruptions = Settings.System.getInt(mContentResolver, "nightmode_interruptions", -1);
        boolean muteMedia = Settings.System.getInt(mContentResolver,
                "nightmode_mute_media_sound", 0) == 1;
        if (lowBrightness) {
            setDayModePreference(Settings.System.SCREEN_BRIGHTNESS, 100);
            setDayModePreference(Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
        }
        if (disableBatteryLight) {
            setCMDayModePreference("battery_light_enabled", 1);
        }
        if (disableNotificationLight) {
            setDayModePreference("notification_light_pulse", 1);
        }
        if (liveDisplayNightMode) {
            setCMDayModePreference(CMSettings.System.DISPLAY_TEMPERATURE_MODE,
                    LiveDisplayManager.MODE_AUTO);
        }
        if (useNightTheme) {
            applyDayTheme();
        }
        if (ambientDisplay) {
            int newValue = Settings.System.getInt(mContentResolver,
                    "daymode_doze_enabled", 0);
            Settings.Secure.putInt(mContentResolver, Settings.Secure.DOZE_ENABLED, newValue);
        }
        if (interruptions != -1) {
            int newValue = Settings.System.getInt(mContentResolver, "daymode_zen_mode", 0);
            mZenController.setZen(newValue, null, TAG);
        }
        if (muteMedia) {
            int newVolume = Settings.System.getInt(mContentResolver, "daymode_media_volume", 0);
            mAudioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0);
        }
        mNotificationManager.cancel("nightmode_enabled", 101);
        try {
            mContext.unregisterReceiver(mDismissReceiver);
        } catch (IllegalArgumentException e) {
            //Ignore
        }
    }

    private void applyNightTheme() {
        String currentStyle = Settings.System.getString(mContentResolver, "theme_current_overlay");
        Settings.System.putString(mContentResolver, "daymode_theme_style", currentStyle);
        String currentStatus = Settings.System.getString(mContentResolver, "theme_current_status");
        Settings.System.putString(mContentResolver, "daymode_theme_status", currentStatus);
        String currentNav = Settings.System.getString(mContentResolver, "theme_current_nav");
        Settings.System.putString(mContentResolver, "daymode_theme_nav", currentNav);
        String currentIcons = Settings.System.getString(mContentResolver, "theme_current_icons");
        Settings.System.putString(mContentResolver, "daymode_theme_icons", currentIcons);
        String currentWall = Settings.System.getString(mContentResolver, "theme_current_wallpaper");
        Settings.System.putString(mContentResolver, "daymode_theme_wallpaper", currentWall);
        String currentLock = Settings.System.getString(mContentResolver, "theme_current_lockscreen");
        Settings.System.putString(mContentResolver, "daymode_theme_lockscreen", currentLock);

        String nightStyle = Settings.System.getString(mContentResolver, "nightmode_theme_style");
        String nightStatus = Settings.System.getString(mContentResolver, "nightmode_theme_status");
        String nightNav = Settings.System.getString(mContentResolver, "nightmode_theme_nav");
        String nightIcons = Settings.System.getString(mContentResolver, "nightmode_theme_icons");
        String nightWall = Settings.System.getString(mContentResolver, "nightmode_theme_wallpaper");
        String nightLock = Settings.System.getString(mContentResolver, "nightmode_theme_lockscreen");

        ThemeChangeRequest.Builder builder = new ThemeChangeRequest.Builder();
        if (nightStyle != null) builder.setOverlay(nightStyle);
        if (nightStatus != null) builder.setStatusBar(nightStatus);
        if (nightNav != null) builder.setNavBar(nightNav);
        if (nightIcons != null) builder.setIcons(nightIcons);
        if (nightWall != null) builder.setWallpaper(nightWall);
        if (nightLock != null) builder.setLockWallpaper(nightLock);
        mThemeManager.requestThemeChange(builder.build(), true);
    }

    private void applyDayTheme() {
        String nightStyle = Settings.System.getString(mContentResolver, "nightmode_theme_style");
        String nightStatus = Settings.System.getString(mContentResolver, "nightmode_theme_status");
        String nightNav = Settings.System.getString(mContentResolver, "nightmode_theme_nav");
        String nightIcons = Settings.System.getString(mContentResolver, "nightmode_theme_icons");
        String nightWall = Settings.System.getString(mContentResolver, "nightmode_theme_wallpaper");
        String nightLock = Settings.System.getString(mContentResolver, "nightmode_theme_lockscreen");

        String newStyle = Settings.System.getString(mContentResolver, "daymode_theme_style");
        String newStatus = Settings.System.getString(mContentResolver, "daymode_theme_status");
        String newNav = Settings.System.getString(mContentResolver, "daymode_theme_nav");
        String newIcons = Settings.System.getString(mContentResolver, "daymode_theme_icons");
        String newWall = Settings.System.getString(mContentResolver, "daymode_theme_wallpaper");
        String newLock = Settings.System.getString(mContentResolver, "daymode_theme_lockscreen");

        newStyle = newStyle == null ? "system" : newStyle;
        newStatus = newStatus == null ? "system" : newStatus;
        newNav = newNav == null ? "system" : newNav;
        newIcons = newIcons == null ? "system" : newIcons;
        newWall = newWall == null ? "system" : newWall;
        newLock = newLock == null ? "system" : newLock;

        ThemeChangeRequest.Builder builder = new ThemeChangeRequest.Builder();
        if (nightStyle != null) builder.setOverlay(newStyle);
        if (nightStatus != null) builder.setStatusBar(newStatus);
        if (nightNav != null) builder.setNavBar(newNav);
        if (nightIcons != null) builder.setIcons(newIcons);
        if (nightWall != null) builder.setWallpaper(newWall);
        if (nightLock != null) builder.setLockWallpaper(newLock);
        mThemeManager.requestThemeChange(builder.build(), false);
    }

    private void setNightModePreference(String key, int value, int defValue) {
        int currentValue = Settings.System.getInt(mContentResolver, key, defValue);
        Settings.System.putInt(mContentResolver, "daymode_" + key, currentValue);
        Settings.System.putInt(mContentResolver, key, value);
    }

    private void setDayModePreference(String key, int defValue) {
        int newValue = Settings.System.getInt(mContentResolver, "daymode_" + key, defValue);
        Settings.System.putInt(mContentResolver, key, newValue);
    }

    private void setCMNightModePreference(String key, int value, int defValue) {
        int currentValue = CMSettings.System.getInt(mContentResolver, key, defValue);
        Settings.System.putInt(mContentResolver, "daymode_" + key, currentValue);
        CMSettings.System.putInt(mContentResolver, key, value);
    }

    private void setCMDayModePreference(String key, int defValue) {
        int newValue = Settings.System.getInt(mContentResolver, "daymode_" + key, defValue);
        CMSettings.System.putInt(mContentResolver, key, newValue);
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
            boolean nightMode = Settings.System.getInt(mContentResolver,
                    "nightmode_enable_nightmode", 0) == 1;
            if (nightMode) {
                enableNightMode();
            } else {
                disableNightMode();
            }
        }
    }

    private class DismissNightModeReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals("dismiss_nightmode")) {
                Settings.System.putInt(mContext.getContentResolver(), "nightmode_enable_nightmode",
                        0);
            }
        }
    }
}
