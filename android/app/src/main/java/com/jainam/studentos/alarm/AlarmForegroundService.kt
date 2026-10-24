package com.jainam.studentos.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.content.pm.ServiceInfo
import android.speech.tts.TextToSpeech
import android.util.Log
import android.annotation.SuppressLint
import android.view.animation.LinearInterpolator
import androidx.core.app.NotificationCompat
import com.jainam.studentos.R
import java.util.Locale

class AlarmForegroundService : Service(), AudioManager.OnAudioFocusChangeListener {

    companion object {
        private const val TAG = "[AlarmForegroundService]"
        private const val NOTIFICATION_ID = 2001
        private const val REQUEST_CODE_DISMISS = 30001
        private const val REQUEST_CODE_SNOOZE = 30002
        private const val REQUEST_CODE_DELETE = 30003
        private const val REQUEST_CODE_FULL_SCREEN = 30004
        const val CHANNEL_ID = "alarm_playback_channel"
        const val ACTION_SNOOZE = "com.jainam.studentos.action.SNOOZE_ALARM"
        const val ACTION_DISMISS = "com.jainam.studentos.action.DISMISS_ALARM"
        const val EXTRA_SNOOZE_MINUTES = "snooze_minutes"
        const val EXTRA_ENTITY_ID = "alarm_entity_id"
        const val EXTRA_TITLE = "alarm_title"
        const val EXTRA_BODY = "alarm_body"
        const val EXTRA_SOUND = "alarm_sound"
        const val EXTRA_VIBRATION = "alarm_vibration"

        // Ring loudness: the alarm stream is pushed to max while ringing (then
        // restored) and the player gain ramps up so the phone does not blast at
        // full volume in the first second.
        private const val RAMP_START = 0.35f
        private const val RAMP_MS = 4500L
    }

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var isPlaying = false
    private var entityId: String? = null
    private var title: String? = null
    private var sound: String? = null
    private var vibrationEnabled = true
    private var notificationManager: NotificationManager? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingTtsText: String? = null
    private var previousAlarmVolume = -1
    private var volumeRamp: ValueAnimator? = null

