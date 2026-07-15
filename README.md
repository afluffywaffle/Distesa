# Achroma

Achroma is a minimal, e-ink-first web browser for the Supernote Nomad and Manta
(RK3566, Android 11). It aims to be a fast, low-ghosting, ad-free reading browser
tuned for a monochrome EPD panel: no chrome clutter, greyscale-safe affordances,
edge-tap navigation, and a rendering path that drives the Supernote EPD refresh
waveforms directly.

## Engine decision — GeckoView (Phase 0 closed)

**GeckoView is the engine.** Phase 0 was scoped as a two-engine gate (Spike A
GeckoView vs Spike B WebView), but the decision was made without building Spike B:

- **GeckoView viability on the Nomad is already proven** — the user runs Fennec
  (Firefox for Android, a GeckoView app) on the device today.
- **Only GeckoView can run WebExtensions.** Android's WebView cannot load
  extensions at all, so uBlock Origin (real content blocking) and our own bundled
  page-flip extension are impossible on it — ad blocking there would be limited to
  crude request interception.

Spike B (WebView) was therefore **skipped**. We are now in **Phase 1**, building
on GeckoView.

### GeckoView version

Pinned: **`org.mozilla.geckoview:geckoview:152.0.20260713164047`** — the latest
stable release resolved from Mozilla's Maven repo
(`https://maven.mozilla.org/maven2/`). Note: GeckoView is published there, NOT to
Maven Central, so `settings.gradle.kts` adds that repository.

## What the app does

- Full-screen `GeckoView` backed by a singleton `GeckoRuntime` + `GeckoSession`
  (classic Views, not Compose).
- Loads a text-based, low-JS, reliably scrollable test page
  (`https://en.wikipedia.org/wiki/E_Ink`) so tap-to-flip and the flip → EPD
  refresh are easy to exercise; it still has infobox/thumbnail images for testing
  the image-policy modes.
- Bundles our own **"eink" WebExtension** (`app/src/main/assets/extensions/eink/`)
  loaded at startup via `webExtensionController.installBuiltIn(...)`. It ports
  eita's "Page Scroll" quantized pagination and adapts it for e-ink: `content.js`
  gives **tap-to-flip** page turning (tap the left third = previous page, right
  third = next page, middle third ignored) using instant viewport jumps
  (`scrollTo` with `behavior:"auto"` — no smooth scroll, which ghosts on EPD).
  Because we author it, it ships inside the APK — unlike third-party add-ons.
