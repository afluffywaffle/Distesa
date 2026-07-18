package com.afluffywaffle.avosetta

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.text.Spannable
import android.text.SpannableString
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.Log
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.afluffywaffle.avosetta.eink.EdgeNavView
import java.net.URLEncoder
import kotlin.math.hypot
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.PanZoomController
import org.mozilla.geckoview.ScreenLength
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController
import com.afluffywaffle.avosetta.eink.Epd
import com.afluffywaffle.avosetta.eink.GlobeSearchDrawable

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
    // Full-width history-autocomplete panel; lives inside the chrome wrapper next to the
    // address bar so it rides the same IME lift (see buildChromeBar / updateSuggestions).
    private lateinit var suggestBox: LinearLayout
    private lateinit var urlField: EditText
    private lateinit var backBtn: Button
    // A "go" (→) affordance that appears in the address bar only while the field is
    // focused, so novices who don't know to press Enter have a visible submit control.
    private var goBtn: Button? = null
    private var canGoBack = false
    private var currentUrl: String? = null
    private var currentTitle: String? = null

    // A thin, always-on wayfinding strip showing the current domain (+ page title),
    // anchored to the same edge as the chrome bar. Tapping it summons the chrome/address
    // bar. Hidden on the blank home and while the full chrome is up (redundant there).
    private lateinit var domainBar: TextView
    // The strip doubles as a coarse e-ink progress bar: a left→right fill (the clip layer
    // of the strip's background) advanced in a few discrete buckets, plus a ⟳ prefix while
    // a load is in flight. Discrete steps keep EPD refreshes to a handful, not a stream.
    private lateinit var domainFill: ClipDrawable
    private var domainLoading = false
    // Gecko reports progress in a few coarse jumps (often 0 → ~75 → done), which reads as a
    // single flash. So we drive an indeterminate CREEP on a timer while loading and show the
    // MAX of the creep and Gecko's real value — guaranteeing steady left→right motion.
    private var domainCreepPct = 0
    private var domainRealPct = 0
    // Chloe "home" mascot: a full-bleed overlay above the (opaque) GeckoView, shown only
    // when no real page is loaded — the empty/new-tab state. Hidden the moment a real URL
    // loads (see setHomeVisible / onLocationChange).
    private lateinit var homeMascot: ImageView
    private val ui = Handler(Looper.getMainLooper())
    private val autoHide = Runnable { hideChrome() }
    private var chromeAtBottom = false

    // Navigation/paging + chrome levers (persisted).
    private var navStyle = "inset"       // "inset" (narrow the page) | "overlay"
    private var navPlacement = "both"    // "both" | "left" | "right" | "none"
    private var showZones = true         // draw the faint chevron affordance
    private var chromePos = "auto"       // "auto" | "top" | "bottom"
    // Paging direction. true (default) = NATURAL: pressing the UP chevron moves the page
    // content UP (advance/forward), matching a touchscreen drag — the direction you press
    // is the direction the page travels. false = SCROLLBAR: up = back, down = forward (the
    // page behaves like a dragged scrollbar thumb). Inverts flipPage's frac sign.
    private var naturalScroll = true
    private var searchEngine = "DuckDuckGo"
    // Custom search: a URL template with "%s" where the query goes, used when
    // searchEngine == "Custom" (see submitUrl / SEARCH_ENGINES + the layout editor).
    private var customSearchUrl = ""
    private var leftStrip: EdgeNavView? = null
    private var rightStrip: EdgeNavView? = null
    // Bottom-of-strip sliver buttons (☰/⊟), tracked so they can be hidden with the
    // strips while the IME is up (the chevrons/slivers look out of place over the keyboard).
    private val sliverButtons = mutableListOf<Button>()

    // Edge-sliver action slots: a small button at the top AND bottom of each nav strip,
    // ALWAYS drawn (independent of showZones) so there is always a way to surface
    // chrome. Values: "chrome" (reveal toolbar) | "collapse" (flip placeholders) |
    // "back" (history back) | "refresh" (reload) | "none". In single-strip mode
    // (navPlacement "left"/"right") the bottom slot is always forced to "chrome" so the
    // user can never lock themselves out of the toolbar; the top slot stays whatever the
    // user configured for that side. In "both" mode a load-time guard forces one BOTTOM
    // slot to "chrome" if neither side has a chrome slot anywhere. "favorites" is
    // reserved for later.
    private var edgeSlotLeftTop = "none"
    private var edgeSlotLeftBottom = "chrome"
    private var edgeSlotRightTop = "none"
    private var edgeSlotRightBottom = "collapse"

    // Auto-focus the address field when the toolbar is revealed by an explicit tap on
    // the chrome sliver (⌕). Since chrome is hidden by default, this makes the sliver a
    // one-tap "type an address" affordance; back/⟳ stay reachable on the revealed bar
    // (and can be put on the other sliver). Never fires on the idle-timer reveal.
    private var autoFocusOnReveal = false
    // How a tap on the thin domain strip decides auto-focus, independent of the sliver:
    // "follow" = use autoFocusOnReveal, "always" = focus, "never" = don't. (LayoutActivity.)
    private var domainFocusMode = "follow"

    // E-ink performance levers (persisted in SharedPreferences; see loadSettings()).
    private var animOff = true          // inject animation/transition-killing CSS
    private var blockFonts = true       // block web-font requests (system fallback)
    private var strictTp = true         // engine strict tracking protection
    private var jsEnabled = true        // per-session JavaScript
    // E-ink contrast levers. preferLight tells pages we want their LIGHT variant
    // (engine-level preferredColorScheme); forceLight is the aggressive override
    // that forces black-on-white via injected CSS (content script). Both default
    // ON — a grayscale panel renders dark themes as muddy grey, so light + high
    // contrast is the right baseline for the device.
    private var preferLight = true      // engine COLOR_SCHEME_LIGHT
    private var forceLight = true       // inject black-on-white CSS override

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
    // Configurable chrome-bar button slots — like the edge slivers, each picks a
    // function from {back, refresh, collapse, none}. Layout is [left] url [right1][right2]
    // ⚙, where ⚙ (settings) is fixed and always present so settings is never stranded.
    private var chromeBtnLeft = "back"
    private var chromeBtnRight1 = "refresh"
    private var chromeBtnRight2 = "collapse"

    // Load-time measurement.
    private var pageStartMs = 0L
    private var pageHost = ""

    // Hosts observed to have a password field — tracking protection is relaxed to
    // Standard for these (Strict can break sign-in). Persisted; grows over time.
    private var loginHosts: MutableSet<String> = mutableSetOf()

    // Per-site "render as-is" escape hatch. A host in this set bypasses EVERY e-ink
    // lever — tracking protection, JS-off, colour scheme, font block, animations-off,
    // force-light, image gating/collapse — and loads natively, while the user's global
    // prefs stay untouched (flip it back to restore them). Native is the source of
    // truth (engine levers read it per-navigation); the content script keeps a mirror
    // in storage.local so it also bypasses at document_start (see images.js _raw:HOST).
    private var rawHosts: MutableSet<String> = mutableSetOf()
    private var rawBtn: Button? = null

    /** The installed uBlock Origin add-on, once resolved (install or list). */
    private var ublock: WebExtension? = null

    /**
     * Native EPD refresh driver for the GeckoView surface. On each page flip the
     * eink extension signals us; [Epd] counts turns and forces a clean full-panel
     * clear (via RattaEink → Supernote EinkManager) every [Epd.FULL_EVERY] turns.
     */
    private val epd = Epd()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Edge-to-edge: required for the IME (and system-bar) insets to be dispatched
        // to our OnApplyWindowInsetsListener. Without this the decor view fits system
        // windows and consumes ime() insets before they reach `root`, so the address
        // bar can never lift above the on-screen input panel (adjustNothing alone is
        // not enough). See liftChromeForIme / the root insets listener below.
        window.setDecorFitsSystemWindows(false)

        loadSettings()

        // The GeckoRuntime + extension + background page are process-wide and
        // survive activity recreate() (e.g. from a settings toggle that calls
        // recreate()). A port left over from a prior activity instance is now
        // dead weight — force it closed so background.js's onDisconnect fires
        // and it self-heals with a fresh connectNative bound to THIS activity.
        try {
            einkPort?.disconnect()
        } catch (e: Throwable) {
            Log.w(TAG, "stale einkPort disconnect threw ${e.javaClass.simpleName}: ${e.message}")
        }
        einkPort = null

        val runtime = sharedRuntime(this)
        // Auto-approve add-on install + permission prompts (test spike only).
        runtime.webExtensionController.promptDelegate = AutoApprovePromptDelegate
        // Engine-level tracking protection, applied before the first load.
        applyTrackingProtection(strictTp)
        // Engine-level preferred colour scheme (light for e-ink), before first load.
        applyColorScheme(preferLight)
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
        session.contentDelegate = TitleContentDelegate
        session.permissionDelegate = DenyPermissionDelegate
        session.scrollDelegate = EinkScrollDelegate

        view = GeckoView(this)
        view.setSession(session)

        val edge = if (chromeAtBottom) Gravity.BOTTOM else Gravity.TOP

        // Default state = NOTHING but the page. Chrome is summoned via the edge sliver;
        // once open it stays open until dismissed — either by the sliver toggle (☰) or by
        // a tap anywhere OFF the chrome (see onInterceptTouchEvent below). There is no idle
        // timeout. While chrome is hidden a page tap is never intercepted, so a lifted
        // consent banner can't shift out from under the user's finger mid-tap.
        val root = object : FrameLayout(this) {
            override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
                // Tap-outside-to-dismiss. While the chrome bar (or its quick panel) is up,
                // the first tap that lands off both of them closes the chrome and is
                // consumed here, so it only dismisses — it doesn't also click a page link
                // or fire a paging strip underneath. Taps ON the chrome/panel fall through
                // to their own handlers untouched.
                if (ev.actionMasked == MotionEvent.ACTION_DOWN &&
                    ::chromeBar.isInitialized && chromeBar.visibility == View.VISIBLE &&
                    !pointInView(chromeBar, ev) &&
                    !(::settingsPanel.isInitialized &&
                        settingsPanel.visibility == View.VISIBLE && pointInView(settingsPanel, ev))
                ) {
                    hideChrome()
                    return true
                }
                return super.onInterceptTouchEvent(ev)
            }
        }
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

        // Chloe home mascot: sits directly ABOVE the opaque GeckoView (which can't be seen
        // through), but is added BEFORE the strips/chrome so the slivers and gear still
        // draw on top and stay tappable — the user can summon chrome and type a URL from
        // the home screen. Visible only in the empty state; hidden once a page loads.
        homeMascot = ImageView(this).apply {
            setImageResource(R.drawable.chloe_typing)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0xFFFFFFFF.toInt())
            setPadding(dp(32), dp(64), dp(32), dp(64))
        }
        root.addView(homeMascot, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
        ))

        // Paging strips (native EdgeNavView). Bottom-weighted; tap = flip.
        addStrips(root, stripW)

        // The chrome-reveal affordance now lives in the edge slivers (bottom of each
        // nav strip, added by addStrips) — freeing the bottom CENTRE, where consent
        // banners put their buttons, so those stay tappable. Chrome hides via its sliver
        // toggle or a tap off the chrome (root.onInterceptTouchEvent); no idle timeout.

        // Thin always-on domain/title strip at the adaptive edge, inset between the
        // strips exactly like the chrome bar. It's the resting state (chrome hidden):
        // shows where you are and, on tap, summons the full chrome/address bar. Added
        // BEFORE the chrome bar so the chrome sits above it in z-order.
        domainBar = buildDomainBar()
        root.addView(
            domainBar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, edge,
            ).apply {
                leftMargin = if (navPlacement != "right") stripW else CHROME_EDGE_GAP
                rightMargin = if (navPlacement != "left") stripW else CHROME_EDGE_GAP
                if (chromeAtBottom) bottomMargin = CHROME_EDGE_GAP else topMargin = CHROME_EDGE_GAP
            },
        )

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
                // Inset from the strips (or a small gap where there's no strip) so the
                // pane's border never sits flush against a screen edge and gets clipped.
                leftMargin = if (navPlacement != "right") stripW else CHROME_EDGE_GAP
                rightMargin = if (navPlacement != "left") stripW else CHROME_EDGE_GAP
                // Resting gap off the anchored edge so the pane's bottom (or top) border
                // and rounded corners are fully visible. The IME-lift overrides this.
                if (chromeAtBottom) bottomMargin = CHROME_EDGE_GAP else topMargin = CHROME_EDGE_GAP
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

        // Default the window to ADJUST_RESIZE so that when a WEB-PAGE field is focused the
        // window shrinks by the IME and GeckoView scrolls the focused element into view.
        // We flip to ADJUST_NOTHING only while OUR address field is focused (see urlField's
        // focus listener), where a resize would fight the custom lift we apply to the bar.
        setSoftInput(adjustNothing = false)

        // Keep the chrome bar sitting just above the on-screen input window while the
        // address field is focused. When our field is focused the mode is ADJUST_NOTHING,
        // so the system won't move anything for us — we read the live IME inset and lift the
        // bar by exactly that height (adapts to any keyboard/panel; see liftChromeForIme).
        root.setOnApplyWindowInsetsListener { v, insets ->
            imeInsetLast = insets.getInsets(WindowInsets.Type.ime()).bottom
            applyImeLift()
            v.onApplyWindowInsets(insets)
        }

        // Open a URL handed in by the History page (which relaunches us with a navUrl
        // extra), otherwise start on the blank Chloe home — the mascot overlay is visible
        // by default and stays until the user navigates somewhere.
        val navUrl = intent?.getStringExtra(EXTRA_NAV_URL)
        if (!navUrl.isNullOrBlank()) {
            intent.removeExtra(EXTRA_NAV_URL)
            session.loadUri(navUrl)
        } else {
            setHomeVisible(true)
        }

        ensureUBlock(runtime)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** Show/hide the Chloe home mascot (the empty-state overlay above the web view). */
    private fun setHomeVisible(visible: Boolean) {
        if (::homeMascot.isInitialized) {
            homeMascot.visibility = if (visible) View.VISIBLE else View.GONE
        }
    }

    /** True when no real page is loaded (blank / new-tab / about:blank). */
    private fun isBlankUrl(url: String?): Boolean =
        url.isNullOrBlank() || url == "about:blank"

    /** Builds the 0–2 native paging strips per the placement + style settings. */
    private fun addStrips(root: FrameLayout, stripW: Int) {
        leftStrip = null
        rightStrip = null
        sliverButtons.clear()
        when (navPlacement) {
            // No strips at all: the only affordance is a floating chrome button.
            "none" -> addFloatingChromeButton(root)
            // Single strip: the bottom sliver is ALWAYS chrome (the guaranteed way to
            // reach the toolbar with no second strip to carry it); the top sliver stays
            // whatever that side is configured for.
            "left" -> leftStrip = addStrip(root, stripW, Gravity.START, edgeSlotLeftTop, "chrome")
            "right" -> rightStrip = addStrip(root, stripW, Gravity.END, edgeSlotRightTop, "chrome")
            else -> {
                leftStrip = addStrip(root, stripW, Gravity.START, edgeSlotLeftTop, edgeSlotLeftBottom)
                rightStrip = addStrip(root, stripW, Gravity.END, edgeSlotRightTop, edgeSlotRightBottom)
            }
        }
    }

    /**
     * One paging strip plus its optional top and bottom sliver buttons, both FUSED into
     * the same continuous capsule (one unbroken outline). The capsule keeps the strip's
     * EXISTING overall size/position (its top edge is always [EdgeNavView.ACTIVE_TOP] of
     * the strip's height, same as with no top sliver at all) — a top cap is carved out
     * of the INSIDE of that capsule, shrinking the paging/chevron zone from within
     * (mirrors how a bottom cap already shrinks it), so the bottom-weighted ergonomics
     * (flip targets low, where a thumb rests) are never disturbed.
     */
    private fun addStrip(root: FrameLayout, stripW: Int, side: Int, slotTop: String, slotBottom: String): EdgeNavView {
        val hasTopSliver = slotTop != "none"
        val hasBottomSliver = slotBottom != "none"
        val sliverPx = dp(SLIVER_H)
        // Lift the bottom sliver off the bottom edge by the same gap the chrome pane
        // rests at, so the sliver glyph shares the address-bar's row when the bar is
        // revealed (bottoms align; the pane is inset horizontally to sit between the
        // strips).
        val sliverGap = if (chromeAtBottom) CHROME_EDGE_GAP else 0
        val strip = EdgeNavView(
            this, showZones,
            capReservePx = if (hasBottomSliver) sliverPx else 0,
            capReserveTopPx = if (hasTopSliver) sliverPx else 0,
            // Interface-level inversion only: the scroll mechanism ([flipPage]) is
            // untouched. NATURAL (default) maps the UPPER zone / up-chevron to "next"
            // (page content travels up, matching the chevron), the lower zone to "prev".
            // SCROLLBAR mode keeps the classic upper=prev / lower=next mapping.
            onNext = { flipPage(if (naturalScroll) "prev" else "next") },
            onPrev = { flipPage(if (naturalScroll) "next" else "prev") },
        )
        root.addView(
            strip,
            FrameLayout.LayoutParams(stripW, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.BOTTOM or side).apply {
                bottomMargin = sliverGap
            },
        )
        if (hasBottomSliver) {
            val sliver = makeSliverButton(slotBottom)
            root.addView(
                sliver,
                FrameLayout.LayoutParams(stripW, sliverPx, Gravity.BOTTOM or side).apply {
                    bottomMargin = sliverGap
                },
            )
            sliverButtons.add(sliver)
        }
        if (hasTopSliver) {
            val sliver = makeSliverButton(slotTop)
            val lp = FrameLayout.LayoutParams(stripW, sliverPx, Gravity.TOP or side)
            root.addView(sliver, lp)
            sliverButtons.add(sliver)
            // The top cap sits INSIDE the strip's existing capsule, starting at
            // EdgeNavView.ACTIVE_TOP of the strip's own (post-layout) height — that
            // height isn't known yet in onCreate, so position it once layout completes.
            strip.post {
                lp.topMargin = (strip.height * EdgeNavView.ACTIVE_TOP).toInt()
                sliver.layoutParams = lp
            }
        }
        return strip
    }

    /**
     * The no-nav-strips fallback: with navPlacement "none" there is no rail to carry a
     * chrome sliver, so a small floating button sits bottom-right instead — same size
     * as a strip sliver, its own bordered pill since there's no rail behind it.
     */
    private fun addFloatingChromeButton(root: FrameLayout) {
        val sliverPx = dp(SLIVER_H)
        val btn = makeSliverButton("chrome").apply { background = borderedPillBackground() }
        root.addView(
            btn,
            FrameLayout.LayoutParams(sliverPx, sliverPx, Gravity.BOTTOM or Gravity.END).apply {
                bottomMargin = CHROME_EDGE_GAP
                rightMargin = CHROME_EDGE_GAP
            },
        )
        sliverButtons.add(btn)
    }

    /**
     * A faint rounded-rect outline matching the strip capsule's hairline + rounding
     * (EdgeNavView's railPaint/railRadius), for sliver buttons that stand alone with no
     * capsule behind them to read as tappable.
     */
    private fun borderedPillBackground(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(12).toFloat()
        setStroke(dp(2), Color.rgb(200, 200, 200))
        setColor(Color.TRANSPARENT)
    }

    /** The little bottom-of-strip action button (chrome reveal / collapse toggle). */
    private fun makeSliverButton(slot: String): Button {
        val glyph = when (slot) {
            "collapse" -> if (imagesCollapsed) "⊞" else "⊟"
            "back" -> "←"
            "refresh" -> "⟳"
            "rendering", "zap", "undozap", "resetzap" -> NavActions.glyph(slot)
            else -> "" // chrome (globe drawable) + supernote/extensions (vector icons)
        }
        return Button(this).apply {
            text = glyph
            setTextColor(Color.rgb(120, 120, 120)) // matches the chevrons/rail ink
            // No background of its own: the continuous capsule outline (and the floating
            // divider above this cap) is drawn by the EdgeNavView behind it. This button
            // is just the icon + click sitting in the capsule's bottom cap.
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(0, 0, 0, 0)
            // "‹" read as too thin/faint next to the bold drawn chevrons — bump size and
            // weight for "back" so it reads with the same visual weight as the rest of
            // the rail's affordances.
            textSize = if (slot == "back") 26f else 20f
            if (slot == "back") setTypeface(typeface, android.graphics.Typeface.BOLD)
            // The chrome/address sliver reveals AND focuses the address bar — both "web
            // address" and "search". A drawn globe-with-magnifier says both; a font glyph
            // can't overlap the two, so use a centred foreground Drawable. Sized to sit in
            // the sliver like the text glyphs (~dp26), same faint grey as the chevrons.
            when (slot) {
                "chrome" -> {
                    foreground = GlobeSearchDrawable(Color.rgb(120, 120, 120), 0xFFFAFAFA.toInt(), dp(26))
                    foregroundGravity = Gravity.CENTER
                }
                "supernote", "extensions" -> {
                    // Same drawn B&W icon as the layout-editor top bar, tinted to the faint
                    // rail ink so it sits with the chevrons.
                    foreground = ContextCompat.getDrawable(this@MainActivity, NavActions.iconRes(slot)!!)
                        ?.mutate()?.apply { setTint(Color.rgb(120, 120, 120)) }
                    foregroundGravity = Gravity.CENTER
                }
            }
            setOnClickListener {
                when (slot) {
                    "collapse" -> { onToggleCollapse(); text = if (imagesCollapsed) "⊞" else "⊟" }
                    "back" -> if (::session.isInitialized && canGoBack) session.goBack()
                    "refresh" -> if (::session.isInitialized) { session.reload(); afterNav() }
                    "rendering" -> startActivity(Intent(this@MainActivity, RenderingActivity::class.java))
                    "supernote" -> startActivity(Intent(this@MainActivity, SupernoteActivity::class.java))
                    "extensions" -> startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                    "zap" -> onArmZap()
                    "undozap" -> postToExtension("undoZap")
                    "resetzap" -> postToExtension("resetZaps")
                    else -> toggleChrome()
                }
            }
        }
    }

    /**
     * Native strip tap → scroll the page directly via GeckoView's PanZoomController,
     * then drive the EPD refresh. No JS round-trip: the old path went
     * native → einkPort(navFlip) → background.js → content.js scroll →
     * background.js(flip) → native refresh — two WebExtension IPC hops per turn.
     * Now we scroll natively (instant, no fling — [SCROLL_BEHAVIOR_AUTO]) by a
     * viewport fraction and let [EinkScrollDelegate] refresh on the settled offset.
     *
     * scrollBy clamps silently at the content edges, so an over-scroll at top/bottom
     * is a no-op (which fires no onScrollChanged) — hence we also arm the refresh
     * backstop here so a page turn always produces at least one EPD refresh.
     */
    private fun flipPage(dir: String) {
        if (!::session.isInitialized) return
        val frac = if (dir == "prev") -PAGE_SCROLL_FRACTION else PAGE_SCROLL_FRACTION
        try {
            session.panZoomController.scrollBy(
                ScreenLength.zero(),
                ScreenLength.fromVisualViewportHeight(frac),
                PanZoomController.SCROLL_BEHAVIOR_AUTO,
            )
            armScrollRefresh()
        } catch (e: Throwable) {
            Log.w(TAG, "flipPage scrollBy threw ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * Debounce a single EPD refresh onto the view: any pending refresh is cancelled
     * and re-armed [SCROLL_SETTLE_MS] out, so the burst of onScrollChanged events one
     * flip emits — plus the flipPage backstop — coalesce into ONE refresh on the
     * settled frame. Safe to call from any thread that reaches the view.
     */
    private fun armScrollRefresh() {
        if (!::view.isInitialized) return
        view.removeCallbacks(scrollRefreshRunnable)
        view.postDelayed(scrollRefreshRunnable, SCROLL_SETTLE_MS)
    }

    /** Drives the Supernote EPD refresh once a scroll has settled (see [Epd]). */
    private val scrollRefreshRunnable = Runnable {
        if (!::view.isInitialized) return@Runnable
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

    // --- Minimal navigation chrome ------------------------------------------

    /**
     * The thin resting-state strip: ~1/3 the chrome height, a bordered pill showing the
     * current domain (bold) and page title (grey), single-line and ellipsized. Tapping it
     * reveals the full chrome/address bar (honouring the auto-focus setting), so it doubles
     * as a summon affordance. Legibility on e-ink wins over shaving the last px of height.
     */
    private fun buildDomainBar(): TextView {
        // Layer 0: the pill (fill + border). Layer 1: a clip-drawable fill advanced by
        // level (0..10000) that reveals a light-grey band left→right — the progress bar.
        // The fill is inset by the stroke so it sits inside the border, and stays light
        // enough that the black domain text remains legible over it.
        val base = GradientDrawable().apply {
            setColor(0xFFFAFAFA.toInt())
            setStroke(dp(1), 0xFF999999.toInt())
            cornerRadius = dp(8).toFloat()
        }
        domainFill = ClipDrawable(
            GradientDrawable().apply { setColor(0xFFD2D2D2.toInt()); cornerRadius = dp(7).toFloat() },
            Gravity.START, ClipDrawable.HORIZONTAL,
        ).apply { level = 0 }
        val bg = LayerDrawable(arrayOf(base, domainFill)).apply { setLayerInset(1, dp(1), dp(1), dp(1), dp(1)) }
        return TextView(this).apply {
            setTextColor(CHROME_INK)
            textSize = 12f
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(2), dp(12), dp(2))
            background = bg
            visibility = View.GONE
            setOnClickListener {
                val focus = when (domainFocusMode) {
                    "always" -> true
                    "never" -> false
                    else -> autoFocusOnReveal
                }
                showChrome(focus)
            }
        }
    }

    /** ⟳ (while loading) + domain (bold) + title (grey), collapsing to just the domain
     *  when the title is empty or identical to the host. */
    private fun domainBarText(): CharSequence {
        val host = hostOf(currentUrl).removePrefix("www.").ifBlank { currentUrl.orEmpty() }
        val title = currentTitle?.trim().orEmpty()
        val prefix = if (domainLoading) "⟳ " else ""
        val hostEnd = prefix.length + host.length
        val body = if (title.isEmpty() || title.equals(host, ignoreCase = true)) {
            "$prefix$host"
        } else {
            "$prefix$host   $title"
        }
        return SpannableString(body).apply {
            setSpan(StyleSpan(Typeface.BOLD), prefix.length, hostEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (body.length > hostEnd) setSpan(
                ForegroundColorSpan(0xFF777777.toInt()),
                hostEnd, body.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }

    /** Paint the fill at max(creep, real). Level is 0..10000; ~5 discrete steps in practice
     *  so the EPD only refreshes a handful of times per load. */
    private fun renderDomainProgress() {
        if (!::domainFill.isInitialized) return
        val pct = maxOf(domainCreepPct, domainRealPct).coerceIn(0, 100)
        domainFill.level = pct * 100
        domainBar.invalidate()
    }

    /** Indeterminate creep: bump the fill one step every beat up to a ceiling (never
     *  reaching 100 on its own — the page stop does that), so there's always visible
     *  motion even when Gecko stays silent. Stops as soon as the load ends. */
    private val domainCreep = object : Runnable {
        override fun run() {
            if (!domainLoading) return
            if (domainCreepPct < 80) { domainCreepPct += 20; renderDomainProgress() }
            if (domainCreepPct < 80) ui.postDelayed(this, 400)
        }
    }

    /** Clears the fill after the brief full-bar hold on page stop. */
    private val domainClear = Runnable {
        domainCreepPct = 0; domainRealPct = 0; renderDomainProgress()
    }

    /** Flip the loading state: set/clear the ⟳ prefix, start/stop the creep, refresh. */
    private fun setDomainLoading(loading: Boolean) {
        domainLoading = loading
        ui.removeCallbacks(domainCreep)
        ui.removeCallbacks(domainClear)
        if (loading) {
            domainCreepPct = 0
            domainRealPct = 0
            renderDomainProgress()     // reset the fill
            updateDomainBar()          // show ⟳
            ui.postDelayed(domainCreep, 250)
        } else {
            // Snap the fill to full and hold it a beat so the completed bar actually
            // registers on the EPD, then clear — otherwise it flashes 100% and vanishes.
            domainCreepPct = 100
            domainRealPct = 100
            renderDomainProgress()     // paint the full bar
            updateDomainBar()          // drop the ⟳
            ui.postDelayed(domainClear, 550)
        }
    }

    /** Show the domain strip only on a real page while the full chrome is hidden; refresh
     *  its text and re-apply the web-view inset whenever that state changes. */
    private fun updateDomainBar() {
        if (!::domainBar.isInitialized) return
        val chromeUp = ::chromeBar.isInitialized && chromeBar.visibility == View.VISIBLE
        val show = !isBlankUrl(currentUrl) && !chromeUp
        if (show) domainBar.text = domainBarText()
        domainBar.visibility = if (show) View.VISIBLE else View.GONE
        domainBar.post { updateEdgeInset() }
    }

    private fun buildChromeBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // Floating pane, not an edge bar: a defined 2px border + rounded corners so
            // that when it's lifted above the IME (leaving a gap below) it reads as a
            // deliberate floating control, not a bar torn off the screen edge. No
            // elevation — on e-ink a drop shadow renders as a grey halo (same rule as
            // the settings panel; flat surfaces only).
            background = GradientDrawable().apply {
                setColor(0xFFFAFAFA.toInt())
                setStroke(dp(2), 0xFF555555.toInt())
                cornerRadius = dp(10).toFloat()
            }
            setPadding(dp(8), dp(6), dp(8), dp(6))
            visibility = View.GONE
            // Any touch within the chrome resets the (generous) idle timer, unless
            // the address field is focused (then it's pinned open).
            setOnTouchListener { _, _ -> if (!urlField.hasFocus()) scheduleAutoHide(); false }
        }
        urlField = EditText(this).apply {
            setTextColor(CHROME_INK)
            setHintTextColor(0xFF777777.toInt())
            hint = "Search or enter address"
            setSingleLine()
            setSelectAllOnFocus(true)
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            // History-backed autocomplete. The framework AutoCompleteTextView dropdown is
            // unusable here: with adjustNothing the window never shrinks for the IME, so it
            // never flips above the field and drops straight under the keyboard. Instead we
            // drive a custom full-width suggestion panel that lives inside the chrome (see
            // suggestBox) and rides the same IME lift as the bar. Each keystroke refreshes it.
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) {
                    if (hasFocus()) updateSuggestions(s?.toString() ?: "")
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
            setOnEditorActionListener { _, actionId, event ->
                // The Supernote keyboard reports its enter key inconsistently — sometimes
                // GO, sometimes DONE/SEARCH/UNSPECIFIED, sometimes just a raw ENTER key
                // event (actionId == IME_ACTION_NONE). Accept any of them as "submit".
                val isSubmit = actionId == EditorInfo.IME_ACTION_GO ||
                    actionId == EditorInfo.IME_ACTION_SEARCH ||
                    actionId == EditorInfo.IME_ACTION_DONE ||
                    actionId == EditorInfo.IME_ACTION_NEXT ||
                    (event != null && event.keyCode == android.view.KeyEvent.KEYCODE_ENTER &&
                        event.action == android.view.KeyEvent.ACTION_DOWN)
                if (isSubmit) { submitUrl(text.toString()); true } else false
            }
            // Some Supernote input modes deliver ENTER as a raw key event to the view
            // rather than firing onEditorAction — catch that path too so submit is
            // reliable regardless of which on-screen keyboard/handwriting mode is active.
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == android.view.KeyEvent.KEYCODE_ENTER &&
                    event.action == android.view.KeyEvent.ACTION_UP) {
                    submitUrl(text.toString()); true
                } else false
            }
            // Focus pins the chrome open indefinitely so the user can type; losing
            // focus restarts the generous idle timer. On focus we also lift the whole
            // bar to the TOP edge: the Supernote's on-screen input / handwriting panel
            // is a bottom overlay that doesn't resize the window, so a bottom-anchored
            // address bar (and its suggestions) gets covered. Top-edge entry is clear
            // of it. Restored to the configured edge on blur.
            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    // Own the IME ourselves while typing an address: ADJUST_NOTHING so the
                    // system doesn't resize under us — the manual lift positions the bar.
                    setSoftInput(adjustNothing = true)
                    ui.removeCallbacks(autoHide)
                    goBtn?.visibility = View.VISIBLE // reveal the → submit affordance
                    // Explicitly show + bind the IME to this field: adjustNothing
                    // suppresses the auto-show, otherwise the served view stays on the
                    // DecorView and typing never reaches us. Once the IME is up, the
                    // window-insets listener lifts the (bottom) bar by the LIVE ime()
                    // inset so it sits just above the panel — and grows with it when the
                    // ink-writer's candidate/autocomplete strip appears (lift-in-place).
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    ui.post { imm?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT) }
                    // The candidate/autocomplete strip appears/grows AFTER show without
                    // firing an inset event, so poll the true IME height while focused.
                    ui.removeCallbacks(imePoll); ui.post(imePoll)
                } else {
                    // Back to ADJUST_RESIZE so web-page fields get scrolled above the IME.
                    setSoftInput(adjustNothing = false)
                    ui.removeCallbacks(imePoll)
                    goBtn?.visibility = View.GONE
                    hideSuggestions()
                    liftChromeForIme(0)
                    setEdgeNavHidden(false) // restore the edge strips/slivers the IME hid
                    scheduleAutoHide()
                }
            }
        }
        val gearBtn = Button(this).apply {
            text = "⚙"
            setTextColor(CHROME_INK)
            setBackgroundColor(Color.TRANSPARENT)
            textSize = 20f
            setOnClickListener {
                val show = settingsPanel.visibility != View.VISIBLE
                // Opening the quick panel with the address bar auto-focused (IME up)
                // must dismiss the IME first — otherwise the keyboard overlay covers
                // the panel. Clearing focus also fires urlField's blur branch, which
                // restores the edge nav and drops the chrome lift.
                if (show) dismissAddressIme()
                settingsPanel.visibility = if (show) View.VISIBLE else View.GONE
            }
        }
        // A contextual → submit button, hidden until the field is focused (see the
        // focus listener). Sits right after the url field, before the config slots.
        goBtn = Button(this).apply {
            text = "→"
            setTextColor(CHROME_INK)
            setBackgroundColor(Color.TRANSPARENT)
            textSize = 22f
            visibility = View.GONE
            setOnClickListener { if (::urlField.isInitialized) submitUrl(urlField.text.toString()) }
        }
        // [left] url [→] [right1][right2] ⚙ — configurable slots flank the fixed url + gear.
        makeChromeButton(chromeBtnLeft)?.let { bar.addView(it) }
        bar.addView(urlField, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(goBtn)
        makeChromeButton(chromeBtnRight1)?.let { bar.addView(it) }
        makeChromeButton(chromeBtnRight2)?.let { bar.addView(it) }
        bar.addView(gearBtn)
        bar.visibility = View.VISIBLE // the wrapper (chromeBar) now owns chrome show/hide

        // Full-width history suggestion panel — its own floating pill, hidden until the
        // field has matches. Same 2px-border / rounded / flat look as the bar.
        suggestBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(0xFFFAFAFA.toInt())
                setStroke(dp(2), 0xFF555555.toInt())
                cornerRadius = dp(10).toFloat()
            }
            visibility = View.GONE
        }

        // Wrapper that IS the chrome (show/hide/lift/tap-dismiss all target chromeBar).
        // Suggestions sit ABOVE the bar on bottom chrome so they rise away from the IME,
        // BELOW it on top chrome (where the keyboard never reaches).
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val barLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        val sugLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        if (chromeAtBottom) {
            sugLp.bottomMargin = dp(6)
            col.addView(suggestBox, sugLp)
            col.addView(bar, barLp)
        } else {
            sugLp.topMargin = dp(6)
            col.addView(bar, barLp)
            col.addView(suggestBox, sugLp)
        }
        return col
    }

    /** Refresh the history suggestion panel for the current address-field query [q]. */
    private fun updateSuggestions(q: String) {
        if (!::suggestBox.isInitialized || !::prefs.isInitialized) return
        val results = if (q.isBlank()) emptyList()
            else HistoryStore.query(prefs, q, limit = SUGGEST_LIMIT)
        suggestBox.removeAllViews()
        if (results.isEmpty()) {
            suggestBox.visibility = View.GONE
            updateEdgeInset()
            return
        }
        results.forEachIndexed { i, e ->
            if (i > 0) suggestBox.addView(View(this).apply {
                setBackgroundColor(0x22000000)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            })
            suggestBox.addView(buildSuggestRow(e))
        }
        suggestBox.visibility = View.VISIBLE
        updateEdgeInset()
    }

    private fun hideSuggestions() {
        if (!::suggestBox.isInitialized) return
        suggestBox.removeAllViews()
        suggestBox.visibility = View.GONE
    }

    /** One tappable suggestion row: bold title over the grey URL. Tap loads the URL. */
    private fun buildSuggestRow(e: HistoryStore.Entry): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        isClickable = true
        setPadding(dp(12), dp(8), dp(12), dp(8))
        addView(TextView(this@MainActivity).apply {
            text = if (e.title.isNotEmpty()) e.title else e.url
            setTextColor(CHROME_INK)
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        addView(TextView(this@MainActivity).apply {
            text = e.url
            setTextColor(CHROME_INK)
            alpha = 0.55f
            textSize = 12f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        })
        setOnClickListener { submitUrl(e.url) }
    }

    /**
     * Build one configurable chrome-bar button for [fn] ∈ {back, refresh, collapse},
     * or null for "none". "back" and "collapse" also record their field ([backBtn],
     * [collapseBtn]) so the existing state hooks (canGoBack enable/hide, collapse-glyph
     * flip) keep driving them wherever the slot lands.
     */
    private fun makeChromeButton(fn: String): Button? = when (fn) {
        "back" -> Button(this).apply {
            text = NavActions.glyph("back") // shared catalog glyph (← everywhere)
            setTextColor(CHROME_INK)
            setBackgroundColor(Color.TRANSPARENT) // no dark button fill; glyph on light bar
            textSize = 22f
            isEnabled = false
            setOnClickListener { if (::session.isInitialized && canGoBack) { session.goBack(); afterNav() } }
        }.also { backBtn = it }
        "refresh" -> Button(this).apply {
            text = NavActions.glyph("refresh")
            setTextColor(CHROME_INK)
            setBackgroundColor(Color.TRANSPARENT)
            textSize = 20f
            setOnClickListener { if (::session.isInitialized) { session.reload(); afterNav() } }
        }
        // Page-level collapse/expand-all: flip every placeholder on THIS page between
        // tiny chips and full boxes, without changing the site's saved policy.
        "collapse" -> Button(this).apply {
            text = if (imagesCollapsed) "⊞" else "⊟"
            setTextColor(CHROME_INK)
            setBackgroundColor(Color.TRANSPARENT)
            textSize = 20f
            setOnClickListener { onToggleCollapse() }
        }.also { collapseBtn = it }
        // Settings-page launchers. Rendering is a ⌗ glyph; Supernote / Extensions carry
        // their drawn B&W icons (same as the layout-editor top bar), tinted to chrome ink.
        "rendering" -> Button(this).apply {
            text = "⌗"
            setTextColor(CHROME_INK)
            setBackgroundColor(Color.TRANSPARENT)
            textSize = 20f
            setOnClickListener { startActivity(Intent(this@MainActivity, RenderingActivity::class.java)) }
        }
        "supernote" -> iconChromeButton(R.drawable.ic_supernote) {
            startActivity(Intent(this@MainActivity, SupernoteActivity::class.java))
        }
        "extensions" -> iconChromeButton(R.drawable.ic_puzzle) {
            startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
        }
        "zap", "undozap", "resetzap" -> Button(this).apply {
            text = NavActions.glyph(fn)
            setTextColor(CHROME_INK)
            setBackgroundColor(Color.TRANSPARENT)
            textSize = 20f
            setOnClickListener {
                when (fn) {
                    "zap" -> onArmZap()
                    "undozap" -> postToExtension("undoZap")
                    else -> postToExtension("resetZaps")
                }
            }
        }
        else -> null
    }

    /** A chrome-bar slot whose mark is a drawn vector icon rather than a font glyph. */
    private fun iconChromeButton(iconRes: Int, onClick: () -> Unit): Button = Button(this).apply {
        text = ""
        setBackgroundColor(Color.TRANSPARENT)
        foreground = ContextCompat.getDrawable(this@MainActivity, iconRes)
            ?.mutate()?.apply { setTint(CHROME_INK) }
        foregroundGravity = Gravity.CENTER
        setOnClickListener { onClick() }
    }

    private fun toggleChrome() {
        // An explicit sliver tap is the only reveal path (a tap off the chrome only hides),
        // so honour the auto-focus setting here — a tap to open the toolbar is almost always
        // a tap to type an address.
        if (chromeBar.visibility == View.VISIBLE) hideChrome() else showChrome(autoFocusOnReveal)
    }

    private fun showChrome(autoFocus: Boolean = false) {
        hideSuggestions() // never flash the previous query's matches on reopen
        chromeBar.visibility = View.VISIBLE
        // The full address bar supersedes the thin domain strip while chrome is up.
        if (::domainBar.isInitialized) domainBar.visibility = View.GONE
        currentUrl?.let { urlField.setText(it) }
        // Shrink the web viewport by the chrome height so the bar doesn't cover the
        // bottom (or top) of the page — otherwise a position:fixed banner (GDPR /
        // cookie consent) sits under the bar and its buttons can't be tapped.
        chromeBar.post { updateEdgeInset() }
        scheduleAutoHide()
        if (autoFocus) {
            // requestFocus fires the field's OnFocusChangeListener, which shows the IME,
            // starts the height poll, and cancels the auto-hide. selectAll so the first
            // keystroke replaces the current URL instead of appending to it.
            urlField.post { urlField.requestFocus(); urlField.selectAll() }
        }
    }

    /**
     * Lift the (bottom-edge) chrome bar by [imeBottomPx] (the live ime() inset) so it
     * sits just above the on-screen input window, PLUS a fixed reserve for the Supernote
     * handwriting engine's candidate/autocomplete strip. That strip lives inside the IME
     * window but ABOVE the reported ime() inset — the IME under-reports its height by
     * ~175px (measured via dumpsys: window frame top 1570 vs ime inset top 1745), so the
     * strip would otherwise cover the lifted bar. The reserve adds a small gap above the
     * plain keyboard (where there is no strip) but keeps the bar clear in handwriting
     * mode. No-op when chrome is top-anchored (IME can't cover it there).
     */
    /**
     * Fallback reserve (px) for the handwriting candidate strip, used ONLY when the
     * hidden true-height API is unavailable. ~175px measured; dp(64)≈192px for headroom.
     */
    private val IME_CANDIDATE_RESERVE get() = dp(64)

    // Cached reflected InputMethodManager.getInputMethodWindowVisibleHeight() (greylisted).
    private var imeHeightMethod: java.lang.reflect.Method? = null
    private var imeHeightResolved = false
    // Last ime() inset the window dispatched (fallback signal; under-reports the strip).
    private var imeInsetLast = 0
    // Bottom inset (px) we apply to the GeckoView to lift a focused WEB-PAGE field above the
    // IME (the Supernote won't resize the window for us). 0 unless a web field has the IME up.
    private var webImeInsetPx = 0

    /**
     * True visible height (px) of the on-screen input window INCLUDING the Supernote
     * handwriting candidate/autocomplete strip, via the hidden
     * InputMethodManager.getInputMethodWindowVisibleHeight(). The public ime() inset
     * under-reports this by the strip height (window frame vs inset: ~990 vs 815 px on
     * Manta). Greylisted but reachable because hidden_api_policy=0 on Supernote — the
     * same unlock the EPD reflection needs (see eink/RattaEink). Returns -1 when the
     * method is missing so callers fall back to the ime() inset + reserve.
     */
    private fun imeWindowVisibleHeight(): Int {
        if (!imeHeightResolved) {
            imeHeightResolved = true
            imeHeightMethod = try {
                InputMethodManager::class.java.getMethod("getInputMethodWindowVisibleHeight")
            } catch (e: Throwable) {
                Log.w(TAG, "[eink-ime] getInputMethodWindowVisibleHeight unavailable: $e"); null
            }
        }
        val m = imeHeightMethod ?: return -1
        return try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            (m.invoke(imm) as? Int) ?: -1
        } catch (e: Throwable) { -1 }
    }

    /**
     * Poll the true IME height while the address field is focused: the candidate strip
     * appears/grows after the keyboard shows WITHOUT firing an inset event, so a single
     * inset-driven read would miss it. Cheap — liftChromeForIme no-ops when unchanged.
     */
    private val imePoll = object : Runnable {
        override fun run() {
            applyImeLift()
            if (::urlField.isInitialized && urlField.hasFocus()) ui.postDelayed(this, 200)
        }
    }

    /**
     * Choose the lift: prefer the hidden true-height API (covers the candidate strip
     * exactly); fall back to the ime() inset + a fixed reserve if reflection is missing.
     */
    private fun applyImeLift() {
        val focused = ::urlField.isInitialized && urlField.hasFocus()
        // Hide the edge paging strips + slivers while the address field is focused:
        // the chevrons/slivers hovering over the keyboard read as out of place. Restored
        // when focus clears (dismiss / hideChrome), which is also when the IME goes away.
        setEdgeNavHidden(focused)
        if (!focused) {
            // Not our address bar → the IME (if up) belongs to a focused WEB-PAGE field.
            // Shrink the GeckoView by the inset so Gecko lifts that field above the keyboard.
            liftChromeForIme(0)
            val webInset = imeInsetLast
            if (webImeInsetPx != webInset) { webImeInsetPx = webInset; updateEdgeInset() }
            return
        }
        // Our address bar owns the IME here: no web-field inset, use the custom lift instead.
        if (webImeInsetPx != 0) { webImeInsetPx = 0; updateEdgeInset() }
        val trueH = imeWindowVisibleHeight()
        val lift = when {
            trueH > 0 -> trueH
            imeInsetLast > 0 -> imeInsetLast + IME_CANDIDATE_RESERVE
            else -> 0
        }
        liftChromeForIme(lift)
    }

    /** Hide (GONE) or restore the edge paging strips and their sliver buttons. Used to
     *  clear them out of the way while the IME is up. Idempotent. */
    private fun setEdgeNavHidden(hidden: Boolean) {
        val vis = if (hidden) View.GONE else View.VISIBLE
        leftStrip?.let { if (it.visibility != vis) it.visibility = vis }
        rightStrip?.let { if (it.visibility != vis) it.visibility = vis }
        for (b in sliverButtons) if (b.visibility != vis) b.visibility = vis
    }

    /** Small resting gap (px) between the pane and its anchored screen edge so its
     *  border/rounded corners aren't clipped. The IME-lift overrides this. */
    private val CHROME_EDGE_GAP get() = dp(6)

    /** Lifts the bottom-edge chrome pane by [px] above the IME; when [px] is 0 (docked)
     *  it rests at CHROME_EDGE_GAP so its bottom border stays visible. No-op when chrome
     *  is top-anchored (IME can't cover it there). */
    private fun liftChromeForIme(px: Int) {
        if (!chromeAtBottom || !::chromeBar.isInitialized) return
        val lp = chromeBar.layoutParams as? FrameLayout.LayoutParams ?: return
        val margin = if (px > 0) px else CHROME_EDGE_GAP
        if (lp.bottomMargin == margin) return
        lp.bottomMargin = margin
        chromeBar.layoutParams = lp
    }

    private fun hideChrome() {
        ui.removeCallbacks(autoHide)
        liftChromeForIme(0) // drop any IME lift so the next reveal sits at the edge
        chromeBar.visibility = View.GONE
        settingsPanel.visibility = View.GONE
        // Restore the thin domain strip (and its inset) now that the full chrome is down.
        updateDomainBar()
        dismissAddressIme()
    }

    /**
     * Dismiss the address-bar IME: clear the field's focus and hide the soft
     * input. Clearing focus fires urlField's blur branch (restore edge nav, drop
     * the IME lift). Safe to call when the field isn't focused / the IME is down.
     */
    /**
     * Switch the window's soft-input mode at runtime: ADJUST_NOTHING while our own address
     * field is focused (the custom lift owns positioning), ADJUST_RESIZE otherwise so a
     * focused WEB-PAGE field triggers a window resize and GeckoView scrolls it above the IME.
     */
    private fun setSoftInput(adjustNothing: Boolean) {
        window.setSoftInputMode(
            if (adjustNothing) WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            else WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
        )
    }

    private fun dismissAddressIme() {
        if (!::urlField.isInitialized) return
        urlField.clearFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(urlField.windowToken, 0)
    }

    /**
     * Inset the GeckoView on the chrome edge by whichever edge bar is currently up: the
     * full chrome bar when it's visible, else the thin domain strip, else flush. Preserves
     * the inset-mode left/right paging margins. The reflow this triggers is intentional —
     * it lifts fixed bottom/top page furniture (consent banners) out from under the bar.
     */
    private fun updateEdgeInset() {
        val lp = view.layoutParams as? FrameLayout.LayoutParams ?: return
        val inset = when {
            ::chromeBar.isInitialized && chromeBar.visibility == View.VISIBLE ->
                chromeBar.height.coerceAtLeast(dp(52))
            ::domainBar.isInitialized && domainBar.visibility == View.VISIBLE ->
                domainBar.height.coerceAtLeast(dp(20)) + CHROME_EDGE_GAP
            else -> 0
        }
        val newTop = if (chromeAtBottom) 0 else inset
        // The Supernote reports an IME inset but never resizes the window, so ADJUST_RESIZE
        // is a no-op there and a focused WEB-PAGE field stays under the keyboard. When one
        // is being edited we shrink the GeckoView from the bottom by that inset ourselves —
        // Gecko then scrolls the focused element into the smaller viewport. (webImeInsetPx
        // is 0 unless a web field, not our address bar, has the IME up.)
        val newBottom = maxOf(if (chromeAtBottom) inset else 0, webImeInsetPx)
        if (lp.topMargin == newTop && lp.bottomMargin == newBottom) return
        lp.topMargin = newTop
        lp.bottomMargin = newBottom
        view.layoutParams = lp
    }

    /**
     * Idle auto-hide is DISABLED: chrome stays up until it's explicitly dismissed
     * (the ☰ sliver toggle, or a tap off the chrome — see root.onInterceptTouchEvent).
     * Kept as a no-op that only clears any stale callback, so the many existing call
     * sites don't need to change and a legacy queued [autoHide] can never fire.
     */
    private fun scheduleAutoHide() {
        ui.removeCallbacks(autoHide)
    }

    /** True if [ev] (screen coords) falls within [v]'s on-screen bounds. */
    private fun pointInView(v: View, ev: MotionEvent): Boolean {
        val r = Rect()
        v.getGlobalVisibleRect(r)
        return r.contains(ev.rawX.toInt(), ev.rawY.toInt())
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
            val template = if (searchEngine == "Custom" && customSearchUrl.contains("%s")) customSearchUrl
                else SEARCH_ENGINES[searchEngine] ?: SEARCH_ENGINES["DuckDuckGo"]!!
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
        preferLight = prefs.getBoolean("preferLight", true)
        forceLight = prefs.getBoolean("forceLight", true)
        fullEvery = prefs.getInt("fullEvery", 6)
        loginHosts = HashSet(prefs.getStringSet("loginHosts", emptySet()) ?: emptySet())
        rawHosts = HashSet(prefs.getStringSet("rawHosts", emptySet()) ?: emptySet())
        navStyle = prefs.getString("navStyle", "inset") ?: "inset"
        navPlacement = prefs.getString("navPlacement", "both") ?: "both"
        showZones = prefs.getBoolean("showZones", true)
        chromePos = prefs.getString("chromePos", "auto") ?: "auto"
        naturalScroll = prefs.getBoolean("naturalScroll", true)
        searchEngine = prefs.getString("searchEngine", "DuckDuckGo") ?: "DuckDuckGo"
        customSearchUrl = prefs.getString("customSearchUrl", "") ?: ""
        collapseMode = prefs.getString("collapseMode", "auto") ?: "auto"
        collapseThreshold = prefs.getInt("collapseThreshold", 6)
        chromeBtnLeft = prefs.getString("chromeBtnLeft", "back") ?: "back"
        chromeBtnRight1 = prefs.getString("chromeBtnRight1", "refresh") ?: "refresh"
        chromeBtnRight2 = prefs.getString("chromeBtnRight2", "collapse") ?: "collapse"
        edgeSlotLeftTop = prefs.getString("edgeSlotLeftTop", "none") ?: "none"
        edgeSlotLeftBottom = prefs.getString("edgeSlotLeftBottom", "chrome") ?: "chrome"
        edgeSlotRightTop = prefs.getString("edgeSlotRightTop", "none") ?: "none"
        edgeSlotRightBottom = prefs.getString("edgeSlotRightBottom", "collapse") ?: "collapse"
        autoFocusOnReveal = prefs.getBoolean("autoFocusOnReveal", false)
        domainFocusMode = prefs.getString("domainFocusMode", "follow") ?: "follow"
        // Lockout guard: with tap-to-dismiss and the centre reveal-strip gone, a chrome
        // sliver is the ONLY way to open the toolbar (and thus settings). "left"/"right"
        // (single strip) and "none" (floating button) always guarantee a chrome
        // affordance by construction (see addStrips/addFloatingChromeButton) — only
        // "both" needs a guard, forcing a bottom slot to chrome if neither side has one
        // anywhere.
        if (navPlacement == "both") {
            val chromeReachable = edgeSlotLeftTop == "chrome" || edgeSlotLeftBottom == "chrome" ||
                edgeSlotRightTop == "chrome" || edgeSlotRightBottom == "chrome"
            if (!chromeReachable) edgeSlotLeftBottom = "chrome"
        }
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

    /** Lowercased host of a URL, or "" if none/unparseable. */
    private fun hostOf(url: String?): String =
        try { java.net.URI(url).host?.lowercase() ?: "" } catch (e: Throwable) { "" }

    /** True if [host] is in the per-site "render as-is" set. */
    private fun isRawHost(host: String?): Boolean {
        val h = host?.lowercase() ?: return false
        return h.isNotEmpty() && rawHosts.contains(h)
    }

    /**
     * Apply the engine-level levers for the host about to load. When the host is in the
     * "render as-is" set, EVERYTHING is relaxed to native rendering: tracking protection
     * off, JavaScript on, no forced colour scheme — the user's global prefs are ignored
     * for this host only. Otherwise the usual policy: Standard ETP for login/auth hosts
     * (Strict can break sign-in), else the user's settings. Called per-navigation.
     */
    private fun applyPerHostEngine(host: String?) {
        val raw = isRawHost(host)
        applyTrackingProtection(strict = !raw && strictTp && !isLoginHost(host))
        if (::session.isInitialized) session.settings.setAllowJavascript(if (raw) true else jsEnabled)
        applyColorScheme(preferLight = if (raw) false else preferLight)
    }

    /** Sets ETP just before each top-level navigation (earliest per-nav hook). */
    private val EtpNavigationDelegate = object : GeckoSession.NavigationDelegate {
        override fun onLoadRequest(
            s: GeckoSession,
            request: GeckoSession.NavigationDelegate.LoadRequest,
        ): GeckoResult<org.mozilla.geckoview.AllowOrDeny>? {
            if (request.target == GeckoSession.NavigationDelegate.TARGET_WINDOW_CURRENT) {
                val host = hostOf(request.uri)
                applyPerHostEngine(host)
                // Push effective content-script levers for the target host BEFORE its
                // subresources fetch, so a raw host's fonts/animations/force-light are
                // already relaxed at the network + document_start layer (not one reload late).
                pushSettingsToExtension(host)
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
            // The title arrives via onTitleChange; clear the stale one so the domain strip
            // doesn't briefly show the previous page's title against the new host.
            currentTitle = null
            // Reveal the Chloe home only in the blank state; hide it once a real page loads.
            setHomeVisible(isBlankUrl(url))
            // Refresh the quick-panel "Render as-is" label + the thin domain strip.
            runOnUiThread { updateRawBtn(); updateDomainBar() }
            // Publish the current host so the Accessibility page can offer a per-site
            // "render as-is" toggle for it (that page has no browsing context of its own).
            if (::prefs.isInitialized) prefs.edit().putString("currentHost", hostOf(url)).apply()
            // Record the visit (the title lands later, via onTitleChange). Skip the blank
            // home / about:blank so the empty state doesn't pollute history.
            if (::prefs.isInitialized && !isBlankUrl(url)) url?.let {
                HistoryStore.record(prefs, it, System.currentTimeMillis())
            }
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

        // A failed load (DNS failure, timeout, offline, refused connection…) otherwise
        // leaves the last page — or a blank about:blank — on screen with no feedback,
        // which on e-ink (no spinner, no chrome by default) looks like nothing happened.
        // Render a plain, high-contrast error page so the user knows the load failed and
        // why. Returning a data: URI tells Gecko to display it in place of the error.
        override fun onLoadError(
            s: GeckoSession,
            uri: String?,
            error: org.mozilla.geckoview.WebRequestError,
        ): GeckoResult<String>? = GeckoResult.fromValue(buildErrorPage(uri, error))
    }

    /**
     * Tracks the page title for the thin domain strip. The title lands after the location
     * change (and can update again during load), so we refresh the strip on each callback.
     */
    private val TitleContentDelegate = object : GeckoSession.ContentDelegate {
        override fun onTitleChange(s: GeckoSession, title: String?) {
            currentTitle = title
            // Backfill the history entry for the current page now that its title is known.
            if (::prefs.isInitialized && title != null) currentUrl?.let { u ->
                if (!isBlankUrl(u)) HistoryStore.updateTitle(prefs, u, title)
            }
            runOnUiThread { updateDomainBar() }
        }
    }

    /**
     * Deny-by-default content permissions. A minimal reader browser has no UI to make
     * an informed geo/cam/mic/notification grant, so silently denying is the safe stance
     * (the site sees a normal "permission denied", same as a user hitting Block). Without
     * a delegate set, Gecko applies its own default handling — we make the policy explicit
     * so nothing can request-and-get camera/mic/location on an e-ink tablet.
     */
    private val DenyPermissionDelegate = object : GeckoSession.PermissionDelegate {
        override fun onContentPermissionRequest(
            s: GeckoSession,
            perm: GeckoSession.PermissionDelegate.ContentPermission,
        ): GeckoResult<Int> =
            GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)

        override fun onMediaPermissionRequest(
            s: GeckoSession,
            uri: String,
            video: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
            audio: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
            callback: GeckoSession.PermissionDelegate.MediaCallback,
        ) {
            callback.reject()
        }

        override fun onAndroidPermissionsRequest(
            s: GeckoSession,
            permissions: Array<out String>?,
            callback: GeckoSession.PermissionDelegate.Callback,
        ) {
            callback.reject()
        }
    }

    /** Maps a Gecko load error to a short, plain-language reason for the error page. */
    private fun errorReason(error: org.mozilla.geckoview.WebRequestError): String = when (error.code) {
        org.mozilla.geckoview.WebRequestError.ERROR_UNKNOWN_HOST ->
            "Couldn’t find that site. Check the address, or your Wi-Fi / DNS."
        org.mozilla.geckoview.WebRequestError.ERROR_NET_TIMEOUT ->
            "The site took too long to respond. Check your connection and try again."
        org.mozilla.geckoview.WebRequestError.ERROR_CONNECTION_REFUSED ->
            "The site refused the connection."
        org.mozilla.geckoview.WebRequestError.ERROR_NET_INTERRUPT ->
            "The connection was interrupted before the page finished loading."
        org.mozilla.geckoview.WebRequestError.ERROR_OFFLINE ->
            "You appear to be offline. Check your Wi-Fi."
        org.mozilla.geckoview.WebRequestError.ERROR_MALFORMED_URI ->
            "That address doesn’t look valid."
        org.mozilla.geckoview.WebRequestError.ERROR_UNKNOWN_PROTOCOL ->
            "That address uses a protocol this browser can’t open."
        else -> "The page couldn’t be loaded."
    }

    /** Builds a flat, grayscale error page (data: URI) tuned for e-ink readability. */
    private fun buildErrorPage(uri: String?, error: org.mozilla.geckoview.WebRequestError): String {
        val host = try { java.net.URI(uri ?: "").host ?: uri ?: "" } catch (e: Throwable) { uri ?: "" }
        val safeHost = host.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val reason = errorReason(error)
        val html = """
            <!doctype html><html><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <style>
              html,body{margin:0;background:#fff;color:#000;
                font-family:serif;-webkit-text-size-adjust:100%;}
              .wrap{max-width:38rem;margin:0 auto;padding:12vh 8vw;}
              h1{font-size:2rem;font-weight:700;margin:0 0 .6rem;}
              p{font-size:1.25rem;line-height:1.5;margin:0 0 1rem;}
              .host{font-family:monospace;font-size:1.1rem;word-break:break-all;
                border-top:2px solid #000;border-bottom:2px solid #000;padding:.6rem 0;}
              .hint{font-size:1.05rem;color:#000;}
            </style></head><body><div class="wrap">
              <h1>Page didn’t load</h1>
              <p>$reason</p>
              <p class="host">$safeHost</p>
              <p class="hint">Tap the address bar to try again, or reload with ⟳.</p>
            </div></body></html>
        """.trimIndent()
        val b64 = android.util.Base64.encodeToString(
            html.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP,
        )
        return "data:text/html;base64,$b64"
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
        // QUICK panel = the handful of per-page/per-site controls toggled often. Toolbar
        // position moved to the visual editor; Animations off moved to the Rendering page;
        // the zap actions are also in the button catalog now (assignable to any slot).
        //
        // Image policy cycle (per-domain; content script persists + reloads). NOTE: the
        // "Images: …" label is ambiguous and this control is due for a rethink — kept here
        // as-is for now.
        imgToggle = makeButton("Images: …") { onCycleImagePolicy() }.apply { isEnabled = false }
        panel.addView(imgToggle)

        // Per-site escape hatch: bypass ALL e-ink processing for this host and render the
        // page natively (fonts, JS, tracking protection, colour, image gating all off),
        // for a site the e-ink treatment breaks. Per-domain, remembered; global prefs
        // untouched. Label reflects the current host's state; disabled on the blank home.
        rawBtn = makeButton("Render as-is: —") { toggleRawForCurrentHost() }.apply { isEnabled = false }
        panel.addView(rawBtn)

        // Element zapper group — arm/undo/reset, fenced off with dividers so the
        // destructive-ish "hide" actions read as one cluster.
        panel.addView(panelDivider())
        // Zap: arm picker mode, hide chrome, next page tap hides that element (persisted
        // per-site). Kills inline JS players / furniture with no src for a rule to match.
        panel.addView(makeButton("⬡ Zap element (hide)") { onArmZap() })
        // Undo / reset — a mis-tapped zap otherwise hides part of a site permanently
        // with no recovery. Undo pops the last zap for this host; reset clears them all.
        panel.addView(makeButton("↺ Undo last zap") { postToExtension("undoZap") })
        panel.addView(makeButton("Reset zaps (this site)") { postToExtension("resetZaps") })
        panel.addView(panelDivider())

        // The visual layout editor is the main settings screen now; it has a "…" button
        // to the classic settings list for the remaining toggles.
        panel.addView(makeButton("More settings…") {
            settingsPanel.visibility = View.GONE
            startActivity(Intent(this, LayoutActivity::class.java))
        })
        return panel
    }

    /** A hairline divider used to fence off groups within the quick-settings panel. */
    private fun panelDivider(): View = View(this).apply {
        setBackgroundColor(0x33000000)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(6); bottomMargin = dp(6)
        }
    }

    private fun makeButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply { text = label; setTextColor(CHROME_INK); setBackgroundColor(Color.TRANSPARENT); setOnClickListener { onClick() } }

    /**
     * Engine-level preferred colour scheme. [preferLight] ON → pages that honour
     * `prefers-color-scheme` serve their LIGHT variant instead of a dark theme that
     * renders as muddy grey on the panel. This is the gentle, can't-break-sites
     * lever; the aggressive black-on-white guarantee is [forceLight] (content script).
     * It's a runtime-wide (not per-session) setting, so a reload picks it up.
     */
    private fun applyColorScheme(preferLight: Boolean) {
        try {
            sharedRuntime(this).settings.preferredColorScheme =
                if (preferLight) GeckoRuntimeSettings.COLOR_SCHEME_LIGHT
                else GeckoRuntimeSettings.COLOR_SCHEME_SYSTEM
        } catch (e: Throwable) {
            Log.w(TAG, "applyColorScheme threw ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun applyTrackingProtection(strict: Boolean) {
        try {
            val cb = sharedRuntime(this).settings.contentBlocking
            // Safe Browsing (malware/phishing/unwanted-software) is independent of the
            // anti-tracking ETP level. Likely default-on, but set it explicitly so a
            // future ETP refactor can't silently drop malware/phishing blocking.
            cb.setSafeBrowsing(ContentBlocking.SafeBrowsing.DEFAULT)
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

    /**
     * Push content-script-side levers (animations, font-block gate, force-light) to the
     * extension. Values are EFFECTIVE for [targetHost] (defaults to the current page):
     * a "render as-is" host gets every lever relaxed (fonts on, no anim-kill, no
     * force-light, no image collapse) plus a `raw` flag so the content script drops
     * image gating too — without disturbing the user's saved prefs.
     */
    private fun pushSettingsToExtension(targetHost: String = hostOf(currentUrl)) {
        val p = einkPort ?: return
        val raw = isRawHost(targetHost)
        try {
            p.postMessage(
                org.json.JSONObject()
                    .put("type", "settings")
                    .put("raw", raw)
                    .put("animOff", !raw && animOff)
                    .put("forceLight", !raw && forceLight)
                    .put("blockFonts", !raw && blockFonts)
                    .put("collapseMode", if (raw) "never" else collapseMode)
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
            runOnUiThread { setDomainLoading(true) }
        }

        override fun onProgressChange(s: GeckoSession, progress: Int) {
            // Feed Gecko's real value in as a floor; the creep timer supplies steady motion
            // between these coarse jumps. renderDomainProgress no-ops when the level is equal.
            runOnUiThread {
                if (progress > domainRealPct) { domainRealPct = progress; renderDomainProgress() }
            }
        }

        override fun onPageStop(s: GeckoSession, success: Boolean) {
            runOnUiThread { setDomainLoading(false) }
            if (pageStartMs <= 0L) return
            val ms = SystemClock.elapsedRealtime() - pageStartMs
            pageStartMs = 0L
            Log.i(
                TAG,
                "[eink-perf] page=$pageHost loadMs=$ms" +
                    " js=${if (jsEnabled) "on" else "off"}" +
                    " fonts=${if (blockFonts) "blocked" else "on"}" +
                    " tp=${if (strictTp) "strict" else "off"}" +
                    " anim=${if (animOff) "off" else "on"}" +
                    " light=${if (preferLight) "pref" else "sys"}" +
                    " contrast=${if (forceLight) "forced" else "off"}" +
                    " raw=${isRawHost(pageHost)}",
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
                    // Page-flip refresh is now driven natively off the scroll offset
                    // (EinkScrollDelegate) — no "flip" message from the extension.
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

    /**
     * Toggle "render as-is" for the current page's host (quick-panel button). Native's
     * rawHosts set is the source of truth for the engine levers; we also tell the content
     * script to persist its own per-host mirror and reload — that reload re-fires
     * onLoadRequest, which applies the (now updated) engine levers. No-op on the blank home.
     */
    private fun toggleRawForCurrentHost() {
        val host = hostOf(currentUrl)
        if (host.isEmpty()) return
        if (!rawHosts.remove(host)) rawHosts.add(host)
        prefs.edit().putStringSet("rawHosts", rawHosts).apply()
        updateRawBtn()
        postRawToExtension(host) // content script persists + reloads; engine re-applies on reload
    }

    /**
     * Tell the content script the current raw state for [host] so it mirrors it into
     * storage.local (_raw:host) and reloads — the reload is what makes the change take,
     * and doing the persist BEFORE the reload (content-side) avoids a document_start race.
     */
    private fun postRawToExtension(host: String) {
        val port = einkPort
        if (port == null) { Log.w(TAG, "setRaw: no eink port yet"); return }
        try {
            port.postMessage(
                org.json.JSONObject()
                    .put("type", "setRaw")
                    .put("host", host)
                    .put("raw", isRawHost(host)),
            )
        } catch (e: Throwable) {
            Log.w(TAG, "setRaw post threw ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Sync the quick-panel raw button's label/enabled state to the current host. */
    private fun updateRawBtn() {
        val b = rawBtn ?: return
        val host = hostOf(currentUrl)
        if (host.isEmpty()) {
            b.isEnabled = false
            b.text = "Render as-is: —"
        } else {
            b.isEnabled = true
            b.text = "Render as-is: ${if (isRawHost(host)) "ON" else "OFF"}"
        }
    }

    private fun shortPolicy(policy: String): String = when (policy) {
        "hide-all" -> "hidden"
        "placeholder-tap" -> "tap"
        "primary-content-only" -> "primary"
        "load-all" -> "all"
        else -> policy
    }

    /**
     * The compositor scroll offset changed (our native flip, or any other scroll).
     * Any settled scroll drives one EPD refresh — this is the ground truth that a
     * flip actually moved the page, replacing content.js's fire-and-forget "flip"
     * ping. Bursts are coalesced by [armScrollRefresh]'s debounce.
     */
    private val EinkScrollDelegate = object : GeckoSession.ScrollDelegate {
        override fun onScrollChanged(s: GeckoSession, scrollX: Int, scrollY: Int) {
            armScrollRefresh()
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

    // Re-entry from the History page: it relaunches us (CLEAR_TOP|SINGLE_TOP) with the
    // chosen URL rather than recreating, so the load lands on the existing session.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val navUrl = intent.getStringExtra(EXTRA_NAV_URL)
        if (!navUrl.isNullOrBlank() && ::session.isInitialized) {
            intent.removeExtra(EXTRA_NAV_URL)
            session.loadUri(navUrl)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::prefs.isInitialized) return
        // Re-read settings that SettingsActivity may have changed and apply them.
        val oldStructural = listOf(
            navStyle, navPlacement, chromePos, showZones.toString(),
            edgeSlotLeftTop, edgeSlotLeftBottom, edgeSlotRightTop, edgeSlotRightBottom,
        )
        val oldContent = listOf(
            strictTp.toString(), jsEnabled.toString(), blockFonts.toString(), animOff.toString(),
            preferLight.toString(), forceLight.toString(),
            collapseMode, collapseThreshold.toString(), fullEvery.toString(),
        )
        // Raw ("render as-is") for the current host may have been toggled from the
        // Accessibility page while we were paused — detect and apply that on return.
        val curHost = hostOf(currentUrl)
        val oldRaw = isRawHost(curHost)
        loadSettings()
        val rawChanged = isRawHost(curHost) != oldRaw
        val newStructural = listOf(
            navStyle, navPlacement, chromePos, showZones.toString(),
            edgeSlotLeftTop, edgeSlotLeftBottom, edgeSlotRightTop, edgeSlotRightBottom,
        )
        val newContent = listOf(
            strictTp.toString(), jsEnabled.toString(), blockFonts.toString(), animOff.toString(),
            preferLight.toString(), forceLight.toString(),
            collapseMode, collapseThreshold.toString(), fullEvery.toString(),
        )
        // Engine-level levers apply immediately, per current host (raw-aware).
        applyPerHostEngine(curHost)
        Epd.FULL_EVERY = fullEvery
        // Content-script levers (animations, fonts, collapse) pushed to the extension.
        pushSettingsToExtension()
        updateRawBtn()
        // A structural change (edge/nav) needs a full rebuild.
        if (oldStructural != newStructural) { recreate(); return }
        // A raw toggle from the Accessibility page: let the content script persist its
        // per-host mirror and reload (that reload re-applies the engine levers above).
        if (rawChanged) { postRawToExtension(curHost); return }
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

        /**
         * Live native port to the eink extension background page (images.js policy
         * pushes, settings levers). Paging no longer rides this port.
         * Process-wide (not per-activity): the GeckoRuntime + extension +
         * background page are a process-wide singleton that survives activity
         * recreate(), but onConnect only re-fires on a fresh connectNative from
         * background.js — a per-activity field would go stale (null) forever
         * after recreate() and silently drop every strip tap.
         */
        @Volatile internal var einkPort: WebExtension.Port? = null

        /** EPD full-clear cadence steps cycled by the settings button (0 = Off). */
        internal val CADENCE_STEPS = intArrayOf(0, 4, 6, 8, 10, 15)

        /** Auto-collapse threshold steps cycled on the settings page. */
        internal val COLLAPSE_THRESHOLD_STEPS = intArrayOf(3, 5, 6, 8, 10, 15)

        /** Dark ink for all (light) chrome — high contrast, no colour. */
        internal const val CHROME_INK = 0xFF222222.toInt()

        /** Height (dp) of the bottom-of-strip sliver action button. */
        private const val SLIVER_H = 56

        /**
         * Fraction of the visual viewport a single page flip scrolls. 0.9 keeps a
         * ~10% overlap band between consecutive pages for reading continuity.
         * (Candidate for a user setting later; see the parked overlap-% backlog.)
         */
        private const val PAGE_SCROLL_FRACTION = 0.9

        /**
         * Debounce (ms) between the last scroll offset change and the EPD refresh —
         * long enough to coalesce a flip's burst of onScrollChanged events (and the
         * flipPage backstop) into one refresh on the settled frame. Instant
         * (SCROLL_BEHAVIOR_AUTO) scrolls settle within a frame or two.
         */
        private const val SCROLL_SETTLE_MS = 48L

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

        /** Intent extra: a URL for the History page to open in the running MainActivity. */
        const val EXTRA_NAV_URL = "navUrl"

        /** Max history rows shown in the address-bar autocomplete panel. */
        private const val SUGGEST_LIMIT = 6

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
                // HTTPS-only: upgrade http:// and show Gecko's interstitial rather
                // than silently loading plaintext. A portable e-ink reader roams onto
                // hostile/open Wi-Fi, so a passive-MITM plaintext load is a real threat
                // (cf. the 2026-07-15 DNS incident). User can still proceed per-site via
                // the interstitial.
                .allowInsecureConnections(GeckoRuntimeSettings.HTTPS_ONLY)
                .build()
            return GeckoRuntime.create(activity.applicationContext, settings)
        }
    }
}
