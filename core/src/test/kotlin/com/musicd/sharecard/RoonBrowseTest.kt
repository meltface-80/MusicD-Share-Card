package com.musicd.sharecard

import com.musicd.sharecard.roon.RoonBrowse
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Choosing what to queue, and refusing to do anything else.
 *
 * NO ROON CORE IS REACHABLE FROM HERE, so none of this has been seen on the
 * wire: the shapes are the ones RoonLabs' own `node-roon-api-browse` documents
 * and that MusicD Remote Lite drives against real hardware. What IS tested is
 * every decision that can be wrong without the network's help — which row is
 * the album, and which action is Queue — because those are the ones that put
 * the wrong record in somebody's queue, or worse.
 *
 * The `pickQueueAction` tests are the important ones in this file. Roon's album
 * menu opens with Play Now.
 */
class RoonBrowseTest {

    private fun items(vararg rows: String) = JSONArray("[" + rows.joinToString(",") + "]")

    private fun row(title: String, subtitle: String = "", hint: String = "list", key: String = "k") =
        """{"title":"$title","subtitle":"$subtitle","hint":"$hint","item_key":"$key"}"""

    // ------------------------------------------------------ the search page

    @Test
    fun `the Albums grouping is the one opened`() {
        val results = items(
            row("Artists", key = "a"),
            row("Albums", key = "b"),
            row("Tracks", key = "c")
        )
        // An Artists row drills into a discography and a Tracks row into a
        // single song; neither is a record to queue.
        assertEquals("b", RoonBrowse.pickAlbumsHeading(results)!!.getString("item_key"))
    }

    @Test
    fun `a single grouping is taken even when it is not called Albums`() {
        // The heading is a display string, so an installation in another
        // language does not say "Albums". One grouping can only mean one thing.
        val results = items(row("Alben", key = "only"))
        assertEquals("only", RoonBrowse.pickAlbumsHeading(results)!!.getString("item_key"))
    }

    @Test
    fun `several groupings and none named Albums is not a guess`() {
        val results = items(row("Kunstner", key = "a"), row("Spor", key = "b"))
        assertNull(RoonBrowse.pickAlbumsHeading(results))
    }

    @Test
    fun `nothing at all is nothing`() {
        assertNull(RoonBrowse.pickAlbumsHeading(JSONArray()))
    }

    // ----------------------------------------------------- the right record

    @Test
    fun `the album is matched on its name and its artist`() {
        val albums = items(
            row("Spiderland", "Bastro", key = "wrong-act"),
            row("Spiderland", "Slint", key = "right"),
            row("Tweez", "Slint", key = "wrong-record")
        )
        // Roon's search is fuzzy and the thing at stake is somebody's queue.
        assertEquals("right", RoonBrowse.pickAlbum(albums, "Spiderland", "Slint")!!
            .getString("item_key"))
    }

    @Test
    fun `a reissue still counts as the record`() {
        // The same rule the Wikipedia and Deezer lookups use: a name qualified
        // on the RIGHT is still that name.
        val albums = items(row("Spiderland (Remastered)", "Slint", key = "reissue"))
        assertEquals("reissue", RoonBrowse.pickAlbum(albums, "Spiderland", "Slint")!!
            .getString("item_key"))
    }

    @Test
    fun `a record by somebody else is refused`() {
        val albums = items(row("Cult", "Static-X", key = "stranger"))
        assertNull(RoonBrowse.pickAlbum(albums, "Cult", "To/Die/For"))
    }

    @Test
    fun `no artist to check against falls back to the title alone`() {
        val albums = items(row("Spiderland", "", key = "only"))
        assertEquals("only", RoonBrowse.pickAlbum(albums, "Spiderland", "")!!
            .getString("item_key"))
    }

    // ------------------------------------------------- THE GUARD THAT MATTERS

    @Test
    fun `Queue is taken and Play Now is not`() {
        /*
         * ROON'S ALBUM MENU OPENS WITH PLAY NOW. An app that reached for "the
         * first action" would stop whatever somebody is listening to and start
         * something else, from a tap on a suggestion. That is the failure this
         * whole file exists to prevent.
         */
        val actions = items(
            row("Play Now", hint = "action", key = "play"),
            row("Play From Here", hint = "action", key = "from-here"),
            row("Add Next", hint = "action", key = "next"),
            row("Queue", hint = "action", key = "queue"),
            row("Start Radio", hint = "action", key = "radio")
        )
        assertEquals("queue", RoonBrowse.pickQueueAction(actions)!!.getString("item_key"))
    }

    @Test
    fun `no Queue action means nothing is invoked at all`() {
        // An installation in another language lands here, and the right
        // outcome is a tap that falls back to opening the record in a
        // streaming service — NOT the nearest action that happens to exist.
        val actions = items(
            row("Play Now", hint = "action", key = "play"),
            row("Add Next", hint = "action", key = "next")
        )
        assertNull(RoonBrowse.pickQueueAction(actions))
    }

