package com.jainam.studentos.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.annotation.SuppressLint
import android.os.PowerManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

class AlarmFullScreenActivity : ComponentActivity() {

    companion object {
        private const val TAG = "[AlarmFullScreen]"
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var dismissedByUser = false

    private val finishReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!dismissedByUser) {
                dismissedByUser = true
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setTurnScreenOn(true)
            setShowWhenLocked(true)
        }

        val entityIdExtra = intent.getStringExtra(AlarmForegroundService.EXTRA_ENTITY_ID)
        val label = intent.getStringExtra(AlarmForegroundService.EXTRA_TITLE) ?: "Alarm"

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "${packageName}:AlarmFullScreen",
        )
        wakeLock?.acquire(5 * 60 * 1000L)

        val filter = IntentFilter().apply {
            addAction(AlarmForegroundService.ACTION_DISMISS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(finishReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnsafeRegisteredReceiver", "UnspecifiedRegisterReceiverFlag")
            registerReceiver(finishReceiver, filter)
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFF6C8CFF),
                onPrimary = Color.White,
                background = Color(0xFF0D0D18),
                surface = Color(0xFF1A1A2E),
                onBackground = Color.White,
                onSurface = Color.White,
                secondary = Color(0xFF7888AA),
            )) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AlarmScreen(
                        label = label,
                        onSnooze = { minutes -> sendSnooze(minutes) },
                        onDismiss = { sendDismiss() },
                    )
                }
            }
        }

        setVolumeControlStream(AudioManager.STREAM_ALARM)
    }

    private fun sendSnooze(minutes: Int) {
        val intent = Intent(this, AlarmForegroundService::class.java).apply {
            action = AlarmForegroundService.ACTION_SNOOZE
            putExtra(AlarmForegroundService.EXTRA_SNOOZE_MINUTES, minutes)
        }
        startService(intent)
        dismissedByUser = true
        finish()
    }

    private fun sendDismiss() {
        val intent = Intent(this, AlarmForegroundService::class.java).apply {
            action = AlarmForegroundService.ACTION_DISMISS
        }
        startService(intent)
        dismissedByUser = true
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(finishReceiver)
        } catch (e: Exception) { /* ignored */ }
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) { /* ignored */ }
    }
}

@Composable
fun AlarmScreen(
    label: String,
    onSnooze: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var currentTime by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        while (true) {
            val now = Date()
            currentTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
            delay(1000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = currentTime,
            fontSize = 72.sp,
            fontWeight = FontWeight.Light,
            color = Color.White,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = label,
            fontSize = 24.sp,
            fontWeight = FontWeight.Medium,
            color = Color(0xFF7888AA),
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(64.dp))

        Text(
            text = "Snooze",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = Color(0xFF667788),
            letterSpacing = 1.sp,
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        ) {
            SnoozeButton(text = "5 min", onClick = { onSnooze(5) })
            SnoozeButton(text = "10 min", onClick = { onSnooze(10) })
            SnoozeButton(text = "15 min", onClick = { onSnooze(15) })
        }

        Spacer(modifier = Modifier.height(40.dp))

        Button(
            onClick = onDismiss,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
            ),
        ) {
            Text(
                text = "Dismiss",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
fun RowScope.SnoozeButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .weight(1f)
            .height(52.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0x33FFFFFF),
        ),
    ) {
        Text(
            text = text,
            fontSize = 14.sp,
            color = Color(0xFFDDDDDD),
        )
    }
}
