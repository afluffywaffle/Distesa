package com.afluffywaffle.avosetta

import android.app.Activity
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Avosetta — the Supernote (e-ink device) page.
 *
 * The knobs that only make sense because this runs on a slow, ghosting e-ink screen:
 * how aggressively to collapse heavy images so pages settle fast, and how often to do a
 * full black-white flash to clear ghosting. Split out from the Rendering page (which was
 * overflowing) so each page stays a single glance. Reads/writes the SAME SharedPreferences
 * ("distesa_settings"); MainActivity re-reads them in onResume. White page, dark ink.
 */
class SupernoteActivity : Activity() {

    private lateinit var prefs: SharedPreferences

    private var collapseMode = "auto"
    private var collapseThreshold = 6
    private var fullEvery = 6

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("distesa_settings", MODE_PRIVATE)
        collapseMode = prefs.getString("collapseMode", "auto") ?: "auto"
        collapseThreshold = prefs.getInt("collapseThreshold", 6)
        fullEvery = prefs.getInt("fullEvery", 6)

        title = "Supernote"

        val root = ScrollView(this).apply {
            setBackgroundColor(0xFFFFFFFF.toInt()) // white page — never a dark band
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        // Explicit exit — NoActionBar theme + the Supernote shell hides system back.
        panel.addView(Button(this).apply {
            text = "‹ Done"
            setTextColor(0xFF222222.toInt())
            setBackgroundColor(Color.TRANSPARENT)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            textSize = 18f
            setOnClickListener { finish() }
        })

        panel.addView(pageTitle("Supernote"))
        panel.addView(intro(
            "Tuning for the e-ink screen — how hard to work to keep pages fast and the " +
            "display clean. These don't change what a site is, only how gently it lands " +
            "on the panel."
        ))

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
        panel.addView(explain(
            "Large images are replaced with a tappable placeholder so pages load fast and " +
            "the screen isn't flooded with slow-to-render photos. \"Auto\" collapses only " +
            "images past the threshold; tap a placeholder to load that one."
        ))

        panel.addView(header("E-ink refresh"))
        panel.addView(makeCycleRow({ cadenceLabel() }) {
            val steps = MainActivity.CADENCE_STEPS
            val idx = steps.indexOf(fullEvery).let { if (it < 0) 0 else it }
            fullEvery = steps[(idx + 1) % steps.size]
            prefs.edit().putInt("fullEvery", fullEvery).apply()
        })
        panel.addView(explain(
            "How often the screen does a full black-white flash to clear ghosting. More " +
            "frequent = cleaner image but more flicker; less frequent = calmer but faint " +
            "ghosts may linger. \"Off\" never force-clears."
        ))

        root.addView(panel)
        setContentView(root)
    }

    // --- house-style helpers (mirrors RenderingActivity) ----------------------

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

    private fun header(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(MainActivity.CHROME_INK)
        textSize = 13f
        setPadding(0, dp(12), 0, dp(2))
        alpha = 0.6f
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

    /** A button whose label is re-read from [label] after each tap (cycle control). */
    private fun makeCycleRow(label: () -> String, onClick: () -> Unit): Button {
        val b = makeButton(label(), {})
        b.textSize = 16f
        b.setOnClickListener { onClick(); b.text = label() }
        return b
    }

    private fun cadenceLabel(): String =
        "Full-clear: " + if (fullEvery <= 0) "Off" else fullEvery.toString()
}
