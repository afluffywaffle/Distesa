# HANDOFF

Threads: `phase1` (Distesa Phase-1 UI/media/settings + naming)

---

## Thread: phase1
_Updated 2026-07-15_

**Project:** Distesa — minimal e-ink web browser for Supernote Nomad/Manta (GeckoView).
Formerly "Achroma". Local path `~/Develop/Achroma` (folder NOT renamed). Public repo:
**https://github.com/afluffywaffle/Distesa**. HEAD `efe5e70`.

### What this session did & why
Polished Phase-1 UX on real hardware (Nomad over wifi `adb connect 100.67.164.61:5555`,
pkg now `com.afluffypancake.distesa.dev`), then did trademark clearance and renamed the app.

Shipped, in order (all committed + pushed, all verified on-device unless noted):
1. **Fixed dark UI** — light inset paging margins (root bg white); transparent toolbar
   buttons (were dark-on-dark, unreadable). Hard rule: ALL chrome light, NO animations.
2. **Collapsible placeholders** — hidden media renders as tiny inline chips (not big boxes);
   page-level `⊞`/`⊟` toggle in chrome. Later auto-driven (see settings).
3. **Chrome viewport shift** — when the (bottom) chrome shows, the GeckoView insets by the
   bar height so fixed consent/GDPR banners aren't covered → tappable.
4. **Bordered settings panel** — was borderless (the layuv problem); now stroke+radius.
5. **Video autoplay guard** — overrides `HTMLMediaElement.play` at document_start so JS/MSE
   players can't autoplay (network `media` block misses XHR/blob segments). Tapping a video
   placeholder marks its element allowed.
6. **Social-embed gating** — X/Twitter, IG, FB, TikTok, Reddit embeds → "Tap to load post"
   placeholder (ETP blocks tracker *requests* but doesn't hide embed iframes).
7. **Settings architecture** (delegated) — new `SettingsActivity` (full settings) + trimmed
   quick panel (Toolbar pos, Animations, Images, Zap, "More settings…"). Auto-collapse mode
   {always,never,auto} default **auto**, threshold **6**. `collapseBtnPlacement` pref scaffolded.
8. **Element zapper** — quick-panel "⬡ Zap element": arms picker, next page tap hides that
   element `display:none!important`, persists an nth-of-type selector per host (`_hide::<host>`),
   re-applied on load + via MutationObserver. The only way to kill inline `<div>` JS players
   (e.g. Newsweek `VideoContentHub`) with no src to match. **NOT yet tested on-device.**
9. **docs/tuning.md** — two tables (lever→loading-effect; lever→breakage-guard), linked in README.
10. **Rename Achroma→Distesa** — trademark clearance came back **RED** (≥2 live Class-9 apps
    named exactly "Achroma": Realm Runner card game + "Achroma Lens"; all good domains taken;
    "-chroma" space policed by Archroma/EnChroma). User picked **Distesa** (Italian "an expanse")
    over Liath. Renamed repo + package `com.afluffypancake.distesa` + applicationId `.distesa.dev`
    + extension id `eink@distesa` + label + docs/comments.

### Key decisions
- **Achroma abandoned (RED clearance)** → Distesa. Clearance was PRELIMINARY (USPTO/EUIPO block
  automated access) — attorney search still advised before filing/store release.
- **Auto-collapse default = auto/6** so text pages stay boxed, media-heavy pages collapse.
- **Zapper via content-script picker, not native context menu** — GeckoView's onContextMenu
  can't target a src-less `<div>` player; arm-then-tap + per-site persist chosen by user.
- Video shell "still renders" after autoplay-kill is expected: it's empty CSS furniture (no
  media loads); the zapper is the tool to remove it.

### Files changed (high level)
- `app/src/main/kotlin/.../distesa/MainActivity.kt` — chrome inset, transparent buttons,
  collapse button, onArmZap, panel border, onResume settings apply (agent), rename.
- `app/src/main/kotlin/.../distesa/SettingsActivity.kt` — NEW (full settings page).
- `app/src/main/assets/extensions/eink/images.js` — collapse mode, autoplay guard, social
  embeds, element zapper, auto-collapse.
- `AndroidManifest.xml`, `*.gradle.kts` — SettingsActivity, rename (namespace/applicationId).
- `README.md`, `docs/tuning.md` — NEW tuning doc + rename.

### Open / next steps
- **Test the zapper on-device** (never tried) — esp. Newsweek `VideoContentHub`. Report whether
  one tap grabs the right container or needs re-zapping → decide if "grab sensible container"
  smarts are needed.
- **Auto-collapse threshold feel** — is ~6 right?
- **`recreate()` flash** on structural settings (toolbar pos, nav style/side) — move to live
  re-layout if it bugs on e-ink.
- Optional: rename local folder `~/Develop/Achroma` → `Distesa` (left as-is; changes path).
- Deferred earlier: on-device verify of login-relax + icon-font exception on real login/FA sites;
  cold heavy-page benchmark.

### Gotchas
- Build with Android Studio JBR: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`
  then `./gradlew :app:assembleDebug -q`. System java 26 too new.
- Deploy over wifi is slow (~2min for ~540MB APK) — run installs in background.
- Old `com.afluffypancake.achroma.dev` was uninstalled; current pkg `com.afluffypancake.distesa.dev`.
- Background `console.log` invisible in logcat EXCEPT the extension's (consoleOutput(true) routes
  `[eink-*]` tags); native diags relayed as `[eink-diag]` under tag DistesaMain.
- zsh word-splitting: use `export ANDROID_SERIAL=...` + full-path `"$ADB"`.

### Next session — paste this to start
See the copy-paste block in the chat / below.
