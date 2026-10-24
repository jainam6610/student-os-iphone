package com.jainam.studentos

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

/**
 * In-app "Study YouTube" player.
 *
 * Loads mobile YouTube in a WebView we control and injects a distraction-free
 * script (the same idea as the Chrome "YouTube Study Mode" extension, adapted to
 * mobile YouTube's `ytm-*` elements):
 *  - Home feed removed → videos only reachable through Search.
 *  - Shorts shelves/items and the Shorts tab removed.
 *  - Comments and related/up-next recommendations removed.
 *
 * This is deliberately *our own* player: the real Google YouTube app exposes no
 * DOM, so the extension behaviour can only exist inside a WebView.
 */
class StudyYouTubeActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "[StudyYouTube]"
        private const val HOME_URL = "https://m.youtube.com/"

        private val ALLOWED_HOSTS = listOf(
            "youtube.com", "youtu.be", "youtube-nocookie.com",
            "google.com", "gstatic.com", "googleusercontent.com",
            "googleapis.com", "ytimg.com", "googlevideo.com",
            "ggpht.com", "gvt1.com", "gvt2.com"
        )
    }

    private lateinit var webView: WebView
    private val mainHandler = Handler(Looper.getMainLooper())
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_study_youtube)

        findViewById<TextView>(R.id.btnCloseStudyYouTube).setOnClickListener { finish() }

        webView = findViewById(R.id.studyYoutubeWebView)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = true
            }
            loadWithOverviewMode = true
            useWideViewPort = true
            cacheMode = WebSettings.LOAD_DEFAULT
            // Keep WebView's real device/browser versions current while removing
            // the marker that makes YouTube serve its limited embedded-WebView UI.
            userAgentString = userAgentString.replace("; wv", "").replace("Version/4.0 ", "")
        }
        webView.setKeepScreenOn(true)
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (fullscreenView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                fullscreenView = view
                fullscreenCallback = callback
                (window.decorView as? FrameLayout)?.addView(
                    view,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    )
            }

            override fun onHideCustomView() = hideFullscreenVideo()
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectStudyScript(view)
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                // Mobile YouTube is a single-page app: navigation does not fire
                // onPageFinished, so re-inject on history changes too.
                injectStudyScript(view)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                val allowed = isAllowedHost(uri)
                if (!allowed) Log.i(TAG, "blocked non-YouTube navigation: $uri")
                if (!allowed) return true
                if (uri.host?.endsWith("youtube.com", ignoreCase = true) == true &&
                    uri.path?.startsWith("/shorts", ignoreCase = true) == true) {
                    Log.i(TAG, "blocked Shorts navigation")
                    view?.loadUrl("https://m.youtube.com/")
                    return true
                }
                return false
            }
        }

        webView.loadUrl(HOME_URL)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    fullscreenView != null -> hideFullscreenVideo()
                    webView.canGoBack() -> webView.goBack()
                    else -> finish()
                }
            }
        })
    }

    private fun hideFullscreenVideo() {
        val view = fullscreenView ?: return
        (view.parent as? ViewGroup)?.removeView(view)
        fullscreenView = null
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
    }

    private fun isAllowedHost(uri: Uri): Boolean {
        val host = uri.host?.lowercase() ?: return false
        return ALLOWED_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    /**
     * Injects the study script. Runs immediately and again shortly after, because
     * the Home feed / player mount a beat after the page reports finished.
     */
    private fun injectStudyScript(view: WebView?) {
        val wv = view ?: return
        wv.evaluateJavascript(STUDY_JS, null)
        mainHandler.postDelayed({
            try { wv.evaluateJavascript(STUDY_JS, null) } catch (_: Exception) {}
        }, 1200L)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        try {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.destroy()
        } catch (_: Exception) {}
        super.onDestroy()
    }

    // No `$` anywhere below: this is a Kotlin raw string, and `$` would be
    // parsed as template interpolation.
    private val STUDY_JS = """
        (function () {
          try {
            // Block Shorts even when opened through a deep link or SPA navigation.
            if (/\/shorts(?:\/|$)/i.test(location.pathname)) {
              history.replaceState(null, '', '/');
              location.replace('https://m.youtube.com/');
              return false;
            }
            if (!document.getElementById('ytsm-style')) {
              var st = document.createElement('style');
              st.id = 'ytsm-style';
              st.textContent = [
                'ytm-browse[page-subtype="home"] #contents{display:none!important}',
                'ytm-browse[page-subtype="home"] ytm-rich-grid-renderer{display:none!important}',
                'ytm-browse[page-subtype="home"]::before{content:"Study Mode. Search for a lecture or open a saved video.";display:block;text-align:center;padding:120px 24px;font-size:18px;line-height:1.5;color:#8a8a90;font-family:system-ui}',
                'ytm-reel-shelf-renderer,ytm-reel-item-renderer,ytm-shorts-lockup-view-model,ytm-shorts-shelf-renderer{display:none!important}',
                'ytm-pivot-bar-item-renderer[tab-identifier="FEshorts"]{display:none!important}',
                'a[href^="/shorts"],a[href*="/shorts/"],ytm-shorts-lockup-view-model,ytm-video-with-context-renderer:has(a[href^="/shorts"]){display:none!important}',
                'ytm-comment-section-renderer,ytm-comments-entry-point-header-renderer,ytm-comments-entry-point-teaser-renderer{display:none!important}',
                'ytm-item-section-renderer[section-identifier="related-items"]{display:none!important}',
                'ytm-item-section-renderer[section-identifier="comments-entry-point"]{display:none!important}',
                'ytm-compact-autoplay-renderer,ytm-autoplay-button,ytm-watch-next-secondary-results-renderer{display:none!important}'
              ].join('');
              (document.head || document.documentElement).appendChild(st);
            }
            if (!window.__ytsmHistoryGuard) {
              window.__ytsmHistoryGuard = true;
              var checkRoute = function () {
                if (/\/shorts(?:\/|$)/i.test(location.pathname)) location.replace('https://m.youtube.com/');
              };
              var push = history.pushState;
              history.pushState = function () { var result = push.apply(this, arguments); checkRoute(); return result; };
              addEventListener('popstate', checkRoute);
              new MutationObserver(function () {
                document.querySelectorAll('a[href^="/shorts"],a[href*="/shorts/"]').forEach(function (el) { el.remove(); });
              }).observe(document.documentElement, { childList: true, subtree: true });
            }
          } catch (e) {}
          return true;
        })();
    """.trimIndent()
}
