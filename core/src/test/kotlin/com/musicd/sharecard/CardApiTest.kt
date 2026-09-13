package com.musicd.sharecard

import com.musicd.sharecard.api.ArtProxy
import com.musicd.sharecard.api.Assets
import com.musicd.sharecard.api.CardApi
import com.musicd.sharecard.http.Request
import com.musicd.sharecard.library.Normalize
import com.musicd.sharecard.meta.CacheStore
import com.musicd.sharecard.meta.Metadata
import com.musicd.sharecard.meta.Pitchfork
import com.musicd.sharecard.meta.Similar
import com.musicd.sharecard.meta.metadataHttpClient
import com.musicd.sharecard.sonos.Household
import com.musicd.sharecard.sonos.SonosSource
import com.musicd.sharecard.settings.Settings
import com.musicd.sharecard.meta.StreamingLinks
import com.musicd.sharecard.settings.SettingsStore
import com.musicd.sharecard.source.Sources
import com.musicd.sharecard.sonos.NowPlaying
import com.musicd.sharecard.sonos.TransportState
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The API as the page sees it.
 *
 * Driven through [CardApi.handle] with scripted players, so the JSON the
 * front-end actually parses is what is asserted — not an intermediate object
 * that a later change to the serialisation could quietly stop matching.
 */
class CardApiTest {

    private val topology = """
        <ZoneGroupState><ZoneGroups>
          <ZoneGroup Coordinator="RINCON_A" ID="g1">
            <ZoneGroupMember UUID="RINCON_A" ZoneName="Living Room"
              Location="http://10.0.0.1:1400/x"/>
          </ZoneGroup>
          <ZoneGroup Coordinator="RINCON_C" ID="g2">
            <ZoneGroupMember UUID="RINCON_C" ZoneName="Study"
              Location="http://10.0.0.3:1400/x"/>
          </ZoneGroup>
        </ZoneGroups></ZoneGroupState>
    """.trimIndent()

    private val players = mutableMapOf(
        "10.0.0.1" to FakePlayer("10.0.0.1"),
        "10.0.0.3" to FakePlayer("10.0.0.3")
    )

    private val assets = Assets { path ->
        when (path) {
            "index.html" -> "<!doctype html><title>Card</title>".toByteArray()
            "app.js" -> "// app".toByteArray()
            else -> null
        }
    }

    /**
     * EVERYTHING SWITCHED ON, which is not the default and is deliberate here.
     *
     * Zones are opt-in, so a CardApi built with no settings can answer about
     * nothing at all — and when that landed, every test in this file failed at
     * once, which is exactly the shape of what it does to a household on
     * upgrade. These tests are about zone selection, the chooser and the art
     * proxy, so they enable the fakes and get on with their own subject. The
     * DEFAULT is asserted on its own, once, in `a fresh install shows nothing
     * until a room is chosen`.
     */
    private fun everythingOn(sources: Sources): SettingsStore {
        val store = SettingsStore.inMemory()
        // Read through the UNFILTERED list: zones() is already filtered by the
        // very setting being written here.
        store.write(
            Settings(
                enabledZones = sources.allZones().map { it.id }.toSet(),
                // Services are opt-in too, so a link-row test that did not say
                // this would be asserting the default rather than the row.
                enabledServices = StreamingLinks.services().map { it.service }.toSet()
            )
        )
        return store
    }

    private fun api(shelf: CacheStore = CacheStore.NONE): CardApi {
        players.values.forEach { it.topology = topology }
        val http = metadataHttpClient()
        val household = Household(
            playerAt = { ip -> players.getValue(ip) },
            seedHosts = listOf("10.0.0.1"),
            discover = { emptyList() }
        )
        val sources = Sources(listOf(SonosSource(household)))
        return CardApi(
            sources,
            Metadata(http, "test", shelf),
            Pitchfork(http, "test"),
            ArtProxy(http),
            assets,
            "1.0.0",
            qobuz = com.musicd.sharecard.meta.QobuzAlbum(http, "test"),
            settingsStore = everythingOn(sources)
        )
    }

    /** A shelf holding exactly these entries, already in date. */
    private fun shelfWith(vararg entries: Pair<String, String>) = object : CacheStore {
        private val stamped = entries.associate { (key, value) ->
            key to System.currentTimeMillis().toString() + "|" + value
        }
        override fun load(namespace: String) =
            if (namespace == "similar") stamped else emptyMap()
        override fun put(namespace: String, key: String, value: String) {}
        override fun remove(namespace: String, key: String) {}
    }

    private fun apiWith(similar: Similar): CardApi {
        players.values.forEach { it.topology = topology }
        val http = metadataHttpClient()
        val household = Household(
            playerAt = { ip -> players.getValue(ip) },
            seedHosts = listOf("10.0.0.1"),
            discover = { emptyList() }
        )
        val sources = Sources(listOf(SonosSource(household)))
        return CardApi(
            sources,
            Metadata(http, "test"),
            Pitchfork(http, "test"),
            ArtProxy(http),
            assets,
            "1.0.0",
            qobuz = com.musicd.sharecard.meta.QobuzAlbum(http, "test"),
            similar = similar,
            settingsStore = everythingOn(sources)
        )
    }

