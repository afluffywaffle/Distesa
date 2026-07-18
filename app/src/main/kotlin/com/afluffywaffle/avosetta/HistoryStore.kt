package com.afluffywaffle.avosetta

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Visited-page history — a capped, newest-first log persisted as a single JSON
 * string in the shared "distesa_settings" prefs (key [KEY]), matching the app's
 * all-SharedPreferences house style rather than pulling in Room/SQLite.
 *
 * Each entry is `{u: url, t: title, ts: epoch-ms}`. The list is de-duplicated by
 * URL (a revisit moves the entry to the front and refreshes its timestamp), pruned
 * to a rolling [RETENTION_MS] window, and hard-capped at [CAP] as a backstop — so the
 * blob stays small enough to read/rewrite whole on every visit and to filter
 * in-memory for address-bar autocomplete. Pruning happens on every read AND write, so
 * expired entries drop out even if the user just opens the History page without
 * browsing.
 *
 * Every operation read-modify-writes the whole array; at these event rates (page
 * loads, keystrokes) and this cap the cost is negligible, and staying stateless
 * keeps every caller trivially consistent through the one shared prefs file
 * (MainActivity records + queries; HistoryActivity reads + deletes).
 */
object HistoryStore {
    const val KEY = "history"
    const val CAP = 1000

    /** Rolling retention window: entries older than this are pruned on read/write. */
    const val RETENTION_MS = 7L * 24 * 60 * 60 * 1000 // 7 days

    /**
     * A server redirect fires onLocationChange for the hop AND the landing page. If the
     * top entry is still untitled and only moments old when the next URL lands, it's
     * almost certainly that intermediate hop — collapse it rather than stack a duplicate.
     * Manual navigation on e-ink is far slower than this window, so it won't merge two
     * genuine visits.
     */
    private const val REDIRECT_WINDOW_MS = 2500L

    data class Entry(val url: String, val title: String, val ts: Long)

    private fun load(prefs: SharedPreferences): MutableList<Entry> {
        val raw = prefs.getString(KEY, null) ?: return mutableListOf()
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<Entry>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val e = Entry(o.getString("u"), o.optString("t", ""), o.optLong("ts", 0L))
                if (e.ts >= cutoff) out.add(e) // drop anything past the retention window
            }
            out
        } catch (e: Throwable) {
            mutableListOf() // a corrupt/legacy blob just starts fresh, never crashes
        }
    }

    private fun save(prefs: SharedPreferences, list: List<Entry>) {
        val arr = JSONArray()
        for (e in list) {
            arr.put(JSONObject().apply { put("u", e.url); put("t", e.title); put("ts", e.ts) })
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    /**
     * Record (or bump to the front) a visit to [url] at [ts]. The title usually
     * isn't known yet at visit time — it's filled in later via [updateTitle] — so a
     * revisit preserves any title already on record for that URL.
     */
    fun record(prefs: SharedPreferences, url: String, ts: Long) {
        val u = url.trim()
        if (u.isEmpty()) return
        val list = load(prefs)
        // Drop a just-recorded, still-untitled redirect hop (see REDIRECT_WINDOW_MS).
        list.firstOrNull()?.let { front ->
            if (front.url != u && front.title.isEmpty() && ts - front.ts < REDIRECT_WINDOW_MS) {
                list.removeAt(0)
            }
        }
        val keepTitle = list.firstOrNull { it.url == u }?.title ?: ""
        list.removeAll { it.url == u }
        list.add(0, Entry(u, keepTitle, ts))
        while (list.size > CAP) list.removeAt(list.size - 1)
        save(prefs, list)
    }

    /** Patch the title of the entry for [url] (the title arrives after the visit). */
    fun updateTitle(prefs: SharedPreferences, url: String, title: String) {
        val u = url.trim()
        val t = title.trim()
        if (u.isEmpty() || t.isEmpty()) return
        val list = load(prefs)
        val i = list.indexOfFirst { it.url == u }
        if (i < 0 || list[i].title == t) return
        list[i] = list[i].copy(title = t)
        save(prefs, list)
    }

    /** All entries, newest first. */
    fun all(prefs: SharedPreferences): List<Entry> = load(prefs)

    /** Newest-first entries whose URL or title contains [q] (case-insensitive). */
    fun query(prefs: SharedPreferences, q: String, limit: Int = 8): List<Entry> {
        val needle = q.trim().lowercase()
        if (needle.isEmpty()) return emptyList()
        return load(prefs).asSequence()
            .filter { it.url.lowercase().contains(needle) || it.title.lowercase().contains(needle) }
            .take(limit)
            .toList()
    }

    fun delete(prefs: SharedPreferences, url: String) {
        val list = load(prefs)
        if (list.removeAll { it.url == url }) save(prefs, list)
    }

    fun clear(prefs: SharedPreferences) {
        prefs.edit().remove(KEY).apply()
    }
}
