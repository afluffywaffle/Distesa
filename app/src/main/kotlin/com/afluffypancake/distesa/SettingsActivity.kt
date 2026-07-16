package com.afluffypancake.distesa

import android.app.Activity
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController

/**
 * Distesa — the dedicated, full settings page.
 *
 * Hosts everything that isn't a fast, frequently-flipped toggle (those stay in the
 * chrome-bar pop-up in [MainActivity.buildSettingsPanel]). Reads and writes the SAME
 * SharedPreferences ("distesa_settings"); MainActivity re-reads them + pushes
 * content-script levers in onResume, applying structural changes (recreate) and
 * reloads as needed. Same house style: LIGHT background, no animation, bordered
 * surface, transparent-fill buttons with dark ink.
 */
class SettingsActivity : Activity() {

    private lateinit var prefs: SharedPreferences

    // Local mirrors of the persisted settings, edited by the rows below.
    private var searchEngine = "DuckDuckGo"
    private var navStyle = "inset"
    private var navPlacement = "both"
    private var showZones = true
    private var blockFonts = true
    private var strictTp = true
    private var jsEnabled = true
    private var fullEvery = 6
    private var collapseMode = "auto"
    private var collapseThreshold = 6
    private var edgeSlotLeft = "chrome"
    private var edgeSlotRight = "collapse"

    private var ublock: WebExtension? = null
    private var ublockBusy = false
    private var ublockBtn: Button? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("distesa_settings", MODE_PRIVATE)
        searchEngine = prefs.getString("searchEngine", "DuckDuckGo") ?: "DuckDuckGo"
        navStyle = prefs.getString("navStyle", "inset") ?: "inset"
        navPlacement = prefs.getString("navPlacement", "both") ?: "both"
        showZones = prefs.getBoolean("showZones", true)
        blockFonts = prefs.getBoolean("blockFonts", true)
        strictTp = prefs.getBoolean("strictTp", true)
        jsEnabled = prefs.getBoolean("jsEnabled", true)
        fullEvery = prefs.getInt("fullEvery", 6)
        collapseMode = prefs.getString("collapseMode", "auto") ?: "auto"
        collapseThreshold = prefs.getInt("collapseThreshold", 6)
        edgeSlotLeft = prefs.getString("edgeSlotLeft", "chrome") ?: "chrome"
        edgeSlotRight = prefs.getString("edgeSlotRight", "collapse") ?: "collapse"

        title = "Distesa settings"

