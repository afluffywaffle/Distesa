// Root build for Achroma — a minimal e-ink web browser for the Supernote
// Nomad/Manta (RK3566, Android 11). Phase 0 Spike A evaluates GeckoView.
// Build config mirrors the known-good layuv android_native setup.
plugins {
    kotlin("android") version "2.1.0" apply false
    // AGP 8.13.x is the latest stable line, compatible with the Gradle 9.1 wrapper.
    id("com.android.application") version "8.13.2" apply false
}
