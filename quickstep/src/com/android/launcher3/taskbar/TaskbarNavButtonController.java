/*
 * Copyright 2021 The Android Open Source Project
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

package com.android.launcher3.taskbar;

import static android.view.KeyEvent.ACTION_DOWN;
import static android.view.KeyEvent.ACTION_UP;

import static com.android.internal.app.AssistUtils.INVOCATION_TYPE_HOME_BUTTON_LONG_PRESS;
import static com.android.internal.app.AssistUtils.INVOCATION_TYPE_KEY;
import static com.android.systemui.shared.Flags.cueBarAceMigration;
import static com.android.launcher3.logging.StatsLogManager.LauncherEvent.LAUNCHER_TASKBAR_A11Y_BUTTON_LONGPRESS;
import static com.android.launcher3.logging.StatsLogManager.LauncherEvent.LAUNCHER_TASKBAR_A11Y_BUTTON_TAP;
import static com.android.launcher3.logging.StatsLogManager.LauncherEvent.LAUNCHER_TASKBAR_BACK_BUTTON_LONGPRESS;
import static com.android.launcher3.logging.StatsLogManager.LauncherEvent.LAUNCHER_TASKBAR_BACK_BUTTON_TAP;
import static com.android.launcher3.logging.StatsLogManager.LauncherEvent.LAUNCHER_TASKBAR_HOME_BUTTON_LONGPRESS;
import static com.android.launcher3.logging.StatsLogManager.LauncherEvent.LAUNCHER_TASKBAR_HOME_BUTTON_TAP;
import static com.android.launcher3.logging.StatsLogManager.LauncherEvent.LAUNCHER_TASKBAR_IME_SWITCHER_BUTTON_LONGPRESS;
import static com.android.launcher3.logging.StatsLogManager.LauncherEvent.LAUNCHER_TASKBAR_IME_SWITCHER_BUTTON_TAP;
import static com.android.launcher3.logging.StatsLogManager.LauncherEvent.LAUNCHER_TASKBAR_MORE_OPTIONS_A11Y_BUTTON_TAP;
import static com.android.launcher3.logging.StatsLogManager.LauncherEvent.LAUNCHER_TASKBAR_OVERVIEW_BUTTON_LONGPRESS;
import static com.android.launcher3.logging.StatsLogManager.LauncherEvent.LAUNCHER_TASKBAR_OVERVIEW_BUTTON_TAP;
import static com.android.systemui.shared.system.ActivityManagerWrapper.CLOSE_SYSTEM_WINDOWS_REASON_HOME_KEY;
import static com.android.systemui.shared.system.ActivityManagerWrapper.CLOSE_SYSTEM_WINDOWS_REASON_RECENTS;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_SCREEN_PINNING;

import android.app.contextualsearch.ContextualSearchConfig;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;

import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.annotation.VisibleForTesting;

import com.android.internal.accessibility.common.ShortcutConstants.UserShortcutType;
import com.android.internal.util.ScreenshotRequest;
import com.android.launcher3.R;
import com.android.launcher3.logging.StatsLogManager;
import com.android.launcher3.testing.TestLogging;
import com.android.launcher3.testing.shared.TestProtocol;
import com.android.quickstep.SystemUiProxy;
import com.android.quickstep.TaskUtils;
import com.android.quickstep.util.ContextualSearchInvoker;
import com.android.systemui.contextualeducation.GestureType;
import com.android.systemui.shared.system.QuickStepContract;
import com.android.systemui.shared.system.QuickStepContract.SystemUiStateFlags;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Controller for 3 button mode in the taskbar.
 * Handles all the functionality of the various buttons, making/routing the right calls into
 * launcher or sysui/system.
 */
public class TaskbarNavButtonController implements TaskbarControllers.LoggableTaskbarController {

    /** Allow some time in between the long press for back and recents. */
    static final int SCREEN_PIN_LONG_PRESS_THRESHOLD = 200;
    static final int SCREEN_PIN_LONG_PRESS_RESET = SCREEN_PIN_LONG_PRESS_THRESHOLD + 100;
    private static final String TAG = "TaskbarNavButtonController";

    /**
     * IchthysOS: the user does not want any assistant / voice-search surface. Flip to false to
     * restore stock behaviour (long-press home invokes Contextual Search / the assistant).
     */
    private static final boolean ASSIST_DISABLED = true;

    private final Rect mTempRect = new Rect();
    private long mLastScreenPinLongPress;
    private boolean mScreenPinned;
    private int mLastSentBackAction = ACTION_UP;