    private fun get(path: String, query: Map<String, String> = emptyMap()) =
        Request("GET", path, query, emptyMap(), ByteArray(0), false, "10.0.0.99")

    /** A request from the app's own WebView, which is trusted without a PIN. */
    private fun onDevice(path: String, query: Map<String, String> = emptyMap()) =
        Request("GET", path, query, emptyMap(), ByteArray(0), false, "127.0.0.1")

    private fun json(path: String, query: Map<String, String> = emptyMap()): JSONObject {
        val response = api().handle(get(path, query))
        return JSONObject(String(response.body, Charsets.UTF_8))
    }

    // ---------------------------------------------------------------- cards

    @Test
    fun `the card names the album, the artist and the room`() {
        players["10.0.0.3"]!!.state = TransportState.PLAYING
        players["10.0.0.3"]!!.track = NowPlaying(
            track = "Good Morning, Captain",
            album = "Spiderland",
            artist = "Slint",
            artUri = "/getaa?u=x&v=53"
        )

        val body = json("/api/now-playing")
        assertTrue(body.getBoolean("playing"))
        assertEquals("Spiderland", body.getString("album"))
        assertEquals("Slint", body.getString("artist"))
        assertEquals("Study", body.getJSONObject("zone").getString("room"))
    }

    @Test
    fun `the cover is served from this app, never from the speaker`() {
        // A canvas that has drawn an image from another origin cannot be read
        // back, so a card that pointed the page at the player would fail at
        // toBlob with nothing to share.
        players["10.0.0.1"]!!.state = TransportState.PLAYING
        players["10.0.0.1"]!!.track =
            NowPlaying(album = "Mezzanine", artist = "Massive Attack", artUri = "/getaa?u=x&v=53")

        val art = json("/api/now-playing").getString("art")
        assertTrue("art must be served by this app: $art", art.startsWith("/api/art?u="))
        assertTrue("the player's own URL is carried in the query", art.contains("10.0.0.1"))
    }

    @Test
    fun `a record with no cover says so rather than pointing at nothing`() {
        players["10.0.0.1"]!!.state = TransportState.PLAYING
        players["10.0.0.1"]!!.track = NowPlaying(album = "Bootleg", artist = "Unknown")
        assertTrue(json("/api/now-playing").isNull("art"))
    }

    @Test
    fun `a silent household says why instead of drawing an empty card`() {
        val body = json("/api/now-playing")
        assertFalse(body.getBoolean("playing"))
        assertEquals("Nothing is playing.", body.getString("reason"))
    }

    @Test
    fun `a radio stream is flagged, and the announcement becomes the credit`() {
        players["10.0.0.1"]!!.state = TransportState.PLAYING
        players["10.0.0.1"]!!.track = NowPlaying(
            track = "BBC Radio 6 Music",
            streamContent = "Bill Callahan - Drover"
        )
        players["10.0.0.1"]!!.currentUri = "x-sonosapi-stream:s20455?sid=254"

        val body = json("/api/now-playing")
        assertTrue(body.getBoolean("stream"))
        assertEquals("Bill Callahan", body.getString("artist"))
    }

    @Test
    fun `naming a zone pins the card to that room`() {
        players["10.0.0.1"]!!.state = TransportState.PAUSED
        players["10.0.0.1"]!!.track = NowPlaying(album = "Blue", artist = "Joni Mitchell")

        // THE PREFIXED ID, which is what the picker actually sends. This asked
        // with a bare "RINCON_A", which names no source — so the lookup could
        // not resolve it and the answer came back through the fallback ladder
        // instead. It happened to name the right room, because that was the
        // only room with anything in it, and the test passed while proving
        // nothing about pinning.
        val body = json("/api/now-playing", mapOf("zone" to "sonos:RINCON_A"))
        assertEquals("Living Room", body.getJSONObject("zone").getString("room"))
        assertEquals("Blue", body.getString("album"))
    }

    @Test
    fun `a named room that is idle says so, and does not answer for another`() {
        // THE REPORTED BUG. The picker said WiiM Pro Plus, the WiiM was idle,
        // and the card said "Playing in Stereo Fives via Roon" — an answer
        // about a different room entirely. Each zone is independent.
        players["10.0.0.1"]!!.state = TransportState.PLAYING
        players["10.0.0.1"]!!.track = NowPlaying(album = "Blue", artist = "Joni Mitchell")
        players["10.0.0.3"]!!.state = TransportState.STOPPED
        players["10.0.0.3"]!!.track = NowPlaying()

        val body = json("/api/now-playing", mapOf("zone" to "sonos:RINCON_C"))
        assertFalse(body.getBoolean("playing"))
        assertFalse("it must not describe the room that IS playing", body.has("album"))
        // And it names the room that is quiet rather than shrugging at the
        // house, which is a different statement when music is on elsewhere.
        assertEquals("Nothing is playing in Study.", body.getString("reason"))
    }

