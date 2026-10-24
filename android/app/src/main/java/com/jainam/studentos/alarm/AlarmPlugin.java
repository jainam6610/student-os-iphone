package com.jainam.studentos.alarm;

import android.app.AlarmManager;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "Alarm")
public class AlarmPlugin extends Plugin {

    private static final String TAG = "[AlarmPlugin]";
    private static AlarmPlugin instance;
    private static JSObject activeAlarmData;

    private static final String PREFS_NAME = "student_os_alarm";
    private static final String PREF_ACTIVE_ENTITY_ID = "active_entity_id";
    private static final String PREF_ACTIVE_TITLE = "active_title";
    private static final String PREF_ACTIVE_BODY = "active_body";
    private static final String PREF_ACTIVE_HABIT_ID = "active_habit_id";

    @Override
    public void load() {
        super.load();
        instance = this;
        Log.i(TAG, "AlarmPlugin loaded");
    }

    public static void fireAlarmEvent(String entityId, String title, String body, String habitId) {
        if (instance == null) return;
        JSObject data = new JSObject();
        data.put("entityId", entityId);
        data.put("title", title);
        data.put("body", body);
        data.put("habitId", habitId != null ? habitId : "");
        activeAlarmData = data;
        Context ctx = instance.getContext();
        if (ctx != null) {
            android.content.SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit()
                .putString(PREF_ACTIVE_ENTITY_ID, entityId)
                .putString(PREF_ACTIVE_TITLE, title)
                .putString(PREF_ACTIVE_BODY, body)
                .putString(PREF_ACTIVE_HABIT_ID, habitId != null ? habitId : "")
                .apply();
        }
        instance.notifyListeners("alarmFired", data);
    }

    public static void clearActiveAlarm() {
        activeAlarmData = null;
        if (instance != null) {
            Context ctx = instance.getContext();
            if (ctx != null) {
                ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply();
            }
        }
    }

    @PluginMethod
    public void getActiveAlarm(PluginCall call) {
        JSObject result = new JSObject();
        if (activeAlarmData != null) {
            result.put("active", true);
            result.put("entityId", activeAlarmData.optString("entityId", ""));
            result.put("title", activeAlarmData.optString("title", ""));
            result.put("body", activeAlarmData.optString("body", ""));
            result.put("habitId", activeAlarmData.optString("habitId", ""));
        } else {
            Context ctx = getContext();
            if (ctx != null) {
                android.content.SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                String eid = prefs.getString(PREF_ACTIVE_ENTITY_ID, null);
                if (eid != null) {
                    result.put("active", true);
                    result.put("entityId", eid);
                    result.put("title", prefs.getString(PREF_ACTIVE_TITLE, ""));
                    result.put("body", prefs.getString(PREF_ACTIVE_BODY, ""));
                    result.put("habitId", prefs.getString(PREF_ACTIVE_HABIT_ID, ""));
                    // restore to static for this session
                    activeAlarmData = result;
                } else {
                    result.put("active", false);
                }
            } else {
                result.put("active", false);
            }
        }
        call.resolve(result);
    }

    @PluginMethod
    public void getPendingThought(PluginCall call) {
        JSObject result = new JSObject();
        result.put("found", false);
        Context ctx = getContext();
        if (ctx == null) { call.resolve(result); return; }
        try {
            android.content.SharedPreferences p = ctx.getSharedPreferences("student_os_thoughts", Context.MODE_PRIVATE);
            String title = p.getString("title", null);
            long ts = p.getLong("ts", 0);
            if (title != null && ts > 0) {
                result.put("found", true);
                result.put("title", title);
                result.put("body", p.getString("body", ""));
                result.put("entityId", p.getString("entity_id", ""));
                result.put("firedAtMs", ts);
            }
        } catch (Exception e) {
            Log.w(TAG, "getPendingThought failed: " + e.getMessage());
        }
        call.resolve(result);
    }

    @PluginMethod
    public void clearPendingThought(PluginCall call) {
        Context ctx = getContext();
        if (ctx != null) {
            ctx.getSharedPreferences("student_os_thoughts", Context.MODE_PRIVATE).edit().clear().apply();
        }
        JSObject result = new JSObject();
        result.put("success", true);
        call.resolve(result);
    }

