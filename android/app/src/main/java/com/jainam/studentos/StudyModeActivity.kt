package com.jainam.studentos

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.jainam.studentos.focusshield.FocusShieldBridge

/**
 * YouTube Study Mode gate. Shown when the YouTube Home/browse feed is detected
 * while Study Mode is on. It covers the algorithmic feed and offers two ways
 * forward:
 *  - "Search YouTube" → dismisses, then taps YouTube's Search and opens the keyboard.
 *  - "Leave to study" → dismisses and returns to the home screen.
 *
 * Search results and the watch page are never gated — only the home feed.
 */
class StudyModeActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "[StudyModeActivity]"
    }

    private var dismissed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_studymode)

        findViewById<Button>(R.id.btnSearchYouTube).setOnClickListener {
            Log.i(TAG, "chose Search")
            dismiss()
            finish()
            // Root is ours while this is on top; ask the manager to click Search
            // once YouTube is foreground again.
            try { FocusShieldBridge.getManager()?.requestYouTubeSearch() } catch (_: Exception) {}
        }

        findViewById<Button>(R.id.btnLeaveStudy).setOnClickListener {
            Log.i(TAG, "chose Leave")
            dismiss()
            goHome()
            finish()
        }

        // Back = go to search, never trap the user.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                dismiss()
                finish()
                try { FocusShieldBridge.getManager()?.requestYouTubeSearch() } catch (_: Exception) {}
            }
        })
    }

    private fun dismiss() {
        if (dismissed) return
        dismissed = true
        try {
            FocusShieldBridge.getManager()?.onStudyModeDismissed()
        } catch (e: Exception) {
            Log.w(TAG, "dismiss() could not notify manager", e)
        }
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
        if (!dismissed) dismiss()
        super.onDestroy()
    }
}
