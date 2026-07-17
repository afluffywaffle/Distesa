package com.afluffywaffle.avosetta

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

    /**
     * @param glyph the single glyph the live button / preview renders (empty if [iconRes]).
     * @param menuGlyph what a picker row shows after the label — defaults to [glyph], but
     *   a toggling function (collapse) lists BOTH of its states so the user knows it flips.
     * @param iconRes an optional vector drawable rendered INSTEAD of [glyph] on the button
     *   and in the preview — for actions (the settings pages) whose mark is a drawn B&W
     *   icon, not a font glyph. When set, the picker row shows the label alone.
     */
    data class Spec(
        val id: String, val label: String, val glyph: String, val contexts: Set<Context>,
        val menuGlyph: String = glyph,
        val iconRes: Int? = null,
    )

    val ALL: List<Spec> = listOf(
        Spec("chrome", "Address bar", "⌕", setOf(Context.SLIVER)),
        Spec("back", "Back", "←", setOf(Context.SLIVER, Context.CHROME)),
        Spec("refresh", "Refresh", "⟳", setOf(Context.SLIVER, Context.CHROME)),
        Spec("collapse", "Collapse", "⊟", setOf(Context.SLIVER, Context.CHROME), menuGlyph = "⊟ / ⊞"),
        // The settings pages, as assignable launchers. Rendering keeps its ⌗ glyph;
        // Supernote / Extensions carry the same drawn B&W icons as the layout-editor top bar.
        Spec("rendering", "Rendering", "⌗", setOf(Context.SLIVER, Context.CHROME)),
        Spec("supernote", "Supernote", "", setOf(Context.SLIVER, Context.CHROME), iconRes = R.drawable.ic_supernote),
        Spec("extensions", "Extensions", "", setOf(Context.SLIVER, Context.CHROME), iconRes = R.drawable.ic_puzzle),
        // Element-zapper actions (also live in the quick-settings panel).
        Spec("zap", "Zap element", "⬡", setOf(Context.SLIVER, Context.CHROME)),
        Spec("undozap", "Undo zap", "↺", setOf(Context.SLIVER, Context.CHROME)),
        Spec("resetzap", "Reset zaps", "⊘", setOf(Context.SLIVER, Context.CHROME)),
        Spec("none", "None", "·", setOf(Context.SLIVER, Context.CHROME)),
    )

    fun forContext(c: Context): List<Spec> = ALL.filter { c in it.contexts }

    /** Option list for a picker: id → "Label   menuGlyph" (label alone for icon actions,
     *  whose mark is a drawable the row can't render inline). The glyph trails the label so
     *  labels left-align in a column (leading glyphs vary in width and misalign the text). */
    fun options(c: Context): List<Pair<String, String>> =
        forContext(c).map { it.id to if (it.iconRes != null) it.label else "${it.label}   ${it.menuGlyph}" }

    fun label(id: String): String = ALL.firstOrNull { it.id == id }?.label ?: id
    fun glyph(id: String): String = ALL.firstOrNull { it.id == id }?.glyph ?: ""
    fun iconRes(id: String): Int? = ALL.firstOrNull { it.id == id }?.iconRes
}