- Gates **images and video/media** per-domain, **blocked at the NETWORK layer** so
  bytes are never fetched until allowed (no "load then hide" race — the whole point
  on e-ink). `background.js` registers `webRequest.onBeforeRequest([blocking])` for
  request types `image`/`imageset`/`media` and cancels anything not on a per-tab
  allowlist (works because the bundled extension is privileged with `webRequest` +
  `webRequestBlocking` + `<all_urls>` — the same capability uBlock uses). `images.js`
  (content script, `document_start`; MutationObserver for late nodes) shows sized
  tap-to-load placeholders in their place. One combined **media policy**, cycled by
  the overlay button:
  - **hide-all** — everything blocked; tapping a placeholder dismisses it.
  - **placeholder-tap** (default) — everything blocked; tapping loads that one item
    (its URL is added to the allowlist, then the fetch is re-triggered).
  - **primary-content-only** — auto-loads heuristic "primary" **images** (video is
    never auto-primary); everything else stays a tap-to-load placeholder.
  - **load-all** — no-op; everything loads.

  **Allowlist coordination:** on tap (or for a primary image) `images.js` sends the
  media URL(s) to `background.js` over the relay port; the background adds them to
  that tab's allowlist and replies `allowed`, and only THEN does `images.js` restore
  the attributes to trigger the now-permitted fetch (ordering guarantees the block
  won't cancel it). The allowlist is per page-load (cleared on navigation); the
  policy persists. **"Primary" is chosen without loading** — attributed `width`/
  `height` or `getBoundingClientRect`, and `<main>`/`<article>`/`[role=main]`
  membership (never `naturalWidth`, since nothing is fetched): an `<img>` is primary
  if it's a sized/attribute-less content image inside main/article, or ≥ 200×200 px
  and ≥ 60% of the page's largest image. **Video** (`<video>`/`<source>`/`poster`)
  is network-blocked the same way. **Video-embed iframes** (YouTube/Vimeo/…) arrive
  as `sub_frame` (not covered by the media block), so they're gated DOM-side —
  `src` stripped to a "▶ tap to load" placeholder, swapped back in on tap.
  Placeholders are inline text boxes sized to attributed dimensions (no layout
  shift, no external assets). Policy persists in `browser.storage.local` keyed by
  `location.hostname`, default `placeholder-tap`. If `webRequest` blocking is
  unavailable it logs and falls back to the DOM-strip behaviour so the app still
  works.
- Installs uBlock Origin at **runtime from AMO** (not bundled in the APK). On
  first launch it calls `WebExtensionController.install(...)` with the AMO
  "latest" signed xpi:
  `https://addons.mozilla.org/firefox/downloads/latest/ublock-origin/latest.xpi`.
  A `PromptDelegate` auto-approves the install/permission prompts (test spike
  only). On later launches it detects the existing install via
  `webExtensionController.list()` (matching add-on id `uBlock0@raymondhill.net`)
  instead of re-installing. Network/install failures log and leave the app
  usable — they do not crash.
- Exposes two **minimal on-device overlay buttons** (top-right, stacked; no
  settings UI yet):
  - `uBO: on` / `uBO: off` — flips uBlock via `enable/disable(ext,
    EnableSource.USER)` and reloads, so the ad-blocking effect appears/disappears.
  - `IMG: <policy>` — cycles this domain's image policy
    (hide-all → tap → primary → all → …). Since native code can't write
    `browser.storage.local`, the button pushes `{type:"cyclePolicy"}` down the
    **eink port** (see below); `images.js` computes the next policy, persists it,
    and reloads. `images.js` reports its current policy back up the port so the
    button can label itself.

  **App↔extension messaging (GeckoView 152) — via a background relay:** GeckoView
  exposes native messaging (`connectNative`/`sendNativeMessage`, which reach the
  app's `MessageDelegate`) **only to background/extension pages, not content
  scripts** — a content script's `runtime.sendMessage`/`connect()` go to the
  extension's own background. So `background.js` is the bridge. The app registers a
  single `MessageDelegate` under the native-app name `"browser"`:
  - **Flip (ext→app):** `content.js` → `runtime.sendMessage({type:"flip"})` →
    `background.js` `onMessage` → `sendNativeMessage("browser", …)` → app
    `onMessage`.
  - **Image policy (app↔ext):** `background.js` opens `connectNative("browser")` →
    app `onConnect(port)`; `images.js` opens `runtime.connect()` to the background,
    which relays both ways — app pushes `{type:"cyclePolicy"}` via
    `port.postMessage`, `images.js` reports its current policy back up (received by
    the app's `PortDelegate`) to label the button.

  Requires the `nativeMessaging` + `geckoViewAddons` (+ `storage`) manifest
  permissions and the `background` entry.
- Ports the layuv e-ink refresh modules into `eink/` (`RattaEink`, `Epd`,
  `EdgeNavView`), decoupled from the layuv reader. `Epd` + `RattaEink` are now
  **wired to page flips**: on each flip `content.js` signals `{type:"flip"}` (via
  the `background.js` relay described above) and `MainActivity`'s `MessageDelegate`
  posts `Epd.pageTurn(geckoView)` onto the view, which forces a
  clean full-panel clear via `RattaEink.sendOneFullFrame` every `FULL_EVERY` (6)
  turns and lets the panel do a default partial update otherwise. `RattaEink`
  reflects into Supernote's hidden `EinkManager` and no-ops safely off-device.
  (`EdgeNavView` remains unwired — a later Phase 1 step.)

## Build

The Android Gradle Plugin does not support this machine's default JDK (26). Use
Android Studio's bundled JDK 17-compatible JBR:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug
```

(`gradle.properties` also pins `org.gradle.java.home` to the same path, so a bare
`./gradlew assembleDebug` works too.)

`local.properties` (pointing `sdk.dir` at `~/Library/Android/sdk`) is generated
locally and git-ignored — recreate it if cloning fresh.

## Install

```bash
~/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Application ID is `com.afluffypancake.achroma.dev` (the `.dev` suffix installs it
alongside other apps on the device).

On the device, the EPD reflection path requires hidden-API access:

```bash
adb shell settings put global hidden_api_policy 0
```

## On-device sanity checklist

With the debug APK installed on the Nomad/Manta, confirm each of these (record
notes/measurements as you go):

| Check | Result |
|---|---|
| App launches, en.wikipedia.org/wiki/E_Ink renders | |
| Idle RAM (MB) | |
| Cold start (ms) | |
| Page load — wiki E Ink (s) | |
| Flip fires native refresh (logcat: "flip -> partial refresh" / "EPD FULL CLEAR") | |
| eink extension loads (logcat: "eink extension installed") | |
| Tap right third → next page (instant jump, clean refresh) | |
| Tap left third → previous page | |
| Tap middle third → no page flip (links still work) | |
| uBlock installs from AMO (logcat: "uBlock installed") | |
| `uBO: on/off` toggle changes ad blocking after reload | |
| `IMG:` button shows current policy (default `tap`) once page loads | |
| Blocked images/video produce NO network fetch (logcat/devtools: request cancelled, not just hidden) | |
| logcat "[eink-bg] network media-block active" appears at startup | |
| Cycle `IMG:` → `hidden`: all images become "🖼 image hidden" boxes | |
| Cycle `IMG:` → `tap`: boxes say "tap to load"; tapping one triggers the fetch + loads | |
| Cycle `IMG:` → `primary`: main/large image loads, ads/thumbs stay placeholders | |
| Cycle `IMG:` → `all`: every image loads normally | |
| `<video>` blocked (no media fetch) until tapped; video-embed iframes show "▶ tap to load" | |
| Media policy persists per-domain across reloads/relaunch | |
| Refresh / ghosting quality on page flip (subjective) | |

### Debugging the media network-block (`adb logcat -s AchromaMain`)

The bundled extension's `console.log` does not reliably reach logcat, so
diagnostics are relayed to the native side and logged as `[eink-diag] …`:
- `granted perms=…` — the permissions GeckoView actually granted the bundled
  extension (proves whether `webRequest`/`webRequestBlocking` were honored).
- `caps: webRequest=… declarativeNetRequest=…` — which APIs exist at runtime.
- `onBeforeRequest registered with [blocking] …` or `addListener THREW …` /
  `webRequest UNAVAILABLE …` — whether the blocking listener installed.
- `media requests seen=N cancelled=M` — whether the listener fires and how many
  requests it cancels (if `seen>0` but `cancelled=0` under a blocking policy, the
  listener fires but `{cancel:true}` is being ignored).

The runtime is created with `consoleOutput(true)` (routes JS console to logcat)
and `extensionsProcessEnabled(false)` (runs the extension in the main process, so
blocking webRequest is honored synchronously rather than downgraded to
observe-only).

## Build config (mirrors layuv android_native, known-good)

- Gradle wrapper 9.1.0, AGP 8.13.2, Kotlin 2.1.0
- compileSdk 36, minSdk 30, targetSdk 36, Java 17
- `app/build.gradle.kts` forces `kotlin-stdlib:2.1.0` — a transitive AndroidX
  dependency otherwise pulls a newer stdlib whose metadata the pinned Kotlin
  2.1.0 compiler cannot read.
