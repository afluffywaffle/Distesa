# HANDOFF

Threads: `phase1` (Distesa Phase-1 UI/media/settings + naming)

---

## Thread: phase1
_Updated 2026-07-15 (session b)_

**Project:** Distesa — minimal e-ink web browser for Supernote Nomad/Manta (GeckoView).
Formerly "Achroma". Local path `~/Develop/Achroma` (folder NOT renamed). Public repo:
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
- Nomad pkg `com.afluffypancake.distesa.dev`. SettingsActivity is `exported=false` — can't
  `am start` it from adb; reach it via ⚙ quick panel → "More settings…".
- 7s chrome auto-hide makes screenshot-driven multi-tap navigation flaky; use uiautomator
  dump for exact button bounds when driving.
- Extension `log()` routes to logcat as `[eink-*]`; native diags as `[eink-diag]`/
  `[eink-ime]` under tag DistesaMain.

### Next session — paste this to start
> Resume **Distesa**, thread **phase1** (repo ~/Develop/Achroma, tip of branch
> `ime-input-fixes`, unpushed). Last session fixed the address-bar input chain on the
> **Manta** (verified on-device): edge-to-edge IME insets, showSoftInput under
> adjustNothing, robust Enter, an onLoadError e-ink error page, the hidden
> `getInputMethodWindowVisibleHeight()` lift so the handwriting autocomplete no longer
> covers the bar, floating-pane styling + resting edge-gap. Read `HANDOFF.md` → `##
> Thread: phase1` (session b) and the `handoff_phase1` memory; **also read
> `~/Develop/supernote-dev-reference/README.md` before any Supernote work.** Device: Manta
> serial `SN100C10008955` over Tailscale `100.98.2.91:5555` (`hidden_api_policy` already
> 0). First task: **verify the same IME flow on the Nomad** (bottom-chrome by default;
> confirm `getInputMethodWindowVisibleHeight` returns non-negative and the lift/pane look
> right), then decide whether to push/merge `ime-input-fixes`. Backlog (in the Open/next
> list): page-flip distance calibration (~75% + user setting), domain/title bar,
> auto-focus address bar on chrome reveal.
