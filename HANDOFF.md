# HANDOFF

Threads: `phase1` (Distesa Phase-1 UI/media/settings + naming) · `avosetta` (rebrand Distesa→Avosetta + Chloe mascot/icon)

---

## Thread: phase1
_Updated 2026-07-18 (session Q)_

### Session 2026-07-18 (Q): Clear-all now wipes Gecko browsing data — COMMITTED, device-VERIFIED
Implemented `PLAN_clear_browsing_data.md`. `HistoryActivity.kt` only: Clear-all's `confirmImpact` body now states the real impact (signed out of websites; caches/site data wiped); onAccept keeps `HistoryStore.clear` + `renderList()` then fires `MainActivity.sharedRuntime(this).storageController.clearData(StorageController.ClearFlags.ALL)` via two-arg `GeckoResult.accept` (Log.i success / Log.w failure, no crash). Per-row ✕ / HistoryStore untouched. Build clean; Opus code-gate PASS. **Verified on the Nomad** (httpbin cookie set → Clear all → logcat `history: gecko data cleared` + cookie gone on reload). **Autocomplete panel visually confirmed working by the user** — session-P pending item CLOSED. Still NOT pushed (`9013ad6` + this commit).

### Next session — paste this to start

> Resume Avosetta, thread **phase1** (repo `~/Develop/Distesa`, branch **`main`**). Session P
> (2026-07-18) shipped **browser HISTORY** — COMMITTED `9013ad6` on main, **NOT pushed**,
> data layer verified on the **Nomad** `SN078C10005528` @ `100.67.164.61:5555` (wifi). New
> **`HistoryStore.kt`** (newest-first visit log as ONE JSON blob in the `distesa_settings`
> prefs — no Room; dedup by URL, **7-day rolling retention** pruned on every read/write,
> 1000-entry backstop; collapses server-redirect hops so a redirect doesn't stack a dup);
> recording in `onLocationChange` (url, skips blank home) + `onTitleChange` (title backfill);
> new **`HistoryActivity.kt`** in the LayoutActivity hub (new **◷** button) — paginated
> newest-first, per-row ✕ + Clear-all, tap-a-row relaunches MainActivity with a `navUrl`
> extra (`CLEAR_TOP|SINGLE_TOP` → new **`onNewIntent`**) so it loads on the existing session;
> and **address-bar autocomplete** = a CUSTOM full-width `suggestBox` panel INSIDE the chrome
> wrapper (buildChromeBar now returns a vertical `col` of `[suggestBox][bar]` on bottom chrome
> so suggestions sit ABOVE the bar and ride the IME lift — the framework `AutoCompleteTextView`
> dropdown is unusable under `adjustNothing`: it drops straight behind the keyboard), driven
> by a `TextWatcher` → `updateSuggestions`. On-device verified via `run-as` prefs read-back:
> dedup + newest-first ordering + redirect-collapse + 7-day prune all confirmed. **STILL
> PENDING: a human visual eyeball of the autocomplete panel** (couldn't drive the IME — focus
> the address bar + type a letter, then screencap). **NEXT: discuss with the user — they have
> thoughts + another feature idea. Two things already on the table:** (1) **Raindrop /
> extensions-as-favorites** — user wants NO native favorites, use extensions instead; BUT the
> app installs AMO add-ons (`ensureUBlock`/`install(UBLOCK_XPI)`) yet surfaces **NO
> browserAction/pageAction popup at all** (confirmed: zero `setActionDelegate` /
> `WebExtension.Action` usage anywhere). So Raindrop would load but you can't log in / hit
> Save. To make it real, FIRST build a browser-action popup surface (a chrome button that
> lists installed extensions' actions via `WebExtension.setActionDelegate` + `WebExtension.Action`
> and renders the popup), THEN install Raindrop (AMO slug `raindropio`). (2) **TABS** (in the
> pipeline, approach undecided) — proposed: one `GeckoSession` per tab + a `TabManager` (list +
> current index), swap the visible one via `view.setSession`; a tab-OVERVIEW screen (hub-style
> list, NOT a persistent e-ink tab strip); handle `onNewSession` for `_blank`/`window.open`;
> cap live sessions for RAM. Sizable — Opus-plan it. Read `HANDOFF.md` → session P + the
> `handoff_phase1` memory; also `~/Develop/supernote-dev-reference/README.md` before Supernote
> work. adb `~/Library/Android/sdk/platform-tools/adb`; `adb connect 100.67.164.61:5555`;
> package `com.afluffywaffle.avosetta.dev` (launch `.../MainActivity`); build `./gradlew
> :app:assembleDebug` then `adb -s 100.67.164.61:5555 install -r
> app/build/outputs/apk/debug/app-debug.apk`. **Driving trick:** `am start -n <launch> -f
> 0x24000000 --es navUrl <URL>` fires `onNewIntent` + loads the URL + records a visit — the
> only reliable way to load a page since the IME swallows synthetic text. `LayoutActivity` /
> `HistoryActivity` are `exported=false`.

<details><summary>Prior kickoff (session O)</summary>

> Resume Avosetta, thread **phase1** (repo `~/Develop/Distesa`, branch **`main`**). Last
> session (O, 2026-07-18) was a small UI polish — the **Address bar settings pane is now
> two columns** (`showAddressBarPicker` in `LayoutActivity.kt`, COMMITTED `3af04d4`, main,
> **NOT pushed**): it was running to the bottom edge on e-ink, so it's split into a wider
> 620dp pane with **left** = Position / Focus / Domain strip focus, **right** = Search
> engines + Custom. The mechanism is a reusable **TWO-COLUMN PATTERN** documented in the
> KDoc on `openPane` (pass `wide = true`, add a horizontal `LinearLayout` with two
> weight-1 vertical columns, feed each column to `paneSubhead`/`paneRow`/`paneHelp`) — it's
> opt-in per pane, NOT automatic; every pane still has the `ScrollView` overflow fallback,
> and `showHelp` uses pagination instead. **NEXT FOCUS (user's pick): browser HISTORY.**
> Read `~/Develop/supernote-dev-reference/README.md` before any Supernote work. Nomad
> `SN078C10005528` @ `100.67.164.61:5555` (wifi); package `com.afluffywaffle.avosetta.dev`.

</details>

<details><summary>Prior kickoff (session N)</summary>

> Resume Avosetta, thread **phase1** (repo `~/Develop/Distesa`, branch **`main`**). Session N
> (2026-07-18, verified on the **Nomad** `SN078C10005528` @ `100.67.164.61:5555` over wifi)
> shipped the **domain/title bar** + **web-field IME lift**, all in `MainActivity.kt` /
> `LayoutActivity.kt` (COMMITTED — see git log tip; check `git status`/push state). Shipped:
> (1) a **thin domain/title strip** — bordered pill at the chrome edge, domain (bold, `www.`
> stripped) + page title (grey), single-line ellipsized; the resting state while chrome is
> hidden, hidden on the Chloe home + while full chrome is up; **tap = summon the address
> bar** (`buildDomainBar`/`updateDomainBar`/`domainBarText`, new `TitleContentDelegate` for
> the title); (2) an **auto-focus decoupler** — new "Domain strip focus" radio group
> (Follow / Always / Never, pref `domainFocusMode`) in the Address bar settings pane
> (`showAddressBarPicker` Focus section), resolved in the strip's tap; (3) the strip doubles
> as a **coarse e-ink progress bar** — a `ClipDrawable` fill layer of its background advanced
> by a TIMED CREEP (`domainCreep`, 0→80% in dp steps, 250ms delay / 400ms/step) with Gecko's
> real `onProgressChange` as a floor (`max(creep, real)`), plus a **⟳** prefix while loading;
> on page stop it **snaps to a full 100% bar and holds ~550ms** (`domainClear`) before clearing
> so completion registers — discrete steps, no continuous animation (which ghosts on EPD);
> (4) **web-page fields now rise above the Supernote IME** — the panel reports an ime() inset
> but NEVER resizes the window, so `ADJUST_RESIZE` is a no-op there. Fix: runtime soft-input
> switch (`setSoftInput` — `ADJUST_NOTHING` only while OUR `urlField` is focused so the custom
> chrome lift owns it, `ADJUST_RESIZE` otherwise) PLUS a **manual GeckoView shrink** by the
> reported inset (`webImeInsetPx`, folded into `updateEdgeInset`'s bottom margin via `maxOf`,
> set in `applyImeLift`'s non-our-field branch) so Gecko scrolls the focused element into the
> smaller viewport. Uses the device-reported inset → scales across screen sizes. NOTE:
> `applyChromeInset(shown)` was refactored → `updateEdgeInset()` (folds chrome bar / domain
> strip / web-IME insets into one). Read `HANDOFF.md` → session N + `handoff_phase1` memory;
> also `~/Develop/supernote-dev-reference/README.md` before Supernote work. adb at
> `~/Library/Android/sdk/platform-tools/adb`; package **`com.afluffywaffle.avosetta.dev`**
> (launch `com.afluffywaffle.avosetta.dev/com.afluffywaffle.avosetta.MainActivity`); build
> `./gradlew :app:assembleDebug` then `adb -s SN078C10005528 install -r
> app/build/outputs/apk/debug/app-debug.apk`. No VIEW intent filter + IME swallows synthetic
> text → **human must drive page loads/URL entry**. **Remaining UI/UX easy wins (user's call,
> in order):** (1) **globe sliver white outline** (`GlobeSearchDrawable.kt`, tiny), (2) **e-ink
> tap feedback on nav strip** (`EdgeNavView` static outline on ACTION_DOWN), (3) **auto-focus
> address on chrome reveal** (dedicated affordance only), (4) **page-flip distance calibration**
> (~75% + overlap %), (5) **quick-panel latency/feedback**. Bigger pieces after:
> **site-exceptions manager**, then the **security pillar**. Optional leftover: gate the removed
> `TEST_URL` dev auto-load behind `.dev`.

</details>

### Session 2026-07-18 (P): browser history — store + History page + address-bar autocomplete — COMMITTED `9013ad6` (main, NOT pushed)
Data layer verified on the **Nomad** (`SN078C10005528` @ `100.67.164.61:5555`, wifi) by reading prefs back via `run-as`. The app had NO history feature before (GeckoView session history is per-session only). Scoped with the user first: **capped JSON store in prefs** (not Room), **hub Activity** UI, autocomplete **included this pass** (user's call), and mid-session the user asked to **cap to 7 days**.
- **`HistoryStore.kt` (NEW).** Newest-first visit log as ONE JSON string under key `history` in the shared `distesa_settings` prefs — matches the all-SharedPreferences house style, no Room/SQLite. Entry `{u,t,ts}`. Dedup by URL (revisit bumps to front + refreshes ts). **7-day rolling retention** (`RETENTION_MS`) pruned inside `load()` on EVERY read/write (so expired entries also vanish from the History page/autocomplete without browsing) + a **1000-entry backstop** cap. **Redirect-hop collapse:** if the top entry is still untitled and <2.5s old when the next URL lands, it's dropped (a server redirect fires `onLocationChange` for both the hop and the landing — this stops it stacking a dup). All ops read-modify-write the whole blob (negligible at this cap/rate; stateless → every caller consistent).
- **Recording (`MainActivity`).** `onLocationChange` → `HistoryStore.record(url)` (skips `isBlankUrl`); `onTitleChange` → `HistoryStore.updateTitle(currentUrl, title)` (title arrives after the visit, fires multiple times). Both guarded on `::prefs.isInitialized`.
- **`HistoryActivity.kt` (NEW)** in the LayoutActivity hub via a new **◷** bordered button (clock glyph, B&W-safe; sits just left of `?`). Mirrors AccessibilityActivity house style. Paginated (`PAGE_SIZE=12`) newest-first, each row = bold title / grey url / relative time + a ✕ to forget it; Clear-all (confirm popup). Tapping a row calls `openUrl` → relaunches `MainActivity` with `putExtra(EXTRA_NAV_URL)` + `FLAG_ACTIVITY_CLEAR_TOP|SINGLE_TOP` then `finish()`. Registered `exported=false` in the manifest.
- **`onNewIntent` (NEW, `MainActivity`)** + a navUrl branch in `onCreate`: reads `EXTRA_NAV_URL` ("navUrl") and `session.loadUri`s it on the existing session (no recreate). Companion consts `EXTRA_NAV_URL`, `SUGGEST_LIMIT=6`.
- **Address-bar autocomplete = a CUSTOM inline panel, NOT the framework dropdown.** First attempt used `AutoCompleteTextView`; on-device it dropped the popup **straight behind the IME** (with `adjustNothing` the window never shrinks, so the framework thinks there's room below and never flips up) and it wasn't full-width — user flagged both. Fix: reverted `urlField` to `EditText` + a `TextWatcher`; `buildChromeBar()` now returns a **vertical `col` wrapper** that IS `chromeBar` (owns show/hide/lift/tap-dismiss) containing a new full-width **`suggestBox`** pill + the horizontal `bar` — `[suggestBox][bar]` on bottom chrome (suggestions ABOVE, rising away from the keyboard), `[bar][suggestBox]` on top chrome. `updateSuggestions(q)` fills it from `HistoryStore.query`; `hideSuggestions()` on blur / `showChrome`. Rides the existing IME lift for free. **Deleted `HistorySuggestAdapter.kt`** (the abandoned framework-dropdown adapter).
- **Files:** `HistoryStore.kt`(new), `HistoryActivity.kt`(new), `MainActivity.kt`, `LayoutActivity.kt`, `AndroidManifest.xml`. Build clean (only pre-existing deprecation warnings).
- **STILL PENDING:** a human **visual check of the suggestion panel** — I can't drive the IME (swallows synthetic text), so autocomplete's on-screen look is unconfirmed; the data layer + navUrl path ARE verified. **Driving trick discovered:** `am start -n <launch> -f 0x24000000 --es navUrl <URL>` fires `onNewIntent`, loads the URL, and records a visit — the reliable way to load a page from adb.
- **Discussed, not built (next session):** **Raindrop / extensions-as-favorites** blocked on a missing **browserAction popup surface** (app has ZERO `setActionDelegate`/`WebExtension.Action` — bookmarking extensions can't be logged into/triggered); **TABS** (one GeckoSession/tab + TabManager + overview screen + `onNewSession` + RAM cap) — user has more thoughts + another feature idea to raise.

### Session 2026-07-18 (O): Address-bar settings pane → two columns — COMMITTED `3af04d4` (main, NOT pushed)
Small UI polish, verified on the **Nomad** (`SN078C10005528` @ `100.67.164.61:5555`, wifi) via `adb screencap` (works for native panes).
- **Problem:** the Address bar picker (`showAddressBarPicker`) had grown a long single column — Position (3) + Search (6) + Focus (1) + Domain strip focus (help + 3) — so on e-ink it ran hard to the bottom edge ("NEVER FOCUS" pinned at the screen bottom).
- **Fix:** split into two columns in a wider pane. `openPane` gained a `wide` flag (620dp vs 360dp). `showAddressBarPicker` now builds a horizontal `LinearLayout` with two weight-1 vertical columns — **left:** Position, Focus, Domain strip focus; **right:** Search engines + Custom (+ the custom-URL `EditText`/note). Confirmed on-device: fits with headroom, no scroll.
- **Reusable pattern documented, NOT automated.** Added KDoc on `openPane` describing the two-column recipe (pass `wide = true`; add a horizontal `LinearLayout` of two `LayoutParams(0, WRAP, 1f)` columns; feed each column to `paneSubhead`/`paneRow`/`paneHelp` as their first arg). Where to split is a per-pane editorial call, so future crowded panes opt in the same way. The `ScrollView` in `openPane` remains the universal overflow fallback; `showHelp` uses pagination as the alternative to columns.
- **Files:** `LayoutActivity.kt` only (`openPane` + `showAddressBarPicker` + KDoc). No new prefs.
- **Note:** other panes are untouched — this does NOT auto-columnate as panes grow; each must opt in. Left column is the taller one (Domain-strip help text); user accepted the slight imbalance rather than moving Focus to the right.

### Session 2026-07-18 (N): domain/title bar + progress fill + web-field IME lift — COMMITTED `643ab56` (main, NOT pushed)
Verified on the **Nomad** (`SN078C10005528` @ `100.67.164.61:5555`, wifi). User-driven, iterative.
- **Thin domain/title strip** (`MainActivity`): bordered pill anchored to the chrome edge (same `edge`/inset-between-strips as the chrome bar), 12sp, domain **bold** (`www.` stripped) + title grey, single-line ellipsized. It's the resting state — shown only on a real page while chrome is hidden (`updateDomainBar`), hidden on the Chloe blank home + whenever full chrome is up. **Tap summons the chrome/address bar.** Title via a new minimal `TitleContentDelegate` (`onTitleChange` → `currentTitle`); `onLocationChange` clears the stale title so the new host doesn't briefly show the old title.
- **Auto-focus decoupler:** the strip's tap resolves focus through `domainFocusMode` (`follow`|`always`|`never`, default follow) — `follow` uses the existing `autoFocusOnReveal`. New "Domain strip focus" radio group in `LayoutActivity.showAddressBarPicker`'s Focus section.
- **Strip = coarse e-ink progress bar.** Background is a `LayerDrawable[pill, ClipDrawable fill]`; the fill's `level` (0–10000) reveals a light-grey band left→right. Gecko's `onProgressChange` is too coarse (0→~75→done = one flash), so a **timed creep** (`domainCreep`: +20% every 400ms after a 250ms delay, ceiling 80%) supplies steady motion, painted at `max(creep, real)` (`renderDomainProgress`). **⟳** prefix while loading (`domainLoading`); on `onPageStop` the fill **snaps to 100% and holds ~550ms** (`domainClear` runnable) before clearing, so completion registers instead of flashing away. Wired via `PerfProgressDelegate` (added `onPageStart`/`onProgressChange`/`onPageStop` UI hooks).
- **Web-field IME lift (the meaty fix).** Problem: web-page inputs stayed under the keyboard. Diagnosis via `dumpsys window`: our window went to `sim={adjust=resize}` AND the IME inset was dispatched (`ITYPE_IME mFrame=[0,1172]…`, ~700px), but the window **frame never shrank** — the Supernote reports the inset without resizing, so `ADJUST_RESIZE` does nothing and GeckoView has no smaller viewport. Fix = two parts: (a) **runtime `setSoftInput`** — `ADJUST_NOTHING` only while `urlField` is focused (custom chrome lift owns it), `ADJUST_RESIZE` otherwise; (b) **manual GeckoView shrink**: `applyImeLift`'s non-our-field branch sets `webImeInsetPx = imeInsetLast`, folded into `updateEdgeInset`'s bottom margin (`maxOf(barInset, webImeInsetPx)`), so we shrink the GeckoView ourselves and Gecko scrolls the focused element up. Full keyboard-height shrink (robust regardless of field position); device-reported inset → scales across panels.
- **Refactor:** `applyChromeInset(shown)` → **`updateEdgeInset()`** (single source folding chrome bar / domain strip / web-IME bottom insets). Callers in `showChrome`/`hideChrome`/`updateDomainBar`/`applyImeLift`.
- **Files:** `MainActivity.kt`, `LayoutActivity.kt`. New prefs: `domainFocusMode`.
- **Gotchas:** progress fill contrast `0xFFD2D2D2` (light so black text stays legible over it). `adb screencap` still unreliable for EPD/Gecko content — drove by logcat + `dumpsys window`/`input_method`.

### Session 2026-07-17 (M): e-ink accessibility levers + per-site "render as-is" + chrome tap-to-dismiss — COMMITTED + PUSHED (`198a171`, `origin/main`)
Verified on the **Manta** (Nomad unreachable over Tailscale all session). User-driven, iterative.
- **Why:** user asked whether we enforce e-ink accessibility (light backgrounds) — we enforced NOTHING before (dark-theme sites rendered muddy grey on the panel). Grew into a contrast feature, then a per-site "render as-is" escape hatch.
- **Accessibility levers (both default ON), new `AccessibilityActivity.kt`** reached via a bordered **◐** button in the `LayoutActivity` top-bar hub (between Extensions and `?`; ◐ = contrast glyph, B&W-safe vs the colour ♿ emoji):
> + per-site "render as-is"**, all committed+pushed and verified on the **Manta**
> (`SN100C10008955` @ `100.98.2.91:5555` — **Nomad was unreachable over Tailscale all
> session**). Shipped: (1) two contrast levers in a **new `AccessibilityActivity`** (◐ button
> in the `LayoutActivity` hub) — **Prefer light pages** (engine `preferredColorScheme=LIGHT`,
> `applyColorScheme`) + **Force black text on white** (content-script CSS in `images.js`,
> which **skips comic/manga readers** via `isMediaViewer()` so viewers like viz keep their
> letterbox); (2) **per-site "Render as-is"** escape hatch that bypasses EVERY e-ink lever for
> one host (`rawHosts` pref = source of truth; engine via `applyPerHostEngine` in
> `onLoadRequest`; content via effective `pushSettingsToExtension(host)` + `images.js`
> `_raw:HOST` mirror; **`background.js` `policyFor()` returns `load-all` for a raw host** so
> the network block stops cancelling images — that was the final bug), reachable from BOTH the
> ⚙ quick-panel button and the ◐ page; (3) **chrome idle timeout removed** → tap-off-chrome
> dismiss (`root.onInterceptTouchEvent`). Read `HANDOFF.md` → `## Thread: phase1` (session M)
> + the `handoff_phase1` memory; also read `~/Develop/supernote-dev-reference/README.md`
> before any Supernote work. adb at `~/Library/Android/sdk/platform-tools/adb`; package
> **`com.afluffywaffle.avosetta.dev`** (launch `com.afluffywaffle.avosetta.dev/com.afluffywaffle.avosetta.MainActivity`);
> build `./gradlew :app:assembleDebug` then `adb -s SN100C10008955 install -r
> app/build/outputs/apk/debug/app-debug.apk`. No VIEW intent filter + IME swallows synthetic
> text → **human must drive page loads/URL entry**. **First tasks (user's call — do the UI/UX
> easy wins first; see the "▶ CURRENT FOCUS" banner atop "Open / next"):** in order —
> (1) **domain/title bar** (decide pinned-top vs. with-address-bar anchoring first),
> (2) **globe sliver white outline** (`GlobeSearchDrawable.kt`, tiny), (3) **e-ink tap
> feedback on nav strip** (`EdgeNavView` static outline on ACTION_DOWN), (4) **auto-focus
> address on chrome reveal** (dedicated affordance only), (5) **page-flip distance calibration**
> (~75% + overlap %), (6) **quick-panel latency/feedback**. Bigger pieces after: **site-exceptions
> manager**, then the **security pillar**. Optional leftover: gate the removed `TEST_URL` dev
> auto-load behind `.dev`.

### Session 2026-07-17 (M): e-ink accessibility levers + per-site "render as-is" + chrome tap-to-dismiss — COMMITTED + PUSHED (`198a171`, `origin/main`)
Verified on the **Manta** (Nomad unreachable over Tailscale all session). User-driven, iterative.
- **Why:** user asked whether we enforce e-ink accessibility (light backgrounds) — we enforced NOTHING before (dark-theme sites rendered muddy grey on the panel). Grew into a contrast feature, then a per-site "render as-is" escape hatch.
- **Accessibility levers (both default ON), new `AccessibilityActivity.kt`** reached via a bordered **◐** button in the `LayoutActivity` top-bar hub (between Extensions and `?`; ◐ = contrast glyph, B&W-safe vs the colour ♿ emoji):
  - **Prefer light pages** — engine `sharedRuntime.settings.preferredColorScheme = COLOR_SCHEME_LIGHT` (`applyColorScheme()`, `COLOR_SCHEME_SYSTEM` when off). Plain toggle (can't break sites).
  - **Force black text on white** — content-script CSS injection in `images.js` (`applyForceLight`, mirrors `animOff`): `html,body{bg#fff;color#000}` + `*:not(img/picture/video/svg/canvas){…}`. `guardedToggle` with impact popup on ENABLING. **Skips comic/manga readers** via `isMediaViewer()` (any img/canvas/video with rect height ≥75% vh AND width ≥30% vw = a page image fit to height); re-checks on DOMContentLoaded/load/resize + setTimeout 0.6/1.8/3.5s (viz is an SPA, page img mounts after load). Found live on viz.com: force-white was repainting the reader's black letterbox → "big white side margins."
  - Prefs `preferLight`/`forceLight`; pushed via `pushSettingsToExtension` + mirrored to `storage.local`. Perf log appends `light=… contrast=…`.
- **Per-site "Render as-is" (bypass ALL e-ink levers for one host, global prefs untouched):**
  - `rawHosts` StringSet pref (native = source of truth). `applyPerHostEngine(host)` (replaces `applyEtpForHost`, called in `onLoadRequest`) → TP off, JS on, colour scheme system. `pushSettingsToExtension(targetHost)` now sends EFFECTIVE raw-aware values + a `raw` flag (blockFonts/animOff/forceLight false, collapseMode "never").
  - `images.js` mirrors per-host in `storage.local` key `_raw:HOST` (var `rawMode`), read at document_start in `start()` → policy=load-all, no gating/anim/forceLight. **`background.js` `policyFor()` returns `load-all` when `policyByHost["_raw:"+host]===true`** (policyByHost already mirrors all storage.local via `storage.onChanged`) — WITHOUT this the network block still cancelled every image even though placeholders were gone (the exact bug the user hit on Newsweek: `cancelled=10 allowed=0`).
  - Toggle flow: button → native updates pref + posts `{type:"setRaw",host,raw}` → `images.js` persists `_raw` THEN `location.reload()` (persist-before-reload avoids a doc_start race); engine applies on the reload's `onLoadRequest`. TWO entry points: quick-panel `rawBtn` ("Render as-is: ON/OFF/—", `toggleRawForCurrentHost`, label synced in `onLocationChange` via `updateRawBtn`) + ◐ page "Per-site" section (`rawRow(host)`, confirm-on-enable). MainActivity writes a `currentHost` pref each `onLocationChange` so the ◐ page has host context; `onResume` detects a raw change for currentHost → `postRawToExtension` + reload. Perf log appends `raw=<bool>`.
- **Chrome idle timeout REMOVED → tap-off-chrome dismiss.** `scheduleAutoHide()` is now a no-op shim (only `removeCallbacks`, no 7s post). `root` is an anon `FrameLayout` overriding `onInterceptTouchEvent`: while `chromeBar`/`settingsPanel` visible, first ACTION_DOWN off both → `hideChrome()` + consume (helper `pointInView`).
- **Files:** `AccessibilityActivity.kt`(new), `MainActivity.kt`, `LayoutActivity.kt`, `AndroidManifest.xml`, `assets/extensions/eink/images.js`, `assets/extensions/eink/background.js`. (`RenderingActivity.kt` net-zero — levers added then moved out.)
- **Three backlog items parked this session** (see "Open / next" below): quick-panel latency/no-feedback, site-exceptions manager, and the earlier "Images:" rename / 2-col picker calls (user chose accessibility instead).
- **Gotchas:** debug applicationId = `com.afluffywaffle.avosetta.dev` (namespace ≠ appId → `am start` needs the `.dev` pkg + full component). No VIEW intent filter → can't `am start` a URL; IME swallows synthetic text → human drives page loads. `adb screencap` unreliable for GeckoView/EPD content — drive by logcat (`[eink-perf]`, `[eink-diag]`, `[eink-images]`).

### Session 2026-07-17 (L): push/merge settings refactor + Chloe mascot (configurator watermark + blank-home overlay) — COMMITTED + PUSHED (`0acedcd`, `origin/main`)

Short session. Verified on the **Nomad** (`SN078C10005528`).

- **Push/merge (first task done).** Fast-forwarded `main` to the `settings-editor-refactor` tip (session-k work + a committed `HANDOFF.md`, `6925470`) and **pushed `origin/main`** — this also carried the 3 previously-unpushed rebrand commits. Deleted the merged local branch.
- **Chloe watermark in the configurator** (`LayoutActivity`). The `PreviewView` is stroke-only line-art, so an `ImageView` added to `root` before the `column` shows through the wireframe. `FIT_CENTER`, `alpha 0.15`, 24/80dp margins — kept faint so the gray wireframe stays legible on e-ink.
- **Chloe blank-home overlay** (`MainActivity`). GeckoView is opaque, so Chloe is an **overlay ABOVE the web view** (added right after `root.addView(view)`, before `addStrips`, so slivers + gear stay tappable over her), `FIT_CENTER` on a white backing. New `homeMascot` field + `setHomeVisible()`/`isBlankUrl()` helpers. **Startup changed: `session.loadUri(TEST_URL)` replaced by `setHomeVisible(true)`** — app now opens on the blank Chloe home. `onLocationChange` toggles her: hidden on a real URL, shown on blank/`about:blank`. `TEST_URL` const kept but now unused.
- **Asset:** `chloe_typing.png` copied from `~/Develop/Chloe/assets/stickers/` into `app/src/main/res/drawable-nodpi/`. User likes it as-is but flagged the **missing eyelash** — replace the file at the same path when Chloe repo ships a fix; no code change.
- **Decision flagged, not resolved:** starting blank removes the Wikipedia dev auto-load. Left blank-by-default (the empty state is only reachable that way); offered to gate the auto-load behind `.dev` — user hasn't decided.
- **Still open (unchanged from k):** the three UX calls above — none tackled this session; the user chose to stop after the mascot.

### Session 2026-07-17 (k): settings editor becomes the hub; Rendering/Supernote/Extensions pages — COMMITTED (`13c32c3` on branch `settings-editor-refactor`, not pushed)

Settings-refactor session, heavily iterated on-device with the user (many screenshots). All verified on the **Nomad** (`SN078C10005528`).

- **Layout editor is the main settings screen.** Chrome quick-panel "More settings…" now opens `LayoutActivity` (was `SettingsActivity`). The editor top bar gained three bordered buttons left of `?`: **⌗** (Rendering, text glyph), a **drawn Supernote device icon**, and a **drawn puzzle Extensions icon** — the 🧩/device emojis render in colour on e-ink, so they're B&W vector drawables (`ic_puzzle.xml`, `ic_supernote.xml`) tinted at runtime.
- **RenderingActivity (new).** Web-content levers: Block web fonts / Strict tracking protection / JavaScript / Animations off — each with a plain-language explanation, plus an "Impact at a glance" **stacked card list** (name + bold **Performance**/**Depends** lines; the old 3-column table compressed too hard at readable font sizes). The three site-affecting toggles show an **impact-confirmation popup** (custom scrim/pane, not AlertDialog) on the *restrictive* move (block fonts ON, strict-TP ON, JS OFF); re-enabling applies silently. Animations-off is a plain toggle (can't break sites).
- **SupernoteActivity (new).** E-ink device tuning: Images auto-collapse (mode + threshold) and E-ink refresh (full-clear), each explained. Split out because Rendering was overflowing.
- **SettingsActivity is now the Extensions page** (uBlock on/off only), retitled. Everything else moved out; dead vars/helpers/imports removed.
- **`NavActions` catalog extended.** Added `Spec.iconRes: Int?` (optional vector drawable, preferred over the text glyph). New actions: `rendering`/`supernote`/`extensions` (page launchers) + `zap`/`undozap`/`resetzap` (⬡ ↺ ⊘), all `SLIVER`+`CHROME`. Both `makeSliverButton`/`makeChromeButton` dispatch them; the editor `PreviewView` draws the icons (mirroring the existing `globe` pattern). `options()` shows label-only for icon actions.
- **Quick-settings panel trimmed.** Dropped toolbar-position (in the editor) and animations-off (now Rendering); kept **Images: …** (flagged in-code as ambiguously named / due for a rethink); zap/undo/reset fenced into a divider-grouped cluster (`panelDivider()`).
- **Rails picker** absorbed nav **Style** (inset/overlay = `navStyle`) and **Show tap zones** (`showZones`), removed from `SettingsActivity`; explanation fonts bumped (13→15f) across the pages.
- **Left uncommitted:** `HANDOFF.md` (avosetta-thread doc from the rebrand session) — intentionally not in commit `13c32c3`.
- **Open UX questions:** "Images: …" rename/behaviour; animations-off placement + whether it needs the impact prompt ("be careful since that's about full" — user's note was ambiguous); 2-column catalog picker at overflow.

### Session 2026-07-17 (j): visual layout editor + natural scroll + configurable chrome bar — COMMITTED (`040aa2b` + `4772a2d`, not pushed)

Big feature session, verified on the **Nomad** (`SN078C10005528`). Heavily iterated on-device with the user (many screenshots).

- **`LayoutActivity` (new) — WYSIWYG layout editor.** Full-screen, tap-to-configure preview that maps 1:1 onto the real layout (bottom-weighted rails at `ACTIVE_TOP=0.40`, corner sliver slots, address bar at resolved position). Tap an element → centred radio pane. Launched from `SettingsActivity` → Navigation → "Layout & buttons ›" (removed the 4 abstract slot cycle-rows + Nav-side row).
- **Natural scroll (default ON).** Interface-level inversion only: `addStrip` binds the rail's upper zone → `flipPage("next")` when natural, lower → "prev"; `flipPage`'s scroll mechanism is untouched (user insisted on this separation). Up-chevron advances the page (content travels the way the chevron points).
- **Rails pane.** Tap page/rail → Location (both/left/right/none) + Natural-scroll toggle, re-rendering in place. `none` → floating chrome button bottom-right (mirrors `addFloatingChromeButton`).
- **Edge slivers.** Tap a corner → function picker; empty (`none`) slots draw a tappable dashed placeholder (both caps always reserved in the preview).
- **Configurable chrome-bar slots.** `buildChromeBar` refactored to `[left] url [r1][r2] ⚙` reading `chromeBtnLeft/Right1/Right2` (defaults back/refresh/collapse) via `makeChromeButton`; ⚙ fixed (never removable → settings can't be stranded). Per-element panes (tap each button, or the url field for position+search). Contextual **→ Go** button (`goBtn`) shows on url-field focus for novices.
- **Search + Custom.** url-field pane has Position (auto/top/bottom) + engine list + **Custom…** (`customSearchUrl` template, `%s`=query; `submitUrl` uses it). **`autoFocusOnReveal` default flipped to false** (annoying while configuring; gold while browsing).
- **`NavActions` (new) — shared catalog.** One source of truth for slot functions (id/label/glyph/context + `menuGlyph` for toggling fns like collapse `⊟ / ⊞`). All pickers + preview glyphs pull from it; back glyph unified to `←` everywhere (was `‹` on the chrome bar).
- **? help.** Bordered icon button (top-right); paginated Layuv-style (each page one category — Buttons / Navigation rails / Address bar — no scroll, `i / N` + Prev/Next). Combo globe icon rendered inline in the Address-bar option via `ImageSpan`.

**Files:** `LayoutActivity.kt` (new, ~600 lines), `NavActions.kt` (new), `MainActivity.kt` (chrome-bar slots + Go + natural-scroll binding + custom search + auto-focus default), `SettingsActivity.kt` (launcher row, removed slot rows + dead code, defaults), `AndroidManifest.xml` (register `LayoutActivity`). Compiles clean; verified on-device.

**Open Qs:** push `4772a2d`; drop redundant SettingsActivity "Search engine" row?; future = editor as main settings page. **Preview note:** the editor shows the *configured* back slot even though the real bar hides Back when you can't go back (deliberate — editor shows assignments, not live state).

---

### Session 2026-07-17 (i): edge-nav capsule redesign polish + traditional back arrow — COMMITTED (`1244a74`, not pushed)

UI polish pass on the edge-nav rail, verified on the **Nomad** (`SN078C10005528`).

- **Fused single capsule.** The rail is now one continuous rounded capsule; the outline
  is drawn by `EdgeNavView` behind the buttons, with a floating divider hairline above
  the bottom cap.
- **Floating bottom button.** The bottom slot is a self-contained floating button with
  its own border (no longer sharing/butting the capsule wall).
- **Back glyph → traditional arrow.** Swapped the thin `‹` chevron for a leftwards arrow
  with a tail (`←`), sized 26sp + BOLD (vs 20sp for other slots) so it reads with the
  same weight as the drawn chevrons at e-ink contrast. `SettingsActivity` back label
  follows: `← Back`. (Intermediate `❮` was tried and rejected as still chevron-like.)

**Files:** `MainActivity.kt` (capsule span/draw, floating button border, back-slot glyph
+ per-slot 26sp/bold), `eink/EdgeNavView.kt` (capsule outline + divider), `SettingsActivity.kt`
(`← Back` label). Compiles clean; verified on-device (screenshots), defaults restored.

**Next:** push `1244a74`; then pick next backlog item with user.

---

### Session 2026-07-17 (h): Chrome-focus→Settings IME-dismiss bug — FIXED + PUSHED (`88f9a4d`)

Cleared backlog item 3 (logged session f). Fixed + verified on the **Nomad**
(`SN078C10005528`, also USB). `main` pushed — origin in sync.

**Bug:** with the address bar auto-focused (IME up), tapping the ⚙ gear showed the quick
settings panel *underneath* the still-present keyboard overlay — the IME never dismissed.

**Root cause (diagnosed via an Explore subagent over `MainActivity.kt`):** the gear
`setOnClickListener` was a pure `settingsPanel.visibility` toggle. It never cleared the
field's focus and never called `hideSoftInputFromWindow` — the focus-clear + IME-hide hook
lives only in `hideChrome()`, which the gear path skipped. Because focus was retained, the
blur branch (restore edge nav, drop the chrome lift) also never ran.

**Fix (`88f9a4d`, `MainActivity.kt`, +17/−2):**
- Extracted `dismissAddressIme()` — `urlField.clearFocus()` + `hideSoftInputFromWindow`,
  guarded by `::urlField.isInitialized`, safe to call when the IME is already down.
  `hideChrome()` now reuses it (was inline before).
- The gear handler computes `show = settingsPanel.visibility != VISIBLE` and calls
  `dismissAddressIme()` **before** showing the panel. Clearing focus fires `urlField`'s
  blur branch, which restores the edge nav and drops the IME lift for free.

**Verified on-device:** user confirms fixed — gear now dismisses the keyboard and shows the
quick panel unobscured. (Oracle used during dev: `dumpsys input_method | grep mInputShown`;
the native chrome/slivers are drawn on a separate overlay surface so they do NOT appear in
`uiautomator dump` — can't script taps on them, human drives the physical taps.)

**Build gotcha (new):** `./gradlew installDebug` failed with `InstallException: EOF` because
both a USB and a Tailscale entry for the **same** Nomad were attached (gradle tried to install
to both). Workaround: `adb -s SN078C10005528 install -r app/build/outputs/apk/debug/app-debug.apk`.
Compile itself was clean.

**Next:** session-e/f backlog is cleared — pick the next item with the user.

---

### Session 2026-07-16 (g): NATIVE scroll + native EPD refresh — COMMITTED + PUSHED (`8843643`)

Executed the Opus-planned spike: replaced the JS-extension-driven flip with native
scroll. Verified on the **Manta** (`SN100C10008955` @ `100.98.2.91:5555`). **User
confirms the flip is "really snappy."** `main` pushed — origin in sync.

**1. Native scroll (the core change).** `flipPage(dir)` no longer posts `navFlip` over
the eink port. It now scrolls the GeckoView directly:
`session.panZoomController.scrollBy(ScreenLength.zero(),
ScreenLength.fromVisualViewportHeight(±0.9), PanZoomController.SCROLL_BEHAVIOR_AUTO)`
(instant, no fling; viewport-fraction units are device-independent, and the 0.9 gives a
~10% overlap band — candidate for the parked overlap-% setting). Both WebExtension IPC
hops are eliminated (old path: native → einkPort(navFlip) → background.js → content.js
scroll → background.js(flip) → onFlip → Epd.pageTurn).

**2. Native EPD refresh via a debounced backstop.** `armScrollRefresh()` (48ms settle,
`SCROLL_SETTLE_MS`) is armed by `flipPage` after each `scrollBy`, coalescing into ONE
`Epd.pageTurn` per turn — logs the SAME oracle `flip -> partial refresh` /
`flip -> EPD FULL CLEAR`. A `ScrollDelegate` (`EinkScrollDelegate`) arms the same
refresh. **Finding:** `ScrollDelegate.onScrollChanged` does NOT fire for *programmatic*
`scrollBy` on GV152, so the **backstop is the primary driver** (fires unconditionally per
tap — also covers the silent edge-clamp at top/bottom of page); the delegate only adds
refresh on *finger* scrolls (a bonus the old path never had).

**3. Deleted the dead JS paging path.** Removed `content.js` + its `manifest.json`
content_scripts entry; stripped the `flip` relay + `onMessage` listener from
`background.js`; removed the `"flip"` case from `MainActivity`'s `FlipMessageDelegate`.
`einkPort` stays (images policy / settings levers); its doc comment de-navFlip'd.

**4. Release now arm64-v8a-only too.** Moved `ndk.abiFilters += "arm64-v8a"` from the
debug block up to `defaultConfig` in `app/build.gradle.kts` — Supernote is the only
target, so release drops 3 unused ABIs.

**Caveat held as predicted:** native scroll does NOT restore a per-tap strip flash.

**Verification notes / gotchas this session:**
- Tailscale DNS was flaky mid-session (`ping 1.1.1.1` OK but `example.com` = unknown
  host) → the Wikipedia `TEST_URL` blanked. Used a **DNS-free `data:` tall page** to
  prove scroll moves real content; restarting Tailscale fixed DNS. Reverted the temp
  `data:` harness + a temp scrollY log before committing (diff verified clean).
- User confirmed on-device: page scrolls up **and** down, the side scrollbar tracks, and
  **~4 taps reach page end** — real scrolling, not refresh-in-place.
- **Removed a stale `com.afluffypancake.distesa.dev`** build (pre-rename bundle id) still
  installed on the Manta.
- **Manta IME "jumping to top" is NOT a bug / not a regression:** `chromePos` is unset →
  `auto` → on the Manta (≥9") that resolves to **top chrome**, so the address bar sits at
  top and the IME-lift is a no-op (IME can't cover a top bar). The bottom-chrome IME-lift
  only exercises when `chromePos` is force-set to `bottom` (session b did this to test).
  User re-tested and confirmed it was on auto.

**Next:** Chrome-focused → tap-Settings IME-won't-dismiss bug (item 3 backlog).

---

### Session 2026-07-16 (f): edge-nav icon legibility + tap-feedback resolved + arm64 debug — COMMITTED (`8898c01`)

Cleared both fresh backlog items from session e, plus a build-size fix. All on the **Nomad**.

**1. Globe + chevron legibility (done).** `GlobeSearchDrawable`: gave the globe the same
white "moat" knock-out stroke the magnifier already had (moat circle/equator/meridian
drawn first, ink on top) so it reads on dark backgrounds. Added the same moat behind the
`EdgeNavView` chevrons. Confirmed on-device.

**2. Tap-feedback highlight — RESOLVED as "no transient flash" (design decision).** First
attempt (a heavier press outline + bolded chevron on ACTION_DOWN) was flaky: highlight
fired inconsistently, worse on fast taps, and got stuck-on. **Root cause (diagnosed on
Opus):** the strip is a **separate overlay surface**; on this panel a physical EPD refresh
is only reliably driven by an `invalidate()` on the **GeckoView compositor surface** (the
page flip: tap → `flipPage` posts `navFlip` to the eink WebExtension → content.js scrolls
→ posts `flip` → `onFlip` → `Epd.pageTurn` → `GeckoView.invalidate()`). An overlay-only
`invalidate()` for a press-and-revert flash has **no refresh of its own**, so its frame
only reaches the panel when a flip refresh happens to sweep the strip region — which
coalesces away on fast taps. Not a timing constant (three delay tweaks all failed).
**Decision:** drop the transient flash entirely; the always-on static affordance (capsule
+ chevrons + moat) marks the zones and rides the flip refresh for free. Removed all press
machinery (`pressedZone`, `pressedRailPaint`, `revertRunnable`, `ACK_REVERT_DELAY_MS`);
kept swipe-vs-tap detection. Confirmed good on-device.

**3. arm64-v8a-only debug builds.** `app/build.gradle.kts` debug block now sets
`ndk.abiFilters += "arm64-v8a"`. Supernote is the **only target** (all arm64, confirmed by
user). Cut the debug APK **515MB → 198MB** and sped up adb installs. Release still bundles
all ABIs — open question whether release should also go single-ABI (likely yes).

**New bug logged (not started):** when Chrome (address bar) is auto-focused and the user
taps Settings, the IME does not dismiss and it hides the quick panel. Likely the
settings-tap path isn't firing the same IME-dismiss/panel-restore hook normal nav does
(cf. commit `6f05e0b`).

**Next direction chosen by user:** native scroll + native EPD refresh to replace the
JS-extension-driven flip — for **latency** (JS round-trip per flip) and **flip
reliability**. Caveat captured: native scroll alone does NOT restore a per-tap strip flash
(the strip still has no refresh trigger; serial tap→feedback→scroll costs a 2nd EPD cycle
per turn). User wants a **scope + spike plan first (no code)**. This is the first task next
session.

---

### Session 2026-07-16 (e): nav-strip recreate lockout — DIAGNOSED, FIXED, COMMITTED (`692ee11`)

Symptom the user hit: **paging strips did nothing on tap; the sliver still worked.**
Triggered by toggling nav-strip config settings. Diagnosed on the **Nomad** via adb
(`input tap` + `screencap` + logcat) and fixed.

**Root cause:** any settings toggle that calls `recreate()` rebuilds `MainActivity`, but
`einkPort` was a PER-ACTIVITY field reset to `null` on recreate. The native `onConnect`
that sets it fires only from `background.js`'s ONE-TIME `connectNative` at startup — the
GeckoRuntime + extension + background page are a **process-wide singleton** that outlive
the Activity — so the rebuilt Activity never re-bound a port and `flipPage`'s
`einkPort ?: return` silently swallowed every tap. Sliver survived because it's pure
native UI (no port). Reproduced deterministically by forcing recreate via
`settings put system font_scale 1.15`.

**Fix (in `692ee11`):**
- `MainActivity`: `einkPort` → process-wide `@Volatile` companion field; `onCreate`
  force-disconnects any stale port so `background.js` self-heals with a fresh
  `connectNative` bound to the new Activity; `onDisconnect` clears only a matching port;
  `flipPage` now **logs** `flipPage: no eink port — dropped <dir>` instead of failing
  silently (this silence is what made it so hard to spot).
- `background.js`: reconnect the native port on disconnect (5-failure cap) + reopen on a
  content-script connect when null.
- **Verified on-device:** after a forced recreate a strip tap again SCROLLS + logs
  `flip -> partial refresh`; before the fix it was dead with zero logs. No lifecycle
  regression (`onDestroy` only closes the session; shared port guarded by "clear if same").

Diagnostic gotcha worth remembering: `screencap` reads the Android framebuffer, NOT the
physical EPD — a scroll can show in `screencap` while the panel looks frozen. Use the
`flip -> partial refresh` logcat line as the ground-truth oracle for "the flip fired".

**Two new backlog items added this session** (see the Open/next backlog list below):
e-ink tap-feedback "highlight" on the nav button; white outline on the globe sliver icon.

---

### Session 2026-07-16 (d): edge-nav capsule redesign + lockout fix — COMMITTED (in `692ee11`)

On-device on the **Nomad** (`SN078C10005528`; also Tailscale `100.67.164.61:5555`,
hidden_api_policy already 0). **Committed** as part of the `692ee11` bundle (with session e).

Done & verified on-device (screenshots):
1. **Edge nav → one continuous capsule.** `EdgeNavView` now draws a single rounded
   outline spanning the paging zone AND the bottom sliver cap (new `capReservePx`
   ctor param = sliver height). Internal boundaries (prev/next split + paging/sliver)
   are short CENTRED open-ended hairlines (`DIVIDER_FRAC`), never touching the walls.
   Old per-corner "two boxes butting" approach removed. Matches the user's PPT mockup.
   The sliver Button lost its own outline (transparent) — it's just icon + click over
   the cap; the capsule/divider are drawn by the strip behind it.
2. **Address sliver icon is DRAWN, not a glyph** — new `eink/GlobeSearchDrawable.kt`:
   a globe (circle+meridian+equator) with a magnifier laid diagonally OVER it and a
   white "moat" knocking out the globe lines under the lens (reads as in-front). Set as
   the chrome sliver's centred `foreground`. Renders crisp on-device. (Old `⌕` /
   `⊕⌕` glyph + `ADDRESS_GLYPH` const removed.)
3. **Sliver aligned to address row** — sliver lifted by `CHROME_EDGE_GAP` (bottomMargin)
   so its bottom shares the chrome pane's row (only visible when chrome shown w/o IME).
4. **Lockout guard fix** — the guard forcing a chrome sliver now evaluates against
   ENABLED rails only (`navPlacement`): a chrome slot on a rail turned OFF is invisible
   and locked the user out of settings. Now if no ACTIVE rail is chrome, force one that
   is. This was a live soft-lock (left rail off + right="collapse"); fix unlocked it.

**Open tweaks the user parked ("hold for later"):**
- Divider hairline length (currently `DIVIDER_FRAC=0.5` of inner width) — tune to taste.
- Guard currently overrides the stored slot at LOAD only, doesn't rewrite the saved
  pref — so re-enabling the left rail reverts the right to its saved "collapse". User
  may want the forced-chrome to PERSIST instead. (Decide + optionally write pref.)
- ~~Then: commit the whole bundle~~ — DONE, committed `692ee11` (session e).

Files touched (uncommitted): `eink/EdgeNavView.kt` (rewritten), `eink/GlobeSearchDrawable.kt`
(new), `MainActivity.kt` (addStrip capReserve/span, makeSliverButton drawn icon +
transparent bg, sliver bottomMargin align, load-time guard rewrite, imports).

---

### Session 2026-07-16 (c): security hardening + address-bar sliver nav

Shipped in one bundle (committed + pushed to `main`, `a69cab3`). All compiles; **none
verified on-device yet.**

**Security (SECURITY.md engine-surface items, all three ticked):**
1. **`DenyPermissionDelegate`** on the session — geo/cam/mic/notifications denied by
   default; media + Android permission requests rejected. (`MainActivity.kt`)
2. **HTTPS-only** — runtime built with `allowInsecureConnections(HTTPS_ONLY)`; Gecko
   shows its interstitial on plaintext instead of a silent http load. User can proceed
   per-site.
3. **Explicit Safe Browsing** — `setSafeBrowsing(DEFAULT)` set unconditionally in
   `applyTrackingProtection` so an ETP refactor can't drop malware/phishing blocking.

**Navigation / UI:**
4. **Auto-focus on reveal** — tapping the chrome sliver reveals the toolbar AND focuses
   the address field (`selectAll` so the first keystroke replaces the URL). Explicit
   taps only, never the idle-timer reveal. Setting **"Focus address bar on open"**
   (default on).
5. **Chrome sliver glyph → `⌕`** (magnifier) — reads as "type an address" now that the
   sliver is effectively the address button.
6. **Back / Refresh sliver slots** — edge slivers cycle ⌕ Address / ⊟ Collapse / ‹ Back
   / ⟳ Refresh / None, so a button-heavy user navigates without opening chrome.
7. **Fix:** `edgeSlot*` were missing from the `onResume` structural-change signature, so
   a slot change didn't rebuild the strips until app restart — now `recreate()`s live.

**⚠️ ON-DEVICE VERIFICATION PENDING (do first next session, ideally Nomad — bottom chrome):**
- **`⌕` renders** in the Supernote system font (BMP glyph, should be fine; easy swap in
  `ADDRESS_GLYPH` const if not).
- **Auto-focus feels right** on the Nomad: tap ⌕ sliver → toolbar reveals → keyboard up →
  bar lifts above it (the `liftChromeForIme` path). Confirm no jarring double-flash.
- **HTTPS-only interstitial** — hit a known http-only page, confirm the interstitial
  shows and "proceed" works (this is the one item with a visible behavior change).
- Also sanity-check Back/Refresh sliver slots actually go back / reload when configured.

**Still open (unchanged from session b):** consent dialog + `extensionsWebAPIEnabled`
(coupled, bigger lift), cert-error interstitial UI, release signing + R8/minify,
exported-components audit (quick), page-flip distance calibration, domain/title bar.

---

**Project:** Distesa — minimal e-ink web browser for Supernote Nomad/Manta (GeckoView).
Formerly "Achroma". Local path `~/Develop/Distesa` (folder renamed 2026-07-16). Public repo:
**https://github.com/afluffywaffle/Distesa**.

---

### Session 2026-07-15 (b): address-bar IME / input on Manta

Goal: verify the IME-lift on the Manta (10.7", top-chrome by default). Made chrome
position user-overridable and forced `chromePos=bottom` to exercise the bottom-chrome
IME path on the large screen. This surfaced a chain of real input bugs, all fixed and
**verified on-device (Manta, serial `SN100C10008955`, ADB over Tailscale `100.98.2.91:5555`)**.

**Fixed (all in `MainActivity.kt`):**
1. **IME insets never dispatched** → added `window.setDecorFitsSystemWindows(false)` in
   onCreate. Without edge-to-edge the decor view swallows `ime()` insets and the
   OnApplyWindowInsetsListener never fires (not even inset=0).
2. **Typing dropped** → `adjustNothing` suppresses the keyboard auto-show, so the served
   view stayed on the DecorView. Now explicitly `imm.showSoftInput(field)` on focus.
3. **Enter did nothing / just closed** → the Supernote keyboard reports Enter as GO,
   DONE/SEARCH, or a raw `KEYCODE_ENTER` depending on mode. Now accept all editor-action
   variants AND a separate `OnKeyListener` for raw ENTER.
4. **Silent about:blank on failure** → added `NavigationDelegate.onLoadError` returning a
   flat, high-contrast e-ink `data:` **error page** ("Page didn't load" + reason + host).
   (The about:blank we chased was actually a **Wi-Fi DNS failure**, not the app — see ref.)
5. **Address bar covered by handwriting autocomplete** → the crux. The public `ime()`
   inset UNDER-reports the IME window by the candidate-strip height (~175px; window top
   1570 vs inset top 1745 on Manta), and the strip appears with no inset event. Fixed
   with the **hidden `InputMethodManager.getInputMethodWindowVisibleHeight()`** (greylisted,
   works because `hidden_api_policy=0`) — returns the TRUE height (~990) incl. strip.
   **Polled every 200ms while focused** (no inset event when the strip appears); falls
   back to `ime()` inset + `dp(64)` reserve if the method is missing.
6. **Lifted bar looked "ripped off"** → styled the chrome/address bar as a bordered
   rounded **floating pane** (no elevation — e-ink halo rule), so the lift reads as
   deliberate.

**Toolbar position** is now user-overridable (quick panel: Auto/Top/Bottom; auto = top
on ≥9", bottom on <9"). This already existed; confirmed working.

**Cross-project deliverable:** consolidated ALL Supernote findings (this session +
layuv's EPD/pen) into **`~/Develop/supernote-dev-reference/README.md`** (visible folder,
backs up with `~/Develop`; NOT in hidden `.claude`). Device ID by serial, hidden_api
unlock, EPD `EinkManager` reflection, pen vs. text-IME, the full IME playbook, e-ink UI
rules, DNS diagnosis. **Read it first for any future Supernote work.**

**Open / next:**

**▶ CURRENT FOCUS (2026-07-17, user's call) — knock out the UI/UX easy wins first,** in
roughly this order (mostly self-contained view/drawable changes, no engine risk); full
detail for each is in the bullets below:
1. **Domain/title bar** — thin current-site strip (decide anchoring: pinned-top vs. with the address bar).
2. **Globe sliver icon white outline** — give the globe the magnifier's white halo so it survives on dark bg. (Small, isolated to `GlobeSearchDrawable.kt`.)
3. **E-ink tap feedback on nav strip** — static heavier outline on ACTION_DOWN in `EdgeNavView`.
4. **Auto-focus address bar on chrome reveal** — but only on a dedicated address affordance, not every reveal.
5. **Page-flip distance calibration** — ~75% advance + overlap %, needs on-device tuning.
6. **Quick-panel latency / no feedback** — optimistic label update; measure first.
Then the bigger pieces (**site-exceptions manager**), then the **security pillar**. The
older "Images:" rename / 2-col picker calls are lower — superseded/deferred. (Housekeeping:
"Hide edge nav bars while IME up" is DONE; animations-off placement is settled = plain
toggle in Rendering.)

- Verify on the **Nomad** (bottom-chrome by default — the IME poll + hidden-API should
  behave the same; confirm `getInputMethodWindowVisibleHeight` returns non-negative there).
- **Security: work down `SECURITY.md`** — expanded this session into three checklists
  (app-surface, engine-surface audit, zero-day/n-day posture). `allowBackup=false` done.
  Highest-leverage item = a **GeckoView update cadence**; real gaps found = **no content
  PermissionDelegate** (geo/cam/mic unhandled) and **no HTTPS-only mode**. A pillar, not
  urgent for dev builds, but do it down the list.
- **Page-flip distance calibration (NEW backlog):** the paging strip currently advances
  ~a FULL screen per push, which is jarring. Try advancing **~75% of the screen** (a
  25% overlap band) so the reader keeps context between flips. Needs on-device testing;
  likely wants a **user adjustment setting** (overlap %). Applies to the native
  EdgeNavView paging (see `EdgeNavView.kt` + the flip handler in `MainActivity.kt`).
- **E-ink tap feedback on nav strip (NEW backlog — Sonnet-sized):** the strip gives NO
  acknowledgement that a tap registered, which made the recreate/`einkPort` lockout bug
  (fixed 2026-07-16) very hard to notice. Want an e-ink-friendly "highlight": on
  ACTION_DOWN in an active zone, briefly draw a stronger OUTLINE around the tapped nav
  button (the prev/next chevron half of the capsule), then clear it. Must be a single
  static stroke (no animation/fade — e-ink), e.g. thicken/darken that half's border for
  one partial refresh. Implement in `EdgeNavView` (it already tracks down/up + zones and
  draws the capsule); trigger a targeted `invalidate()` on down, clear on up/after a short
  post. Keep it subtle — a momentary heavier outline, not a fill (fills ghost on EPD).
- **Globe sliver icon needs a white outline (NEW backlog — Sonnet-sized):** the globe glyph
  in `GlobeSearchDrawable.kt` has NO white halo/outline, so on dark page backgrounds it
  disappears. The magnifier part already carries a white outline and stays visible — give
  the globe the same white stroke/halo underneath so it reads on any background. Match the
  magnifier's outline treatment in the same drawable.
- **Hide edge nav bars while IME is up (DONE 2026-07-16):** the `EdgeNavView` paging
  strips + corner slivers now go `GONE` while the address field is focused and restore on
  blur, driven off the same focus signal as `liftChromeForIme` (`setEdgeNavHidden` in
  `MainActivity.kt`, hooked in `applyImeLift` + the focus-change blur branch). Verified
  on Nomad: chevrons/slivers vanish on focus, return on blur.
- **Domain/title bar (NEW backlog):** a thin bar showing the current site's name/URL
  (domain) so the user knows where they are. Open Q: persistently pinned on top, or
  attached to / revealed with the address bar? Decide the anchoring before building.
- **Quick-panel (⚙) feels slow / no feedback (NEW backlog, 2026-07-17 — analyze):** the
  quick-menu controls feel unresponsive — you tap and it looks like nothing happened,
  **especially the "Images:" cycle**. Root suspicion: these controls are round-trip +
  reload-driven, so there's a long gap before anything visibly changes. "Images:" →
  `onCycleImagePolicy()` posts `cyclePolicy` to the extension → images.js changes policy,
  persists, and `location.reload()`s; on e-ink the reload + EPD settle is seconds, and the
  button LABEL only updates when the content script reports the new policy back (see the
  `imgToggle.text = "Images: …"` message path in `MainActivity`), so there's no immediate
  acknowledgement of the tap. Same shape likely affects "Render as-is" (also reload-based).
  Wants: analyze the latency and add **immediate feedback** — e.g. optimistically update
  the button label on tap, and/or a brief "applying…" state, and/or a single static press
  affordance (note: transient e-ink press-flash was tried + rejected in session f — a full
  invalidate/refresh is needed, so lean on label/state change over a flash). Measure where
  the time actually goes (post → persist → reload → first paint → EPD) before optimizing.
- **Site-exceptions manager (NEW backlog, 2026-07-17 — biggish):** a page that surfaces
  every domain we hold a special exception for, so they can be reviewed/reconfigured in one
  place. Sources to aggregate (all per-host, already persisted): **`rawHosts`** ("render
  as-is", SharedPreferences set), **`loginHosts`** (ETP-relaxed / password-field seen,
  SharedPreferences set), **per-site image policy** (owned by images.js in
  `storage.local[HOST]` — hide-all/placeholder-tap/primary-content-only/load-all; NOT in
  SharedPreferences, so reading it needs a native↔extension query or a mirror), and
  **zapped elements** (per-host hide selectors in `storage.local`, key `HIDE_KEY`). Wants:
  (1) list of domains + which exception(s) each has; (2) **add current domain** to a list
  (e.g. "add this site to render-as-is") — the quick-panel/◐-page toggles already do raw,
  but this is the bulk/manage view; (3) **pagination** (the list can get long); (4) **sort
  alpha**; (5) **search/filter** by domain. Per-row: toggle/clear each exception, or remove
  the domain entirely. Design notes: image-policy + zaps live in extension storage, so the
  manager either (a) mirrors them into SharedPreferences on change, or (b) gets a native
  "dump all exception state" round-trip from the extension — decide before building. New
  Activity in the settings hub (its own top-bar button, or a row on the ◐ Accessibility
  page). E-ink house style (paginate, don't infinite-scroll; ☑/☐ rows; no dialogs).
- **Auto-focus address bar on chrome reveal (NEW backlog):** since chrome is hidden by
  default, tapping the chrome-reveal (sliver ☰) should focus the address field so the
  user can type immediately without a second tap. Especially important for top-anchored
  chrome. (Small change: `urlField.requestFocus()` + showSoftInput in the reveal path —
  but consider that the user may reveal chrome just to hit Back/⟳, so maybe only
  auto-focus on a dedicated "address" affordance, not every reveal.)
- **Rename Distesa → Avosetta + icon (NEW backlog, 2026-07-17):** app is being renamed
  from "Distesa" to **Avosetta** (the avocet). Mascot = **Klueta** (nickname **"Chloe"**),
  a clumsy-cute female chibi avocet; **Serilee** (pied kingfisher) is RESERVED for a future app. Full naming/icon
  progression + rationale saved at `~/Desktop/avosetta-progression/` (and in the
  `naming-avosetta` memory). Icon direction: minimal single-line e-ink mark (v4) as the
  app icon + chibi "Avo" as the marketing mascot. TODO: (1) rename in code — package
  `com.afluffywaffle.distesa` → avosetta, app label, strings; (2) finalize icon;
  (3) **easter egg** — hide the "how we got the name" story (Distesa → Avosetta, and the
  reserved Serilee bird) somewhere in the **Help/About** screen.
- **Credentials / password managers (NEW backlog):** stance = store nothing. Preferred
  path is a **WebExtension password manager** (Bitwarden) since Supernote has no
  configured autofill + no GMS (measured: `autofill_service` empty). **Blocker:** Distesa
  has no `browserAction`/popup surface, so an extension can't expose its unlock/pick UI —
  need a way to invoke extension popups. Also viable: native autofill IF user installs +
  selects a provider; passkeys/WebAuthn as the modern target. Full write-up in
  **`SECURITY.md`** (repo root), incl. the app-surface hardening checklist that folds in
  the session-(a) deferred security items.
- **Security pillar (tracked; next handoff, not top priority yet but non-negotiable —
  "browsers must be trusted"):** DONE this session → `allowBackup=false`. Remaining, in
  recommended order (see `SECURITY.md` checklist): (1) replace `AutoApprovePromptDelegate`
  with a **real consent dialog**, then (2) enable `extensionsWebAPIEnabled(true)` to give
  the Firefox-style "Add to Firefox" install flow from AMO inside Distesa — these two are
  ONE coupled piece and also deliver **user-installable extensions** (incl. a password-
  manager extension). Lower/again-later: cert-error specific messaging (GeckoView already
  never auto-bypasses cert errors — verify invariant), and **release signing + R8/minify**
  (currently release is debug-signed; distribution-time). Already fine: exported flags
  (launcher must be exported; SettingsActivity is not), ETP label-boundary matching.
- Deferred opt batch #2 and deferred security items from session (a) still stand (below).

---

### What this session did & why
Started by testing the element **zapper** on-device (the prior open task), then turned
into a UX/e-ink polish + bug-fix pass driven by live on-device use.

Shipped (committed; `88c87ba` zapper, `9d57737` the rest):
1. **Zapper undo/reset** — quick-panel "↺ Undo last zap" + "Reset zaps (this site)".
   Mutate the stored `_hide::<host>` list + reload (hides are only display:none, so a
   reload restores). **Zapper verified on Nomad**: one tap grabbed `#nw-video-player`
   by stable id; undo/reset both restored. (Newsweek VideoContentHub.)
2. **Edge-sliver buttons** — a button at the bottom of each nav strip (left=☰ Toolbar
   reveal, right=⊟ Collapse by default), always drawn even with tap-zones off. Paging
   zone shrinks above the sliver. Per-side configurable in Settings (chrome/collapse/
   none); guard forces ≥1 slot to chrome so the toolbar is never unreachable. Chrome
   bar inset by strip width so it never overlaps the corner slivers (☰ toggles closed).
3. **GDPR/consent banner tap fix** — removed the bottom-centre reveal strip AND
   tap-page-to-dismiss, so a page tap never un-insets the viewport and drops a lifted
   fixed banner out from under the finger. Chrome opens/closes only via sliver or idle.
4. **Address bar hidden by on-screen input** — `windowSoftInputMode=adjustNothing` + a
   window-insets listener lifts the bottom chrome bar by the LIVE IME height so it sits
   just above the handwriting/keyboard panel + its autocomplete strip. Runtime-measured
   → adapts to any keyboard/device, no hardcoding. (IME window on Nomad = [0,1021]–
   [1404,1872].)
5. **Settings dead-end fix** — "‹ Done" button (Activity has NoActionBar + hidden system
   back).
6. **E-ink UI** — boolean settings now render as ☑/☐ + ON/OFF text rows (not SwitchCompat,
   unreadable in grayscale); removed quick-panel drop shadow (elevation) + settings-page
   card border (flat surfaces; shadows read as grey halos on e-ink).
7. **Safe security+perf** (from 3 subagent audits) — dropped the boundary-less
   `endsWith()` in the ETP allowlist (look-alike host bypass); cached RattaEink's
   reflected `sendOneFullFrame` Method off the page-turn loop; disconnect the login
   MutationObserver once reported.

### Key decisions
- **Edge slivers over the old bottom-centre reveal** (user idea) — the centre reveal sat
  exactly where consent-banner buttons live; moving reveal to the gutters frees the
  centre AND kills the inset-fight. Dropped tap-to-dismiss entirely as a result.
- **IME handling = adjustNothing + insets, not adjustResize** — the Supernote input is a
  bottom-docked IME window; adjustResize didn't lift the bar (candidate strip popped
  over). Reading the ime() inset at runtime and lifting the bar by it is device-agnostic.
- **Reader-mode + ink is the future flagship** (design only, not built) — see the
  `project-distesa-readermode-ink` memory. layuv ingests **.docx** and uses **text
  anchors** (PS al Coda uses page coords); reader-mode article → docx → layuv annotate
  (text-anchored, survives reflow) → annotations stored in the docx. Broaden layuv
  ingestion to txt/rtf/md/epub(text) later.

### Files changed
- `MainActivity.kt` — edge slivers (addStrip/makeSliverButton, SLIVER_H, edgeSlot prefs
  + lockout guard), chrome bar inset by stripW, removed reveal strip + tap-to-dismiss +
  pointInPanel, `liftChromeForIme` + window-insets listener, ☑/☐ makeSwitch, allowlist
  endsWith fix. (MotionEvent import → WindowInsets.)
- `SettingsActivity.kt` — "‹ Done" button, ☑/☐ makeSwitch, edge-slot cycle rows
  (slotLabel/nextSlot), removed card border.
- `AndroidManifest.xml` — windowSoftInputMode adjustNothing (MainActivity).
- `eink/RattaEink.kt` — cache reflected Method.
- `assets/extensions/eink/images.js` — zapper undo/reset (undoZap/resetZaps) + login
  observer disconnect.

### Open / next steps (verify on MANTA next, then Nomad)
- **Manta ADB**: USB serial `SN100C10008955`, WiFi `192.168.12.185:5555` (per layuv
  `reference_supernote_device` memory). Needs `adb -s SN100C10008955 shell settings put
  global hidden_api_policy 0` for the EPD reflection. Build with Android Studio JBR.
- **GOTCHA — IME fix won't exercise on Manta**: Manta is 10.7" (>9") so `chromeAtBottom`
  is FALSE → chrome anchors TOP, and `liftChromeForIme` is a no-op (IME can't cover a top
  bar). So Manta validates the top-chrome case; the **IME-lift needs the NOMAD** (bottom
  chrome). On Nomad, type in the address bar and confirm `[eink-ime] inset=<non-zero>` in
  logcat AND the bar sits just above the panel. If inset logs 0, adjustNothing isn't
  dispatching ime insets → fall back to top-relocation (git shows the reverted approach).
- Still to verify anywhere: banner tap no longer drops; settings Done + flat surfaces +
  ☑/☐ rows + edge-slot config; slivers don't overlap chrome (confirmed via layout bounds
  on Nomad, re-check visually on Manta's top-chrome layout).
- **Deferred optimization batch #2** (safe-ish, needs retest): batch reads/writes in
  images.js gateAll (reflow storm), coalesce media-observer gateAll, gate onResume lever
  re-apply. **Higher-risk**: recreate()→live re-layout for showZones/navStyle, chrome
  inset overlay mode.
- **Deferred security** (not applied): AutoApprovePromptDelegate auto-grants add-on
  prompts; release signed with debug keystore + no minify; login-host auto-persist lets a
  page self-relax ETP; allowBackup=true.

### Gotchas
- Build: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`
  then `./gradlew :app:assembleDebug`. System java too new. ~540MB APK, wifi install ~2min.
- Delegate the Gradle build to a cheap subagent (returns pass/fail only) — keeps the
  compile log out of the main context.
- Nomad pkg `com.afluffywaffle.distesa.dev`. SettingsActivity is `exported=false` — can't
  `am start` it from adb; reach it via ⚙ quick panel → "More settings…".
- 7s chrome auto-hide makes screenshot-driven multi-tap navigation flaky; use uiautomator
  dump for exact button bounds when driving.
- Extension `log()` routes to logcat as `[eink-*]`; native diags as `[eink-diag]`/
  `[eink-ime]` under tag DistesaMain.

### Next session — paste this to start
> Resume **Distesa**, thread **phase1** (repo ~/Develop/Distesa, branch `main`, tip
> `8843643` — **pushed, origin in sync**). Last session (g) landed the Opus-planned
> **native scroll + native EPD refresh** spike, replacing the JS-extension flip:
> `flipPage` now scrolls the GeckoView directly via
> `PanZoomController.scrollBy(0, ±0.9 visual-viewport-height, SCROLL_BEHAVIOR_AUTO)` and a
> debounced `armScrollRefresh()` backstop drives `Epd.pageTurn` (same `flip -> partial
> refresh` oracle). The whole `navFlip`→`content.js`→`flip` round-trip is deleted
> (`content.js` gone). **Verified snappy on the Manta**; strip-flash caveat held. Release
> is now arm64-only too. Read `HANDOFF.md` → `## Thread: phase1` (session g) and the
> `handoff_phase1` memory; **also read `~/Develop/supernote-dev-reference/README.md` AND
> `Epd.kt`'s "prior approaches tried and abandoned" notes before any Supernote/refresh
> work.** Devices: Nomad `SN078C10005528` @ `100.67.164.61:5555`, Manta `SN100C10008955`
> @ `100.98.2.91:5555`; adb at `~/Library/Android/sdk/platform-tools/adb`; package
> `com.afluffywaffle.distesa.dev`; build `./gradlew installDebug`. **Next task:
> Chrome-focused → tap-Settings IME-won't-dismiss bug** (item 3 backlog) — when the
> address field is auto-focused and the user taps Settings, the IME doesn't dismiss and it
> hides the quick panel; likely the settings-tap path isn't firing the same
> IME-dismiss/panel-restore hook normal nav does (cf. `6f05e0b`). Also parked:
> divider-hairline length tune + chrome-slot-guard persistence. (Note: "IME jumps to top"
> on the Manta is NOT a bug — `chromePos=auto` → top chrome on ≥9"; IME-lift is bottom-only.)

---

## Thread: avosetta
_Updated 2026-07-17 (rebrand session)_

Renaming/rebranding the app **Distesa → Avosetta** (the avocet), plus a mascot character
**Chloe** that spun out into its own project.

### Done this session (all merged to `main`, tip `6e65d77`)
- **Name locked: Avosetta** (app; "Avosetta Browser"). Rejected many candidates on
  conflict/meaning grounds — notably **Setta** alone = "sect/cult" in Italian (so the app
  name must stay the full word "Avosetta", never shortened in branding). Full rationale +
  all rejects in the `handoff_avosetta` memory (aka `naming-avosetta`).
- **Mascot: Klueta, nickname "Chloe"** — a clumsy-cute female chibi avocet. Name from Dutch
  *Kluut* (the avocet's call) + a "klutz" wink.
- **Serilee** (pied kingfisher) — RESERVED for a future app; do not reuse here.
- **Code rename DONE + verified:** `com.afluffywaffle.distesa` → `com.afluffywaffle.avosetta`
  (dev `.avosetta.dev`), namespace, `android:label`, `git mv` of the whole source tree
  (incl. `eink/`), settings.gradle rootProject.name, README/SECURITY. Commits `5f88b4b`
  (rename) + `1bb1053` (icon), merged via `6e65d77`. Built green, installed + launched on the
  **Nomad** `SN078C10005528`, stale `distesa.dev` uninstalled from device.
- **App icon = Chloe** (chibi avocet). New `res/mipmap-*/ic_launcher(.|_round).png` at all
  densities + manifest `android:icon`/`roundIcon` (app previously had NO icon → default robot).
- **Chloe is now her own private repo:** `~/Develop/Chloe/` (git `main`, initial commit) with
  CLAUDE.md, HANDOFF.md, `assets/stickers/` (14 poses), `assets/reference/` (canonical v5),
  `tools/gen_stickers.py`, `.gitignore` (blocks the API key). NOT pushed to any remote.

### Icon/art workflow (reusable)
- Images generated via **OpenRouter** (user's credits), model `google/gemini-3-pro-image`,
  chat-completions + `"modalities":["image","text"]`, image returned as base64 data-URL in
  `choices[0].message.images[0].image_url.url`. Key read from a file, NEVER echoed/committed.
- **Consistency = image-to-image:** pass an existing PNG back in as `image_url` + an edit
  instruction (keeps the character; also does surgical edits). Chloe canon: **beak NOT a
  mouth**, B&W only, exactly one bird. See `~/Develop/Chloe/` (tools + CLAUDE.md).

### Left as-is / open
- Cosmetic leftovers (intentional): SharedPreferences key `"distesa_settings"`, log tags
  `DistesaMain`/etc., bundled WebExtension id `eink@distesa`. Rename later if desired.
- **Easter egg (not built):** hide the "how we got the name" story (Distesa→Avosetta + the
  reserved Serilee bird) in the **Help/About** screen. `04_avo_v3_reading_v5.png` /
  `chloe_reading` earmarked for the Help / layout-config screen.
- Soft/rounded **"avosetta" wordmark** — not started.
- Chloe: iMessage sticker pack on iOS App Store (needs transparent-bg exports); more poses;
  wire stickers to app states (sleepy=loading, oops=error, hmm=empty, hi=splash).
- Avosetta naming/icon asset history lives at `~/Desktop/avosetta-progression/`.

### Next session — paste this to start

> Resume the **Avosetta** project, thread **avosetta** (repo `~/Develop/Distesa`, branch
> `main`, tip `6e65d77`; sibling repo `~/Develop/Chloe`). The Distesa→Avosetta rename + Chloe
> launcher icon are merged and verified on the Nomad. Read `HANDOFF.md` → `## Thread: avosetta`
> and the `handoff_avosetta` memory first. Next up (pick one): build the **Help/About "how we
> got the name" easter egg** (Distesa→Avosetta + reserved Serilee), design the soft **"avosetta"
> wordmark**, or expand **Chloe** (more sticker poses / transparent-bg exports for an iMessage
> pack) in `~/Develop/Chloe`.
