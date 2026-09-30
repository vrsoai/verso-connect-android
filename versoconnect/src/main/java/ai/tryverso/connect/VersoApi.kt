package ai.tryverso.connect

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** The two calls of `POST /api/connect-native`. */
internal class VersoApi(private val baseUrl: String) {

    data class Start(
        val captureNonce: String,
        val clientSecret: String,
        val loginUrl: String,
        val cookieDomain: String,
        val cookieName: String,
        val sessionUrl: String,
    )

    sealed class Capture {
        data class Connected(val connectionId: String) : Capture()
        data object Waiting : Capture()
    }

    suspend fun start(token: String, device: JSONObject): Start = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("action", "start")
            .put("token", token)
            .put("device", device)
        val (status, json) = post(body)
        if (status != 200) throw rejected(status, json)
        try {
            Start(
                captureNonce = json.getString("captureNonce"),
                clientSecret = json.getString("clientSecret"),
                loginUrl = json.getString("loginUrl"),
                cookieDomain = json.getString("cookieDomain"),
                cookieName = json.getString("cookieName"),
                sessionUrl = json.getString("sessionUrl"),
            )
        } catch (e: JSONException) {
            throw VersoConnectException.Network(e)
        }
    }

    suspend fun capture(
        captureNonce: String,
        clientSecret: String,
        sessionToken: String,
        accessToken: String?,
        providerAccountId: String?,
        providerAccountEmail: String?,
    ): Capture = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("action", "capture")
            .put("captureNonce", captureNonce)
            .put("clientSecret", clientSecret)
            .put("sessionToken", sessionToken)
        accessToken?.let { body.put("accessToken", it) }
        providerAccountId?.let { body.put("providerAccountId", it) }
        providerAccountEmail?.let { body.put("providerAccountEmail", it) }
        val (status, json) = post(body)
        if (status != 200) throw rejected(status, json)
        if (json.optString("status") == "connected") {
            Capture.Connected(json.optString("connectionId"))
        } else {
            Capture.Waiting
        }
    }

    private fun post(body: JSONObject): Pair<Int, JSONObject> {
        val connection = try {
            (URL("$baseUrl/api/connect-native").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 20_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "VersoConnect-Android/${VersoConnect.version}")
            }
        } catch (e: IOException) {
            throw VersoConnectException.Network(e)
        }
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status < 400) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            val json = try { JSONObject(text) } catch (e: JSONException) { JSONObject() }
            return status to json
        } catch (e: IOException) {
            throw VersoConnectException.Network(e)
        } finally {
            connection.disconnect()
        }
    }

    private fun rejected(status: Int, json: JSONObject) =
        VersoConnectException.Rejected(status, json.optString("error", "Request failed ($status)"))
}
