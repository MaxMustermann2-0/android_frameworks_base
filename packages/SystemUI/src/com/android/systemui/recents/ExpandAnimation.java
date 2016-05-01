package com.android.systemui.recents;

import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.Transformation;

public class ExpandAnimation extends Animation {
    private View mView;
    private float mStartHeight;
    private float mTargetHeight;

    public ExpandAnimation(View v, float startHeight, float targetHeight) {
        mStartHeight = startHeight;
        mTargetHeight = targetHeight;
        mView = v;
        setDuration(300);
    }

    @Override
    protected void applyTransformation(float interpolatedTime, Transformation t) {
        float height = (mTargetHeight - mStartHeight) * interpolatedTime + mStartHeight;
        ViewGroup.LayoutParams p = mView.getLayoutParams();
        p.height = (int) height;
        mView.requestLayout();
    }
}