    @Test
    fun `whatever's playing is not pinned to whoever answered last`() {
        // Answering once used to set preferredZoneId, so the next card without
        // a zone came from that room by preference. "Whatever's playing" has
        // to keep meaning that.
        players["10.0.0.1"]!!.state = TransportState.PLAYING
        players["10.0.0.1"]!!.track = NowPlaying(album = "Blue", artist = "Joni Mitchell")
        val api = api()

        JSONObject(String(api.handle(get("/api/now-playing")).body, Charsets.UTF_8))
        val selected = JSONObject(String(api.handle(get("/api/zones")).body, Charsets.UTF_8))
        assertTrue("nothing was chosen, so nothing is selected", selected.isNull("selected"))
    }

    @Test
    fun `two rooms on offers the choice instead of picking one`() {
        // Answered as a single card, "whatever's playing" had to choose a room
        // and silently discard the other. The person looking chooses now.
        players["10.0.0.1"]!!.state = TransportState.PLAYING
        players["10.0.0.1"]!!.track = NowPlaying(album = "Blue", artist = "Joni Mitchell")
        players["10.0.0.3"]!!.state = TransportState.PLAYING
        players["10.0.0.3"]!!.track = NowPlaying(album = "Spiderland", artist = "Slint")

        val body = json("/api/now-playing")
        assertTrue(body.optBoolean("choose"))
        // No card, and it says so: the page hides the card's own rows on this.
        assertFalse(body.getBoolean("playing"))
        assertFalse("a chooser is not a card", body.has("album"))

        val rooms = body.getJSONArray("rooms")
        assertEquals(2, rooms.length())
        val byRoom = (0 until rooms.length()).associate {
            rooms.getJSONObject(it).getString("name") to rooms.getJSONObject(it)
        }
        assertEquals("Blue", byRoom.getValue("Living Room").getString("album"))
        assertEquals("Spiderland", byRoom.getValue("Study").getString("album"))
        // The tile has to lead somewhere, and that is the zone's own card.
        assertTrue(byRoom.getValue("Study").getString("uid").startsWith("sonos:"))
    }

    @Test
    fun `one room on still draws the card, because one room is not a choice`() {
        // A grid of one tile costs a tap and shows nothing the card would not.
        players["10.0.0.1"]!!.state = TransportState.PLAYING
        players["10.0.0.1"]!!.track = NowPlaying(album = "Blue", artist = "Joni Mitchell")
        players["10.0.0.3"]!!.state = TransportState.STOPPED
        players["10.0.0.3"]!!.track = NowPlaying()

        val body = json("/api/now-playing")
        assertFalse("one room on is not a chooser", body.optBoolean("choose"))
        assertTrue(body.getBoolean("playing"))
        assertEquals("Blue", body.getString("album"))
    }

    @Test
    fun `naming a zone never opens the chooser, however many rooms are on`() {
        // A named zone is a lock. Two rooms on is a choice only when the
        // question was about the house.
        players["10.0.0.1"]!!.state = TransportState.PLAYING
        players["10.0.0.1"]!!.track = NowPlaying(album = "Blue", artist = "Joni Mitchell")
        players["10.0.0.3"]!!.state = TransportState.PLAYING
        players["10.0.0.3"]!!.track = NowPlaying(album = "Spiderland", artist = "Slint")

        val body = json("/api/now-playing", mapOf("zone" to "sonos:RINCON_C"))
        assertFalse(body.optBoolean("choose"))
        assertEquals("Spiderland", body.getString("album"))
        assertEquals("Study", body.getJSONObject("zone").getString("room"))
    }

    @Test
    fun `the server streaming the music may serve its cover`() {
        // THE REPORTED CASE, end to end. A MusicD Server on a DietPi box
        // streamed to a Sonos and put an absolute art URL on its own address
        // into the DIDL. That host is not a speaker and not public https, so
        // the proxy refused it and the card drew with a blank sleeve while the
        // Sonos app showed the cover.
        players["10.0.0.1"]!!.state = TransportState.PLAYING
        players["10.0.0.1"]!!.track = NowPlaying(
            album = "Heaven or Las Vegas",
            artist = "Cocteau Twins",
            uri = "http://192.168.0.57:3400/stream/track/1234.flac",
            artUri = "http://192.168.0.57:3400/art/YTpDb2N0ZWF1IFR3aW5z"
        )

        val body = json("/api/now-playing", mapOf("zone" to "sonos:RINCON_A"))
        assertEquals("Heaven or Las Vegas", body.getString("album"))
        // The card names the proxy, never the server directly — a canvas that
        // has drawn a cross-origin image cannot be read back.
        assertTrue(body.getString("art"), body.getString("art").startsWith("/api/art?u="))

        // And the proxy will now actually fetch it, because the transport said
        // that host is the one playing the music.
        val household = Household(
            playerAt = { ip -> players.getValue(ip) },
            seedHosts = listOf("10.0.0.1"),
            discover = { emptyList() }
        )
        val sonos = SonosSource(household)
        assertFalse(
            "nothing is allowed before anything has been seen playing",
            sonos.artHosts().contains("192.168.0.57")
        )
        sonos.nowPlaying(null)
        assertTrue(
            "the streaming host must be allowed once it has been seen",
            sonos.artHosts().contains("192.168.0.57")
        )
        // The speakers are still known in their own right.
        assertTrue(sonos.artHosts().contains("10.0.0.1"))
    }

