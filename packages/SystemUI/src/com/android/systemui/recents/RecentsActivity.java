/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.recents;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.app.SearchManager;
import android.content.BroadcastReceiver;
import android.content.ContentUris;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.Point;
import android.graphics.PorterDuff;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.CalendarContract;
import android.provider.ContactsContract;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Display;
import android.view.DragEvent;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewStub;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.RotateAnimation;
import android.view.animation.ScaleAnimation;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.Toast;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.Prefs;
import com.android.systemui.R;
import com.android.systemui.recents.misc.Console;
import com.android.systemui.recents.misc.DebugTrigger;
import com.android.systemui.recents.misc.ReferenceCountedTrigger;
import com.android.systemui.recents.misc.SystemServicesProxy;
import com.android.systemui.recents.model.RecentsTaskLoadPlan;
import com.android.systemui.recents.model.RecentsTaskLoader;
import com.android.systemui.recents.model.Task;
import com.android.systemui.recents.model.TaskStack;
import com.android.systemui.recents.views.DebugOverlayView;
import com.android.systemui.recents.views.RecentsView;
import com.android.systemui.recents.views.SystemBarScrimViews;
import com.android.systemui.recents.views.ViewAnimation;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

/**
 * The main Recents activity that is started from AlternateRecentsComponent.
 */
