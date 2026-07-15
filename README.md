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
- Loads a deliberately ad/image-heavy page (`https://www.theverge.com`) so the
  ad-blocking effect is visible.
- Bundles our own **"eink" WebExtension** (`app/src/main/assets/extensions/eink/`)
  loaded at startup via `webExtensionController.installBuiltIn(...)`. It ports
  eita's "Page Scroll" quantized pagination and adapts it for e-ink: a content
  script gives **tap-to-flip** page turning (tap the left third = previous page,
  right third = next page, middle third ignored) using instant viewport jumps
  (`scrollTo` with `behavior:"auto"` — no smooth scroll, which ghosts on EPD).
  Because we author it, it ships inside the APK — unlike third-party add-ons.
- Installs uBlock Origin at **runtime from AMO** (not bundled in the APK). On
  first launch it calls `WebExtensionController.install(...)` with the AMO
  "latest" signed xpi:
  `https://addons.mozilla.org/firefox/downloads/latest/ublock-origin/latest.xpi`.
  A `PromptDelegate` auto-approves the install/permission prompts (test spike
  only). On later launches it detects the existing install via
  `webExtensionController.list()` (matching add-on id `uBlock0@raymondhill.net`)
  instead of re-installing. Network/install failures log and leave the app
  usable — they do not crash.
- Exposes a **minimal on-device toggle**: the GeckoView is wrapped in a
  `FrameLayout` with a small top-right button labelled `uBO: on` / `uBO: off`.
  Tapping it flips the add-on via `enable/disable(ext, EnableSource.USER)` and
  reloads the page so the ad-blocking effect appears/disappears. This is a
  standalone real add-on — updatable and toggleable, exactly like Firefox for
  Android — not a frozen bundled extension.
- Ports the layuv e-ink refresh modules into `eink/` (`RattaEink`, `Epd`,
  `EdgeNavView`), decoupled from the layuv reader. These are **not yet wired** to
  the Gecko surface — that is Phase 1 (see the TODOs in those files).

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
| App launches, theverge.com renders | |
| Idle RAM (MB) | |
| Cold start (ms) | |
| Page load — theverge.com (s) | |
| eink extension loads (logcat: "eink extension installed") | |
| Tap right third → next page (instant jump, clean refresh) | |
| Tap left third → previous page | |
| Tap middle third → no page flip (links still work) | |
| uBlock installs from AMO (logcat: "uBlock installed") | |
| `uBO: on/off` toggle changes ad blocking after reload | |
| Refresh / ghosting quality on page flip (subjective) | |

## Build config (mirrors layuv android_native, known-good)

- Gradle wrapper 9.1.0, AGP 8.13.2, Kotlin 2.1.0
- compileSdk 36, minSdk 30, targetSdk 36, Java 17
- `app/build.gradle.kts` forces `kotlin-stdlib:2.1.0` — a transitive AndroidX
  dependency otherwise pulls a newer stdlib whose metadata the pinned Kotlin
  2.1.0 compiler cannot read.
