package com.afluffywaffle.avosetta

import android.app.Activity
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController

/**
 * Avosetta — the Extensions page.
 *
 * Reached from the puzzle button in the layout editor's top bar. Everything else that
 * used to live in the classic settings list moved into the visual editor (rails, chrome
 * bar) or the Rendering page (fonts / tracking / JS / images / e-ink refresh); all that
 * remains here is turning bundled WebExtensions on and off. Reads/writes the SAME
 * SharedPreferences ("distesa_settings") as the rest; MainActivity re-reads them and
 * reloads as needed in onResume. Same house style: white page, no animation, dark ink.
 */
class SettingsActivity : Activity() {

    private lateinit var prefs: SharedPreferences

    private var ublock: WebExtension? = null
    private var ublockBusy = false
    private var ublockBtn: Button? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("distesa_settings", MODE_PRIVATE)

        title = "Extensions"

        val root = ScrollView(this).apply {
            setBackgroundColor(0xFFFFFFFF.toInt()) // white page — never a dark band
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        // Explicit exit — this Activity runs under a NoActionBar theme and the
        // Supernote shell hides the system back button, so without this the page is
        // a dead end. Also handled via onBackPressed for the hardware/gesture back.
        panel.addView(Button(this).apply {
            text = "‹ Done"
            setTextColor(0xFF222222.toInt())
            setBackgroundColor(Color.TRANSPARENT)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            textSize = 18f
            setOnClickListener { finish() }
        })

        panel.addView(pageTitle("Extensions"))
        panel.addView(intro(
            "Bundled WebExtensions. Turning one off reloads the current page so the change " +
            "takes effect right away."
        ))
        ublockBtn = makeButton("uBlock: …") { onToggleUBlock() }.apply {
            isEnabled = false
            textSize = 16f
        }
        panel.addView(ublockBtn)
        panel.addView(explain(
            "uBlock Origin blocks ads and trackers site-wide. Off, pages load with their " +
            "ads intact — heavier, but occasionally needed when a site misbehaves."
        ))

        root.addView(panel)
        setContentView(root)

        resolveUBlock()
    }

    private fun pageTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(MainActivity.CHROME_INK)
        textSize = 22f
        setPadding(0, dp(8), 0, dp(4))
    }

    private fun intro(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(MainActivity.CHROME_INK)
        textSize = 16f
        alpha = 0.8f
        setPadding(0, 0, 0, dp(12))
    }

    private fun explain(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(MainActivity.CHROME_INK)
        textSize = 15f
        alpha = 0.7f
        setPadding(dp(4), 0, 0, dp(16))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun makeButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            setTextColor(MainActivity.CHROME_INK)
            setBackgroundColor(Color.TRANSPARENT)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setOnClickListener { onClick() }
        }

    // --- uBlock on/off (self-contained; drives the shared runtime) -----------

    private fun resolveUBlock() {
        val controller = MainActivity.sharedRuntime(this).webExtensionController
        controller.list().accept(
            { installed ->
                ublock = installed?.firstOrNull { it.id == MainActivity.UBLOCK_ID }
                runOnUiThread { ublockBtn?.isEnabled = ublock != null; refreshUBlockLabel() }
            },
            { e ->
                Log.w(TAG, "uBlock list failed: ${e?.message}")
                runOnUiThread { refreshUBlockLabel() }
            },
        )
    }

    private fun onToggleUBlock() {
        val ext = ublock ?: return
        if (ublockBusy) return
        ublockBusy = true
        ublockBtn?.isEnabled = false
        val controller = MainActivity.sharedRuntime(this).webExtensionController
        val result = if (ext.metaData.enabled) {
            controller.disable(ext, WebExtensionController.EnableSource.USER)
        } else {
            controller.enable(ext, WebExtensionController.EnableSource.USER)
        }
        result.accept(
            { updated ->
                ublock = updated
                ublockBusy = false
                // Flag so MainActivity reloads the page when we return.
                prefs.edit().putBoolean("ublockDirty", true).apply()
                runOnUiThread { ublockBtn?.isEnabled = true; refreshUBlockLabel() }
            },
            { e ->
                Log.w(TAG, "uBlock toggle failed: ${e?.message}")
                ublockBusy = false
                runOnUiThread { ublockBtn?.isEnabled = true; refreshUBlockLabel() }
            },
        )
    }

    private fun refreshUBlockLabel() {
        ublockBtn?.text = when {
            ublock == null -> "uBlock: n/a"
            ublock?.metaData?.enabled == true -> "uBlock: on"
            else -> "uBlock: off"
        }
    }

    companion object {
        private const val TAG = "DistesaSettings"
    }
}
