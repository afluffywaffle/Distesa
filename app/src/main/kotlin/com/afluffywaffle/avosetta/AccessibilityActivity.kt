package com.afluffywaffle.avosetta

import android.app.Activity
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Avosetta — the Accessibility page.
 *
 * The two contrast/colour levers that pull web content toward the light,
 * high-contrast baseline an e-ink panel renders best. The screen is grayscale, so a
 * site's dark theme or coloured/low-contrast text just reads as muddy grey; these
 * fix that.
 *
 * - "Prefer light pages" — engine-level preferredColorScheme=LIGHT. Only asks sites
 *   that honour prefers-color-scheme for their light variant; can't break anything,
 *   so it's a plain toggle.
 * - "Force black text on white" — a content-script CSS override (images.js) that
 *   guarantees black-on-white however the site styled itself. Aggressive (flattens
 *   meaningful colour), so it confirms before turning ON.
 *
 * Reads/writes the SAME SharedPreferences ("distesa_settings") as everything else;
 * MainActivity re-reads them in onResume — applying the colour scheme at the engine
 * and pushing forceLight to the content script. Same house style as the Rendering /
 * Supernote pages: white page, no animation, transparent buttons with dark ink.
 */
class AccessibilityActivity : Activity() {

    private lateinit var prefs: SharedPreferences

    private var preferLight = true
    private var forceLight = true
    private var rawHosts: MutableSet<String> = mutableSetOf()
    private var currentHost = ""

    private lateinit var root: FrameLayout
    private var modal: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("distesa_settings", MODE_PRIVATE)
        preferLight = prefs.getBoolean("preferLight", true)
        forceLight = prefs.getBoolean("forceLight", true)
        rawHosts = HashSet(prefs.getStringSet("rawHosts", emptySet()) ?: emptySet())
        currentHost = (prefs.getString("currentHost", "") ?: "").lowercase()

        title = "Accessibility"

        val scroll = ScrollView(this).apply {
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

        panel.addView(pageTitle("Accessibility"))
        panel.addView(intro(
            "The panel is grayscale, so a site's dark theme or low-contrast text just reads " +
            "as muddy grey. These pull every page toward the light, high-contrast look e-ink " +
            "shows best."
        ))

        panel.addView(header("Contrast & colour"))

        // Gentle: only asks sites for their light variant. Can't break anything, so
        // a plain toggle.
        panel.addView(makeSwitch("Prefer light pages", preferLight) { on ->
            preferLight = on; prefs.edit().putBoolean("preferLight", on).apply()
        })
        panel.addView(explain(
            "Tells every site you want its light theme, so pages that follow the system " +
            "dark/light setting serve their light version instead of a dark one that turns " +
            "muddy grey on e-ink. Doesn't force text colour — a site can still ship low " +
            "contrast; that's what the next setting is for."
        ))

        // Aggressive: overrides the page's own colours. Turning it ON is the risky
        // move (flattens meaningful colour), so it confirms first.
        panel.addView(guardedToggle(
            "Force black text on white", "forceLight", forceLight, riskyValue = true,
            impactTitle = "Force black on white?",
            impactBody = "Every page is redrawn as black text on a white background, ignoring " +
                "the site's own colours. Legibility is guaranteed, but colour-coded things — " +
                "link colours, syntax highlighting — all become plain black. Pictures are " +
                "left alone.",
        ) { forceLight = it })
        panel.addView(explain(
            "Overrides each page's colours with solid black-on-white — the strong guarantee " +
            "that text is always legible, however the site styled it. Since the panel is " +
            "grayscale, the colour it removes carried no information anyway. Images, logos " +
            "and icons are left as they are."
        ))

        // --- Per-site escape hatch --------------------------------------------
        panel.addView(header("Per-site"))
        if (currentHost.isEmpty()) {
            panel.addView(explain(
                "Open a site first, then come back here to turn off all e-ink processing for " +
                "that site. (There's also a “Render as-is” button in the ⚙ quick panel while browsing.)"
            ))
        } else {
            panel.addView(rawRow(currentHost))
            panel.addView(explain(
                "Renders " + currentHost + " exactly as the site ships it — turning OFF every " +
                "e-ink lever for this site only (fonts, JavaScript, tracking protection, colour, " +
                "image gating). Your global settings above are untouched; other sites keep them. " +
                "Use it when a site looks broken under the e-ink treatment. Takes effect on reload."
            ))
        }

        scroll.addView(panel)
        root = FrameLayout(this).apply { addView(scroll) }
        setContentView(root)
    }

    override fun onBackPressed() {
        if (modal != null) dismissModal() else super.onBackPressed()
    }

