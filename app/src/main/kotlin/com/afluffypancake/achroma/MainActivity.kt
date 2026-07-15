package com.afluffypancake.achroma

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
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

        val runtime = sharedRuntime(this)
        // Auto-approve add-on install + permission prompts (test spike only).
        runtime.webExtensionController.promptDelegate = AutoApprovePromptDelegate

        // Our OWN bundled extension (page-flip). Unlike uBlock (a third-party
        // add-on installed at runtime from AMO), we author this one so it ships
        // inside the APK and loads via installBuiltIn from assets.
        installEink(runtime)

        session = GeckoSession()
        session.open(runtime)

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
        setContentView(root)

        session.loadUri(TEST_URL)

        ensureUBlock(runtime)
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
                val type = when (message) {
                    is org.json.JSONObject -> message.optString("type")
                    else -> message.toString()
                }
                if (type == "flip") onFlip()
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
            runOnUiThread { imgToggle.isEnabled = true }
        }
    }

    private val EinkPortDelegate = object : WebExtension.PortDelegate {
        override fun onPortMessage(message: Any, port: WebExtension.Port) {
            try {
                val obj = message as? org.json.JSONObject ?: return
                if (obj.optString("type") == "policy") {
                    val policy = obj.optString("policy")
                    runOnUiThread {
                        imgToggle.isEnabled = true
                        imgToggle.text = "IMG: ${shortPolicy(policy)}"
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

        /** Ad/image-heavy test page — a realistic render/refresh workload. */
        private const val TEST_URL = "https://www.theverge.com"

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
                runtime ?: GeckoRuntime.create(activity.applicationContext).also { runtime = it }
            }
        }
    }
}
