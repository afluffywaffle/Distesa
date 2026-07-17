package com.afluffywaffle.distesa

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.afluffywaffle.distesa.eink.GlobeSearchDrawable
import kotlin.math.hypot

/**
 * Distesa — the visual layout editor.
 *
 * A full-screen, WYSIWYG configurator for the edge rails, their four corner sliver
 * slots, where the address bar sits, and the paging direction. Instead of abstract
 * "Left bottom button" cycle-rows, the user sees a faithful preview of the browser
 * layout and taps the element they want to change; a centred picker pane pops up over
 * the preview with the options for JUST that element. Writes the SAME
 * SharedPreferences ("distesa_settings") the browser reads in onResume — closing this
 * screen lets MainActivity re-apply everything (recreate) as usual.
 *
 * House style: white page, mid-grey ink, hairline outlines, no animation.
 */
class LayoutActivity : Activity() {

    private lateinit var prefs: SharedPreferences

    private var navPlacement = "both"
    private var chromePos = "auto"
    private var edgeSlotLeftTop = "none"
    private var edgeSlotLeftBottom = "chrome"
    private var edgeSlotRightTop = "none"
    private var edgeSlotRightBottom = "collapse"
    private var naturalScroll = true
    private var chromeBtnLeft = "back"
    private var chromeBtnRight1 = "refresh"
    private var chromeBtnRight2 = "collapse"
    private var searchEngine = "DuckDuckGo"
    private var customSearchUrl = ""
    private var autoFocusOnReveal = false

    private lateinit var root: FrameLayout
    private lateinit var preview: PreviewView
    private var modal: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("distesa_settings", MODE_PRIVATE)
        navPlacement = prefs.getString("navPlacement", "both") ?: "both"
        chromePos = prefs.getString("chromePos", "auto") ?: "auto"
        edgeSlotLeftTop = prefs.getString("edgeSlotLeftTop", "none") ?: "none"
        edgeSlotLeftBottom = prefs.getString("edgeSlotLeftBottom", "chrome") ?: "chrome"
        edgeSlotRightTop = prefs.getString("edgeSlotRightTop", "none") ?: "none"
        edgeSlotRightBottom = prefs.getString("edgeSlotRightBottom", "collapse") ?: "collapse"
        naturalScroll = prefs.getBoolean("naturalScroll", true)
        chromeBtnLeft = prefs.getString("chromeBtnLeft", "back") ?: "back"
        chromeBtnRight1 = prefs.getString("chromeBtnRight1", "refresh") ?: "refresh"
        chromeBtnRight2 = prefs.getString("chromeBtnRight2", "collapse") ?: "collapse"
        searchEngine = prefs.getString("searchEngine", "DuckDuckGo") ?: "DuckDuckGo"
        customSearchUrl = prefs.getString("customSearchUrl", "") ?: ""
        autoFocusOnReveal = prefs.getBoolean("autoFocusOnReveal", false)

        title = "Layout"

        root = FrameLayout(this).apply { setBackgroundColor(0xFFFFFFFF.toInt()) }

