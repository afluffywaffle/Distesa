# PLAN — Notepad (split-view pen scratchpad)

_Planned 2026-07-18 (session R, Fable). Roadmap item ③. V1 decisions settled with the
user in session Q (see HANDOFF.md session R); this plan turns them into build phases._

## Goal

A split-view mode in `MainActivity`: web page in one pane, a pen scratchpad ("pad") in
the other. Pen-only pad, paginated (never scrolls), notes URL-bound to the source page,
JSON storage, PDF export, and a "Notes" hub list sibling to History.

## Settled v1 decisions (from session Q — do not relitigate)

- **Fixed 50/50 split.** Portrait: web top / pad bottom. Landscape: pad on the user's
  handedness side, web on the other. No draggable divider.
- **Half-size nav rails** in split mode; **no rail on the pad side** (pen owns that
  surface — avoids stray taps).
- **Pen vs. finger routing** via `MotionEvent.getToolType`: pad accepts stylus only
  (finger ignored = free palm rejection); pen on pad never leaks scroll to Gecko.
- **Fixed logical canvas per page** (~3:4), rendered scale-to-fit/letterboxed into
  whatever pane geometry is showing — strokes NEVER reflow, the page just scales.
- **Paginated pad** — "need more room" = next page.
- **URL-bound notes**: each note references the source page URL/title for revisiting.
- **Storage**: per-note JSON — manifest (url, title, created, updated) + per-page
  stroke lists (points, pressure, width).
- **Export**: flattened PDF v1; PDF with vector `Ink` annotations as the
  strokes-preserved option (standard readers understand it — no custom format). v2 ok.
- **Hub "Notes" list** sibling to History; open-source-page uses the existing
  navUrl path, open-note opens the pad.

## Key architecture facts (surveyed 2026-07-18)

- `MainActivity` builds everything programmatically: `root` (anon FrameLayout,
  ~line 282) → GeckoView `view` with `viewLp` (MATCH_PARENT both; `navStyle=inset`
  already narrows it via left/right margins = `stripW`). **`updateEdgeInset()`**
  (~1250) is the single place that mutates `viewLp` for chrome/domain/IME insets —
  the split shrink must integrate THERE, not fight it.
- Rotation does NOT recreate (`configChanges="orientation|screenSize|keyboardHidden"`),
  and nothing listens for it today — split mode must handle `onConfigurationChanged`
  itself (or accept a `recreate()`; see Phase 2).
- `computeChromeAtBottom()` (~1359) has the diagonal-inches logic to reuse for any
  size-dependent choice.
- Hub pattern to mirror: `HistoryActivity` (ScrollView + hand-built rows, in-memory
  pagination PAGE_SIZE=12, `confirmImpact` modal, ✕/Clear-all) and the `LayoutActivity`
  `topBar` glyph buttons (◷ History ~line 160). `HistoryStore`: single JSON blob in
  `distesa_settings` prefs.
- **No file I/O exists in the app today** (100% SharedPreferences). Stroke data is too
  big for the prefs-blob pattern → notes go to `getFilesDir()/notes/` (first real
  file storage; deliberate divergence from the HistoryStore pattern).
- **No pen handling exists** (zero `toolType` usage). `root.onInterceptTouchEvent`
  (chrome tap-off dismiss) must NOT swallow pad pen-downs.
- **EPD refresh**: `Epd.pageTurn(view)` partial/full-clear cycle (FULL_EVERY=6);
  `Epd.selection(view)` = plain invalidate. Region-window HAL refresh not applicable.
- **Reuse from layuv** (`~/Develop/layuv/android_native/.../reader/`):
  - `DrawPathClient.kt` — reflection binder client for the Supernote **drawPath
    low-latency hardware-ink service** (`service_myservice`): `sendPen` (type/width/
    color), `setWritableAreas` (rect whitelist/blacklist, flag 1 = ONLY that rect
    writable — exactly our pad rect), `clearScreen`, `sendReset` (mandatory
    post-resume whole-screen reset). Confirmed working on the Nomad.
  - `InkNoteActivity.kt` — a working ink-note surface: stylus-only MotionEvent
    capture into a stroke model + drawPath hardware ink on top, tool switching
    (thin/thick/eraser), clear/reset lifecycle. **Port its patterns, not the file.**
- adb can now drive page loads (`am start -a android.intent.action.VIEW -d <URL>
  -p com.afluffywaffle.avosetta.dev`) — useful for split-mode verification.
- `adb screencap` is blind to EPD-pushed ink — verify pen strokes by stroke-model
  logs + human eyeball, not screenshots.

## Phases

