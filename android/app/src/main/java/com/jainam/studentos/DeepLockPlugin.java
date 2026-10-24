package com.jainam.studentos;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityManager;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import androidx.core.app.ActivityCompat;

import com.jainam.studentos.focusshield.FocusShieldBridge;
import com.jainam.studentos.focusshield.FocusShieldManager;

import android.os.Handler;
import android.os.Looper;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@CapacitorPlugin(name = "DeepLock")
public class DeepLockPlugin extends Plugin {

    private static final String TAG = "[DeepLockPlugin]";
    private static final String EVENT_FOREGROUND_APP_CHANGED = "foregroundAppChanged";
    private static final String EVENT_BLOCKED_APP_ATTEMPT = "blockedAppAttempt";
    private static final String EVENT_SESSION_STATE_CHANGED = "focusSessionStateChanged";
    private static final String PREFS_NAME = "student_os_prefs";
    private static final String KEY_DISCLOSURE = "prominent_disclosure_accepted";

    /** Instrumentation logs are debug-only (release builds stay quiet). */
    private boolean isDebugBuild() {
        android.content.Context ctx = getContext();
        return ctx != null &&
            (ctx.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    // Completions emitted by native before JS attached its listener (cold start /
    // offline finish) are buffered here and drained by consumePendingCompletion().
    private final java.util.ArrayDeque<JSObject> completedEventsBuffer = new java.util.ArrayDeque<>();

    private final DeepLockService.PluginCallback pluginCallback =
            new DeepLockService.PluginCallback() {
                @Override
                public void onForegroundAppChanged(String packageName, String appName) {
                    JSObject data = new JSObject();
                    data.put("packageName", packageName != null ? packageName : "");
                    data.put("appName", appName != null ? appName : "");
                    notifyListeners(EVENT_FOREGROUND_APP_CHANGED, data, false);
                }

                @Override
                public void onBlockedAppAttempt(String packageName, String appName) {
                    JSObject data = new JSObject();
                    data.put("packageName", packageName != null ? packageName : "");
                    data.put("appName", appName != null ? appName : "");
                    data.put("timestamp", System.currentTimeMillis());
                    notifyListeners(EVENT_BLOCKED_APP_ATTEMPT, data, false);
                }

                @Override
                public void onSessionStateChanged(String state) {
                    JSObject data = new JSObject();
                    data.put("state", state != null ? state : "");
                    notifyListeners(EVENT_SESSION_STATE_CHANGED, data, false);
                }
            };

    @Override
    public void load() {
        Log.i(TAG, "load() — DeepLockPlugin loading");
        super.load();
        ensureCallbackRegistered();
        registerSessionCompletedNotifier();
        mainHandler.postDelayed(pendingCompletionRunnable, 2000);
        Log.i(TAG, "load() — complete, FocusShieldBridge.getManager()=" + FocusShieldBridge.getManager());
    }

    private volatile PluginCall pendingNotificationPermissionCall;
    private final Runnable pendingCompletionRunnable = new Runnable() {
        @Override
        public void run() {
            FocusShieldManager mgr = getFocusShield();
            if (mgr != null) {
                mgr.checkPendingCompletions();
            }
        }
    };
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private void registerSessionCompletedNotifier() {
        FocusShieldManager fsm = getFocusShield();
        if (fsm == null) {
            Log.w(TAG, "registerSessionCompletedNotifier — FocusShieldManager is null, will retry on next call");
            return;
        }
        fsm.setSessionCompletedNotifier(data -> {
            JSObject event = new JSObject();
            event.put("sessionId", (String) data.get("sessionId"));
            event.put("actualDurationMs", (Long) data.get("actualDurationMs"));
            event.put("plannedDuration", (Long) data.get("plannedDuration"));
            event.put("streak", (Integer) data.get("streak"));
            event.put("startTime", (Long) data.get("startTime"));
            event.put("endTime", (Long) data.get("endTime"));
            synchronized (completedEventsBuffer) {
                completedEventsBuffer.addLast(event);
                while (completedEventsBuffer.size() > 10) completedEventsBuffer.removeFirst();
            }
            notifyListeners("sessionCompleted", event, false);
            Log.i(TAG, "[FOCUS_INSTRUMENT] sessionCompleted emitted to JS — sessionId=" + data.get("sessionId"));
            return kotlin.Unit.INSTANCE;
        });
        fsm.registerSessionCompleteListener();
        Log.i(TAG, "registerSessionCompletedNotifier — notifier + listener registered");
        fsm.checkPendingCompletions();
    }

    /**
     * Pull-based completion drain. JS calls this on Focus mount so a session
     * finished natively while the React tree was unmounted (cold start / offline
     * finish) is never lost even though the live notification was dropped.
     * The live `sessionCompleted` listener stays intact; JS dedupes by sessionId.
     */
    @PluginMethod
    public void consumePendingCompletion(PluginCall call) {
        JSObject result = new JSObject();
        JSArray completions = new JSArray();
        synchronized (completedEventsBuffer) {
            for (JSObject event : completedEventsBuffer) {
                completions.put(event);
            }
            completedEventsBuffer.clear();
        }
        result.put("completions", completions);
        call.resolve(result);
    }

    @Override
    protected void handleOnDestroy() {
        Log.i(TAG, "handleOnDestroy() — removing plugin callback");
        DeepLockService service = DeepLockService.getInstance();
        if (service != null) {
            service.removePluginCallback(pluginCallback);
        }
        FocusShieldManager fsm = getFocusShield();
        if (fsm != null) {
            fsm.setSessionCompletedNotifier(null);
        }
        mainHandler.removeCallbacks(pendingCompletionRunnable);
        synchronized (completedEventsBuffer) { completedEventsBuffer.clear(); }
        super.handleOnDestroy();
    }

    @Override
    public void handleRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.handleRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 9991 && pendingNotificationPermissionCall != null) {
            boolean granted = grantResults.length > 0 && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
            JSObject result = new JSObject();
            result.put("granted", granted);
            pendingNotificationPermissionCall.resolve(result);
            pendingNotificationPermissionCall = null;
        }
    }

    private void ensureCallbackRegistered() {
        DeepLockService service = DeepLockService.getInstance();
        if (service != null) {
            service.addPluginCallback(pluginCallback);
        }
    }

        private synchronized FocusShieldManager getFocusShield() {
        FocusShieldManager fsm = FocusShieldBridge.getManager();
        if (fsm == null && getContext() != null) {
            fsm = new FocusShieldManager(getContext().getApplicationContext());
            fsm.setup(getContext().getPackageName(), action -> false, null);
            FocusShieldBridge.initialize(fsm, action -> false);
            Log.i(TAG, "getFocusShield() — lazily initialized fallback FocusShieldManager");
        }
        return fsm;
    }

