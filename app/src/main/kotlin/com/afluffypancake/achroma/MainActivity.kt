package com.afluffypancake.achroma

import android.app.Activity
import android.content.SharedPreferences
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Switch
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController
import com.afluffypancake.achroma.eink.Epd

/**
 * Achroma Phase 0 Spike A — the GeckoView engine evaluation.
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
    private lateinit var toggle: Button
    private lateinit var imgToggle: Button
    private lateinit var prefs: SharedPreferences
    private lateinit var settingsPanel: LinearLayout
    private lateinit var cadenceBtn: Button

    // E-ink performance levers (persisted in SharedPreferences; see loadSettings()).
    private var animOff = true          // inject animation/transition-killing CSS
    private var blockFonts = true       // block web-font requests (system fallback)
    private var strictTp = true         // engine strict tracking protection
    private var jsEnabled = true        // per-session JavaScript
    private var fullEvery = 10          // EPD full-clear cadence (0 = Off)

    // Load-time measurement.
    private var pageStartMs = 0L
    private var pageHost = ""

    // Hosts observed to have a password field — tracking protection is relaxed to
    // Standard for these (Strict can break sign-in). Persisted; grows over time.
    private var loginHosts: MutableSet<String> = mutableSetOf()

    /** The installed uBlock Origin add-on, once resolved (install or list). */
    private var ublock: WebExtension? = null
    private var busy = false

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

        // Wrap the GeckoView so we can overlay a minimal on/off control.
        val root = FrameLayout(this)
        root.addView(view)
        toggle = Button(this).apply {
            text = "uBO: …"
            isEnabled = false
            setOnClickListener { onToggleClicked() }
        }
        root.addView(
            toggle,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END,
            ),
        )
        // Second overlay button: cycles the current domain's image policy. Sits
        // just below the uBO button (same minimal style).
        imgToggle = Button(this).apply {
            text = "IMG: …"
            isEnabled = false
            setOnClickListener { onCycleImagePolicy() }
        }
        root.addView(
            imgToggle,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END,
            ).apply { topMargin = 140 },
        )
        // Third overlay button: gear that shows/hides the settings panel.
        val gear = Button(this).apply {
            text = "⚙"
            setOnClickListener {
                settingsPanel.visibility =
                    if (settingsPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        }
        root.addView(
            gear,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END,
            ).apply { topMargin = 280 },
        )
        // The settings panel itself (hidden until the gear is tapped).
        settingsPanel = buildSettingsPanel()
        root.addView(
            settingsPanel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END,
            ).apply { topMargin = 420 },
        )
        setContentView(root)

        session.loadUri(TEST_URL)

        ensureUBlock(runtime)
    }

    // --- Settings: persistence + panel + levers -----------------------------

    private fun loadSettings() {
        prefs = getSharedPreferences("achroma_settings", MODE_PRIVATE)
        animOff = prefs.getBoolean("animOff", true)
        blockFonts = prefs.getBoolean("blockFonts", true)
        strictTp = prefs.getBoolean("strictTp", true)
        jsEnabled = prefs.getBoolean("jsEnabled", true)
        fullEvery = prefs.getInt("fullEvery", 10)
        loginHosts = HashSet(prefs.getStringSet("loginHosts", emptySet()) ?: emptySet())
    }

    /** True if [host] should get Standard (not Strict) tracking protection. */
    private fun isLoginHost(host: String?): Boolean {
        if (host.isNullOrEmpty()) return false
        val h = host.lowercase()
        if (loginHosts.contains(h)) return true
        return AUTH_ALLOWLIST.any { h == it || h.endsWith(".$it") || h.endsWith(it) }
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
    }

    private fun buildSettingsPanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xEE222222.toInt()) // semi-opaque dark grey
            setPadding(28, 20, 28, 20)
            visibility = View.GONE
        }
        panel.addView(makeSwitch("Animations off", animOff) { on ->
            animOff = on; prefs.edit().putBoolean("animOff", on).apply(); pushSettingsToExtension()
        })
        panel.addView(makeSwitch("Block web fonts", blockFonts) { on ->
            blockFonts = on; prefs.edit().putBoolean("blockFonts", on).apply(); pushSettingsToExtension()
        })
        panel.addView(makeSwitch("Strict tracking protection", strictTp) { on ->
            strictTp = on; prefs.edit().putBoolean("strictTp", on).apply()
            applyTrackingProtection(on); reloadPage()
        })
        panel.addView(makeSwitch("JavaScript", jsEnabled) { on ->
            jsEnabled = on; prefs.edit().putBoolean("jsEnabled", on).apply()
            if (::session.isInitialized) session.settings.setAllowJavascript(on); reloadPage()
        })
        cadenceBtn = Button(this).apply {
            text = cadenceLabel()
            setOnClickListener { cycleCadence() }
        }
        panel.addView(cadenceBtn)
        return panel
    }

    private fun makeSwitch(label: String, initial: Boolean, onChange: (Boolean) -> Unit): Switch {
        return Switch(this).apply {
            text = label
            setTextColor(0xFFEEEEEE.toInt())
            isChecked = initial
            setOnCheckedChangeListener { _, isChecked -> onChange(isChecked) }
        }
    }

    private fun cadenceLabel(): String =
        "Full-clear: " + if (fullEvery <= 0) "Off" else fullEvery.toString()

    private fun cycleCadence() {
        val idx = CADENCE_STEPS.indexOf(fullEvery).let { if (it < 0) 0 else it }
        fullEvery = CADENCE_STEPS[(idx + 1) % CADENCE_STEPS.size]
        prefs.edit().putInt("fullEvery", fullEvery).apply()
        Epd.FULL_EVERY = fullEvery
        cadenceBtn.text = cadenceLabel()
        Log.i(TAG, "[eink-diag] EPD full-clear cadence -> ${cadenceLabel()}")
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
                    .put("blockFonts", blockFonts),
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
                    // reliably reach logcat), surfaced under tag AchromaMain.
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
                            imgToggle.text = "IMG: ${shortPolicy(policy)}"
                        }
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

    /** Cache the add-on and reflect its current enabled state in the button. */
    private fun onExtResolved(ext: WebExtension?) {
        ublock = ext
        busy = false
        toggle.isEnabled = ext != null
        refreshLabel()
    }

    private fun onToggleClicked() {
        val ext = ublock ?: return
        if (busy) return
        busy = true
        toggle.isEnabled = false
        val controller = sharedRuntime(this).webExtensionController
        val enabled = ext.metaData.enabled
        val result: GeckoResult<WebExtension> = if (enabled) {
            controller.disable(ext, WebExtensionController.EnableSource.USER)
        } else {
            controller.enable(ext, WebExtensionController.EnableSource.USER)
        }
        result.accept(
            { updated ->
                Log.i(TAG, "uBlock now ${if (updated?.metaData?.enabled == true) "enabled" else "disabled"}")
                onExtResolved(updated)
                // Reload so the (dis)enabled content blocker takes effect on the page.
                session.reload()
            },
            { e ->
                Log.w(TAG, "toggle failed: ${e?.message}")
                busy = false
                toggle.isEnabled = true
                refreshLabel()
            },
        )
    }

    private fun refreshLabel() {
        val ext = ublock
        toggle.text = when {
            ext == null -> "uBO: n/a"
            ext.metaData.enabled -> "uBO: on"
            else -> "uBO: off"
        }
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
        private const val TAG = "AchromaMain"

        /** EPD full-clear cadence steps cycled by the settings button (0 = Off). */
        private val CADENCE_STEPS = intArrayOf(0, 4, 6, 8, 10, 15)

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
        private const val UBLOCK_ID = "uBlock0@raymondhill.net"

        /** AMO "latest" signed xpi for uBlock Origin (302-redirects to the current version). */
        private const val UBLOCK_XPI =
            "https://addons.mozilla.org/firefox/downloads/latest/ublock-origin/latest.xpi"

        @Volatile private var runtime: GeckoRuntime? = null

        /** Process-wide GeckoRuntime singleton. */
        private fun sharedRuntime(activity: Activity): GeckoRuntime {
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