    // ---------------------------------------------------------------- zones

    @Test
    fun `the zone list names every playable group`() {
        val zones = json("/api/zones").getJSONArray("zones")
        assertEquals(2, zones.length())
        val names = (0 until zones.length()).map { zones.getJSONObject(it).getString("room") }
        assertTrue(names.containsAll(listOf("Living Room", "Study")))
    }

    @Test
    fun `health reports the version the page was served by`() {
        val body = json("/api/health")
        assertTrue(body.getBoolean("ok"))
        assertEquals("1.0.0", body.getString("version"))
    }

    // ------------------------------------------------------------ the page

    @Test
    fun `the root path serves the page`() {
        val response = api().handle(get("/"))
        assertEquals(200, response.status)
        assertTrue(response.contentType.startsWith("text/html"))
    }

    @Test
    fun `a file that is not in the bundle is a 404`() {
        assertEquals(404, api().handle(get("/nope.js")).status)
    }

    @Test
    fun `a traversing path cannot escape the bundle`() {
        assertEquals(404, api().handle(get("/../../etc/passwd")).status)
        assertEquals(404, api().handle(get("/assets/../../secret")).status)
    }

    @Test
    fun `a read route still refuses a POST`() {
        // Webhooks brought POST and DELETE into the app, and the top-level
        // method gate had to widen for them. That must not quietly make every
        // route writable: the routes that change nothing stay GET-only.
        for (path in listOf(
            "/api/now-playing", "/api/zones", "/api/extras", "/api/health",
            // Reading what version is published changes nothing either, so it
            // belongs on this list and not on the write list beside it.
            "/api/update/status", "/"
        )) {
            val post = Request("POST", path, emptyMap(), emptyMap(), ByteArray(0), false)
            assertEquals("$path must refuse a POST", 405, api().handle(post).status)
        }
    }

    // ----------------------------------------------------------------- links

    @Test
    fun `extras carries somewhere to hear the record`() {
        // fast=1 is the cache-only path, so this asks nothing of the network —
        // which is the point: the links are a function of the album and the
        // artist, so they are on screen with the first paint rather than after
        // a metadata lookup that may never come back.
        val body = json(
            "/api/extras",
            mapOf("album" to "Laughing Stock", "artist" to "Talk Talk", "fast" to "1")
        )
        val links = body.getJSONArray("links")
        assertEquals(7, links.length())

        val services = (0 until links.length()).map { links.getJSONObject(it).getString("service") }
        assertEquals(
            listOf("qobuz", "tidal", "spotify", "apple", "amazon", "deezer", "bandcamp"), services
        )
        for (i in 0 until links.length()) {
            val url = links.getJSONObject(i).getString("url")
            assertTrue("$url is not https", url.startsWith("https://"))
            assertTrue("$url does not name the record", url.contains("Laughing%20Stock"))
        }
    }

    /**
     * The words on the card come from a Wikipedia article and the card credits
     * it in small type — but the article itself was never offered, so the one
     * source the blurb actually came from was the only thing on the page you
     * could not follow. Metadata has carried that URL since the port.
     *
     * PRIMED THROUGH THE CACHE rather than the network: en.wikipedia.org is
     * hard-coded in the lookup and there is nothing here to point at a mock.
     * The shelf is the supported way in, `fast=1` reads it without opening a
     * socket, and it exercises the encode/decode round trip on the way past.
     */
    @Test
    fun `extras carries the article the blurb was taken from`() {
        val key = Normalize.text("Laughing Stock") + "||" + Normalize.text("Talk Talk")
        val article = "https://en.wikipedia.org/wiki/Laughing_Stock"
        val remembered = mapOf(
            key to System.currentTimeMillis().toString() + "|" + JSONObject()
                .put("year", 1991)
                .put("bio", "The fifth and final studio album by Talk Talk.")
                .put("src", "Wikipedia")
                .put("url", article)
                .toString()
        )
        val shelf = object : CacheStore {
            override fun load(namespace: String) =
                if (namespace == "extras") remembered else emptyMap()
            override fun put(namespace: String, key: String, value: String) {}
            override fun remove(namespace: String, key: String) {}
        }

        val body = JSONObject(
            String(
                api(shelf).handle(
                    get(
                        "/api/extras",
                        mapOf("album" to "Laughing Stock", "artist" to "Talk Talk", "fast" to "1")
                    )
                ).body,
                Charsets.UTF_8
            )
        )
        assertEquals("Wikipedia", body.getString("bioSource"))
        assertEquals(article, body.getString("bioUrl"))
        // And the blurb it belongs to, so a URL can never arrive on its own
        // and label a chip for words that are not on the card.
        assertTrue(body.getString("bio").isNotEmpty())
    }

    // --------------------------------------------------------------- similar

