package com.afluffypancake.achroma

import android.app.Activity
import android.os.Bundle
import android.util.Log
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

/**
 * Achroma Phase 0 Spike A — the GeckoView engine evaluation.
 *
 * Minimal by design: a single full-screen [GeckoView] backed by a process-wide
 * [GeckoRuntime] singleton and one [GeckoSession]. No tabs, no address bar — that
 * is Phase 1. The goal here is only: launch, load a page, attempt to install
 * uBlock Origin, and be measurable (RAM, cold start, page load, refresh quality)
 * against the parallel WebView spike.
 *
 * The e-ink refresh modules (eink/) are ported from layuv but NOT yet wired to
 * the Gecko rendering surface — that is Phase 1 work (see eink/ TODOs).
 */
class MainActivity : Activity() {

    private lateinit var session: GeckoSession
    private lateinit var view: GeckoView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val runtime = sharedRuntime(this)
        installUBlock(runtime)

        session = GeckoSession()
        session.open(runtime)

        view = GeckoView(this)
        view.setSession(session)
        setContentView(view)

        // A deliberately ad/image-heavy page so the uBlock effect is visible.
        session.loadUri(TEST_URL)
    }

    /**
     * Install uBlock Origin as a built-in WebExtension from assets. This is
     * wrapped so a MISSING extension (the assets/extensions/ublock/ folder has
     * only a README placeholder until a real build is dropped in) logs a warning
     * rather than crashing the spike.
     */
    private fun installUBlock(runtime: GeckoRuntime) {
        try {
            runtime.webExtensionController
                .installBuiltIn(UBLOCK_URI)
                .accept(
                    { ext -> Log.i(TAG, "uBlock installed: ${ext?.id}") },
                    { e -> Log.w(TAG, "uBlock install failed (extension missing or invalid?): ${e?.message}") },
                )
        } catch (e: Throwable) {
            Log.w(TAG, "uBlock install threw ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    override fun onDestroy() {
        // The GeckoSession is per-Activity; the runtime is a process singleton and
        // intentionally outlives it.
        if (::session.isInitialized) session.close()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AchromaMain"

        /** Ad/image-heavy test page — makes the uBlock filtering effect visible. */
        private const val TEST_URL = "https://www.theverge.com"

        /** Built-in WebExtension location — see assets/extensions/ublock/README.txt. */
        private const val UBLOCK_URI = "resource://android/assets/extensions/ublock/"

        @Volatile private var runtime: GeckoRuntime? = null

        /** Process-wide GeckoRuntime singleton. */
        private fun sharedRuntime(activity: Activity): GeckoRuntime {
            return runtime ?: synchronized(this) {
                runtime ?: GeckoRuntime.create(activity.applicationContext).also { runtime = it }
            }
        }
    }
}
