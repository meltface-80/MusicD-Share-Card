package com.musicd.sharecard

import com.musicd.sharecard.api.ArtProxy
import com.musicd.sharecard.api.Assets
import com.musicd.sharecard.api.CardApi
import com.musicd.sharecard.http.Request
import com.musicd.sharecard.meta.Metadata
import com.musicd.sharecard.meta.Pitchfork
import com.musicd.sharecard.meta.metadataHttpClient
import com.musicd.sharecard.settings.Settings
import com.musicd.sharecard.settings.SettingsStore
import com.musicd.sharecard.source.PlayState
import com.musicd.sharecard.source.Playing
import com.musicd.sharecard.source.Source
import com.musicd.sharecard.source.Sources
import com.musicd.sharecard.source.ZoneRef
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settings screen, through the routes it is actually driven by.
 *
 * THE FIRST TEST HERE IS THE WHOLE PRODUCT DECISION. A fresh install finds the
 * house and shows nothing, because rooms are opt-in — and the message it shows
 * must send somebody to Settings rather than to their router.
 */
class SettingsApiTest {

    private class FakeSource(
        override val name: String,
        private val rooms: Map<String, Playing>
    ) : Source {
        val askedFor = ArrayList<String?>()
        override fun start() {}
        override fun zones() = rooms.keys.map { ZoneRef(name, ZoneRef.idFor(name, it), it) }
        override fun nowPlaying(zoneId: String?): Playing? {
            askedFor += zoneId
            if (zoneId != null) return rooms[zoneId]
            return rooms.values.firstOrNull { it.state.isPlaying }
        }
        override fun artHosts() = listOf("fake.local")
    }

    private fun playing(zone: String, album: String) = Playing(
        source = "Sonos",
        zoneId = ZoneRef.idFor("Sonos", zone),
        zoneName = zone,
        album = album,
        artist = "Slint",
        state = PlayState.PLAYING
    )

    private val kitchenId = ZoneRef.idFor("Sonos", "Kitchen")
    private val loungeId = ZoneRef.idFor("Sonos", "Lounge")

    private lateinit var source: FakeSource
    private lateinit var store: SettingsStore

    private fun api(settings: Settings = Settings()): CardApi {
        source = FakeSource(
            "Sonos",
            mapOf(
                "Kitchen" to playing("Kitchen", "Spiderland"),
                "Lounge" to playing("Lounge", "Loveless")
            )
        )
        store = SettingsStore.inMemory(settings)
        val http = metadataHttpClient()
        return CardApi(
            Sources(listOf(source)),
            Metadata(http, "test"),
            Pitchfork(http, "test"),
            ArtProxy(http),
            Assets { null },
            "1.0.0",
            settingsStore = store
        )
    }

    private fun get(api: CardApi, path: String, query: Map<String, String> = emptyMap()) =
        JSONObject(
            String(
                api.handle(Request("GET", path, query, emptyMap(), ByteArray(0), false, "10.0.0.99"))
                    .body,
                Charsets.UTF_8
            )
        )

    private fun post(api: CardApi, path: String, body: String, from: String) =
        api.handle(Request("POST", path, emptyMap(), emptyMap(), body.toByteArray(), false, from))

    // ------------------------------------------------------- a fresh install

    @Test
    fun `a fresh install finds the rooms and shows none of them`() {
        val api = api()

        // The picker is empty: nothing may be chosen that was not switched on.
        assertEquals(0, get(api, "/api/zones").getJSONArray("zones").length())

        val card = get(api, "/api/now-playing", mapOf("zone" to ""))
        assertFalse(card.getBoolean("playing"))
        // AND THE MESSAGE MATTERS AS MUCH AS THE BEHAVIOUR. "No players found"
        // would send somebody to hosts.txt, multicast and VLANs for a problem
        // whose fix is two taps.
        assertTrue(
            "the empty state must point at Settings, not the network: " +
                card.getString("reason"),
            card.getString("reason").contains("Settings")
        )
    }

    @Test
    fun `the settings screen lists the rooms that are switched off`() {
        val api = api()
        val zones = get(api, "/api/settings", mapOf("zones" to "1")).getJSONArray("zones")
        // Everywhere else a switched-off room does not exist. Here it must.
        assertEquals(2, zones.length())
        assertFalse(zones.getJSONObject(0).getBoolean("enabled"))
        assertFalse(get(api, "/api/settings").getBoolean("anyZoneEnabled"))
    }

