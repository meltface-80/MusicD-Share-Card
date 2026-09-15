package com.musicd.sharecard

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * WHAT THE SHELL CALLS IN `:core` MUST BE VISIBLE FROM ANOTHER MODULE.
 *
 * `internal` in Kotlin is per MODULE, and `:app` is not `:core`. Shipped as
 * `internal fun listenerNote` inside an `internal companion object`, which CI
 * refused twice over — and NOTHING in the local check list could see it:
 *
 *  - `./gradlew :core:test` compiles `:core` only.
 *  - `DeviceAudioTest` lives in :core's own test source set, where `internal`
 *    IS visible, so the test passed while the app would not build.
 *
 * That is the "compiling is not evidence" rule stood on its head: the tests
 * were evidence of the wrong thing. CI catches it in forty seconds, but a CI
 * round trip is the slowest loop in this project and this seam is going to be
 * crossed again, so it is checked here.
 *
 * A REFERENCE IS FLAGGED ONLY WHEN BOTH HALVES MATCH — the type name AND the
 * member — so an ordinary word shared with some unrelated `internal` helper
 * cannot fail the build.
 */
class ModuleSeamTest {

    private fun kotlinFiles(dir: String): List<File> =
        File("../$dir").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    /** Everything `:core` declares that another module cannot reach. */
    private class Hidden {
        /** "DeviceAudio.listenerNote" — a member declared internal itself. */
        val members = mutableSetOf<String>()

        /** Types whose COMPANION is internal: nothing on it resolves outside. */
        val sealedCompanions = mutableSetOf<String>()

        /** "DeviceAudio.MAX_SESSIONS" — declared inside a companion, any visibility. */
        val companionMembers = mutableSetOf<String>()
    }

    private fun scanCore(): Hidden {
        val out = Hidden()
        val topLevel = Regex("""^(?:internal |private |public )?(?:abstract |open |sealed |data )?(?:class|object|interface) (\w+)""")
        val companion = Regex("""^\s*(internal |private )?companion object""")
        val member = Regex("""^\s*(internal|private) (?:fun|val|var|const val|class|object) (\w+)""")
        val anyMember = Regex("""^\s*(?:internal |private |public )?(?:fun|val|var|const val|class|object) (\w+)""")

        for (file in kotlinFiles("core/src/main/kotlin")) {
            var type = ""
            var inCompanion = false
            var companionIndent = -1
            for (raw in file.readLines()) {
                topLevel.find(raw)?.let { if (!raw.startsWith(" ")) type = it.groupValues[1] }
                if (type.isEmpty()) continue

                companion.find(raw)?.let {
                    inCompanion = true
                    companionIndent = raw.takeWhile { c -> c == ' ' }.length
                    if (it.groupValues[1].isNotBlank()) out.sealedCompanions += type
                }
                // A companion ends at a brace closing at its own indent.
                if (inCompanion && raw.trimEnd() == " ".repeat(companionIndent) + "}") inCompanion = false

                member.find(raw)?.let { out.members += "$type.${it.groupValues[2]}" }
                if (inCompanion) anyMember.find(raw)?.let { out.companionMembers += "$type.${it.groupValues[1]}" }
            }
        }
        return out
    }

    /**
     * CODE, NOT PROSE — and this scan needed telling twice.
     *
     * The first cut stripped `//` only, and immediately flagged
     * `[DeviceAudio.artNote]` inside a KDoc: a documentation link, not a call,
     * and nothing a compiler minds. That is this repository's existing rule
     * ("a source scan must read code, not prose", written after a scan matched
     * the comment explaining its own fix) collected by the person who wrote it
     * down. Line numbers are preserved so a real hit still points somewhere.
     */
    private fun codeOnly(lines: List<String>): List<String> {
        val out = ArrayList<String>(lines.size)
        var inBlock = false
        for (raw in lines) {
            var line = raw
            if (inBlock) {
                val end = line.indexOf("*/")
                if (end < 0) { out += ""; continue }
                line = line.substring(end + 2)
                inBlock = false
            }
            val start = line.indexOf("/*")
            if (start >= 0) {
                val end = line.indexOf("*/", start + 2)
                line = if (end >= 0) line.substring(0, start) + line.substring(end + 2)
                else { inBlock = true; line.substring(0, start) }
            }
            out += line.substringBefore("//")
        }
        return out
    }

    @Test
    fun `the shell never calls something only core can see`() {
        val hidden = scanCore()
        assertTrue("the core scan found nothing — has it broken?", hidden.members.size > 3)

        val reference = Regex("""\b([A-Z]\w*)\.(\w+)""")
        val broken = mutableListOf<String>()

        for (file in kotlinFiles("app/src/main/java")) {
            for ((number, code) in codeOnly(file.readLines()).withIndex()) {
                for (hit in reference.findAll(code)) {
                    val (type, name) = hit.destructured
                    val key = "$type.$name"
                    val why = when {
                        key in hidden.members -> "$name is internal in $type"
                        type in hidden.sealedCompanions && key in hidden.companionMembers ->
                            "$type's companion object is internal, so $name cannot be resolved"
                        else -> continue
                    }
                    broken += "${file.name}:${number + 1} $why"
                }
            }
        }

        assertTrue(
            "the Android shell calls into :core across the module boundary, and " +
                "`internal` does not cross it — :app will not compile:\n  " +
                broken.joinToString("\n  "),
            broken.isEmpty()
        )
    }
}