        // Top bar: Done (exit) on the left, a ? help button on the right.
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        topBar.addView(Button(this).apply {
            text = "← Done"
            setTextColor(0xFF222222.toInt())
            setBackgroundColor(Color.TRANSPARENT)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            textSize = 18f
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        topBar.addView(Button(this).apply {
            text = "?"
            setTextColor(MainActivity.CHROME_INK)
            background = borderBg() // a real bordered button, not a bare glyph
            gravity = Gravity.CENTER
            textSize = 20f
            setPadding(dp(20), dp(8), dp(20), dp(8))
            setOnClickListener { showHelp() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // A short hint under the bar so the tap-to-edit affordance is discoverable.
        val hint = TextView(this).apply {
            text = "Tap a corner button, the address bar, or the page to change it"
            setTextColor(MainActivity.CHROME_INK)
            alpha = 0.6f
            textSize = 15f
            setPadding(dp(16), 0, dp(16), dp(8))
        }

        preview = PreviewView(this) { el -> onElementTapped(el) }

        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(topBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        column.addView(hint, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        column.addView(preview, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(column, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)
    }

    override fun onBackPressed() {
        if (modal != null) dismissModal() else super.onBackPressed()
    }

    // --- element → picker ----------------------------------------------------

    private fun onElementTapped(el: String) {
        when (el) {
            "slotLT" -> showSlotPicker("Left top button", edgeSlotLeftTop, false) {
                edgeSlotLeftTop = it; save("edgeSlotLeftTop", it)
            }
            "slotLB" -> showSlotPicker("Left bottom button", edgeSlotLeftBottom, isBottomLocked("left")) {
                edgeSlotLeftBottom = it; save("edgeSlotLeftBottom", it)
            }
            "slotRT" -> showSlotPicker("Right top button", edgeSlotRightTop, false) {
                edgeSlotRightTop = it; save("edgeSlotRightTop", it)
            }
            "slotRB" -> showSlotPicker("Right bottom button", edgeSlotRightBottom, isBottomLocked("right")) {
                edgeSlotRightBottom = it; save("edgeSlotRightBottom", it)
            }
            "cLeft" -> showChromeSlotPicker("Left button", chromeBtnLeft, "chromeBtnLeft") { chromeBtnLeft = it }
            "cRight1" -> showChromeSlotPicker("Right button", chromeBtnRight1, "chromeBtnRight1") { chromeBtnRight1 = it }
            "cRight2" -> showChromeSlotPicker("Far-right button", chromeBtnRight2, "chromeBtnRight2") { chromeBtnRight2 = it }
            "chrome", "cField" -> showAddressBarPicker()
            "page", "railL", "railR" -> showRailsPicker()
        }
    }

    /** In single-strip mode the bottom slot on the live strip is force-locked to chrome. */
    private fun isBottomLocked(side: String): Boolean = navPlacement == side

    private fun showSlotPicker(title: String, current: String, locked: Boolean, onPick: (String) -> Unit) {
        val note = if (locked)
            "Locked to Address bar: with a single rail this is the only way to reach the toolbar."
        else null
        showPicker(title, NavActions.options(NavActions.Context.SLIVER), if (locked) "chrome" else current, note) {
            if (!locked) onPick(it)
        }
    }

    private fun save(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
        applyLockoutGuard() // the preview can't strand chrome in "both" mode
        dismissModal()
        preview.invalidate()
    }

    // --- centred picker pane -------------------------------------------------

    /** Open an empty centred pane over a tap-to-dismiss scrim; caller fills it via [build]. */
    private fun openPane(build: (LinearLayout) -> Unit) {
        dismissModal()
        val scrim = FrameLayout(this).apply {
            setBackgroundColor(0x22000000)
            isClickable = true
            setOnClickListener { dismissModal() }
        }
        val pane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = borderBg()
            setPadding(dp(18), dp(16), dp(18), dp(14))
            isClickable = true // don't let taps fall through to the scrim
        }
        build(pane)
        // A ScrollView so a tall pane (e.g. the address-bar slots) scrolls instead of
        // running off-screen; short panes still size to content and centre.
        val scroller = android.widget.ScrollView(this).apply {
            isFillViewport = false
            addView(pane, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        scrim.addView(scroller, FrameLayout.LayoutParams(dp(360), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        root.addView(scrim, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        modal = scrim
    }

    private fun paneTitle(pane: LinearLayout, text: String) = pane.addView(TextView(this).apply {
        this.text = text; setTextColor(MainActivity.CHROME_INK); textSize = 19f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(dp(2), 0, 0, dp(8))
    })

    private fun paneSubhead(pane: LinearLayout, text: String) = pane.addView(TextView(this).apply {
        this.text = text; setTextColor(MainActivity.CHROME_INK); alpha = 0.55f; textSize = 13f
        setPadding(dp(2), dp(10), 0, dp(2))
    })

    private fun paneNote(pane: LinearLayout, text: String) = pane.addView(TextView(this).apply {
        this.text = text; setTextColor(MainActivity.CHROME_INK); alpha = 0.65f; textSize = 15f
        setPadding(dp(4), dp(6), dp(4), 0)
    })

    /** A labelled explanation block for the help pane: bold term + description. */
    private fun paneHelp(pane: LinearLayout, term: String, body: String) {
        pane.addView(TextView(this).apply {
            text = term; setTextColor(MainActivity.CHROME_INK); textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(2), dp(14), dp(2), dp(2))
        })
        pane.addView(TextView(this).apply {
            text = body; setTextColor(MainActivity.CHROME_INK); alpha = 0.75f; textSize = 17f
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(dp(2), 0, dp(2), dp(2))
        })
    }

    /**
     * Explains what each function and option does. Paginated (Layuv-style): each page is
     * authored to fit one screen and you flip with Prev/Next — no scrolling on e-ink. Add
     * a page here when a topic overflows rather than letting a page scroll.
     */
    private fun showHelp() = openPane { pane ->
        // One category per page (each authored to fit one screen).
        val pages: List<Pair<String, (LinearLayout) -> Unit>> = listOf(
            "Buttons" to { p ->
                paneHelp(p, "⌕  Address bar", "Reveals the toolbar so you can type a URL or search, go back, refresh, etc.")
                paneHelp(p, "←  Back", "Goes to the previous page in history.")
                paneHelp(p, "⟳  Refresh", "Reloads the current page.")
                paneHelp(p, "⊟  Collapse", "Shrinks every image on the page to a small chip (tap again to expand). Keeps pages light and fast on e-ink; doesn't change the site.")
                paneHelp(p, "·  None", "Leaves the slot empty.")
            },
            "Navigation rails" to { p ->
                paneHelp(p, "Location", "Which edges carry a paging rail: both sides, left only, right only, or none (a floating button instead).")
                paneHelp(p, "Natural scroll", "On: pressing the top of a rail moves the page up, like dragging paper. Off: it behaves like a scrollbar (top = back up the page).")
            },
            "Address bar" to { p ->
                paneHelp(p, "Position · Auto", "Puts the bar at the bottom on small screens (thumb-reachable) and at the top on large ones. Or force Top / Bottom.")
                paneHelp(p, "Search", "Which engine a typed search uses — or Custom, where you supply your own search URL (%s = the query).")
                paneHelp(p, "Auto-focus on open", "When on, revealing the bar puts the cursor in the field so you can type right away. Handy while browsing; off keeps taps calm while configuring.")
            },
        )
        var idx = 0
        fun render() {
            pane.removeAllViews()
            paneTitle(pane, pages[idx].first)
            val content = LinearLayout(this@LayoutActivity).apply { orientation = LinearLayout.VERTICAL }
            pages[idx].second(content)
            pane.addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            // Footer: ‹ Prev · i / N · Next ›
            val nav = LinearLayout(this@LayoutActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, 0)
            }
            nav.addView(Button(this@LayoutActivity).apply {
                text = "‹ Prev"; setTextColor(MainActivity.CHROME_INK); setBackgroundColor(Color.TRANSPARENT)
                textSize = 15f; gravity = Gravity.START
                visibility = if (idx > 0) View.VISIBLE else View.INVISIBLE
                setOnClickListener { idx--; render() }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            nav.addView(TextView(this@LayoutActivity).apply {
                text = "${idx + 1} / ${pages.size}"; setTextColor(MainActivity.CHROME_INK); alpha = 0.6f
                textSize = 13f; gravity = Gravity.CENTER
            })
            nav.addView(Button(this@LayoutActivity).apply {
                text = "Next ›"; setTextColor(MainActivity.CHROME_INK); setBackgroundColor(Color.TRANSPARENT)
                textSize = 15f; gravity = Gravity.END
                visibility = if (idx < pages.size - 1) View.VISIBLE else View.INVISIBLE
                setOnClickListener { idx++; render() }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            pane.addView(nav, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        render()
    }

    /** A radio or checkbox row — a full-width, finger-sized (52dp) target with a big
     *  marker (the ◉/○ glyphs render tiny, so use large circle/box glyphs, scaled up).
     *  [trailingIcon] draws a real Drawable after the label (e.g. the globe+magnifier). */
    private fun paneRow(
        pane: LinearLayout, on: Boolean, radio: Boolean, label: String,
        trailingIcon: android.graphics.drawable.Drawable? = null, onClick: () -> Unit,
    ) {
        val mark = if (radio) (if (on) "⬤" else "◯") else (if (on) "☑" else "☐")
        val sb = android.text.SpannableStringBuilder(mark)
        sb.setSpan(android.text.style.RelativeSizeSpan(1.35f), 0, 1, android.text.Spannable.SPAN_INCLUSIVE_EXCLUSIVE)
        sb.append("   ").append(label)
        if (trailingIcon != null) {
            // Inline the drawable right after the label (an ImageSpan flows with the text,
            // so it lands in the same column as the other rows' trailing glyphs — a
            // compound drawable would instead pin it to the button's far right edge).
            trailingIcon.setBounds(0, 0, dp(20), dp(20))
            sb.append("   ")
            val s = sb.length
            sb.append("￼")
            sb.setSpan(android.text.style.ImageSpan(trailingIcon, android.text.style.ImageSpan.ALIGN_BASELINE),
                s, s + 1, android.text.Spannable.SPAN_INCLUSIVE_EXCLUSIVE)
        }
        pane.addView(Button(this).apply {
            setText(sb, android.widget.TextView.BufferType.SPANNABLE)
            setTextColor(MainActivity.CHROME_INK)
            setBackgroundColor(Color.TRANSPARENT)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            textSize = 18f
            minimumHeight = dp(52)
            setPadding(dp(6), dp(12), dp(6), dp(12))
            setOnClickListener { onClick() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun showPicker(
        title: String,
        options: List<Pair<String, String>>,
        current: String,
        note: String?,
        onPick: (String) -> Unit,
    ) = openPane { pane ->
        paneTitle(pane, title)
        for ((value, label) in options) {
            // "chrome" shows the real globe+magnifier drawable, not the ⌕ menu glyph, so
            // the pane matches the actual sliver button.
            val icon = if (value == "chrome")
                GlobeSearchDrawable(MainActivity.CHROME_INK, 0xFFFFFFFF.toInt(), dp(22)) else null
            val shown = if (icon != null) NavActions.label(value) else label
            paneRow(pane, value == current, true, shown, icon) { onPick(value) }
        }
        if (note != null) paneNote(pane, note)
    }

    /**
     * The rails pane groups everything about the paging rails: their Location (a radio
     * over navPlacement) and Scroll direction (a Natural toggle, shown only when rails
     * exist). Unlike the other pickers this pane STAYS open and re-renders in place, so
     * both groups can be adjusted in one visit; the scrim / back closes it.
     */
    private fun showRailsPicker() = openPane { pane ->
        fun render() {
            pane.removeAllViews()
            paneTitle(pane, "Navigation rails")
            paneSubhead(pane, "Location")
            val locations = listOf(
                "both" to "Both sides", "left" to "Left only", "right" to "Right only", "none" to "None (floating)",
            )
            for ((value, label) in locations) paneRow(pane, value == navPlacement, true, label) {
                if (navPlacement != value) {
                    navPlacement = value
                    prefs.edit().putString("navPlacement", value).apply()
                    applyLockoutGuard()
                    preview.invalidate()
                    render()
                }
            }
            // Scroll direction only applies where there are chevron rails to page with.
            if (navPlacement != "none") {
                paneSubhead(pane, "Scroll direction")
                paneRow(pane, naturalScroll, false, "Natural") {
                    naturalScroll = !naturalScroll
                    prefs.edit().putBoolean("naturalScroll", naturalScroll).apply()
                    render()
                }
            }
        }
        render()
    }

    /**
     * The address-bar pane: Position (auto/top/bottom) plus three configurable button
     * slots that flank the url field — each picks a function like an edge sliver. The ⚙
     * settings button is fixed (never removable) so settings can't be stranded. Stays
     * open and re-renders in place so several slots can be set in one visit.
     */
    /** One chrome-bar button: a single function picker, closes on pick (like a sliver). */
    private fun showChromeSlotPicker(title: String, current: String, key: String, set: (String) -> Unit) =
        showPicker(title, NavActions.options(NavActions.Context.CHROME), current, null) {
            set(it); save(key, it)
        }

    /**
     * The address-bar FIELD pane: where the bar sits (position) and what its search box
     * does (engine, incl. a Custom template). Stays open, re-renders in place; picking
     * Custom reveals a template field ("%s" = the query).
     */
    private fun showAddressBarPicker() = openPane { pane ->
        fun render() {
            pane.removeAllViews()
            paneTitle(pane, "Address bar")
            paneSubhead(pane, "Position")
            for ((value, label) in listOf("auto" to "Auto", "top" to "Top", "bottom" to "Bottom"))
                paneRow(pane, value == chromePos, true, label) {
                    chromePos = value; prefs.edit().putString("chromePos", value).apply(); preview.invalidate(); render()
                }
            paneSubhead(pane, "Search")
            for (name in MainActivity.SEARCH_ENGINES.keys) paneRow(pane, searchEngine == name, true, name) {
                searchEngine = name; prefs.edit().putString("searchEngine", name).apply(); render()
            }
            paneRow(pane, searchEngine == "Custom", true, "Custom…") {
                searchEngine = "Custom"; prefs.edit().putString("searchEngine", "Custom").apply(); render()
            }
            if (searchEngine == "Custom") {
                pane.addView(android.widget.EditText(this@LayoutActivity).apply {
                    setText(customSearchUrl)
                    hint = "https://example.com/search?q=%s"
                    setTextColor(MainActivity.CHROME_INK); setHintTextColor(0xFF999999.toInt())
                    textSize = 16f; setSingleLine()
                    inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
                    addTextChangedListener(object : android.text.TextWatcher {
                        override fun afterTextChanged(s: android.text.Editable?) {
                            customSearchUrl = s?.toString() ?: ""
                            prefs.edit().putString("customSearchUrl", customSearchUrl).apply()
                        }
                        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    })
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                paneNote(pane, "Use %s where the search text goes.")
            }
            paneSubhead(pane, "Focus")
            paneRow(pane, autoFocusOnReveal, false, "Auto-focus on open") {
                autoFocusOnReveal = !autoFocusOnReveal
                prefs.edit().putBoolean("autoFocusOnReveal", autoFocusOnReveal).apply()
                render()
            }
        }
        render()
    }

    /** "both"-mode guard: ensure at least one chrome slot so the toolbar stays reachable. */
    private fun applyLockoutGuard() {
        if (navPlacement == "both") {
            val reachable = edgeSlotLeftTop == "chrome" || edgeSlotLeftBottom == "chrome" ||
                edgeSlotRightTop == "chrome" || edgeSlotRightBottom == "chrome"
            if (!reachable) { edgeSlotLeftBottom = "chrome"; prefs.edit().putString("edgeSlotLeftBottom", "chrome").apply() }
        }
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

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** Mirrors MainActivity.computeChromeAtBottom so the preview resolves "auto" the same. */
    private fun computeChromeAtBottom(): Boolean = when (chromePos) {
        "top" -> false
        "bottom" -> true
        else -> {
            val dm = resources.displayMetrics
            hypot((dm.widthPixels / dm.xdpi).toDouble(), (dm.heightPixels / dm.ydpi).toDouble()) < 9.0
        }
    }

    // -------------------------------------------------------------------------
    //  The preview: a faithful, inert replica of the browser layout that reports
    //  which element a tap landed on. Not scaled — it maps 1:1 onto the editor's
    //  content area, so the rails sit exactly where they do in the browser.
    // -------------------------------------------------------------------------
    inner class PreviewView(context: Context, val onTap: (String) -> Unit) : View(context) {

        private val railPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(200, 200, 200); style = Paint.Style.STROKE; strokeWidth = dp(1.5f)
        }
        private val chevronPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(120, 120, 120); style = Paint.Style.STROKE
            strokeWidth = dp(2.5f); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(120, 120, 120); textAlign = Paint.Align.CENTER; textSize = dp(18f)
        }
        private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = MainActivity.CHROME_INK; textAlign = Paint.Align.LEFT; textSize = dp(13f)
        }
        private val globe = GlobeSearchDrawable(Color.rgb(120, 120, 120), 0xFFFAFAFA.toInt(), dp(24))
        // Match the real chrome bar (buildChromeBar): #FAFAFA fill + 2px #555 border.
        private val chromeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFAFAFA.toInt(); style = Paint.Style.FILL }
        private val chromeBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF555555.toInt(); style = Paint.Style.STROKE; strokeWidth = dp(2f)
        }

        // Hit rects, recomputed each draw.
        private val rLeftTop = RectF(); private val rLeftBot = RectF()
        private val rRightTop = RectF(); private val rRightBot = RectF()
        private val rLeftPage = RectF(); private val rRightPage = RectF()
        private val rChrome = RectF(); private val rPage = RectF()
        private val rFloat = RectF()
        private val rCLeft = RectF(); private val rCRight1 = RectF(); private val rCRight2 = RectF()

        private fun dp(v: Float): Float = v * resources.displayMetrics.density
        private val railW get() = width * 0.08f
        private val activeTop = 0.40f // mirrors EdgeNavView.ACTIVE_TOP — rails are bottom-weighted
        private val slotH get() = dp(56f)
        private val chromeH get() = dp(60f)
        private val gap get() = dp(8f)

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val pad = dp(6f)
            val botPad = dp(18f) // extra breathing room so the frame doesn't hug the screen edge
            // Device frame.
            railPaint.color = Color.rgb(170, 170, 170)
            canvas.drawRoundRect(pad, pad, w - pad, h - botPad, dp(14f), dp(14f), railPaint)
            railPaint.color = Color.rgb(200, 200, 200)

            val hasLeft = navPlacement == "both" || navPlacement == "left"
            val hasRight = navPlacement == "both" || navPlacement == "right"
            val top = pad + dp(4f); val bot = h - botPad - dp(4f)

            rLeftTop.setEmpty(); rLeftBot.setEmpty(); rRightTop.setEmpty(); rRightBot.setEmpty()
            rLeftPage.setEmpty(); rRightPage.setEmpty(); rFloat.setEmpty()
            rCLeft.setEmpty(); rCRight1.setEmpty(); rCRight2.setEmpty()

            if (hasLeft) drawRail(canvas, pad + dp(4f), top, bot, "left")
            if (hasRight) drawRail(canvas, w - pad - dp(4f) - railW, top, bot, "right")

            val pageLeft = if (hasLeft) pad + dp(4f) + railW else pad
            val pageRight = if (hasRight) w - pad - dp(4f) - railW else w - pad
            val atBottom = computeChromeAtBottom()

            // Chrome (address bar): drawn where it sits when revealed, inset past any rails.
            // In "none" mode the bar is hidden behind a floating button (drawn below), so
            // there's no persistent bar to show — the page spans the full height instead.
            rChrome.setEmpty()
            if (navPlacement != "none") {
                val chromeLeft = pageLeft + gap
                val chromeRight = pageRight - gap
                // Bottom edge aligns with the rail bottoms (in use they share a line);
                // top-anchored chrome keeps a small gap from the frame.
                val cy = if (atBottom) bot - chromeH else top + gap
                val midY = cy + chromeH / 2
                rChrome.set(chromeLeft, cy, chromeRight, cy + chromeH)
                canvas.drawRoundRect(rChrome, dp(14f), dp(14f), chromeFill)
                canvas.drawRoundRect(rChrome, dp(14f), dp(14f), chromeBorder)

                val slotW = dp(42f); val hh = dp(20f) // generous spacing like the real bar
                // All three config slots are ALWAYS reserved (a "none" slot draws a faint
                // placeholder) so each stays an individual tap target. ⚙ is fixed.
                val lx = chromeLeft + dp(20f)
                drawChromeSlot(canvas, chromeBtnLeft, lx, midY)
                rCLeft.set(lx - hh, cy, lx + hh, cy + chromeH)

                var rx = chromeRight - dp(22f)
                drawChromeGlyph(canvas, "gear", rx, midY); rx -= slotW
                drawChromeSlot(canvas, chromeBtnRight2, rx, midY); rCRight2.set(rx - hh, cy, rx + hh, cy + chromeH); rx -= slotW
                drawChromeSlot(canvas, chromeBtnRight1, rx, midY); rCRight1.set(rx - hh, cy, rx + hh, cy + chromeH)

                inkPaint.textSize = dp(15f); inkPaint.color = Color.rgb(119, 119, 119) // #777 hint
                canvas.drawText("Search or enter address", lx + slotW * 0.7f, midY + dp(5f), inkPaint)
            }

            // Central page area (tap → rails picker; also the fallback when navPlacement=none).
            val pageTop = if (rChrome.isEmpty || atBottom) top else rChrome.bottom
            val pageBot = if (rChrome.isEmpty || !atBottom) bot else rChrome.top
            rPage.set(pageLeft, pageTop, pageRight, pageBot)
            glyphPaint.color = Color.rgb(190, 190, 190)
            glyphPaint.textSize = dp(13f)
            canvas.drawText("page content", rPage.centerX(), rPage.centerY(), glyphPaint)
            glyphPaint.color = Color.rgb(120, 120, 120)
            glyphPaint.textSize = dp(18f)

            // With no rails, chrome falls back to a bordered floating button bottom-right
            // (mirrors MainActivity.addFloatingChromeButton) — the only toolbar affordance.
            if (navPlacement == "none") {
                val s = dp(52f); val g = dp(8f)
                rFloat.set(w - pad - g - s, bot - g - s, w - pad - g, bot - g)
                railPaint.color = Color.rgb(150, 150, 150)
                canvas.drawRoundRect(rFloat, dp(12f), dp(12f), railPaint)
                railPaint.color = Color.rgb(200, 200, 200)
                val gs = dp(13f)
                globe.setBounds((rFloat.centerX() - gs).toInt(), (rFloat.centerY() - gs).toInt(),
                    (rFloat.centerX() + gs).toInt(), (rFloat.centerY() + gs).toInt())
                globe.draw(canvas)
                inkPaint.textSize = dp(12f); inkPaint.color = MainActivity.CHROME_INK
                canvas.drawText("Tap the page to add rails", rPage.centerX() - dp(72f), rPage.centerY() + dp(24f), inkPaint)
            }
        }

        private fun drawRail(canvas: Canvas, left: Float, fullTop: Float, bot: Float, side: String) {
            val right = left + railW
            // Bottom-weighted: the capsule only occupies the lower (1 - activeTop) of the
            // strip, exactly like EdgeNavView — the top of the strip is inert/empty.
            val railTop = fullTop + (bot - fullTop) * activeTop
            canvas.drawRoundRect(left, railTop, right, bot, dp(12f), dp(12f), railPaint)
            val topSlot = if (side == "left") edgeSlotLeftTop else edgeSlotRightTop
            var botSlot = if (side == "left") edgeSlotLeftBottom else edgeSlotRightBottom
            if (isBottomLocked(side)) botSlot = "chrome"

            // Both slot caps are ALWAYS reserved in the editor, so every corner stays a
            // tappable target (an empty "none" slot draws a faint placeholder you can tap
            // to fill). The paging zone is the stable middle between the two caps.
            val topRect = RectF(left, railTop, right, railTop + slotH)
            val botRect = RectF(left, bot - slotH, right, bot)
            val pageTop = railTop + slotH
            val pageBot = bot - slotH
            val cx = (left + right) / 2f

            // Short centred divider hairlines (mirrors EdgeNavView.drawDivider).
            val halfLen = railW * 0.25f
            canvas.drawLine(cx - halfLen, pageTop, cx + halfLen, pageTop, railPaint)
            canvas.drawLine(cx - halfLen, pageBot, cx + halfLen, pageBot, railPaint)
            val splitY = pageTop + (pageBot - pageTop) * 0.5f
            canvas.drawLine(cx - halfLen, splitY, cx + halfLen, splitY, railPaint)

            // Chevrons re-centre in each half of the paging zone (up above the split, down below).
            drawChevron(canvas, cx, pageTop + (splitY - pageTop) / 2f, true)
            drawChevron(canvas, cx, splitY + (pageBot - splitY) / 2f, false)

            drawSlotGlyph(canvas, topRect, topSlot)
            drawSlotGlyph(canvas, botRect, botSlot)

            if (side == "left") {
                rLeftTop.set(topRect); rLeftBot.set(botRect); rLeftPage.set(left, pageTop, right, pageBot)
            } else {
                rRightTop.set(topRect); rRightBot.set(botRect); rRightPage.set(left, pageTop, right, pageBot)
            }
        }

        private fun drawChevron(canvas: Canvas, cx: Float, cy: Float, up: Boolean) {
            val cw = dp(7f); val ch = dp(6f)
            val ty = if (up) cy + ch else cy - ch
            canvas.drawLine(cx - cw, ty, cx, if (up) cy - ch else cy + ch, chevronPaint)
            canvas.drawLine(cx, if (up) cy - ch else cy + ch, cx + cw, ty, chevronPaint)
        }

        private val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(205, 205, 205); style = Paint.Style.STROKE; strokeWidth = dp(1f)
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(4f), dp(4f)), 0f)
        }

        /** Draw a chrome-bar button glyph centred at (cx, midY). ⚙ is the fixed settings
         *  button; everything else renders its shared catalog glyph. */
        private fun drawChromeGlyph(canvas: Canvas, fn: String, cx: Float, midY: Float) {
            val g = if (fn == "gear") "⚙" else NavActions.glyph(fn)
            if (g.isEmpty()) return
            glyphPaint.color = MainActivity.CHROME_INK // dark ink, like the real bar
            glyphPaint.textSize = dp(20f)
            canvas.drawText(g, cx, midY + dp(7f), glyphPaint)
            glyphPaint.color = Color.rgb(120, 120, 120)
            glyphPaint.textSize = dp(18f)
        }

        /** A chrome-bar config slot: its glyph, or a faint dashed placeholder when "none". */
        private fun drawChromeSlot(canvas: Canvas, fn: String, cx: Float, midY: Float) {
            if (fn == "none") {
                val s = dp(9f)
                canvas.drawRoundRect(cx - s, midY - s, cx + s, midY + s, dp(4f), dp(4f), placeholderPaint)
            } else {
                drawChromeGlyph(canvas, fn, cx, midY)
            }
        }

        private fun drawSlotGlyph(canvas: Canvas, r: RectF, slot: String) {
            val cx = r.centerX(); val cy = r.centerY()
            if (slot == "none") {
                // Faint dashed placeholder: an empty, still-tappable slot to fill.
                val m = dp(12f)
                canvas.drawRoundRect(r.left + m, r.top + m, r.right - m, r.bottom - m, dp(6f), dp(6f), placeholderPaint)
                return
            }
            if (slot == "chrome") { // the only non-text glyph — a drawn globe+magnifier
                val s = dp(12f)
                globe.setBounds((cx - s).toInt(), (cy - s).toInt(), (cx + s).toInt(), (cy + s).toInt())
                globe.draw(canvas)
                return
            }
            // Every other function renders its shared catalog glyph (back a touch larger).
            glyphPaint.textSize = if (slot == "back") dp(24f) else dp(18f)
            canvas.drawText(NavActions.glyph(slot), cx, cy + dp(7f), glyphPaint)
            glyphPaint.textSize = dp(18f)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action != MotionEvent.ACTION_UP) return true
            val x = event.x; val y = event.y
            val el = when {
                !rLeftTop.isEmpty && rLeftTop.contains(x, y) -> "slotLT"
                !rLeftBot.isEmpty && rLeftBot.contains(x, y) -> "slotLB"
                !rRightTop.isEmpty && rRightTop.contains(x, y) -> "slotRT"
                !rRightBot.isEmpty && rRightBot.contains(x, y) -> "slotRB"
                !rFloat.isEmpty && rFloat.contains(x, y) -> "chrome"
                !rCLeft.isEmpty && rCLeft.contains(x, y) -> "cLeft"
                !rCRight1.isEmpty && rCRight1.contains(x, y) -> "cRight1"
                !rCRight2.isEmpty && rCRight2.contains(x, y) -> "cRight2"
                !rChrome.isEmpty && rChrome.contains(x, y) -> "cField"
                !rLeftPage.isEmpty && rLeftPage.contains(x, y) -> "railL"
                !rRightPage.isEmpty && rRightPage.contains(x, y) -> "railR"
                else -> "page"
            }
            onTap(el)
            return true
        }
    }
}
