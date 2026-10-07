package ai.tryverso.connect

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * The connect screen: the provider's login in a WebView that presents itself
 * as Chrome for Android (Google refuses logins from a WebView user agent), a
 * cookie watch, and the capture call to Verso. Started by [VersoConnect.present]
 * or [VersoConnectContract]; finishes itself in every case.
 */
internal class VersoConnectActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_TOKEN = "ai.tryverso.connect.TOKEN"
        private const val EXTRA_BASE_URL = "ai.tryverso.connect.BASE_URL"
        const val EXTRA_CONNECTION_ID = "ai.tryverso.connect.CONNECTION_ID"
        const val EXTRA_STATUS = "ai.tryverso.connect.STATUS"
        const val EXTRA_ERROR = "ai.tryverso.connect.ERROR"

        const val RESULT_REJECTED = 2
        const val RESULT_TIMED_OUT = 3
        const val RESULT_NETWORK = 4
        const val RESULT_INVALID_LINK = 5

        private const val CHROME_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
        private const val POLL_MS = 2_000L
        private const val CAPTURE_RETRY_MS = 3_000L
        private const val MAX_WAITING_ANSWERS = 40

        fun intent(context: Context, token: String, baseUrl: String): Intent =
            Intent(context, VersoConnectActivity::class.java)
                .putExtra(EXTRA_TOKEN, token)
                .putExtra(EXTRA_BASE_URL, baseUrl)

        fun parseResult(resultCode: Int, intent: Intent?): Result<VersoConnection> = when (resultCode) {
            RESULT_OK -> Result.success(VersoConnection(intent?.getStringExtra(EXTRA_CONNECTION_ID) ?: ""))
            RESULT_CANCELED -> Result.failure(VersoConnectException.Cancelled())
            RESULT_REJECTED -> Result.failure(
                VersoConnectException.Rejected(
                    intent?.getIntExtra(EXTRA_STATUS, 0) ?: 0,
                    intent?.getStringExtra(EXTRA_ERROR) ?: "Rejected",
                ),
            )
            RESULT_TIMED_OUT -> Result.failure(VersoConnectException.TimedOut())
            RESULT_INVALID_LINK -> Result.failure(VersoConnectException.InvalidLink())
            else -> Result.failure(
                VersoConnectException.Network(IOException(intent?.getStringExtra(EXTRA_ERROR) ?: "Network error")),
            )
        }
    }

    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private val api by lazy { VersoApi(intent.getStringExtra(EXTRA_BASE_URL) ?: VersoConnect.baseUrl) }
    private val handler = Handler(Looper.getMainLooper())
    private var start: VersoApi.Start? = null
    private var checking = false
    private var waitingAnswers = 0
    private var finished = false

    /** Every host the login navigated to, so the clean-up reaches the provider's subdomains. */
    private val visitedHosts: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val poll = object : Runnable {
        override fun run() {
            checkForSession()
            handler.postDelayed(this, POLL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val token = intent.getStringExtra(EXTRA_TOKEN)
        if (token.isNullOrEmpty()) {
            deliver(RESULT_INVALID_LINK, null)
            return
        }
        buildUi()
        onBackPressedDispatcher.addCallback(this) { deliver(RESULT_CANCELED, null) }
        configureWebView()

        lifecycleScope.launch {
            try {
                val started = api.start(token, deviceInfo())
                start = started
                clearProviderState()
                webView.loadUrl(started.loginUrl)
                handler.postDelayed(poll, POLL_MS)
            } catch (e: VersoConnectException.Rejected) {
                deliver(RESULT_REJECTED, Intent().putExtra(EXTRA_STATUS, e.status).putExtra(EXTRA_ERROR, e.message))
            } catch (e: Exception) {
                deliver(RESULT_NETWORK, Intent().putExtra(EXTRA_ERROR, e.message))
            }
        }
    }

    // ---------------------------------------------------------------- UI

    private fun buildUi() {
        val density = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        // Edge-to-edge (the default from Android 15 for apps targeting it)
        // would draw the header under the status bar and the page under the
        // navigation bar.
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        val header = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (52 * density).toInt())
            setBackgroundColor(Color.WHITE)
        }
        header.addView(TextView(this).apply {
            text = "Connect ChatGPT"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        })
        header.addView(TextView(this).apply {
            text = "Cancel"
            textSize = 16f
            setTextColor(Color.parseColor("#2547F4"))
            gravity = Gravity.CENTER
            setPadding((16 * density).toInt(), 0, (16 * density).toInt(), 0)
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END)
            setOnClickListener { deliver(RESULT_CANCELED, null) }
        })
        root.addView(header)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (4 * density).toInt())
        }
        root.addView(progress)
        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(webView)
        setContentView(root)
    }

    // ----------------------------------------------------------- WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = CHROME_USER_AGENT
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
        }
        webView.addJavascriptInterface(Bridge(), "VersoBridge")
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (request.isForMainFrame) request.url.host?.let { visitedHosts.add(it) }
                return null
            }

            override fun onPageFinished(view: WebView, url: String?) {
                progress.visibility = View.INVISIBLE
                checkForSession()
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            /** Sign-in buttons that open a popup are loaded in place instead. */
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                val popup = WebView(this@VersoConnectActivity)
                popup.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                        webView.loadUrl(request.url.toString())
                        popup.destroy()
                        return true
                    }
                }
                (resultMsg.obj as WebView.WebViewTransport).webView = popup
                resultMsg.sendToTarget()
                return true
            }
        }
    }

    /** The page answers the session request through this object. */
    inner class Bridge {
        @JavascriptInterface
        fun onSession(text: String) {
            handler.post { onSessionJson(text) }
        }
    }

    // ------------------------------------------------------------ Capture

    private fun checkForSession() {
        val started = start ?: return
        if (finished || checking) return
        val header = CookieManager.getInstance().getCookie("https://${started.cookieDomain}") ?: return
        if (sessionToken(header, started.cookieName) == null) return
        checking = true
        val script = "(function(){fetch(" + JSONObject.quote(started.sessionUrl) +
            ",{credentials:'include'}).then(function(r){return r.text()})" +
            ".then(function(t){VersoBridge.onSession(t)})" +
            ".catch(function(){VersoBridge.onSession('')})})()"
        webView.evaluateJavascript(script, null)
    }

    private fun onSessionJson(text: String) {
        val started = start ?: return
        if (finished) return
        val json = try { JSONObject(text) } catch (e: Exception) { JSONObject() }
        val accessToken = json.optString("accessToken").takeIf { it.isNotEmpty() }
        if (accessToken == null) {
            // The cookie exists before the login is complete (verification
            // steps): look again on the next poll.
            checking = false
            return
        }
        val header = CookieManager.getInstance().getCookie("https://${started.cookieDomain}") ?: ""
        val sessionToken = sessionToken(header, started.cookieName)
        if (sessionToken == null) {
            checking = false
            return
        }
        val user = json.optJSONObject("user")
        lifecycleScope.launch {
            try {
                when (val outcome = api.capture(
                    started.captureNonce, started.clientSecret, sessionToken, accessToken,
                    user?.optString("id")?.takeIf { it.isNotEmpty() },
                    user?.optString("email")?.takeIf { it.isNotEmpty() },
                )) {
                    is VersoApi.Capture.Connected ->
                        deliver(RESULT_OK, Intent().putExtra(EXTRA_CONNECTION_ID, outcome.connectionId))
                    VersoApi.Capture.Waiting -> {
                        waitingAnswers++
                        if (waitingAnswers >= MAX_WAITING_ANSWERS) {
                            deliver(RESULT_TIMED_OUT, null)
                        } else {
                            handler.postDelayed({ checking = false; checkForSession() }, CAPTURE_RETRY_MS)
                        }
                    }
                }
            } catch (e: VersoConnectException.Rejected) {
                deliver(RESULT_REJECTED, Intent().putExtra(EXTRA_STATUS, e.status).putExtra(EXTRA_ERROR, e.message))
            } catch (e: Exception) {
                deliver(RESULT_NETWORK, Intent().putExtra(EXTRA_ERROR, e.message))
            }
        }
    }

    /** The cookie named [name], or its `name.0`, `name.1`, … chunks joined. */
    private fun sessionToken(cookieHeader: String, name: String): String? {
        val pairs = cookieHeader.split(";").mapNotNull { part ->
            val i = part.indexOf('=')
            if (i < 0) null else part.substring(0, i).trim() to part.substring(i + 1).trim()
        }.toMap()
        pairs[name]?.takeIf { it.isNotEmpty() }?.let { return it }
        val chunks = pairs.filterKeys { it.startsWith("$name.") && it.substringAfterLast('.').toIntOrNull() != null }
        if (chunks.isEmpty()) return null
        return chunks.entries
            .sortedBy { it.key.substringAfterLast('.').toInt() }
            .joinToString("") { it.value }
    }

    private fun deviceInfo(): JSONObject {
        val appVersion = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }
        return JSONObject()
            .put("platform", "android")
            .put("osVersion", Build.VERSION.RELEASE ?: "")
            .put("sdkVersion", VersoConnect.version)
            .put("model", Build.MODEL ?: "")
            .put("appVersion", appVersion)
    }

    // ------------------------------------------------------------- Finish

    private fun deliver(code: Int, data: Intent?) {
        if (finished) return
        finished = true
        handler.removeCallbacksAndMessages(null)
        if (::webView.isInitialized) webView.stopLoading()
        // The provider's state is gone by the time the app gets the result;
        // onDestroy clears again for anything a late response set.
        clearProviderState()
        setResult(code, data)
        VersoConnect.pendingCallback?.let { callback ->
            VersoConnect.pendingCallback = null
            callback(parseResult(code, data))
        }
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
        clearProviderState()
        super.onDestroy()
    }

    /**
     * Expires the provider's cookies and deletes its web storage: before the
     * login, so no stale session decides the account; after it, so nothing of
     * the session stays on the device. The cookie jar is the host app's own,
     * shared with its WebViews, so only the provider's domains are touched
     * (see [ProviderState]).
     */
    private fun clearProviderState() {
        val started = start ?: return
        val domains = listOfNotNull(started.cookieDomain, Uri.parse(started.loginUrl).host, Uri.parse(started.sessionUrl).host)
        val hosts = ProviderState.hostsToClear(domains, visitedHosts)
        val leftover = ProviderState.clear(CookieManager.getInstance(), WebStorage.getInstance(), hosts)
        if (leftover.isNotEmpty()) Log.w("VersoConnect", "provider cookies still present on ${leftover.joinToString()}")
    }
}
