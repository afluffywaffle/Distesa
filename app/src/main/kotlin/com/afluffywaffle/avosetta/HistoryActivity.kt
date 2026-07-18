package com.afluffywaffle.avosetta

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.format.DateUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.mozilla.geckoview.StorageController

/**
 * Avosetta — the History page.
 *
 * A newest-first list of visited pages, read straight from [HistoryStore] (the
 * capped JSON log in the shared "distesa_settings" prefs). Reached from the layout
 * editor's top-bar hub, alongside Rendering / Supernote / Extensions / Accessibility.
 *
 * Tapping a row relaunches MainActivity with an [MainActivity.EXTRA_NAV_URL] extra
 * (CLEAR_TOP|SINGLE_TOP), so the URL loads on the existing session rather than a new
 * browser instance. Each row has a ✕ to forget it; "Clear all" wipes the log (with a
 * confirm). Paginated so a long history never runs off the bottom on e-ink.
 *
 * Same house style as the Accessibility / Rendering pages: white page, no animation,
 * transparent buttons with dark ink, flat bordered panes (no elevation).
 */
class HistoryActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var root: FrameLayout
    private lateinit var listBox: LinearLayout
    private var modal: View? = null

    private var page = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("distesa_settings", MODE_PRIVATE)
        title = "History"

        val scroll = ScrollView(this).apply {
            setBackgroundColor(0xFFFFFFFF.toInt())
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        // Top row: Done (exit) + Clear all.
        val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        topRow.addView(makeButton("‹ Done") { finish() }.apply {
            textSize = 18f
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        topRow.addView(makeButton("Clear all") {
            if (HistoryStore.all(prefs).isEmpty()) return@makeButton
            confirmImpact(
                "Clear all history?",
                "Forgets every page in this list, and you'll be signed out of websites; caches and site data are wiped. This can't be undone.",
            ) {
                HistoryStore.clear(prefs); page = 0; renderList()
                MainActivity.sharedRuntime(this).storageController
                    .clearData(StorageController.ClearFlags.ALL)
                    .accept(
                        { Log.i("history", "gecko data cleared") },
                        { e -> Log.w("history", "gecko data clear failed", e) },
                    )
            }
        }.apply { textSize = 16f; gravity = Gravity.CENTER_VERTICAL })
        panel.addView(topRow)

        panel.addView(pageTitle("History"))

        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(listBox)

        scroll.addView(panel)
        root = FrameLayout(this).apply { addView(scroll) }
        setContentView(root)

        renderList()
    }

    override fun onBackPressed() {
        if (modal != null) dismissModal() else super.onBackPressed()
    }

    // --- list -----------------------------------------------------------------

    private fun renderList() {
        listBox.removeAllViews()
        val all = HistoryStore.all(prefs)
        if (all.isEmpty()) {
            listBox.addView(explain(
                "No history yet. Pages you visit will appear here, newest first."
            ))
            return
        }

        val pages = (all.size + PAGE_SIZE - 1) / PAGE_SIZE
        page = page.coerceIn(0, pages - 1)
        val from = page * PAGE_SIZE
        val slice = all.subList(from, minOf(from + PAGE_SIZE, all.size))
        for (e in slice) listBox.addView(historyRow(e))

        if (pages > 1) listBox.addView(pager(pages))
    }

    /** One tappable history row: title + url + relative time, with a ✕ to forget it. */
    private fun historyRow(e: HistoryStore.Entry): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = borderBg()
            setPadding(dp(12), dp(10), dp(6), dp(10))
        }
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            setOnClickListener { openUrl(e.url) }
        }
        textCol.addView(TextView(this).apply {
            text = if (e.title.isNotEmpty()) e.title else e.url
            setTextColor(MainActivity.CHROME_INK)
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        textCol.addView(TextView(this).apply {
            text = e.url
            setTextColor(MainActivity.CHROME_INK)
            alpha = 0.6f
            textSize = 13f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        })
        if (e.ts > 0) textCol.addView(TextView(this).apply {
            text = DateUtils.getRelativeTimeSpanString(
                e.ts, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
            )
            setTextColor(MainActivity.CHROME_INK)
            alpha = 0.45f
            textSize = 12f
        })
        row.addView(textCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(makeButton("✕") {
            HistoryStore.delete(prefs, e.url); renderList()
        }.apply { textSize = 18f; gravity = Gravity.CENTER })

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            setPadding(0, 0, 0, dp(8))
        }
    }

    /** Prev / "Page X of N" / Next controls. */
    private fun pager(pages: Int): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        bar.addView(makeButton("‹ Prev") {
            if (page > 0) { page--; renderList() }
        }.apply { textSize = 16f; isEnabled = page > 0; alpha = if (page > 0) 1f else 0.35f })
        bar.addView(TextView(this).apply {
            text = "Page ${page + 1} of $pages"
            setTextColor(MainActivity.CHROME_INK)
            alpha = 0.7f
            textSize = 14f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(makeButton("Next ›") {
            if (page < pages - 1) { page++; renderList() }
        }.apply { textSize = 16f; isEnabled = page < pages - 1; alpha = if (page < pages - 1) 1f else 0.35f })
        return bar
    }

    private fun openUrl(url: String) {
        startActivity(Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_NAV_URL, url)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        finish()
    }

    // --- house-style helpers (mirror AccessibilityActivity) --------------------

    private fun pageTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(MainActivity.CHROME_INK)
        textSize = 22f
        setPadding(0, dp(8), 0, dp(8))
    }

    private fun explain(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(MainActivity.CHROME_INK)
        textSize = 15f
        alpha = 0.7f
        setPadding(dp(4), dp(8), 0, dp(16))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

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

    // --- confirm popup (custom scrim/pane, e-ink friendly) ---------------------

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
            isClickable = true
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

    companion object {
        private const val PAGE_SIZE = 12
    }
}
