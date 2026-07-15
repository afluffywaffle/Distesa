// Achroma :app — the GeckoView e-ink browser spike. Classic Views (NOT Compose):
// a full-screen GeckoView backed by a singleton GeckoRuntime + GeckoSession.
//
// Repositories are NOT declared here: the settings.gradle.kts
// dependencyResolutionManagement block (mavenCentral + google + maven.mozilla.org)
// supplies them. A module-level repositories{} block would, under Gradle's default
// PREFER_PROJECT mode, REPLACE those for this module.
plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.afluffypancake.achroma"
    compileSdk = 36

    defaultConfig {
        // .dev suffix so this spike installs ALONGSIDE other apps on the device.
        applicationId = "com.afluffypancake.achroma.dev"
        minSdk = 30 // Nomad/Manta are Android 11.
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// A transitive dependency (androidx) pulls a newer kotlin-stdlib than the pinned
// Kotlin 2.1.0 compiler can read its metadata; force the stdlib to match.
configurations.all {
    resolutionStrategy {
        force("org.jetbrains.kotlin:kotlin-stdlib:2.1.0")
    }
}

dependencies {
    // GeckoView — Mozilla's Gecko engine as an embeddable Android view.
    // Latest stable release resolved from maven.mozilla.org (see README).
    implementation("org.mozilla.geckoview:geckoview:152.0.20260713164047")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.annotation:annotation:1.9.1")
}
