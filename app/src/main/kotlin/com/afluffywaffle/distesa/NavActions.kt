package com.afluffywaffle.distesa

/**
 * The single source of truth for the functions a configurable button can perform —
 * shared by BOTH the edge slivers and the chrome-bar slots, and by the layout editor's
 * pickers. Add a new function HERE (one line) and it appears in every applicable slot
 * picker at once; the only per-slot work left is wiring its click behaviour in
 * MainActivity ([MainActivity.makeSliverButton] / [MainActivity.makeChromeButton]).
 *
 * [Context] gates where an action is offered: "chrome" (reveal the toolbar) is
 * meaningless on the chrome bar itself, so it's SLIVER-only. Everything else is both.
 */
object NavActions {

    enum class Context { SLIVER, CHROME }

    /** @param glyph the menu/preview glyph; the live buttons may style it per context. */
    data class Spec(val id: String, val label: String, val glyph: String, val contexts: Set<Context>)

    val ALL: List<Spec> = listOf(
        Spec("chrome", "Address bar", "⌕", setOf(Context.SLIVER)),
        Spec("back", "Back", "←", setOf(Context.SLIVER, Context.CHROME)),
        Spec("refresh", "Refresh", "⟳", setOf(Context.SLIVER, Context.CHROME)),
        Spec("collapse", "Collapse", "⊟", setOf(Context.SLIVER, Context.CHROME)),
        Spec("none", "None", "·", setOf(Context.SLIVER, Context.CHROME)),
    )

    fun forContext(c: Context): List<Spec> = ALL.filter { c in it.contexts }

    /** Option list for a picker: id → "Label   glyph". The glyph trails the label so the
     *  labels left-align in a column (leading glyphs vary in width and misalign the text). */
    fun options(c: Context): List<Pair<String, String>> =
        forContext(c).map { it.id to "${it.label}   ${it.glyph}" }

    fun label(id: String): String = ALL.firstOrNull { it.id == id }?.label ?: id
    fun glyph(id: String): String = ALL.firstOrNull { it.id == id }?.glyph ?: ""
}
