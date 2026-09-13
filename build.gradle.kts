/*
 * Plugin VERSIONS are declared in settings.gradle.kts, under
 * pluginManagement.plugins, and deliberately not here.
 *
 * They were here, as `apply false`, which still resolves every one of them on
 * every build — including the Android Gradle Plugin, on builds that produce no
 * APK. Declared as defaults in settings they are resolved only when a module
 * actually applies them, which is what lets `-Psharecard.serverOnly=true`
 * build the JVM server with no Android toolchain at all.
 */

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
