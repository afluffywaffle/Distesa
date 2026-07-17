package com.afluffywaffle.avosetta

import android.app.Activity
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Avosetta — the Rendering page.
 *
 * The three levers that decide how much of a site actually runs: web fonts, tracking
 * protection, and JavaScript. They used to be a bare "Reading" section of toggles;
 * here each one gets a plain-language explanation plus a comparison table of its
 * performance win versus how much page rendering depends on it — because on e-ink the
 * right trade-off (fewer repaints, longer battery) is worth understanding, not guessing.
 *
 * Reads/writes the SAME SharedPreferences ("distesa_settings") as everything else;
 * MainActivity re-reads them and pushes the content-script levers in onResume. Same
 * house style: white page, no animation, transparent-fill buttons with dark ink.
 */
class RenderingActivity : Activity() {

    private lateinit var prefs: SharedPreferences

    private var blockFonts = true
    private var strictTp = true
    private var jsEnabled = true
    private var animOff = true

    private lateinit var root: FrameLayout
    private var modal: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("distesa_settings", MODE_PRIVATE)
        blockFonts = prefs.getBoolean("blockFonts", true)
        strictTp = prefs.getBoolean("strictTp", true)
        jsEnabled = prefs.getBoolean("jsEnabled", true)
        animOff = prefs.getBoolean("animOff", true)

        title = "Rendering"

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

        panel.addView(pageTitle("Rendering"))
        panel.addView(intro(
            "How much of each page Avosetta actually downloads and runs. Turning these " +
            "off makes pages lighter and faster and spares the e-ink screen needless " +
            "repaints — at the cost of some sites not looking or working quite right."
        ))

        // The three levers. Each confirms before the RESTRICTIVE move — the one that
        // makes the browser render less and can break sites (block fonts ON, strict
        // protection ON, JavaScript OFF). Re-enabling a capability is always safe, so it
        // applies with no prompt. riskyValue is the pref value that triggers the popup.
        panel.addView(guardedToggle(
            "Block web fonts", "blockFonts", blockFonts, riskyValue = true,
            impactTitle = "Block web fonts?",
            impactBody = "Pages will use the device's built-in fonts. Sites that rely on an " +
                "icon-font for their buttons may show empty boxes instead of icons.",
        ) { blockFonts = it })
        panel.addView(explain(
            "Renders pages with the device's built-in fonts instead of downloading each " +
            "site's custom typefaces. Text appears sooner and the page reflows less. " +
            "Sites that use an icon-font for their buttons may show empty boxes."
        ))

        panel.addView(guardedToggle(
            "Strict tracking protection", "strictTp", strictTp, riskyValue = true,
            impactTitle = "Turn on strict tracking protection?",
            impactBody = "Trackers and cross-site scripts are blocked. A few sites' logins " +
                "or embedded content that depend on third-party scripts may stop working.",
        ) { strictTp = it })
        panel.addView(explain(
            "Blocks known trackers and cross-site scripts before they load. Fewer network " +
            "requests means quicker, lighter pages — and more privacy. A few sites lean on " +
            "third-party scripts for logins or embeds and may need it turned off to work."
        ))

        panel.addView(guardedToggle(
            "JavaScript", "jsEnabled", jsEnabled, riskyValue = false,
            impactTitle = "Turn off JavaScript?",
            impactBody = "Pages become static and much faster and the battery lasts far " +
                "longer — but many modern sites need JavaScript to show their content, so " +
                "some may come up blank or won't be interactive.",
        ) { jsEnabled = it })
        panel.addView(explain(
            "Runs the site's own scripts. Off, pages are static and very fast and the " +
            "battery lasts far longer — but many modern sites need JavaScript just to show " +
            "their content, so some will come up blank or won't be interactive."
        ))

        // A plain toggle (no impact prompt): unlike the three above, killing animations
        // doesn't break sites — it just stops motion that would thrash the e-ink panel.
        panel.addView(makeSwitch("Animations off", animOff) { on ->
            animOff = on; prefs.edit().putBoolean("animOff", on).apply()
        })
        panel.addView(explain(
            "Suppresses CSS animations and transitions. On e-ink, motion means constant " +
            "partial repaints and ghosting, so off keeps the screen calm — with no effect " +
            "on what a site shows."
        ))

        panel.addView(header("Impact at a glance"))
        panel.addView(impactTable())

        scroll.addView(panel)
        root = FrameLayout(this).apply { addView(scroll) }
        setContentView(root)
    }

    override fun onBackPressed() {
        if (modal != null) dismissModal() else super.onBackPressed()
    }

    // --- the comparison "table" -----------------------------------------------

    /**
     * Not a real grid — at a readable font three columns would each be a squeezed sliver
     * of wrapped text. Instead each setting is a stacked card: its name, then two
     * full-width labelled lines (Performance / Rendering). Reads top-to-bottom, never
     * compresses, and the bold labels keep the two dimensions scannable. Hairline between.
     */
    private fun impactTable(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, 0)
        }
        val rows = listOf(
            Triple(
                "Block web fonts",
                "Small–moderate — skips font downloads and reflow; fewer repaints",
                "Low — text falls back to system fonts; icon-fonts may show boxes",
            ),
            Triple(
                "Strict tracking protection",
                "Moderate — blocks tracker requests and scripts; lighter, faster loads",
                "Low–medium — some logins and embeds that rely on third-party scripts break",
            ),
            Triple(
                "JavaScript",
                "Large — the biggest lever; far less CPU, redraw and battery drain",
                "High — many sites need it to render at all; apps won't be interactive",
            ),
        )
        rows.forEachIndexed { i, (name, perf, dep) ->
            if (i > 0) col.addView(divider())
            col.addView(impactCard(name, perf, dep))
        }
        return col
    }

    private fun impactCard(name: String, perf: String, dep: String): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            addView(TextView(this@RenderingActivity).apply {
                text = name
                setTextColor(MainActivity.CHROME_INK)
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 0, 0, dp(4))
            })
            addView(aspectLine("Performance", perf))
            addView(aspectLine("Depends", dep))
        }

    /** One "Label — body" line, the label bolded so the two aspects scan at a glance. */
    private fun aspectLine(label: String, body: String): TextView {
        val full = "$label — $body"
        val span = SpannableString(full)
        span.setSpan(StyleSpan(Typeface.BOLD), 0, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return TextView(this).apply {
            text = span
            setTextColor(MainActivity.CHROME_INK)
            textSize = 15f
            alpha = 0.8f
            setPadding(dp(2), dp(1), 0, dp(2))
        }
    }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(0x22000000)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
    }

    // --- shared house-style helpers (mirrors SettingsActivity) ----------------

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
     * A boolean row (checkbox glyph + ON/OFF word — not a SwitchCompat; a switch's
     * thumb/tint is unreadable on grayscale e-ink). Unlike a plain toggle, moving to
     * [riskyValue] first raises an impact popup: accept applies + writes the pref, decline
     * leaves the toggle where it was. The other direction (re-enabling) applies silently.
     * [commit] mirrors the new value back into the Activity's field.
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
