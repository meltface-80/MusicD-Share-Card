plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

/*
 * ONE COPY OF THE PAGE, NOT TWO.
 *
 * The web app is `app/src/main/assets/web`, which the APK bundles as assets.
 * Copying it here would be a second card that drifts from the first, which is
 * the whole reason `sharecard.js` is a port rather than a rewrite. It goes on
 * this module's classpath instead, at exactly the same paths, so the Assets
 * lambda below is the same lookup the Android one does.
 */
sourceSets["main"].resources.srcDir("$rootDir/app/src/main/assets")

/*
 * THE VERSION IS READ OFF app/build.gradle.kts, with the same expression CI
 * uses to decide what to publish.
 *
 * There must be ONE number. A version declared here as well would be the one
 * the container reports and the one nobody remembers to bump — and the card
 * server prints it on /api/debug, which is how a bug report says which build it
 * came from.
 */
val shareCardVersion: String =
    Regex("""versionName = "([^"]+)"""")
        .find(file("$rootDir/app/build.gradle.kts").readText())
        ?.groupValues?.get(1)
        ?: "dev"

version = shareCardVersion

application {
    mainClass.set("com.musicd.sharecard.server.MainKt")
    // Baked into the generated start script, which is what the image runs.
    // SHARECARD_VERSION overrides it for anyone running the jar by hand.
    applicationDefaultJvmArgs = listOf("-Dsharecard.version=$shareCardVersion")
}

dependencies {
    implementation(project(":core"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    /*
     * A REAL DEPENDENCY HERE, unlike in :core.
     *
     * Android ships org.json in the platform, so :core declares it compileOnly
     * to keep a second copy out of the APK. There is no platform here: without
     * this line every parse in the app throws NoClassDefFoundError at runtime
     * and nothing at compile time says so.
     */
    implementation("org.json:json:20240303")
}
