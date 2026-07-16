package com.afluffypancake.distesa

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.WindowInsets
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.widget.SwitchCompat
import com.afluffypancake.distesa.eink.EdgeNavView
import java.net.URLEncoder
import kotlin.math.hypot
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController
import com.afluffypancake.distesa.eink.Epd

/**
 * Distesa Phase 0 Spike A — the GeckoView engine evaluation.
 *
 * Minimal by design: a single full-screen [GeckoView] backed by a process-wide
 * [GeckoRuntime] singleton and one [GeckoSession]. No tabs, no address bar — that
 * is Phase 1.
 *
 * WebExtension support (the whole reason for choosing GeckoView over WebView) is
 * exercised here by installing uBlock Origin at RUNTIME from AMO
 * (addons.mozilla.org) via [WebExtensionController.install] — NOT bundled/frozen
 * into the APK. The add-on is therefore a real, updatable, toggleable add-on, just
 * as in Firefox for Android. A tiny top-right overlay button flips it on/off so we
 * can A/B the ad-blocking effect on-device without any browser UI yet.
 *
 * Because there is no interactive UI to approve add-on permission prompts, a
 * [WebExtensionController.PromptDelegate] auto-grants install/permission requests
 * (acceptable for a test spike — NOT what a shipping build should do).
 *
 * A bundled "eink" WebExtension provides tap-to-flip pagination; each flip is
 * signalled to us via native messaging (see [FlipMessageDelegate]) and drives the
 * ported [Epd] + RattaEink EPD refresh — a clean full-panel clear every
 * [Epd.FULL_EVERY] turns to flush accumulated e-ink ghosting.
 */
class MainActivity : Activity() {

    private lateinit var session: GeckoSession
    private lateinit var view: GeckoView
    private lateinit var imgToggle: Button
    private lateinit var prefs: SharedPreferences
    private lateinit var settingsPanel: LinearLayout

    // Minimal, mostly-hidden navigation chrome (see buildChromeBar()).
    private lateinit var chromeBar: LinearLayout
    private lateinit var urlField: EditText
    private lateinit var backBtn: Button
    private var canGoBack = false
    private var currentUrl: String? = null
    private val ui = Handler(Looper.getMainLooper())
    private val autoHide = Runnable { hideChrome() }
    private var chromeAtBottom = false

    // Navigation/paging + chrome levers (persisted).
    private var navStyle = "inset"       // "inset" (narrow the page) | "overlay"
    private var navPlacement = "both"    // "both" | "left" | "right"
    private var showZones = true         // draw the faint chevron affordance
    private var chromePos = "auto"       // "auto" | "top" | "bottom"
    private var searchEngine = "DuckDuckGo"
    private var leftStrip: EdgeNavView? = null
    private var rightStrip: EdgeNavView? = null

    // Edge-sliver action slots: a small button at the bottom of each nav strip,
    // ALWAYS drawn (independent of showZones) so there is always a way to surface
    // chrome. Values: "chrome" (reveal toolbar) | "collapse" (flip placeholders) |
    // "none". A load-time guard forces at least one slot to "chrome" so the user can
    // never lock themselves out of the toolbar. "favorites" is reserved for later.
    private var edgeSlotLeft = "chrome"
    private var edgeSlotRight = "collapse"

    // E-ink performance levers (persisted in SharedPreferences; see loadSettings()).
    private var animOff = true          // inject animation/transition-killing CSS
    private var blockFonts = true       // block web-font requests (system fallback)
    private var strictTp = true         // engine strict tracking protection
    private var jsEnabled = true        // per-session JavaScript

    // Page-level media placeholder display: collapsed = tiny inline chips (default),
    // else full sized boxes. Owned by images.js; mirrored here to label the toggle.
    private var imagesCollapsed = true
    private var collapseBtn: Button? = null
    private var fullEvery = 6           // EPD full-clear cadence (0 = Off)

    // Auto-collapse threshold. collapseMode ∈ {"always","never","auto"} decides the
    // page's INITIAL placeholder display; in "auto" images.js flips to collapsed once
    // more than collapseThreshold media elements have been gated. Mirrored to the
    // content script via pushSettingsToExtension().
    private var collapseMode = "auto"
    private var collapseThreshold = 6
    // Where the per-page collapse toggle lives. Only "chrome" is wired today; the
    // key exists so alternate placements can be added without a schema change.
    private var collapseBtnPlacement = "chrome"

    // Load-time measurement.
    private var pageStartMs = 0L
    private var pageHost = ""

    // Hosts observed to have a password field — tracking protection is relaxed to
    // Standard for these (Strict can break sign-in). Persisted; grows over time.
    private var loginHosts: MutableSet<String> = mutableSetOf()

    /** The installed uBlock Origin add-on, once resolved (install or list). */
    private var ublock: WebExtension? = null

    /** Live port to the eink content script (images.js), for the image-policy cycle. */
    private var einkPort: WebExtension.Port? = null

    /**
     * Native EPD refresh driver for the GeckoView surface. On each page flip the
     * eink extension signals us; [Epd] counts turns and forces a clean full-panel
     * clear (via RattaEink → Supernote EinkManager) every [Epd.FULL_EVERY] turns.
     */
    private val epd = Epd()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        loadSettings()

        val runtime = sharedRuntime(this)
        // Auto-approve add-on install + permission prompts (test spike only).
        runtime.webExtensionController.promptDelegate = AutoApprovePromptDelegate
        // Engine-level tracking protection, applied before the first load.
        applyTrackingProtection(strictTp)
        // EPD full-clear cadence lever.
        Epd.FULL_EVERY = fullEvery

