# HANDOFF

Threads: `phase1` (Distesa Phase-1 UI/media/settings + naming)

---

## Thread: phase1
_Updated 2026-07-15_

**Project:** Distesa — minimal e-ink web browser for Supernote Nomad/Manta (GeckoView).
Formerly "Achroma". Local path `~/Develop/Achroma` (folder NOT renamed). Public repo:
**https://github.com/afluffywaffle/Distesa**. HEAD `9d57737`.

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
> Resume **Distesa**, thread **phase1** (repo ~/Develop/Achroma, HEAD `9d57737`). Last
> session shipped edge-sliver chrome buttons, an IME-aware address bar, a GDPR banner-tap
> fix, e-ink UI polish, and safe security/perf fixes — all committed but **not yet
> verified on-device** (paused to change locations). Read `HANDOFF.md` → `## Thread:
> phase1` and the `handoff_phase1` memory. First task: **on-device verification**. Start
> on the **Manta** (USB serial `SN100C10008955`, wifi `192.168.12.185:5555`; needs `adb
> shell settings put global hidden_api_policy 0`) — but note the **IME-lift fix needs the
> Nomad** (Manta is >9" so chrome anchors top and the fix is a no-op there): on Nomad,
> type in the address bar and confirm `[eink-ime] inset=<non-zero>` in logcat AND the bar
> sits just above the panel; if inset logs 0, fall back to top-relocation. Also verify:
> banner tap doesn't drop, settings Done + flat surfaces + ☑/☐ rows, edge-slot config.
