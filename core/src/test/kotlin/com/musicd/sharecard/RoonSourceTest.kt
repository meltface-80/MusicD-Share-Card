package com.musicd.sharecard

import com.musicd.sharecard.roon.Zone
import com.musicd.sharecard.roon.ZoneStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading Roon's zone feed.
 *
 * THIS IS THE WHOLE REASON ROON IS A SOURCE. Roon streaming to a Sonos speaker
 * hands it "Roon" + 32 hex characters where the title should be, no artist, no
 * album and no cover — a card made from the speaker's view of it is headed with
 * a hash. Asked directly, Roon has all four.
 *
 * `three_line` is the shape that matters and the order is easy to get wrong:
 * line1 is the TRACK, line2 the ARTIST, line3 the ALBUM. Read in the wrong
 * order it produces a card headed with a track name, which looks almost right.
 */
class RoonSourceTest {

    private fun zoneJson(
        id: String = "1601ca9c5f",
        name: String = "Stereo Fives",
        state: String = "playing",
        line1: String = "Pushit",
        line2: String = "TOOL",
        line3: String = "Ænima",
        imageKey: String? = "abc123"
    ): JSONObject {
        val threeLine = JSONObject().put("line1", line1).put("line2", line2).put("line3", line3)
        val nowPlaying = JSONObject().put("three_line", threeLine)
        if (imageKey != null) nowPlaying.put("image_key", imageKey)
        return JSONObject()
            .put("zone_id", id)
            .put("display_name", name)
            .put("state", state)
            .put("now_playing", nowPlaying)
    }

    @Test
    fun `three_line puts the album on line3 and the artist on line2`() {
        val zone = Zone.parse(zoneJson())
        val np = zone.nowPlaying!!
        assertEquals("Pushit", np.line1)
        assertEquals("TOOL", np.line2)
        assertEquals("Ænima", np.line3)
        assertTrue(zone.isPlaying)
    }

    @Test
    fun `two_line is used when three_line is absent`() {
        // Not every source gives Roon three lines; the parser falls back rather
        // than reporting an empty now-playing.
        val json = JSONObject()
            .put("zone_id", "z").put("display_name", "Study").put("state", "playing")
            .put(
                "now_playing",
                JSONObject().put(
                    "two_line",
                    JSONObject().put("line1", "Drover").put("line2", "Bill Callahan")
                )
            )
        val np = Zone.parse(json).nowPlaying!!
        assertEquals("Drover", np.line1)
        assertEquals("Bill Callahan", np.line2)
        assertEquals("", np.line3)
    }

    @Test
    fun `a zone with nothing loaded reports no now-playing`() {
        val json = JSONObject()
            .put("zone_id", "z").put("display_name", "Kitchen").put("state", "stopped")
        val zone = Zone.parse(json)
        assertNull(zone.nowPlaying)
        assertTrue(!zone.isPlaying)
    }

    @Test
    fun `the image key survives, because it is the whole cover story`() {
        assertEquals("abc123", Zone.parse(zoneJson()).nowPlaying!!.imageKey)
        assertNull(Zone.parse(zoneJson(imageKey = null)).nowPlaying!!.imageKey)
    }

    // ------------------------------------------------------------ zone store

    @Test
    fun `the subscribed message loads the household`() {
        val store = ZoneStore()
        store.applySubscribed(
            JSONObject().put(
                "zones",
                org.json.JSONArray()
                    .put(zoneJson(id = "a", name = "Stereo Fives"))
                    .put(zoneJson(id = "b", name = "Study", state = "paused"))
            )
        )
        assertEquals(2, store.all().size)
        assertEquals("Stereo Fives", store.byId("a")?.displayName)
        assertTrue(store.byId("a")!!.isPlaying)
        assertTrue(!store.byId("b")!!.isPlaying)
    }

    @Test
    fun `a changed message updates one zone without disturbing the others`() {
        val store = ZoneStore()
        store.applySubscribed(
            JSONObject().put(
                "zones",
                org.json.JSONArray().put(zoneJson(id = "a")).put(zoneJson(id = "b", name = "Study"))
            )
        )
        store.applyChanged(
            JSONObject().put(
                "zones_changed",
                org.json.JSONArray().put(zoneJson(id = "a", line3 = "Lateralus"))
            )
        )
        assertEquals("Lateralus", store.byId("a")?.nowPlaying?.line3)
        assertEquals("Ænima", store.byId("b")?.nowPlaying?.line3)
    }

    @Test
    fun `a removed zone goes`() {
        val store = ZoneStore()
        store.applySubscribed(
            JSONObject().put("zones", org.json.JSONArray().put(zoneJson(id = "a")))
        )
        store.applyChanged(
            JSONObject().put("zones_removed", org.json.JSONArray().put("a"))
        )
        assertTrue(store.all().isEmpty())
    }
}
