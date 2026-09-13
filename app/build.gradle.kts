import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.musicd.sharecard.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.musicd.sharecard"
        minSdk = 26
        targetSdk = 36
        // Bumping versionName is what publishes a new APK into dist/ and
        // repoints the README at it — see the workflow. versionCode must rise
        // with it or Android refuses to install over the previous build.
        versionCode = 37
        versionName = "0.37.0"
    }

    buildFeatures {
        buildConfig = true
    }

    /**
     * The release key, and why it cannot be the debug one.
     *
     * Android refuses to install an APK over one signed with a different key,
     * and the debug keystore is generated per machine — a CI runner is fresh
     * every time, so every published build would carry a NEW certificate and
     * every "update" would be refused. That happened three times running in the
     * app this one is built from; it is not a hypothetical.
     *
     * So the key comes from the environment (a CI secret), and there is no
     * fallback that silently signs with something else: a release build with no
     * key configured is unsigned, which fails loudly at install time rather than
     * producing an APK that looks fine and can never be updated.
     */
    val keystorePath = System.getenv("SHARECARD_KEYSTORE")
    if (!keystorePath.isNullOrBlank()) {
        signingConfigs.create("release") {
            storeFile = file(keystorePath)
            storePassword = System.getenv("SHARECARD_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("SHARECARD_KEY_ALIAS") ?: "sharecard"
            keyPassword = System.getenv("SHARECARD_KEY_PASSWORD")
                ?: System.getenv("SHARECARD_KEYSTORE_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/*.kotlin_module",
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*"
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Everything that is not Android lives in :core, where it is unit-tested on
    // a plain JVM. This module is the shell: a WebView, a foreground service,
    // and the share sheet.
    implementation(project(":core"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // FileProvider only. Handing an image to a share sheet means handing over a
    // content:// URI, and that needs a provider.
    implementation("androidx.core:core:1.13.1")

    testImplementation("junit:junit:4.13.2")
    // The real org.json, ahead of android.jar's stub which throws on every call.
    testImplementation("org.json:json:20240303")
}
