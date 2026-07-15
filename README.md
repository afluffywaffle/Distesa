# Achroma

Achroma is a minimal, e-ink-first web browser for the Supernote Nomad and Manta
(RK3566, Android 11). It aims to be a fast, low-ghosting, ad-free reading browser
tuned for a monochrome EPD panel: no chrome clutter, greyscale-safe affordances,
edge-tap navigation, and a rendering path that drives the Supernote EPD refresh
waveforms directly. This repository is the Phase 0 spike.

## Phase 0 — the dual-engine gate

Phase 0 evaluates two browser engines side by side before committing to one:

- **Spike A — GeckoView** (this repo): Mozilla's Gecko engine, which supports
  real WebExtensions and therefore uBlock Origin as a true content blocker.
- **Spike B — Android WebView**: the OS-bundled engine (smaller, no extension
  support; ad blocking via request interception only).

The gate decision compares RAM footprint, cold start, page-load time, and — most
important on e-ink — refresh/ghosting quality. Fill in the metrics table below
from on-device runs of each spike, then pick the engine to build Phase 1 on.

### GeckoView version

Pinned: **`org.mozilla.geckoview:geckoview:152.0.20260713164047`** — the latest
stable release resolved from Mozilla's Maven repo
(`https://maven.mozilla.org/maven2/`). Note: GeckoView is published there, NOT to
Maven Central, so `settings.gradle.kts` adds that repository.

## What this spike does

- Full-screen `GeckoView` backed by a singleton `GeckoRuntime` + `GeckoSession`
  (classic Views, not Compose).
- Loads a deliberately ad/image-heavy page (`https://www.theverge.com`) so the
  ad-blocking effect is visible.
- Attempts to install uBlock Origin as a built-in WebExtension from
  `app/src/main/assets/extensions/ublock/`. **You must drop the uBlock Origin
  extension files into that folder** — see its `README.txt`. If absent, the app
  logs a warning and runs without blocking (it does not crash).
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

## Phase 0 metrics — fill in on-device

| Metric | GeckoView (Spike A) | WebView (Spike B) |
|---|---|---|
| Idle RAM (MB) | | |
| Cold start (ms) | | |
| Page load — theverge.com, no blocker (s) | | |
| Page load — with uBlock (s) | | |
| APK size (MB) | | |
| Refresh / ghosting quality (subjective) | | |
| uBlock / ad blocking supported | yes (WebExtension) | no (request-intercept only) |

## Build config (mirrors layuv android_native, known-good)

- Gradle wrapper 9.1.0, AGP 8.13.2, Kotlin 2.1.0
- compileSdk 36, minSdk 30, targetSdk 36, Java 17
- `app/build.gradle.kts` forces `kotlin-stdlib:2.1.0` — a transitive AndroidX
  dependency otherwise pulls a newer stdlib whose metadata the pinned Kotlin
  2.1.0 compiler cannot read.