    @PluginMethod
    public void startDeepLock(PluginCall call) {
        Log.d(TAG, "entering startDeepLock");
        // Fail fast when the accessibility service is not actually bound. A
        // fallback FocusShieldManager can be constructed without it, which used
        // to report success:true while no accessibility event ever arrived —
        // "Deep Lock is on but nothing is blocked". The UI must be told the
        // truth so it can send the user to fix the service.
        if (!DeepLockService.isServiceRunning()) {
            Log.w(TAG, "startDeepLock — accessibility service not bound, refusing to arm");
            JSObject refused = new JSObject();
            refused.put("success", false);
            refused.put("serviceRunning", false);
            refused.put("error", "accessibility_service_not_running");
            call.resolve(refused);
            return;
        }
        ensureCallbackRegistered();
        registerSessionCompletedNotifier();
        FocusShieldManager fsm = getFocusShield();
        if (fsm == null) {
            Log.w(TAG, "startDeepLock — FocusShieldManager is null, returning error");
            JSObject result = new JSObject();
            result.put("success", false);
            result.put("error", "FocusShieldManager not initialized");
            call.resolve(result);
            Log.d(TAG, "exiting startDeepLock — null manager");
            return;
        }

        JSArray whitelistArray = call.getArray("whitelist");
        Set<String> pkgs = new HashSet<>();
        if (whitelistArray != null) {
            for (int i = 0; i < whitelistArray.length(); i++) {
                String pkg = whitelistArray.optString(i, null);
                if (pkg != null) pkgs.add(pkg);
            }
        }

        // Break-blocked packages — blocked even during breaks/pauses
        JSArray breakBlockedArray = call.getArray("breakBlockedPackages");
        Set<String> breakBlockedPkgs = new HashSet<>();
        if (breakBlockedArray != null) {
            for (int i = 0; i < breakBlockedArray.length(); i++) {
                String pkg = breakBlockedArray.optString(i, null);
                if (pkg != null) breakBlockedPkgs.add(pkg);
            }
        }

        String mode = call.getString("mode");
        if (mode == null) mode = "deep-lock";

        Integer durationMinutes = call.getInt("durationMinutes");
        if (durationMinutes == null) durationMinutes = 0;

        Boolean strict = call.getBoolean("strict");
        boolean strictMode = strict != null && strict;

        Log.i(TAG, "[FOCUS_INSTRUMENT] startDeepLock mode=" + mode + " duration=" + durationMinutes + "min pkgs=" + pkgs.size() + " strict=" + strictMode + " breakBlocked=" + breakBlockedPkgs.size());

        final int finalDuration = durationMinutes;
        final boolean finalStrict = strictMode;
        final String finalMode = mode;
        final java.util.concurrent.atomic.AtomicBoolean settled = new java.util.concurrent.atomic.AtomicBoolean(false);
        final Runnable timeout = () -> {
            Log.w(TAG, "startDeepLock — timed out waiting for blocking to arm");
            resolveStart(call, fsm, settled, false, "blocking_not_armed_timeout");
        };
        mainHandler.postDelayed(timeout, 8000);

        // Arm on a worker thread: list persistence hits the Room DB, and the
        // callback must not resolve before the engine is actually ready.
        new Thread(() -> {
            try {
                fsm.startSession(finalDuration, finalMode, pkgs, finalStrict, breakBlockedPkgs, (ready, reason) -> {
                    mainHandler.removeCallbacks(timeout);
                    resolveStart(call, fsm, settled, ready, ready ? null : reason);
                });
            } catch (Throwable t) {
                mainHandler.removeCallbacks(timeout);
                Log.e(TAG, "startDeepLock — startSession threw", t);
                resolveStart(call, fsm, settled, false, "start_failed");
            }
        }, "DeepLock-start").start();
    }

    /** Resolve a startDeepLock call exactly once, after blocking is armed (or has failed). */
    private void resolveStart(PluginCall call, FocusShieldManager fsm,
                              java.util.concurrent.atomic.AtomicBoolean settled,
                              boolean ready, String error) {
        if (!settled.compareAndSet(false, true)) return;
        mainHandler.post(() -> {
            Map<String, Object> snap = fsm.getSessionSnapshot();
            JSObject result = new JSObject();
            result.put("success", ready);
            if (!ready) result.put("error", error == null ? "blocking_not_armed" : error);
            result.put("serviceRunning", true);
            result.put("sessionId", snap.get("sessionId"));
            result.put("startTime", snap.get("startTime"));
            result.put("endTime", snap.get("endTime"));
            call.resolve(result);
            Log.d(TAG, "exiting startDeepLock — success=" + ready + " error=" + error);
        });
    }

