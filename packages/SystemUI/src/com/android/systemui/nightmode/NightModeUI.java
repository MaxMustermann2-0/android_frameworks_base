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
import android.content.res.ThemeChangeRequest;
import android.content.res.ThemeManager;
import android.media.AudioManager;
import android.os.Handler;
import android.provider.Settings;

/**
 * Created by mm20 on 18.10.15.
 */
public class NightModeUI extends SystemUI {
    protected Handler mHandler = new Handler();
    AudioManager mAudioManager;
    ThemeManager mThemeManager;
    private NotificationManager mNotificationManager;
    private Intent mSettingsIntent;
    private ContentResolver mContentResolver;

    @Override
    public void start() {
        mSettingsIntent = new Intent("android.settings.NIGHTMODE_SETTINGS");
        mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        mThemeManager = (ThemeManager) mContext.getSystemService(Context.THEME_SERVICE);
        mContentResolver = mContext.getContentResolver();
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

    private void enableNightMode() {
        boolean lowBrightness = Settings.System.getInt(mContentResolver, "nightmode_low_brightness",
                0) == 1;
        boolean disableBatteryLight = Settings.System.getInt(mContentResolver,
                "nightmode_disable_battery_light", 0) == 1;
        boolean disableNotificationLight = Settings.System.getInt(mContentResolver,
                "nightmode_disable_notification_light", 0) == 1;
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
            setNightModePreference("battery_light_enabled", 0, 1);
        }
        if (disableNotificationLight) {
            setNightModePreference("notification_light_pulse", 0, 1);
        }
        if (liveDisplayNightMode) {
            int mode = Integer.parseInt(mContext.getResources().getStringArray(
                    com.android.internal.R.array.live_display_values)[3]);
            setNightModePreference(Settings.System.DISPLAY_TEMPERATURE_MODE, mode, 0);
        }
        if (useNightTheme) {
            applyNightTheme();
        }
        if (interruptions != -1) {
            int currentValue = Settings.Global.getInt(mContentResolver, "zen_mode", 0);
            Settings.System.putInt(mContentResolver, "daymode_zen_mode", currentValue);
            Settings.Global.putInt(mContentResolver, "zen_mode", interruptions);
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
            setDayModePreference("battery_light_enabled", 1);
        }
        if (disableNotificationLight) {
            setDayModePreference("notification_light_pulse", 1);
        }
        if (liveDisplayNightMode) {
            setDayModePreference(Settings.System.DISPLAY_TEMPERATURE_MODE, 0);
        }
        if (useNightTheme) {
            applyDayTheme();
        }
        if (interruptions != -1) {
            int newValue = Settings.System.getInt(mContentResolver, "daymode_zen_mode", 0);
            Settings.Global.putInt(mContentResolver, "zen_mode", newValue);
        }
        if (muteMedia) {
            int newVolume = Settings.System.getInt(mContentResolver, "daymode_media_volume", 0);
            mAudioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0);
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
            boolean nightMode = Settings.System.getInt(mContentResolver, "nightmode_enable_nightmode", 0) == 1;
            if (nightMode) {
                showNightModeNotification();
                enableNightMode();
            } else {
                mNotificationManager.cancel("nightmode_enabled", 101);
                disableNightMode();
            }
        }
    }
}