    /**
     * Called by JS after the in-app thought card has been shown, so the
     * 5-minute retry is suppressed and pending state is cleaned up.
     */
    @PluginMethod
    public void markThoughtHandled(PluginCall call) {
        String entityId = call.getString("entityId");
        Context ctx = getContext();
        boolean ok = false;
        if (ctx != null && entityId != null && !entityId.isEmpty()) {
            try {
                ThoughtNotifier.markTouched(ctx, entityId);
                new AlarmScheduler(ctx).cancel(entityId + "-retry");
                ThoughtNotifier.clearPending(ctx);
                NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) nm.cancel(entityId.hashCode());
                ok = true;
            } catch (Exception e) {
                Log.w(TAG, "markThoughtHandled failed: " + e.getMessage());
            }
        }
        JSObject result = new JSObject();
        result.put("success", ok);
        call.resolve(result);
    }

    /**
     * Motivation is notification-only: JS calls this to post a "thought"
     * immediately through the existing ThoughtNotifier (no in-app popup).
     */
    @PluginMethod
    public void postThought(PluginCall call) {
        String title = call.getString("title", "");
        String body = call.getString("body", "");
        String entityId = call.getString("entityId", "thought-" + System.currentTimeMillis());
        Context ctx = getContext();
        boolean ok = false;
        if (ctx != null && title != null && !title.isEmpty()) {
            try {
                ThoughtNotifier.show(ctx, entityId, title, body == null ? "" : body);
                ok = true;
            } catch (Exception e) {
                Log.w(TAG, "postThought failed: " + e.getMessage());
            }
        }
        JSObject result = new JSObject();
        result.put("success", ok);
        call.resolve(result);
    }

    @PluginMethod
    public void snoozeAlarm(PluginCall call) {
        int minutes = call.getInt("minutes", 5);
        Context ctx = getContext();
        if (ctx == null) { call.reject("Context unavailable"); return; }
        clearActiveAlarm();
        try {
            Intent intent = new Intent(ctx, AlarmForegroundService.class);
            intent.setAction(AlarmForegroundService.ACTION_SNOOZE);
            intent.putExtra(AlarmForegroundService.EXTRA_SNOOZE_MINUTES, minutes);
            ctx.startService(intent);
        } catch (Exception e) {
            Log.w(TAG, "startService(ACTION_SNOOZE) failed: " + e.getMessage());
        }
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(2001);
        } catch (Exception e) {
            Log.w(TAG, "NotificationManager.cancel failed: " + e.getMessage());
        }
        JSObject result = new JSObject();
        result.put("success", true);
        call.resolve(result);
    }

    @PluginMethod
    public void scheduleAlarm(PluginCall call) {
        // Use getData() to access raw JSObject for safer number parsing
        JSObject data = call.getData();
        String entityId = data != null ? data.optString("entityId", null) : call.getString("entityId");
        String type = data != null ? data.optString("type", null) : call.getString("type");
        String title = data != null ? data.optString("title", null) : call.getString("title");
        String body = data != null ? data.optString("body", null) : call.getString("body");
        Boolean isRecurring = call.getBoolean("isRecurring", false);
        String timeStr = data != null ? data.optString("timeStr", "") : call.getString("timeStr");
        String sound = data != null ? data.optString("sound", "default") : call.getString("sound", "default");
        Boolean vibration = call.getBoolean("vibration", true);
        String extraJson = data != null ? data.optString("extraJson", "{}") : call.getString("extraJson", "{}");

        long scheduleAtMs = 0;
        Double scheduleAtMsFallback = call.getDouble("scheduleAtMs");
        if (data != null && data.has("scheduleAtMs")) {
            Object raw = data.opt("scheduleAtMs");
            if (raw instanceof Number) {
                scheduleAtMs = ((Number) raw).longValue();
            }
        } else if (scheduleAtMsFallback != null) {
            scheduleAtMs = scheduleAtMsFallback.longValue();
        } else {
        }

        if (scheduleAtMs <= 0) {
            String rawVal = data != null ? String.valueOf(data.opt("scheduleAtMs")) : "null";
            call.reject("scheduleAtMs is required (raw=" + rawVal + ")");
            return;
        }

        if (entityId == null) { call.reject("entityId is required"); return; }
        if (title == null) { call.reject("title is required"); return; }

        Context ctx = getContext();
        if (ctx == null) {
            call.reject("Context unavailable");
            return;
        }

        AlarmData alarmData = new AlarmData(
            entityId,
            type != null ? type : "habit",
            title,
            body != null ? body : "",
            scheduleAtMs,
            isRecurring,
            timeStr != null ? timeStr : "",
            sound,
            vibration,
            extraJson
        );

        AlarmScheduler scheduler = new AlarmScheduler(ctx);
        scheduler.schedule(alarmData);

        JSObject result = new JSObject();
        result.put("success", true);
        call.resolve(result);
    }

    @PluginMethod
    public void cancelAlarm(PluginCall call) {
        String entityId = call.getString("entityId");
        if (entityId == null) {
            call.reject("entityId is required");
            return;
        }

        Context ctx = getContext();
        if (ctx == null) {
            call.reject("Context unavailable");
            return;
        }


        AlarmScheduler scheduler = new AlarmScheduler(ctx);
        scheduler.cancel(entityId);

        JSObject result = new JSObject();
        result.put("success", true);
        call.resolve(result);
    }

    @PluginMethod
    public void cancelAllAlarms(PluginCall call) {
        Context ctx = getContext();
        if (ctx == null) {
            call.reject("Context unavailable");
            return;
        }

        AlarmScheduler scheduler = new AlarmScheduler(ctx);
        scheduler.cancelAll();

        JSObject result = new JSObject();
        result.put("success", true);
        call.resolve(result);
    }

    @PluginMethod
    public void checkExactAlarmPermission(PluginCall call) {
        Context ctx = getContext();
        if (ctx == null) {
            call.reject("Context unavailable");
            return;
        }

        boolean canSchedule = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            canSchedule = am != null && am.canScheduleExactAlarms();
        }


        JSObject result = new JSObject();
        result.put("granted", canSchedule);
        call.resolve(result);
    }

    @PluginMethod
    public void requestExactAlarmPermission(PluginCall call) {
        Context ctx = getContext();
        if (ctx == null) {
            call.reject("Context unavailable");
            return;
        }


        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent intent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
            intent.setData(android.net.Uri.parse("package:" + ctx.getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                ctx.startActivity(intent);
            } catch (Exception e) {
                Log.e(TAG, "Failed to open exact alarm settings: " + e.getMessage());
            }
        }

        JSObject result = new JSObject();
        result.put("success", true);
        call.resolve(result);
    }

    private android.media.MediaPlayer previewPlayer;
    private android.animation.ValueAnimator previewRamp;

    /**
     * Fade the preview in from 35% to full gain over 3s — same feel as a real
     * alarm, which ramps so the first second does not blast.
     */
    private void fadeInPreview(android.media.MediaPlayer player) {
        cancelPreviewRamp();
        try {
            player.setVolume(0.35f, 0.35f);
        } catch (IllegalStateException e) {
            return;
        }
        android.animation.ValueAnimator animator =
            android.animation.ValueAnimator.ofFloat(0.35f, 1f);
        animator.setDuration(3000L);
        animator.setInterpolator(new android.view.animation.LinearInterpolator());
        animator.addUpdateListener(a -> {
            android.media.MediaPlayer p = previewPlayer;
            if (p == null) return;
            try {
                float gain = (float) a.getAnimatedValue();
                p.setVolume(gain, gain);
            } catch (IllegalStateException ignored) {
                // Player released mid-ramp.
            }
        });
        previewRamp = animator;
        animator.start();
    }

    private void cancelPreviewRamp() {
        if (previewRamp != null) {
            previewRamp.cancel();
            previewRamp = null;
        }
    }

    @PluginMethod
    public void previewAlarm(PluginCall call) {
        String sound = call.getString("sound", "studentos_alarm");
        Context ctx = getContext();
        if (ctx == null) {
            call.reject("Context unavailable");
            return;
        }

        stopPreview(null);

        try {
            android.media.AudioAttributes audioAttributes = new android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();

            previewPlayer = new android.media.MediaPlayer();
            previewPlayer.setAudioAttributes(audioAttributes);
            previewPlayer.setLooping(false);

            if ("studentos_alarm".equals(sound)) {
                int resId = ctx.getResources().getIdentifier("studentos_alarm", "raw", ctx.getPackageName());
                if (resId != 0) {
                    android.content.res.AssetFileDescriptor afd = ctx.getResources().openRawResourceFd(resId);
                    previewPlayer.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
                    afd.close();
                } else {
                    android.net.Uri defaultUri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM);
                    previewPlayer.setDataSource(ctx, defaultUri);
                }
            } else {
                previewPlayer.setDataSource(ctx, android.net.Uri.parse(sound));
            }

            previewPlayer.prepare();
            previewPlayer.start();
            fadeInPreview(previewPlayer);

            // Stop preview automatically after 6 seconds — one full tone loop.
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                stopPreview(null);
            }, 6000);

            JSObject result = new JSObject();
            result.put("success", true);
            call.resolve(result);
        } catch (Exception e) {
            Log.e(TAG, "Failed to preview alarm", e);
            call.reject("Failed to preview alarm: " + e.getMessage());
        }
    }

    @PluginMethod
    public void dismissAlarm(PluginCall call) {
        Context ctx = getContext();
        if (ctx == null) { call.reject("Context unavailable"); return; }
        clearActiveAlarm();
        try {
            Intent intent = new Intent(ctx, AlarmForegroundService.class);
            intent.setAction(AlarmForegroundService.ACTION_DISMISS);
            ctx.startService(intent);
        } catch (Exception e) {
            Log.w(TAG, "startService(ACTION_DISMISS) failed: " + e.getMessage());
        }
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(2001);
        } catch (Exception e) {
            Log.w(TAG, "NotificationManager.cancel failed: " + e.getMessage());
        }
        JSObject result = new JSObject();
        result.put("success", true);
        call.resolve(result);
    }

    @PluginMethod
    public void stopPreview(PluginCall call) {
        cancelPreviewRamp();
        try {
            if (previewPlayer != null) {
                if (previewPlayer.isPlaying()) {
                    previewPlayer.stop();
                }
                previewPlayer.release();
                previewPlayer = null;
            }
            if (call != null) {
                JSObject result = new JSObject();
                result.put("success", true);
                call.resolve(result);
            }
        } catch (Exception e) {
            Log.w(TAG, "Error stopping preview", e);
            if (call != null) call.reject(e.getMessage());
        }
    }

    @Override
    protected void handleOnDestroy() {
        cancelPreviewRamp();
        instance = null;
        activeAlarmData = null;
        super.handleOnDestroy();
    }
}
