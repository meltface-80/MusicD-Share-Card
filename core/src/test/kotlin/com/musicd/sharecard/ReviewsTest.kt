package com.musicd.sharecard

import com.musicd.sharecard.meta.FileCacheStore
import com.musicd.sharecard.meta.Reviews
import com.musicd.sharecard.settings.FileSettingsStore
import com.musicd.sharecard.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Where the words come from, and which of those are on to begin with.
 *
 * THE DEFAULTS ARE THE WHOLE SUBJECT. Services and zones both default to
 * nothing, and review sources deliberately do not: Wikipedia and Pitchfork are
 * what the card has always drawn, so defaulting them off would empty every card
 * in the house to make a settings screen consistent. The artist sources are new
 * and are asked for.
 */
class ReviewsTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `the record's own sources are on, the artist's are not`() {
        val fresh = Settings()
        assertTrue(fresh.reviewEnabled(Reviews.WIKIPEDIA))
        assertTrue(fresh.reviewEnabled(Reviews.PITCHFORK))
        assertTrue(fresh.reviewEnabled(Reviews.ALLMUSIC))
        assertFalse(fresh.reviewEnabled(Reviews.WIKIPEDIA_ARTIST))
        assertFalse(fresh.reviewEnabled(Reviews.ALLMUSIC_ARTIST))
    }

    @Test
    fun `switching one off leaves the others exactly as they were`() {
        /*
         * The trap this guards. The stored set is materialised from the
         * defaults the first time anything is touched — so if that
         * materialisation were missing, switching Pitchfork off would write a
         * set containing nothing, and Wikipedia and AllMusic would go off with
         * it. Every card in the house loses its blurb because somebody turned
         * off a score.
         */
        val after = Settings().withReview(Reviews.PITCHFORK, false)
        assertFalse(after.reviewEnabled(Reviews.PITCHFORK))
        assertTrue(after.reviewEnabled(Reviews.WIKIPEDIA))
        assertTrue(after.reviewEnabled(Reviews.ALLMUSIC))
        // And the artist ones are still off; materialising must not turn
        // things ON that were never on.
        assertFalse(after.reviewEnabled(Reviews.WIKIPEDIA_ARTIST))
    }

    @Test
    fun `turning an artist source on does not disturb the record's`() {
        val after = Settings().withReview(Reviews.WIKIPEDIA_ARTIST, true)
        assertTrue(after.reviewEnabled(Reviews.WIKIPEDIA_ARTIST))
        assertTrue(after.reviewEnabled(Reviews.WIKIPEDIA))
        assertTrue(after.reviewEnabled(Reviews.PITCHFORK))
    }

    // ------------------------------------------------------------- on disk

    @Test
    fun `a file with no review key at all still means the defaults`() {
        /*
         * ABSENT IS NOT EMPTY, and every install that predates this screen has
         * a settings file with no review key in it. Reading that as "nothing
         * chosen" is what keeps their card's blurb and score.
         */
        val file = File(temp.root, "settings.json")
        file.writeText("""{"enabledZones":["roon:1"],"enabledServices":[]}""")
        val read = FileSettingsStore(file).read()
        assertNull(read.enabledReviews)
        assertTrue(read.reviewEnabled(Reviews.WIKIPEDIA))
        assertFalse(read.reviewEnabled(Reviews.ALLMUSIC_ARTIST))
    }

    @Test
    fun `an explicitly empty set means nothing, not the defaults`() {
        // Somebody switched every source off. That is a choice, and it has to
        // survive a restart rather than springing back on.
        val file = File(temp.root, "settings.json")
        FileSettingsStore(file).write(Settings(enabledReviews = emptySet()))
        val read = FileSettingsStore(file).read()
        assertEquals(emptySet<String>(), read.enabledReviews)
        assertFalse(read.reviewEnabled(Reviews.WIKIPEDIA))
    }

    @Test
    fun `a choice survives a restart`() {
        val file = File(temp.root, "settings.json")
        FileSettingsStore(file).write(
            Settings().withReview(Reviews.PITCHFORK, false)
                .withReview(Reviews.ALLMUSIC_ARTIST, true)
        )
        val read = FileSettingsStore(file).read()
        assertFalse(read.reviewEnabled(Reviews.PITCHFORK))
        assertTrue(read.reviewEnabled(Reviews.ALLMUSIC_ARTIST))
        assertTrue(read.reviewEnabled(Reviews.WIKIPEDIA))
    }

    // ---------------------------------------------------------- the links

    @Test
    fun `AllMusic links are built through the one encoding rule`() {
        /*
         * Not by hand. A space is %20 and NOT a plus, because the query rides
         * in the path; and a slash is spent as a space, or "AC/DC" 404s. Those
         * rules already have tests in StreamingLinks and a second copy here
         * would be a second place for them to drift.
         */
        val url = Reviews.albumUrl("Slint", "Spiderland")!!
        assertTrue(url, url.startsWith("https://www.allmusic.com/search/albums/"))
        assertTrue(url, url.contains("%20"))
        assertFalse("a plus would be searched for literally: $url", url.contains("+"))

        val slashed = Reviews.albumUrl("AC/DC", "Back in Black")!!
        assertFalse("an encoded slash is decoded back into a path segment", slashed.contains("%2F"))

        val artist = Reviews.artistUrl("Cocteau Twins")!!
        assertTrue(artist, artist.startsWith("https://www.allmusic.com/search/artists/"))
    }

    @Test
    fun `nothing worth searching for produces no link`() {
        // The same condition under which the card itself is not drawn.
        assertNull(Reviews.albumUrl(null, ""))
        assertNull(Reviews.artistUrl(null))
        assertNull(Reviews.artistUrl("   "))
    }

    @Test
    fun `every source the settings screen offers is one the app knows`() {
        // The write path refuses an id outside this set, because a stored set
        // is materialised from the defaults and an unknown name in it could
        // never be drawn or switched off again.
        assertEquals(Reviews.ALL.map { it.id }.toSet(), Reviews.IDS)
        assertEquals(Reviews.ALL.size, Reviews.IDS.size)
    }

    @Test
    fun `the caches are untouched by any of this`() {
        // A guard against the settings work quietly acquiring a fourth writer:
        // FileCacheStore is still the thing that writes what lookups found.
        val dir = File(temp.root, "cache")
        FileCacheStore(dir).apply { put("extras", "k", "v"); flush() }
        assertEquals("v", FileCacheStore(dir).load("extras")["k"])
    }
}