    /**
     * A ☑/☐ toggle for whether [host] is in the "render as-is" set. Persists to the
     * SAME `rawHosts` pref MainActivity reads; on return, its onResume detects the change
     * for the current host and reloads/applies. Turning it ON (the bypass) confirms first.
     */
    private fun rawRow(host: String): Button {
        val b = makeButton("", {})
        b.textSize = 16f
        fun render() {
            val on = rawHosts.contains(host)
            b.text = (if (on) "☑  " else "☐  ") + "Render as-is" +
                (if (on) "   · ON" else "   · OFF")
        }
        fun apply(on: Boolean) {
            if (on) rawHosts.add(host) else rawHosts.remove(host)
            prefs.edit().putStringSet("rawHosts", rawHosts).apply()
            render()
        }
        render()
        b.setOnClickListener {
            val turningOn = !rawHosts.contains(host)
            if (turningOn) confirmImpact(
                "Render $host as-is?",
                "This turns OFF all e-ink processing for $host — fonts, JavaScript, tracking " +
                    "protection, colour and image gating — and loads it like an ordinary browser. " +
                    "Only this site is affected; your global settings stay as they are.",
            ) { apply(true) }
            else apply(false)
        }
        return b
    }

    // --- shared house-style helpers (mirrors RenderingActivity) ----------------

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

    /** The explanation under a toggle. Bigger than a caption — this is meant to be read. */
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

    /**
     * A boolean row (checkbox glyph + ON/OFF word). Moving to [riskyValue] first raises
     * an impact popup: accept applies + writes the pref, decline leaves it. The other
     * direction applies silently. [commit] mirrors the new value into the Activity field.
     */
    private fun guardedToggle(
        label: String, prefKey: String, initial: Boolean, riskyValue: Boolean,
        impactTitle: String, impactBody: String, commit: (Boolean) -> Unit,
    ): Button {
        var state = initial
        val b = makeButton("", {})
        b.textSize = 16f
        fun render() {
            b.text = (if (state) "☑  " else "☐  ") + label + (if (state) "   · ON" else "   · OFF")
        }
        fun apply(next: Boolean) {
            state = next
            commit(next)
            prefs.edit().putBoolean(prefKey, next).apply()
            render()
        }
        render()
        b.setOnClickListener {
            val next = !state
            if (next == riskyValue) confirmImpact(impactTitle, impactBody) { apply(next) }
            else apply(next)
        }
        return b
    }

    // --- impact confirmation popup (custom scrim/pane, e-ink friendly) ---------

    /** Centred card over a tap-to-dismiss scrim: title, impact text, Cancel / Continue. */
    private fun confirmImpact(title: String, body: String, onAccept: () -> Unit) {
        dismissModal()
        val scrim = FrameLayout(this).apply {
            setBackgroundColor(0x22000000)
            isClickable = true
            setOnClickListener { dismissModal() }
        }
        val pane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = borderBg()
            setPadding(dp(20), dp(18), dp(20), dp(14))
            isClickable = true // swallow taps so they don't dismiss via the scrim
        }
        pane.addView(TextView(this).apply {
            text = title; setTextColor(MainActivity.CHROME_INK); textSize = 19f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(8))
        })
        pane.addView(TextView(this).apply {
            text = body; setTextColor(MainActivity.CHROME_INK); textSize = 16f; alpha = 0.8f
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(0, 0, 0, dp(14))
        })
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END
        }
        buttons.addView(makeButton("Cancel") { dismissModal() }.apply {
            textSize = 16f; gravity = Gravity.CENTER
            setPadding(dp(16), dp(6), dp(16), dp(6))
        })
        buttons.addView(makeButton("Continue") { dismissModal(); onAccept() }.apply {
            textSize = 16f; gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(16), dp(6), dp(16), dp(6))
        })
        pane.addView(buttons, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        scrim.addView(pane, FrameLayout.LayoutParams(dp(340), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        root.addView(scrim, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        modal = scrim
    }

    private fun dismissModal() {
        modal?.let { root.removeView(it) }
        modal = null
    }

    private fun borderBg(): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFFFFFFFF.toInt())
            setStroke(dp(1), Color.rgb(120, 120, 120))
            cornerRadius = dp(10).toFloat()
        }

    private fun makeButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            setTextColor(MainActivity.CHROME_INK)
            setBackgroundColor(Color.TRANSPARENT)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setOnClickListener { onClick() }
        }

    /** A plain ☑/☐ toggle (no impact prompt), for a setting that can't break sites. */
    private fun makeSwitch(label: String, initial: Boolean, onChange: (Boolean) -> Unit): Button {
        var state = initial
        val b = makeButton("", {})
        b.textSize = 16f
        fun render() {
            b.text = (if (state) "☑  " else "☐  ") + label + (if (state) "   · ON" else "   · OFF")
        }
        render()
        b.setOnClickListener { state = !state; render(); onChange(state) }
        return b
    }
}