        val root = ScrollView(this).apply {
            setBackgroundColor(0xFFFFFFFF.toInt()) // white page — never a dark band
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // No bordered card here: this is a full PAGE (the ScrollView already owns
            // the white surface), unlike the floating quick-panel which needs a border
            // to separate it from the page behind it. Flat page = the e-ink default.
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

        panel.addView(header("Search"))
        panel.addView(makeCycleRow({ "Search engine: $searchEngine" }) {
            val names = MainActivity.SEARCH_ENGINES.keys.toList()
            searchEngine = names[(names.indexOf(searchEngine).coerceAtLeast(0) + 1) % names.size]
            prefs.edit().putString("searchEngine", searchEngine).apply()
        })

        panel.addView(header("Reading"))
        panel.addView(makeSwitch("Block web fonts", blockFonts) { on ->
            blockFonts = on; prefs.edit().putBoolean("blockFonts", on).apply()
        })
        panel.addView(makeSwitch("Strict tracking protection", strictTp) { on ->
            strictTp = on; prefs.edit().putBoolean("strictTp", on).apply()
        })
        panel.addView(makeSwitch("JavaScript", jsEnabled) { on ->
            jsEnabled = on; prefs.edit().putBoolean("jsEnabled", on).apply()
        })

        panel.addView(header("Images: auto-collapse"))
        panel.addView(makeCycleRow({ "Collapse mode: ${collapseMode.replaceFirstChar { it.uppercase() }}" }) {
            collapseMode = when (collapseMode) { "auto" -> "always"; "always" -> "never"; else -> "auto" }
            prefs.edit().putString("collapseMode", collapseMode).apply()
        })
        panel.addView(makeCycleRow({ "Auto threshold: $collapseThreshold" }) {
            val steps = MainActivity.COLLAPSE_THRESHOLD_STEPS
            val idx = steps.indexOf(collapseThreshold).let { if (it < 0) 0 else it }
            collapseThreshold = steps[(idx + 1) % steps.size]
            prefs.edit().putInt("collapseThreshold", collapseThreshold).apply()
        })

        panel.addView(header("E-ink refresh"))
        panel.addView(makeCycleRow({ cadenceLabel() }) {
            val steps = MainActivity.CADENCE_STEPS
            val idx = steps.indexOf(fullEvery).let { if (it < 0) 0 else it }
            fullEvery = steps[(idx + 1) % steps.size]
            prefs.edit().putInt("fullEvery", fullEvery).apply()
        })

        panel.addView(header("Navigation"))
        panel.addView(makeCycleRow({ "Nav zones: $navStyle" }) {
            navStyle = if (navStyle == "inset") "overlay" else "inset"
            prefs.edit().putString("navStyle", navStyle).apply()
        })
        panel.addView(makeCycleRow({ "Nav side: $navPlacement" }) {
            navPlacement = when (navPlacement) { "both" -> "left"; "left" -> "right"; else -> "both" }
            prefs.edit().putString("navPlacement", navPlacement).apply()
        })
        panel.addView(makeSwitch("Show tap zones", showZones) { on ->
            showZones = on; prefs.edit().putBoolean("showZones", on).apply()
        })
        // Edge slivers: a button at the bottom of each nav strip, always visible even
        // with tap zones off. One is normally "chrome" (the only way to open the
        // toolbar); a load-time guard keeps at least one slot on chrome.
        panel.addView(makeCycleRow({ "Left edge button: ${slotLabel(edgeSlotLeft)}" }) {
            edgeSlotLeft = nextSlot(edgeSlotLeft)
            prefs.edit().putString("edgeSlotLeft", edgeSlotLeft).apply()
        })
        panel.addView(makeCycleRow({ "Right edge button: ${slotLabel(edgeSlotRight)}" }) {
            edgeSlotRight = nextSlot(edgeSlotRight)
            prefs.edit().putString("edgeSlotRight", edgeSlotRight).apply()
        })

        panel.addView(header("Extensions"))
        ublockBtn = makeButton("uBlock: …") { onToggleUBlock() }.apply { isEnabled = false }
        panel.addView(ublockBtn)

        root.addView(panel)
        setContentView(root)

        resolveUBlock()
    }

    private fun header(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(MainActivity.CHROME_INK)
        textSize = 13f
        setPadding(0, dp(12), 0, dp(2))
        alpha = 0.6f
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /**
     * A boolean row rendered as an explicit checkbox glyph + ON/OFF text, NOT a
     * SwitchCompat — on grayscale e-ink a switch's thumb position and tint are hard
     * to read, so state was ambiguous. ☑ / ☐ + word is unambiguous with no colour.
     */
    private fun makeSwitch(label: String, initial: Boolean, onChange: (Boolean) -> Unit): Button {
        var state = initial
        fun render(): String = (if (state) "☑  " else "☐  ") + label + (if (state) "   · ON" else "   · OFF")
        return makeButton(render()) {}.apply {
            text = render()
            textSize = 16f
            setOnClickListener {
                state = !state
                text = render()
                onChange(state)
            }
        }
    }

    private fun makeButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            setTextColor(MainActivity.CHROME_INK)
            setBackgroundColor(Color.TRANSPARENT)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setOnClickListener { onClick() }
        }

    /** A button whose label is re-read from [label] after each tap (cycle control). */
    private fun makeCycleRow(label: () -> String, onClick: () -> Unit): Button {
        val b = makeButton(label(), {})
        b.setOnClickListener { onClick(); b.text = label() }
        return b
    }

    private fun cadenceLabel(): String =
        "Full-clear: " + if (fullEvery <= 0) "Off" else fullEvery.toString()

    /** Edge-sliver slot cycle. "favorites" is reserved for a future feature. */
    private fun nextSlot(slot: String): String =
        when (slot) { "chrome" -> "collapse"; "collapse" -> "none"; else -> "chrome" }

    private fun slotLabel(slot: String): String = when (slot) {
        "chrome" -> "☰ Toolbar"
        "collapse" -> "⊟ Collapse"
        else -> "None"
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