    private val actionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_SNOOZE -> {
                    val minutes = intent.getIntExtra(EXTRA_SNOOZE_MINUTES, 10)
                    handleSnooze(minutes)
                }
                ACTION_DISMISS -> {
                    handleDismiss()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StudentOS:AlarmWakeLock")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibrator = vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        }

        createNotificationChannel()

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                // Speak the alarm title through the alarm stream, not media:
                // otherwise the announcement is inaudible while the phone is
                // on silent/media volume 0.
                tts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                ttsReady = true
                pendingTtsText?.let { speakTts(it) }
                pendingTtsText = null
            }
        }

        val filter = IntentFilter().apply {
            addAction(ACTION_SNOOZE)
            addAction(ACTION_DISMISS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(actionReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnsafeRegisteredReceiver", "UnspecifiedRegisterReceiverFlag")
            registerReceiver(actionReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent.action) {
            ACTION_DISMISS -> {
                Log.i(TAG, "onStartCommand: ACTION_DISMISS")
                handleDismiss()
                return START_NOT_STICKY
            }
            ACTION_SNOOZE -> {
                val minutes = intent.getIntExtra(EXTRA_SNOOZE_MINUTES, 5)
                Log.i(TAG, "onStartCommand: ACTION_SNOOZE $minutes min")
                handleSnooze(minutes)
                return START_NOT_STICKY
            }
        }

        entityId = intent.getStringExtra(EXTRA_ENTITY_ID)
        title = intent.getStringExtra(EXTRA_TITLE) ?: "Alarm"
        sound = intent.getStringExtra(EXTRA_SOUND) ?: "studentos_alarm"
        vibrationEnabled = intent.getBooleanExtra(EXTRA_VIBRATION, true)
        val body = intent.getStringExtra(EXTRA_BODY) ?: ""

        Log.i(TAG, "onStartCommand: entityId=$entityId title=$title")

        wakeLock?.acquire(10 * 60 * 1000L)

        val dismissPendingIntent = PendingIntent.getService(
            this, REQUEST_CODE_DISMISS,
            Intent(this, AlarmForegroundService::class.java).setAction(ACTION_DISMISS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val snoozePendingIntent = PendingIntent.getService(
            this, REQUEST_CODE_SNOOZE,
            Intent(this, AlarmForegroundService::class.java).setAction(ACTION_SNOOZE)
                .putExtra(EXTRA_SNOOZE_MINUTES, 5),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val deletePendingIntent = PendingIntent.getService(
            this, REQUEST_CODE_DELETE,
            Intent(this, AlarmForegroundService::class.java).setAction(ACTION_DISMISS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // USE_FULL_SCREEN_INTENT is a Play-restricted permission: it is only
        // allowed for alarm/calling apps, and only when actually used. The intent
        // used to be `null`, so the permission was declared but never exercised
        // and Android 14+ could block the background activity launch entirely.
        val fullScreenPendingIntent = PendingIntent.getActivity(
            this,
            REQUEST_CODE_FULL_SCREEN,
            Intent(this, AlarmFullScreenActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
                putExtra(EXTRA_ENTITY_ID, entityId)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_BODY, body)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .addAction(android.R.drawable.ic_lock_idle_alarm, "Dismiss", dismissPendingIntent)
            .addAction(android.R.drawable.ic_lock_idle_alarm, "Snooze 5 min", snoozePendingIntent)
            .setDeleteIntent(deletePendingIntent)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            stopSelf()
            return START_NOT_STICKY
        }
        startPlaying()

        return START_STICKY
    }

    private fun startPlaying() {
        if (isPlaying) return
        isPlaying = true

        requestAudioFocus()
        boostAlarmStream()
        playAlarmAudio()
        if (vibrationEnabled) startVibrating()
        val ttsText = title ?: "Alarm"
        if (ttsReady) {
            speakTts(ttsText)
        } else {
            pendingTtsText = ttsText
        }
    }

    /**
     * Push the ALARM stream to its maximum while this alarm rings. MediaPlayer
     * gain is capped at 1.0, so without this the ring is only as loud as the
     * alarm slider happens to be — often a few percent after someone lowered it
     * for an earlier alarm. The previous index is restored in [stopPlaying].
     */
    private fun boostAlarmStream() {
        val am = audioManager ?: return
        try {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val current = am.getStreamVolume(AudioManager.STREAM_ALARM)
            if (current >= max) return
            previousAlarmVolume = current
            am.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
            Log.i(TAG, "Raised alarm stream volume $current -> $max")
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot raise alarm volume (policy access missing): ${e.message}")
        }
    }

    /** Put the ALARM stream back the way the user left it. */
    private fun restoreAlarmStream() {
        val am = audioManager ?: return
        val previous = previousAlarmVolume
        if (previous < 0) return
        previousAlarmVolume = -1
        try {
            am.setStreamVolume(AudioManager.STREAM_ALARM, previous, 0)
            Log.i(TAG, "Restored alarm stream volume to $previous")
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot restore alarm volume: ${e.message}")
        }
    }

    /** Ramp the player gain so the alarm fades in instead of starting at a blast. */
    private fun startVolumeRamp() {
        val player = mediaPlayer ?: return
        volumeRamp?.cancel()
        try {
            player.setVolume(RAMP_START, RAMP_START)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Cannot set initial ramp volume: ${e.message}")
            return
        }
        val animator = ValueAnimator.ofFloat(RAMP_START, 1f).apply {
            duration = RAMP_MS
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val gain = anim.animatedValue as Float
                try {
                    mediaPlayer?.setVolume(gain, gain)
                } catch (_: IllegalStateException) {
                    // Player released mid-ramp; nothing to do.
                }
            }
        }
        volumeRamp = animator
        animator.start()
    }

    private fun cancelVolumeRamp() {
        volumeRamp?.cancel()
        volumeRamp = null
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(this)
                .build()

            audioManager?.requestAudioFocus(audioFocusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager?.requestAudioFocus(
                this,
                AudioManager.STREAM_ALARM,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
            )
        }
    }

    override fun onAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Log.i(TAG, "Audio focus lost. Ducking/pausing alarm if needed (but alarms should usually persist).")
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.i(TAG, "Audio focus gained. Restoring volume.")
                cancelVolumeRamp()
                mediaPlayer?.setVolume(1.0f, 1.0f)
            }
        }
    }

    private fun playAlarmAudio() {
        try {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(audioAttributes)
                isLooping = true
            }

            // 1. Try bundled custom sound
            if (sound == "studentos_alarm") {
                val afd = resources.openRawResourceFd(R.raw.studentos_alarm)
                if (afd != null) {
                    mediaPlayer?.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    afd.close()
                } else {
                    throw Exception("Raw resource not found")
                }
            } else {
                // 2. Try parsing provided URI
                mediaPlayer?.setDataSource(this, Uri.parse(sound))
            }

            mediaPlayer?.prepare()
            mediaPlayer?.start()
            Log.i(TAG, "MediaPlayer started looping alarm sound")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to play preferred alarm sound: ${e.message}. Attempting fallback...")
            playFallbackAudio()
        }
        // Covers the bundled sound, the caller-supplied URI and the ringtone
        // fallbacks: whichever player is alive gets the fade-in.
        startVolumeRamp()
    }

    private fun playFallbackAudio() {
        try {
            mediaPlayer?.reset()
            val defaultAlarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            mediaPlayer?.setDataSource(this, defaultAlarmUri)
            mediaPlayer?.prepare()
            mediaPlayer?.start()
            Log.i(TAG, "Fallback to TYPE_ALARM succeeded")
        } catch (e2: Exception) {
            Log.e(TAG, "Fallback to TYPE_ALARM failed. Attempting TYPE_NOTIFICATION...")
            try {
                mediaPlayer?.reset()
                val notifUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                mediaPlayer?.setDataSource(this, notifUri)
                mediaPlayer?.prepare()
                mediaPlayer?.start()
                Log.i(TAG, "Fallback to TYPE_NOTIFICATION succeeded")
            } catch (e3: Exception) {
                Log.e(TAG, "All audio fallbacks failed.", e3)
            }
        }
    }

    private fun startVibrating() {
        try {
            val pattern = longArrayOf(0, 500, 500, 500, 500)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = VibrationEffect.createWaveform(pattern, 1)
                vibrator?.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, 1)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Vibration failed: ${e.message}")
        }
    }

    private fun speakTts(text: String) {
        try {
            val utteranceId = entityId ?: text
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            } else {
                @Suppress("DEPRECATION")
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null as java.util.HashMap<String, String>?)
            }
        } catch (e: Exception) {
            Log.w(TAG, "TTS speak failed: ${e.message}")
        }
    }

    private fun stopPlaying() {
        isPlaying = false
        cancelVolumeRamp()
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (e: Exception) {
            Log.w(TAG, "Stop MediaPlayer error: ${e.message}")
        }
        try {
            tts?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Stop TTS error: ${e.message}")
        }
        try {
            vibrator?.cancel()
        } catch (e: Exception) {
            Log.w(TAG, "Cancel vibration error: ${e.message}")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager?.abandonAudioFocus(this)
        }
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        restoreAlarmStream()
    }

    private fun handleSnooze(minutes: Int) {
        Log.i(TAG, "Snoozing for $minutes minutes")
        stopPlaying()
        stopForeground(STOP_FOREGROUND_REMOVE)

        val entityId = this.entityId ?: return
        val now = System.currentTimeMillis()
        val snoozeUntilMs = now + (minutes * 60_000L)

        // Cancel original alarm PendingIntent + remove from DataStore to prevent stale boot recovery
        AlarmScheduler(this).cancel(entityId)

        val data = AlarmData(
            entityId = "$entityId-snooze",
            type = "habit",
            title = title ?: "Alarm",
            body = "Snoozed",
            scheduleAtMs = snoozeUntilMs,
            isRecurring = false,
            timeStr = "",
            sound = sound ?: "studentos_alarm",
            vibration = vibrationEnabled,
            extraJson = "{}",
        )
        AlarmScheduler(this).schedule(data)
        stopSelf()
    }

    private fun handleDismiss() {
        Log.i(TAG, "Dismissing alarm")
        stopPlaying()
        stopForeground(STOP_FOREGROUND_REMOVE)

        val entityId = this.entityId
        if (entityId != null) {
            AlarmScheduler(this).cancel("$entityId-snooze")
        }
        stopSelf()
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        stopPlaying()
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) { }
        try {
            tts?.shutdown()
            tts = null
        } catch (e: Exception) {
            Log.w(TAG, "TTS shutdown error: ${e.message}")
        }
        try {
            unregisterReceiver(actionReceiver)
        } catch (e: Exception) {
            // ignored if already unregistered
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Alarm Playback",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Alarm sound and vibration is playing"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }
}
