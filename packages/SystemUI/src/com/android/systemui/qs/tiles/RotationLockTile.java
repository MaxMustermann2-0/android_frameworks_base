/*
 * Copyright (C) 2014 The Android Open Source Project
 * Copyright (C) 2015 The CyanogenMod Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.qs.tiles;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
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
import com.android.systemui.statusbar.policy.RotationLockController;
import com.android.systemui.statusbar.policy.RotationLockController.RotationLockControllerCallback;

import cyanogenmod.app.StatusBarPanelCustomTile;

/**
 * Quick settings tile: Rotation
 **/
public class RotationLockTile extends QSTile<QSTile.BooleanState> {


    public static final int ROTATION_0_MODE = 1;
    public static final int ROTATION_90_MODE = 2;
    public static final int ROTATION_180_MODE = 4;
    public static final int ROTATION_270_MODE = 8;
    private static final Intent DISPLAY_ROTATION_SETTINGS =
            new Intent("android.settings.DISPLAY_ROTATION_SETTINGS");

    private final AnimationIcon mPortraitToAuto
            = new AnimationIcon(R.drawable.ic_portrait_to_auto_rotate_animation);
    private final AnimationIcon mAutoToPortrait
            = new AnimationIcon(R.drawable.ic_portrait_from_auto_rotate_animation);

    private final AnimationIcon mLandscapeToAuto
            = new AnimationIcon(R.drawable.ic_landscape_to_auto_rotate_animation);
    private final AnimationIcon mAutoToLandscape
            = new AnimationIcon(R.drawable.ic_landscape_from_auto_rotate_animation);

    private final RotationLockController mController;
    private String[] mEntries;

    public RotationLockTile(Host host) {
        super(host);
        mController = host.getRotationLockController();
        mEntries = mContext.getResources().getStringArray(R.array.rotation_detail_list_entries);
    }

    @Override
    protected BooleanState newTileState() {
        return new BooleanState();
    }

    public void setListening(boolean listening) {
        if (mController == null) return;
        if (listening) {
            mController.addRotationLockControllerCallback(mCallback);
        } else {
            mController.removeRotationLockControllerCallback(mCallback);
        }
    }

    @Override
    protected void handleClick() {
        if (mController == null) return;
        MetricsLogger.action(mContext, getMetricsCategory(), !mState.value);
        final boolean newState = !mState.value;
        mController.setRotationLocked(newState);
        refreshState(newState ? UserBoolean.USER_TRUE : UserBoolean.USER_FALSE);
    }

    @Override
    protected void handleLongClick() {
        showDetail(true);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        if (mController == null) return;
        final boolean rotationLocked = arg != null ? ((UserBoolean) arg).value
                : mController.isRotationLocked();
        final boolean userInitiated = arg != null ? ((UserBoolean) arg).userInitiated : false;
        state.visible = mController.isRotationLockAffordanceVisible();
        if (state.value == rotationLocked && state.contentDescription != null) {
            // No change and initialized, no need to update all the values.
            return;
        }
        state.value = rotationLocked;
        final boolean portrait = isCurrentOrientationLockPortrait();
        final AnimationIcon icon;
        if (rotationLocked) {
            final int label = portrait ? R.string.quick_settings_rotation_locked_portrait_label
                    : R.string.quick_settings_rotation_locked_landscape_label;
            state.label = mContext.getString(label);
            icon = portrait ? mAutoToPortrait : mAutoToLandscape;
        } else {
            state.label = mContext.getString(R.string.quick_settings_rotation_unlocked_label);
            icon = portrait ? mPortraitToAuto : mLandscapeToAuto;
        }
        icon.setAllowAnimation(userInitiated);
        state.icon = icon;
        state.contentDescription = getAccessibilityString(rotationLocked,
                R.string.accessibility_rotation_lock_on_portrait,
                R.string.accessibility_rotation_lock_on_landscape,
                R.string.accessibility_rotation_lock_off);
    }

    private boolean isCurrentOrientationLockPortrait() {
        int lockOrientation = mController.getRotationLockOrientation();
        if (lockOrientation == Configuration.ORIENTATION_UNDEFINED) {
            // Freely rotating device; use current rotation
            return mContext.getResources().getConfiguration().orientation
                    != Configuration.ORIENTATION_LANDSCAPE;
        } else {
            return lockOrientation != Configuration.ORIENTATION_LANDSCAPE;
        }
    }

    @Override
    public int getMetricsCategory() {
        return MetricsLogger.QS_ROTATIONLOCK;
    }