        // Our OWN bundled extension (page-flip). Unlike uBlock (a third-party
        // add-on installed at runtime from AMO), we author this one so it ships
        // inside the APK and loads via installBuiltIn from assets.
        installEink(runtime)

        session = GeckoSession()
        session.open(runtime)
        session.settings.setAllowJavascript(jsEnabled)
        session.progressDelegate = PerfProgressDelegate
        session.navigationDelegate = EtpNavigationDelegate

        view = GeckoView(this)
        view.setSession(session)

        val edge = if (chromeAtBottom) Gravity.BOTTOM else Gravity.TOP

        // Default state = NOTHING but the page. Chrome is summoned/dismissed only via
        // the edge sliver (or the idle timer); a page tap NEVER moves it, so a lifted
        // consent banner can't shift out from under the user's finger mid-tap.
        val root = FrameLayout(this)
        // Light background so the INSET paging margins (revealed when the GeckoView
        // is narrowed) read as white page-margin, never a dark band. Honors the
        // "no dark chrome" rule.
        root.setBackgroundColor(0xFFFFFFFF.toInt())

        // The GeckoView. In INSET mode we physically narrow it with real left/right
        // margins so the paging strips sit in empty margins (page reflows narrower);
        // in OVERLAY mode it fills the width and strips overlay the page edges.
        val stripW = (resources.displayMetrics.widthPixels * 0.08f).toInt() // ~8%
        val viewLp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
        )
        if (navStyle == "inset") {
            if (navPlacement != "right") viewLp.leftMargin = stripW
            if (navPlacement != "left") viewLp.rightMargin = stripW
        }
        root.addView(view, viewLp)

        // Paging strips (native EdgeNavView). Bottom-weighted; tap = flip.
        addStrips(root, stripW)

        // The chrome-reveal affordance now lives in the edge slivers (bottom of each
        // nav strip, added by addStrips) — freeing the bottom CENTRE, where consent
        // banners put their buttons, so those stay tappable. There is deliberately no
        // tap-page-to-dismiss: chrome hides via its sliver toggle or the idle timer.

        // Chrome bar (hidden by default) at the adaptive edge. Inset horizontally by
        // the strip width on each side that has a strip, so the bar sits BETWEEN the
        // strips and never overlaps their bottom edge slivers (☰/⊟) — those stay
        // visible and tappable (☰ toggles the bar closed) while chrome is up.
        chromeBar = buildChromeBar()
        root.addView(
            chromeBar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, edge,
            ).apply {
                if (navPlacement != "right") leftMargin = stripW
                if (navPlacement != "left") rightMargin = stripW
            },
        )

        // Settings panel (hidden until ⚙). Sits just inside the chrome edge.
        settingsPanel = buildSettingsPanel()
        root.addView(
            settingsPanel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                edge or Gravity.END,
            ).apply {
                if (chromeAtBottom) bottomMargin = dp(52) else topMargin = dp(52)
            },
        )
        setContentView(root)

        // Keep the chrome bar sitting just above the on-screen input window while the
        // address field is focused. windowSoftInputMode=adjustNothing means the system
        // won't move anything for us, so we read the live IME inset and lift the bar by
        // exactly that height (adapts to any keyboard/panel; see liftChromeForIme).
        root.setOnApplyWindowInsetsListener { v, insets ->
            val imeBottom = insets.getInsets(WindowInsets.Type.ime()).bottom
            Log.i(TAG, "[eink-ime] inset=$imeBottom urlFocus=${::urlField.isInitialized && urlField.hasFocus()}")
            liftChromeForIme(if (::urlField.isInitialized && urlField.hasFocus()) imeBottom else 0)
            v.onApplyWindowInsets(insets)
        }

        session.loadUri(TEST_URL)

        ensureUBlock(runtime)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** Builds the 1–2 native paging strips per the placement + style settings. */
    private fun addStrips(root: FrameLayout, stripW: Int) {
        leftStrip = null
        rightStrip = null
        if (navPlacement != "right") {
            leftStrip = addStrip(root, stripW, Gravity.START, edgeSlotLeft)
        }
        if (navPlacement != "left") {
            rightStrip = addStrip(root, stripW, Gravity.END, edgeSlotRight)
        }
    }

    /**
     * One paging strip plus its optional bottom sliver button. When [slot] is an
     * action (not "none"), the EdgeNavView is shortened by [SLIVER_H] so its paging
     * zone ends above the sliver, and a small always-visible button is placed in the
     * freed space. The sliver is drawn regardless of [showZones] — it's the guaranteed
     * way to reach the toolbar.
     */
    private fun addStrip(root: FrameLayout, stripW: Int, side: Int, slot: String): EdgeNavView {
        val hasSliver = slot != "none"
        val sliverPx = dp(SLIVER_H)
        val strip = EdgeNavView(this, showZones, onNext = { flipPage("next") }, onPrev = { flipPage("prev") })
        root.addView(
            strip,
            FrameLayout.LayoutParams(stripW, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.BOTTOM or side).apply {
                if (hasSliver) bottomMargin = sliverPx // keep paging taps off the sliver
            },
        )
        if (hasSliver) root.addView(makeSliverButton(slot), FrameLayout.LayoutParams(stripW, sliverPx, Gravity.BOTTOM or side))
        return strip
    }

    /** The little bottom-of-strip action button (chrome reveal / collapse toggle). */
    private fun makeSliverButton(slot: String): Button {
        val glyph = when (slot) {
            "collapse" -> if (imagesCollapsed) "⊞" else "⊟"
            else -> "☰" // chrome
        }
        return Button(this).apply {
            text = glyph
            setTextColor(Color.rgb(150, 150, 150)) // faint light ink, matches chevrons
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(0, 0, 0, 0)
            textSize = 20f
            setOnClickListener {
                when (slot) {
                    "collapse" -> { onToggleCollapse(); text = if (imagesCollapsed) "⊞" else "⊟" }
                    else -> toggleChrome()
                }
            }
        }
    }

    /** Native strip tap → tell content.js to scroll a page (it also signals EPD). */
    private fun flipPage(dir: String) {
        val p = einkPort ?: return
        try {
            p.postMessage(org.json.JSONObject().put("type", "navFlip").put("dir", dir))
        } catch (e: Throwable) {
            Log.w(TAG, "flipPage post threw ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // --- Minimal navigation chrome ------------------------------------------

    private fun buildChromeBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xF5FAFAFA.toInt()) // near-white, high-contrast, no colour
            setPadding(dp(6), dp(4), dp(6), dp(4))
            visibility = View.GONE
            // Any touch within the chrome resets the (generous) idle timer, unless
            // the address field is focused (then it's pinned open).
            setOnTouchListener { _, _ -> if (!urlField.hasFocus()) scheduleAutoHide(); false }
        }
        backBtn = Button(this).apply {
            text = "‹"
            setTextColor(CHROME_INK)
            setBackgroundColor(Color.TRANSPARENT) // no dark button fill; glyph on light bar
            textSize = 22f
            isEnabled = false
            setOnClickListener {
                if (::session.isInitialized && canGoBack) { session.goBack(); afterNav() }
            }
        }
        urlField = EditText(this).apply {
            setTextColor(CHROME_INK)
            setHintTextColor(0xFF777777.toInt())
            hint = "Search or enter address"
            setSingleLine()
            setSelectAllOnFocus(true)
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO) { submitUrl(text.toString()); true } else false
            }
            // Focus pins the chrome open indefinitely so the user can type; losing
            // focus restarts the generous idle timer. On focus we also lift the whole
            // bar to the TOP edge: the Supernote's on-screen input / handwriting panel
            // is a bottom overlay that doesn't resize the window, so a bottom-anchored
            // address bar (and its suggestions) gets covered. Top-edge entry is clear
            // of it. Restored to the configured edge on blur.
            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) ui.removeCallbacks(autoHide)
                else { liftChromeForIme(0); scheduleAutoHide() }
            }
        }
        val reloadBtn = Button(this).apply {
            text = "⟳"
            setTextColor(CHROME_INK)
            setBackgroundColor(Color.TRANSPARENT)
            textSize = 20f
            setOnClickListener { if (::session.isInitialized) { session.reload(); afterNav() } }
        }
        // Page-level collapse/expand-all: flip every placeholder on THIS page
        // between tiny chips and full boxes, without changing the site's saved
        // policy. ⊟ = collapse to chips, ⊞ = expand to boxes.
        collapseBtn = Button(this).apply {
            text = if (imagesCollapsed) "⊞" else "⊟"
            setTextColor(CHROME_INK)
            setBackgroundColor(Color.TRANSPARENT)
            textSize = 20f
            setOnClickListener { onToggleCollapse() }
        }
        val gearBtn = Button(this).apply {
            text = "⚙"
            setTextColor(CHROME_INK)
            setBackgroundColor(Color.TRANSPARENT)
            textSize = 20f
            setOnClickListener {
                settingsPanel.visibility =
                    if (settingsPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        }
        bar.addView(backBtn)
        bar.addView(urlField, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(reloadBtn)
        // Collapse-toggle placement scaffold. Only "chrome" is implemented; the
        // when() is the seam where future placements plug in without touching the
        // rest of the bar. Do NOT add a UI control to change this yet.
        when (collapseBtnPlacement) {
            "chrome" -> collapseBtn?.let { bar.addView(it) }
            else -> {
                // TODO future collapse-button placements (floating overlay, edge
                // gesture, hidden). Leave the button un-added so the bar stays clean.
            }
        }
        bar.addView(gearBtn)
        return bar
    }

    private fun toggleChrome() {
        if (chromeBar.visibility == View.VISIBLE) hideChrome() else showChrome()
    }

    private fun showChrome() {
        chromeBar.visibility = View.VISIBLE
        currentUrl?.let { urlField.setText(it) }
        // Shrink the web viewport by the chrome height so the bar doesn't cover the
        // bottom (or top) of the page — otherwise a position:fixed banner (GDPR /
        // cookie consent) sits under the bar and its buttons can't be tapped.
        chromeBar.post { applyChromeInset(true) }
        scheduleAutoHide()
    }

    /**
     * Lift the (bottom-edge) chrome bar by [imeBottomPx] so it sits JUST ABOVE the
     * on-screen input window — the Supernote handwriting panel and its autocomplete
     * strip live inside one bottom-docked IME window, so its inset height is exactly
     * the gap we need. Driven by the window-insets listener with the live IME height,
     * so it adapts to any keyboard on any device (Nomad panel, Manta keyboard) with
     * no hardcoded sizes. No-op when chrome is top-anchored (IME can't cover it there).
     */
    private fun liftChromeForIme(imeBottomPx: Int) {
        if (!chromeAtBottom || !::chromeBar.isInitialized) return
        val lp = chromeBar.layoutParams as? FrameLayout.LayoutParams ?: return
        if (lp.bottomMargin == imeBottomPx) return
        lp.bottomMargin = imeBottomPx
        chromeBar.layoutParams = lp
    }

    private fun hideChrome() {
        ui.removeCallbacks(autoHide)
        liftChromeForIme(0) // drop any IME lift so the next reveal sits at the edge
        chromeBar.visibility = View.GONE
        settingsPanel.visibility = View.GONE
        applyChromeInset(false)
        urlField.clearFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(urlField.windowToken, 0)
    }

    /**
     * Inset the GeckoView by the chrome bar's height on the chrome edge while it's
     * visible, restoring to flush when hidden. Preserves the inset-mode left/right
     * paging margins. The reflow this triggers is intentional — it lifts fixed
     * bottom/top page furniture (consent banners) out from under the bar.
     */
    private fun applyChromeInset(shown: Boolean) {
        val lp = view.layoutParams as? FrameLayout.LayoutParams ?: return
        val inset = if (shown) chromeBar.height.coerceAtLeast(dp(52)) else 0
        val newTop = if (chromeAtBottom) 0 else inset
        val newBottom = if (chromeAtBottom) inset else 0
        if (lp.topMargin == newTop && lp.bottomMargin == newBottom) return
        lp.topMargin = newTop
        lp.bottomMargin = newBottom
        view.layoutParams = lp
    }

    /**
     * Generous idle auto-hide (~7s). Never runs while the address field is focused
     * (typing pins the chrome open). Reset on any chrome touch.
     */
    private fun scheduleAutoHide() {
        ui.removeCallbacks(autoHide)
        if (::urlField.isInitialized && urlField.hasFocus()) return // pinned while typing
        ui.postDelayed(autoHide, 7000)
    }

    /** Called after Go/back/reload — hide the chrome once navigation is under way. */
    private fun afterNav() = hideChrome()

    /**
     * URL-or-search: if the text looks like a URL/host (starts with http, or has a
     * dot and no spaces) load it (prepend https:// when schemeless); otherwise
     * treat it as a DuckDuckGo search query.
     */
    private fun submitUrl(raw: String) {
        val t = raw.trim()
        if (t.isEmpty()) return
        val looksUrl = t.startsWith("http://") || t.startsWith("https://") ||
            (t.contains(".") && !t.contains(" "))
        val uri = if (looksUrl) {
            if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
        } else {
            val template = SEARCH_ENGINES[searchEngine] ?: SEARCH_ENGINES["DuckDuckGo"]!!
            template.replace("%s", URLEncoder.encode(t, "UTF-8"))
        }
        if (::session.isInitialized) session.loadUri(uri)
        afterNav()
    }

    // --- Settings: persistence + panel + levers -----------------------------

    private fun loadSettings() {
        prefs = getSharedPreferences("distesa_settings", MODE_PRIVATE)
        animOff = prefs.getBoolean("animOff", true)
        blockFonts = prefs.getBoolean("blockFonts", true)
        strictTp = prefs.getBoolean("strictTp", true)
        jsEnabled = prefs.getBoolean("jsEnabled", true)
        fullEvery = prefs.getInt("fullEvery", 6)
        loginHosts = HashSet(prefs.getStringSet("loginHosts", emptySet()) ?: emptySet())
        navStyle = prefs.getString("navStyle", "inset") ?: "inset"
        navPlacement = prefs.getString("navPlacement", "both") ?: "both"
        showZones = prefs.getBoolean("showZones", true)
        chromePos = prefs.getString("chromePos", "auto") ?: "auto"
        searchEngine = prefs.getString("searchEngine", "DuckDuckGo") ?: "DuckDuckGo"
        collapseMode = prefs.getString("collapseMode", "auto") ?: "auto"
        collapseThreshold = prefs.getInt("collapseThreshold", 6)
        collapseBtnPlacement = prefs.getString("collapseBtnPlacement", "chrome") ?: "chrome"
        edgeSlotLeft = prefs.getString("edgeSlotLeft", "chrome") ?: "chrome"
        edgeSlotRight = prefs.getString("edgeSlotRight", "collapse") ?: "collapse"
        // Lockout guard: with tap-to-dismiss and the centre reveal-strip gone, the
        // sliver is the ONLY way to open the toolbar. If the user cleared it from
        // both slots, force the left one back to chrome.
        if (edgeSlotLeft != "chrome" && edgeSlotRight != "chrome") edgeSlotLeft = "chrome"
        chromeAtBottom = computeChromeAtBottom()
    }

    /** Adaptive chrome edge: small screens (Nomad ~7.8") = bottom, large (Manta) = top. */
    private fun computeChromeAtBottom(): Boolean = when (chromePos) {
        "top" -> false
        "bottom" -> true
        else -> {
            val dm = resources.displayMetrics
            val diagIn = hypot(
                (dm.widthPixels / dm.xdpi).toDouble(),
                (dm.heightPixels / dm.ydpi).toDouble(),
            )
            diagIn < 9.0 // below ~9" diagonal → bottom (thumb-reachable)
        }
    }

    /** True if [host] should get Standard (not Strict) tracking protection. */
    private fun isLoginHost(host: String?): Boolean {
        if (host.isNullOrEmpty()) return false
        val h = host.lowercase()
        if (loginHosts.contains(h)) return true
        // Label-boundary match only: "evil-github.com" must NOT match "github.com".
        // (A bare endsWith would relax tracking protection for look-alike domains.)
        return AUTH_ALLOWLIST.any { h == it || h.endsWith(".$it") }
    }

    /**
     * Apply the correct ETP level for the host about to load: Standard for login/
     * auth hosts so sign-in isn't broken, otherwise the user's global setting
     * (Strict by default). Called per-navigation from the NavigationDelegate.
     */
    private fun applyEtpForHost(host: String?) {
        if (isLoginHost(host)) applyTrackingProtection(strict = false) // Standard
        else applyTrackingProtection(strict = strictTp)
    }

    /** Sets ETP just before each top-level navigation (earliest per-nav hook). */
    private val EtpNavigationDelegate = object : GeckoSession.NavigationDelegate {
        override fun onLoadRequest(
            s: GeckoSession,
            request: GeckoSession.NavigationDelegate.LoadRequest,
        ): GeckoResult<org.mozilla.geckoview.AllowOrDeny>? {
            if (request.target == GeckoSession.NavigationDelegate.TARGET_WINDOW_CURRENT) {
                val host = try { java.net.URI(request.uri).host } catch (e: Throwable) { null }
                applyEtpForHost(host)
            }
            return null // allow the load
        }

        override fun onLocationChange(
            s: GeckoSession,
            url: String?,
            perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
            hasUserGesture: Boolean,
        ) {
            currentUrl = url
            // Keep the (usually hidden) address field in sync when it's not focused.
            if (::urlField.isInitialized && !urlField.hasFocus()) {
                url?.let { urlField.setText(it) }
            }
        }

        override fun onCanGoBack(s: GeckoSession, value: Boolean) {
            canGoBack = value
            if (::backBtn.isInitialized) runOnUiThread {
                backBtn.isEnabled = value
                backBtn.visibility = if (value) View.VISIBLE else View.GONE
            }
        }
    }

    private fun buildSettingsPanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Light fill WITH a defined border — without a border the panel bleeds
            // into a white page and its edges vanish (the layuv problem). Dark-grey
            // 2px stroke + rounded corners so it reads as a distinct surface.
            background = GradientDrawable().apply {
                setColor(0xFFFAFAFA.toInt())
                setStroke(dp(2), 0xFF555555.toInt())
                cornerRadius = dp(10).toFloat()
            }
            // No elevation: on e-ink a drop shadow renders as a grey halo. The 2px
            // border already defines the surface. (E-ink rule: flat, no shadows.)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            visibility = View.GONE
        }
        // QUICK panel = the handful of controls toggled often. Everything else
        // lives on the dedicated SettingsActivity ("More settings…").
        //
        // Toolbar position — the plain, obvious control the user asked for.
        panel.addView(makeCycleRow({ "Toolbar position: ${chromePos.replaceFirstChar { it.uppercase() }}" }) {
            chromePos = when (chromePos) { "auto" -> "top"; "top" -> "bottom"; else -> "auto" }
            prefs.edit().putString("chromePos", chromePos).apply()
            recreate() // structural: rebuild at the new edge
        })
        panel.addView(makeSwitch("Animations off", animOff) { on ->
            animOff = on; prefs.edit().putBoolean("animOff", on).apply(); pushSettingsToExtension()
        })
        // Image policy cycle (per-domain; content script persists + reloads).
        imgToggle = makeButton("Images: …") { onCycleImagePolicy() }.apply { isEnabled = false }
        panel.addView(imgToggle)
        // Element zapper: arm picker mode, hide chrome, next page tap hides that
        // element (persisted per-site). Kills inline JS players / furniture that
        // has no src for a network/embed rule to match.
        panel.addView(makeButton("⬡ Zap element (hide)") { onArmZap() })
        // Undo / reset — a mis-tapped zap otherwise hides part of a site permanently
        // with no recovery. Undo pops the last zap for this host; reset clears them all.
        panel.addView(makeButton("↺ Undo last zap") { postToExtension("undoZap") })
        panel.addView(makeButton("Reset zaps (this site)") { postToExtension("resetZaps") })
        // Everything else moved to the dedicated page.
        panel.addView(makeButton("More settings…") {
            settingsPanel.visibility = View.GONE
            startActivity(Intent(this, SettingsActivity::class.java))
        })
        return panel
    }

    /** Boolean row as an explicit ☑/☐ + ON/OFF glyph — legible on grayscale e-ink
     *  where a SwitchCompat thumb/tint is not. Matches SettingsActivity. */
    private fun makeSwitch(label: String, initial: Boolean, onChange: (Boolean) -> Unit): Button {
        var state = initial
        fun render(): String = (if (state) "☑  " else "☐  ") + label + (if (state) "   · ON" else "   · OFF")
        return makeButton(render()) {}.apply {
            setOnClickListener { state = !state; text = render(); onChange(state) }
        }
    }

    private fun makeButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply { text = label; setTextColor(CHROME_INK); setBackgroundColor(Color.TRANSPARENT); setOnClickListener { onClick() } }

    /** A button whose label is re-read from [label] after each tap (cycle control). */
    private fun makeCycleRow(label: () -> String, onClick: () -> Unit): Button {
        val b = makeButton(label(), {})
        b.setOnClickListener { onClick(); b.text = label() }
        return b
    }

    private fun applyTrackingProtection(strict: Boolean) {
        try {
            val cb = sharedRuntime(this).settings.contentBlocking
            if (strict) {
                cb.setEnhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.STRICT)
                cb.setAntiTracking(
                    ContentBlocking.AntiTracking.STRICT or
                        ContentBlocking.AntiTracking.CRYPTOMINING or
                        ContentBlocking.AntiTracking.FINGERPRINTING,
                )
                cb.setStrictSocialTrackingProtection(true)
            } else {
                cb.setEnhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.NONE)
                cb.setAntiTracking(ContentBlocking.AntiTracking.NONE)
                cb.setStrictSocialTrackingProtection(false)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "applyTrackingProtection threw ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun reloadPage() {
        if (::session.isInitialized) session.reload()
    }

    /** Push content-script-side levers (animations, font-block gate) to the extension. */
    private fun pushSettingsToExtension() {
        val p = einkPort ?: return
        try {
            p.postMessage(
                org.json.JSONObject()
                    .put("type", "settings")
                    .put("animOff", animOff)
                    .put("blockFonts", blockFonts)
                    .put("collapseMode", collapseMode)
                    .put("collapseThreshold", collapseThreshold),
            )
        } catch (e: Throwable) {
            Log.w(TAG, "pushSettingsToExtension threw ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Logs per-page load time + the current lever states for on-device A/B. */
    private val PerfProgressDelegate = object : GeckoSession.ProgressDelegate {
        override fun onPageStart(s: GeckoSession, url: String) {
            pageStartMs = SystemClock.elapsedRealtime()
            pageHost = try { java.net.URI(url).host ?: url } catch (e: Throwable) { url }
        }

        override fun onPageStop(s: GeckoSession, success: Boolean) {
            if (pageStartMs <= 0L) return
            val ms = SystemClock.elapsedRealtime() - pageStartMs
            pageStartMs = 0L
            Log.i(
                TAG,
                "[eink-perf] page=$pageHost loadMs=$ms" +
                    " js=${if (jsEnabled) "on" else "off"}" +
                    " fonts=${if (blockFonts) "blocked" else "on"}" +
                    " tp=${if (strictTp) "strict" else "off"}" +
                    " anim=${if (animOff) "off" else "on"}",
            )
        }
    }

    /**
     * Install our bundled "eink" WebExtension (tap-to-flip pagination) from
     * assets. installBuiltIn is idempotent across launches. Failure logs and
     * leaves the app usable.
     */
    private fun installEink(runtime: GeckoRuntime) {
        try {
            runtime.webExtensionController.installBuiltIn(EINK_URI).accept(
                { ext ->
                    Log.i(TAG, "eink extension installed: ${ext?.id}")
                    // DIAGNOSTIC: log which permissions GeckoView actually GRANTED
                    // our bundled extension — this is the direct capability probe
                    // for whether "webRequest"/"webRequestBlocking" are honored.
                    val md = ext?.metaData
                    Log.i(TAG, "[eink-diag] granted perms=${md?.requiredPermissions?.joinToString(",")}")
                    Log.i(TAG, "[eink-diag] granted origins=${md?.requiredOrigins?.joinToString(",")}")
                    // Register a native-messaging delegate under the special app
                    // name "browser" so the content script's runtime.sendMessage
                    // reaches us on every page flip.
                    ext?.setMessageDelegate(FlipMessageDelegate, "browser")
                },
                { e -> Log.w(TAG, "eink extension install failed: ${e?.message}") },
            )
        } catch (e: Throwable) {
            Log.w(TAG, "eink install threw ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * Receives {type:"flip"} messages from the eink content script and drives the
     * EPD refresh. Posted onto the view so the full clear runs AFTER the flip has
     * painted. All failures are logged and swallowed — the extension must keep
     * flipping pages even if the native refresh path errors.
     */
    private val FlipMessageDelegate = object : WebExtension.MessageDelegate {
        // content.js signals each page flip via runtime.sendMessage → onMessage.
        override fun onMessage(
            nativeApp: String,
            message: Any,
            sender: WebExtension.MessageSender,
        ): GeckoResult<Any>? {
            try {
                val obj = message as? org.json.JSONObject
                val type = obj?.optString("type") ?: message.toString()
                when (type) {
                    "flip" -> onFlip()
                    // DIAGNOSTIC: background.js reports webRequest capability +
                    // request/cancel counters here (its own console.log doesn't
                    // reliably reach logcat), surfaced under tag DistesaMain.
                    "diag" -> Log.i(TAG, "[eink-diag] ${obj?.optString("msg")}")
                }
            } catch (e: Throwable) {
                Log.w(TAG, "flip message handling threw ${e.javaClass.simpleName}: ${e.message}")
            }
            return null
        }

        // images.js opens a long-lived port via runtime.connect → onConnect. We
        // keep the latest port (a page navigation replaces it) to push policy
        // cycle commands, and read its policy reports back to label the button.
        override fun onConnect(port: WebExtension.Port) {
            einkPort = port
            port.setDelegate(EinkPortDelegate)
            runOnUiThread {
                imgToggle.isEnabled = true
                // Send content-script levers (animations off, font-block gate) so
                // the freshly-connected page applies them.
                pushSettingsToExtension()
            }
        }
    }

    private val EinkPortDelegate = object : WebExtension.PortDelegate {
        override fun onPortMessage(message: Any, port: WebExtension.Port) {
            try {
                val obj = message as? org.json.JSONObject ?: return
                when (obj.optString("type")) {
                    "policy" -> {
                        val policy = obj.optString("policy")
                        runOnUiThread {
                            imgToggle.isEnabled = true
                            imgToggle.text = "Images: ${shortPolicy(policy)}"
                        }
                    }
                    // images.js reports the current collapse mode → label the toggle
                    // (⊞ = tap to expand to boxes, ⊟ = tap to collapse to chips).
                    "collapsed" -> {
                        imagesCollapsed = obj.optBoolean("value", true)
                        runOnUiThread { collapseBtn?.text = if (imagesCollapsed) "⊞" else "⊟" }
                    }
                    // images.js found a password field on this host → remember it
                    // so tracking protection relaxes to Standard for its loads.
                    "loginHost" -> {
                        val host = obj.optString("host")
                        if (host.isNotEmpty() && loginHosts.add(host)) {
                            prefs.edit().putStringSet("loginHosts", loginHosts).apply()
                            Log.i(TAG, "[eink-diag] login host added -> $host (ETP relaxed to standard)")
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "port message handling threw ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        override fun onDisconnect(port: WebExtension.Port) {
            if (port == einkPort) einkPort = null
        }
    }

    /** Flip all placeholders on the current page between chips and boxes (per-page, not persisted per-site). */
    private fun onToggleCollapse() {
        val port = einkPort
        if (port == null) {
            Log.w(TAG, "toggle collapse: no eink port yet")
            return
        }
        try {
            port.postMessage(org.json.JSONObject().put("type", "collapseToggle"))
        } catch (e: Throwable) {
            Log.w(TAG, "collapseToggle post threw ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Arm the in-page element zapper: close chrome so the page is tappable, then the next tap hides that element. */
    private fun onArmZap() {
        val port = einkPort
        if (port == null) {
            Log.w(TAG, "arm zap: no eink port yet")
            return
        }
        hideChrome() // get chrome + panel out of the way so the next tap lands on the page
        try {
            port.postMessage(org.json.JSONObject().put("type", "armZap"))
        } catch (e: Throwable) {
            Log.w(TAG, "armZap post threw ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Post a simple {type:...} message to the eink content script (undo/reset zaps, etc.). */
    private fun postToExtension(type: String) {
        val port = einkPort
        if (port == null) {
            Log.w(TAG, "$type: no eink port yet")
            return
        }
        settingsPanel.visibility = View.GONE
        try {
            port.postMessage(org.json.JSONObject().put("type", type))
        } catch (e: Throwable) {
            Log.w(TAG, "$type post threw ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Ask the content script to advance this domain's image policy (it persists + reloads). */
    private fun onCycleImagePolicy() {
        val port = einkPort
        if (port == null) {
            Log.w(TAG, "cycle image policy: no eink port yet")
            return
        }
        try {
            port.postMessage(org.json.JSONObject().put("type", "cyclePolicy"))
        } catch (e: Throwable) {
            Log.w(TAG, "cyclePolicy post threw ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun shortPolicy(policy: String): String = when (policy) {
        "hide-all" -> "hidden"
        "placeholder-tap" -> "tap"
        "primary-content-only" -> "primary"
        "load-all" -> "all"
        else -> policy
    }

    private fun onFlip() {
        if (!::view.isInitialized) return
        view.post {
            try {
                val fullClear = epd.pageTurn(view)
                if (fullClear) {
                    Log.i(TAG, "flip -> EPD FULL CLEAR (every ${Epd.FULL_EVERY} turns)")
                } else {
                    Log.i(TAG, "flip -> partial refresh")
                }
            } catch (e: Throwable) {
                Log.w(TAG, "EPD refresh threw ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    /**
     * Resolve uBlock: if it's already installed (a prior launch), reuse it;
     * otherwise install it from AMO. Failures log and leave the app usable.
     */
    private fun ensureUBlock(runtime: GeckoRuntime) {
        val controller = runtime.webExtensionController
        controller.list().accept(
            { installed ->
                val existing = installed?.firstOrNull { it.id == UBLOCK_ID }
                if (existing != null) {
                    Log.i(TAG, "uBlock already installed: ${existing.id}")
                    onExtResolved(existing)
                } else {
                    installUBlock(controller)
                }
            },
            { e ->
                Log.w(TAG, "list() failed (${e?.message}); attempting install anyway")
                installUBlock(controller)
            },
        )
    }

    private fun installUBlock(controller: WebExtensionController) {
        Log.i(TAG, "Installing uBlock Origin from AMO: $UBLOCK_XPI")
        controller.install(UBLOCK_XPI).accept(
            { ext ->
                Log.i(TAG, "uBlock installed: ${ext?.id}")
                onExtResolved(ext)
            },
            { e -> Log.w(TAG, "uBlock install failed: ${e?.message}") },
        )
    }

    /** Cache the resolved add-on. The on/off UI now lives in SettingsActivity. */
    private fun onExtResolved(ext: WebExtension?) {
        ublock = ext
    }

    override fun onResume() {
        super.onResume()
        if (!::prefs.isInitialized) return
        // Re-read settings that SettingsActivity may have changed and apply them.
        val oldStructural = listOf(navStyle, navPlacement, chromePos, showZones.toString())
        val oldContent = listOf(
            strictTp.toString(), jsEnabled.toString(), blockFonts.toString(), animOff.toString(),
            collapseMode, collapseThreshold.toString(), fullEvery.toString(),
        )
        loadSettings()
        val newStructural = listOf(navStyle, navPlacement, chromePos, showZones.toString())
        val newContent = listOf(
            strictTp.toString(), jsEnabled.toString(), blockFonts.toString(), animOff.toString(),
            collapseMode, collapseThreshold.toString(), fullEvery.toString(),
        )
        // Engine-level levers apply immediately.
        applyTrackingProtection(strictTp)
        Epd.FULL_EVERY = fullEvery
        if (::session.isInitialized) session.settings.setAllowJavascript(jsEnabled)
        // Content-script levers (animations, fonts, collapse) pushed to the extension.
        pushSettingsToExtension()
        // A structural change (edge/nav) needs a full rebuild.
        if (oldStructural != newStructural) { recreate(); return }
        // A content-affecting change (or a uBlock toggle from settings) needs a reload.
        val ublockDirty = prefs.getBoolean("ublockDirty", false)
        if (ublockDirty) prefs.edit().putBoolean("ublockDirty", false).apply()
        if ((oldContent != newContent || ublockDirty) && ::session.isInitialized) session.reload()
    }

    override fun onDestroy() {
        // The GeckoSession is per-Activity; the runtime is a process singleton and
        // intentionally outlives it.
        if (::session.isInitialized) session.close()
        super.onDestroy()
    }

    /** Auto-grants install/permission/optional prompts — test spike only. */
    private object AutoApprovePromptDelegate : WebExtensionController.PromptDelegate {
        override fun onInstallPromptRequest(
            extension: WebExtension,
            permissions: Array<out String>,
            origins: Array<out String>,
            dataCollectionPermissions: Array<out String>,
        ): GeckoResult<WebExtension.PermissionPromptResponse> =
            GeckoResult.fromValue(WebExtension.PermissionPromptResponse(true, true, true))

        override fun onUpdatePrompt(
            extension: WebExtension,
            newPermissions: Array<out String>,
            newOrigins: Array<out String>,
            newDataCollectionPermissions: Array<out String>,
        ): GeckoResult<org.mozilla.geckoview.AllowOrDeny> =
            GeckoResult.allow()

        override fun onOptionalPrompt(
            extension: WebExtension,
            optionalPermissions: Array<out String>,
            optionalOrigins: Array<out String>,
            optionalDataCollectionPermissions: Array<out String>,
        ): GeckoResult<org.mozilla.geckoview.AllowOrDeny> =
            GeckoResult.allow()
    }

    companion object {
        private const val TAG = "DistesaMain"

        /** EPD full-clear cadence steps cycled by the settings button (0 = Off). */
        internal val CADENCE_STEPS = intArrayOf(0, 4, 6, 8, 10, 15)

        /** Auto-collapse threshold steps cycled on the settings page. */
        internal val COLLAPSE_THRESHOLD_STEPS = intArrayOf(3, 5, 6, 8, 10, 15)

        /** Dark ink for all (light) chrome — high contrast, no colour. */
        internal const val CHROME_INK = 0xFF222222.toInt()

        /** Height (dp) of the bottom-of-strip sliver action button. */
        private const val SLIVER_H = 56

        /** Built-in search engines (query templates, %s = encoded query). */
        internal val SEARCH_ENGINES = linkedMapOf(
            "DuckDuckGo" to "https://duckduckgo.com/?q=%s",
            "Startpage" to "https://www.startpage.com/sp/search?query=%s",
            "Brave" to "https://search.brave.com/search?q=%s",
            "Google" to "https://www.google.com/search?q=%s",
        )

        /** Common auth/SSO hosts always treated as Standard TP (suffix match). */
        private val AUTH_ALLOWLIST = listOf(
            "accounts.google.com",
            "login.microsoftonline.com",
            "appleid.apple.com",
            "login.live.com",
            "facebook.com",
            "github.com",
            "auth0.com",
            "okta.com",
        )

        /**
         * Text-based, low-JS, reliably scrollable page — makes tap-to-flip (and
         * the flip → EPD refresh) easy to exercise on-device. It also has a mix of
         * images (infobox + thumbnails) for testing the image-policy modes.
         */
        private const val TEST_URL = "https://en.wikipedia.org/wiki/E_Ink"

        /** Our bundled page-flip extension, loaded from assets via installBuiltIn. */
        private const val EINK_URI = "resource://android/assets/extensions/eink/"

        /** uBlock Origin's stable add-on id (used to detect an existing install). */
        internal const val UBLOCK_ID = "uBlock0@raymondhill.net"

        /** AMO "latest" signed xpi for uBlock Origin (302-redirects to the current version). */
        internal const val UBLOCK_XPI =
            "https://addons.mozilla.org/firefox/downloads/latest/ublock-origin/latest.xpi"

        @Volatile private var runtime: GeckoRuntime? = null

        /** Process-wide GeckoRuntime singleton. */
        internal fun sharedRuntime(activity: Activity): GeckoRuntime {
            return runtime ?: synchronized(this) {
                runtime ?: createRuntime(activity).also { runtime = it }
            }
        }

        private fun createRuntime(activity: Activity): GeckoRuntime {
            val settings = GeckoRuntimeSettings.Builder()
                // Route JS console (including our extension's console.log) to
                // logcat so [eink-bg]/[eink-images] diagnostics are visible.
                .consoleOutput(true)
                // Run WebExtensions IN the main process. Blocking webRequest
                // (return {cancel:true}) must be honored synchronously; an
                // out-of-process extension can silently downgrade blocking to
                // observe-only, which defeats the media network-block.
                .extensionsProcessEnabled(false)
                .build()
            return GeckoRuntime.create(activity.applicationContext, settings)
        }
    }
}
