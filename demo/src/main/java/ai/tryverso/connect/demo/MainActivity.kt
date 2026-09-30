package ai.tryverso.connect.demo

import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import ai.tryverso.connect.VersoConnect
import ai.tryverso.connect.VersoConnectException

/**
 * Paste a connect link, tap Connect. A `link` string extra prefills the field:
 * `adb shell am start -n ai.tryverso.connect.demo/.MainActivity --es link '<url>'`.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        val pad = (24 * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "Verso Connect demo"
            textSize = 22f
            gravity = Gravity.CENTER
        })
        val link = EditText(this).apply {
            hint = "https://connect.tryverso.ai/start?token=…"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            setText(intent.getStringExtra("link") ?: "")
        }
        root.addView(link, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = pad })
        val status = TextView(this).apply {
            text = "Paste a connect link, then tap Connect."
            gravity = Gravity.CENTER
        }
        val button = Button(this).apply { text = "Connect ChatGPT" }
        root.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = pad / 2 })
        root.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = pad / 2 })
        setContentView(root)

        button.setOnClickListener {
            val uri = Uri.parse(link.text.toString().trim())
            button.isEnabled = false
            status.text = "Opening ChatGPT…"
            VersoConnect.present(this, uri) { result ->
                button.isEnabled = true
                status.text = result.fold(
                    onSuccess = { "Connected. connectionId: ${it.connectionId}" },
                    onFailure = { e ->
                        when (e) {
                            is VersoConnectException.Cancelled -> "Cancelled."
                            is VersoConnectException.Rejected -> "Rejected (${e.status}): ${e.message}"
                            else -> "Failed: ${e.message}"
                        }
                    },
                )
            }
        }
    }
}