Each phase compiles, is device-verifiable on the Nomad, and commits on its own.

### Phase 0 — Surfaces in the existing chrome slots (small, standalone, NO new UI)

_Added 2026-07-18 after user discussion. Two earlier shapes rejected: a tab/pill
row ("too busy") and home-screen links (notepad must be openable while ON a
page). Final shape (user's): repurpose the UI that already exists — no new
surface, just new defaults + catalog entries._

- **Principle:** corner slivers = always-present, muscle-memory MECHANICS;
  the summoned chrome bar = deliberate DESTINATIONS.
- **New default sliver layout:** top-left **← back**, bottom-left **⌕ address**,
  top-right **⟳ refresh**, bottom-right **⊟ collapse images** (all four actions
  already exist in the sliver catalog — this is a defaults change only).
- **New default chrome-bar slots** (`chromeBtnLeft/Right1/Right2`):
  **◷ History** / **✎ Notepad** / **(none — reserved for Reader)**. Back/refresh/
  collapse leave the bar (they live on the slivers now). Bar reads
  `◷ [url] ✎ ⚙` when done — LESS busy than today's five-glyph bar.
- **Three new `NavActions` catalog entries** — `history` (◷), `notepad` (✎),
  `reader` (glyph TBD) — valid in BOTH chrome-slot and sliver contexts, so the
  whole arrangement stays user-configurable in the layout editor.
- **No dead placeholders:** Phase 0 ships `history` only (right1 empty until
  Phase 2 wires `notepad`; right2 empty until reader mode lands — a missing
  slot renders as nothing, `makeChromeButton` already returns null for it).
- **◷ leaves the `LayoutActivity` hub** — one way to reach each surface; the
  hub keeps only settings/config pages.
- **Defaults migration:** new defaults apply only where prefs are unset —
  existing configs don't rearrange themselves (reset dev-device prefs on
  install, or reconfigure once in the editor).
- **Verify**: fresh-prefs install → sliver corners as above; summon chrome →
  ◷ opens History; layout editor still reassigns everything; suggestion box
  unchanged above the bar.

### Phase 1 — Pen foundation: `PadView` + drawPath port (no browser integration yet)

New `notes/DrawPathClient.kt` (port from layuv, package-renamed, appName = our pkg) and
new `notes/PadView.kt`:

- Custom `View`. `onTouchEvent`: accept only `TOOL_TYPE_STYLUS`; build strokes as
  point lists (x, y, pressure) in **logical canvas coordinates** (fixed logical page,
  e.g. 1000×1333 ≈ 3:4; map view↔logical via a scale-to-fit letterbox transform
  computed in `onSizeChanged`). Finger events: return false (fall through to nothing).
- Draw: strokes as anti-aliased `Path`s, width from pen width × pressure curve
  (mirror layuv's). `Epd.selection(this)` per stroke-end; hardware ink from drawPath
  covers the in-stroke latency, our software render is the persistent record.
- drawPath lifecycle (all guarded by `DrawPathClient.available()` — must degrade to
  plain software ink on non-Supernote/emulator): on pad shown → `sendReset` +
  `setWritableAreas([pad rect on screen], flag 1)` + `sendPen`; on pad hidden/paused →
  `clearScreen` + `sendReset`; on stroke-end → `clearScreen` periodically is NOT
  needed (hardware ink persists until clear — clear on page turn instead, then our
  software render is what remains).
- Pages: `PadView` holds current page's strokes only; page turn = swap stroke list +
  `clearScreen` + `Epd.pageTurn(this)` (full-feeling refresh, ghosting reset).
- **Verify standalone**: temp dev entry (e.g. long-press ◷ or a temp hub button) to a
  full-screen PadView; human draws — checks latency, palm rejection, letterbox
  mapping, page turn. This is the riskiest phase (hidden service on OUR package);
  do it first and alone.

### Phase 2 — Split mode in `MainActivity`

- New `padMode` state + `padContainer` (PadView + a thin pad toolbar: page ◀ N ▶,
  pen/eraser, ＋page, export, close ✕) added to `root`.
- **Geometry in `updateEdgeInset()`**: when `padMode`, portrait → GeckoView bottom
  margin = half height (pad occupies lower half; keeps web-top/pad-bottom decision),
  landscape → left or right margin = half width per a new `padSide` pref
  (`right` default; "handedness" setting in the Address-bar/Layout pane later —
  ship pref-only first). Pad container gets the complementary LayoutParams. Chrome
  bar/domain strip stay anchored to the WEB pane's edge (portrait: chrome stays top
  if top, else sits at the split boundary — simplest correct rule: force chrome to
  the NON-pad edge while padMode is on).
