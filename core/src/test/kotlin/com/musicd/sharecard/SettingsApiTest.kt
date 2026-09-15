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
    fun `every streaming service is listed and off`() {
        // Opt-in, like the rooms. Reported as a bug when they defaulted on.
        val services = get(api(), "/api/settings").getJSONArray("services")
        assertTrue(services.length() >= 7)
        for (i in 0 until services.length()) {
            assertFalse(
                services.getJSONObject(i).getString("id") + " should start switched off",
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
        val api = api(Settings(enabledZones = setOf(loungeId), enabledServices = setOf("tidal")))
        post(api, "/api/settings", """{"zones":{"$kitchenId":true}}""", "127.0.0.1")

        assertEquals(setOf(loungeId, kitchenId), store.read().enabledZones)
        assertEquals(setOf("tidal"), store.read().enabledServices)
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
        val api = api(
            Settings(
                enabledZones = setOf(kitchenId),
                // Everything on EXCEPT tidal, so the assertion below is about
                // the switch and not about the default.
                enabledServices = setOf("qobuz", "spotify", "apple", "amazon", "deezer", "bandcamp")
            )
        )
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
        val api = api(Settings(enabledZones = setOf(kitchenId), enabledServices = setOf("tidal")))
        val answer = get(
            api, "/api/qobuz",
            mapOf("album" to "Spiderland", "artist" to "Slint")
        )
        assertTrue("a switched-off Qobuz must resolve nothing", answer.isNull("url"))
    }

    // ------------------------------------------------- the gate, turned off

    private fun openApi(settings: Settings = Settings()): CardApi {
        source = FakeSource("Sonos", mapOf("Kitchen" to playing("Kitchen", "Spiderland")))
        store = SettingsStore.inMemory(settings)
        val http = metadataHttpClient()
        return CardApi(
            Sources(listOf(source)),
            Metadata(http, "test"),
            Pitchfork(http, "test"),
            ArtProxy(http),
            Assets { null },
            "1.0.0",
            settingsStore = store,
            // What the container does unless SHARECARD_PIN is set.
            requirePin = false
        )
    }

    @Test
    fun `with the gate off any device on the network may configure`() {
        /*
         * THE CONTAINER'S DEFAULT, AND IT IS THE OPPOSITE OF ANDROID'S. On a
         * phone loopback is somebody standing at the device and the PIN is on
         * that screen; a container usually has no browser on it at all, so the
         * gate applied to everybody and the PIN had to be dug out of the log
         * to switch a room on. Asked for directly.
         */
        val api = openApi()
        val answer = post(api, "/api/settings", """{"zones":{"$kitchenId":true}}""", "10.0.0.99")
        assertEquals(200, answer.status)
        assertEquals(setOf(kitchenId), store.read().enabledZones)
    }

    @Test
    fun `and the page is told there is nothing to type`() {
        // Otherwise every settings screen draws a PIN box that does nothing.
        val open = get(openApi(), "/api/setup")
        assertFalse(open.getBoolean("needsPin"))
        assertTrue(open.getBoolean("mayConfigure"))

        // The Android build is unchanged: a browser that is not the device
        // still has to prove itself.
        val gated = get(api(), "/api/setup")
        assertTrue(gated.getBoolean("needsPin"))
        assertFalse(gated.getBoolean("mayConfigure"))
    }

    @Test
    fun `the gate being off does not hand out a webhook url`() {
        /*
         * The rule that survives regardless: no route returns a webhook URL,
         * only its mask. Turning the gate off widens who may CHANGE things; it
         * must not widen what can be READ back.
         */
        val api = openApi()
        post(
            api, "/api/webhooks",
            """{"name":"Music","url":"https://discord.com/api/webhooks/1/""" +
                "a".repeat(68) + """"}""",
            "10.0.0.99"
        )
        val listed = get(api, "/api/webhooks").toString()
        assertFalse("a webhook URL escaped: $listed", listed.contains("a".repeat(68)))
    }

    // ---------------------------------------------------------- the reviews

    @Test
    fun `the reviews screen lists both kinds with their own defaults`() {
        val reviews = get(api(), "/api/settings").getJSONArray("reviews")
        val byId = (0 until reviews.length()).associate {
            val r = reviews.getJSONObject(it)
            r.getString("id") to r
        }
        assertTrue("the album sources start on", byId.getValue("wikipedia").getBoolean("enabled"))
        assertTrue(byId.getValue("pitchfork").getBoolean("enabled"))
        assertTrue(byId.getValue("allmusic").getBoolean("enabled"))
        // Asked for, rather than arriving unasked.
        assertFalse(byId.getValue("wikipedia-artist").getBoolean("enabled"))
        assertFalse(byId.getValue("allmusic-artist").getBoolean("enabled"))
        // The page groups on this.
        assertEquals("album", byId.getValue("wikipedia").getString("kind"))
        assertEquals("artist", byId.getValue("wikipedia-artist").getString("kind"))
    }

    @Test
    fun `switching a review source off is remembered without disturbing the rest`() {
        val api = api()
        post(api, "/api/settings", """{"reviews":{"pitchfork":false}}""", "127.0.0.1")
        assertFalse(store.read().reviewEnabled("pitchfork"))
        assertTrue("switching a score off must not take the blurb with it",
            store.read().reviewEnabled("wikipedia"))
    }

    @Test
    fun `a review source this version has never heard of is refused`() {
        val api = api()
        post(api, "/api/settings", """{"reviews":{"not-a-source":true}}""", "127.0.0.1")
        assertFalse(store.read().enabledReviews.orEmpty().contains("not-a-source"))
    }

    @Test
    fun `switching AllMusic on puts a chip in the reading row`() {
        val api = api(Settings(enabledZones = setOf(kitchenId)))
        val reading = get(
            api, "/api/extras",
            mapOf("album" to "Spiderland", "artist" to "Slint", "fast" to "1")
        ).getJSONArray("reading")

        val names = (0 until reading.length()).map { reading.getJSONObject(it).getString("name") }
        assertTrue("AllMusic is on by default and should be offered: $names",
            names.any { it == "AllMusic" })
        // The artist sources are off, so nothing about Slint-the-band appears.
        assertFalse(names.any { it.startsWith("AllMusic: ") })
        assertFalse(names.any { it.startsWith("Wikipedia: ") })
    }

    @Test
    fun `switching the artist sources on adds them and nothing else`() {
        val api = api(
            Settings(enabledZones = setOf(kitchenId))
                .withReview("allmusic-artist", true)
        )
        val reading = get(
            api, "/api/extras",
            mapOf("album" to "Spiderland", "artist" to "Slint", "fast" to "1")
        ).getJSONArray("reading")
        val names = (0 until reading.length()).map { reading.getJSONObject(it).getString("name") }
        // THE CHIP SAYS WHICH SOURCE AND WHICH KIND, AND NOT WHO. It used to
        // carry the artist — "AllMusic: Slint" — and a Roon card credited to
        // four people made a chip six lines deep in a four-column grid, which
        // took the whole row with it. See LinkChipsTest.
        assertTrue("the artist chip says it is the artist one: $names",
            names.any { it == "AllMusic artist" })
        assertTrue("a chip label is carrying the record's artist: $names",
            names.none { it.contains("Slint") })
    }

    @Test
    fun `switching every review source off leaves the reading row empty`() {
        var settings = Settings(enabledZones = setOf(kitchenId))
        for (id in com.musicd.sharecard.meta.Reviews.IDS) settings = settings.withReview(id, false)
        val answer = get(
            api(settings), "/api/extras",
            mapOf("album" to "Spiderland", "artist" to "Slint", "fast" to "1")
        )
        assertEquals(0, answer.getJSONArray("reading").length())
        // And the words that would have gone ON the card are gone with them.
        assertTrue(answer.isNull("bio"))
        assertTrue(answer.isNull("score"))
    }

    // ------------------------------------------------------- queueing

    private fun roonApi(requirePin: Boolean = false): Pair<CardApi, MutableList<String>> {
        val calls = mutableListOf<String>()
        source = FakeSource("Sonos", mapOf("Kitchen" to playing("Kitchen", "Spiderland")))
        store = SettingsStore.inMemory()
        val http = metadataHttpClient()
        val browse = com.musicd.sharecard.roon.RoonBrowse { null }
        val api = CardApi(
            Sources(listOf(source)),
            Metadata(http, "test"),
            Pitchfork(http, "test"),
            ArtProxy(http),
            Assets { null },
            "1.0.0",
            settingsStore = store,
            requirePin = requirePin,
            roonBrowse = browse
        )
        return api to calls
    }

    @Test
    fun `queueing refuses a GET`() {
        // A GET that starts or changes playback is one a link prefetch can
        // fire by itself.
        val (api, _) = roonApi()
        val answer = api.handle(
            Request("GET", "/api/queue", emptyMap(), emptyMap(), ByteArray(0), false, "127.0.0.1")
        )
        assertEquals(405, answer.status)
    }

    @Test
    fun `queueing refuses a room whose source has no queue`() {
        /*
         * "Add to the end of the queue" names no queue on a Sonos or a UPnP
         * renderer, and picking a room on somebody's behalf would be choosing
         * which room to play into. The card has to already be about a room
         * that can take one.
         *
         * ASSERTED AS THE INVARIANT RATHER THAN THE WORDING. This used to
         * require the refusal to contain the word "Roon", which was true while
         * Roon was the only source with a queue — and it then failed on the
         * change that added Lyrion and made the message name neither. Assert
         * what must be TRUE (a source with no queue is refused, and nothing is
         * guessed at), not the sentence it is refused in.
         */
        val (api, _) = roonApi()
        val answer = post(
            api, "/api/queue",
            """{"album":"Spiderland","artist":"Slint","zone":"$kitchenId"}""",
            "127.0.0.1"
        )
        assertEquals(400, answer.status)
        assertFalse(
            "a refused room must not be reported as queued",
            String(answer.body).contains("\"queued\":true")
        )
    }

    @Test
    fun `queueing needs an album`() {
        val (api, _) = roonApi()
        val answer = post(
            api, "/api/queue",
            """{"album":"","artist":"Slint","zone":"roon:1"}""",
            "127.0.0.1"
        )
        assertEquals(400, answer.status)
    }

    @Test
    fun `queueing is gated when a PIN is required`() {
        // It reaches into somebody's listening room, so it sits behind the
        // same gate as adding a webhook.
        val (api, _) = roonApi(requirePin = true)
        val answer = post(
            api, "/api/queue",
            """{"album":"Spiderland","artist":"Slint","zone":"roon:1"}""",
            "10.0.0.99"
        )
        assertEquals(401, answer.status)
    }

    /**
     * The same api, with Lyrion's queue wired instead of Roon's.
     *
     * Pointed at NO server, because what is under test here is the ROUTE —
     * that a `lyrion:` zone is dispatched to Lyrion rather than refused, and
     * that a player id made of colons survives the prefix coming off. The
     * wire itself is [LmsQueueTest]'s job, against a real HTTP server.
     */
    private fun lyrionApi(): CardApi {
        source = FakeSource("Sonos", mapOf("Kitchen" to playing("Kitchen", "Spiderland")))
        store = SettingsStore.inMemory()
        val http = metadataHttpClient()
        val queue = com.musicd.sharecard.lms.LmsQueue(
            com.musicd.sharecard.lms.LmsClient(http)
        ) { null }
        return CardApi(
            Sources(listOf(source)),
            Metadata(http, "test"),
            Pitchfork(http, "test"),
            ArtProxy(http),
            Assets { null },
            "1.0.0",
            settingsStore = store,
            lmsQueue = queue
        )
    }

    @Test
    fun `a Lyrion room is dispatched to Lyrion, not refused`() {
        /*
         * 400 is "that room cannot take a queue" and 200 is "Lyrion was asked
         * and said no". The difference between them IS the dispatch, and it is
         * the whole of what this route added — a `lyrion:` zone that fell into
         * the else branch would look identical to a Sonos one from the page.
         */
        val answer = post(
            lyrionApi(), "/api/queue",
            """{"album":"Spiderland","artist":"Slint","zone":"lyrion:00:04:20:aa:bb:cc"}""",
            "127.0.0.1"
        )
        assertEquals(200, answer.status)
        val body = JSONObject(String(answer.body))
        assertFalse(body.getBoolean("queued"))
        assertTrue(body.getString("detail"), body.getString("detail").isNotEmpty())
    }

    @Test
    fun `a player id made of colons survives the prefix coming off`() {
        /*
         * A LYRION PLAYER IS NAMED BY ITS MAC ADDRESS, which is the one raw id
         * in this app made of colons — so splitting at the LAST colon rather
         * than the first would hand the server "cc" and queue into nothing,
         * silently. `ZoneRef.rawOf` is what the route passes through.
         */
        assertEquals(
            "00:04:20:aa:bb:cc",
            ZoneRef.rawOf("lyrion:00:04:20:aa:bb:cc")
        )
        assertEquals("lyrion", ZoneRef.sourceOf("lyrion:00:04:20:aa:bb:cc"))
    }

    @Test
    fun `a Roon zone with no connection answers rather than throwing`() {
        val (api, _) = roonApi()
        val answer = post(
            api, "/api/queue",
            """{"album":"Spiderland","artist":"Slint","zone":"roon:1"}""",
            "127.0.0.1"
        )
        assertEquals(200, answer.status)
        val body = JSONObject(String(answer.body))
        assertFalse(body.getBoolean("queued"))
        assertTrue(body.getString("detail"), body.getString("detail").isNotEmpty())
    }

    // ------------------------------------------- what this build tells the page

    /*
     * ASKED FOR: the version at the foot of the settings menu, so "which
     * version are you on" stops being the first line of every bug report. It
     * was only ever in /api/debug, which is a wall of facts somebody has to be
     * told to open.
     *
     * AND THE VARIANT, which is not cosmetic: the action row draws a Download
     * button on Android and not on the container, where the browser saves the
     * picture better than a button can. /api/update/status has carried the
     * variant all along, but that is fetched for the update bar and may not
     * have landed by the time the first card is painted.
     */
    @Test
    fun `setup says which build this is and which shell it is`() {
        val answer = get(api(), "/api/setup")
        assertEquals("1.0.0", answer.getString("version"))
        assertEquals("android", answer.getString("variant"))
    }

    @Test
    fun `no updater is answered as android, which keeps the button`() {
        // A shell that cannot install anything says nothing about whether the
        // browser can save an image — and the safe answer is the one that
        // KEEPS a control rather than the one that removes it on a guess.
        val answer = get(api(), "/api/setup")
        assertEquals("android", answer.getString("variant"))
        assertFalse(
            "the version must never be the literal string null on Android",
            answer.getString("version") == "null"
        )
    }
}
