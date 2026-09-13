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

        /*
         * CODE LINES ONLY, and this was a bug in the scan itself.
         *
         * It matched the whole file text, so a KDoc SAYING "use str() and not
         * optString" was an offence — the explanation of the rule tripped the
         * rule. Exactly what ChooserDrawnTest was already fixed for, in a repo
         * whose own notes say a source scan must read code and not prose; this
         * one had simply never been asked the question.
         *
         * JsonSafe.kt stays exempt outright: it is where the safe wrapper
         * lives, so it is the one file whose CODE must mention it.
         */
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.name != "JsonSafe.kt" }
            .filter { file ->
                file.readLines().any { line ->
                    val code = line.trimStart()
                    line.contains("optString") &&
                        !code.startsWith("//") && !code.startsWith("*")
                }
            }
            .map { it.path }
            .toList()

        assertTrue(
            "optString is unsafe on Android — use str()/strOrNull(). Found in: $offenders",
            offenders.isEmpty()
        )
    }
}