    @Test
    fun `something merely containing the word Queue is not the Queue action`() {
        val actions = items(
            row("Play Now and Clear Queue", hint = "action", key = "danger"),
            row("Queue All", hint = "action", key = "also-not-it")
        )
        assertNull(
            "only an action titled exactly Queue may be invoked",
            RoonBrowse.pickQueueAction(actions)
        )
    }

    @Test
    fun `an action with no key cannot be invoked`() {
        val actions = JSONArray("""[{"title":"Queue","hint":"action"}]""")
        assertNull(RoonBrowse.pickQueueAction(actions))
    }

    // ------------------------------------------------------- the action menu

    @Test
    fun `the action list is found by its hint, not its label`() {
        val screen = items(
            row("Tracks", hint = "list", key = "tracks"),
            row("Play Album", hint = "action_list", key = "menu")
        )
        assertEquals("menu", RoonBrowse.pickActionList(screen)!!.getString("item_key"))
    }

    @Test
    fun `a screen with no action list offers nothing`() {
        assertNull(RoonBrowse.pickActionList(items(row("Tracks", hint = "list"))))
    }

    // ------------------------------------------------------------- zone ids

    @Test
    fun `the zone id handed to Roon is Roon's own`() {
        // This app prefixes zone ids with their source so two sources cannot
        // collide on one room. Roon has never heard of that prefix.
        assertEquals("1601...", com.musicd.sharecard.roon.ZoneRefIds.raw("roon:1601..."))
        assertEquals("plain", com.musicd.sharecard.roon.ZoneRefIds.raw("plain"))
    }

    @Test
    fun `not being connected is an answer, not a crash`() {
        val browse = RoonBrowse { null }
        val outcome = browse.queueAlbum("Spiderland", "Slint", "roon:1")
        assertTrue(outcome.detail, !outcome.queued)
        assertTrue(outcome.detail, outcome.detail.contains("Roon"))
    }

    // ------------------------------------------------- what Roon said back

    /*
     * REPORTED FROM THE FIELD: a suggested album that IS in the library was
     * tapped on a Roon card and never arrived in the queue.
     *
     * Every one of these browse replies used to be thrown away. That cost two
     * separate things, and the second is very likely the shape of that report:
     * a refusal partway down the chain was followed by a `load` of whatever
     * screen was still open, so the reason finally given named the wrong step;
     * and the FINAL invoke — the one that queues — was never read at all, so
     * Roon refusing it reached the page as "Added to the end of the queue in
     * Roon". A tap that claims to have worked is the one failure nobody goes
     * looking for.
     */

    private fun reply(vararg pairs: Pair<String, Any?>) =
        JSONObject().apply { pairs.forEach { (k, v) -> if (v != null) put(k, v) } }

    @Test
    fun `a list answer is how the chain goes on`() {
        assertNull(RoonBrowse.refused(reply("action" to "list"), expectList = true))
    }

    @Test
    fun `an error is reported in Roon's own words`() {
        val why = RoonBrowse.refused(
            reply("is_error" to true, "message" to "Zone is not available"),
            expectList = true
        )
        assertEquals("Zone is not available", why)
    }

    @Test
    fun `is_error written as the number 1 is still an error`() {
        // optBoolean reads 1 as FALSE — the trap LmsClient.truthy already
        // exists for, and is_error is exactly the field an encoder writes as 1.
        assertNotNull(RoonBrowse.refused(reply("is_error" to 1, "message" to "No"), expectList = true))
        assertNotNull(RoonBrowse.refused(reply("is_error" to "true"), expectList = true))
        assertNull(RoonBrowse.refused(reply("is_error" to 0, "action" to "list"), expectList = true))
    }

    @Test
    fun `a message where a list was expected does not go on to load a stale one`() {
        val why = RoonBrowse.refused(
            reply("action" to "message", "message" to "This is not available"),
            expectList = true
        )
        assertEquals(
            "a browse that answered in words left the PREVIOUS screen loaded, and " +
                "the reason reported was whichever later step then failed",
            "This is not available", why
        )
    }

    @Test
    fun `the performed action answers in words, and that is success`() {
        // The last step must NOT expect a list: Roon answers a performed action
        // with action "message" and the words it would have put on screen.
        assertNull(
            RoonBrowse.refused(
                reply("action" to "message", "message" to "Added 12 tracks to queue"),
                expectList = false
            )
        )
        // But an error on that same step is still an error, and reporting it as
        // success is the bug this whole section is about.
        assertNotNull(
            RoonBrowse.refused(
                reply("action" to "message", "message" to "Zone is not available", "is_error" to true),
                expectList = false
            )
        )
    }

    @Test
    fun `an answer with no action at all is not taken for a list`() {
        assertNotNull(RoonBrowse.refused(reply("message" to "nothing here"), expectList = true))
        // And it still says something when Roon sent no words either.
        assertNotNull(RoonBrowse.refused(reply(), expectList = true))
    }
}
