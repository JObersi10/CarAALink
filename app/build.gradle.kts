plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/** Short commit this build was made from -- for the in-app update checker to compare against. */
fun gitSha(): String = runCatching {
    providers.exec { commandLine("git", "rev-parse", "--short=10", "HEAD") }.standardOutput.asText.get().trim()
}.getOrDefault("unknown")

/**
 * Branch this build was made from, so the "dev build" update channel has a
 * sensible default without the owner having to type it in Settings.
 * `GITHUB_HEAD_REF` is what Actions sets for a `pull_request` build (where
 * `git rev-parse --abbrev-ref HEAD` would otherwise say the unhelpful
 * "HEAD", since PR checkouts land on a detached commit); plain pushes
 * (including to `aa-post-v1` itself) don't set it, so fall back to git.
 */
fun gitBranch(): String = System.getenv("GITHUB_HEAD_REF")?.takeIf { it.isNotBlank() } ?: runCatching {
    providers.exec { commandLine("git", "rev-parse", "--abbrev-ref", "HEAD") }.standardOutput.asText.get().trim()
}.getOrDefault("aa-post-v1")

android {
    namespace = "dev.caraalink"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.caraalink"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "GIT_SHA", "\"${gitSha()}\"")
        buildConfigField("String", "GIT_BRANCH", "\"${gitBranch()}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        // Committed on purpose (see .gitignore) so every debug build --
        // local, CI, anyone's machine -- signs with the same key. Without
        // this, each environment's auto-generated ~/.android/debug.keystore
        // differs, so swapping a locally-built APK for a CI-built one (or
        // vice versa) fails to install with INSTALL_FAILED_UPDATE_INCOMPATIBLE.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":protocol"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("com.google.android.material:material:1.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.11.0")
}