    @Test
    fun `every streaming service is listed and on`() {
        val services = get(api(), "/api/settings").getJSONArray("services")
        assertTrue(services.length() >= 7)
        for (i in 0 until services.length()) {
            assertTrue(
                services.getJSONObject(i).getString("id"),
                services.getJSONObject(i).getBoolean("enabled")
            )
        }
    }

    // --------------------------------------------------------- switching on

    @Test
    fun `enabling a room puts it in the picker and lets it answer`() {
        val api = api()
        post(api, "/api/settings", """{"zones":{"$kitchenId":true}}""", "127.0.0.1")

        val zones = get(api, "/api/zones").getJSONArray("zones")
        assertEquals(1, zones.length())
        assertEquals("Kitchen", zones.getJSONObject(0).getString("name"))

        val card = get(api, "/api/now-playing", mapOf("zone" to ""))
        assertTrue(card.getBoolean("playing"))
        assertEquals("Spiderland", card.getString("album"))
    }

    @Test
    fun `a room that is still switched off is never asked`() {
        val api = api()
        post(api, "/api/settings", """{"zones":{"$kitchenId":true}}""", "127.0.0.1")
        source.askedFor.clear()

        get(api, "/api/now-playing", mapOf("zone" to loungeId))
        assertTrue(
            "the switched-off speaker was reached: ${source.askedFor}",
            source.askedFor.none { it == "Lounge" }
        )
    }

    @Test
    fun `the choice survives being read back`() {
        val api = api()
        post(api, "/api/settings", """{"zones":{"$kitchenId":true}}""", "127.0.0.1")
        assertEquals(setOf(kitchenId), store.read().enabledZones)
        assertTrue(get(api, "/api/settings").getBoolean("anyZoneEnabled"))
    }

    @Test
    fun `naming only one thing leaves the rest alone`() {
        // Partial on purpose: two devices with the page open must not
        // overwrite each other with a stale snapshot of everything.
        val api = api(Settings(enabledZones = setOf(loungeId), disabledServices = setOf("tidal")))
        post(api, "/api/settings", """{"zones":{"$kitchenId":true}}""", "127.0.0.1")

        assertEquals(setOf(loungeId, kitchenId), store.read().enabledZones)
        assertEquals(setOf("tidal"), store.read().disabledServices)
    }

    // ------------------------------------------------------------ the gate

    @Test
    fun `changing settings from another device needs the PIN`() {
        val api = api()
        val refused = post(api, "/api/settings", """{"zones":{"$kitchenId":true}}""", "10.0.0.99")
        assertEquals(401, refused.status)
        // And nothing was written.
        assertTrue(store.read().noZonesChosen)
    }

    @Test
    fun `settings refuses a GET that tries to write`() {
        // A GET that changes configuration is one a link prefetch can fire.
        val api = api()
        get(api, "/api/settings", mapOf("zones" to """{"$kitchenId":true}"""))
        assertTrue(store.read().noZonesChosen)
    }

    // --------------------------------------------------------- the services

    @Test
    fun `a switched-off service loses its link`() {
        val api = api(Settings(enabledZones = setOf(kitchenId), disabledServices = setOf("tidal")))
        val links = get(
            api, "/api/extras",
            mapOf("album" to "Spiderland", "artist" to "Slint", "fast" to "1")
        ).getJSONArray("links")

        val ids = (0 until links.length()).map { links.getJSONObject(it).getString("service") }
        assertFalse("tidal is switched off and must not be linked", ids.contains("tidal"))
        assertTrue("everything else still links", ids.contains("spotify"))
    }

    @Test
    fun `switching Qobuz off stops the lookup, not just the chip`() {
        /*
         * QOBUZ IS THE ONE SERVICE WITH A REQUEST BEHIND IT — it reads a page
         * off www.qobuz.com to resolve a real album id, because a search URL
         * can never open that app on a record. Every other chip is a string
         * built from the album and the artist. So "no background processes"
         * means something here and nowhere else, and hiding the chip in the
         * page would have left the request running.
         */
        val api = api(Settings(enabledZones = setOf(kitchenId), disabledServices = setOf("qobuz")))
        val answer = get(
            api, "/api/qobuz",
            mapOf("album" to "Spiderland", "artist" to "Slint")
        )
        assertTrue("a switched-off Qobuz must resolve nothing", answer.isNull("url"))
    }
}