- **Rails**: in padMode rebuild strips half-height (`addStrips` variant) on the web
  pane only; none on the pad side. Simplest v1: keep rails full config but clamp
  their layout to the web pane's bounds; hide the pad-side rail.
- **Rotation**: add `onConfigurationChanged` override — recompute split geometry +
  `PadView` letterbox (strokes rescale for free via the logical canvas). No
  recreate (would drop the Gecko session state churn).
- **Touch routing**: PadView consumes stylus; `root.onInterceptTouchEvent`'s
  chrome-dismiss gets a `pointInView(padContainer)` exemption so pad taps never
  dismiss/steal. drawPath writable rect = pad's on-screen rect, updated on layout.
- **Entry point**: the `notepad` (✎) `NavActions` entry from Phase 0 becomes
  functional and takes its default chrome-bar right1 slot. Opening padMode on a
  page creates (or reopens — see Phase 3 binding) the note for the current URL.
- **Verify**: adb-drive a page load, toggle padMode via the quick panel (human tap),
  eyeball both orientations, pen on pad + finger-scroll web simultaneously OK.

### Phase 3 — Storage: `NoteStore` + URL binding

- New `notes/NoteStore.kt`: files under `getFilesDir()/notes/<id>.json`; shape
  `{ id, url, title, created, updated, pages: [ { strokes: [ { w, pts:[x,y,p,...] } ] } ] }`
  (flat float arrays keep the JSON compact). Index = directory scan (cheap at this
  scale; no prefs blob). Autosave: debounced (~2s after last stroke) + on
  pause/page-turn/close — never lose ink on process death.
- Binding: opening notepad on a page finds the newest note with the same URL (reopen)
  else creates one bound to `currentUrl`/`currentTitle`. A note keeps its binding
  even if the web pane navigates away (the note remembers where it was born; v1 does
  not rebind).
- **Verify**: draw → kill app → reopen note: strokes back, page count right;
  `run-as` cat the JSON.

### Phase 4 — Hub: `NotesActivity`

- Mirror `HistoryActivity` wholesale: reached via a **list glyph on the pad
  toolbar** (NOT a chrome slot — ✎ there means pad-on-this-page — and NOT a
  hub button, the hub is settings-only now); paginated rows (bold title / grey url / relative time / page count), per-row ✕ +
  Clear-all via `confirmImpact`, `exported=false`.
- Row tap → relaunch `MainActivity` with `EXTRA_NAV_URL` = note's url **+ new
  `EXTRA_NOTE_ID`** → `onNewIntent`/`onCreate` loads the page AND opens padMode on
  that note.
- **Verify**: on-device round trip hub → note → draw → back to hub.

### Phase 5 — PDF export

- `android.graphics.pdf.PdfDocument` (built-in, Canvas-based — strokes are already
  Canvas paths; no new dependency). One PDF page per pad page at the logical canvas
  aspect; header line with note title + source URL + date. Flattened only in v1.
- Output to `getExternalFilesDir(DOWNLOADS)` (or MediaStore Downloads) +
  fire `ACTION_SEND` share sheet from the pad toolbar's export button.
- v2 (parked): PDF vector `Ink` annotations for strokes-preserved export.
- **Verify**: export a 2-page note, pull the PDF via adb, open on the Mac.

## Open decisions (ask the user during the build, not blockers)

1. `padSide` handedness default (plan says right) + where its setting lives.
2. Eraser v1: stroke-eraser (delete whole stroke on touch) is much simpler than
   pixel erase and matches layuv — proposed default.
3. Does closing padMode keep the note listed even if empty? (Proposed: discard
   empty notes silently.)
4. Chrome-position interaction: force chrome to the non-pad edge in padMode (plan's
   rule) — confirm it feels right on the Nomad.

## Risks

- **drawPath under our package**: layuv confirmed the service works, but writable-area
  rects + our GeckoView SurfaceView layering is untested — hardware ink might paint
  OVER the web pane if rects are wrong. Phase 1's standalone harness exists to
  de-risk exactly this before any browser integration.
- **SurfaceView + half-screen resize**: GeckoView in a SurfaceView may flicker/full-
  refresh on resize; acceptable on e-ink (one full clear on mode toggle is fine).
- **IME vs padMode**: address-bar IME lift while split is on — simplest rule: opening
  chrome/IME temporarily overlays as today; pad ignores IME insets entirely.

## Verification gates

Per phase: `./gradlew :app:assembleDebug` clean + on-device check listed above +
Opus/Fable diff gate before each commit. Human drives all pen input (synthetic
stylus events don't reach drawPath).
