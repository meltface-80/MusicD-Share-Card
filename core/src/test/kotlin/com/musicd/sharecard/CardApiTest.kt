package com.musicd.sharecard

import com.musicd.sharecard.api.ArtProxy
import com.musicd.sharecard.api.Assets
import com.musicd.sharecard.api.CardApi
import com.musicd.sharecard.http.Request
import com.musicd.sharecard.meta.Metadata
import com.musicd.sharecard.meta.Pitchfork
import com.musicd.sharecard.meta.metadataHttpClient
import com.musicd.sharecard.sonos.Household
import com.musicd.sharecard.sonos.SonosSource
import com.musicd.sharecard.source.Sources
import com.musicd.sharecard.sonos.NowPlaying
import com.musicd.sharecard.sonos.TransportState
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

    private fun api(): CardApi {
        players.values.forEach { it.topology = topology }
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
            "1.0.0"
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

        val body = json("/api/now-playing", mapOf("zone" to "RINCON_A"))
        assertEquals("Living Room", body.getJSONObject("zone").getString("room"))
        assertEquals("Blue", body.getString("album"))
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
        for (path in listOf("/api/now-playing", "/api/zones", "/api/extras", "/api/health", "/")) {
            val post = Request("POST", path, emptyMap(), emptyMap(), ByteArray(0), false)
            assertEquals("$path must refuse a POST", 405, api().handle(post).status)
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
    fun `extras with no album named is a bad request`() {
        assertEquals(400, api().handle(get("/api/extras")).status)
    }
}
