package ai.tryverso.connect.demo

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import ai.tryverso.connect.VersoConnect
import ai.tryverso.connect.VersoConnectException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * Where this demo's backend lives, and the key that stands in for a user
 * session. Both come from local.properties (or the environment) through
 * BuildConfig, so they stay out of git. A real app sends its own session
 * token instead of a shared key.
 */
object DemoConfig {
    val BACKEND_URL: String = BuildConfig.DEMO_BACKEND_URL
    val DEMO_KEY: String = BuildConfig.DEMO_KEY
}

/**
 * A stand-in for a partner app: one screen, one button. Tapping it asks the
 * partner backend for a connect link, then hands that link to VersoConnect.
 *
 * Debug only: a `link` string extra skips the backend:
 * `adb shell am start -n ai.tryverso.connect.demo/.MainActivity --es link '<url>'`.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var button: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("demo", MODE_PRIVATE)
        // One stable id per install: what a real app would derive from its own user.
        val userRef = prefs.getString("userRef", null) ?: ("demo-" + UUID.randomUUID().toString().take(8)).also {
            prefs.edit().putString("userRef", it).apply()
        }
        val density = resources.displayMetrics.density
        val pad = (24 * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad * 2, pad, pad)
        }
        fun row(view: android.view.View, top: Int = pad / 2) = root.addView(
            view,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = top },
        )
        row(TextView(this).apply { text = "Partner app"; textSize = 28f; gravity = Gravity.CENTER }, 0)
        row(TextView(this).apply {
            text = "Connect your ChatGPT account to bring your conversations into this app."
            gravity = Gravity.CENTER
        })
        button = Button(this).apply { text = "Connect ChatGPT" }
        row(button, pad)
        status = TextView(this).apply { gravity = Gravity.CENTER }
        row(status)
        row(TextView(this).apply { text = "user $userRef"; textSize = 11f; gravity = Gravity.CENTER; alpha = 0.5f }, pad)
        setContentView(root)

        prefs.getString("connectionId", null)?.let { showConnected(it) }

        button.setOnClickListener {
            button.isEnabled = false
            val debugLink = intent.getStringExtra("link")
            if (debugLink != null) {
                present(Uri.parse(debugLink), prefs)
            } else {
                status.text = "Asking the backend for a connect link…"
                thread {
                    val result = runCatching { PartnerBackend.connectLink(userRef) }
                    runOnUiThread {
                        result.fold(
                            onSuccess = { present(it, prefs) },
                            onFailure = { e -> button.isEnabled = true; status.text = "Failed: ${e.message}" },
                        )
                    }
                }
            }
        }
    }

    private fun present(link: Uri, prefs: android.content.SharedPreferences) {
        status.text = "Opening ChatGPT…"
        // A cookie standing in for this app's own WebView session: the flow must leave it alone.
        CookieManager.getInstance().setCookie("https://partner.example", "session=kept; Path=/")
        VersoConnect.present(this, link) { result ->
            button.isEnabled = true
            fun report(moment: String) {
                val cookies = CookieManager.getInstance()
                Log.i(
                    "VersoDemo",
                    "$moment: app cookie kept=" + (cookies.getCookie("https://partner.example")?.contains("session=kept") == true) +
                        ", provider cookies left=" + (cookies.getCookie("https://chatgpt.com") != null),
                )
            }
            report("at result")
            status.postDelayed({ report("2s later") }, 2_000)
            result.fold(
                onSuccess = {
                    prefs.edit().putString("connectionId", it.connectionId).apply()
                    showConnected(it.connectionId)
                },
                onFailure = { e ->
                    status.text = when (e) {
                        is VersoConnectException.Cancelled -> "Cancelled."
                        is VersoConnectException.Rejected -> "Rejected (${e.status}): ${e.message}"
                        else -> "Failed: ${e.message}"
                    }
                },
            )
        }
    }

    private fun showConnected(connectionId: String) {
        status.text = "ChatGPT connected\nconnection $connectionId"
        button.text = "Reconnect"
    }
}

/** The one call a partner app makes to its own backend before showing the flow. */
object PartnerBackend {
    fun connectLink(userRef: String): Uri {
        val connection = (URL(DemoConfig.BACKEND_URL + "/connect-link").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer " + DemoConfig.DEMO_KEY)
        }
        try {
            connection.outputStream.use { it.write(JSONObject().put("userRef", userRef).toString().toByteArray()) }
            if (connection.responseCode != 200) throw IOException("backend answered ${connection.responseCode}")
            val body = connection.inputStream.bufferedReader().readText()
            return Uri.parse(JSONObject(body).getString("url"))
        } finally {
            connection.disconnect()
        }
    }
}
