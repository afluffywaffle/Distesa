# HANDOFF

Threads: `phase1` (Distesa Phase-1 UI/media/settings + naming)

---

## Thread: phase1
_Updated 2026-07-16 (session e)_

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
> `692ee11`, unpushed). Last session diagnosed + fixed the **nav-strip recreate lockout**
> (paging strips went dead after any settings toggle that calls `recreate()`, because
> `einkPort` reset to null and never re-bound) and committed the whole edge-nav bundle
> (capsule redesign + drawn globe icon + this lockout fix). Verified on the **Nomad**.
> Read `HANDOFF.md` → `## Thread: phase1` (sessions e + d) and the `handoff_phase1`
> memory; **also read `~/Develop/supernote-dev-reference/README.md` before any Supernote
> work.** Device: Nomad serial `SN078C10005528` over Tailscale `100.67.164.61:5555`
> (`hidden_api_policy` already 0); adb at `~/Library/Android/sdk/platform-tools/adb`;
> package `com.afluffywaffle.distesa.dev`; build `./gradlew installDebug`. First task —
> pick one of the two fresh **Sonnet-sized** backlog items: (1) **e-ink tap-feedback
> highlight** on the nav button (brief heavier OUTLINE on ACTION_DOWN in `EdgeNavView`,
> static stroke, no animation), or (2) **white outline on the globe sliver icon** in
> `GlobeSearchDrawable.kt` (match the magnifier's outline so the globe reads on dark
> backgrounds). Both are in the Open/next backlog list. Also parked: divider-length tune
> + whether the chrome-slot guard should persist to the saved pref. Consider pushing.
