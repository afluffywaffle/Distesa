pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
        // GeckoView is published to Mozilla's own Maven repo, NOT Maven Central.
        maven("https://maven.mozilla.org/maven2/")
    }
}

rootProject.name = "distesa"

// Single Android module — the GeckoView spike (Phase 0 Spike A).
include(":app")
