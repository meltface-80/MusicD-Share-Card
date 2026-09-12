plugins {
    id("org.jetbrains.kotlin.jvm")
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

dependencies {
    // Pure-JVM, and equally happy inside an APK — the SOAP calls to the
    // players and the metadata lookups both go through it.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Android ships org.json in the platform, so it must NOT be packaged into
    // the APK. It is a compile-time dependency here and a real one only under
    // test, where there is no android.jar to provide it.
    compileOnly("org.json:json:20240303")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    // The update manifest may only name an https URL, and that rule is worth
    // testing against a real TLS server rather than switching it off for the
    // test. This provides the throwaway certificate MockWebServer serves with.
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
}

tasks.test {
    testLogging { events("passed", "failed", "skipped") }

    /*
     * THE SCANNED FILES ARE TEST INPUTS, and without this line they are not.
     *
     * Five tests here read source files from outside :core and assert things
     * about them — FilePickerContractTest, ParserHardeningTest, JsonSafeTest,
     * PageRefreshTest, DiagnosticsDrawnTest. They exist because the failures
     * they catch cannot be reproduced on a JVM: there is no WebView, no
     * browser and no Android runtime here.
     *
     * Gradle does not know they read anything. So editing app.js or
     * MainActivity.kt and running :core:test left the task UP-TO-DATE and the
     * scans simply did not run — green, having checked nothing. That was found
     * by breaking one on purpose and watching it pass, which is the only reason
     * it is not still true.
     */
    inputs.files(
        fileTree("$rootDir/app/src/main/assets/web") { include("**/*.js") },
        fileTree("$rootDir/app/src/main/java") { include("**/*.kt") }
    ).withPropertyName("scannedSources").withPathSensitivity(PathSensitivity.RELATIVE)
}
