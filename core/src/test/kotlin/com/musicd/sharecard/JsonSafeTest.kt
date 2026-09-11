package com.musicd.sharecard

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `optString` must not come back.
 *
 * The two org.json implementations disagree about JSON null: Android's returns
 * the LITERAL text "null" where the desktop one returns "". These tests run on
 * the JVM against the desktop implementation, so no assertion here could ever
 * catch the difference — the source is scanned instead.
 *
 * See [str] in JsonSafe.kt for what this is standing in for.
 */
class JsonSafeTest {

    @Test
    fun `no production source calls optString directly`() {
        val root = File("src/main/kotlin")
        assertTrue("cannot find the source to scan: ${root.absolutePath}", root.isDirectory)

        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            // JsonSafe.kt is where the safe wrapper lives, so it is the one
            // file that must mention it.
            .filter { it.name != "JsonSafe.kt" }
            .filter { it.readText().contains("optString") }
            .map { it.path }
            .toList()

        assertTrue(
            "optString is unsafe on Android — use str()/strOrNull(). Found in: $offenders",
            offenders.isEmpty()
        )
    }
}