    @Override
    public void dumpLogs(String prefix, PrintWriter pw) {
        pw.println(prefix + "TaskbarNavButtonController:");

        pw.println(prefix + "\tmLastScreenPinLongPress=" + mLastScreenPinLongPress);
        pw.println(prefix + "\tmScreenPinned=" + mScreenPinned);
        pw.println(prefix + "\tmLastSentBackAction="
                + KeyEvent.actionToString(mLastSentBackAction));
        // IchthysOS: configured on-screen nav button actions (DeviceKeysConstants.Action
        // ordinals). Handy for telling "the setting didn't stick" apart from "the action isn't
        // supported" without a rebuild.
        pw.println(prefix + "\tmActionsCacheValid=" + mActionsCacheValid);
        pw.println(prefix + "\tmHomeLongPressAction=" + mHomeLongPressAction
                + " mHomeDoubleTapAction=" + mHomeDoubleTapAction);
        pw.println(prefix + "\tmAppSwitchLongPressAction=" + mAppSwitchLongPressAction
                + " mAppSwitchDoubleTapAction=" + mAppSwitchDoubleTapAction);
    }

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {
            BUTTON_BACK,
            BUTTON_HOME,
            BUTTON_RECENTS,
            BUTTON_IME_SWITCH,
            BUTTON_A11Y,
            BUTTON_QUICK_SETTINGS,
            BUTTON_NOTIFICATIONS,
            BUTTON_MORE_OPTIONS,
    })

    public @interface TaskbarButton {}

    static final int BUTTON_BACK = 1;
    static final int BUTTON_HOME = BUTTON_BACK << 1;
    static final int BUTTON_RECENTS = BUTTON_HOME << 1;
    static final int BUTTON_IME_SWITCH = BUTTON_RECENTS << 1;
    static final int BUTTON_A11Y = BUTTON_IME_SWITCH << 1;
    static final int BUTTON_QUICK_SETTINGS = BUTTON_A11Y << 1;
    static final int BUTTON_NOTIFICATIONS = BUTTON_QUICK_SETTINGS << 1;
    static final int BUTTON_MORE_OPTIONS = BUTTON_NOTIFICATIONS << 1;
    static final int BUTTON_SPACE = BUTTON_MORE_OPTIONS << 1;

    private static final int SCREEN_UNPIN_COMBO = BUTTON_BACK | BUTTON_RECENTS;
    private int mLongPressedButtons = 0;

    // ------------------------------------------------------------------------------------------
    // IchthysOS: user-configurable actions for the on-screen navigation buttons.
    //
    // WHY THIS LIVES HERE AND NOT IN PhoneWindowManager.
    // On this build the 3-button navbar is the Launcher taskbar (window "Taskbar", owner
    // com.android.launcher3, type NAVIGATION_BAR); there is no SystemUI NavigationBar0 window at
    // all. Verified on device by injecting taps at each button and reading back
    // "dumpsys input" -> InputDispatcher RecentQueue:
    //   * home TAP     -> MotionEvent DOWN/UP, then KeyEvent HOME(3) deviceId=-1
    //                     flags=FLAG_FROM_SYSTEM|FLAG_VIRTUAL_HARD_KEY. That key is injected by
    //                     SystemUI's LauncherProxyService.sendEvent(), reached via
    //                     OverviewCommandHelper's HOME command -> SystemUiProxy.onKeyEvent(). Its
    //                     ACTION_DOWN and ACTION_UP are 1ms apart, so PhoneWindowManager can only
    //                     ever see a *short* press this way -- a long press is not expressible.
    //   * recents TAP  -> MotionEvents only. No key event: onButtonClick() calls
    //                     OverviewCommandHelper directly.
    //   * back TAP     -> MotionEvents only. No key event: back goes through
    //                     SystemUiProxy.onBackEvent() into the predictive-back animation.
    // And every long press (onButtonLongClick below) is a plain View.OnLongClickListener that
    // never produces a key event at all. So a PhoneWindowManager-only port of the crDroid
    // hardware-key actions cannot fire for home-long-press, home-double-tap, recents-long-press
    // or recents-double-tap on this device: the events simply never get there.
    //
    // HOW ACTIONS ARE PERFORMED.
    // Launcher3 is not privileged enough to do most of these itself (no DEVICE_POWER, no
    // INJECT_EVENTS, no FORCE_STOP_PACKAGES). Where possible we hand the work to the system by
    // way of the *existing* SystemUiProxy surface -- in particular onKeyEvent(), which makes
    // LauncherProxyService inject a real system key that PhoneWindowManager then handles with
    // full privilege. No SystemUI change is required for any of this.
    //
    // Settings are the standard LineageOS/crDroid ones, in the "lineagesettings" provider (NOT
    // Settings.System). Launcher3 cannot link lineage-sdk, so they are read with a plain
    // ContentResolver query -- LineageSettingsProvider declares only a writePermission, and
    // LineageSettings' own NameValueCache falls back to exactly this query, so it is a supported
    // access path. Launcher3 runs as the current user, so the resolver is already USER_CURRENT.
    // ------------------------------------------------------------------------------------------
    private static final Uri LINEAGE_SETTINGS_SYSTEM_URI =
            Uri.parse("content://lineagesettings/system");
    private static final String[] LINEAGE_VALUE_PROJECTION = new String[] { "value" };
    private static final String LINEAGE_NAME_SELECTION = "name=?";

    private static final String KEY_HOME_LONG_PRESS_ACTION = "key_home_long_press_action";
    private static final String KEY_HOME_DOUBLE_TAP_ACTION = "key_home_double_tap_action";
    private static final String KEY_APP_SWITCH_LONG_PRESS_ACTION =
            "key_app_switch_long_press_action";
    private static final String KEY_APP_SWITCH_DOUBLE_TAP_ACTION =
            "key_app_switch_double_tap_action";

    // Ordinals of org.lineageos.internal.util.DeviceKeysConstants.Action. That enum lives in
    // lineage-sdk, which Launcher3 does not link, so the ordinals are mirrored here the same way
    // PhoneWindowManager mirrors them. Keep in sync with DeviceKeysConstants.Action and with
    // crDroidSettings' navbar_keys_action_values.
    private static final int ACTION_NOTHING = 0;
    private static final int ACTION_MENU = 1;
    private static final int ACTION_APP_SWITCH = 2;
    private static final int ACTION_SEARCH = 3;
    private static final int ACTION_VOICE_SEARCH = 4;
    private static final int ACTION_IN_APP_SEARCH = 5;
    private static final int ACTION_LAUNCH_CAMERA = 6;
    private static final int ACTION_SLEEP = 7;
    private static final int ACTION_LAST_APP = 8;
    private static final int ACTION_SPLIT_SCREEN = 9;
    private static final int ACTION_KILL_APP = 10;
    private static final int ACTION_PLAY_PAUSE_MUSIC = 11;
    private static final int ACTION_SCREENSHOT = 12;
    private static final int ACTION_PARTIAL_SCREENSHOT = 13;
    private static final int ACTION_TORCH = 14;
    private static final int ACTION_VOLUME_PANEL = 15;
    private static final int ACTION_CLEAR_ALL_NOTIFICATIONS = 16;
    private static final int ACTION_NOTIFICATIONS_PANEL = 17;
    private static final int ACTION_EXPAND_QS_PANEL = 18;
    private static final int ACTION_RINGER_MODES = 19;

    // Defaults. IchthysOS has no assistant / voice search, so home long-press defaults to NOTHING
    // rather than reproducing stock's VOICE_SEARCH. A fresh install therefore never lands on an
    // assistant action, and ACTION_VOICE_SEARCH is unreachable anyway (performKeyAction() refuses
    // it, and it is no longer offered by crDroidSettings' navbar_keys_action_entries). Keep in
    // sync with crDroidSettings' Buttons.java, which shows the same defaults in the picker.
    private static final int DEFAULT_HOME_LONG_PRESS_ACTION = ACTION_NOTHING;
    private static final int DEFAULT_DOUBLE_TAP_ACTION = ACTION_NOTHING;
    private static final int DEFAULT_APP_SWITCH_LONG_PRESS_ACTION = ACTION_NOTHING;

    /**
     * Pending single-tap work, held back only while a double-tap action is configured for that
     * button (otherwise taps stay completely synchronous, as upstream). Non-null means we are
     * inside the double-tap window.
     */
    @Nullable private Runnable mPendingHomeSingleTap;
    @Nullable private Runnable mPendingRecentsSingleTap;

    // Cached copies of the four settings above. The lineagesettings provider lives in its own
    // app process, so a cold read can have to wait for that process to start -- not something we
    // can afford on the home button's tap path. Warmed at init() and invalidated by
    // mSettingsObserver, so a press only ever reads these fields.
    @Nullable private ContentObserver mSettingsObserver;
    // volatile: invalidated from the ContentObserver, read from the button dispatch path.
    private volatile boolean mActionsCacheValid;
    private int mHomeLongPressAction = DEFAULT_HOME_LONG_PRESS_ACTION;
    private int mHomeDoubleTapAction = DEFAULT_DOUBLE_TAP_ACTION;
    private int mAppSwitchLongPressAction = DEFAULT_APP_SWITCH_LONG_PRESS_ACTION;
    private int mAppSwitchDoubleTapAction = DEFAULT_DOUBLE_TAP_ACTION;

    private boolean mTorchEnabled;
    private boolean mTorchCallbackRegistered;
    /** IchthysOS: kept to unregister in onDestroy (each rebuilt controller used to stay alive). */
    private CameraManager.TorchCallback mTorchCallback;

    private final int mDisplayId;
    private final TaskbarNavButtonCallbacks mCallbacks;
    private final SystemUiProxy mSystemUiProxy;
    private final Handler mHandler;
    private final ContextualSearchInvoker mContextualSearchInvoker;
    // The lifecycle of TaskbarNavButtonController for primary display can be as long as TIS, which
    // is longer than TaskbarActivityContext. Thus we should clear ref of TaskbarControllers which
    // holds a lot of TaskbarActivityContext lifecycle objs to avoid memory leaks.
    private TaskbarControllers mControllers;
    @Nullable private StatsLogManager mStatsLogManager;

    private final Runnable mResetLongPress = this::resetScreenUnpin;

    public TaskbarNavButtonController(
            int displayId,
            TaskbarNavButtonCallbacks callbacks,
            SystemUiProxy systemUiProxy,
            Handler handler,
            ContextualSearchInvoker contextualSearchInvoker) {
        mDisplayId = displayId;
        mCallbacks = callbacks;
        mSystemUiProxy = systemUiProxy;
        mHandler = handler;
        mContextualSearchInvoker = contextualSearchInvoker;
    }

    public void onButtonClick(@TaskbarButton int buttonType, View view) {
        if (buttonType == BUTTON_SPACE) {
            return;
        }
        if (mLastSentBackAction == ACTION_DOWN) {
            Log.i(TAG, "Button click ignored while back button is pressed");
            // prevent interactions with other buttons while back button is pressed
            return;
        }
        switch (buttonType) {
            case BUTTON_BACK:
                executeBack(/* keyEvent */ null);
                break;
            case BUTTON_HOME:
                logEvent(LAUNCHER_TASKBAR_HOME_BUTTON_TAP);
                mSystemUiProxy.updateContextualEduStats(/* isTrackpadGesture= */ false,
                        GestureType.HOME);
                // IchthysOS: honour key_home_double_tap_action. Falls straight through to
                // navigateHome() when no double-tap action is configured (the default), so the
                // stock home button keeps its zero-latency behaviour.
                if (!handleDoubleTap(BUTTON_HOME, KEY_HOME_DOUBLE_TAP_ACTION, this::navigateHome)) {
                    navigateHome();
                }
                break;
            case BUTTON_RECENTS:
                logEvent(LAUNCHER_TASKBAR_OVERVIEW_BUTTON_TAP);
                mSystemUiProxy.updateContextualEduStats(/* isTrackpadGesture= */ false,
                        GestureType.OVERVIEW);
                // IchthysOS: honour key_app_switch_double_tap_action, same deal as home.
                if (!handleDoubleTap(BUTTON_RECENTS, KEY_APP_SWITCH_DOUBLE_TAP_ACTION,
                        this::navigateToOverview)) {
                    navigateToOverview();
                }
                break;
            case BUTTON_IME_SWITCH:
                logEvent(LAUNCHER_TASKBAR_IME_SWITCHER_BUTTON_TAP);
                onImeSwitcherPress();
                break;
            case BUTTON_A11Y:
                logEvent(LAUNCHER_TASKBAR_A11Y_BUTTON_TAP);
                notifyA11yClick(false /* longClick */);
                break;
            case BUTTON_QUICK_SETTINGS:
                showQuickSettings();
                break;
            case BUTTON_NOTIFICATIONS:
                showNotifications();
                break;
            case BUTTON_MORE_OPTIONS:
                logEvent(LAUNCHER_TASKBAR_MORE_OPTIONS_A11Y_BUTTON_TAP);
                launchAccessibilityShortcutChooser();
                break;
        }
    }

    /**
     * Handles long clicks and plays haptics for user visible actions.
     */
    public boolean onButtonLongClick(@TaskbarButton int buttonType, View view) {
        if (buttonType == BUTTON_SPACE) {
            return false;
        }
        if (mLastSentBackAction == ACTION_DOWN && buttonType != BUTTON_BACK
                && buttonType != BUTTON_RECENTS) {
            // prevent interactions with other buttons while back button is pressed (except back
            // and recents button for screen-unpin action).
            Log.i(TAG, "Button long click ignored while back button is pressed");
            return false;
        }

        boolean playHapticAndSound = false;

        switch (buttonType) {
            case BUTTON_HOME: {
                logEvent(LAUNCHER_TASKBAR_HOME_BUTTON_LONGPRESS);
                // IchthysOS: key_home_long_press_action. The upstream assistant path
                // (onLongPressHome -> Contextual Search / SysUI startAssistant) is deliberately
                // NOT reachable from here any more: this build has no assistant, so VOICE_SEARCH
                // falls through to performKeyAction(), which refuses it and returns false, which
                // in turn makes the long press behave like an ordinary home press instead of
                // being swallowed.
                final int action = getCachedAction(KEY_HOME_LONG_PRESS_ACTION);
                if (performKeyAction(action)) {
                    playHapticAndSound = true;
                } else {
                    // Configured to do nothing (or an action this device can't perform): don't
                    // swallow the gesture, so releasing still behaves like a normal home tap.
                    return false;
                }
                break;
            }
            case BUTTON_A11Y:
                logEvent(LAUNCHER_TASKBAR_A11Y_BUTTON_LONGPRESS);
                notifyA11yClick(true /* longClick */);
                playHapticAndSound = true;
                break;
            case BUTTON_BACK:
                logEvent(LAUNCHER_TASKBAR_BACK_BUTTON_LONGPRESS);
                if (backRecentsLongpress(buttonType)) {
                    playHapticAndSound = true;
                }
                break;
            case BUTTON_RECENTS:
                logEvent(LAUNCHER_TASKBAR_OVERVIEW_BUTTON_LONGPRESS);
                // Screen-pinning unpin combo (back + recents) keeps absolute priority and is
                // untouched: backRecentsLongpress() only returns true while pinned, and while
                // pinned we never run a custom action.
                if (backRecentsLongpress(buttonType)) {
                    playHapticAndSound = true;
                } else if (performKeyAction(
                        getCachedAction(KEY_APP_SWITCH_LONG_PRESS_ACTION))) {
                    // IchthysOS: key_app_switch_long_press_action.
                    playHapticAndSound = true;
                }
                break;
            case BUTTON_IME_SWITCH:
                logEvent(LAUNCHER_TASKBAR_IME_SWITCHER_BUTTON_LONGPRESS);
                onImeSwitcherLongPress();
                playHapticAndSound = true;
                break;
            case BUTTON_MORE_OPTIONS:
                launchAccessibilityShortcutChooser();
                playHapticAndSound = true;
                break;
            default:
                return false;
        }

        if (playHapticAndSound) {
            view.playSoundEffect(SoundEffectConstants.CLICK);
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS,
                    HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING);
        }
        return true;
    }

    public @StringRes int getButtonContentDescription(@TaskbarButton int buttonType) {
        switch (buttonType) {
            case BUTTON_HOME:
                return R.string.taskbar_button_home;
            case BUTTON_A11Y:
                return R.string.taskbar_button_a11y;
            case BUTTON_BACK:
                return R.string.taskbar_button_back;
            case BUTTON_IME_SWITCH:
                return R.string.taskbar_button_ime_switcher;
            case BUTTON_RECENTS:
                return R.string.taskbar_button_recents;
            case BUTTON_NOTIFICATIONS:
                return R.string.taskbar_button_notifications;
            case BUTTON_QUICK_SETTINGS:
                return R.string.taskbar_button_quick_settings;
            case BUTTON_MORE_OPTIONS:
                return R.string.taskbar_button_more_options;
            default:
                return 0;
        }
    }

    /**
     * Notifies SystemUI of the new bounds of the recents button in screen coordinates.
     */
    public void onRecentsButtonLayoutChanged(Rect bounds) {
        mSystemUiProxy.notifyRecentsButtonPositionChanged(bounds);
        if (cueBarAceMigration() && mControllers.cueBarController != null) {
            mControllers.cueBarController.onRecentsButtonLayoutChanged(bounds);
        }
    }

    private boolean backRecentsLongpress(@TaskbarButton int buttonType) {
        mLongPressedButtons |= buttonType;
        return determineScreenUnpin();
    }

    /**
     * Checks if the user has long pressed back and recents buttons
     * "together" (within {@link #SCREEN_PIN_LONG_PRESS_THRESHOLD})ms
     * If so, then requests the system to turn off screen pinning.
     *
     * @return true if the long press is a valid user action in attempting to unpin an app
     *         Will always return {@code false} when screen pinning is not active.
     *         NOTE: Returning true does not mean that screen pinning has stopped
     */
    private boolean determineScreenUnpin() {
        long timeNow = System.currentTimeMillis();
        if (!mScreenPinned) {
            return false;
        }

        if (mLastScreenPinLongPress == 0) {
            // First button long press registered, just mark time and wait for second button press
            mLastScreenPinLongPress = System.currentTimeMillis();
            mHandler.postDelayed(mResetLongPress, SCREEN_PIN_LONG_PRESS_RESET);
            return true;
        }

        if ((timeNow - mLastScreenPinLongPress) > SCREEN_PIN_LONG_PRESS_THRESHOLD) {
            // Too long in-between presses, reset the clock
            resetScreenUnpin();
            return false;
        }

        if ((mLongPressedButtons & SCREEN_UNPIN_COMBO) == SCREEN_UNPIN_COMBO) {
            // Hooray! They did it (finally...)
            mSystemUiProxy.stopScreenPinning();
            mHandler.removeCallbacks(mResetLongPress);
            resetScreenUnpin();
        }
        return true;
    }

    private void resetScreenUnpin() {
        // if only back button was long pressed, navigate back like a single click back behavior.
        if (mLongPressedButtons == BUTTON_BACK) {
            executeBack(null);
        }
        mLongPressedButtons = 0;
        mLastScreenPinLongPress = 0;
    }

    public void updateSysuiFlags(@SystemUiStateFlags long sysuiFlags) {
        mScreenPinned = (sysuiFlags & SYSUI_STATE_SCREEN_PINNING) != 0;
    }

    public void init(TaskbarControllers taskbarControllers) {
        mControllers = taskbarControllers;
        mStatsLogManager = mControllers.getTaskbarActivityContext().getStatsLogManager();
        registerActionSettingsObserver();
    }

    public void onDestroy() {
        cancelPendingSingleTaps();
        // Before mControllers is cleared -- unregistering needs the context.
        unregisterActionSettingsObserver();
        if (mTorchCallback != null && mControllers != null) {
            final CameraManager cameraManager = mControllers.getTaskbarActivityContext()
                    .getSystemService(CameraManager.class);
            if (cameraManager != null) {
                cameraManager.unregisterTorchCallback(mTorchCallback);
            }
            mTorchCallback = null;
            mTorchCallbackRegistered = false;
        }
        mStatsLogManager = null;
        mControllers = null;
    }

    private void logEvent(StatsLogManager.LauncherEvent event) {
        if (mStatsLogManager == null) {
            Log.w(TAG, "No stats log manager to log taskbar button event");
            return;
        }
        mStatsLogManager.logger().log(event);
    }

    private void navigateHome() {
        TaskUtils.closeSystemWindowsAsync(CLOSE_SYSTEM_WINDOWS_REASON_HOME_KEY);
        mCallbacks.onNavigateHome(mDisplayId);
    }

    private void navigateToOverview() {
        if (mScreenPinned) {
            return;
        }
        TestLogging.recordEvent(TestProtocol.SEQUENCE_MAIN, "onOverviewToggle");
        TaskUtils.closeSystemWindowsAsync(CLOSE_SYSTEM_WINDOWS_REASON_RECENTS);
        mCallbacks.onToggleOverview(mDisplayId);
    }

    public void hideOverview() {
        mCallbacks.onHideOverview(mDisplayId);
    }

    void sendBackKeyEvent(int action, boolean cancelled) {
        if (action == mLastSentBackAction) {
            // There must always be an alternating sequence of ACTION_DOWN and ACTION_UP events
            return;
        }
        long time = SystemClock.uptimeMillis();
        KeyEvent keyEvent = new KeyEvent(time, time, action, KeyEvent.KEYCODE_BACK, 0);
        keyEvent.setDisplayId(mDisplayId);
        if (cancelled) {
            keyEvent.cancel();
        }
        executeBack(keyEvent);
    }

    private void executeBack(@Nullable KeyEvent keyEvent) {
        if (keyEvent == null || (keyEvent.getAction() == ACTION_UP && !keyEvent.isCanceled())) {
            logEvent(LAUNCHER_TASKBAR_BACK_BUTTON_TAP);
            mSystemUiProxy.updateContextualEduStats(/* isTrackpadGesture= */ false,
                    GestureType.BACK);
        }
        mSystemUiProxy.onBackEvent(keyEvent, mDisplayId);
        mLastSentBackAction = keyEvent != null ? keyEvent.getAction() : ACTION_UP;
    }

    private void onImeSwitcherPress() {
        mSystemUiProxy.onImeSwitcherPressed();
    }

    private void onImeSwitcherLongPress() {
        mSystemUiProxy.onImeSwitcherLongPress();
    }

    private void launchAccessibilityShortcutChooser() {
        Intent intent = new Intent(
                QuickStepContract.ACTION_LAUNCH_ACCESSIBILITY_SHORTCUT_CHOOSER_DIALOG)
                .setPackage(QuickStepContract.SYSUI_PACKAGE)
                .putExtra(QuickStepContract.EXTRA_ACCESSIBILITY_DISPLAY_ID, mDisplayId)
                .putExtra(
                        QuickStepContract.EXTRA_ACCESSIBILITY_SHORTCUT_TYPE,
                        UserShortcutType.SOFTWARE)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND);

        mControllers.getTaskbarActivityContext().sendBroadcastAsUser(intent, UserHandle.CURRENT);
    }

    private void notifyA11yClick(boolean longClick) {
        if (longClick) {
            mSystemUiProxy.notifyAccessibilityButtonLongClicked();
        } else {
            mSystemUiProxy.notifyAccessibilityButtonClicked(mDisplayId);
        }
    }

    /**
     * Upstream's "long press home invokes the assistant" path. IchthysOS has no assistant, so
     * this is no longer called from {@link #onButtonLongClick} - the guard below only exists so
     * that it stays inert if a future merge reintroduces a caller. Kept rather than deleted to
     * keep upstream diffs small.
     */
    @SuppressWarnings("unused")
    private void onLongPressHome(View view) {
        // IchthysOS: assistant disabled on this build.
        if (ASSIST_DISABLED) {
            Log.i(TAG, "Contextual Search invocation failed: assistant disabled on this build");
            return;
        }
        if (mControllers == null) {
            Log.w(TAG, "Contextual Search invocation failed: Controller not initialized");
            return;
        }
        if (mScreenPinned) {
            Log.w(TAG, "Contextual Search invocation failed: Screen pinned");
            return;
        }
        if (!mControllers.getSharedState().assistantLongPressEnabled) {
            Log.w(TAG, "Contextual Search invocation failed: LPH disabled");
            return;
        }
        // Attempt to start Contextual Search, otherwise fall back to SysUi's implementation.
        view.getBoundsOnScreen(mTempRect);
        ContextualSearchConfig config = new ContextualSearchConfig.Builder()
                .setSourceBounds(mTempRect).setDisplayId(mDisplayId).build();
        if (!mContextualSearchInvoker.tryStartAssistOverride(
                INVOCATION_TYPE_HOME_BUTTON_LONG_PRESS, config)) {
            Bundle args = new Bundle();
            args.putInt(INVOCATION_TYPE_KEY, INVOCATION_TYPE_HOME_BUTTON_LONG_PRESS);
            mSystemUiProxy.startAssistant(args);
        }
    }

    private void showQuickSettings() {
        mSystemUiProxy.toggleNotificationPanel();
    }

    private void showNotifications() {
        mSystemUiProxy.toggleNotificationPanel();
    }

    // ------------------------------------------------------------------------------------------
    // IchthysOS: configurable on-screen nav button actions. See the block comment near
    // SCREEN_UNPIN_COMBO for why the consumer lives here and not in PhoneWindowManager.
    // ------------------------------------------------------------------------------------------

    @Nullable
    private Context getTaskbarContext() {
        final TaskbarControllers controllers = mControllers;
        return controllers == null ? null : controllers.getTaskbarActivityContext();
    }

    private void registerActionSettingsObserver() {
        final Context context = getTaskbarContext();
        if (context == null || mSettingsObserver != null) {
            return;
        }
        mSettingsObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange) {
                mActionsCacheValid = false;
            }
        };
        // notifyForDescendants: the provider notifies the per-name item URI on a put but the bare
        // table URI on a delete, so watch the whole system table to catch both.
        context.getContentResolver().registerContentObserver(LINEAGE_SETTINGS_SYSTEM_URI,
                true /* notifyForDescendants */, mSettingsObserver);
        mActionsCacheValid = false;
        // Warm the cache off the button press path, so the very first press never pays for
        // starting the settings provider's process.
        mHandler.post(this::loadActionSettings);
    }

    private void unregisterActionSettingsObserver() {
        if (mSettingsObserver == null) {
            return;
        }
        final Context context = getTaskbarContext();
        if (context != null) {
            context.getContentResolver().unregisterContentObserver(mSettingsObserver);
        }
        mSettingsObserver = null;
        mActionsCacheValid = false;
    }

    private void loadActionSettings() {
        if (mActionsCacheValid || getTaskbarContext() == null) {
            return;
        }
        mHomeLongPressAction =
                getActionSetting(KEY_HOME_LONG_PRESS_ACTION, DEFAULT_HOME_LONG_PRESS_ACTION);
        mHomeDoubleTapAction =
                getActionSetting(KEY_HOME_DOUBLE_TAP_ACTION, DEFAULT_DOUBLE_TAP_ACTION);
        mAppSwitchLongPressAction = getActionSetting(
                KEY_APP_SWITCH_LONG_PRESS_ACTION, DEFAULT_APP_SWITCH_LONG_PRESS_ACTION);
        mAppSwitchDoubleTapAction =
                getActionSetting(KEY_APP_SWITCH_DOUBLE_TAP_ACTION, DEFAULT_DOUBLE_TAP_ACTION);
        mActionsCacheValid = true;
    }

    /** Cached action for one of the four supported nav button gestures. */
    private int getCachedAction(String settingKey) {
        loadActionSettings();
        switch (settingKey) {
            case KEY_HOME_LONG_PRESS_ACTION:
                return mHomeLongPressAction;
            case KEY_HOME_DOUBLE_TAP_ACTION:
                return mHomeDoubleTapAction;
            case KEY_APP_SWITCH_LONG_PRESS_ACTION:
                return mAppSwitchLongPressAction;
            case KEY_APP_SWITCH_DOUBLE_TAP_ACTION:
                return mAppSwitchDoubleTapAction;
            default:
                return ACTION_NOTHING;
        }
    }

    /**
     * Reads a {@code DeviceKeysConstants.Action} ordinal out of the LineageOS settings provider.
     * Returns {@code def} when the setting is unset or unreadable, and {@link #ACTION_NOTHING}
     * when the stored value is not an action we know about -- mirroring
     * {@code Action.fromIntSafe()}, so a stale or bogus value degrades to "do nothing" instead of
     * throwing.
     */
    private int getActionSetting(String name, int def) {
        final Context context = getTaskbarContext();
        if (context == null) {
            return def;
        }
        try (Cursor cursor = context.getContentResolver().query(LINEAGE_SETTINGS_SYSTEM_URI,
                LINEAGE_VALUE_PROJECTION, LINEAGE_NAME_SELECTION, new String[] { name }, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                final String value = cursor.getString(0);
                if (value != null) {
                    final int action = Integer.parseInt(value);
                    return (action < ACTION_NOTHING || action > ACTION_RINGER_MODES)
                            ? ACTION_NOTHING : action;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not read LineageSettings.System." + name, e);
        }
        return def;
    }

    /**
     * Double-tap handling for a nav button.
     *
     * @return {@code true} if this tap was consumed here (either deferred as a possible first tap
     *         or spent triggering the double-tap action). {@code false} means no double-tap
     *         action is configured and the caller should just do its normal thing immediately --
     *         which is the default, so the buttons stay latency-free unless the user opts in.
     */
    private boolean handleDoubleTap(@TaskbarButton int buttonType, String settingKey,
            Runnable singleTapAction) {
        if (mScreenPinned) {
            return false;
        }
        final Runnable pending = buttonType == BUTTON_HOME
                ? mPendingHomeSingleTap : mPendingRecentsSingleTap;
        if (pending != null) {
            // Second tap inside the window: drop the deferred first tap and run the action.
            mHandler.removeCallbacks(pending);
            setPendingSingleTap(buttonType, null);
            if (!performKeyAction(getCachedAction(settingKey))) {
                // The setting changed between the two taps, or the action isn't supported here.
                // Behave like the two ordinary taps the user actually made.
                singleTapAction.run();
            }
            return true;
        }
        if (getCachedAction(settingKey) == ACTION_NOTHING) {
            return false;
        }
        final Runnable deferred = () -> {
            setPendingSingleTap(buttonType, null);
            singleTapAction.run();
        };
        setPendingSingleTap(buttonType, deferred);
        mHandler.postDelayed(deferred, ViewConfiguration.getDoubleTapTimeout());
        return true;
    }

    private void setPendingSingleTap(@TaskbarButton int buttonType, @Nullable Runnable runnable) {
        if (buttonType == BUTTON_HOME) {
            mPendingHomeSingleTap = runnable;
        } else {
            mPendingRecentsSingleTap = runnable;
        }
    }

    private void cancelPendingSingleTaps() {
        if (mPendingHomeSingleTap != null) {
            mHandler.removeCallbacks(mPendingHomeSingleTap);
            mPendingHomeSingleTap = null;
        }
        if (mPendingRecentsSingleTap != null) {
            mHandler.removeCallbacks(mPendingRecentsSingleTap);
            mPendingRecentsSingleTap = null;
        }
    }

    /**
     * Performs a {@code DeviceKeysConstants.Action}.
     *
     * @return {@code true} if something was actually done. {@code false} for "nothing" and for
     *         actions the launcher process cannot perform, so callers can fall back to the
     *         button's stock behaviour instead of silently swallowing the gesture.
     */
    private boolean performKeyAction(int action) {
        if (mScreenPinned) {
            // Screen pinning exists to stop the user leaving the pinned app; a nav button bound
            // to "go home" / "camera" / "sleep" would be a hole straight through it.
            return false;
        }
        switch (action) {
            case ACTION_NOTHING:
                return false;
            case ACTION_MENU:
                mSystemUiProxy.onKeyEvent(KeyEvent.KEYCODE_MENU, mDisplayId);
                return true;
            case ACTION_APP_SWITCH:
                navigateToOverview();
                return true;
            case ACTION_IN_APP_SEARCH:
                mSystemUiProxy.onKeyEvent(KeyEvent.KEYCODE_SEARCH, mDisplayId);
                return true;
            case ACTION_SEARCH:
                // IchthysOS: "Search assistant" is an assistant surface, which this build does
                // not have; injecting KEYCODE_SEARCH only ever did in-app search, so the option
                // is gone from crDroidSettings' navbar_keys_action_entries and a stored value
                // (which the picker now shows as "Nothing") does nothing, like VOICE_SEARCH.
            case ACTION_VOICE_SEARCH:
                // IchthysOS: no assistant / voice search on this build. The option is gone from
                // crDroidSettings' pickers, so this is only reachable from a value stored before
                // that change (or restored from a backup); treat it as "nothing" so the button
                // falls back to its stock behaviour rather than doing something unwanted.
                Log.i(TAG, "Nav button action VOICE_SEARCH ignored: assistant disabled");
                return false;
            case ACTION_LAUNCH_CAMERA:
                return launchCamera();
            case ACTION_SLEEP:
                // Launcher3 has no DEVICE_POWER permission; PhoneWindowManager turns an injected
                // KEYCODE_SLEEP into sleepPress()/sleepRelease() for us.
                mSystemUiProxy.onKeyEvent(KeyEvent.KEYCODE_SLEEP, mDisplayId);
                return true;
            case ACTION_LAST_APP:
                // IchthysOS: Slim Recents loads asynchronously, so the double toggle below just
                // leaves its panel open; it switches directly instead.
                if (mCallbacks.onSwitchToLastApp(mDisplayId)) {
                    return true;
                }
                // Two overview toggles in a row is exactly the "double tap recents" sequence
                // OverviewCommandHelper keeps a command queue for, and it lands on the last app.
                navigateToOverview();
                navigateToOverview();
                return true;
            case ACTION_PLAY_PAUSE_MUSIC:
                mSystemUiProxy.onKeyEvent(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, mDisplayId);
                return true;
            case ACTION_PARTIAL_SCREENSHOT:
                // Android 17 removed TAKE_SCREENSHOT_SELECTED_REGION (ScreenshotRequest.Builder
                // only accepts FULLSCREEN / PROVIDED_IMAGE), so there is no region-select entry
                // point left. The full-screen shot's editor lets the user crop anyway.
            case ACTION_SCREENSHOT:
                takeScreenshot();
                return true;
            case ACTION_TORCH:
                return toggleTorch();
            case ACTION_VOLUME_PANEL:
                return showVolumePanel();
            case ACTION_NOTIFICATIONS_PANEL:
                mSystemUiProxy.expandNotificationPanel();
                return true;
            case ACTION_EXPAND_QS_PANEL:
                mSystemUiProxy.toggleQuickSettingsPanel();
                return true;
            case ACTION_SPLIT_SCREEN:
            case ACTION_KILL_APP:
            case ACTION_CLEAR_ALL_NOTIFICATIONS:
            case ACTION_RINGER_MODES:
            default:
                // Needs privileges the launcher process does not have (FORCE_STOP_PACKAGES, DND
                // policy access, a connected notification listener) or plumbing that does not
                // exist here. crDroidSettings does not offer these for the on-screen buttons;
                // this is the belt-and-braces path for a value written by something else.
                Log.w(TAG, "Nav button action " + action + " is not supported on this device");
                return false;
        }
    }

    private boolean launchCamera() {
        final Context context = getTaskbarContext();
        if (context == null) {
            return false;
        }
        try {
            context.startActivity(new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Could not launch camera", e);
            return false;
        }
    }

    private void takeScreenshot() {
        mSystemUiProxy.takeScreenshot(new ScreenshotRequest.Builder(
                WindowManager.TAKE_SCREENSHOT_FULLSCREEN,
                WindowManager.ScreenshotSource.SCREENSHOT_OTHER)
                .setDisplayId(mDisplayId)
                .build());
    }

    private boolean showVolumePanel() {
        final Context context = getTaskbarContext();
        if (context == null) {
            return false;
        }
        final AudioManager audioManager = context.getSystemService(AudioManager.class);
        if (audioManager == null) {
            return false;
        }
        // ADJUST_SAME shows the panel without moving the volume, and no FLAG_PLAY_SOUND, so this
        // never makes noise.
        audioManager.adjustSuggestedStreamVolume(AudioManager.ADJUST_SAME,
                AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI);
        return true;
    }

    private boolean toggleTorch() {
        final Context context = getTaskbarContext();
        if (context == null) {
            return false;
        }
        final CameraManager cameraManager = context.getSystemService(CameraManager.class);
        if (cameraManager == null) {
            return false;
        }
        try {
            if (!mTorchCallbackRegistered) {
                // Keeps mTorchEnabled honest when something else (QS tile, camera app) changes
                // the torch. The very first press assumes "off", which is right nearly always and
                // self-corrects from the callback afterwards.
                mTorchCallback = new CameraManager.TorchCallback() {
                    @Override
                    public void onTorchModeChanged(String cameraId, boolean enabled) {
                        mTorchEnabled = enabled;
                    }

                    @Override
                    public void onTorchModeUnavailable(String cameraId) {
                        mTorchEnabled = false;
                    }
                };
                cameraManager.registerTorchCallback(mTorchCallback, mHandler);
                mTorchCallbackRegistered = true;
            }
            String torchCameraId = null;
            for (String cameraId : cameraManager.getCameraIdList()) {
                final CameraCharacteristics characteristics =
                        cameraManager.getCameraCharacteristics(cameraId);
                if (Boolean.TRUE.equals(
                        characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE))) {
                    torchCameraId = cameraId;
                    final Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                    if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                        break;
                    }
                }
            }
            if (torchCameraId == null) {
                return false;
            }
            cameraManager.setTorchMode(torchCameraId, !mTorchEnabled);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Could not toggle torch", e);
            return false;
        }
    }

    @VisibleForTesting
    boolean hasControllersSet() {
        return mControllers != null;
    }

    /** Callbacks for navigation buttons on Taskbar. */
    public interface TaskbarNavButtonCallbacks {
        /** Callback invoked when the home button is pressed. */
        default void onNavigateHome(int displayId) {}

        /** Callback invoked when the overview button is pressed. */
        default void onToggleOverview(int displayId) {}

        /** Callback invoken when a visible overview needs to be hidden. */
        default void onHideOverview(int displayId) { }

        /**
         * IchthysOS: "Last app" action for recents styles the double overview toggle can't
         * serve. Returns false when the caller should fall back to the double toggle.
         */
        default boolean onSwitchToLastApp(int displayId) {
            return false;
        }
    }
}
