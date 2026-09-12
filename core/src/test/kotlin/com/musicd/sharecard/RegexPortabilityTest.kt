package com.musicd.sharecard

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every regex in this app must be one ANDROID will also compile.
 *
 * WHY THIS IS A SOURCE SCAN AND NOT AN ASSERTION, exactly as with
 * [ParserHardeningTest]: the bug it stands for cannot happen on this JVM.
 * `java.util.regex` here treats a dangling `}` as a literal brace and compiles
 * happily; Android's is ICU-backed and stricter, and refuses it.
 *
 * A regex in a companion object IS A STATIC INITIALISER, which makes the
 * consequence the same one that cost three releases before: the class never
 * loads, and the failure is not "this lookup found nothing" but
 *
 *     The card server could not start.
 *     ExceptionInInitializerError: null
 *
 * That is what one unescaped `}` in Pitchfork's rating pattern did in 0.19.0.
 * Every test here passed, CI was green, and the app would not open.
 *
 * So the patterns are read out of the source and walked: a `{` outside a
 * character class has to begin a real quantifier, and a `}` or `]` outside one
 * has to be escaped. Anything else is a brace the two platforms disagree about.
 */
class RegexPortabilityTest {

    private val roots = listOf(
        File("src/main/kotlin"),
        File("../app/src/main/java")
    )

    /** `Regex("…")` with a plain single-line literal, which is all this app uses. */
    private val literal = Regex("""Regex\(\s*"((?:[^"\\]|\\.)*)"""")

    @Test
    fun `no regex carries a brace the platforms disagree about`() {
        val offenders = ArrayList<String>()
        var checked = 0

        for (root in roots) {
            if (!root.isDirectory) continue
            for (file in root.walkTopDown().filter { it.isFile && it.extension == "kt" }) {
                for (m in literal.findAll(file.readText())) {
                    val pattern = unescapeKotlin(m.groupValues[1])
                    checked++
                    problem(pattern)?.let { offenders += "${file.name}: $it  in  $pattern" }
                }
            }
        }

        assertTrue("no Regex literals were found — the scan is not looking where it thinks", checked > 0)
        assertTrue(
            "these compile here and throw on Android, from a static initialiser:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    /** Kotlin source text -> the string the compiler actually hands to Regex. */
    private fun unescapeKotlin(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        while (i < source.length) {
            val c = source[i]
            if (c != '\\' || i == source.lastIndex) {
                out.append(c); i++; continue
            }
            when (val next = source[i + 1]) {
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                else -> out.append(next)
            }
            i += 2
        }
        return out.toString()
    }

    /**
     * The first brace or bracket this pattern gets wrong, or null.
     *
     * A `{` outside a character class may only begin a quantifier — `{2}`,
     * `{0,}`, `{0,600}`. A `}` or `]` outside one may only appear escaped. Both
     * are literals on the JVM and syntax errors on Android.
     */
    private fun problem(pattern: String): String? {
        var i = 0
        var inClass = false
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\\' -> i++ // whatever follows is escaped, and fine either way
                inClass && c == ']' -> inClass = false
                inClass -> Unit
                c == '[' -> inClass = true
                c == ']' -> return "unescaped ] outside a character class at $i"
                c == '{' -> {
                    val end = pattern.indexOf('}', i)
                    val body = if (end > i) pattern.substring(i + 1, end) else null
                    if (body == null || !QUANTIFIER.matches(body)) {
                        return "'{' at $i does not begin a quantifier"
                    }
                    i = end // the loop's i++ steps past the '}'
                }
                c == '}' -> return "unescaped } outside a quantifier at $i"
            }
            i++
        }
        return if (inClass) "unclosed character class" else null
    }

    private companion object {
        /** The inside of `{…}`: `2`, `0,`, `0,600`. */
        val QUANTIFIER = Regex("""\d+(,\d*)?""")
    }
}

/**
 * What a failure gets to say for itself.
 *
 * "ExceptionInInitializerError: null" is a real message this app showed on a
 * device. It names no class, no line and no reason, because the Error carries
 * no message of its own — everything is in its cause. A report like that turns
 * a one-line fix into a guessing game.
 */
class DescribeThrowableTest {

    @Test
    fun `the cause is what gets reported, not the empty wrapper`() {
        val cause = java.util.regex.PatternSyntaxException("Dangling }", "x}", 1)
        val wrapper = ExceptionInInitializerError(cause)

        val text = describe(wrapper)
        assertTrue("names the wrapper: $text", text.contains("ExceptionInInitializerError"))
        assertTrue("names the real fault: $text", text.contains("PatternSyntaxException"))
        assertTrue("says what was wrong: $text", text.contains("Dangling"))
    }

    @Test
    fun `a throwable with nothing to say still names itself`() {
        assertTrue(describe(IllegalStateException()).contains("IllegalStateException"))
    }

    @Test
    fun `our own frame is named, because it says which class failed to load`() {
        val thrown = runCatching { com.musicd.sharecard.library.Normalize.text(boom()) }
            .exceptionOrNull()
        assertTrue(describe(thrown!!), describe(thrown).contains("DescribeThrowableTest"))
    }

    private fun boom(): String = throw IllegalArgumentException("no")

    @Test
    fun `a cause that points at itself does not loop`() {
        val self = object : RuntimeException("round") {
            override val cause: Throwable get() = this
        }
        assertTrue(describe(self).isNotEmpty())
    }
}
