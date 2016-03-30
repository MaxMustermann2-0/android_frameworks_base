/*
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
package com.android.systemui.qs;

import android.Manifest;
import android.annotation.Nullable;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.ThemeChangeRequest;
import android.content.res.ThemeManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.ResultReceiver;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.ScaleAnimation;
import android.view.animation.TranslateXAnimation;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.CompoundButton;
import android.widget.ScrollView;

import com.android.systemui.R;
import com.android.systemui.statusbar.phone.QSTileHost;
import com.android.systemui.statusbar.phone.SystemUIDialog;

public class QSSettings extends ScrollView implements View.OnClickListener {
    private static final String RESULT_RECEIVER_EXTRA = "result_receiver";
    private static final String LOCK_CLOCK_PACKAGENAME = "com.cyanogenmod.lockclock";
    private static final String LOCK_CLOCK_PERM_CLASS = LOCK_CLOCK_PACKAGENAME
            + ".weather.PermissionRequestActivity";
    private QSTileHost mHost;

    private View mBluegrey;
    private View mRed;
    private View mBlue;
    private View mTeal;
    private View mOrange;
    private View mCyan;
    private View mCurrent;

    private boolean mAdapterEditingState;
    private QSBooleanSettingRow mShowWeather;
    private ResultReceiver mResultReceiver;
    private boolean mColorSchemeOpen;

    public QSSettings(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setFillViewport(true);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        findViewById(R.id.reset_tiles).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                initiateTileReset();
            }
        });

        mShowWeather = (QSBooleanSettingRow) findViewById(R.id.show_weather);
        mShowWeather.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    PackageManager packageManager = getContext().getPackageManager();
                    if (packageManager.checkPermission(Manifest.permission.ACCESS_COARSE_LOCATION,
                            LOCK_CLOCK_PACKAGENAME) != PackageManager.PERMISSION_GRANTED) {
                        mShowWeather.setChecked(false);
                        requestPermission();
                        mHost.collapsePanels();
                    }
                }
            }
        });
    }

    public Parcelable getResultReceiverForSending() {
        if (mResultReceiver == null) {
            mResultReceiver = new ResultReceiver(new Handler()) {
                @Override
                protected void onReceiveResult(int resultCode, Bundle resultData) {
                    super.onReceiveResult(resultCode, resultData);
                    if (resultCode == Activity.RESULT_OK) {
                        mShowWeather.setChecked(true);
                    }
                    mResultReceiver = null;
                }
            };
        }
        Parcel parcel = Parcel.obtain();
        mResultReceiver.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        ResultReceiver receiverForSending = ResultReceiver.CREATOR.createFromParcel(parcel);
        parcel.recycle();
        return receiverForSending;
    }

    private void requestPermission() {
        Intent i = new Intent();
        i.setClassName(LOCK_CLOCK_PACKAGENAME, LOCK_CLOCK_PERM_CLASS);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        i.putExtra(RESULT_RECEIVER_EXTRA, getResultReceiverForSending());
        getContext().startActivity(i);
        findViewById(R.id.qs_color_scheme).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                openColorScheme();
            }
        });
        mBluegrey = findViewById(R.id.color_scheme_bluegrey);
        mRed = findViewById(R.id.color_scheme_red);
        mBlue = findViewById(R.id.color_scheme_blue);
        mTeal = findViewById(R.id.color_scheme_teal);
        mOrange = findViewById(R.id.color_scheme_orange);
        mCyan = findViewById(R.id.color_scheme_cyan);
        mCurrent = findViewById(R.id.color_scheme_current);

        mBluegrey.setOnClickListener(this);
        mRed.setOnClickListener(this);
        mBlue.setOnClickListener(this);
        mTeal.setOnClickListener(this);
        mOrange.setOnClickListener(this);
        mCyan.setOnClickListener(this);
        mCurrent.setOnClickListener(this);
    }

    private void initiateTileReset() {
        final AlertDialog d = new AlertDialog.Builder(mContext)
                .setMessage(R.string.qs_tiles_reset_confirmation)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(com.android.internal.R.string.reset,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                mHost.initiateReset();
                            }
                        }).create();
        SystemUIDialog.makeSystemUIDialog(d);
        d.show();
    }

    public void setHost(QSTileHost host) {
        mHost = host;
    }

    public boolean getAdapterEditingState() {
        return mAdapterEditingState;
    }

    public void setAdapterEditingState(boolean editing) {
        this.mAdapterEditingState = editing;
    }

    @Override
    public void onClick(View view) {
        if(view == mCurrent) {
            if(mColorSchemeOpen){
                closeColorScheme();
            }else{
                openColorScheme();
            }
            return;
        }
        ThemeChangeRequest.Builder builder = new ThemeChangeRequest.Builder();
        ThemeManager themeManager = (ThemeManager) getContext()
                .getSystemService(Context.THEME_SERVICE);
        String themePkg = "system";
        if(view == mRed) themePkg = "de.mm20.sysuired";
        if(view == mBlue) themePkg = "de.mm20.sysuiblue";
        if(view == mTeal) themePkg = "de.mm20.sysuiteal";
        if(view == mCyan) themePkg = "de.mm20.sysuicyan";
        if(view == mOrange) themePkg = "de.mm20.sysuiorange";
        builder.setStatusBar(themePkg);
        themeManager.requestThemeChange(builder.build(), false);
    }

    private void openColorScheme(){
        mColorSchemeOpen = true;
        mBluegrey.setVisibility(VISIBLE);
        mBlue.setVisibility(VISIBLE);
        mRed.setVisibility(VISIBLE);
        mTeal.setVisibility(VISIBLE);
        mOrange.setVisibility(VISIBLE);
        mCyan.setVisibility(VISIBLE);
        AlphaAnimation anim1 = new AlphaAnimation(0, 1);
        anim1.setDuration(200);
        AlphaAnimation anim2 = new AlphaAnimation(0, 1);
        anim2.setDuration(200);
        AlphaAnimation anim3 = new AlphaAnimation(0, 1);
        anim3.setDuration(200);
        AlphaAnimation anim4 = new AlphaAnimation(0, 1);
        anim4.setDuration(200);
        AlphaAnimation anim5 = new AlphaAnimation(0, 1);
        anim5.setDuration(200);
        AlphaAnimation anim6 = new AlphaAnimation(0, 1);
        anim6.setDuration(200);
        mBlue.startAnimation(anim1);
        mBluegrey.startAnimation(anim2);
        mTeal.startAnimation(anim3);
        mRed.startAnimation(anim4);
        mCyan.startAnimation(anim5);
        mOrange.startAnimation(anim6);
    }

    private void closeColorScheme(){
        mColorSchemeOpen = false;
        AlphaAnimation anim1 = new AlphaAnimation(1, 0);
        anim1.setDuration(200);
        anim1.setAnimationListener(new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {

            }

            @Override
            public void onAnimationEnd(Animation animation) {
                mBluegrey.setVisibility(INVISIBLE);
                mBlue.setVisibility(INVISIBLE);
                mRed.setVisibility(INVISIBLE);
                mTeal.setVisibility(INVISIBLE);
                mCyan.setVisibility(INVISIBLE);
                mOrange.setVisibility(INVISIBLE);
            }

            @Override
            public void onAnimationRepeat(Animation animation) {

            }
        });
        AlphaAnimation anim2 = new AlphaAnimation(1, 0);
        anim2.setDuration(200);
        AlphaAnimation anim3 = new AlphaAnimation(1, 0);
        anim3.setDuration(200);
        AlphaAnimation anim4 = new AlphaAnimation(1, 0);
        anim4.setDuration(200);
        AlphaAnimation anim5 = new AlphaAnimation(1, 0);
        anim5.setDuration(200);
        AlphaAnimation anim6 = new AlphaAnimation(1, 0);
        anim6.setDuration(200);
        mBlue.startAnimation(anim1);
        mBluegrey.startAnimation(anim2);
        mTeal.startAnimation(anim3);
        mRed.startAnimation(anim4);
        mOrange.startAnimation(anim5);
        mCyan.startAnimation(anim6);
    }
}
