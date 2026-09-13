pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }

    /*
     * PLUGIN VERSIONS LIVE HERE, AND THAT IS WHAT LETS :app BE LEFT OUT.
     *
     * They used to sit in the root build file as `apply false`, which resolves
     * the plugin marker whether or not any project applies it — so the Android
     * Gradle Plugin was downloaded to build things that contain no Android at
     * all: `:core:test` on a laptop, and the container image, which is a JVM
     * server and has no use for an Android SDK.
     *
     * Declared here they are DEFAULTS: a module asks for a plugin by id with no
     * version, and the version is only resolved if something actually applies
     * it. One place still declares each version, which is the reason they were
     * centralised in the first place — a version repeated per module loads the
     * Kotlin plugin twice, and Gradle warns that this "is not supported and may
     * break the build".
     */
    plugins {
        id("com.android.application") version "8.13.2"
        id("org.jetbrains.kotlin.android") version "2.2.21"
        id("org.jetbrains.kotlin.jvm") version "2.2.21"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "MusicDShareCard"

include(":core")
include(":server")

/*
 * -Psharecard.serverOnly=true LEAVES THE ANDROID MODULE OUT.
 *
 * THIS REPLACES A SED. Building anything here used to mean resolving the
 * Android Gradle Plugin, which is not reachable from every environment this is
 * worked on in — so the documented way to run `:core:test` was to strip the
 * plugins out of two build files by hand and remember to put them back, with
 * "never commit the stripped versions" written in capitals beside it. A rule
 * that needs capitals is a rule that gets broken.
 *
 * The container image uses the same flag for a better reason than convenience:
 * it builds `:server`, which is a plain JVM program, and pulling an Android
 * toolchain into that image's builder stage would be fetching a compiler for a
 * platform the result cannot run on.
 */
if (providers.gradleProperty("sharecard.serverOnly").orNull != "true") {
    include(":app")
}
