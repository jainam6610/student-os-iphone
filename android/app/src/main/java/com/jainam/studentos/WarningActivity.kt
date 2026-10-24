package com.jainam.studentos

import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.jainam.studentos.focusshield.FocusShieldBridge
import com.jainam.studentos.focusshield.FocusShieldManager
import com.jainam.studentos.focusshield.MotivationEngine

/**
 * Full-screen "Flow Shield" popup shown when the user opens a blocked app during
 * a focus session. Port of the Flow Shield prototype: countdown ring, re-enter
 * button, mindful breathing, pause, and a hold-to-earn 5-minute emergency pass.
 */
class WarningActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "[WarningActivity]"
        private const val COUNTDOWN_MS = 3_000L
        private const val HOLD_PASS_MS = 10_000L
        private const val TICK_MS = 50L
        private const val SESSION_CHECK_INTERVAL_MS = 2_000L
        private const val WARNING_TIMEOUT_MS = 60_000L
    }

    private lateinit var ring: CountdownRingView
    private lateinit var countdownText: TextView
    private lateinit var timerStatusLabel: TextView
    private lateinit var ringCenterIcon: TextView
    private lateinit var appNameText: TextView
    private lateinit var motivationTitle: TextView
    private lateinit var bodyText: TextView
    private lateinit var pillSprintLabel: TextView
    private lateinit var vaultIcon: ImageView
    private lateinit var returnButton: Button
    private lateinit var breathButton: Button
    private lateinit var pauseButton: Button
    private lateinit var breathBox: LinearLayout
    private lateinit var breathBubble: View
    private lateinit var breathText: TextView
    private lateinit var closeBreathBtn: TextView
    private lateinit var holdPassContainer: FrameLayout
    private lateinit var holdProgressBar: View
    private lateinit var holdLabel: TextView
    private lateinit var holdTimerBadge: TextView
    private lateinit var streakText: TextView
    private lateinit var blockedText: TextView
    private lateinit var savedText: TextView

    private val mainHandler = Handler(Looper.getMainLooper())

    private var packageName: String = ""
    private var appName: String = ""
    private var difficultyMode: String = "HARD"
    private var dismissed = false
    /**
     * True when this block fires outside a focus session (always-on surface
     * blocking). There is no session to fall back to, so the screen must not
     * auto-dismiss on "no session" and should eject to the home screen.
     */
    private var standalone = false
    /**
     * Surface blocks (Shorts/Reels/URL) must NOT grant the 6s re-entry grace pass
     * on dismiss: that pass let the user reopen the player immediately and skip
     * the block. App-level blocks keep the pass to avoid a dismiss loop.
     */
    private var surfaceBlock = false

    private var remainingMs = COUNTDOWN_MS
    private var paused = false
    private var lastTick = 0L

    private var holding = false
    private var holdElapsedMs = 0L
    private var passGranted = false

    private var breathPhase = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_warning)

        packageName = intent?.getStringExtra("packageName") ?: ""
        appName = intent?.getStringExtra("appName") ?: packageName
        difficultyMode = intent?.getStringExtra("difficultyMode") ?: "HARD"
        standalone = intent?.getBooleanExtra("standalone", false) ?: false
        val reason = intent?.getStringExtra("reason") ?: ""
        surfaceBlock = reason == "SHORTFORM" || reason == "ACTIVITY" || reason == "URL" || reason == "STUDYMODE"
        Log.i(TAG, "onCreate() pkg=$packageName app=$appName mode=$difficultyMode standalone=$standalone reason=$reason surface=$surfaceBlock")

        // Tell the engine the block screen really rendered. If this never fires,
        // BlockingEngine releases its "warning showing" latch after 2s instead of
        // swallowing every later block as WARNING_ALREADY_SHOWING.
        try {
            FocusShieldBridge.getManager()?.onWarningVisible()
        } catch (e: Exception) {
            Log.e(TAG, "onCreate() could not report visibility", e)
        }

        bindViews()
        populateContent()
        setupBehavior()
        // A standalone block has no session to end, so the session-end checker
        // would fire instantly and tear the screen down. Skip it there.
        if (!standalone) startSessionEndChecker()
        startWarningTimeout()

        lastTick = System.currentTimeMillis()
        mainHandler.post(ticker)
    }

    private fun bindViews() {
        ring = findViewById(R.id.countdownRing)
        countdownText = findViewById(R.id.countdownText)
        timerStatusLabel = findViewById(R.id.timerStatusLabel)
        ringCenterIcon = findViewById(R.id.ringCenterIcon)
        appNameText = findViewById(R.id.appNameText)
        motivationTitle = findViewById(R.id.motivationTitle)
        bodyText = findViewById(R.id.bodyText)
        pillSprintLabel = findViewById(R.id.pillSprintLabel)
        vaultIcon = findViewById(R.id.vaultIcon)
        returnButton = findViewById(R.id.returnButton)
        breathButton = findViewById(R.id.breathButton)
        pauseButton = findViewById(R.id.pauseButton)
        breathBox = findViewById(R.id.breathBox)
        breathBubble = findViewById(R.id.breathBubble)
        breathText = findViewById(R.id.breathText)
        closeBreathBtn = findViewById(R.id.closeBreathBtn)
        holdPassContainer = findViewById(R.id.holdPassContainer)
        holdProgressBar = findViewById(R.id.holdProgressBar)
        holdLabel = findViewById(R.id.holdLabel)
        holdTimerBadge = findViewById(R.id.holdTimerBadge)
        streakText = findViewById(R.id.streakText)
        blockedText = findViewById(R.id.blockedText)
        savedText = findViewById(R.id.savedText)
    }

    private fun populateContent() {
        appNameText.text = "$appName gently put to sleep"
        val motivation = MotivationEngine.getMotivationalMessage("grind")
        motivationTitle.text = motivation.first
        bodyText.text = motivation.second

        loadVaultIcon()

        val ctx = getSessionContext()
        val remainingMinutes = ctx?.remainingMinutes ?: 0
        val elapsedMinutes = ctx?.elapsedMinutes ?: 0
        val streak = ctx?.streak ?: 0
        val distractions = ctx?.interruptionCount ?: 0

        pillSprintLabel.text = if (remainingMinutes > 0) "DEEP WORK • ${remainingMinutes}m SPRINT" else "DEEP WORK • SPRINT"

        streakText.text = "🔥 $streak"
        blockedText.text = "🛡️ ${todayDistractions()}"
        savedText.text = "⚡ ${formatTime(elapsedMinutes)}"

        // Prefer a derived line when we have live session context.
        if (remainingMinutes > 0) {
            bodyText.text = "Your sprint has $remainingMinutes minutes left of pure momentum. " +
                (if (distractions > 0) "$distractions attempts deflected." else "Is this impulse worth resetting your streak?")
        }
    }

    private fun todayDistractions(): Int {
        return try {
            FocusShieldBridge.getManager()?.getTodayDistractions() ?: 0
        } catch (_: Exception) { 0 }
    }

    private fun loadVaultIcon() {
        try {
            if (packageName.isBlank()) return
            @Suppress("DEPRECATION")
            val icon: Drawable = packageManager.getApplicationIcon(packageName)
            vaultIcon.setImageDrawable(icon)
        } catch (e: Exception) {
            Log.w(TAG, "loadVaultIcon() failed for $packageName", e)
            vaultIcon.visibility = View.GONE
        }
    }

    private fun getSessionContext(): FocusShieldManager.SessionContext? {
        return try {
            FocusShieldBridge.getManager()?.getSessionContext()
        } catch (_: Exception) { null }
    }

    private fun formatTime(minutes: Int): String {
        if (minutes < 60) return "${minutes}m"
        val h = minutes / 60
        val m = minutes % 60
        return if (m > 0) "${h}h ${m}m" else "${h}h"
    }

    private fun setupBehavior() {
        // Never let the hardware/gesture back button escape into the blocked app.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                Log.i(TAG, "back pressed — returning to focus")
                returnToFocus()
            }
        })

        returnButton.setOnClickListener { returnToFocus() }

        pauseButton.setOnClickListener { togglePause() }
        val timerTrigger = findViewById<View>(R.id.timerTrigger)
        timerTrigger.setOnClickListener { togglePause() }

        breathButton.setOnClickListener { startBreathCycle() }
        closeBreathBtn.setOnClickListener { stopBreathCycle() }

        if (difficultyMode == "STRICT" || difficultyMode == "HARDCORE") {
            // No emergency pass in strict modes — hold button is the bypass we must deny.
            holdPassContainer.visibility = View.GONE
        } else {
            setupHoldPass()
        }

        if (difficultyMode == "EASY") {
            findViewById<TextView>(R.id.dismissHint).let {
                it.visibility = View.VISIBLE
                it.text = "Returning to focus in a moment…"
            }
        }
    }

    // --- Countdown ---

    private val ticker = object : Runnable {
        override fun run() {
            if (dismissed) return
            val now = System.currentTimeMillis()
            val delta = now - lastTick
            lastTick = now

            if (!paused) {
                remainingMs = (remainingMs - delta).coerceAtLeast(0L)
                renderCountdown()
                if (remainingMs <= 0L && !holding) {
                    returnToHome()
                    return
                }
            }

            if (holding) {
                holdElapsedMs += delta
                renderHold()
                if (holdElapsedMs >= HOLD_PASS_MS && !passGranted) {
                    grantPass()
                }
            }

            mainHandler.postDelayed(this, TICK_MS)
        }
    }

    private fun renderCountdown() {
        val fraction = (remainingMs.toFloat() / COUNTDOWN_MS).coerceIn(0f, 1f)
        ring.progress = fraction
        val seconds = ((remainingMs + 999) / 1000).coerceAtLeast(0)
        countdownText.text = if (seconds > 0) "${seconds}s" else "0s"
    }

    private fun togglePause(custom: Boolean? = null) {
        paused = custom ?: !paused
        if (paused) {
            pauseButton.text = "▶ Resume"
            timerStatusLabel.text = "SHIELD PAUSED"
            ringCenterIcon.text = "⏸️"
        } else {
            pauseButton.text = "⏸ Pause"
            timerStatusLabel.text = "AUTO-RETURN"
            ringCenterIcon.text = "🛡️"
        }
    }

    // --- Mindful breathing ---

    private fun startBreathCycle() {
        togglePause(true)
        breathBox.visibility = View.VISIBLE
        breathPhase = 0
        runBreathPhase()
        mainHandler.postDelayed(breathRunner, 3200)
    }

    private val breathRunner = object : Runnable {
        override fun run() {
            if (dismissed || breathBox.visibility != View.VISIBLE) return
            runBreathPhase()
            mainHandler.postDelayed(this, 3200)
        }
    }

    private fun runBreathPhase() {
        val phases = arrayOf("Inhale", "Hold", "Exhale")
        val label = phases[breathPhase % phases.size]
        breathText.text = label
        val targetScale = if (label == "Exhale") 1f else 1.8f
        breathBubble.animate().scaleX(targetScale).scaleY(targetScale).setDuration(3000).start()
        breathPhase++
    }

    private fun stopBreathCycle() {
        mainHandler.removeCallbacks(breathRunner)
        breathBox.visibility = View.GONE
        breathBubble.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
        togglePause(false)
    }

    // --- Hold for emergency pass ---

    private fun setupHoldPass() {
        holdPassContainer.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!passGranted) {
                        holding = true
                        holdElapsedMs = 0L
                        togglePause(true)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (holding && !passGranted) cancelHold()
                    true
                }
                else -> false
            }
        }
    }

    private fun renderHold() {
        val fraction = (holdElapsedMs.toFloat() / HOLD_PASS_MS).coerceIn(0f, 1f)
        holdProgressBar.post {
            val parentWidth = holdPassContainer.width
            holdProgressBar.layoutParams = holdProgressBar.layoutParams.apply {
                width = (parentWidth * fraction).toInt()
            }
            holdProgressBar.requestLayout()
        }
        val remainingSec = (((HOLD_PASS_MS - holdElapsedMs) + 999) / 1000).coerceAtLeast(0)
        holdTimerBadge.text = "${remainingSec}s"
    }

    private fun cancelHold() {
        holding = false
        holdElapsedMs = 0L
        holdTimerBadge.text = "10s"
        holdLabel.text = "⏳ Hold 10s for 5m Pass"
        holdProgressBar.post {
            holdProgressBar.layoutParams = holdProgressBar.layoutParams.apply { width = 0 }
            holdProgressBar.requestLayout()
        }
        togglePause(false)
    }

    private fun grantPass() {
        passGranted = true
        holding = false
        holdLabel.text = "✅ Pass active (5m)"
        holdTimerBadge.text = "OK"
        Log.i(TAG, "grantPass() — emergency pass, opening $packageName")
        dismissed = true
        mainHandler.removeCallbacksAndMessages(null)
        // Grant the pass first so the re-check triggered by dismissWarning sees it
        // and does not immediately re-block the app.
        try {
            FocusShieldBridge.getManager()?.grantEmergencyPass(packageName)
        } catch (e: Exception) {
            Log.e(TAG, "grantPass() grantEmergencyPass failed", e)
        }
        dismissWarning()
        try {
            val launch = packageManager.getLaunchIntentForPackage(packageName)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launch)
            }
        } catch (e: Exception) {
            Log.e(TAG, "grantPass() launch failed", e)
        }
        finishAfterTransition()
    }

    // --- Lifecycle guards ---

    private fun startSessionEndChecker() {
        val checker = object : Runnable {
            override fun run() {
                if (dismissed) return
                try {
                    val mgr = FocusShieldBridge.getManagerOrNull()
                    if (mgr != null && !mgr.isSessionActive()) {
                        Log.i(TAG, "session ended while warning showing — auto-dismissing")
                        returnToFocus()
                        return
                    }
                } catch (_: Exception) {}
                mainHandler.postDelayed(this, SESSION_CHECK_INTERVAL_MS)
            }
        }
        mainHandler.postDelayed(checker, SESSION_CHECK_INTERVAL_MS)
    }

    private fun startWarningTimeout() {
        mainHandler.postDelayed({
            if (!dismissed) {
                Log.i(TAG, "warning timeout reached — auto-dismissing")
                returnToFocus()
            }
        }, WARNING_TIMEOUT_MS)
    }

    private fun launchMainActivity() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("navigate_to", "focus")
        }
        startActivity(intent)
    }

    private fun dismissWarning() {
        try {
            // Surface blocks: reset the latch but do not grant a re-entry pass.
            FocusShieldBridge.getManager()?.onWarningDismissed(packageName, !surfaceBlock)
        } catch (e: Exception) {
            Log.e(TAG, "dismissWarning() error", e)
        }
    }

    private fun returnToFocus() {
        Log.i(TAG, "returnToFocus() — user chose to return (standalone=$standalone)")
        dismissed = true
        mainHandler.removeCallbacksAndMessages(null)
        dismissWarning()
        // No session to return to → eject to the launcher so the block is real.
        if (standalone) goHome() else launchMainActivity()
        finishAfterTransition()
    }

    private fun returnToHome() {
        Log.i(TAG, "returnToHome() — countdown expired (standalone=$standalone)")
        dismissed = true
        mainHandler.removeCallbacksAndMessages(null)
        dismissWarning()
        if (standalone) goHome() else launchMainActivity()
        finishAfterTransition()
    }

    private fun goHome() {
        try {
            val home = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(home)
        } catch (e: Exception) {
            Log.w(TAG, "goHome() failed", e)
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy() — WarningActivity finishing")
        dismissed = true
        mainHandler.removeCallbacksAndMessages(null)
        com.jainam.studentos.focusshield.feature.blocking.BlockingEngine.isWarningScreenActive.set(false)
        super.onDestroy()
    }
}
