package com.musicd.sharecard

import com.musicd.sharecard.library.Normalize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guard that stops a lookup landing on a stranger.
 *
 * Three things ask it: which Wikipedia article is this album, which is this
 * artist, and is Deezer's top search hit really the act we named. All three
 * fail the same way when it says yes too easily — a confident answer about
 * somebody else, on a card that looks exactly like a correct one.
 *
 * IT SAID YES TOO EASILY FOR AS LONG AS IT EXISTED. Its own comment named
 * "The Who" against "The Guess Who" as the pair it rejected; it matched the
 * shorter name anywhere inside the longer, so that pair overlapped. Written
 * down here so it cannot come back quietly.
 */
class NamesOverlapTest {

    @Test
    fun `a name qualified on the right is the same name`() {
        assertTrue(Normalize.namesOverlap("Jay Z", "Jay Z feat. Alicia Keys"))
        assertTrue(Normalize.namesOverlap("Spiderland", "Spiderland (Slint album)"))
        assertTrue(Normalize.namesOverlap("Abbey Road", "Abbey Road (Remastered)"))
        assertTrue(Normalize.namesOverlap("Talk Talk", "Talk Talk (band)"))
        // Either way round: the caller does not always know which is longer.
        assertTrue(Normalize.namesOverlap("Slint discography", "Slint"))
    }

    @Test
    fun `a leading article is not a difference`() {
        assertTrue(Normalize.namesOverlap("The Wall", "Wall"))
        assertTrue(Normalize.namesOverlap("The Dark Side of the Moon", "Dark Side of the Moon"))
    }

    @Test
    fun `accents and ligatures fold, because one rule does all the folding`() {
        assertTrue(Normalize.namesOverlap("Bjork", "Björk"))
        assertTrue(Normalize.namesOverlap("Ænima", "AEnima"))
    }

    @Test
    fun `a different act that happens to end the same way is refused`() {
        // THE ONE THAT WAS WRONG. Both reduce to something ending in "who"
        // once the leading article is stripped, and a run-of-words match found
        // it. This is a stranger's biography on somebody else's card.
        assertFalse(Normalize.namesOverlap("The Who", "The Guess Who"))
        assertFalse(Normalize.namesOverlap("Sabbath", "Black Sabbath"))
        assertFalse(Normalize.namesOverlap("Wings", "Paul McCartney and Wings"))
    }

    /**
     * WHAT THIS RULE CANNOT DO, written down so nobody tries to make it.
     *
     * "Eagles" and "Eagles of Death Metal" are two different bands. They are
     * also, letter for letter, the same shape as "Jay Z" and "Jay Z feat.
     * Alicia Keys", which are one artist — a name followed by more words. No
     * amount of string comparison separates those, and the version of this
     * that tried ended up rejecting the qualified Wikipedia titles the whole
     * guard exists to accept. Telling them apart needs an entity database, and
     * the one this app has is MusicBrainz, which is asked by ID and never by
     * this function.
     */
    @Test
    fun `two acts sharing a first word cannot be told apart, and this says so`() {
        assertTrue(Normalize.namesOverlap("Eagles", "Eagles of Death Metal"))
    }

    @Test
    fun `a longer word that merely begins the same is refused`() {
        // Word by word, not character by character.
        assertFalse(Normalize.namesOverlap("The Beat", "The Beatles"))
        assertFalse(Normalize.namesOverlap("Low", "Lowlife"))
    }

    /**
     * MEDIAWIKI CANNOT PUT A `#` IN A PAGE TITLE — it is the fragment
     * separator — so every record named with one is filed under the word.
     * Big Star's "#1 Record" is at /wiki/Number_1_Record, and the folding
     * dropped the hash as punctuation: "1 record" against "number 1 record",
     * which the front anchor then correctly refused. Reported from the field
     * as no blurb and no Wikipedia chip on that card, with the artist chip
     * sitting beside it working perfectly — which is the two halves of this
     * lookup disagreeing, and the proof it was the TITLE that failed.
     */
    @Test
    fun `a hash before a digit is the word number, because Wikipedia has no choice`() {
        assertTrue(Normalize.namesOverlap("Number 1 Record", "#1 Record"))
        assertTrue(Normalize.namesOverlap("#1 Record", "Number 1 Record"))
        assertTrue(Normalize.namesOverlap("Number 1 Crush", "#1 Crush"))
        // Still a record, not a different one: the guard is unmoved.
        assertFalse(Normalize.namesOverlap("Number 1 Record", "Number 2 Record"))
    }

    /**
     * AND A SHARP IS NOT A NUMBER. "#" is how a key signature is written, and
     * folding that one to "number" would put "prelude number minor" in a
     * search box. Only a hash IMMEDIATELY BEFORE A DIGIT is the word, which is
     * the only shape Wikipedia's rule produces.
     */
    @Test
    fun `a sharp is left alone`() {
        assertEquals("number 1 record", Normalize.text("#1 Record"))
        assertEquals("prelude in c minor", Normalize.text("Prelude in C# Minor"))
        assertFalse(Normalize.text("C# Minor").contains("number"))
    }

    @Test
    fun `nothing overlaps nothing`() {
        assertFalse(Normalize.namesOverlap("", "Slint"))
        assertFalse(Normalize.namesOverlap("Slint", ""))
        assertFalse(Normalize.namesOverlap("!!!", "???"))
    }
}
