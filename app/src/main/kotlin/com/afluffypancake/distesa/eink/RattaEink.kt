package com.afluffypancake.distesa.eink

import android.content.Context
import android.util.Log
import java.lang.reflect.Method

/**
 * Thin reflection wrapper around android.os.EinkManager — the Ratta (Supernote)
 * EPD refresh API, reached via getSystemService("eink").
 *
 * Requires `adb shell settings put global hidden_api_policy 0` on the device.
 *
 * Gracefully no-ops on non-Supernote hardware where getSystemService("eink")
 * returns null; errors are logged and swallowed.
 *
 * Ported from layuv (com.afluffywaffle.layuv.reader) — this file had no
 * reader-specific coupling, so it is copied verbatim aside from the package.
 */
internal object RattaEink {
    private const val TAG = "DistesaRattaEink"
    private const val SERVICE = "eink"

    @Volatile private var manager: Any? = null
    @Volatile private var fullFrameMethod: Method? = null
    @Volatile private var resolved = false

    fun available(context: Context): Boolean {
        resolve(context)
        return manager != null
    }

    /**
     * Trigger a full-screen EPD refresh to clear accumulated ghosting.
     * Maps to EinkManager.sendOneFullFrame() — a soft clean refresh (not the
     * panel-flashing screenRefresh(force=true) variant, which is overkill for
     * routine ghost management between page turns).
     */
    fun sendOneFullFrame(context: Context) {
        resolve(context)
        val m = manager ?: return
        try {
            // Method resolved once in resolve(); avoids a getMethod() table walk on
            // every full-clear (this runs inside the page-turn loop).
            fullFrameMethod?.invoke(m)
            Log.i(TAG, "sendOneFullFrame ok")
        } catch (e: Throwable) {
            Log.w(TAG, "sendOneFullFrame: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun resolve(context: Context) {
        if (resolved) return
        resolved = true
        manager = try {
            context.applicationContext.getSystemService(SERVICE).also {
                if (it != null) Log.i(TAG, "EinkManager resolved: ${it.javaClass.name}")
                else Log.i(TAG, "EinkManager not available (non-Supernote build)")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "getSystemService(eink) threw: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
        fullFrameMethod = try {
            manager?.javaClass?.getMethod("sendOneFullFrame")
        } catch (e: Throwable) {
            Log.w(TAG, "resolve sendOneFullFrame: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }
}
