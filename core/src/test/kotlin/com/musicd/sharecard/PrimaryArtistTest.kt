package com.musicd.sharecard

import com.musicd.sharecard.library.Normalize
import com.musicd.sharecard.meta.Reviews
import com.musicd.sharecard.meta.StreamingLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A SEARCH BOX GETS THE FIRST CREDITED ACT, NOT THE WHOLE CREDIT.
 *
 * A Roon card came back credited "Stan Getz / Cal Tjader / Alan Jay Lerner /
 * Frederick Loewe" — two performers and the two men who wrote the songs — and
 * every chip in the links row searched for all four names as one. AllMusic
 * answered in as many words: "No search results were found for Stan Getz Cal
 * Tjader Alan Jay Lerner Frederick Loewe". It is right; there is no such act.
 *
 * THE LIMITS MATTER AS MUCH AS THE SPLIT. Throwing away a real part of a name
 * leaves a search that finds nothing, which is the same failure from the other
 * side — so the cases that must NOT split are asserted here beside the ones
 * that must.
 */
class PrimaryArtistTest {

    private val fourNames = "Stan Getz / Cal Tjader / Alan Jay Lerner / Frederick Loewe"

    @Test
    fun `a slash-separated credit is cut to its first act`() {
        assertEquals("Stan Getz", Normalize.primaryArtist(fourNames))
        assertEquals("Stan Getz", Normalize.primaryArtist("Stan Getz / Cal Tjader"))
    }

    @Test
    fun `a featured act is dropped`() {
        assertEquals("Jay Z", Normalize.primaryArtist("Jay Z feat. Alicia Keys"))
        assertEquals("Jay Z", Normalize.primaryArtist("Jay Z ft Alicia Keys"))
        assertEquals("Jay Z", Normalize.primaryArtist("Jay Z Featuring Alicia Keys"))
    }

    @Test
    fun `a semicolon separates`() {
        assertEquals("Stan Getz", Normalize.primaryArtist("Stan Getz;Cal Tjader"))
    }

    @Test
    fun `a name that is not a list comes back whole`() {
        // AC/DC is the case StreamingLinks.searchQuery already has a rule and a
        // test for, and a bare slash is what it is written with.
        assertEquals("AC/DC", Normalize.primaryArtist("AC/DC"))
        // A conjunction is not a separator: each of these is one act, and two
        // of them are the pairs Normalize.namesOverlap is written around.
        assertEquals("Nick Cave & the Bad Seeds", Normalize.primaryArtist("Nick Cave & the Bad Seeds"))
        assertEquals("Simon & Garfunkel", Normalize.primaryArtist("Simon & Garfunkel"))
        assertEquals("Earth, Wind & Fire", Normalize.primaryArtist("Earth, Wind & Fire"))
        assertEquals("Sleeping with Sirens", Normalize.primaryArtist("Sleeping with Sirens"))
        assertEquals("Aphex Twin", Normalize.primaryArtist("Aphex Twin"))
    }

    @Test
    fun `nothing in means nothing out`() {
        assertEquals("", Normalize.primaryArtist(null))
        assertEquals("", Normalize.primaryArtist("   "))
        // A credit that opens with a separator would otherwise leave an empty
        // search box, which is worse than the whole string.
        assertEquals("/ Cal Tjader", Normalize.primaryArtist("/ Cal Tjader"))
    }

    @Test
    fun `the AllMusic artist chip searches for one act`() {
        val url = Reviews.artistUrl(fourNames)
        assertEquals("https://www.allmusic.com/search/artists/Stan%20Getz", url)
    }

    @Test
    fun `every service chip searches for one act`() {
        val links = StreamingLinks.forAlbum(fourNames, "Cal Tjader-Stan Getz Sextet")
        assertTrue("no links were built at all", links.isNotEmpty())
        for (link in links) {
            assertTrue(
                "${link.name} still carries the whole four-name credit: ${link.url}",
                !link.url.contains("Lerner") && !link.url.contains("Loewe")
            )
            assertTrue(
                "${link.name} lost the act it should search for: ${link.url}",
                link.url.contains("Stan%20Getz")
            )
        }
    }

    @Test
    fun `the AllMusic album chip searches for one act`() {
        val url = Reviews.albumUrl(fourNames, "Cal Tjader-Stan Getz Sextet")
        assertEquals(
            "https://www.allmusic.com/search/albums/" +
                "Stan%20Getz%20Cal%20Tjader-Stan%20Getz%20Sextet",
            url
        )
    }

    @Test
    fun `AC-DC keeps both halves of its name in a search`() {
        // The rule this whole thing must not break, and the one with a comment
        // of its own in StreamingLinks: a slash is spent as a space, so the
        // record is found — but only if the name survives this step first.
        val links = StreamingLinks.forAlbum("AC/DC", "Back in Black")
        assertTrue(
            "AC/DC lost half its name: ${links.first().url}",
            links.all { it.url.contains("AC%20DC%20Back%20in%20Black") }
        )
    }
}