    /**
     * Acts to hear next, primed through the shelf so this opens no socket.
     *
     * The URL is the part worth asserting. It is built on the SERVER by
     * StreamingLinks, because Qobuz's search 404s without a storefront segment
     * and the query rides in the path so a space has to be %20 — three rules
     * that already have tests, and that a copy in app.js would drift from.
     */
    @Test
    fun `similar acts come back with a link the page does not have to build`() {
        val remembered = mapOf(
            Normalize.text("Talk Talk") to
                System.currentTimeMillis().toString() + "|" + JSONArray()
                    .put(
                        JSONObject().put("n", "Bark Psychosis").put("a", "Hex").put("y", 1994)
                    )
                    .put(JSONObject().put("n", "Slint"))
                    .toString()
        )
        val shelf = object : CacheStore {
            override fun load(namespace: String) =
                if (namespace == "similar") remembered else emptyMap()
            override fun put(namespace: String, key: String, value: String) {}
            override fun remove(namespace: String, key: String) {}
        }
        val http = metadataHttpClient()
        val api = apiWith(similar = Similar(http, "test", store = shelf))

        val body = JSONObject(
            String(
                api.handle(
                    get("/api/similar", mapOf("artist" to "Talk Talk", "fast" to "1"))
                ).body,
                Charsets.UTF_8
            )
        )
        val acts = body.getJSONArray("acts")
        assertEquals(2, acts.length())
        assertEquals("Bark Psychosis", acts.getJSONObject(0).getString("name"))
        assertEquals("Hex", acts.getJSONObject(0).getString("album"))
        assertEquals(1994, acts.getJSONObject(0).getInt("year"))

        for (i in 0 until acts.length()) {
            val url = acts.getJSONObject(i).getString("url")
            assertTrue("$url is not https", url.startsWith("https://"))
            assertTrue("$url has no Qobuz storefront and would 404", url.contains("/search/?q="))
            assertFalse("a form-encoded space is searched for literally", url.contains("+"))
        }
        // An act with no record named still gets a link, for the act.
        assertTrue(acts.getJSONObject(1).isNull("album"))
        assertTrue(acts.getJSONObject(1).getString("url").contains("Slint"))
    }

    /**
     * Which service a suggestion links to is the page's choice, held on the
     * device, and named on the request. The URL is still built here.
     */
    @Test
    fun `the suggestion link follows the service the page asked for`() {
        val api = apiWith(similar = Similar(metadataHttpClient(), "test", store = shelfWith(
            Normalize.text("Talk Talk") to JSONArray()
                .put(JSONObject().put("n", "Bark Psychosis").put("a", "Hex").put("y", 1994))
                .toString()
        )))

        fun urlFor(service: String?): String {
            val query = mutableMapOf("artist" to "Talk Talk", "fast" to "1")
            if (service != null) query["service"] = service
            val body = JSONObject(
                String(api.handle(get("/api/similar", query)).body, Charsets.UTF_8)
            )
            return body.getJSONArray("acts").getJSONObject(0).getString("url")
        }

        assertTrue(urlFor("tidal").startsWith("https://tidal.com/search?q="))
        assertTrue(urlFor("bandcamp").startsWith("https://bandcamp.com/search?q="))
        assertTrue(urlFor("apple").startsWith("https://music.apple.com/search?term="))
        // Qobuz is the default, and it keeps its storefront segment — without
        // one that search is a 404, which is the whole reason this is built
        // here rather than in the page.
        assertTrue(urlFor(null).contains("/search/?q="))
        assertTrue(urlFor(null).startsWith("https://www.qobuz.com/"))
        // A service nobody offers falls back rather than failing: a link to
        // the wrong shop beats a 500 on a row of suggestions.
        assertTrue(urlFor("napster").startsWith("https://www.qobuz.com/"))

        // Whichever service it is, the record is what gets searched for.
        for (service in listOf("tidal", "spotify", "deezer")) {
            assertTrue(urlFor(service).contains("Bark%20Psychosis%20Hex"))
        }
    }

    @Test
    fun `similar with no artist named is a bad request, and it refuses a POST`() {
        assertEquals(400, api().handle(get("/api/similar")).status)
        // A read route that answers a POST is how the webhook gate was widened
        // by accident once. Everything not named in WRITE_ROUTES is GET-only.
        val post = Request("POST", "/api/similar", mapOf("artist" to "Slint"), emptyMap(),
            ByteArray(0), false, "10.0.0.99")
        assertEquals(405, api().handle(post).status)
    }

    /** With no lookup wired in, the row simply never appears. */
    @Test
    fun `similar answers an empty row rather than an error when it is switched off`() {
        val body = json("/api/similar", mapOf("artist" to "Slint", "fast" to "1"))
        assertEquals(0, body.getJSONArray("acts").length())
    }

    @Test
    fun `the Qobuz album lookup is its own route and never blocks the card`() {
        // fast=1 is cache-only, so this asks Qobuz nothing. An empty cache
        // answers "no link", which is the same shape as a record Qobuz does
        // not carry — the page keeps its search chip either way.
        val body = json(
            "/api/qobuz",
            mapOf("album" to "Mezzanine", "artist" to "Massive Attack", "fast" to "1")
        )
        assertTrue("a miss must be a null url, not an error", body.isNull("url"))

        // And it stays off /api/extras: folding a rate-gated page fetch into
        // the metadata would hold the whole card back for one link.
        val extras = json(
            "/api/extras",
            mapOf("album" to "Mezzanine", "artist" to "Massive Attack", "fast" to "1")
        )
        assertFalse(extras.has("qobuzUrl"))
    }