    /**
     * Get the correct accessibility string based on the state
     *
     * @param locked          Whether or not rotation is locked.
     * @param idWhenPortrait  The id which should be used when locked in portrait.
     * @param idWhenLandscape The id which should be used when locked in landscape.
     * @param idWhenOff       The id which should be used when the rotation lock is off.
     * @return
     */
    private String getAccessibilityString(boolean locked, int idWhenPortrait, int idWhenLandscape,
                                          int idWhenOff) {
        int stringID;
        if (locked) {
            stringID = isCurrentOrientationLockPortrait() ? idWhenPortrait : idWhenLandscape;
        } else {
            stringID = idWhenOff;
        }
        return mContext.getString(stringID);
    }

    @Override
    protected String composeChangeAnnouncement() {
        return getAccessibilityString(mState.value,
                R.string.accessibility_rotation_lock_on_portrait_changed,
                R.string.accessibility_rotation_lock_on_landscape_changed,
                R.string.accessibility_rotation_lock_off_changed);
    }

    private final RotationLockControllerCallback mCallback = new RotationLockControllerCallback() {
        @Override
        public void onRotationLockStateChanged(boolean rotationLocked, boolean affordanceVisible) {
            refreshState(rotationLocked ? UserBoolean.BACKGROUND_TRUE
                    : UserBoolean.BACKGROUND_FALSE);
        }
    };

    @Override
    public DetailAdapter getDetailAdapter() {
        return new RotationLockDetailAdapter();
    }

    private final class RotationLockDetailAdapter implements DetailAdapter, AdapterView.OnItemClickListener {
        private QSDetailItemsList mItems;
        private CheckBoxAdapter mAdapter;

        @Override
        public int getTitle() {
            return R.string.quick_settings_display_rotation_label;
        }

        @Override
        public Boolean getToggleState() {
            return !mController.isRotationLocked();
        }

        @Override
        public void setToggleState(boolean state) {
            mController.setRotationLocked(!state);
            fireToggleStateChanged(state);
            mItems.getListView().setAdapter(state ? mAdapter : null);
            if (state) updateCheckedItems();
        }

        @Override
        public int getMetricsCategory() {
            return MetricsLogger.DONT_TRACK_ME_BRO;
        }

        private void updateCheckedItems() {
            int mode = Settings.System.getInt(mContext.getContentResolver(),
                    Settings.System.ACCELEROMETER_ROTATION_ANGLES,
                    ROTATION_0_MODE | ROTATION_90_MODE | ROTATION_270_MODE);
            mItems.getListView().setItemChecked(0, (mode & ROTATION_0_MODE) == ROTATION_0_MODE);
            mItems.getListView().setItemChecked(1, (mode & ROTATION_90_MODE) == ROTATION_90_MODE);
            mItems.getListView().setItemChecked(2, (mode & ROTATION_180_MODE) == ROTATION_180_MODE);
            mItems.getListView().setItemChecked(3, (mode & ROTATION_270_MODE) == ROTATION_270_MODE);
        }

        @Override
        public View createDetailView(Context context, View convertView, ViewGroup parent) {
            mItems = QSDetailItemsList.convertOrInflate(context, convertView, parent);
            ListView listView = mItems.getListView();
            listView.setOnItemClickListener(this);
            listView.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
            listView.setDivider(null);
            mAdapter = new CheckBoxAdapter(context,
                    android.R.layout.simple_list_item_multiple_choice, mEntries);
            listView.setAdapter(mController.isRotationLocked() ? null : mAdapter);
            if (!mController.isRotationLocked())
                updateCheckedItems();
            mItems.setEmptyState(R.drawable.ic_qs_rotation_lock, R.string.quick_settings_rotation_locked_label);
            return mItems;
        }

        @Override
        public Intent getSettingsIntent() {
            return DISPLAY_ROTATION_SETTINGS;
        }

        @Override
        public StatusBarPanelCustomTile getCustomTile() {
            return null;
        }

        @Override
        public void onItemClick(AdapterView<?> adapterView, View view, int i, long l) {
            if (!mItems.getListView().isItemChecked(0) && !mItems.getListView().isItemChecked(1) &&
                    !mItems.getListView().isItemChecked(2) && !mItems.getListView().isItemChecked(3)) {
                mItems.getListView().setItemChecked(0, true);
            }
            int mode = 0;
            mode |= mItems.getListView().isItemChecked(0) ? ROTATION_0_MODE : 0;
            mode |= mItems.getListView().isItemChecked(1) ? ROTATION_90_MODE : 0;
            mode |= mItems.getListView().isItemChecked(2) ? ROTATION_180_MODE : 0;
            mode |= mItems.getListView().isItemChecked(3) ? ROTATION_270_MODE : 0;

            Settings.System.putInt(mContext.getContentResolver(),
                    Settings.System.ACCELEROMETER_ROTATION_ANGLES, mode);
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
