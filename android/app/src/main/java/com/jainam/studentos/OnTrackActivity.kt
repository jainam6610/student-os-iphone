package com.jainam.studentos

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.jainam.studentos.focusshield.FocusShieldBridge

/**
 * "Are you on track?" — a calm, single-question check-in shown over a
 * distracting app (YouTube / Instagram / browsers / TikTok / Snapchat) after ten
 * minutes of continuous use *outside* a focus session.
 *
 * Deliberately NOT a block screen:
 *  - "Yes, I'm on track" dismisses and restarts the 10-minute window.
 *  - "Not on track" sends the user to the home screen so they can make a
 *    deliberate choice to come back.
 *
 * Enabled by the Motivation Engine toggle; timing lives in FocusShieldManager.
 */
class OnTrackActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "[OnTrackActivity]"
    }

    private var packageName: String = ""
    private var answered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ontrack)

        packageName = intent?.getStringExtra("packageName") ?: ""
        Log.i(TAG, "onCreate() pkg=$packageName")

        val body = findViewById<TextView>(R.id.ontrackBody)
        val appLabel = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
        }.getOrElse { "this app" }
        body.text = "You've been in $appLabel for 10 minutes. Is this what you meant to do?"

        findViewById<Button>(R.id.btnOnTrack).setOnClickListener {
            Log.i(TAG, "answered: on track")
            answer(onTrack = true)
        }
        findViewById<Button>(R.id.btnOffTrack).setOnClickListener {
            Log.i(TAG, "answered: not on track")
            answer(onTrack = false)
        }

        // Back = treat as "on track" so the overlay can never trap the user.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                answer(onTrack = true)
            }
        })
    }

    private fun answer(onTrack: Boolean) {
        if (answered) return
        answered = true
        try {
            FocusShieldBridge.getManager()?.onCheckInAnswered(packageName, onTrack)
        } catch (e: Exception) {
            Log.w(TAG, "answer() could not notify manager", e)
        }
        if (!onTrack) goHome()
        finish()
    }

    private fun goHome() {
        try {
            val home = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(home)
            Log.i(TAG, "goHome() — sent user to the launcher")
        } catch (e: Exception) {
            Log.w(TAG, "goHome() failed", e)
        }
    }

    override fun onDestroy() {
        // If the system dismissed us without an answer, restart the timer so the
        // overlay does not immediately re-fire.
        if (!answered) {
            try {
                FocusShieldBridge.getManager()?.onCheckInAnswered(packageName, true)
            } catch (_: Exception) {}
        }
        super.onDestroy()
    }
}