    @Test
    fun `the Qobuz route refuses a POST like every other read`() {
        val post = Request("POST", "/api/qobuz", emptyMap(), emptyMap(), ByteArray(0), false)
        assertEquals(405, api().handle(post).status)
    }

    @Test
    fun `an album with no name is linked nowhere`() {
        val response = api().handle(get("/api/extras", mapOf("album" to "", "fast" to "1")))
        assertEquals(400, response.status)
    }

    // --------------------------------------------------------------- updates

    /**
     * The same API with a working updater, pointed at an address that refuses
     * a connection immediately — these tests are about the gate and the method,
     * not about what a manifest says.
     */
    private fun apiWithUpdater(): CardApi {
        val http = metadataHttpClient()
        val household = Household(
            playerAt = { ip -> players.getValue(ip) },
            seedHosts = listOf("10.0.0.1"),
            discover = { emptyList() }
        )
        return CardApi(
            Sources(listOf(SonosSource(household))),
            Metadata(http, "test"),
            Pitchfork(http, "test"),
            ArtProxy(http),
            assets,
            "1.0.0",
            updater = com.musicd.sharecard.meta.Updater(
                http = http,
                currentVersion = "1.0.0",
                manifestUrl = "https://127.0.0.1:1/latest.json",
                downloadDir = java.nio.file.Files.createTempDirectory("api-update").toFile(),
                install = { throw AssertionError("no install in an API test") }
            )
        )
    }

    private fun post(path: String, from: String) =
        Request("POST", path, emptyMap(), emptyMap(), ByteArray(0), false, from)

    @Test
    fun `a host that cannot install an APK says so instead of erroring`() {
        // A desktop browser pointed at this app must not be shown an update
        // button. The page reads "available: false" as nothing to offer, so
        // this answers in that shape rather than with a 404 or a 501.
        val body = json("/api/update/status")
        assertFalse(body.getBoolean("available"))
        assertFalse(body.getBoolean("supported"))
    }

    /**
     * The update bar is drawn only on the device running the app, and this is
     * the field it hangs on.
     *
     * The APK installs HERE. A page open on an iPad across the house is looking
     * at software it cannot replace — its Update button asked for a PIN and
     * then offered to update a machine in another room, which is not what
     * anybody pressing it meant. Reported as "shows the update button, does
     * nothing".
     */
    @Test
    fun `only the device itself is told an update is its to install`() {
        val api = apiWithUpdater()

        val onDevice = api.handle(
            Request("GET", "/api/update/status", emptyMap(), emptyMap(), ByteArray(0), false, "127.0.0.1")
        )
        assertTrue(JSONObject(String(onDevice.body, Charsets.UTF_8)).getBoolean("onDevice"))

        val acrossTheHouse = api.handle(
            Request("GET", "/api/update/status", emptyMap(), emptyMap(), ByteArray(0), false, "192.168.0.50")
        )
        assertFalse(JSONObject(String(acrossTheHouse.body, Charsets.UTF_8)).getBoolean("onDevice"))
    }

    @Test
    fun `installing an update needs the PIN from anywhere but the device`() {
        // Adding a webhook is gated because a webhook URL is a credential.
        // Replacing the APK on an always-on device in another room is a good
        // deal more than that, and is gated the same way.
        val api = apiWithUpdater()
        assertEquals(401, api.handle(post("/api/update/check", "192.168.0.50")).status)
        assertEquals(401, api.handle(post("/api/update/apply", "192.168.0.50")).status)

        // From the app's own WebView there is no PIN to type.
        assertEquals(200, api.handle(post("/api/update/check", "127.0.0.1")).status)
    }

    @Test
    fun `an update is never started by a GET`() {
        // The gate would hold either way, but a GET that installs software is
        // one a link prefetch or a browser's speculative fetch can fire on its
        // own, from the very device that is trusted without a PIN.
        val api = apiWithUpdater()
        for (path in listOf("/api/update/check", "/api/update/apply")) {
            val response = api.handle(
                Request("GET", path, emptyMap(), emptyMap(), ByteArray(0), false, "127.0.0.1")
            )
            assertEquals("$path must refuse a GET", 405, response.status)
        }
    }

    @Test
    fun `an unknown method is refused outright`() {
        val put = Request("PUT", "/api/webhooks", emptyMap(), emptyMap(), ByteArray(0), false)
        assertEquals(405, api().handle(put).status)
    }

    @Test
    fun `the art route refuses a URL off the local network`() {
        val response = api().handle(get("/api/art", mapOf("u" to "https://example.com/x.jpg")))
        assertEquals(404, response.status)
    }

    @Test
    fun `extras answers from cache without opening a socket when fast is set`() {
        // Nothing has been looked up, so every field is empty — the point is
        // that it RETURNS, promptly, rather than going to MusicBrainz.
        val body = json("/api/extras", mapOf("album" to "Spiderland", "artist" to "Slint", "fast" to "1"))
        assertTrue(body.getBoolean("cached"))
        assertTrue(body.isNull("release"))
        assertNotNull(body)
    }

