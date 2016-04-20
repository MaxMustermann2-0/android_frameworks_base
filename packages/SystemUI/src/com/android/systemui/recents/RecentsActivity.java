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
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ContentUris;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageDataObserver;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.PorterDuff;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.CalendarContract;
import android.provider.ContactsContract;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.DragEvent;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.ScaleAnimation;
import android.view.animation.TranslateYAnimation;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
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
import com.android.systemui.recents.views.RecentTaskView;
import com.android.systemui.recents.views.RecentTasksView;
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
public class RecentsActivity extends Activity implements View.OnDragListener,
        RecentTasksView.OnTaskLongClickListener, View.OnClickListener {

    RecentsConfiguration mConfig;
    long mLastTabKeyEventTime;

    // Runnables to finish the Recents activity
    FinishRecentsRunnable mFinishLaunchHomeRunnable;

    // Runnable to be executed after we paused ourselves
    Runnable mAfterPauseRunnable;
    private ArrayList<DashItem> mApplications;
    private ArrayList<DashItem> mSearchableData;
    private ArrayList<DashItem> mSearchResults;
    private ArrayList<DashItem> mFilesResults;
    private GridView mDashGridView;
    private ImageView mDashDeleter;
    private RecentTasksView mTasksView;
    private EditText mSearch;
    private LinearLayout mRecentsActions;
    private boolean mApplicationIndexing = false;
    private boolean mDataIndexing = false;
    private boolean mFilesIndexing = false;
    private boolean mRecentsActionsOpen = false;
    private RecentTaskView mLongClickedTaskView;
    private View mActionMultiWindow;
    private View mActionWipeData;
    private View mActionUninstall;
    private View mActionKill;
    private View mActionAppInfo;
    private View mActionPin;
    private View mRecentsActionsOverlays;


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
            } else if (action.equals(Intent.ACTION_PACKAGE_INSTALL) ||
                    action.equals(Intent.ACTION_PACKAGE_CHANGED) ||
                    action.equals(Intent.ACTION_PACKAGE_REMOVED)) {
                if (!mApplicationIndexing) new IndexApplicationsTask().execute();
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
    public void updateRecentsTasks() {
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
        mTasksView.setTasks(stacks);
        mTasksView.setOnTaskLongClickListener(this);

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
            // If we launched from Home, then return to Home
            if (mConfig.launchedFromHome) {
                dismissRecentsToHomeRaw(true);
                return true;
            }
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
        inflateDebugOverlay();
        mDashGridView = (GridView) findViewById(R.id.dash_content_grid);
        mDashDeleter = (ImageView) findViewById(R.id.dash_deleter);
        mRecentsActions  = (LinearLayout) findViewById(R.id.recents_actions_layout);
        mRecentsActionsOverlays  = findViewById(R.id.recents_actions_overlay);
        mRecentsActionsOverlays.setOnClickListener(this);
        mActionMultiWindow  = findViewById(R.id.recents_action_multiwindow);
        mActionMultiWindow.setOnClickListener(this);
        mActionKill  = findViewById(R.id.recents_action_kill);
        mActionKill.setOnClickListener(this);
        mActionUninstall  = findViewById(R.id.recents_action_uninstall);
        mActionUninstall.setOnClickListener(this);
        mActionWipeData  = findViewById(R.id.recents_action_wipe);
        mActionWipeData.setOnClickListener(this);
        mActionAppInfo  = findViewById(R.id.recents_action_info);
        mActionAppInfo.setOnClickListener(this);
        mActionPin  = findViewById(R.id.recents_action_pin);
        mActionPin.setOnClickListener(this);
        mTasksView = (RecentTasksView) findViewById(R.id.recent_tasks);
        mDashDeleter.setOnDragListener(this);

        // Register the broadcast receiver to handle messages when the screen is turned off
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(mSystemBroadcastReceiver, filter);

        mSearchableData = new ArrayList<>();
        mApplications = new ArrayList<>();
        mSearchResults = new ArrayList<>();
        mFilesResults = new ArrayList<>();
        new IndexApplicationsTask().execute();
        new IndexDataTask().execute();
        mSearch = (EditText) findViewById(R.id.dash_search);
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
    }

    private void updateSearchResults() {
        if (mSearch.getText().toString().length() > 0) {
            mSearchResults.clear();
            for (DashItem item : mApplications) {
                if (item.getLabel().toLowerCase().contains(mSearch.getText().toString()
                        .toLowerCase())) {
                    mSearchResults.add(item);
                }
            }
            for (DashItem item : mSearchableData) {
                if (item.getLabel().toLowerCase().contains(mSearch.getText().toString()
                        .toLowerCase())) {
                    mSearchResults.add(item);
                }
            }
            if (mFilesResults != null){
                for (DashItem item : mFilesResults) {
                    if (item.getLabel().toLowerCase().contains(mSearch.getText().toString()
                        .toLowerCase())) {
                        mSearchResults.add(item);
                    }
                }
            }
            mDashGridView.setNumColumns(1);
            mDashGridView.setAdapter(new DashListAdapter(RecentsActivity.this, mSearchResults));
            findViewById(R.id.dash_search_progress).setVisibility(mApplicationIndexing ||
                    mDataIndexing || mFilesIndexing ? View.VISIBLE : View.GONE);
        } else {
            mDashGridView.setNumColumns(4);
            DashGridAdapter adapter = new DashGridAdapter(RecentsActivity.this, mApplications);
            mDashGridView.setAdapter(adapter);
            findViewById(R.id.dash_search_progress).setVisibility(mApplicationIndexing ?
                    View.VISIBLE : View.GONE);
            mFilesResults = null;
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (mRecentsActions != null) mRecentsActions.setVisibility(View.GONE);
        mRecentsActionsOpen = false;
        MetricsLogger.visible(this, MetricsLogger.OVERVIEW_ACTIVITY);
        RecentsTaskLoader loader = RecentsTaskLoader.getInstance();
        SystemServicesProxy ssp = loader.getSystemServicesProxy();
        Recents.notifyVisibilityChanged(this, ssp, true);

        // Register the broadcast receiver to handle messages from our service
        IntentFilter filter = new IntentFilter();
        filter.addAction(Recents.ACTION_HIDE_RECENTS_ACTIVITY);
        filter.addAction(Recents.ACTION_TOGGLE_RECENTS_ACTIVITY);
        filter.addAction(Recents.ACTION_START_ENTER_ANIMATION);
        filter.addAction(Intent.ACTION_PACKAGE_INSTALL);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        registerReceiver(mServiceBroadcastReceiver, filter);

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
        }
    }

    @Override
    protected void onResume() {
        if (mTasksView != null) mTasksView.scrollTo(0, 0);
        if (mConfig.searchBarEnabled && mConfig.launchedFromHome) {
            overridePendingTransition(0, 0);
        }
        super.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mAfterPauseRunnable != null) {
            mAfterPauseRunnable = null;
        }
        mFilesResults = null;
    }

    @Override
    protected void onStop() {
        super.onStop();
        MetricsLogger.hidden(this, MetricsLogger.OVERVIEW_ACTIVITY);
        RecentsTaskLoader loader = RecentsTaskLoader.getInstance();
        SystemServicesProxy ssp = loader.getSystemServicesProxy();
        Recents.notifyVisibilityChanged(this, ssp, false);

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
    }

    @Override
    public boolean onDrag(View v, DragEvent event) {
        final ImageView deleterBg = (ImageView) findViewById(R.id.dash_deleter_bg);
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED:
                mDashDeleter.setImageResource(R.drawable.ic_delete);
                DashItem dashItem = (DashItem) event.getLocalState();
                deleterBg.setColorFilter(0xFFF44336,
                        PorterDuff.Mode.SRC_IN);
                if (dashItem.getType() == DashItem.TYPE_APPLICATION) {
                    try {
                        ApplicationInfo applicationInfo = getPackageManager()
                                .getApplicationInfo(dashItem.getLaunchInfo(), 0);
                        if ((applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                            mDashDeleter.setImageResource(R.drawable.ic_info);
                            deleterBg.setColorFilter(0xFF03A9F4,
                                    PorterDuff.Mode.SRC_IN);
                        }
                    } catch (PackageManager.NameNotFoundException e) {
                        Log.e(getClass().getSimpleName(), e.getMessage());
                    }
                }
                mDashDeleter.setAlpha(1f);
                ScaleAnimation enterAnim = new ScaleAnimation(0f, 1f, 0f, 1f,
                        Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f);
                enterAnim.setDuration(200);
                mDashDeleter.startAnimation(enterAnim);
                break;
            case DragEvent.ACTION_DRAG_ENTERED:
                deleterBg.setAlpha(1f);
                ScaleAnimation bgEnterAnim = new ScaleAnimation(0f, 1f, 0f, 1f,
                        Animation.RELATIVE_TO_SELF, 1f, Animation.RELATIVE_TO_SELF, 0f);
                bgEnterAnim.setDuration(200);
                deleterBg.startAnimation(bgEnterAnim);
                break;
            case DragEvent.ACTION_DRAG_EXITED:
                ScaleAnimation bgExitAnim = new ScaleAnimation(1f, 0f, 1f, 0f,
                        Animation.RELATIVE_TO_SELF, 1f, Animation.RELATIVE_TO_SELF, 0f);
                bgExitAnim.setDuration(200);
                bgExitAnim.setAnimationListener(new Animation.AnimationListener() {
                    @Override
                    public void onAnimationStart(Animation animation) {

                    }

                    @Override
                    public void onAnimationEnd(Animation animation) {
                        deleterBg.setAlpha(0f);
                    }

                    @Override
                    public void onAnimationRepeat(Animation animation) {

                    }
                });
                deleterBg.startAnimation(bgExitAnim);
                break;
            case DragEvent.ACTION_DROP:
                final DashItem item = (DashItem) event.getLocalState();
                if (item.getType() == DashItem.TYPE_APPLICATION) {
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
                                public void onClick(DialogInterface dialogInterface, int i) {
                                    try {
                                        switch (item.getType()) {
                                            case DashItem.TYPE_FILE:
                                                if (item.getMoreLaunchInfo().equals("file")) {
                                                    File file = new File(item.getLaunchInfo());
                                                    boolean success = file.delete();
                                                    if (!success)
                                                        Toast.makeText(RecentsActivity.this,
                                                                R.string.failed, Toast.LENGTH_SHORT)
                                                                .show();
                                                } else {
                                                    try {
                                                        deleteDirectory(new File(item.getLaunchInfo()));
                                                    } catch (IOException e) {
                                                        Toast.makeText(RecentsActivity.this,
                                                                R.string.failed, Toast.LENGTH_SHORT)
                                                                .show();
                                                    }
                                                }
                                                break;
                                            case DashItem.TYPE_CALENDAR:
                                                Uri uri = ContentUris.withAppendedId(
                                                        CalendarContract.Events.CONTENT_URI,
                                                        Long.parseLong(item.getLaunchInfo()));
                                                getContentResolver().delete(uri, null, null);
                                                break;
                                            case DashItem.TYPE_CONTACT:
                                                uri = ContentUris.withAppendedId(
                                                        ContactsContract.Contacts.CONTENT_URI,
                                                        Long.parseLong(item.getLaunchInfo()));
                                                getContentResolver().delete(uri, null, null);
                                                break;
                                            case DashItem.TYPE_MUSIC:
                                                uri = ContentUris.withAppendedId(
                                                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
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
                                                android.Manifest.permission.WRITE_EXTERNAL_STORAGE},
                                                0);
                                    }
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
                ScaleAnimation bgExitAnim1 = new ScaleAnimation(1f, 0f, 1f, 0f,
                        Animation.RELATIVE_TO_SELF, 1f, Animation.RELATIVE_TO_SELF, 0f);
                bgExitAnim1.setDuration(200);
                ScaleAnimation exitAnim = new ScaleAnimation(1f, 0f, 1f, 0f,
                        Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f);
                exitAnim.setDuration(200);
                exitAnim.setAnimationListener(new Animation.AnimationListener() {
                    @Override
                    public void onAnimationStart(Animation animation) {

                    }

                    @Override
                    public void onAnimationEnd(Animation animation) {
                        mDashDeleter.setAlpha(0f);
                        deleterBg.setAlpha(0f);
                    }

                    @Override
                    public void onAnimationRepeat(Animation animation) {

                    }
                });
                mDashDeleter.startAnimation(exitAnim);
                deleterBg.startAnimation(bgExitAnim1);
                break;
        }
        return true;
    }

    void deleteDirectory(File f) throws IOException {
        if (f.isDirectory()) {
            for (File c : f.listFiles())
                deleteDirectory(c);
        }
        if (!f.delete()) throw new FileNotFoundException("Failed to delete file: " + f);
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
                    mLastTabKeyEventTime = SystemClock.elapsedRealtime();
                }
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_UP: {
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_DOWN: {
                return true;
            }
            case KeyEvent.KEYCODE_DEL:
            case KeyEvent.KEYCODE_FORWARD_DEL: {
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
    }

    @Override
    public void onBackPressed() {
        if(mRecentsActionsOpen){
            hideRecentsActions();
            return;
        }
        // Test mode where back does not do anything
        if (mConfig.debugModeEnabled) return;
        dismissRecentsToHomeRaw(true);
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
            } else {
                // Enable the debug mode
                Prefs.putBoolean(this, Prefs.Key.DEBUG_MODE_ENABLED, true);
                mConfig.debugModeEnabled = true;
                inflateDebugOverlay();
            }
            Toast.makeText(this, "Debug mode (" + Constants.Values.App.DebugModeVersion + ") " +
                            (mConfig.debugModeEnabled ? "Enabled" : "Disabled") + ", please restart Recents now",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void showRecentsActions() {
        final Task task = mLongClickedTaskView.getTask();
        final String packageName = task.key.baseIntent.getComponent().getPackageName();
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo appInfo = pm.getApplicationInfo(packageName, 0);
            DevicePolicyManager dpm = (DevicePolicyManager)
                    getSystemService(Context.DEVICE_POLICY_SERVICE);

            boolean hasActiveAdmins = dpm.packageHasActiveAdmins(packageName);
            boolean isClearable = (appInfo.flags &
                    (ApplicationInfo.FLAG_ALLOW_CLEAR_USER_DATA | ApplicationInfo.FLAG_SYSTEM)) !=
                    ApplicationInfo.FLAG_SYSTEM;
            if (!isClearable || hasActiveAdmins) {
                mActionUninstall.setVisibility(View.GONE);
                mActionWipeData.setVisibility(View.GONE);
            }
            if((appInfo.flags & ApplicationInfo.FLAG_SYSTEM) == ApplicationInfo.FLAG_SYSTEM) {
                mActionUninstall.setVisibility(View.GONE);
            }
        } catch (PackageManager.NameNotFoundException ex) {
        }
        mRecentsActions.setVisibility(View.VISIBLE);
        mRecentsActionsOverlays.setVisibility(View.VISIBLE);
        AlphaAnimation alphaAnim = new AlphaAnimation(0f, 1f);
        TranslateYAnimation translateAnim = new TranslateYAnimation(Animation.RELATIVE_TO_SELF, 1f,
                Animation.RELATIVE_TO_SELF, 0f);
        alphaAnim.setDuration(100);
        translateAnim.setDuration(100);
        mRecentsActionsOverlays.startAnimation(alphaAnim);
        mRecentsActions.startAnimation(translateAnim);
        mRecentsActionsOpen = true;
    }

    private void hideRecentsActions(){
        AlphaAnimation alphaAnim = new AlphaAnimation(1f, 0f);
        TranslateYAnimation translateAnim = new TranslateYAnimation(Animation.RELATIVE_TO_SELF, 0f,
                Animation.RELATIVE_TO_SELF, 1f);
        alphaAnim.setDuration(100);
        translateAnim.setDuration(100);
        translateAnim.setAnimationListener(new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {

            }

            @Override
            public void onAnimationEnd(Animation animation) {
                mActionUninstall.setVisibility(View.VISIBLE);
                mActionWipeData.setVisibility(View.VISIBLE);
                mRecentsActions.setVisibility(View.GONE);
                mRecentsActionsOverlays.setVisibility(View.GONE);
            }

            @Override
            public void onAnimationRepeat(Animation animation) {

            }
        });
        mRecentsActions.startAnimation(translateAnim);
        mRecentsActionsOverlays.startAnimation(alphaAnim);
        mRecentsActionsOpen = false;
        mLongClickedTaskView = null;
    }

    @Override
    public void onClick(View view) {
        if (view == mRecentsActionsOverlays) {
            hideRecentsActions();
        }
        if (view == mActionMultiWindow) {
            new RecentsResizeTaskDialog(getFragmentManager(), this)
                    .showResizeTaskDialog(mLongClickedTaskView.getTask(), mTasksView);
        }
        if (view == mActionWipeData) {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            am.clearApplicationUserData(mLongClickedTaskView.getTask().key.baseIntent
                    .getComponent().getPackageName(), new IPackageDataObserver.Stub() {
                @Override
                public void onRemoveCompleted(String packageName, boolean succeeded) {}
            });
            mTasksView.removeTaskView(mLongClickedTaskView);
            hideRecentsActions();
        }
        if (view == mActionKill) {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            am.forceStopPackage(mLongClickedTaskView.getTask().key.baseIntent.getComponent()
                    .getPackageName());
            mTasksView.removeTaskView(mLongClickedTaskView);
            hideRecentsActions();
        }
        if (view == mActionUninstall) {
            Uri packageUri = Uri.parse("package:" + mLongClickedTaskView.getTask().key.baseIntent
                    .getComponent().getPackageName());
            Intent uninstallIntent = new Intent(Intent.ACTION_UNINSTALL_PACKAGE, packageUri);
            uninstallIntent.putExtra(Intent.EXTRA_UNINSTALL_ALL_USERS, true);
            startActivity(uninstallIntent);
            mTasksView.removeTaskView(mLongClickedTaskView);
            hideRecentsActions();
        }
        if (view == mActionAppInfo) {
            Intent intent =
                    new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + mLongClickedTaskView.getTask().key.baseIntent
                    .getComponent().getPackageName()));
            startActivity(intent);
            hideRecentsActions();
        }
        if (view == mActionPin) {
            mTasksView.startScreenPinning(mLongClickedTaskView);
            hideRecentsActions();
        }
    }

    @Override
    public boolean onTaskLongClick(RecentTaskView taskView) {
        if(mRecentsActionsOpen) return false;
        mLongClickedTaskView = taskView;
        showRecentsActions();
        return true;
    }

    private class IndexApplicationsTask extends AsyncTask<Object, Object, ArrayList<DashItem>> {

        @Override
        protected ArrayList<DashItem> doInBackground(Object... params) {
            mApplicationIndexing = true;
            ArrayList<DashItem> tmp = new ArrayList<>();
            PackageManager pm = getPackageManager();

            Intent i = new Intent(Intent.ACTION_MAIN, null);
            i.addCategory(Intent.CATEGORY_LAUNCHER);

            List<ResolveInfo> availableActivities = pm.queryIntentActivities(i, 0);
            for (ResolveInfo ri : availableActivities) {
                DashItem app = new DashItem(DashItem.TYPE_APPLICATION, ri.loadLabel(pm).toString(),
                        getString(R.string.type_application), ri.activityInfo.packageName,
                        ri.activityInfo.name);
                tmp.add(app);
            }
            Collections.sort(tmp, new DashItem.Comparator());
            return tmp;
        }

        @Override
        protected void onPostExecute(ArrayList<DashItem> list) {
            super.onPostExecute(list);
            if (mApplications == null) mApplications = new ArrayList<>();
            mApplications.clear();
            mApplications.addAll(list);
            mApplicationIndexing = false;
            if (mDashGridView == null) return;
            updateSearchResults();
        }
    }

    private class IndexFilesTask extends AsyncTask<String, Object, ArrayList<DashItem>> {

        String search;

        @Override
        protected ArrayList<DashItem> doInBackground(String... params) {
            mFilesIndexing = true;
            search = params[0];
            ArrayList<DashItem> tmp = new ArrayList<>();
            if(params[0].equals("")) return tmp;
            try {
                tmp.addAll(getListFiles(Environment.getStorageDirectory()));
                Collections.sort(tmp, new DashItem.Comparator());
            } catch (SecurityException e) {
                requestPermissions(new String[]{android.Manifest.permission.WRITE_CONTACTS,
                                android.Manifest.permission.WRITE_CALENDAR,
                                android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 0);
            }
            return tmp;
        }

        private ArrayList<DashItem> getListFiles(File parentDir) {
            ArrayList<DashItem> inFiles = new ArrayList<>();
            if (!mSearch.getText().toString().equals(search)) return inFiles;
            File[] files = parentDir.listFiles();
            if (files == null || files.length == 0) return inFiles;
            for (File file : files) {
                if (file.isDirectory()) {
                    if(file.getName().toLowerCase().contains(search.toLowerCase())) {
                        inFiles.add(new DashItem(DashItem.TYPE_FILE, file.getName(),
                                getString(R.string.type_folder) + ", " + file.getAbsolutePath(),
                                file.getAbsolutePath(), "folder"));
                    }
                    inFiles.addAll(getListFiles(file));
                } else {
                    if(file.getName().toLowerCase().contains(search.toLowerCase())) {
                        inFiles.add(new DashItem(DashItem.TYPE_FILE, file.getName(),
                                getString(R.string.type_file) + ", " + file.getAbsolutePath(),
                                file.getAbsolutePath(), "file"));
                    }
                }
            }
            return inFiles;
        }

        @Override
        protected void onPostExecute(ArrayList<DashItem> list) {
            super.onPostExecute(list);
            if (!mSearch.getText().toString().equals(search)) return;
            if (mFilesResults == null) mFilesResults = new ArrayList<>();
            mFilesResults.clear();
            mFilesResults.addAll(list);
            if (mDashGridView == null) return;
            mFilesIndexing = false;
            updateSearchResults();
        }
    }

    private class IndexDataTask extends AsyncTask<Object, Object, ArrayList<DashItem>> {

        @Override
        protected ArrayList<DashItem> doInBackground(Object... params) {
            mDataIndexing = true;
            ArrayList<DashItem> tmp = new ArrayList<>();
            try {
                Uri contactsUri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI;
                String[] contactsProjection = {ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER};
                Cursor contactsCursor = getContentResolver().query(contactsUri, contactsProjection,
                        null, null, null);
                int name = contactsCursor.getColumnIndex(
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME);
                int id = contactsCursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID);
                int phone = contactsCursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER);
                while (contactsCursor.moveToNext()) {
                    DashItem item = new DashItem(DashItem.TYPE_CONTACT, contactsCursor.getString(name),
                            getString(R.string.type_contact) + ", " + contactsCursor.getString(phone),
                            contactsCursor.getString(id), null);
                    tmp.add(item);
                }
                contactsCursor.close();
                Uri musicUri = MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI;
                String[] albumProjection = {MediaStore.Audio.Albums._ID, MediaStore.Audio.Albums.ALBUM,
                        MediaStore.Audio.Albums.ARTIST, MediaStore.Audio.Albums.ALBUM_ART};
                Cursor musicCursor = getContentResolver().query(musicUri, albumProjection,
                        null, null, null);
                id = musicCursor.getColumnIndex(MediaStore.Audio.Albums._ID);
                musicCursor.close();
                musicUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                String[] trackProjection = {MediaStore.Audio.Media._ID, MediaStore.Audio.Media.ALBUM,
                        MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.TITLE,
                        MediaStore.Audio.Media.DATA};
                musicCursor = getContentResolver().query(musicUri, trackProjection,
                        null, null, null);
                id = musicCursor.getColumnIndex(MediaStore.Audio.Media._ID);
                int title = musicCursor.getColumnIndex(MediaStore.Audio.Media.TITLE);
                int album = musicCursor.getColumnIndex(MediaStore.Audio.Media.ALBUM);
                int artist = musicCursor.getColumnIndex(MediaStore.Audio.Media.ARTIST);
                int path = musicCursor.getColumnIndex(MediaStore.Audio.Media.DATA);
                while (musicCursor.moveToNext()) {
                    DashItem item = new DashItem(DashItem.TYPE_MUSIC, musicCursor.getString(title),
                            getString(R.string.type_track) + ", " + musicCursor.getString(artist) +
                                    ", " + musicCursor.getString(album),
                            musicCursor.getString(id), musicCursor.getString(path));
                    tmp.add(item);
                }
                musicCursor.close();
                Uri.Builder builder = CalendarContract.Instances.CONTENT_URI.buildUpon();
                ContentUris.appendId(builder, System.currentTimeMillis());
                ContentUris.appendId(builder, System.currentTimeMillis() + 31536000000L);
                String[] calendarProjection = {CalendarContract.Instances.EVENT_ID,
                        CalendarContract.Instances.BEGIN, CalendarContract.Instances.TITLE};
                Cursor calendarCursor = getContentResolver().query(builder.build(),
                        calendarProjection, null, null, null);
                while (calendarCursor.moveToNext()) {
                    String eventTitle = calendarCursor.getString(2);
                    long begin = calendarCursor.getLong(1);
                    Calendar calendar = Calendar.getInstance();
                    calendar.setTimeInMillis(begin);
                    DateFormat df = DateFormat.getDateTimeInstance(DateFormat.SHORT,
                            DateFormat.SHORT);
                    DashItem item = new DashItem(DashItem.TYPE_CALENDAR, eventTitle,
                            getString(R.string.type_event) + ", " + df.format(calendar.getTime()),
                            calendarCursor.getString(0), null);
                    tmp.add(item);
                }
                calendarCursor.close();
            } catch (SecurityException e) {
                requestPermissions(new String[]{android.Manifest.permission.READ_CONTACTS,
                                android.Manifest.permission.READ_CALENDAR,
                                android.Manifest.permission.READ_EXTERNAL_STORAGE}, 0);
                return tmp;
            }
            return tmp;
        }

        @Override
        protected void onPostExecute(ArrayList<DashItem> list) {
            super.onPostExecute(list);
            if (mSearchableData == null) mSearchableData = new ArrayList<>();
            mSearchableData.clear();
            mSearchableData.addAll(list);
            mDataIndexing = false;
            if (mDashGridView == null) return;
            updateSearchResults();
        }
    }
}
