package com.android.systemui.recents.views;

import android.app.ActivityOptions;
import android.content.ClipData;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Handler;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Display;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.TranslateXAnimation;
import android.view.animation.TranslateYAnimation;
import android.widget.FrameLayout;
import android.widget.OverScroller;

import com.android.systemui.R;
import com.android.systemui.recents.Recents;
import com.android.systemui.recents.RecentsActivity;
import com.android.systemui.recents.misc.SystemServicesProxy;
import com.android.systemui.recents.model.RecentsTaskLoader;
import com.android.systemui.recents.model.Task;
import com.android.systemui.recents.model.TaskStack;

import java.util.ArrayList;

public class VerticalRecentTasksView extends RecentTasksView {

    private float[] mLastTouchesY;
    private float mLastTouchX;

    public VerticalRecentTasksView(Context context) {
        this(context, null);
    }

    public VerticalRecentTasksView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public VerticalRecentTasksView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mLastTouchesY = new float[2];
    }

    @Override
    void initViews() {
        removeAllViews();
        boolean abort = false;
        WindowManager wm = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
        Display display = wm.getDefaultDisplay();
        Point size = new Point();
        display.getSize(size);
        float y = (size.y - 24 * getResources().getDisplayMetrics().density) * (5f/6f);
        int taskIndex = mTasks.size() - 1;
        mFirstTaskIndex = taskIndex;
        while (taskIndex >= 0 && !abort) {
            Task task = mTasks.get(taskIndex);
            y = y - mThumbnailSize;
            if (y <= getScrollX() + size.y) {
                RecentTaskView rtv = new RecentTaskView(getContext());
                LayoutParams params = new LayoutParams(LayoutParams.MATCH_PARENT, mThumbnailSize);
                rtv.setLayoutParams(params);
                rtv.setY(y);
                rtv.setX(0);
                rtv.setTag(y);
                rtv.setElevation(4.f);
                rtv.setTask(task);
                rtv.setOnClickListener(this);
                rtv.setOnLongClickListener(this);
                if (y <= getScrollY()) {
                    rtv.setY(0);
                    rtv.setElevation(0f);
                    rtv.moveToBackground();
                    abort = true;
                }
                addView(rtv);
            } else {
                mFirstTaskIndex--;
            }
            taskIndex--;
        }
        mLastTaskIndex = taskIndex;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (getChildCount() < 1) return false;
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                mTouchDownTime = System.currentTimeMillis();
                mLastTouchesY[0] = event.getY();
                mLastTouchesY[1] = event.getY();
                mLastTouchX = event.getX();
                mTouchDownX = event.getX();
                mTouchDownY = event.getY();
                mFastScrolling = false;
                mLongPressed = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (mLongPressed) return true;
                if (System.currentTimeMillis() - mTouchDownTime > 500
                        && System.currentTimeMillis() - mTouchDownTime < 1000
                        && Math.abs(event.getX() - mTouchDownX) < 10
                        && Math.abs(event.getY() - mTouchDownY) < 10
                        && (getChildAt(event.getX(), event.getY()) != null)) {
                    getChildAt(event.getX(), event.getY()).performLongClick();
                    mLongPressed = true;
                    mSwipeDirection = NONE;
                    return true;
                }
                if (Math.abs(event.getX() - mTouchDownX) < 10
                        && Math.abs(event.getY() - mTouchDownY) < 10) {
                    mSwipeDirection = NONE;
                    return true;
                }
                if (mSwipeDirection == NONE) {
                    if (Math.abs(event.getX() - mTouchDownX) > Math.abs(event.getY() - mTouchDownY)) {
                        mSwipeDirection = X;
                        if (getChildAt(event.getX(), event.getY()) != null) {
                            mSwipeView = getChildAt(event.getX(), event.getY());
                        } else {
                            mSwipeDirection = Y;
                        }
                    } else {
                        mSwipeDirection = Y;
                    }
                }
                if (mSwipeDirection == Y) {
                    float yDiff = event.getY() - mLastTouchesY[1];
                    if (getScrollY() - yDiff > 0) {
                        yDiff = getScrollY();
                    }
                    if(getScrollX() - yDiff <
                            mTasks.size() * -mThumbnailSize + mThumbnailSize + getWidth()) {
                        yDiff = -((mTasks.size() * -mThumbnailSize + mThumbnailSize + getWidth() -
                                getScrollY()));
                    }
                    scrollBy(0, -(int) yDiff);
                    mLastTouchesTimes[0] = mLastTouchesTimes[1];
                    mLastTouchesTimes[1] = System.currentTimeMillis();
                    mLastTouchesY[0] = mLastTouchesY[1];
                    mLastTouchesY[1] = event.getY();
                } else {
                    float xDiff = event.getX() - mLastTouchX;
                    mSwipeView.setX(mSwipeView.getX() + xDiff);
                    mLastTouchX = event.getX();
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (mSwipeDirection == X && mSwipeView != null
                        && Math.abs(mTouchDownX - event.getX()) > mSwipeView.getWidth()) {
                    removeTaskView((RecentTaskView) mSwipeView);
                    mSwipeDirection = NONE;
                    return true;
                }
                if (mSwipeDirection == X && mSwipeView != null) {
                    TranslateXAnimation anim = new TranslateXAnimation(Animation.ABSOLUTE,
                            mSwipeView.getX(), Animation.ABSOLUTE, 0);
                    anim.setDuration(150);
                    mSwipeView.setX(0);
                    mSwipeView.startAnimation(anim);
                    mSwipeDirection = NONE;
                    return true;
                }
                if (System.currentTimeMillis() - mTouchDownTime < 100
                        && Math.abs(event.getX() - mTouchDownX) < 10
                        && Math.abs(event.getY() - mTouchDownY) < 10
                        && (getChildAt(event.getX(), event.getY()) != null)) {
                    getChildAt(event.getX(), event.getY()).performClick();
                    mSwipeDirection = NONE;
                    return true;
                }
                if(mSwipeDirection == Y) {
                    mScrollVelocity = (int)(((event.getY() - mLastTouchesY[0]) / getWidth()) *
                            -50000 / (System.currentTimeMillis() - mLastTouchesTimes[0]));
                    smoothScroll();
                }
                mSwipeDirection = NONE;
                return true;
        }
        return false;
    }

    @Override
    public void removeTaskView(final RecentTaskView taskView) {
        final int index = indexOfChild(taskView);
        taskView.setVisibility(INVISIBLE);
        new Thread(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < 5; i++) {
                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            for (int j = 0; j < index; j++) {
                                getChildAt(j).setY(getChildAt(j).getY() - mThumbnailSize / 5.f);
                            }
                            scrollBy(0, (int) (mThumbnailSize / -5.0));
                        }
                    });
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException e) {
                        Log.e(getClass().getSimpleName(), e.getMessage());
                    }
                }
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        RecentsTaskLoader loader = RecentsTaskLoader.getInstance();
                        loader.deleteTaskData(taskView.getTask(), false);
                        mTasks.remove(taskView.getTask());
                        mSystemServicesProxy.removeTask(taskView.getTask().key.id);
                        scrollBy(0,mThumbnailSize - 1);
                        initViews();
                    }
                });
            }
        }).start();
    }

    @Override
    protected void onScrollChanged(int l, int t, int oldl, int oldt) {
        super.onScrollChanged(l, t, oldl, oldt);
        mIsComputingScroll = true;
        for (int i = 0; i < getChildCount(); i++) {
            RecentTaskView view = (RecentTaskView) getChildAt(i);
            if (t > (Float) view.getTag()) {
                view.setY(t);
                view.setElevation(0f);
                if (view.isInForeground()) {
                    if (i == getChildCount() - 2) {
                        removeViewAt(getChildCount() - 1);
                        mLastTaskIndex++;
                    }
                    view.moveToBackground();
                }
            } else {
                if (!view.isInForeground()) {
                    if (i == getChildCount() - 1 && mLastTaskIndex > 0) {
                        RecentTaskView newView = new RecentTaskView(getContext());
                        LayoutParams params = new LayoutParams(LayoutParams.MATCH_PARENT,
                                mThumbnailSize);
                        newView.setLayoutParams(params);
                        float y = (Float) getChildAt(i).getTag() - mThumbnailSize;
                        newView.setY(t);
                        newView.setTag(y);
                        newView.setElevation(0.f);
                        newView.moveToBackground();
                        newView.setTask(mTasks.get(mLastTaskIndex - 1));
                        newView.setOnClickListener(this);
                        newView.setOnLongClickListener(this);
                        addView(newView);
                        view.setY((Float) getChildAt(i).getTag());
                        view.setElevation(4f);
                        mLastTaskIndex--;
                    }
                    view.moveToForeground();
                }
            }
        }
        if (getChildAt(0).getY() - getScrollY() > getHeight()) {
            removeViewAt(0);
            mFirstTaskIndex--;
        }
        if (getChildAt(0).getY() - getScrollX() < getHeight() - mThumbnailSize
                && mFirstTaskIndex < mTasks.size() - 1) {
            RecentTaskView newView = new RecentTaskView(getContext());
            LayoutParams params = new LayoutParams(LayoutParams.MATCH_PARENT, mThumbnailSize);
            newView.setLayoutParams(params);
            float y = (Float) getChildAt(0).getTag() + mThumbnailSize;
            newView.setY(getChildAt(0).getY() + mThumbnailSize);
            newView.setTag(y);
            newView.setElevation(0.f);
            newView.moveToBackground();
            newView.setTask(mTasks.get(mFirstTaskIndex + 1));
            newView.setOnClickListener(this);
            newView.setOnLongClickListener(this);
            addView(newView, 0);
            mFirstTaskIndex++;
        }
        mIsComputingScroll = false;
    }

    @Override
    void smoothScroll() {
        mFastScrolling = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                while (mFastScrolling) {
                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (getScrollY() + mScrollVelocity > 0) {
                                mScrollVelocity = -getScrollY();
                            }
                            if(getScrollY() + mScrollVelocity < mTasks.size() * -mThumbnailSize +
                                    mThumbnailSize + getHeight()) {
                                mScrollVelocity = (mTasks.size() * -mThumbnailSize +
                                        mThumbnailSize + getHeight() - getScrollY());
                            }
                            if (!mIsComputingScroll) scrollBy(0, mScrollVelocity < mThumbnailSize ?
                                    mScrollVelocity : mThumbnailSize - 1);
                        }
                    });
                    try {
                        Thread.sleep(60);
                    } catch (InterruptedException e) {
                        Log.e(getClass().getSimpleName(), e.getMessage());
                    }
                    mScrollVelocity = mScrollVelocity * 4 / 5;
                    if (mScrollVelocity < 1 && mScrollVelocity > -1) mFastScrolling = false;
                }
                mFastScrolling = false;
            }
        }).start();
    }
}
