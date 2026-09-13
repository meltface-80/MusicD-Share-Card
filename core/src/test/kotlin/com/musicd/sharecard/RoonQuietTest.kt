package com.musicd.sharecard

import com.musicd.sharecard.roon.RoonStage
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Roon says nothing when there is no Roon, and stops looking for it.
 *
 * REPORTED: "seeing a note about no Roon core detected. Yeah, that's fine as
 * this isn't a Roon only app. So remove that error. If it doesn't detect a Roon
 * zone then stop looking until a full app refresh."
 *
 * Both halves matter and the second is the worse one: the failed discovery
 * rescheduled itself every thirty seconds for the life of the process, which on
 * a device that is never switched off is a multicast sweep of the household
 * twice a minute for ever — inside the one source nobody had checked against
 * the no-polling rule.
 *
 * Scanned rather than driven, because reaching those branches needs a real SOOD
 * socket and a Core that is not there, and neither exists in this JVM.
 */
class RoonQuietTest {

    private fun read(path: String): String {
        val file = File("../$path")
        assertTrue("cannot find $path at ${file.absolutePath}", file.isFile)
        return file.readText()
    }

    private val client by lazy {
        read("core/src/main/kotlin/com/musicd/sharecard/roon/RoonClient.kt")
    }

    @Test
    fun `a failed discovery does not schedule another one`() {
        // The whole no-Core branch, from the publish to the end of the block.
        val branch = client.substringAfter("if (!found && running.get())").substringBefore("\n    }")
        assertTrue(
            "a failed Roon discovery must not reschedule itself — Refresh is " +
                "what asks again:\n$branch",
            !branch.contains("net.schedule")
        )
    }

    @Test
    fun `nothing in the Roon client polls on a timer except a live reconnect`() {
        // A backoff on a socket that WAS connected is a different thing from
        // sweeping the network for a Core that is not there.
        val schedules = client.lines()
            .filter { it.contains("net.schedule") }
            .filterNot { it.trimStart().startsWith("//") }
        assertTrue(
            "unexpected timer in RoonClient: $schedules",
            schedules.size <= 1
        )
    }

    @Test
    fun `an absent Core is silent`() {
        val source = read("core/src/main/kotlin/com/musicd/sharecard/roon/RoonSource.kt")
        val notice = source.substringAfter("override fun notice()").substringBefore("\n    }")
        // The stages that mean "no Roon here, or on the way to knowing" must
        // map to null, not to a line on somebody's screen.
        for (quiet in listOf("ABSENT", "DISCOVERING", "CONNECTING", "IDLE")) {
            assertTrue(
                "RoonStage.$quiet must not produce a notice:\n$notice",
                notice.contains(quiet)
            )
        }
        assertTrue("the quiet stages must end in null", notice.contains("-> null"))
        // The one that still speaks, because the user must act on it.
        assertTrue(notice.contains("AWAITING_APPROVAL"))
    }

    @Test
    fun `ABSENT is a stage of its own, so a missing Core is not an error`() {
        assertTrue(
            "RoonStage must distinguish 'no Core here' from 'a Core would not talk'",
            client.contains("ABSENT")
        )
        // And it is reachable: the enum entry alone would be decoration.
        assertTrue(client.contains("RoonStage.ABSENT"))
    }

    @Test
    fun `the stage enum still has every value the source maps`() {
        // A stage added later and not mapped is a `when` that stops compiling,
        // which is the right failure — this just pins that the set is the one
        // the notice() above was written against.
        for (stage in RoonStage.entries) {
            assertTrue("RoonStage.$stage is not named in RoonClient", client.contains(stage.name))
        }
    }
}