    /** Update the permanent always-blocked list (blocked even outside sessions). */
    @PluginMethod
    public void updateAlwaysBlockedList(PluginCall call) {
        Log.d(TAG, "entering updateAlwaysBlockedList");
        FocusShieldManager fsm = getFocusShield();
        if (fsm == null) {
            JSObject result = new JSObject();
            result.put("success", false);
            result.put("error", "FocusShieldManager not initialized");
            call.resolve(result);
            return;
        }
        JSArray arr = call.getArray("packages");
        Set<String> pkgs = new HashSet<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                String pkg = arr.optString(i, null);
                if (pkg != null) pkgs.add(pkg);
            }
        }
        fsm.updateAlwaysBlockedList(pkgs, () -> mainHandler.post(() -> {
            JSObject result = new JSObject();
            result.put("success", true);
            call.resolve(result);
            Log.d(TAG, "exiting updateAlwaysBlockedList — " + pkgs.size() + " packages persisted");
        }));
    }

    /** Enable/disable break-time blocking. */
    @PluginMethod
    public void updateBreakBlockedList(PluginCall call) {
        Log.d(TAG, "entering updateBreakBlockedList");
        FocusShieldManager fsm = getFocusShield();
        if (fsm == null) {
            JSObject fail = new JSObject();
            fail.put("success", false);
            fail.put("error", "FocusShieldManager not initialized");
            call.resolve(fail);
            return;
        }
        JSArray arr = call.getArray("packages");
        java.util.HashSet<String> pkgs = new java.util.HashSet<>();
        if (arr != null) {
            try {
                for (Object o : arr.toList()) {
                    if (o != null) pkgs.add(String.valueOf(o));
                }
            } catch (Exception e) {
                Log.e(TAG, "updateBreakBlockedList parse error", e);
            }
        }
        fsm.updateBreakBlockedList(pkgs, () -> mainHandler.post(() -> {
            JSObject result = new JSObject();
            result.put("success", true);
            call.resolve(result);
            Log.d(TAG, "exiting updateBreakBlockedList — " + pkgs.size() + " packages persisted");
        }));
    }

    /** Update the session-only blocked list (blocked during focus sessions, allowed outside). */
    @PluginMethod
    public void updateSessionBlockedList(PluginCall call) {
        Log.d(TAG, "entering updateSessionBlockedList");
        FocusShieldManager fsm = getFocusShield();
        if (fsm == null) {
            JSObject fail = new JSObject();
            fail.put("success", false);
            fail.put("error", "FocusShieldManager not initialized");
            call.resolve(fail);
            return;
        }
        JSArray arr = call.getArray("packages");
        java.util.HashSet<String> pkgs = new java.util.HashSet<>();
        if (arr != null) {
            try {
                for (Object o : arr.toList()) {
                    if (o != null) pkgs.add(String.valueOf(o));
                }
            } catch (Exception e) {
                Log.e(TAG, "updateSessionBlockedList parse error", e);
            }
        }
        fsm.updateSessionBlockedList(pkgs, () -> mainHandler.post(() -> {
            JSObject result = new JSObject();
            result.put("success", true);
            call.resolve(result);
            Log.d(TAG, "exiting updateSessionBlockedList — " + pkgs.size() + " packages persisted");
        }));
    }

    @PluginMethod
    public void setContentFilters(PluginCall call) {
        Log.d(TAG, "entering setContentFilters");
        FocusShieldManager fsm = getFocusShield();
        JSObject result = new JSObject();
        if (fsm == null) {
            Log.w(TAG, "setContentFilters — FocusShieldManager null (accessibility service not running)");
            result.put("success", false);
            result.put("error", "Accessibility service is not running");
            call.resolve(result);
            return;
        }
        boolean shorts = Boolean.TRUE.equals(call.getBoolean("blockShorts", false));
        boolean reels = Boolean.TRUE.equals(call.getBoolean("blockReels", false));
        boolean tiktok = Boolean.TRUE.equals(call.getBoolean("blockTikTok", false));
        boolean snapchat = Boolean.TRUE.equals(call.getBoolean("blockSnapchat", false));
        boolean studyMode = Boolean.TRUE.equals(call.getBoolean("youtubeStudyMode", false));
        boolean adult = Boolean.TRUE.equals(call.getBoolean("blockAdultUrls", false));
        Integer allowanceRaw = call.getInt("shortFormAllowance", 1);
        int allowance = allowanceRaw == null ? 1 : allowanceRaw;

        Set<String> websites = new HashSet<>();
        JSArray sites = call.getArray("blockedWebsites");
        if (sites != null) {
            try {
                for (Object o : sites.toList()) {
                    if (o != null) websites.add(String.valueOf(o));
                }
            } catch (Exception e) {
                Log.e(TAG, "setContentFilters parse error (blockedWebsites)", e);
            }
        }

        fsm.setContentFilters(shorts, reels, adult, websites, allowance, tiktok, snapchat, studyMode);
        result.put("success", true);
        result.put("serviceRunning", DeepLockService.isServiceRunning());
        call.resolve(result);
        Log.d(TAG, "exiting setContentFilters — shorts=" + shorts + " reels=" + reels
                + " tiktok=" + tiktok + " snapchat=" + snapchat + " studyMode=" + studyMode
                + " adult=" + adult + " websites=" + websites.size()
                + " shortFormAllowance=" + allowance
                + " serviceRunning=" + DeepLockService.isServiceRunning());
    }

    /** Read back the filters the engine is actually enforcing (diagnostics). */
    @PluginMethod
    public void getBreakBlockedList(PluginCall call) {
        Log.d(TAG, "entering getBreakBlockedList");
        FocusShieldManager fsm = getFocusShield();
        if (fsm == null) {
            JSObject result = new JSObject();
            result.put("packages", new JSArray());
            call.resolve(result);
            return;
        }
        new Thread(() -> {
            try {
                android.content.Context ctx = getContext();
                com.jainam.studentos.focusshield.data.AppDatabase db =
                    com.jainam.studentos.focusshield.data.AppDatabase.getDatabase(ctx);
                java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                final JSArray[] arr = {new JSArray()};
                kotlinx.coroutines.BuildersKt.launch(
                    kotlinx.coroutines.GlobalScope.INSTANCE,
                    kotlinx.coroutines.Dispatchers.getIO(),
                    kotlinx.coroutines.CoroutineStart.DEFAULT,
                    (scope, cont) -> {
                        try {
                            java.util.List<com.jainam.studentos.focusshield.data.AppCategory> cats =
                                kotlinx.coroutines.BuildersKt.runBlocking(
                                    kotlin.coroutines.EmptyCoroutineContext.INSTANCE,
                                    (s, c) -> db.appCategoryDao().getBreakBlockedCategories(c)
                                );
                            JSArray out = new JSArray();
                            for (com.jainam.studentos.focusshield.data.AppCategory cat : cats) {
                                out.put(cat.getPackageName());
                            }
                            arr[0] = out;
                        } catch (Exception e) {
                            Log.e(TAG, "getBreakBlockedList DB read error", e);
                        } finally {
                            latch.countDown();
                        }
                        return kotlin.Unit.INSTANCE;
                    }
                );
                latch.await(3, java.util.concurrent.TimeUnit.SECONDS);
                JSObject result = new JSObject();
                result.put("packages", arr[0]);
                call.resolve(result);
            } catch (Exception e) {
                Log.e(TAG, "getBreakBlockedList error", e);
                JSObject result = new JSObject();
                result.put("packages", new JSArray());
                call.resolve(result);
            }
        }, "getBreakBlockedList").start();
        Log.d(TAG, "exiting getBreakBlockedList — async");
    }

    @PluginMethod
    public void getContentFilters(PluginCall call) {
        FocusShieldManager fsm = getFocusShield();
        boolean serviceRunning = DeepLockService.isServiceRunning();
        JSObject result = new JSObject();
        if (fsm == null) {
            result.put("success", false);
            result.put("serviceRunning", serviceRunning);
            result.put("blockShorts", false);
            result.put("blockReels", false);
            result.put("blockTikTok", false);
            result.put("blockSnapchat", false);
            result.put("youtubeStudyMode", false);
            result.put("blockAdultUrls", false);
            result.put("blockedWebsites", new JSArray());
            result.put("shortFormAllowance", 1);
            result.put("checkInEnabled", false);
            call.resolve(result);
            return;
        }

        Map<String, Object> state = fsm.getContentFilters();
        JSArray websites = new JSArray();
        Object rawSites = state.get("blockedWebsites");
        if (rawSites instanceof List) {
            for (Object o : (List<?>) rawSites) websites.put(String.valueOf(o));
        }

        result.put("success", true);
        // The settings are always persisted; this tells the UI whether anything
        // is actually being enforced right now.
        result.put("serviceRunning", serviceRunning);
        result.put("blockShorts", Boolean.TRUE.equals(state.get("blockShorts")));
        result.put("blockReels", Boolean.TRUE.equals(state.get("blockReels")));
        result.put("blockTikTok", Boolean.TRUE.equals(state.get("blockTikTok")));
        result.put("blockSnapchat", Boolean.TRUE.equals(state.get("blockSnapchat")));
        result.put("youtubeStudyMode", Boolean.TRUE.equals(state.get("youtubeStudyMode")));
        result.put("blockAdultUrls", Boolean.TRUE.equals(state.get("blockAdultUrls")));
        result.put("blockedWebsites", websites);
        Object allowance = state.get("shortFormAllowance");
        result.put("shortFormAllowance", allowance instanceof Number ? ((Number) allowance).intValue() : 1);
        result.put("checkInEnabled", Boolean.TRUE.equals(state.get("checkInEnabled")));
        call.resolve(result);
    }

    @PluginMethod
    public void setBreakBlockingEnabled(PluginCall call) {
        Log.d(TAG, "entering setBreakBlockingEnabled");
        FocusShieldManager fsm = getFocusShield();
        Boolean enabled = call.getBoolean("enabled", false);
        if (fsm != null) {
            fsm.setBreakBlockingEnabled(Boolean.TRUE.equals(enabled));
        }
        JSObject result = new JSObject();
        result.put("success", true);
        call.resolve(result);
        Log.d(TAG, "exiting setBreakBlockingEnabled — enabled=" + enabled);
    }

    /** Enable/disable YouTube Study Mode (gate the Home fee; search + videos stay usable). */
    @PluginMethod
    public void setYoutubeStudyMode(PluginCall call) {
        Log.d(TAG, "entering setYoutubeStudyMode");
        FocusShieldManager fsm = getFocusShield();
        Boolean enabled = call.getBoolean("enabled", false);
        if (fsm != null) {
            fsm.setYoutubeStudyMode(Boolean.TRUE.equals(enabled));
        }
        JSObject result = new JSObject();
        result.put("success", true);
        call.resolve(result);
        Log.d(TAG, "exiting setYoutubeStudyMode — enabled=" + enabled);
    }

    /** Open the in-app distraction-free Study YouTube player (WebView). */
    @PluginMethod
    public void openStudyYouTube(PluginCall call) {
        JSObject result = new JSObject();
        try {
            android.content.Context ctx = getContext();
            Intent intent = new Intent(ctx, StudyYouTubeActivity.class);
            if (!(ctx instanceof android.app.Activity)) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            ctx.startActivity(intent);
            result.put("success", true);
        } catch (Exception e) {
            Log.e(TAG, "openStudyYouTube failed", e);
            result.put("success", false);
            result.put("error", String.valueOf(e.getMessage()));
        }
        call.resolve(result);
    }

    /**
     * Enable/disable the 10-minute "Are you on track?" check-in. Driven by the
     * Motivation Engine toggle in the web app; only fires outside focus sessions.
     */
    @PluginMethod
    public void setCheckInEnabled(PluginCall call) {
        Log.d(TAG, "entering setCheckInEnabled");
        FocusShieldManager fsm = getFocusShield();
        Boolean enabled = call.getBoolean("enabled", false);
        if (fsm != null) {
            fsm.setCheckInEnabled(Boolean.TRUE.equals(enabled));
        }
        JSObject result = new JSObject();
        result.put("success", true);
        call.resolve(result);
        Log.d(TAG, "exiting setCheckInEnabled — enabled=" + enabled);
    }

    /** Get the current always-blocked package list. */
    @PluginMethod
    public void getAlwaysBlockedList(PluginCall call) {
        Log.d(TAG, "entering getAlwaysBlockedList");
        FocusShieldManager fsm = getFocusShield();
        if (fsm == null) {
            JSObject result = new JSObject();
            result.put("packages", new JSArray());
            call.resolve(result);
            return;
        }
        // Run on background thread since it's a suspend function bridged via coroutine
        new Thread(() -> {
            try {
                // Use kotlinx.coroutines.BuildersKt.runBlocking equivalent via scope
                // We read from DB directly here for simplicity
                android.content.Context ctx = getContext();
                com.jainam.studentos.focusshield.data.AppDatabase db =
                    com.jainam.studentos.focusshield.data.AppDatabase.getDatabase(ctx);
                // Use a latch to bridge coroutines
                java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                final JSArray[] arr = {new JSArray()};
                kotlinx.coroutines.BuildersKt.launch(
                    kotlinx.coroutines.GlobalScope.INSTANCE,
                    kotlinx.coroutines.Dispatchers.getIO(),
                    kotlinx.coroutines.CoroutineStart.DEFAULT,
                    (scope, cont) -> {
                        try {
                            java.util.List<com.jainam.studentos.focusshield.data.AppCategory> cats =
                                kotlinx.coroutines.BuildersKt.runBlocking(
                                    kotlin.coroutines.EmptyCoroutineContext.INSTANCE,
                                    (s, c) -> db.appCategoryDao().getAlwaysBlockedOnly(c)
                                );
                            JSArray out = new JSArray();
                            for (com.jainam.studentos.focusshield.data.AppCategory cat : cats) {
                                out.put(cat.getPackageName());
                            }
                            arr[0] = out;
                        } catch (Exception e) {
                            Log.e(TAG, "getAlwaysBlockedList DB read error", e);
                        } finally {
                            latch.countDown();
                        }
                        return kotlin.Unit.INSTANCE;
                    }
                );
                latch.await(3, java.util.concurrent.TimeUnit.SECONDS);
                JSObject result = new JSObject();
                result.put("packages", arr[0]);
                call.resolve(result);
            } catch (Exception e) {
                Log.e(TAG, "getAlwaysBlockedList error", e);
                JSObject result = new JSObject();
                result.put("packages", new JSArray());
                call.resolve(result);
            }
        }, "getAlwaysBlockedList").start();
        Log.d(TAG, "exiting getAlwaysBlockedList — async");
    }

    /** Read the session-only blocked list (CategoryType.BLOCKED rows). */
    @PluginMethod
    public void getSessionBlockedList(PluginCall call) {
        Log.d(TAG, "entering getSessionBlockedList");
        FocusShieldManager fsm = getFocusShield();
        if (fsm == null) {
            JSObject result = new JSObject();
            result.put("packages", new JSArray());
            result.put("success", false);
            call.resolve(result);
            return;
        }
        new Thread(() -> {
            try {
                android.content.Context ctx = getContext();
                com.jainam.studentos.focusshield.data.AppDatabase db =
                    com.jainam.studentos.focusshield.data.AppDatabase.getDatabase(ctx);
                java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                final JSArray[] arr = {new JSArray()};
                final boolean[] ok = {false};
                kotlinx.coroutines.BuildersKt.launch(
                    kotlinx.coroutines.GlobalScope.INSTANCE,
                    kotlinx.coroutines.Dispatchers.getIO(),
                    kotlinx.coroutines.CoroutineStart.DEFAULT,
                    (scope, cont) -> {
                        try {
                            java.util.List<com.jainam.studentos.focusshield.data.AppCategory> cats =
                                kotlinx.coroutines.BuildersKt.runBlocking(
                                    kotlin.coroutines.EmptyCoroutineContext.INSTANCE,
                                    (s, c) -> db.appCategoryDao().getBlockedOnly(c)
                                );
                            JSArray out = new JSArray();
                            for (com.jainam.studentos.focusshield.data.AppCategory cat : cats) {
                                out.put(cat.getPackageName());
                            }
                            arr[0] = out;
                            ok[0] = true;
                        } catch (Exception e) {
                            Log.e(TAG, "getSessionBlockedList DB read error", e);
                        } finally {
                            latch.countDown();
                        }
                        return kotlin.Unit.INSTANCE;
                    }
                );
                latch.await(3, java.util.concurrent.TimeUnit.SECONDS);
                JSObject result = new JSObject();
                result.put("packages", arr[0]);
                result.put("success", ok[0]);
                call.resolve(result);
            } catch (Exception e) {
                Log.e(TAG, "getSessionBlockedList error", e);
                JSObject result = new JSObject();
                result.put("packages", new JSArray());
                result.put("success", false);
                call.resolve(result);
            }
        }, "getSessionBlockedList").start();
        Log.d(TAG, "exiting getSessionBlockedList — async");
    }

    @PluginMethod
    public void stopDeepLock(PluginCall call) {
        Log.d(TAG, "entering stopDeepLock");
        ensureCallbackRegistered();
        JSObject result = new JSObject();
        FocusShieldManager fsm = getFocusShield();
        if (fsm != null) {
            Log.i(TAG, "stopDeepLock — ending session");
            fsm.endSession();
            result.put("success", true);
        } else {
            // Do NOT report success on a null manager — that leaves native state
            // (DataStore ACTIVE) stale and re-arms blocking on next connect.
            Log.w(TAG, "stopDeepLock — FocusShieldManager is null, reporting failure");
            result.put("success", false);
            result.put("error", "FocusShieldManager not initialized");
        }
        call.resolve(result);
        Log.d(TAG, "exiting stopDeepLock");
    }

    @PluginMethod
    public void setMode(PluginCall call) {
        Log.d(TAG, "entering setMode");
        FocusShieldManager fsm = getFocusShield();
        if (fsm == null) {
            Log.w(TAG, "setMode — FocusShieldManager is null");
            call.resolve();
            Log.d(TAG, "exiting setMode — null manager");
            return;
        }

        String mode = call.getString("mode");
        if (mode == null) mode = "deep-lock";
        Log.i(TAG, "setMode — mode=" + mode);
        fsm.setMode(mode);
        call.resolve();
        Log.d(TAG, "exiting setMode");
    }

    @PluginMethod
    public void updateWhitelist(PluginCall call) {
        Log.d(TAG, "entering updateWhitelist");
        FocusShieldManager fsm = getFocusShield();
        if (fsm == null) {
            Log.w(TAG, "updateWhitelist — FocusShieldManager is null");
            JSObject result = new JSObject();
            result.put("success", false);
            result.put("error", "FocusShieldManager not initialized");
            call.resolve(result);
            Log.d(TAG, "exiting updateWhitelist — null manager");
            return;
        }

        JSArray whitelistArray = call.getArray("whitelist");
        Set<String> pkgs = new HashSet<>();
        if (whitelistArray != null) {
            for (int i = 0; i < whitelistArray.length(); i++) {
                String pkg = whitelistArray.optString(i, null);
                if (pkg != null) pkgs.add(pkg);
            }
        }

        fsm.updateWhitelist(pkgs, () -> mainHandler.post(() -> {
            JSObject result = new JSObject();
            result.put("success", true);
            call.resolve(result);
            Log.d(TAG, "exiting updateWhitelist — persisted");
        }));
    }

    @PluginMethod
    public void isAccessibilityServiceEnabled(PluginCall call) {
        Log.d(TAG, "entering isAccessibilityServiceEnabled");
        AccessibilityManager am = (AccessibilityManager) getContext()
                .getSystemService(Context.ACCESSIBILITY_SERVICE);
        List<android.accessibilityservice.AccessibilityServiceInfo> enabledServices =
                am.getEnabledAccessibilityServiceList(
                        android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK);

        boolean found = false;
        for (android.accessibilityservice.AccessibilityServiceInfo service : enabledServices) {
            if (service.getResolveInfo().serviceInfo.packageName.equals(getContext().getPackageName())) {
                found = true;
                break;
            }
        }

        Log.i(TAG, "isAccessibilityServiceEnabled — found=" + found);
        JSObject result = new JSObject();
        result.put("enabled", found);
        call.resolve(result);
        Log.d(TAG, "exiting isAccessibilityServiceEnabled");
    }

    @PluginMethod
    public void openAccessibilitySettings(PluginCall call) {
        Log.d(TAG, "entering openAccessibilitySettings");
        SharedPreferences prefs = getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (!"true".equals(prefs.getString(KEY_DISCLOSURE, ""))) {
            Log.w(TAG, "openAccessibilitySettings — BLOCKED: prominent disclosure not accepted");
            call.reject("Prominent disclosure not yet accepted");
            return;
        }
        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            getContext().startActivity(intent);
            Log.i(TAG, "openAccessibilitySettings — intent fired");
            call.resolve();
        } catch (Exception e) {
            Log.e(TAG, "openAccessibilitySettings — FAILED: " + e.getMessage());
            call.reject("Failed to open accessibility settings: " + e.getMessage());
        }
        Log.d(TAG, "exiting openAccessibilitySettings");
    }

    @PluginMethod
    public void setProminentDisclosureAccepted(PluginCall call) {
        Boolean accepted = call.getBoolean("accepted", false);
        SharedPreferences.Editor editor = getContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit();
        if (Boolean.TRUE.equals(accepted)) {
            editor.putString(KEY_DISCLOSURE, "true");
        } else {
            editor.remove(KEY_DISCLOSURE);
        }
        editor.apply();
        call.resolve();
    }

    @PluginMethod
    public void clearAllData(PluginCall call) {
        // TODO: This method clears SharedPreferences but Zustand data lives in
        // WebView localStorage, not SharedPreferences. The prefs2.clear() call
        // does not actually wipe the app's task/habit/progress data. The JS-side
        // clearAllData in settings.tsx (localStorage.removeItem) works correctly.
        // To fix: use Capacitor WebView data clearing APIs or bridge to JS clear.
        Log.d(TAG, "entering clearAllData");
        try {
            android.content.Context ctx = getContext();
            // Clear all SharedPreferences (Zustand persistence, etc.)
            android.content.SharedPreferences prefs = ctx.getSharedPreferences("CapacitorStorage", android.content.Context.MODE_PRIVATE);
            prefs.edit().clear().apply();
            android.content.SharedPreferences prefs2 = ctx.getSharedPreferences("student-os-storage-v3", android.content.Context.MODE_PRIVATE);
            prefs2.edit().clear().apply();
            // Clear WebView data
            android.webkit.WebViewDatabase.getInstance(ctx).clearHttpAuthUsernamePassword();
            call.resolve();
        } catch (Exception e) {
            Log.e(TAG, "clearAllData — FAILED: " + e.getMessage());
            call.reject("Failed to clear local data: " + e.getMessage());
        }
        Log.d(TAG, "exiting clearAllData");
    }

    @PluginMethod
    public void requestScreenPinning(PluginCall call) {
        Log.d(TAG, "entering requestScreenPinning");
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            Log.w(TAG, "requestScreenPinning — API < 21");
            JSObject result = new JSObject();
            result.put("success", false);
            result.put("error", "Screen pinning requires API 21+");
            call.resolve(result);
            return;
        }
        if (getActivity() == null) {
            Log.w(TAG, "requestScreenPinning — Activity is null");
            JSObject result = new JSObject();
            result.put("success", false);
            result.put("error", "Activity not available");
            call.resolve(result);
            return;
        }
        try {
            getActivity().startLockTask();
            Log.i(TAG, "requestScreenPinning — startLockTask OK");
            JSObject result = new JSObject();
            result.put("success", true);
            call.resolve(result);
        } catch (Exception e) {
            Log.e(TAG, "requestScreenPinning — FAILED: " + e.getMessage());
            JSObject result = new JSObject();
            result.put("success", false);
            result.put("error", e.getMessage());
            call.resolve(result);
        }
        Log.d(TAG, "exiting requestScreenPinning");
    }

    @PluginMethod
    public void getCurrentForegroundApp(PluginCall call) {
        Log.d(TAG, "entering getCurrentForegroundApp");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            try {
                android.app.usage.UsageStatsManager usm = (android.app.usage.UsageStatsManager)
                        getContext().getSystemService(Context.USAGE_STATS_SERVICE);
                long time = System.currentTimeMillis();
                List<android.app.usage.UsageStats> appList = usm.queryUsageStats(
                        android.app.usage.UsageStatsManager.INTERVAL_DAILY, time - 1000 * 60, time);
                if (appList != null && !appList.isEmpty()) {
                    android.app.usage.UsageStats recentStats = null;
                    for (android.app.usage.UsageStats stats : appList) {
                        if (recentStats == null
                                || stats.getLastTimeUsed() > recentStats.getLastTimeUsed()) {
                            recentStats = stats;
                        }
                    }
                    String packageName = recentStats != null ? recentStats.getPackageName() : "";
                    Log.i(TAG, "getCurrentForegroundApp — pkg=" + packageName);
                    JSObject result = new JSObject();
                    result.put("packageName", packageName);
                    call.resolve(result);
                    Log.d(TAG, "exiting getCurrentForegroundApp");
                    return;
                }
            } catch (SecurityException e) {
                Log.w(TAG, "getCurrentForegroundApp — SecurityException: " + e.getMessage());
            }
        }
        JSObject result = new JSObject();
        result.put("packageName", "");
        call.resolve(result);
        Log.d(TAG, "exiting getCurrentForegroundApp — empty result");
    }

    @PluginMethod
    public void openAppManagement(PluginCall call) {
        Log.d(TAG, "entering openAppManagement");
        Intent intent = new Intent(getContext(), AllowedAppsActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            getContext().startActivity(intent);
            Log.i(TAG, "openAppManagement — activity launched");
            JSObject result = new JSObject();
            result.put("success", true);
            call.resolve(result);
        } catch (Exception e) {
            Log.e(TAG, "openAppManagement — FAILED: " + e.getMessage());
            JSObject result = new JSObject();
            result.put("success", false);
            result.put("error", e.getMessage());
            call.resolve(result);
        }
        Log.d(TAG, "exiting openAppManagement");
    }

    /**
     * Launchable apps for the Deep Lock onboarding checklist. Reads straight
     * from PackageManager (no Room / coroutine bridge) because this is a
     * read-only one-shot list. System apps are flagged so the UI can hide them
     * or show them separately.
     */
    @PluginMethod
    public void getInstalledApps(PluginCall call) {
        Log.d(TAG, "entering getInstalledApps");
        // Icons are opt-in: the picker wants them, the onboarding checklist does
        // not (base64 payload for ~100 apps is a few hundred KB).
        final boolean withIcons = Boolean.TRUE.equals(call.getBoolean("withIcons", false));
        new Thread(() -> {
            JSArray out = new JSArray();
            try {
                android.content.pm.PackageManager pm = getContext().getPackageManager();
                Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
                mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
                List<android.content.pm.ResolveInfo> resolveInfos;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    resolveInfos = pm.queryIntentActivities(
                        mainIntent, android.content.pm.PackageManager.ResolveInfoFlags.of(0L));
                } else {
                    @SuppressWarnings("deprecation")
                    List<android.content.pm.ResolveInfo> legacy = pm.queryIntentActivities(mainIntent, 0);
                    resolveInfos = legacy;
                }
                String self = getContext().getPackageName();
                Set<String> seen = new HashSet<>();
                List<JSObject> list = new java.util.ArrayList<>();
                if (resolveInfos != null) {
                    for (android.content.pm.ResolveInfo ri : resolveInfos) {
                        if (ri.activityInfo == null) continue;
                        String pkg = ri.activityInfo.packageName;
                        if (pkg == null || pkg.isEmpty() || pkg.equals(self) || !seen.add(pkg)) continue;
                        CharSequence labelCs = ri.loadLabel(pm);
                        String label = labelCs != null ? labelCs.toString() : pkg;
                        boolean isSystem = ri.activityInfo.applicationInfo != null &&
                            (ri.activityInfo.applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0;
                        JSObject app = new JSObject();
                        app.put("packageName", pkg);
                        app.put("appName", label);
                        app.put("isSystemApp", isSystem);
                        if (withIcons) {
                            String icon = encodeAppIcon(ri, pm);
                            if (icon != null) app.put("icon", icon);
                        }
                        list.add(app);
                    }
                }
                list.sort((a, b) -> {
                    String la = String.valueOf(a.opt("appName"));
                    String lb = String.valueOf(b.opt("appName"));
                    return la.compareToIgnoreCase(lb);
                });
                for (JSObject app : list) out.put(app);
            } catch (Exception e) {
                Log.e(TAG, "getInstalledApps error", e);
            }
            JSObject result = new JSObject();
            result.put("apps", out);
            call.resolve(result);
        }, "getInstalledApps").start();
        Log.d(TAG, "exiting getInstalledApps — async");
    }

    /**
     * Render a launcher icon to a compact base64 WebP data URI (~40dp). Returns
     * null when the icon cannot be loaded; the JS side falls back to a letter
     * avatar, so a missing icon never breaks the list.
     */
    private String encodeAppIcon(android.content.pm.ResolveInfo ri, android.content.pm.PackageManager pm) {
        try {
            android.graphics.drawable.Drawable icon = ri.loadIcon(pm);
            if (icon == null) return null;
            float density = getContext().getResources().getDisplayMetrics().density;
            int px = Math.max(48, Math.round(40 * density));
            android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                px, px, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(bmp);
            icon.setBounds(0, 0, px, px);
            icon.draw(canvas);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            android.graphics.Bitmap.CompressFormat format =
                android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R
                    ? android.graphics.Bitmap.CompressFormat.WEBP_LOSSY
                    : android.graphics.Bitmap.CompressFormat.WEBP;
            bmp.compress(format, 85, bos);
            bmp.recycle();
            String b64 = android.util.Base64.encodeToString(
                bos.toByteArray(), android.util.Base64.NO_WRAP);
            return "data:image/webp;base64," + b64;
        } catch (Exception e) {
            return null;
        }
    }

    @PluginMethod
    public void getOnboardingConfig(PluginCall call) {
        Log.d(TAG, "entering getOnboardingConfig");
        Context ctx = getContext();
        JSObject config = new JSObject();
        java.util.concurrent.atomic.AtomicInteger remaining = new java.util.concurrent.atomic.AtomicInteger(4);

        OnboardingHelper.isOnboardingCompletedAsync(ctx, completed -> {
            config.put("onboardingCompleted", completed);
            if (remaining.decrementAndGet() == 0) {
                Log.i(TAG, "getOnboardingConfig — completed=" + config.getBool("onboardingCompleted") + " theme=" + config.getString("theme"));
                call.resolve(config);
                Log.d(TAG, "exiting getOnboardingConfig");
            }
        });

        OnboardingHelper.readPlannerIntegrationAsync(ctx, integration -> {
            config.put("plannerIntegration", integration);
            if (remaining.decrementAndGet() == 0) {
                Log.i(TAG, "getOnboardingConfig — completed=" + config.getBool("onboardingCompleted") + " theme=" + config.getString("theme"));
                call.resolve(config);
                Log.d(TAG, "exiting getOnboardingConfig");
            }
        });

        OnboardingHelper.readThemeAsync(ctx, theme -> {
            config.put("theme", theme);
            if (remaining.decrementAndGet() == 0) {
                Log.i(TAG, "getOnboardingConfig — completed=" + config.getBool("onboardingCompleted") + " theme=" + config.getString("theme"));
                call.resolve(config);
                Log.d(TAG, "exiting getOnboardingConfig");
            }
        });

        OnboardingHelper.readAccentColorAsync(ctx, accent -> {
            config.put("accentColor", accent);
            if (remaining.decrementAndGet() == 0) {
                Log.i(TAG, "getOnboardingConfig — completed=" + config.getBool("onboardingCompleted") + " theme=" + config.getString("theme"));
                call.resolve(config);
                Log.d(TAG, "exiting getOnboardingConfig");
            }
        });
    }

    @PluginMethod
    public void getTheme(PluginCall call) {
        Log.d(TAG, "entering getTheme");
        Context ctx = getContext();
        JSObject result = new JSObject();
        java.util.concurrent.atomic.AtomicInteger remaining = new java.util.concurrent.atomic.AtomicInteger(2);

        OnboardingHelper.readThemeAsync(ctx, theme -> {
            result.put("theme", theme);
            if (remaining.decrementAndGet() == 0) {
                Log.i(TAG, "getTheme — theme=" + result.getString("theme") + " accent=" + result.getString("accentColor"));
                call.resolve(result);
                Log.d(TAG, "exiting getTheme");
            }
        });

        OnboardingHelper.readAccentColorAsync(ctx, accent -> {
            result.put("accentColor", accent);
            if (remaining.decrementAndGet() == 0) {
                Log.i(TAG, "getTheme — theme=" + result.getString("theme") + " accent=" + result.getString("accentColor"));
                call.resolve(result);
                Log.d(TAG, "exiting getTheme");
            }
        });
    }

    @PluginMethod
    public void setOnboardingComplete(PluginCall call) {
        Log.d(TAG, "entering setOnboardingComplete");
        Boolean complete = call.getBoolean("complete", true);
        if (complete) {
            OnboardingHelper.setOnboardingCompleted(getContext());
            Log.i(TAG, "setOnboardingComplete — marked complete");
        }
        JSObject result = new JSObject();
        result.put("success", true);
        call.resolve(result);
        Log.d(TAG, "exiting setOnboardingComplete");
    }

    @PluginMethod
    public void setTheme(PluginCall call) {
        Log.d(TAG, "entering setTheme");
        String theme = call.getString("theme");
        String accent = call.getString("accentColor");
        Log.i(TAG, "setTheme — theme=" + theme + " accent=" + accent);
        if (theme != null) OnboardingHelper.saveTheme(getContext(), theme);
        if (accent != null) OnboardingHelper.saveAccentColor(getContext(), accent);
        JSObject result = new JSObject();
        result.put("success", true);
        call.resolve(result);
        Log.d(TAG, "exiting setTheme");
    }

    @PluginMethod
    public void pauseDeepLock(PluginCall call) {
        Log.d(TAG, "entering pauseDeepLock");
        FocusShieldManager fsm = getFocusShield();
        JSObject result = new JSObject();
        if (fsm != null) {
            Log.i(TAG, "pauseDeepLock — pausing session");
            fsm.pauseSession();
            Map<String, Object> snap = fsm.getSessionSnapshot();
            result.put("success", true);
            result.put("remainingMs", (Long) snap.get("remainingMs"));
        } else {
            Log.w(TAG, "pauseDeepLock — FocusShieldManager is null");
            result.put("success", false);
        }
        call.resolve(result);
        Log.d(TAG, "exiting pauseDeepLock");
    }

    @PluginMethod
    public void resumeDeepLock(PluginCall call) {
        Log.d(TAG, "entering resumeDeepLock");
        FocusShieldManager fsm = getFocusShield();
        JSObject result = new JSObject();
        if (fsm != null) {
            Log.i(TAG, "resumeDeepLock — resuming session");
            fsm.resumeSession();
            Map<String, Object> snap = fsm.getSessionSnapshot();
            result.put("success", true);
            result.put("endTime", (Long) snap.get("endTime"));
            result.put("totalPausedDuration", (Long) snap.get("totalPausedDuration"));
        } else {
            Log.w(TAG, "resumeDeepLock — FocusShieldManager is null");
            result.put("success", false);
        }
        call.resolve(result);
        Log.d(TAG, "exiting resumeDeepLock");
    }

    @PluginMethod
    public void isNotificationPermissionGranted(PluginCall call) {
        Log.d(TAG, "entering isNotificationPermissionGranted");
        Context ctx = getContext();
        boolean granted = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            android.app.NotificationManager nm = (android.app.NotificationManager)
                    ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            granted = nm.areNotificationsEnabled();
        }
        Log.i(TAG, "isNotificationPermissionGranted — granted=" + granted);
        JSObject result = new JSObject();
        result.put("granted", granted);
        call.resolve(result);
        Log.d(TAG, "exiting isNotificationPermissionGranted");
    }

    @PluginMethod
    public void requestNotificationPermission(PluginCall call) {
        Log.d(TAG, "entering requestNotificationPermission");
        Activity activity = getActivity();
        if (activity == null) {
            Log.w(TAG, "requestNotificationPermission — Activity is null");
            call.reject("Activity not available");
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (android.content.pm.PackageManager.PERMISSION_GRANTED ==
                    androidx.core.app.ActivityCompat.checkSelfPermission(activity, android.Manifest.permission.POST_NOTIFICATIONS)) {
                Log.i(TAG, "requestNotificationPermission — already granted");
                JSObject result = new JSObject();
                result.put("granted", true);
                call.resolve(result);
            } else {
                Log.i(TAG, "requestNotificationPermission — requesting runtime permission");
                pendingNotificationPermissionCall = call;
                androidx.core.app.ActivityCompat.requestPermissions(
                        activity,
                        new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                        9991
                );
            }
        } else {
            JSObject result = new JSObject();
            result.put("granted", true);
            call.resolve(result);
        }
    }

    @PluginMethod
    public void openNotificationSettings(PluginCall call) {
        Log.d(TAG, "entering openNotificationSettings");
        Intent intent;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, getContext().getPackageName());
        } else {
            intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(android.net.Uri.parse("package:" + getContext().getPackageName()));
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            getContext().startActivity(intent);
            Log.i(TAG, "openNotificationSettings — intent fired");
            call.resolve();
        } catch (Exception e) {
            Log.e(TAG, "openNotificationSettings — FAILED: " + e.getMessage());
            call.reject("Failed to open notification settings: " + e.getMessage());
        }
        Log.d(TAG, "exiting openNotificationSettings");
    }

    @PluginMethod
    public void checkFullScreenIntent(PluginCall call) {
        JSObject result = new JSObject();
        boolean granted = true; // Pre-34: permission auto-granted, no toggle exists
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            NotificationManager nm = (NotificationManager) getContext().getSystemService(Context.NOTIFICATION_SERVICE);
            granted = nm != null && nm.canUseFullScreenIntent();
        }
        result.put("granted", granted);
        call.resolve(result);
    }

    @PluginMethod
    public void getDeepLockStatus(PluginCall call) {
        Log.d(TAG, "entering getDeepLockStatus");
        Context ctx = getContext();
        FocusShieldManager fsm = getFocusShield();
        JSObject result = new JSObject();

        // Permission statuses
        try {
            AccessibilityManager am = (AccessibilityManager) ctx.getSystemService(Context.ACCESSIBILITY_SERVICE);
            List<android.accessibilityservice.AccessibilityServiceInfo> enabledServices =
                am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
            boolean found = false;
            for (android.accessibilityservice.AccessibilityServiceInfo service : enabledServices) {
                if (service.getResolveInfo().serviceInfo.packageName.equals(ctx.getPackageName())) {
                    found = true; break;
                }
            }
            result.put("accessibilityEnabled", found);
        } catch (Exception e) { result.put("accessibilityEnabled", false); }

        try {
            android.app.NotificationManager nm = (android.app.NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            boolean granted = true;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                granted = nm.areNotificationsEnabled();
            }
            result.put("notificationPermissionGranted", granted);
        } catch (Exception e) { result.put("notificationPermissionGranted", false); }

        try {
            android.app.NotificationManager nm = (android.app.NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            result.put("dndGranted", nm.isNotificationPolicyAccessGranted());
        } catch (Exception e) { result.put("dndGranted", false); }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                android.os.PowerManager pm = (android.os.PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
                result.put("batteryOptimizationIgnored", pm.isIgnoringBatteryOptimizations(ctx.getPackageName()));
            } else {
                result.put("batteryOptimizationIgnored", true);
            }
        } catch (Exception e) { result.put("batteryOptimizationIgnored", false); }

        try {
            // AppOps check (same path as isUsageAccessGranted). The old
            // queryUsageStats(60s window) returned an empty list on fresh boot or
            // after idle, falsely reporting "Usage Access" as missing even when
            // the permission was granted.
            result.put("usageAccessGranted", isUsageAccessGrantedInternal(ctx));
        } catch (Exception e) {
            result.put("usageAccessGranted", false);
        }

        boolean serviceRunning = DeepLockService.isServiceRunning();
        result.put("serviceRunning", serviceRunning);

        // Session status
        if (fsm != null) {
            boolean sessActive = fsm.isSessionActive();
            String sessState = fsm.getActiveState();
            result.put("sessionActive", sessActive);
            result.put("sessionState", sessState);
            result.put("todayFocusMs", fsm.getTodayFocusMs());
            result.put("todayDistractions", fsm.getTodayDistractions());
            result.put("totalFocusMs", fsm.getTotalFocusMs());

            FocusShieldManager.SessionContext sc = fsm.getSessionContext();
            if (sc != null) {
                JSObject sessionCtx = new JSObject();
                sessionCtx.put("elapsedMinutes", sc.getElapsedMinutes());
                sessionCtx.put("remainingMinutes", sc.getRemainingMinutes());
                sessionCtx.put("totalMinutes", sc.getTotalMinutes());
                sessionCtx.put("interruptionCount", sc.getInterruptionCount());
                sessionCtx.put("streak", sc.getStreak());
                result.put("sessionContext", sessionCtx);
                if (isDebugBuild()) Log.d(TAG, "[FOCUS_INSTRUMENT] getDeepLockStatus active=" + sessActive + " state=" + sessState + " ctx_elapsed=" + sc.getElapsedMinutes() + " remaining=" + sc.getRemainingMinutes() + " total=" + sc.getTotalMinutes());
            } else {
                if (isDebugBuild()) Log.d(TAG, "[FOCUS_INSTRUMENT] getDeepLockStatus active=" + sessActive + " state=" + sessState + " (no sessionContext)");
            }
        } else {
            Log.w(TAG, "[FOCUS_INSTRUMENT] getDeepLockStatus — FocusShieldManager is null, returning defaults");
            result.put("sessionActive", false);
            result.put("sessionState", "idle");
            result.put("todayFocusMs", 0);
            result.put("todayDistractions", 0);
            result.put("totalFocusMs", 0);
        }

        call.resolve(result);
        Log.d(TAG, "exiting getDeepLockStatus");
    }

    @PluginMethod
    public void isServiceRunning(PluginCall call) {
        Log.d(TAG, "entering isServiceRunning");
        boolean running = DeepLockService.isServiceRunning();
        Log.i(TAG, "isServiceRunning — running=" + running);
        JSObject result = new JSObject();
        result.put("running", running);
        call.resolve(result);
        Log.d(TAG, "exiting isServiceRunning");
    }

    @PluginMethod
    public void isUsageAccessGranted(PluginCall call) {
        Log.d(TAG, "entering isUsageAccessGranted");
        boolean granted = isUsageAccessGrantedInternal(getContext());
        Log.i(TAG, "isUsageAccessGranted — granted=" + granted);
        JSObject result = new JSObject();
        result.put("granted", granted);
        call.resolve(result);
        Log.d(TAG, "exiting isUsageAccessGranted");
    }

    /**
     * Reliable PACKAGE_USAGE_STATS check.
     *
     * AppOpsManager is the source of truth — the previous UsageStatsManager query
     * returned an empty list on fresh boot / after idle and reported the permission
     * as missing even when it was granted. The stats query is kept only as a
     * fallback if AppOps itself throws.
     */
    private boolean isUsageAccessGrantedInternal(Context ctx) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                android.app.AppOpsManager appOps = (android.app.AppOpsManager)
                        ctx.getSystemService(Context.APP_OPS_SERVICE);
                if (appOps != null) {
                    int mode;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        mode = appOps.unsafeCheckOpNoThrow(
                                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                                android.os.Process.myUid(),
                                ctx.getPackageName());
                    } else {
                        mode = appOps.checkOpNoThrow(
                                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                                android.os.Process.myUid(),
                                ctx.getPackageName());
                    }
                    return mode == android.app.AppOpsManager.MODE_ALLOWED;
                }
            }
            return false;
        } catch (Exception e) {
            Log.e(TAG, "isUsageAccessGrantedInternal — AppOps FAILED: " + e.getMessage());
            try {
                android.app.usage.UsageStatsManager usm = (android.app.usage.UsageStatsManager)
                        ctx.getSystemService(Context.USAGE_STATS_SERVICE);
                if (usm != null) {
                    long time = System.currentTimeMillis();
                    List<android.app.usage.UsageStats> stats = usm.queryUsageStats(
                            android.app.usage.UsageStatsManager.INTERVAL_DAILY, time - 1000 * 60 * 10, time);
                    return stats != null && !stats.isEmpty();
                }
            } catch (Exception e2) {
                Log.e(TAG, "isUsageAccessGrantedInternal fallback FAILED: " + e2.getMessage());
            }
            return false;
        }
    }

    @PluginMethod
    public void openUsageAccessSettings(PluginCall call) {
        Log.d(TAG, "entering openUsageAccessSettings");
        Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            getContext().startActivity(intent);
            Log.i(TAG, "openUsageAccessSettings — intent fired");
            call.resolve();
        } catch (Exception e) {
            Log.e(TAG, "openUsageAccessSettings — FAILED: " + e.getMessage());
            call.reject("Failed to open Usage Access settings: " + e.getMessage());
        }
        Log.d(TAG, "exiting openUsageAccessSettings");
    }

    // ─── Do Not Disturb ─────────────────────────────────────

    @PluginMethod
    public void isDndAccessGranted(PluginCall call) {
        Log.d(TAG, "entering isDndAccessGranted");
        try {
            android.app.NotificationManager nm = (android.app.NotificationManager)
                    getContext().getSystemService(Context.NOTIFICATION_SERVICE);
            boolean granted = nm.isNotificationPolicyAccessGranted();
            Log.i(TAG, "isDndAccessGranted — granted=" + granted);
            JSObject result = new JSObject();
            result.put("granted", granted);
            call.resolve(result);
        } catch (Exception e) {
            Log.e(TAG, "isDndAccessGranted — FAILED: " + e.getMessage());
            JSObject result = new JSObject();
            result.put("granted", false);
            call.resolve(result);
        }
        Log.d(TAG, "exiting isDndAccessGranted");
    }

    @PluginMethod
    public void openDndSettings(PluginCall call) {
        Log.d(TAG, "entering openDndSettings");
        Intent intent = new Intent(
                android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            getContext().startActivity(intent);
            Log.i(TAG, "openDndSettings — intent fired");
            call.resolve();
        } catch (Exception e) {
            Log.e(TAG, "openDndSettings — FAILED: " + e.getMessage());
            call.reject("Failed to open DND settings: " + e.getMessage());
        }
        Log.d(TAG, "exiting openDndSettings");
    }

    @PluginMethod
    public void enableDnd(PluginCall call) {
        Log.d(TAG, "entering enableDnd");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.app.NotificationManager nm = (android.app.NotificationManager)
                    getContext().getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm.isNotificationPolicyAccessGranted()) {
                nm.setInterruptionFilter(
                        android.app.NotificationManager.INTERRUPTION_FILTER_NONE);
                Log.i(TAG, "enableDnd — DND enabled");
                JSObject result = new JSObject();
                result.put("success", true);
                call.resolve(result);
                Log.d(TAG, "exiting enableDnd — success");
                return;
            } else {
                Log.w(TAG, "enableDnd — notification policy access not granted");
            }
        }
        JSObject result = new JSObject();
        result.put("success", false);
        result.put("error", "Notification policy access not granted");
        call.resolve(result);
        Log.d(TAG, "exiting enableDnd — failed");
    }

    @PluginMethod
    public void disableDnd(PluginCall call) {
        Log.d(TAG, "entering disableDnd");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.app.NotificationManager nm = (android.app.NotificationManager)
                    getContext().getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm.isNotificationPolicyAccessGranted()) {
                nm.setInterruptionFilter(
                        android.app.NotificationManager.INTERRUPTION_FILTER_ALL);
                Log.i(TAG, "disableDnd — DND disabled");
            } else {
                Log.w(TAG, "disableDnd — notification policy access not granted");
            }
        }
        JSObject result = new JSObject();
        result.put("success", true);
        call.resolve(result);
        Log.d(TAG, "exiting disableDnd");
    }

    // ─── Session State ──────────────────────────────────────

    @PluginMethod
    public void getSessionState(PluginCall call) {
        Log.d(TAG, "entering getSessionState");
        FocusShieldManager fsm = getFocusShield();
        JSObject result = new JSObject();
        if (fsm == null) {
            result.put("hasActiveSession", false);
            call.resolve(result);
            return;
        }
        Map<String, Object> snap = fsm.getSessionSnapshot();
        boolean hasSession = snap.get("sessionId") != null;
        result.put("hasActiveSession", hasSession);
        if (hasSession) {
            result.put("sessionId", (String) snap.get("sessionId"));
            result.put("status", (String) snap.get("status"));
            result.put("startTime", (Long) snap.get("startTime"));
            result.put("endTime", (Long) snap.get("endTime"));
            result.put("plannedDuration", (Long) snap.get("plannedDuration"));
            result.put("totalPausedDuration", (Long) snap.get("totalPausedDuration"));
            result.put("version", (Integer) snap.get("version"));
            result.put("remainingMs", (Long) snap.get("remainingMs"));
            result.put("actualDurationMs", (Long) snap.get("actualDurationMs"));
            result.put("streak", (Integer) snap.get("streak"));
            result.put("strict", (Boolean) snap.get("strict"));
        }
        call.resolve(result);
        Log.d(TAG, "exiting getSessionState");
    }

    @PluginMethod
    public void getMonotonicClock(PluginCall call) {
        JSObject result = new JSObject();
        result.put("elapsedRealtime", android.os.SystemClock.elapsedRealtime());
        result.put("currentTimeMillis", System.currentTimeMillis());
        call.resolve(result);
    }

    @PluginMethod
    public void forceComplete(PluginCall call) {
        Log.i(TAG, "forceComplete — diagnostic action");
        FocusShieldManager fsm = getFocusShield();
        if (fsm != null) {
            fsm.forceComplete();
            JSObject result = new JSObject();
            result.put("success", true);
            call.resolve(result);
        } else {
            call.reject("FocusShieldManager not initialized");
        }
    }

    @PluginMethod
    public void forceStop(PluginCall call) {
        Log.i(TAG, "forceStop — diagnostic action");
        FocusShieldManager fsm = getFocusShield();
        if (fsm != null) {
            fsm.cancelSession();
            JSObject result = new JSObject();
            result.put("success", true);
            call.resolve(result);
        } else {
            call.reject("FocusShieldManager not initialized");
        }
    }

    // ─── Battery Optimization ───────────────────────────────

    @PluginMethod
    public void isBatteryOptimizationIgnored(PluginCall call) {
        Log.d(TAG, "entering isBatteryOptimizationIgnored");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.os.PowerManager pm = (android.os.PowerManager)
                    getContext().getSystemService(Context.POWER_SERVICE);
            boolean ignored = pm.isIgnoringBatteryOptimizations(
                    getContext().getPackageName());
            Log.i(TAG, "isBatteryOptimizationIgnored — ignored=" + ignored);
            JSObject result = new JSObject();
            result.put("ignored", ignored);
            call.resolve(result);
        } else {
            Log.i(TAG, "isBatteryOptimizationIgnored — API < 23, returning true");
            JSObject result = new JSObject();
            result.put("ignored", true);
            call.resolve(result);
        }
        Log.d(TAG, "exiting isBatteryOptimizationIgnored");
    }

    @PluginMethod
    public void requestIgnoreBatteryOptimization(PluginCall call) {
        Log.d(TAG, "entering requestIgnoreBatteryOptimization");
        Intent intent = new Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
        intent.setData(android.net.Uri.parse("package:" + getContext().getPackageName()));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            getContext().startActivity(intent);
            Log.i(TAG, "requestIgnoreBatteryOptimization — intent fired");
            call.resolve();
        } catch (Exception e) {
            Log.e(TAG, "requestIgnoreBatteryOptimization — FAILED: " + e.getMessage());
            call.reject("Failed to open battery optimization settings: " + e.getMessage());
        }
        Log.d(TAG, "exiting requestIgnoreBatteryOptimization");
    }

    @PluginMethod
    public void openBatteryOptimizationSettings(PluginCall call) {
        Log.d(TAG, "entering openBatteryOptimizationSettings");
        Intent intent = new Intent(
                android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            getContext().startActivity(intent);
            Log.i(TAG, "openBatteryOptimizationSettings — intent fired");
        } catch (Exception e) {
            Log.e(TAG, "openBatteryOptimizationSettings — FAILED: " + e.getMessage());
        }
        call.resolve();
        Log.d(TAG, "exiting openBatteryOptimizationSettings");
    }
}
