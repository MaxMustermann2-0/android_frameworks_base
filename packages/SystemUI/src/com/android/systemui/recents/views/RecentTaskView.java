package com.android.systemui.recents.views;

import android.content.Context;
import android.content.pm.PackageManager;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.recents.model.Task;
import com.android.systemui.recents.model.TaskStack;


public class RecentTaskView extends FrameLayout {
    ImageView mThumbnail;
    private TextView mLabel;
    private ImageView mIcon;
    private View mShadow;
    private boolean mInForeground;
    private Task mTask;

    public RecentTaskView(Context context) {
        super(context);
        setClickable(true);
        setLongClickable(true);
        addView(inflate(context, R.layout.recent_task_view, null));
        mThumbnail = (ImageView) findViewById(R.id.thumbnail);
        mIcon = (ImageView) findViewById(R.id.icon);
        mLabel = (TextView) findViewById(R.id.label);
        mShadow = findViewById(R.id.shadow);
    }

    public void setTask(Task task) {
        mTask = task;
        mLabel.setText(task.activityLabel);
        mIcon.setImageDrawable(task.activityIcon);
        mThumbnail.setBackgroundColor(task.colorPrimary);
        mThumbnail.setImageBitmap(task.thumbnail);
    }

    public void moveToBackground() {
        mInForeground = false;
        setElevation(0f);
        mThumbnail.setElevation(0f);
        mShadow.setVisibility(VISIBLE);
        AlphaAnimation fadeIn = new AlphaAnimation(0f, 1f);
        fadeIn.setDuration(200);
        AlphaAnimation fadeOut = new AlphaAnimation(1f, 0f);
        fadeOut.setDuration(200);
        fadeOut.setAnimationListener(new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {

            }

            @Override
            public void onAnimationEnd(Animation animation) {
                mLabel.setVisibility(INVISIBLE);
            }

            @Override
            public void onAnimationRepeat(Animation animation) {

            }
        });
        mLabel.startAnimation(fadeOut);
        mShadow.startAnimation(fadeIn);
    }

    public void moveToForeground() {
        mInForeground = true;
        setElevation(4f);
        mThumbnail.setElevation(4f);
        mLabel.setVisibility(VISIBLE);
        AlphaAnimation fadeIn = new AlphaAnimation(0f, 1f);
        fadeIn.setDuration(200);
        AlphaAnimation fadeOut = new AlphaAnimation(1f, 0f);
        fadeOut.setDuration(200);
        fadeOut.setAnimationListener(new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {

            }

            @Override
            public void onAnimationEnd(Animation animation) {
                mShadow.setVisibility(INVISIBLE);
            }

            @Override
            public void onAnimationRepeat(Animation animation) {

            }
        });
        mShadow.startAnimation(fadeOut);
        mLabel.startAnimation(fadeIn);
    }

    public boolean isInForeground() {
        return mInForeground;
    }

    public Task getTask() {
        return mTask;
    }
}