    @Test
    fun `the diagnostics page carries what the shell knows, crash included`() {
        // A crash from the last launch explains more than anything else on that
        // page, and the device that crashed is usually in another room — so it
        // has to reach the browser, not just the device's own screen.
        val api = CardApi(
            Sources(
                listOf(
                    SonosSource(
                        Household(
                            playerAt = { ip -> players.getValue(ip) },
                            seedHosts = listOf("10.0.0.1"),
                            discover = { emptyList() },
                            scan = {
                                com.musicd.sharecard.sonos.SonosScan.Result(emptyList(), emptyList())
                            }
                        )
                    )
                )
            ),
            Metadata(metadataHttpClient(), "test"),
            Pitchfork(metadataHttpClient(), "test"),
            ArtProxy(metadataHttpClient()),
            assets,
            "1.0.0",
            hostNotes = { listOf("Last crash:\njava.lang.IllegalStateException: boom") }
        )
        val body = JSONObject(String(api.handle(get("/api/debug")).body, Charsets.UTF_8))
        val app = body.getJSONArray("app").getString(0)
        assertTrue("the trace must survive to the page: $app", app.contains("boom"))
    }

    @Test
    fun `a shell that throws while reporting does not take the page down`() {
        // The diagnostics page is what somebody reaches for when the app is
        // already misbehaving. It must not be the next thing to fail.
        val api = CardApi(
            Sources(
                listOf(
                    SonosSource(
                        Household(
                            playerAt = { ip -> players.getValue(ip) },
                            seedHosts = listOf("10.0.0.1"),
                            discover = { emptyList() },
                            scan = {
                                com.musicd.sharecard.sonos.SonosScan.Result(emptyList(), emptyList())
                            }
                        )
                    )
                )
            ),
            Metadata(metadataHttpClient(), "test"),
            Pitchfork(metadataHttpClient(), "test"),
            ArtProxy(metadataHttpClient()),
            assets,
            "1.0.0",
            hostNotes = { throw RuntimeException("reading the crash log failed") }
        )
        assertEquals(200, api.handle(get("/api/debug")).status)
    }

    // --------------------------------------------------------------- webhooks

    private fun apiWith(store: com.musicd.sharecard.webhook.WebhookStore) = CardApi(
        Sources(listOf(SonosSource(Household(
            playerAt = { ip -> players.getValue(ip) },
            seedHosts = listOf("10.0.0.1"),
            discover = { emptyList() },
            scan = { com.musicd.sharecard.sonos.SonosScan.Result(emptyList(), emptyList()) }
        )))),
        Metadata(metadataHttpClient(), "test"),
        Pitchfork(metadataHttpClient(), "test"),
        ArtProxy(metadataHttpClient()),
        assets,
        "1.0.0",
        webhooks = store
    )

    private fun post(path: String, body: String, from: String, query: Map<String, String> = emptyMap()) =
        Request("POST", path, query, emptyMap(), body.toByteArray(), false, from)

    private val discordUrl = "https://discord.com/api/webhooks/1234567890/abcdefghijklmnop"

    /**
     * THE PROPERTY THIS WHOLE FEATURE TURNS ON. A webhook URL is a credential:
     * anyone holding it can post to that channel from anywhere, forever. It is
     * typed once and must never be readable back out over the network.
     */
    @Test
    fun `no route ever hands back a webhook URL`() {
        val store = com.musicd.sharecard.webhook.WebhookStore.inMemory("123456")
        val api = apiWith(store)
        api.handle(post("/api/webhooks", """{"name":"Vinyl","url":"$discordUrl"}""", "127.0.0.1"))

        for (path in listOf("/api/webhooks", "/api/setup", "/api/debug")) {
            val body = String(api.handle(get(path)).body, Charsets.UTF_8)
            assertFalse(
                "$path leaked the webhook token: $body",
                body.contains("abcdefghijklmnop")
            )
        }
    }

    @Test
    fun `the device itself can add a webhook without a PIN`() {
        val store = com.musicd.sharecard.webhook.WebhookStore.inMemory("123456")
        val api = apiWith(store)
        val response = api.handle(
            post("/api/webhooks", """{"name":"Vinyl","url":"$discordUrl"}""", "127.0.0.1")
        )
        assertEquals(200, response.status)
        assertEquals(1, store.all().size)
        assertEquals("Vinyl", store.all()[0].name)
    }

    @Test
    fun `another device cannot add one without the PIN`() {
        val store = com.musicd.sharecard.webhook.WebhookStore.inMemory("123456")
        val api = apiWith(store)
        val refused = api.handle(
            post("/api/webhooks", """{"name":"Vinyl","url":"$discordUrl"}""", "192.168.0.50")
        )
        assertEquals(401, refused.status)
        assertTrue("nothing may have been stored", store.all().isEmpty())

        val allowed = api.handle(
            post(
                "/api/webhooks", """{"name":"Vinyl","url":"$discordUrl"}""", "192.168.0.50",
                mapOf("pin" to "123456")
            )
        )
        assertEquals(200, allowed.status)
        assertEquals(1, store.all().size)
    }

