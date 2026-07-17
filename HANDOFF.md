# HANDOFF

Threads: `phase1` (Distesa Phase-1 UI/media/settings + naming)

---

## Thread: phase1
_Updated 2026-07-17 (session j)_

### Next session — paste this to start

> Resume Distesa, thread **phase1** (repo `~/Develop/Distesa`, branch `main`, tip
> `4772a2d` — **committed locally, NOT pushed**; `040aa2b` under it). Session j built the
> big new feature: a full-screen WYSIWYG **layout editor** (`LayoutActivity`) that
> replaces the abstract slot cycle-rows — you tap a faithful preview of the browser
> (rails, corner sliver slots, address bar) and a centred pane configures that element.
> Also landed: **natural scroll** (default on; interface-level zone→action swap in
> `addStrip`, `flipPage` untouched), **configurable chrome-bar slots** (`[left] url
> [r1][r2] ⚙`, gear fixed) with per-element panes, a contextual **→ Go** button, a
> **search picker incl. Custom** template (`%s`), **auto-focus now default OFF**, a
> shared **`NavActions`** catalog (one source of truth for slot functions), and a
> paginated **? help** (Layuv-style, one category per page). Verified on the Nomad.
> Read `HANDOFF.md` → `## Thread: phase1` (session j) and the `handoff_phase1` memory;
> also read `~/Develop/supernote-dev-reference/README.md` AND `Epd.kt`'s "prior
> approaches tried and abandoned" notes before any Supernote/refresh work. Devices: Nomad
> `SN078C10005528` @ `100.67.164.61:5555` (also USB), Manta `SN100C10008955` @
> `100.98.2.91:5555`; adb at `~/Library/Android/sdk/platform-tools/adb`; package
> `com.afluffywaffle.distesa.dev`; build `./gradlew assembleDebug` then `adb -s
> SN078C10005528 install -r app/build/outputs/apk/debug/app-debug.apk` (installDebug
> throws `EOF` with both USB+Tailscale entries attached; `LayoutActivity` is
> `exported=false` so `am start` from shell is blocked — the user navigates to it).
> First task: **push `4772a2d`**; then decide: (a) drop the now-redundant "Search engine"
> cycle row in `SettingsActivity` (search lives in the editor now); (b) the parked
> big idea — **make the editor the main settings page**, with links out to other settings.

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
- **Auto-focus address bar on chrome reveal (NEW backlog):** since chrome is hidden by
  default, tapping the chrome-reveal (sliver ☰) should focus the address field so the
  user can type immediately without a second tap. Especially important for top-anchored
  chrome. (Small change: `urlField.requestFocus()` + showSoftInput in the reveal path —
  but consider that the user may reveal chrome just to hit Back/⟳, so maybe only
  auto-focus on a dedicated "address" affordance, not every reveal.)
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
