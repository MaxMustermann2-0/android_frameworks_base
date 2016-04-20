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

public class RecentTasksView extends FrameLayout implements View.OnClickListener, View.OnLongClickListener {

    static final int NONE = 0;
    static final int X = 1;
    static final int Y = 2;

    private float[] mLastTouchesX;
    long[] mLastTouchesTimes;
    private float mLastTouchY;
    float mTouchDownX;
    float mTouchDownY;
    long mTouchDownTime;
    int mThumbnailSize;
    int mFirstTaskIndex;
    int mLastTaskIndex;
    Handler mHandler;
    int mScrollVelocity;
    boolean mIsComputingScroll;
    int mSwipeDirection = NONE;
    View mSwipeView;

    SystemServicesProxy mSystemServicesProxy;

    ArrayList<Task> mTasks;
    boolean mFastScrolling;
    boolean mLongPressed;

    private OnTaskLongClickListener mOnTaskLongClickListener;

    public interface OnTaskLongClickListener {
        boolean onTaskLongClick(RecentTaskView taskView);
    }

    public RecentTasksView(Context context) {
        this(context, null);
    }

    public RecentTasksView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public RecentTasksView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mLastTouchesX = new float[2];
        mLastTouchesTimes = new long[2];
        setClipChildren(false);
        mSystemServicesProxy = RecentsTaskLoader.getInstance().getSystemServicesProxy();
        mThumbnailSize = context.getResources()
                .getDimensionPixelSize(R.dimen.recents_thumbnail_size);
        mFastScrolling = false;
        mIsComputingScroll = false;
        mLongPressed = false;
        mHandler = new Handler();
    }

    void initViews() {
        removeAllViews();
        boolean abort = false;
        WindowManager wm = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
        Display display = wm.getDefaultDisplay();
        Point size = new Point();
        display.getSize(size);
        float x = size.x;
        int taskIndex = mTasks.size() - 1;
        mFirstTaskIndex = taskIndex;
        while (taskIndex >= 0 && !abort) {
            Task task = mTasks.get(taskIndex);
            x = x - mThumbnailSize;
            if (x <= getScrollX() + size.x) {
                RecentTaskView rtv = new RecentTaskView(getContext());
                LayoutParams params = new LayoutParams(mThumbnailSize, LayoutParams.MATCH_PARENT);
                rtv.setLayoutParams(params);
                rtv.setX(x);
                rtv.setY(0);
                rtv.setTag(x);
                rtv.setElevation(4.f);
                rtv.setTask(task);
                rtv.setOnClickListener(this);
                rtv.setOnLongClickListener(this);
                if (x <= getScrollX()) {
                    rtv.setX(0);
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

    public void setTasks(ArrayList<TaskStack> tasks) {
        mTasks = tasks.get(0).getTasks();
        scrollTo(0, 0);
        initViews();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        initViews();
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (getChildCount() < 1) return false;
        switch (ev.getAction()) {
            case MotionEvent.ACTION_DOWN:
                return true;
            case MotionEvent.ACTION_MOVE:
                return true;
            case MotionEvent.ACTION_UP:
                return true;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (getChildCount() < 1) return false;
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                mTouchDownTime = System.currentTimeMillis();
                mLastTouchesX[0] = event.getX();
                mLastTouchesX[1] = event.getX();
                mLastTouchesTimes[0] = System.currentTimeMillis();
                mLastTouchesTimes[1] = System.currentTimeMillis();
                mLastTouchY = event.getY();
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
                    if (Math.abs(event.getX() - mTouchDownX) < Math.abs(event.getY() - mTouchDownY)) {
                        mSwipeDirection = Y;
                        if (getChildAt(event.getX(), event.getY()) != null) {
                            mSwipeView = getChildAt(event.getX(), event.getY());
                        } else {
                            mSwipeDirection = X;
                        }
                    } else {
                        mSwipeDirection = X;
                    }
                }
                if (mSwipeDirection == X) {
                    float xDiff = event.getX() - mLastTouchesX[1];
                    if (getScrollX() - xDiff > 0) {
                        xDiff = getScrollX();
                    }
                    if(getScrollX() - xDiff <
                            mTasks.size() * -mThumbnailSize + mThumbnailSize + getWidth()) {
                        xDiff = -((mTasks.size() * -mThumbnailSize + mThumbnailSize + getWidth() -
                                getScrollX()));
                    }
                    scrollBy(-(int) xDiff, 0);
                    mLastTouchesTimes[0] = mLastTouchesTimes[1];
                    mLastTouchesTimes[1] = System.currentTimeMillis();
                    mLastTouchesX[0] = mLastTouchesX[1];
                    mLastTouchesX[1] = event.getX();
                } else {
                    float yDiff = event.getY() - mLastTouchY;
                    mSwipeView.setY(mSwipeView.getY() + yDiff);
                    mLastTouchY = event.getY();
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (mSwipeDirection == Y && mSwipeView != null
                        && Math.abs(mTouchDownY - event.getY()) > mSwipeView.getHeight()) {
                    removeTaskView((RecentTaskView) mSwipeView);
                    mSwipeDirection = NONE;
                    return true;
                }
                if (mSwipeDirection == Y && mSwipeView != null) {
                    TranslateYAnimation anim = new TranslateYAnimation(Animation.ABSOLUTE,
                        mSwipeView.getY(), Animation.ABSOLUTE, 0);
                    anim.setDuration(150);
                    mSwipeView.setY(0);
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
                if(mSwipeDirection == X) {
                    mScrollVelocity = (int)(((event.getX() - mLastTouchesX[0]) / getWidth()) *
                            -50000 / (System.currentTimeMillis() - mLastTouchesTimes[0]));
                    smoothScroll();
                }
                mSwipeDirection = NONE;
                return true;
        }
        return false;
    }

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
                                getChildAt(j).setX(getChildAt(j).getX() - mThumbnailSize / 5.f);
                            }
                            scrollBy((int) (mThumbnailSize / -5.0), 0);
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
                        scrollBy(mThumbnailSize - 1, 0);
                        initViews();
                    }
                });
            }
        }).start();
    }

    @Override
    protected void onScrollChanged(int l, int t, int oldl, int oldt) {
        super.onScrollChanged(l, t, oldl, oldt);
        if (getClass() != RecentTasksView.class) return;
        mIsComputingScroll = true;
        for (int i = 0; i < getChildCount(); i++) {
            RecentTaskView view = (RecentTaskView) getChildAt(i);
            if (l > (Float) view.getTag()) {
                view.setX(l);
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
                        LayoutParams params = new LayoutParams(mThumbnailSize,
                                LayoutParams.MATCH_PARENT);
                        newView.setLayoutParams(params);
                        float x = (Float) getChildAt(i).getTag() - mThumbnailSize;
                        newView.setX(l);
                        newView.setTag(x);
                        newView.setElevation(0.f);
                        newView.moveToBackground();
                        newView.setTask(mTasks.get(mLastTaskIndex - 1));
                        newView.setOnClickListener(this);
                        newView.setOnLongClickListener(this);
                        addView(newView);
                        view.setX((Float) getChildAt(i).getTag());
                        view.setElevation(4f);
                        mLastTaskIndex--;
                    }
                    view.moveToForeground();
                }
            }
        }
        if (getChildAt(0).getX() - getScrollX() > getWidth()) {
            removeViewAt(0);
            mFirstTaskIndex--;
        }
        if (getChildAt(0).getX() - getScrollX() < getWidth() - mThumbnailSize
                && mFirstTaskIndex < mTasks.size() - 1) {
            RecentTaskView newView = new RecentTaskView(getContext());
            LayoutParams params = new LayoutParams(mThumbnailSize,
                    LayoutParams.MATCH_PARENT);
            newView.setLayoutParams(params);
            float x = (Float) getChildAt(0).getTag() + mThumbnailSize;
            newView.setX(getChildAt(0).getX() + mThumbnailSize);
            newView.setTag(x);
            newView.setElevation(4.f);
            newView.moveToForeground();
            newView.setTask(mTasks.get(mFirstTaskIndex + 1));
            newView.setOnClickListener(this);
            newView.setOnLongClickListener(this);
            addView(newView, 0);
            mFirstTaskIndex++;
        }
        mIsComputingScroll = false;
    }

    public View getChildAt(float x, float y) {
        x = x + getScrollX();
        y = y + getScrollY();
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if ((child.getX() < x) && (child.getX() + child.getWidth() > x) && (child.getY() < y)
                    && (child.getY() + child.getHeight()) > y) {
                return child;
            }
        }
        return null;
    }

    void smoothScroll() {
        mFastScrolling = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                while (mFastScrolling) {
                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (getScrollX() + mScrollVelocity > 0) {
                                mScrollVelocity = -getScrollX();
                            }
                            if(getScrollX() + mScrollVelocity < mTasks.size() * -mThumbnailSize +
                                    mThumbnailSize + getWidth()) {
                                mScrollVelocity = (mTasks.size() * -mThumbnailSize +
                                        mThumbnailSize + getWidth() - getScrollX());
                            }
                            if (!mIsComputingScroll) scrollBy(mScrollVelocity < mThumbnailSize ?
                                    mScrollVelocity : mThumbnailSize - 1, 0);
                        }
                    });
                    try {
                        Thread.sleep(30);
                    } catch (InterruptedException e) {
                        Log.e(getClass().getSimpleName(), e.getMessage());
                    }
                    mScrollVelocity = (int)(mScrollVelocity * 0.95);
                    if (mScrollVelocity < 1 && mScrollVelocity > -1) mFastScrolling = false;
                }
                mFastScrolling = false;
            }
        }).start();
    }

    public void launchTask(Task task) {
        if (task.isActive) {
            mSystemServicesProxy.moveTaskToFront(task.key.id, null);
        } else {
            mSystemServicesProxy.startActivityFromRecents(getContext(), task.key.id,
                    task.activityLabel, null);
        }
    }

    public void startScreenPinning(RecentTaskView rtv) {
        ActivityOptions.OnAnimationStartedListener animStartedListener =
                new ActivityOptions.OnAnimationStartedListener() {
            boolean mTriggered = false;
            @Override
            public void onAnimationStarted() {
                if (!mTriggered) {
                    postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            Recents.startScreenPinning(getContext(), mSystemServicesProxy);
                        }
                    }, 350);
                    mTriggered = true;
                }
            }
        };
        int left = 0, top = 0;
        int width = rtv.getMeasuredWidth(), height = rtv.mThumbnail.getMeasuredHeight();
        Rect bounds = rtv.mThumbnail.getDrawable().getBounds();
        left = (width - bounds.width()) / 2;
        top = rtv.mThumbnail.getPaddingTop();
        width = bounds.width();
        height = bounds.height();
        ActivityOptions options = ActivityOptions.makeThumbnailAspectScaleUpAnimation(
                rtv.mThumbnail,
                Bitmap.createBitmap(1, 1, Bitmap.Config.ALPHA_8).createAshmemBitmap(),
                left, top, width, height, rtv.mThumbnail.getHandler(), animStartedListener);
        if (rtv.getTask().isActive) {
            mSystemServicesProxy.moveTaskToFront(rtv.getTask().key.id, options);
        } else {
            mSystemServicesProxy.startActivityFromRecents(getContext(), rtv.getTask().key.id,
                    rtv.getTask().activityLabel, options);
        }
    }

    public Task getNextTaskOrTopTask(Task task) {
        int index = mTasks.indexOf(task);
        if (index > 0)  return mTasks.get(index - 1);
        return mTasks.get(mTasks.size() - 1);
    }

    public void setOnTaskLongClickListener(OnTaskLongClickListener listener) {
        mOnTaskLongClickListener = listener;
    }

    @Override
    public void onClick(View view) {
        RecentTaskView rtv = (RecentTaskView) view;
        int left = 0, top = 0;
        int width = rtv.getMeasuredWidth(), height = rtv.mThumbnail.getMeasuredHeight();
        Rect bounds = rtv.mThumbnail.getDrawable().getBounds();
        left = (width - bounds.width()) / 2;
        top = rtv.mThumbnail.getPaddingTop();
        width = bounds.width();
        height = bounds.height();
        ActivityOptions options = ActivityOptions.makeThumbnailAspectScaleUpAnimation(rtv.mThumbnail,
                Bitmap.createBitmap(1, 1, Bitmap.Config.ALPHA_8).createAshmemBitmap(),
                left, top, width, height, rtv.mThumbnail.getHandler(), null);
        if (rtv.getTask().isActive) {
            mSystemServicesProxy.moveTaskToFront(rtv.getTask().key.id, options);
        } else {
            mSystemServicesProxy.startActivityFromRecents(getContext(), rtv.getTask().key.id,
                    rtv.getTask().activityLabel, options);
        }
    }

    @Override
    public boolean onLongClick(View view) {
        Log.d("AndroidRuntime", "LongClick!!");
        return mOnTaskLongClickListener != null &&
                mOnTaskLongClickListener.onTaskLongClick((RecentTaskView) view);
    }
}