    @Test
    fun `another device cannot remove one without the PIN`() {
        val store = com.musicd.sharecard.webhook.WebhookStore.inMemory("123456")
        val api = apiWith(store)
        api.handle(post("/api/webhooks", """{"name":"Vinyl","url":"$discordUrl"}""", "127.0.0.1"))
        val id = store.all()[0].id

        val refused = Request("DELETE", "/api/webhooks/$id", emptyMap(), emptyMap(), ByteArray(0), false, "192.168.0.50")
        assertEquals(401, api.handle(refused).status)
        assertEquals(1, store.all().size)

        val allowed = Request(
            "DELETE", "/api/webhooks/$id", mapOf("pin" to "123456"), emptyMap(),
            ByteArray(0), false, "192.168.0.50"
        )
        assertEquals(200, api.handle(allowed).status)
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `the PIN is shown to the device and to nobody else`() {
        val store = com.musicd.sharecard.webhook.WebhookStore.inMemory("123456")
        val api = apiWith(store)

        val fromDevice = JSONObject(String(api.handle(onDevice("/api/setup")).body, Charsets.UTF_8))
        assertEquals("123456", fromDevice.getString("pin"))
        assertTrue(fromDevice.getBoolean("onDevice"))

        val remote = Request("GET", "/api/setup", emptyMap(), emptyMap(), ByteArray(0), false, "192.168.0.50")
        val across = JSONObject(String(api.handle(remote).body, Charsets.UTF_8))
        assertTrue("a PIN served to the LAN is decoration", across.isNull("pin"))
        assertFalse(across.getBoolean("mayConfigure"))
    }

    @Test
    fun `a URL that is not a Discord webhook is refused with a reason`() {
        val store = com.musicd.sharecard.webhook.WebhookStore.inMemory("123456")
        val api = apiWith(store)
        val response = api.handle(
            post("/api/webhooks", """{"name":"x","url":"https://example.com/hook"}""", "127.0.0.1")
        )
        assertEquals(400, response.status)
        assertTrue(String(response.body, Charsets.UTF_8).contains("example.com"))
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `the listing is masked and says whether this device may configure`() {
        val store = com.musicd.sharecard.webhook.WebhookStore.inMemory("123456")
        val api = apiWith(store)
        api.handle(post("/api/webhooks", """{"name":"Vinyl","url":"$discordUrl"}""", "127.0.0.1"))

        val body = JSONObject(String(api.handle(onDevice("/api/webhooks")).body, Charsets.UTF_8))
        val first = body.getJSONArray("webhooks").getJSONObject(0)
        assertEquals("Vinyl", first.getString("name"))
        assertTrue(first.getString("masked").contains("1234567890"))
        assertFalse(first.has("url"))
        assertTrue(body.getBoolean("mayConfigure"))
    }

    @Test
    fun `posting a card needs no PIN, because that is the everyday action`() {
        // The worst this offers a stranger on the wifi is posting a picture of
        // the owner's own album to the owner's own channel. Adding a webhook is
        // where the damage would be, and that is gated.
        val store = com.musicd.sharecard.webhook.WebhookStore.inMemory("123456")
        val api = apiWith(store)
        api.handle(post("/api/webhooks", """{"name":"Vinyl","url":"$discordUrl"}""", "127.0.0.1"))
        val id = store.all()[0].id

        val response = api.handle(
            Request(
                "POST", "/api/webhooks/$id/post", emptyMap(), emptyMap(),
                ByteArray(0), false, "192.168.0.50"
            )
        )
        // Rejected for having no card, NOT for having no PIN.
        assertEquals(400, response.status)
        assertTrue(String(response.body, Charsets.UTF_8).contains("No card"))
    }

    @Test
    fun `setting a picture is gated like every other change to a webhook`() {
        val store = com.musicd.sharecard.webhook.WebhookStore.inMemory("123456")
        val api = apiWith(store)
        api.handle(post("/api/webhooks", """{"name":"Vinyl","url":"$discordUrl"}""", "127.0.0.1"))
        val id = store.all()[0].id

        val refused = Request(
            "POST", "/api/webhooks/$id/avatar", emptyMap(), emptyMap(),
            "data:image/png;base64,iVBORw0KGgo=".toByteArray(), false, "192.168.0.50"
        )
        assertEquals(401, api.handle(refused).status)
    }

    @Test
    fun `the bundled icons are served, so iOS has one to use`() {
        // Without an apple-touch-icon, adding the page to a Home Screen gets a
        // screenshot or a bare letter.
        val icons = CardApi(
            Sources(emptyList()),
            Metadata(metadataHttpClient(), "test"),
            Pitchfork(metadataHttpClient(), "test"),
            ArtProxy(metadataHttpClient()),
            Assets { path -> if (path.startsWith("icons/")) byteArrayOf(1, 2, 3) else null },
            "1.0.0"
        )
        val response = icons.handle(get("/icons/apple-touch-icon.png"))
        assertEquals(200, response.status)
        assertEquals("image/png", response.contentType)
    }

    @Test
    fun `extras with no album named is a bad request`() {
        assertEquals(400, api().handle(get("/api/extras")).status)
    }
}
