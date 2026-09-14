package com.musicd.sharecard

import com.musicd.sharecard.discover.FilePlayHistory
import com.musicd.sharecard.discover.PlayHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The record of what this app has drawn a card for.
 *
 * It is the fourth thing here that writes to disk, and the one that holds
 * something about a person rather than about the app, so the rules it has to
 * keep are worth asserting rather than assuming: it folds on the ACT, it is
 * capped, it survives a restart, and a file it cannot read costs the history
 * and never the app.
 */
class PlayHistoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = FilePlayHistory(File(tmp.root, "history.json"))

    @Test
    fun `an act is remembered once, however many of their records play`() {
        val h = store()
        h.remember("David Bowie", "Low")
        h.remember("David Bowie", "\"Heroes\"")
        h.remember("David Bowie", "Lodger")
        assertEquals(
            "a household that plays six Bowie records must weigh as Bowie once, " +
                "or one act crowds out everyone else",
            1, h.recent().size
        )
        // The album is kept so the screen can say why the act is in the list,
        // and the LAST one is the true one.
        assertEquals("Lodger", h.recent().first().album)
    }

    @Test
    fun `the same act under a different spelling is still one act`() {
        val h = store()
        h.remember("Bjork", "Post")
        h.remember("Björk", "Homogenic")
        assertEquals(1, h.recent().size)
        assertEquals("Homogenic", h.recent().first().album)
    }

    @Test
    fun `most recently heard comes first`() {
        val h = store()
        h.remember("Slint", "Spiderland")
        h.remember("Low", "Things We Lost in the Fire")
        assertEquals(listOf("Low", "Slint"), h.recent().map { it.artist })
    }

    @Test
    fun `playing an old act again brings them back to the front`() {
        val h = store()
        h.remember("Slint", "Spiderland")
        h.remember("Low", "Things We Lost in the Fire")
        h.remember("Slint", "Tweez")
        assertEquals(listOf("Slint", "Low"), h.recent().map { it.artist })
    }

    @Test
    fun `it is capped, and the oldest act is the one that goes`() {
        val h = store()
        for (i in 1..PlayHistory.LIMIT + 10) h.remember("Act $i", "Record $i")
        val kept = h.recent(PlayHistory.LIMIT + 50)
        assertEquals(PlayHistory.LIMIT, kept.size)
        assertEquals("Act ${PlayHistory.LIMIT + 10}", kept.first().artist)
        assertTrue(
            "an act nobody has played for months must stop shaping the screen",
            kept.none { it.artist == "Act 1" }
        )
    }

    @Test
    fun `it survives a restart`() {
        val file = File(tmp.root, "history.json")
        FilePlayHistory(file).remember("Cocteau Twins", "Heaven or Las Vegas")
        assertEquals("Cocteau Twins", FilePlayHistory(file).recent().first().artist)
    }

    @Test
    fun `a file it cannot read costs the history and not the app`() {
        val file = File(tmp.root, "history.json")
        file.writeText("{ this is not the array it was }")
        val h = FilePlayHistory(file)
        assertEquals(emptyList<PlayHistory.Heard>(), h.recent())
        // And it recovers: the next card written over the rubbish is kept.
        h.remember("Aphex Twin", "Selected Ambient Works 85-92")
        assertEquals("Aphex Twin", FilePlayHistory(file).recent().first().artist)
    }

    @Test
    fun `a nameless act is not remembered at all`() {
        val h = store()
        h.remember("", "Some Record")
        h.remember("   ", "Another")
        assertEquals(
            "there is nothing to ask a release feed about without an act",
            emptyList<PlayHistory.Heard>(), h.recent()
        )
    }

    @Test
    fun `the in-memory store behaves the same way`() {
        // The two falling out of step is how a test passes against behaviour
        // the app does not have, which is why they share one fold().
        val h = PlayHistory.inMemory()
        h.remember("Slint", "Spiderland")
        h.remember("Low", "Things We Lost in the Fire")
        h.remember("Slint", "Tweez")
        assertEquals(listOf("Slint", "Low"), h.recent().map { it.artist })
    }
}
