package com.musicd.sharecard

import com.musicd.sharecard.sonos.parseZoneGroupState
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every optional XML parser setting must be applied defensively.
 *
 * WHY THIS IS A SOURCE SCAN AND NOT AN ASSERTION. The bug it stands for cannot
 * happen on the JVM: `setXIncludeAware` works fine here and throws
 * `UnsupportedOperationException` on Android, which does not implement it. It
 * was applied in a static initialiser, so it did not fail one parse — the Xml
 * CLASS never loaded, every later use threw NoClassDefFoundError, and the app
 * died on a device while every test here passed. Three releases went out with
 * it, each diagnosing a different symptom of the same line.
 *
 * No test that runs on this JVM could ever have caught it. So the source is
 * scanned instead, the same way JsonSafeTest scans for `optString` — the rule
 * is that hardening a parser is best-effort, because it runs on whatever XML
 * implementation the platform happens to ship.
 */
class ParserHardeningTest {

    private val source: String
        get() {
            val file = File("src/main/kotlin/com/musicd/sharecard/sonos/Xml.kt")
            assertTrue("cannot find Xml.kt at ${file.absolutePath}", file.isFile)
            return file.readText()
        }

    @Test
    fun `no parser setting is applied without a guard around it`() {
        // A bare `isXIncludeAware = false` or `setFeature(...)` is the shape
        // that killed the app. Tracked by brace depth rather than per line,
        // because a guarded call is commonly written across several lines:
        //
        //     quietly("disallow-doctype-decl", loud = true) {
        //         setFeature(...)
        //     }
        val bare = Regex("""^\s*(is[A-Z]\w*\s*=|setFeature\s*\()""")
        val offenders = ArrayList<String>()
        var depth = 0
        for ((i, line) in source.lines().withIndex()) {
            val guardedHere = line.contains("quietly(")
            if (bare.containsMatchIn(line) && depth == 0 && !guardedHere) {
                offenders += "line ${i + 1}: ${line.trim()}"
            }
            if (guardedHere) depth++
            else if (depth > 0) depth += line.count { it == '{' } - line.count { it == '}' }
            if (depth < 0) depth = 0
        }

        assertTrue(
            "every DocumentBuilderFactory setting must go through quietly {} — a platform " +
                "that refuses one must not stop the class loading. Found: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun `building the factory cannot throw out of the initialiser`() {
        assertTrue(
            "buildFactory() must catch Throwable: the failure it stands for was an Error",
            source.contains("catch (e: Throwable)")
        )
        assertTrue(
            "the factory must be nullable, so a platform with no usable parser still loads",
            source.contains("DocumentBuilderFactory? = buildFactory()")
        )
    }

    @Test
    fun `the parser still works, and still refuses a DOCTYPE`() {
        // The hardening must not have been quietly guarded into doing nothing.
        val good = """
            <ZoneGroupState><ZoneGroups>
              <ZoneGroup Coordinator="A" ID="g">
                <ZoneGroupMember UUID="A" ZoneName="Kitchen" Location="http://10.0.0.1:1400/x"/>
              </ZoneGroup>
            </ZoneGroups></ZoneGroupState>
        """.trimIndent()
        assertTrue("a normal reply must still parse", parseZoneGroupState(good).size == 1)

        val attack = """
            <?xml version="1.0"?>
            <!DOCTYPE foo [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
            <ZoneGroupState><ZoneGroups>
              <ZoneGroup Coordinator="A" ID="g">
                <ZoneGroupMember UUID="A" ZoneName="&xxe;" Location="http://10.0.0.1:1400/x"/>
              </ZoneGroup>
            </ZoneGroups></ZoneGroupState>
        """.trimIndent()
        assertTrue("a DOCTYPE must still be refused", parseZoneGroupState(attack).isEmpty())
    }
}