public class RecentsActivity extends Activity implements RecentsView.RecentsViewCallbacks,
        DebugOverlayView.DebugOverlayViewCallbacks, View.OnClickListener, View.OnDragListener {

    RecentsConfiguration mConfig;
    long mLastTabKeyEventTime;

    // Top level views
    RecentsView mRecentsView;
    SystemBarScrimViews mScrimViews;
    ViewStub mEmptyViewStub;
    ViewStub mDebugOverlayStub;
    View mEmptyView;
    DebugOverlayView mDebugOverlay;

    // Resize task debug
    RecentsResizeTaskDialog mResizeTaskDebugDialog;

    // Runnables to finish the Recents activity
    FinishRecentsRunnable mFinishLaunchHomeRunnable;

    // Runnable to be executed after we paused ourselves
    Runnable mAfterPauseRunnable;

    private ArrayList<SearchItem> mApplications;
    private ArrayList<SearchItem> mSearchableData;
    private ArrayList<SearchItem> mSearchResults;
    private ArrayList<SearchItem> mFilesResults;
    private GridView mSearchResultView;
    private EditText mSearch;
    private ImageView mExpandCollapse;
    private View mSearchOverlay;
    private ImageView mSearchDeleter;
    private boolean mApplicationIndexing = false;
    private boolean mDataIndexing = false;
    private boolean mFilesIndexing = false;
    private boolean mSearchExpanded = false;


    /**
     * A common Runnable to finish Recents either by calling finish() (with a custom animation) or
     * launching Home with some ActivityOptions.  Generally we always launch home when we exit
     * Recents rather than just finishing the activity since we don't know what is behind Recents in
     * the task stack.  The only case where we finish() directly is when we are cancelling the full
     * screen transition from the app.
     */
    class FinishRecentsRunnable implements Runnable {
        Intent mLaunchIntent;
        ActivityOptions mLaunchOpts;

        /**
         * Creates a finish runnable that starts the specified intent, using the given
         * ActivityOptions.
         */
        public FinishRecentsRunnable(Intent launchIntent, ActivityOptions opts) {
            mLaunchIntent = launchIntent;
            mLaunchOpts = opts;
        }

        @Override
        public void run() {
            // Finish Recents
            if (mLaunchIntent != null) {
                try {
                    if (mLaunchOpts != null) {
                        startActivityAsUser(mLaunchIntent, mLaunchOpts.toBundle(), UserHandle.CURRENT);
                    } else {
                        startActivityAsUser(mLaunchIntent, UserHandle.CURRENT);
                    }
                } catch (Exception e) {
                    Console.logError(RecentsActivity.this,
                            getString(R.string.recents_launch_error_message, "Home"));
                }
            } else {
                finish();
                overridePendingTransition(R.anim.recents_to_launcher_enter,
                        R.anim.recents_to_launcher_exit);
            }
        }
    }

    /**
     * Broadcast receiver to handle messages from AlternateRecentsComponent.
     */
    final BroadcastReceiver mServiceBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(Recents.ACTION_HIDE_RECENTS_ACTIVITY)) {
                if (intent.getBooleanExtra(Recents.EXTRA_TRIGGERED_FROM_ALT_TAB, false)) {
                    // If we are hiding from releasing Alt-Tab, dismiss Recents to the focused app
                    dismissRecentsToFocusedTaskOrHome(false);
                } else if (intent.getBooleanExtra(Recents.EXTRA_TRIGGERED_FROM_HOME_KEY, false)) {
                    // Otherwise, dismiss Recents to Home
                    dismissRecentsToHomeRaw(true);
                } else {
                    // Do nothing
                }
            } else if (action.equals(Recents.ACTION_TOGGLE_RECENTS_ACTIVITY)) {
                // If we are toggling Recents, then first unfilter any filtered stacks first
                dismissRecentsToFocusedTaskOrHome(true);
            } else if (action.equals(Recents.ACTION_START_ENTER_ANIMATION)) {
                // Trigger the enter animation
                onEnterAnimationTriggered();
                // Notify the fallback receiver that we have successfully got the broadcast
                // See AlternateRecentsComponent.onAnimationStarted()
                setResultCode(Activity.RESULT_OK);
            }
        }
    };

    /**
     * Broadcast receiver to handle messages from the system
     */
    final BroadcastReceiver mSystemBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(Intent.ACTION_SCREEN_OFF)) {
                // When the screen turns off, dismiss Recents to Home
                dismissRecentsToHome(false);
            }
        }
    };

    /**
     * A custom debug trigger to listen for a debug key chord.
     */
    final DebugTrigger mDebugTrigger = new DebugTrigger(new Runnable() {
        @Override
        public void run() {
            onDebugModeTriggered();
        }
    });

    /**
     * Updates the set of recent tasks
     */
    void updateRecentsTasks() {
        // If AlternateRecentsComponent has preloaded a load plan, then use that to prevent
        // reconstructing the task stack
        RecentsTaskLoader loader = RecentsTaskLoader.getInstance();
        RecentsTaskLoadPlan plan = Recents.consumeInstanceLoadPlan();
        if (plan == null) {
            plan = loader.createLoadPlan(this);
        }

        // Start loading tasks according to the load plan
        if (!plan.hasTasks()) {
            loader.preloadTasks(plan, mConfig.launchedFromHome);
        }
        RecentsTaskLoadPlan.Options loadOpts = new RecentsTaskLoadPlan.Options();
        loadOpts.runningTaskId = mConfig.launchedToTaskId;
        loadOpts.numVisibleTasks = mConfig.launchedNumVisibleTasks;
        loadOpts.numVisibleTaskThumbnails = mConfig.launchedNumVisibleThumbnails;
        loader.loadTasks(this, plan, loadOpts);

        ArrayList<TaskStack> stacks = plan.getAllTaskStacks();
        mConfig.launchedWithNoRecentTasks = !plan.hasTasks();
        mRecentsView.setTaskStacks(stacks);

        // Create the home intent runnable
        Intent homeIntent = new Intent(Intent.ACTION_MAIN, null);
        homeIntent.addCategory(Intent.CATEGORY_HOME);
        homeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        mFinishLaunchHomeRunnable = new FinishRecentsRunnable(homeIntent,
                ActivityOptions.makeCustomAnimation(this,
                        R.anim.recents_to_search_launcher_enter,
                        R.anim.recents_to_search_launcher_exit));

        // Mark the task that is the launch target
        int taskStackCount = stacks.size();
        int launchTaskIndexInStack = 0;
        if (mConfig.launchedToTaskId != -1) {
            for (int i = 0; i < taskStackCount; i++) {
                TaskStack stack = stacks.get(i);
                ArrayList<Task> tasks = stack.getTasks();
                int taskCount = tasks.size();
                for (int j = 0; j < taskCount; j++) {
                    Task t = tasks.get(j);
                    if (t.key.id == mConfig.launchedToTaskId) {
                        t.isLaunchTarget = true;
                        launchTaskIndexInStack = tasks.size() - j - 1;
                        break;
                    }
                }
            }
        }

        // Update the top level view's visibilities
        if (mConfig.launchedWithNoRecentTasks) {
            if (mEmptyView == null) {
                mEmptyView = mEmptyViewStub.inflate();
            }
            mEmptyView.setVisibility(View.VISIBLE);
            mEmptyView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    dismissRecentsToHome(true);
                }
            });
            mRecentsView.setSearchBarVisibility(View.GONE);
        } else {
            if (mEmptyView != null) {
                mEmptyView.setVisibility(View.GONE);
                mEmptyView.setOnClickListener(null);
            }
        }

        // Animate the SystemUI scrims into view
        mScrimViews.prepareEnterRecentsAnimation();

        // Keep track of whether we launched from the nav bar button or via alt-tab
        if (mConfig.launchedWithAltTab) {
            MetricsLogger.count(this, "overview_trigger_alttab", 1);
        } else {
            MetricsLogger.count(this, "overview_trigger_nav_btn", 1);
        }
        // Keep track of whether we launched from an app or from home
        if (mConfig.launchedFromAppWithThumbnail) {
            MetricsLogger.count(this, "overview_source_app", 1);
            // If from an app, track the stack index of the app in the stack (for affiliated tasks)
            MetricsLogger.histogram(this, "overview_source_app_index", launchTaskIndexInStack);
        } else {
            MetricsLogger.count(this, "overview_source_home", 1);
        }
        // Keep track of the total stack task count
        int taskCount = 0;
        for (int i = 0; i < stacks.size(); i++) {
            TaskStack stack = stacks.get(i);
            taskCount += stack.getTaskCount();
        }
        MetricsLogger.histogram(this, "overview_task_count", taskCount);
    }

    /**
     * Dismisses recents if we are already visible and the intent is to toggle the recents view
     */
    boolean dismissRecentsToFocusedTaskOrHome(boolean checkFilteredStackState) {
        SystemServicesProxy ssp = RecentsTaskLoader.getInstance().getSystemServicesProxy();
        if (ssp.isRecentsTopMost(ssp.getTopMostTask(), null)) {
            // If we currently have filtered stacks, then unfilter those first
            if (checkFilteredStackState &&
                    mRecentsView.unfilterFilteredStacks()) return true;
            // If we have a focused Task, launch that Task now
            if (mRecentsView.launchFocusedTask()) return true;
            // If we launched from Home, then return to Home
            if (mConfig.launchedFromHome) {
                dismissRecentsToHomeRaw(true);
                return true;
            }
            // Otherwise, try and return to the Task that Recents was launched from
            if (mRecentsView.launchPreviousTask()) return true;
            // If none of the other cases apply, then just go Home
            dismissRecentsToHomeRaw(true);
            return true;
        }
        return false;
    }

    /**
     * Dismisses Recents directly to Home.
     */
    void dismissRecentsToHomeRaw(boolean animated) {
        if (animated) {
            ReferenceCountedTrigger exitTrigger = new ReferenceCountedTrigger(this,
                    null, mFinishLaunchHomeRunnable, null);
            mRecentsView.startExitToHomeAnimation(
                    new ViewAnimation.TaskViewExitContext(exitTrigger));
        } else {
            mFinishLaunchHomeRunnable.run();
        }
    }

    /**
     * Dismisses Recents directly to Home without transition animation.
     */
    void dismissRecentsToHomeWithoutTransitionAnimation() {
        finish();
        overridePendingTransition(0, 0);
    }

    /**
     * Dismisses Recents directly to Home if we currently aren't transitioning.
     */
    boolean dismissRecentsToHome(boolean animated) {
        SystemServicesProxy ssp = RecentsTaskLoader.getInstance().getSystemServicesProxy();
        if (ssp.isRecentsTopMost(ssp.getTopMostTask(), null)) {
            // Return to Home
            dismissRecentsToHomeRaw(animated);
            return true;
        }
        return false;
    }

    /**
     * Called with the activity is first created.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // For the non-primary user, ensure that the SystemServicesProxy and configuration is
        // initialized
        RecentsTaskLoader.initialize(this);
        SystemServicesProxy ssp = RecentsTaskLoader.getInstance().getSystemServicesProxy();
        mConfig = RecentsConfiguration.reinitialize(this, ssp);

        // Set the Recents layout
        setContentView(R.layout.recents);
        mRecentsView = (RecentsView) findViewById(R.id.recents_view);
        mRecentsView.setCallbacks(this);
        mEmptyViewStub = (ViewStub) findViewById(R.id.empty_view_stub);
        mDebugOverlayStub = (ViewStub) findViewById(R.id.debug_overlay_stub);
        mScrimViews = new SystemBarScrimViews(this, mConfig);
        inflateDebugOverlay();

        // Register the broadcast receiver to handle messages when the screen is turned off
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(SearchManager.INTENT_GLOBAL_SEARCH_ACTIVITY_CHANGED);
        registerReceiver(mSystemBroadcastReceiver, filter);
        mSearchableData = new ArrayList<>();
        mApplications = new ArrayList<>();
        mSearchResults = new ArrayList<>();
        mFilesResults = new ArrayList<>();
        new IndexApplicationsTask().execute();
        new IndexDataTask().execute();
        mSearch = (EditText) findViewById(R.id.dash_search);
        mSearchResultView = (GridView) findViewById(R.id.search_results_grid);
        mExpandCollapse = (ImageView) findViewById(R.id.search_expand_icon);
        mExpandCollapse.setOnClickListener(this);
        mSearchOverlay = findViewById(R.id.recents_search_overlay);
        mSearchOverlay.setOnClickListener(this);
        mSearchDeleter = (ImageView) findViewById(R.id.search_delete_icon);
        mSearchDeleter.setOnDragListener(this);
        mSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence charSequence, int start, int count,
                                          int after) {

            }

            @Override
            public void onTextChanged(CharSequence charSequence, int start, int before, int count) {

            }

            @Override
            public void afterTextChanged(Editable editable) {
                updateSearchResults();
                if (!mApplicationIndexing) new IndexApplicationsTask().execute();
                if (!mDataIndexing) new IndexDataTask().execute();
                if (mSearch.getText().toString().length() > 0) {
                    new IndexFilesTask().execute(mSearch.getText().toString());
                }
            }
        });
    }

    /**
     * Inflates the debug overlay if debug mode is enabled.
     */
    void inflateDebugOverlay() {
        if (!Constants.DebugFlags.App.EnableDebugMode) return;

        if (mConfig.debugModeEnabled && mDebugOverlay == null) {
            // Inflate the overlay and seek bars
            mDebugOverlay = (DebugOverlayView) mDebugOverlayStub.inflate();
            mDebugOverlay.setCallbacks(this);
            mRecentsView.setDebugOverlay(mDebugOverlay);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);

        // Clear any debug rects
        if (mDebugOverlay != null) {
            mDebugOverlay.clear();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        MetricsLogger.visible(this, MetricsLogger.OVERVIEW_ACTIVITY);
        RecentsTaskLoader loader = RecentsTaskLoader.getInstance();
        SystemServicesProxy ssp = loader.getSystemServicesProxy();
        Recents.notifyVisibilityChanged(this, ssp, true);

        // Register the broadcast receiver to handle messages from our service
        IntentFilter filter = new IntentFilter();
        filter.addAction(Recents.ACTION_HIDE_RECENTS_ACTIVITY);
        filter.addAction(Recents.ACTION_TOGGLE_RECENTS_ACTIVITY);
        filter.addAction(Recents.ACTION_START_ENTER_ANIMATION);
        registerReceiver(mServiceBroadcastReceiver, filter);

        // Register any broadcast receivers for the task loader
        loader.registerReceivers(this, mRecentsView);

        // Update the recent tasks
        updateRecentsTasks();

        // If this is a new instance from a configuration change, then we have to manually trigger
        // the enter animation state, or if recents was relaunched by AM, without going through
        // the normal mechanisms
        boolean wasLaunchedByAm = !mConfig.launchedFromHome && !mConfig.launchedFromAppWithThumbnail;
        if (mConfig.launchedHasConfigurationChanged || wasLaunchedByAm) {
            onEnterAnimationTriggered();
        }

        if (!mConfig.launchedHasConfigurationChanged) {
            mRecentsView.disableLayersForOneFrame();
        }
    }

    @Override
    protected void onResume() {
        if (mConfig.searchBarEnabled && mConfig.launchedFromHome) {
            overridePendingTransition(0, 0);
        }
        super.onResume();
        boolean autoOpenSearch = Settings.System.getInt(getContentResolver(),
                "recents_auto_launch_search", 0) == 1;
        if (autoOpenSearch) expandSearch();
        else collapseSearch();
    }

    @Override
    protected void onPause() {
        super.onPause();
        boolean autoOpenSearch = Settings.System.getInt(getContentResolver(),
                "recents_auto_launch_search", 0) == 1;
        if (mSearchExpanded && !autoOpenSearch) collapseSearch();
        if (mAfterPauseRunnable != null) {
            mRecentsView.post(mAfterPauseRunnable);
            mAfterPauseRunnable = null;
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        MetricsLogger.hidden(this, MetricsLogger.OVERVIEW_ACTIVITY);
        RecentsTaskLoader loader = RecentsTaskLoader.getInstance();
        SystemServicesProxy ssp = loader.getSystemServicesProxy();
        Recents.notifyVisibilityChanged(this, ssp, false);

        // Notify the views that we are no longer visible
        mRecentsView.onRecentsHidden();

        // Unregister the RecentsService receiver
        unregisterReceiver(mServiceBroadcastReceiver);

        // Unregister any broadcast receivers for the task loader
        loader.unregisterReceivers();

        // Workaround for b/22542869, if the RecentsActivity is started again, but without going
        // through SystemUI, we need to reset the config launch flags to ensure that we do not
        // wait on the system to send a signal that was never queued.
        mConfig.launchedFromHome = false;
        mConfig.launchedFromSearchHome = false;
        mConfig.launchedFromAppWithThumbnail = false;
        mConfig.launchedToTaskId = -1;
        mConfig.launchedWithAltTab = false;
        mConfig.launchedHasConfigurationChanged = false;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // Unregister the system broadcast receivers
        unregisterReceiver(mSystemBroadcastReceiver);
    }

    public void onEnterAnimationTriggered() {
        // Try and start the enter animation (or restart it on configuration changed)
        ReferenceCountedTrigger t = new ReferenceCountedTrigger(this, null, null, null);
        ViewAnimation.TaskViewEnterContext ctx = new ViewAnimation.TaskViewEnterContext(t);
        mRecentsView.startEnterRecentsAnimation(ctx);

        // Animate the SystemUI scrim views
        mScrimViews.startEnterRecentsAnimation();
    }

    @Override
    public void onTrimMemory(int level) {
        RecentsTaskLoader loader = RecentsTaskLoader.getInstance();
        if (loader != null) {
            loader.onTrimMemory(level);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_TAB: {
                boolean hasRepKeyTimeElapsed = (SystemClock.elapsedRealtime() -
                        mLastTabKeyEventTime) > mConfig.altTabKeyDelay;
                if (event.getRepeatCount() <= 0 || hasRepKeyTimeElapsed) {
                    // Focus the next task in the stack
                    final boolean backward = event.isShiftPressed();
                    mRecentsView.focusNextTask(!backward);
                    mLastTabKeyEventTime = SystemClock.elapsedRealtime();
                }
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_UP: {
                mRecentsView.focusNextTask(true);
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_DOWN: {
                mRecentsView.focusNextTask(false);
                return true;
            }
            case KeyEvent.KEYCODE_DEL:
            case KeyEvent.KEYCODE_FORWARD_DEL: {
                mRecentsView.dismissFocusedTask();
                // Keep track of deletions by keyboard
                MetricsLogger.histogram(this, "overview_task_dismissed_source",
                        Constants.Metrics.DismissSourceKeyboard);
                return true;
            }
            default:
                break;
        }
        // Pass through the debug trigger
        mDebugTrigger.onKeyEvent(keyCode);
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onUserInteraction() {
        mRecentsView.onUserInteraction();
    }

    @Override
    public void onBackPressed() {
        if(mSearchExpanded) {
            collapseSearch();
            return;
        }

        // Test mode where back does not do anything
        if (mConfig.debugModeEnabled) return;

        // Dismiss Recents to the focused Task or Home
        dismissRecentsToFocusedTaskOrHome(true);
    }

    /**
     * Called when debug mode is triggered
     */
    public void onDebugModeTriggered() {
        if (mConfig.developerOptionsEnabled) {
            if (Prefs.getBoolean(this, Prefs.Key.DEBUG_MODE_ENABLED, false /* boolean */)) {
                // Disable the debug mode
                Prefs.remove(this, Prefs.Key.DEBUG_MODE_ENABLED);
                mConfig.debugModeEnabled = false;
                inflateDebugOverlay();
                if (mDebugOverlay != null) {
                    mDebugOverlay.disable();
                }
            } else {
                // Enable the debug mode
                Prefs.putBoolean(this, Prefs.Key.DEBUG_MODE_ENABLED, true);
                mConfig.debugModeEnabled = true;
                inflateDebugOverlay();
                if (mDebugOverlay != null) {
                    mDebugOverlay.enable();
                }
            }
            Toast.makeText(this, "Debug mode (" + Constants.Values.App.DebugModeVersion + ") " +
                            (mConfig.debugModeEnabled ? "Enabled" : "Disabled") + ", please restart Recents now",
                    Toast.LENGTH_SHORT).show();
        }
    }


    /****
     * RecentsResizeTaskDialog
     ****/

    private RecentsResizeTaskDialog getResizeTaskDebugDialog() {
        if (mResizeTaskDebugDialog == null) {
            mResizeTaskDebugDialog = new RecentsResizeTaskDialog(getFragmentManager(), this);
        }
        return mResizeTaskDebugDialog;
    }

    @Override
    public void onTaskResize(Task t) {
        getResizeTaskDebugDialog().showResizeTaskDialog(t, mRecentsView);
    }

    /****
     * RecentsView.RecentsViewCallbacks Implementation
     ****/

    @Override
    public void onExitToHomeAnimationTriggered() {
        // Animate the SystemUI scrim views out
        mScrimViews.startExitRecentsAnimation();
    }

    @Override
    public void onTaskViewClicked() {
    }

    @Override
    public void onTaskLaunchFailed() {
        // Return to Home
        dismissRecentsToHomeRaw(true);
    }

    @Override
    public void onAllTaskViewsDismissed() {
        mFinishLaunchHomeRunnable.run();
    }

    @Override
    public void onScreenPinningRequest() {
        RecentsTaskLoader loader = RecentsTaskLoader.getInstance();
        SystemServicesProxy ssp = loader.getSystemServicesProxy();
        Recents.startScreenPinning(this, ssp);

        MetricsLogger.count(this, "overview_screen_pinned", 1);
    }

    @Override
    public void runAfterPause(Runnable r) {
        mAfterPauseRunnable = r;
    }

    /****
     * DebugOverlayView.DebugOverlayViewCallbacks
     ****/

    @Override
    public void onPrimarySeekBarChanged(float progress) {
        // Do nothing
    }

    @Override
    public void onSecondarySeekBarChanged(float progress) {
        // Do nothing
    }


    @Override
    public void onClick(View view) {
        if (view == mExpandCollapse) {
            if (mSearchExpanded) collapseSearch();
            else expandSearch();
        } else if (view == mSearchOverlay && mSearchExpanded) {
            collapseSearch();
        }
    }

    private void expandSearch() {
        if (mSearchExpanded) return;
        mSearchExpanded = true;
        mSearchResultView.setEmptyView(findViewById(R.id.grid_empty_view));
        mSearchResultView.setVisibility(View.GONE);
        mSearchOverlay.setVisibility(View.VISIBLE);
        mExpandCollapse.setImageResource(R.drawable.ic_expand_less);
        View searchLayout = findViewById(R.id.search_layout);
        int height = getResources().getDimensionPixelSize(R.dimen.recents_search_bar_space_height);
        Display display = getWindowManager().getDefaultDisplay();
        Point size = new Point();
        display.getSize(size);
        float targetHeight = size.y * 0.75f;
        ExpandAnimation expandAnim = new ExpandAnimation(searchLayout, height, targetHeight);
        expandAnim.setDuration(300);
        expandAnim.setAnimationListener(new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {

            }

            @Override
            public void onAnimationEnd(Animation animation) {
                mSearchResultView.setVisibility(View.VISIBLE);
                AlphaAnimation alphaAnim = new AlphaAnimation(0f, 1f);
                alphaAnim.setDuration(300);
                mSearchResultView.startAnimation(alphaAnim);
            }

            @Override
            public void onAnimationRepeat(Animation animation) {

            }
        });
        AlphaAnimation alphaAnimation = new AlphaAnimation(0f, 1f);
        alphaAnimation.setDuration(300);
        RotateAnimation rotateAnim = new RotateAnimation(180, 0, Animation.RELATIVE_TO_SELF, 0.5f,
                Animation.RELATIVE_TO_SELF, 0.5f);
        rotateAnim.setDuration(300);
        searchLayout.startAnimation(expandAnim);
        mExpandCollapse.startAnimation(rotateAnim);
        mSearchOverlay.startAnimation(alphaAnimation);
    }

    private void collapseSearch() {
        if (!mSearchExpanded) return;
        mSearchExpanded = false;
        findViewById(R.id.grid_empty_view).setVisibility(View.GONE);
        AlphaAnimation alphaAnim = new AlphaAnimation(1f, 0f);
        alphaAnim.setDuration(300);
        alphaAnim.setAnimationListener(new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {

            }

            @Override
            public void onAnimationEnd(Animation animation) {
                mExpandCollapse.setImageResource(R.drawable.ic_qs_tile_expand);
                mSearchResultView.setVisibility(View.GONE);
                View searchLayout = findViewById(R.id.search_layout);
                float height = mSearchOverlay.getHeight() * 0.75f;
                int targetHeight = getResources()
                        .getDimensionPixelSize(R.dimen.recents_search_bar_space_height);
                ExpandAnimation expandAnim = new ExpandAnimation(searchLayout, height, targetHeight);
                expandAnim.setDuration(300);
                RotateAnimation rotateAnim = new RotateAnimation(180, 0, Animation.RELATIVE_TO_SELF,
                        0.5f, Animation.RELATIVE_TO_SELF, 0.5f);
                AlphaAnimation alphaAnim = new AlphaAnimation(1f, 0f);
                alphaAnim.setDuration(300);
                new Handler().postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        mSearchOverlay.setVisibility(View.GONE);
                    }
                }, 300);
                rotateAnim.setDuration(300);
                searchLayout.startAnimation(expandAnim);
                mExpandCollapse.startAnimation(rotateAnim);
                mSearchOverlay.startAnimation(alphaAnim);
            }

            @Override
            public void onAnimationRepeat(Animation animation) {

            }
        });
        mSearchResultView.startAnimation(alphaAnim);
    }

    private void updateSearchResults() {
        if (!mSearchExpanded && mSearch.getText().toString().length() > 0) expandSearch();
        if (mSearch.getText().toString().length() > 0) {
            mSearchResults.clear();
            for (SearchItem item : mApplications) {
                if (item.getLabel().toLowerCase().contains(mSearch.getText().toString()
                        .toLowerCase())) {
                    mSearchResults.add(item);
                }
            }
            for (SearchItem item : mSearchableData) {
                if (item.getLabel().toLowerCase().contains(mSearch.getText().toString()
                        .toLowerCase())) {
                    mSearchResults.add(item);
                }
            }
            if (mFilesResults != null) {
                for (SearchItem item : mFilesResults) {
                    if (item.getLabel().toLowerCase().contains(mSearch.getText().toString()
                            .toLowerCase())) {
                        mSearchResults.add(item);
                    }
                }
            }
            SearchGridAdapter adapter = new SearchGridAdapter(RecentsActivity.this, mSearchResults);
            mSearchResultView.setAdapter(adapter);
            findViewById(R.id.dash_search_progress).setVisibility(mApplicationIndexing ||
                    mDataIndexing || mFilesIndexing ? View.VISIBLE : View.GONE);
            mSearchDeleter.setVisibility(mApplicationIndexing || mDataIndexing || mFilesIndexing ?
                    View.INVISIBLE : View.VISIBLE);
        } else {
            SearchGridAdapter adapter = new SearchGridAdapter(RecentsActivity.this, mApplications);
            mSearchResultView.setAdapter(adapter);
            findViewById(R.id.dash_search_progress).setVisibility(mApplicationIndexing ?
                    View.VISIBLE : View.GONE);
            mSearchDeleter.setVisibility(mApplicationIndexing ? View.INVISIBLE : View.VISIBLE);
            mFilesResults = null;
        }
    }

    @Override
    public boolean onDrag(View v, DragEvent event) {
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED:
                mSearchDeleter.setImageResource(R.drawable.ic_delete);
                mSearchDeleter.setVisibility(View.VISIBLE);
                SearchItem dashItem = (SearchItem) event.getLocalState();
                if (dashItem.getType() == SearchItem.TYPE_APPLICATION) {
                    try {
                        ApplicationInfo applicationInfo = getPackageManager()
                                .getApplicationInfo(dashItem.getLaunchInfo(), 0);
                        if ((applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                            mSearchDeleter.setImageResource(R.drawable.ic_info);
                        }
                    } catch (PackageManager.NameNotFoundException e) {
                        Log.e(getClass().getSimpleName(), e.getMessage());
                    }
                }
                break;
            case DragEvent.ACTION_DRAG_ENTERED:
                mSearchDeleter.setVisibility(View.VISIBLE);
                mSearchDeleter.setAlpha(1f);
                mSearchDeleter.setColorFilter(0xFFF44336, PorterDuff.Mode.SRC_IN);
                break;
            case DragEvent.ACTION_DRAG_EXITED:
                mSearchDeleter.setVisibility(View.VISIBLE);
                mSearchDeleter.setAlpha(0.5f);
                mSearchDeleter.setColorFilter(0xFF000000, PorterDuff.Mode.SRC_IN);
                break;
            case DragEvent.ACTION_DROP:
                final SearchItem item = (SearchItem) event.getLocalState();
                if (item.getType() == SearchItem.TYPE_APPLICATION) {
                    try {
                        ApplicationInfo applicationInfo = getPackageManager()
                                .getApplicationInfo(item.getLaunchInfo(), 0);
                        if ((applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                            Intent intent = new Intent(Intent.ACTION_UNINSTALL_PACKAGE);
                            intent.setData(Uri.parse("package:" + item.getLaunchInfo()));
                            startActivity(intent);
                        } else {
                            Intent intent =
                                    new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                            intent.setData(Uri.parse("package:" + item.getLaunchInfo()));
                            startActivity(intent);
                        }
                    } catch (PackageManager.NameNotFoundException e) {
                        Log.e(getClass().getSimpleName(), e.getMessage());
                    }
                } else {
                    AlertDialog.Builder builder = new AlertDialog.Builder(this);
                    builder.setMessage(getString(R.string.confirm_deletion, item.getLabel()))
                            .setPositiveButton(android.R.string.ok,
                                    new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface dialogInterface,
                                                            int i) {
                                            deleteItem(item);
                                            if (!mApplicationIndexing)
                                                new IndexApplicationsTask().execute();
                                            if (!mDataIndexing) new IndexDataTask().execute();
                                            if (mSearch.getText().toString().length() > 0) {
                                                new IndexFilesTask()
                                                        .execute(mSearch.getText().toString());
                                            }
                                            dialogInterface.dismiss();
                                        }
                                    })
                            .setNegativeButton(android.R.string.cancel,
                                    new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface dialogInterface, int i) {
                                            dialogInterface.cancel();
                                        }
                                    }).show();
                }
            case DragEvent.ACTION_DRAG_ENDED:
                mSearchDeleter.setAlpha(0.5f);
                mSearchDeleter.setImageResource(R.drawable.ic_search);
                mSearchDeleter.setColorFilter(0xFF000000, PorterDuff.Mode.SRC_IN);
                if (findViewById(R.id.dash_search_progress).getVisibility() == View.VISIBLE) {
                    mSearchDeleter.setVisibility(View.INVISIBLE);
                }
                break;
        }
        return true;
    }

    private void deleteItem (SearchItem item){
        try {
            switch (item.getType()) {
                case SearchItem.TYPE_FILE:
                    if (item.getMoreLaunchInfo().equals("file")) {
                        File file = new File(item.getLaunchInfo());
                        boolean success = file.delete();
                        if (!success) Toast.makeText(RecentsActivity.this, R.string.failed,
                                Toast.LENGTH_SHORT).show();
                    } else {
                        try {
                            deleteDirectory(new File(item.getLaunchInfo()));
                        } catch (IOException e) {
                            Toast.makeText(RecentsActivity.this, R.string.failed,
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                    break;
                case SearchItem.TYPE_CALENDAR:
                    Uri uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI,
                            Long.parseLong(item.getLaunchInfo()));
                    getContentResolver().delete(uri, null, null);
                    break;
                case SearchItem.TYPE_CONTACT:
                    uri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI,
                            Long.parseLong(item.getLaunchInfo()));
                    getContentResolver().delete(uri, null, null);
                    break;
                case SearchItem.TYPE_MUSIC:
                    uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                            Long.parseLong(item.getLaunchInfo()));
                    getContentResolver().delete(uri, null, null);
                    File file = new File(item.getMoreLaunchInfo());
                    boolean success = file.delete();
                    if (!success) Toast.makeText(RecentsActivity.this,
                            R.string.failed, Toast.LENGTH_SHORT).show();
                    break;
            }
        } catch (SecurityException e) {
            requestPermissions(new String[]{
                            android.Manifest.permission.WRITE_CONTACTS,
                            android.Manifest.permission.WRITE_CALENDAR,
                            android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 0);
        }
    }

    private void deleteDirectory(File f) throws IOException {
        if (f.isDirectory()) {
            for (File c : f.listFiles())
                deleteDirectory(c);
        }
        if (!f.delete()) throw new FileNotFoundException("Failed to delete file: " + f);
    }


    private class IndexApplicationsTask extends AsyncTask<Object, Object, ArrayList<SearchItem>> {

        @Override
        protected ArrayList<SearchItem> doInBackground(Object... params) {
            mApplicationIndexing = true;
            ArrayList<SearchItem> tmp = new ArrayList<>();
            PackageManager pm = getPackageManager();

            Intent i = new Intent(Intent.ACTION_MAIN, null);
            i.addCategory(Intent.CATEGORY_LAUNCHER);

            List<ResolveInfo> availableActivities = pm.queryIntentActivities(i, 0);
            for (ResolveInfo ri : availableActivities) {
                SearchItem app = new SearchItem(SearchItem.TYPE_APPLICATION, ri.loadLabel(pm)
                        .toString(),
                        getString(R.string.type_application), ri.activityInfo.packageName,
                        ri.activityInfo.name);
                tmp.add(app);
            }
            Collections.sort(tmp, new SearchItem.Comparator());
            return tmp;
        }

        @Override
        protected void onPostExecute(ArrayList<SearchItem> list) {
            super.onPostExecute(list);
            if (mApplications == null) mApplications = new ArrayList<>();
            mApplications.clear();
            mApplications.addAll(list);
            mApplicationIndexing = false;
            if (mSearchResultView == null) return;
            updateSearchResults();
        }
    }

    private class IndexFilesTask extends AsyncTask<String, Object, ArrayList<SearchItem>> {

        String search;

        @Override
        protected ArrayList<SearchItem> doInBackground(String... params) {
            mFilesIndexing = true;
            search = params[0];
            ArrayList<SearchItem> tmp = new ArrayList<>();
            boolean searchFiles = Settings.System.getInt(getContentResolver(),
                    "recents_search_files", 1) == 1;
            if (params[0].equals("") || !searchFiles) return tmp;
            try {
                tmp.addAll(getListFiles(Environment.getStorageDirectory()));
                tmp.addAll(getListFiles(Environment.getExternalStorageDirectory()));
                Collections.sort(tmp, new SearchItem.Comparator());
            } catch (SecurityException e) {
                requestPermissions(new String[]{android.Manifest.permission.WRITE_CONTACTS,
                        android.Manifest.permission.WRITE_CALENDAR,
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 0);
            }
            return tmp;
        }

        private ArrayList<SearchItem> getListFiles(File parentDir) {
            ArrayList<SearchItem> inFiles = new ArrayList<>();
            if (!mSearch.getText().toString().equals(search)) return inFiles;
            File[] files = parentDir.listFiles();
            if (files == null || files.length == 0) return inFiles;
            for (File file : files) {
                if (file.isDirectory()) {
                    if (file.getName().toLowerCase().contains(search.toLowerCase())) {
                        inFiles.add(new SearchItem(SearchItem.TYPE_FILE, file.getName(),
                                getString(R.string.type_folder), file.getAbsolutePath(), "folder"));
                    }
                    inFiles.addAll(getListFiles(file));
                } else {
                    if (file.getName().toLowerCase().contains(search.toLowerCase())) {
                        inFiles.add(new SearchItem(SearchItem.TYPE_FILE, file.getName(),
                                getString(R.string.type_file), file.getAbsolutePath(), "file"));
                    }
                }
            }
            return inFiles;
        }

        @Override
        protected void onPostExecute(ArrayList<SearchItem> list) {
            super.onPostExecute(list);
            if (!mSearch.getText().toString().equals(search)) return;
            if (mFilesResults == null) mFilesResults = new ArrayList<>();
            mFilesResults.clear();
            mFilesResults.addAll(list);
            if (mSearchResultView == null) return;
            mFilesIndexing = false;
            updateSearchResults();
        }
    }

    private class IndexDataTask extends AsyncTask<Object, Object, ArrayList<SearchItem>> {

        @Override
        protected ArrayList<SearchItem> doInBackground(Object... params) {
            mDataIndexing = true;
            ArrayList<SearchItem> tmp = new ArrayList<>();
            try {
                int id;
                boolean searchContacts = Settings.System.getInt(getContentResolver(),
                        "recents_search_contacts", 1) == 1;
                if (searchContacts) {
                    Uri contactsUri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI;
                    String[] contactsProjection = {
                            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME};
                    Cursor contactsCursor = getContentResolver().query(contactsUri,
                            contactsProjection, null, null, null);
                    int name = contactsCursor.getColumnIndex(
                            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME);
                    id = contactsCursor
                            .getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID);
                    while (contactsCursor.moveToNext()) {
                        SearchItem item = new SearchItem(SearchItem.TYPE_CONTACT,
                                contactsCursor.getString(name), getString(R.string.type_contact),
                                contactsCursor.getString(id), null);
                        tmp.add(item);
                    }
                    contactsCursor.close();
                }
                boolean searchMusic = Settings.System.getInt(getContentResolver(),
                        "recents_search_music", 1) == 1;
                if (searchMusic) {
                    Uri musicUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                    String[] trackProjection = {MediaStore.Audio.Media._ID,
                            MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.DATA};
                    Cursor musicCursor = getContentResolver().query(musicUri, trackProjection,
                            null, null, null);
                    id = musicCursor.getColumnIndex(MediaStore.Audio.Media._ID);
                    int title = musicCursor.getColumnIndex(MediaStore.Audio.Media.TITLE);
                    int path = musicCursor.getColumnIndex(MediaStore.Audio.Media.DATA);
                    while (musicCursor.moveToNext()) {
                        SearchItem item = new SearchItem(SearchItem.TYPE_MUSIC,
                                musicCursor.getString(title), getString(R.string.type_track),
                                musicCursor.getString(id), musicCursor.getString(path));
                        tmp.add(item);
                    }
                    musicCursor.close();
                }
                boolean searchCalendar = Settings.System.getInt(getContentResolver(),
                        "recents_search_calendar", 1) == 1;
                if (searchCalendar) {
                    Uri.Builder builder = CalendarContract.Instances.CONTENT_URI.buildUpon();
                    ContentUris.appendId(builder, System.currentTimeMillis());
                    ContentUris.appendId(builder, System.currentTimeMillis() + 31536000000L);
                    String[] calendarProjection = {CalendarContract.Instances.EVENT_ID,
                            CalendarContract.Instances.TITLE};
                    Cursor calendarCursor = getContentResolver().query(builder.build(),
                            calendarProjection, null, null, null);
                    while (calendarCursor.moveToNext()) {
                        String eventTitle = calendarCursor.getString(1);
                        SearchItem item = new SearchItem(SearchItem.TYPE_CALENDAR, eventTitle,
                                getString(R.string.type_event), calendarCursor.getString(0), null);
                        tmp.add(item);
                    }
                    calendarCursor.close();
                }
            } catch (SecurityException e) {
                requestPermissions(new String[]{android.Manifest.permission.READ_CONTACTS,
                        android.Manifest.permission.READ_CALENDAR,
                        android.Manifest.permission.READ_EXTERNAL_STORAGE}, 0);
                return tmp;
            }
            return tmp;
        }

        @Override
        protected void onPostExecute(ArrayList<SearchItem> list) {
            super.onPostExecute(list);
            if (mSearchableData == null) mSearchableData = new ArrayList<>();
            mSearchableData.clear();
            mSearchableData.addAll(list);
            mDataIndexing = false;
            if (mSearchResultView == null) return;
            updateSearchResults();
        }
    }
}
