package com.musicd.sharecard

import com.musicd.sharecard.library.Normalize
import com.musicd.sharecard.meta.albumArticleFits
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guard that decides whether a Wikipedia article is about the record that
 * is actually playing.
 *
 * The first case here is the one that was reported: a card for "Cult" by
 * To/Die/For carrying Static-X's biography, because the only check ever made
 * was on the album TITLE and "Cult of Static" begins with "Cult".
 */
class AlbumArticleTest {

    // Real first sentences, trimmed to what the guard reads.
    private val staticX =
        "Cult of Static is the sixth studio album by American industrial metal " +
            "band Static-X, released on March 17, 2009."
    private val toDieFor =
        "Cult is the fourth studio album by Finnish gothic metal band " +
            "To/Die/For, released in 2003."

    @Test
    fun `the reported wrong blurb is refused`() {
        assertFalse(albumArticleFits("Cult of Static", staticX, "Cult", "To/Die/For"))
    }

    @Test
    fun `the right record still passes`() {
        assertTrue(albumArticleFits("Cult (To/Die/For album)", toDieFor, "Cult", "To/Die/For"))
        // And the same article under a bare title, where only the extract can
        // name the act.
        assertTrue(albumArticleFits("Cult", toDieFor, "Cult", "To/Die/For"))
    }

    @Test
    fun `the album title is still checked`() {
        assertFalse(albumArticleFits("Cult of Static", staticX, "Wish", "Static-X"))
    }

    @Test
    fun `a record with no artist behaves as it always did`() {
        assertTrue(albumArticleFits("Cult of Static", staticX, "Cult", ""))
    }

    @Test
    fun `ordinary records are not collateral`() {
        assertTrue(
            albumArticleFits(
                "Heaven or Las Vegas", "Heaven or Las Vegas is the sixth studio album " +
                    "by Scottish rock band Cocteau Twins, released in 1990.",
                "Heaven or Las Vegas", "Cocteau Twins"
            )
        )
        assertTrue(
            albumArticleFits(
                "Spiderland (Slint album)", "Spiderland is the second studio album by " +
                    "American rock band Slint, released in March 1991.",
                "Spiderland", "Slint"
            )
        )
    }

    @Test
    fun `an ampersand in the speaker's artist is not a mismatch`() {
        // The speaker writes "&"; Wikipedia's sentence writes "and". Refusing
        // the right article over a conjunction is the obvious way to make this
        // guard cost more than it saves.
        assertTrue(
            albumArticleFits(
                "Murder Ballads", "Murder Ballads is the ninth studio album by " +
                    "Australian rock band Nick Cave and the Bad Seeds.",
                "Murder Ballads", "Nick Cave & The Bad Seeds"
            )
        )
    }

    @Test
    fun `the limit is named rather than pretended away`() {
        // Identical in shape to the Jay Z case namesOverlap keeps: a name that
        // is a prefix of a longer real one. The extract for an Eagles of Death
        // Metal record does contain the run of words "eagles of death metal",
        // and the run "eagles" is inside it. A spare wrong blurb here is the
        // documented cost of matching on runs of words at all.
        assertTrue(
            albumArticleFits(
                "Peace, Love, Death Metal", "Peace, Love, Death Metal is the debut " +
                    "album by American rock band Eagles of Death Metal.",
                "Peace, Love, Death Metal", "Eagles"
            )
        )
    }

    @Test
    fun `mentions reads prose, not characters`() {
        val prose = "the fourth studio album by the English band Low, released in 1996"
        assertTrue(Normalize.mentions(prose, "Low"))
        assertFalse(Normalize.mentions(prose, "Lowlife"))
        // The pair this family of rules exists for: dropping "the" as well as
        // "and" would put "The Who" back inside "The Guess Who".
        assertFalse(
            Normalize.mentions("the second album by Canadian band The Guess Who", "The Who")
        )
        assertFalse(Normalize.mentions("", "Slint"))
        assertFalse(Normalize.mentions("Spiderland by Slint", ""))
    }
}
