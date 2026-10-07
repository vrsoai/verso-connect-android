package ai.tryverso.connect

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract

/**
 * Lets a user connect their ChatGPT account to Verso from inside your app.
 *
 * Your backend signs a connect link with `signLink()` from `@versoai/core`
 * and returns it to the app; the app hands it to [present] (or launches
 * [VersoConnectContract]). The provider's login opens in a WebView that
 * behaves like the system browser, the captured session goes to Verso, and
 * the screen closes itself in every case.
 */
object VersoConnect {
    const val version = "0.1.2"

    private const val DEFAULT_BASE_URL = "https://connect.tryverso.ai"

    /**
     * Where the SDK talks to. By default the host of the connect link itself,
     * so a link minted for a staging environment goes to staging without any
     * configuration. Set it to force another host.
     */
    @JvmStatic
    var baseUrl: String = DEFAULT_BASE_URL

    internal var pendingCallback: ((Result<VersoConnection>) -> Unit)? = null

    /**
     * Opens the connect screen on top of [activity] and calls [callback] once,
     * on the main thread, with the connection or a [VersoConnectException].
     * The link is single use and valid 15 minutes: fetch a fresh one each
     * time the user taps your button.
     */
    @JvmStatic
    fun present(activity: Activity, link: Uri, callback: (Result<VersoConnection>) -> Unit) {
        val token = tokenFrom(link)
        if (token == null) {
            callback(Result.failure(VersoConnectException.InvalidLink()))
            return
        }
        pendingCallback = callback
        activity.startActivity(VersoConnectActivity.intent(activity, token, baseFor(link)))
    }

    /** The API host for a link: the link's own origin unless [baseUrl] was overridden. */
    internal fun baseFor(link: Uri): String =
        if (baseUrl == DEFAULT_BASE_URL) originOf(link) ?: baseUrl else baseUrl

    /** `scheme://host[:port]` of a link, where its API lives. */
    internal fun originOf(link: Uri): String? {
        val scheme = link.scheme ?: return null
        val host = link.host ?: return null
        return if (link.port > 0) "$scheme://$host:${link.port}" else "$scheme://$host"
    }

    /** The `token` query parameter, or a bare token as the last path segment. */
    internal fun tokenFrom(link: Uri): String? {
        link.getQueryParameter("token")?.takeIf { it.isNotEmpty() }?.let { return it }
        val last = link.lastPathSegment ?: return null
        return last.takeIf { it.count { c -> c == '.' } == 2 }
    }
}

/** A connection created for the user who just logged in. */
data class VersoConnection(val connectionId: String)

sealed class VersoConnectException(message: String) : Exception(message) {
    /** The link carries no token. */
    class InvalidLink : VersoConnectException("The connect link carries no token.")

    /** The user closed the screen. */
    class Cancelled : VersoConnectException("The user cancelled.")

    /**
     * Verso refused: an expired link (401), a link already used (403), or a
     * ChatGPT account already connected by another user of your app (409).
     * Sign a new link.
     */
    class Rejected(val status: Int, message: String) : VersoConnectException(message)

    /** The provider session never became usable. */
    class TimedOut : VersoConnectException("The provider session was not ready in time.")

    /** The request to Verso failed. */
    class Network(cause: Throwable) : VersoConnectException(cause.message ?: "Network error") {
        init { initCause(cause) }
    }
}

/**
 * Activity Result contract for the connect screen:
 * `registerForActivityResult(VersoConnectContract()) { result -> … }`, then
 * `launch(link)`.
 */
class VersoConnectContract : ActivityResultContract<Uri, Result<VersoConnection>>() {
    override fun createIntent(context: Context, input: Uri): Intent =
        VersoConnectActivity.intent(context, VersoConnect.tokenFrom(input) ?: "", VersoConnect.baseFor(input))

    override fun parseResult(resultCode: Int, intent: Intent?): Result<VersoConnection> =
        VersoConnectActivity.parseResult(resultCode, intent)
}
