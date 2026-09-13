package com.musicd.sharecard

import com.musicd.sharecard.api.Access
import com.musicd.sharecard.http.Request
import com.musicd.sharecard.webhook.DiscordPoster
import com.musicd.sharecard.webhook.Webhook
import com.musicd.sharecard.webhook.WebhookRejected
import com.musicd.sharecard.webhook.WebhookUrls
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Webhooks, and the credential they are.
 *
 * A Discord webhook URL lets whoever holds it post to that channel from
 * anywhere on the internet, for as long as it exists. Until now this app held
 * no secrets at all, which is the entire reason it could answer the whole LAN
 * without a password — so the rules around this one are the point, not
 * decoration.
 */
class WebhookTest {

    private val real = "https://discord.com/api/webhooks/1234567890/abcdefghijklmnop"

    // ---------------------------------------------------------- what is taken

    @Test
    fun `a Discord webhook URL is accepted`() {
        assertEquals(real, WebhookUrls.validate(real))
        assertEquals(
            real.replace("discord.com", "discordapp.com"),
            WebhookUrls.validate(real.replace("discord.com", "discordapp.com"))
        )
    }

    @Test
    fun `whitespace around a pasted URL is forgiven`() {
        assertEquals(real, WebhookUrls.validate("  $real\n"))
    }

    /**
     * Accepting any URL would make this a general-purpose poster that anyone on
     * the LAN could aim at a host of their choosing — an SSRF with a file
     * attached.
     */
    @Test
    fun `anything that is not a Discord webhook is refused`() {
        for (bad in listOf(
            "https://example.com/api/webhooks/1/2",
            "https://evil.example/discord.com/api/webhooks/1/2",
            "http://discord.com/api/webhooks/1234567890/abcdefghijklmnop",
            "https://discord.com/channels/123/456",
            "https://169.254.169.254/api/webhooks/1/2",
            "not a url",
            ""
        )) {
            var refused = false
            try {
                WebhookUrls.validate(bad)
            } catch (e: WebhookRejected) {
                refused = true
            }
            assertTrue("should have been refused: $bad", refused)
        }
    }

    @Test
    fun `the refusal says what is actually wrong`() {
        // "Invalid" tells somebody nothing about which half they got wrong.
        val plainHttp = runCatching {
            WebhookUrls.validate("http://discord.com/api/webhooks/1/abcdefghij")
        }.exceptionOrNull()
        assertTrue(plainHttp!!.message!!.contains("https"))

        val channelLink = runCatching {
            WebhookUrls.validate("https://discord.com/channels/1/2")
        }.exceptionOrNull()
        assertTrue(channelLink!!.message!!.contains("Integrations"))

        val elsewhere = runCatching {
            WebhookUrls.validate("https://example.com/api/webhooks/1/abcdefghij")
        }.exceptionOrNull()
        assertTrue(elsewhere!!.message!!.contains("example.com"))
    }

    // -------------------------------------------------------------- the mask

    @Test
    fun `the mask never carries enough of the token to use it`() {
        val masked = Webhook("id", "Vinyl chat", real).masked
        assertFalse("the token must not survive masking", masked.contains("abcdefghijklmnop"))
        assertFalse(masked.contains("efghijklmnop"))
        // Enough to tell two webhooks apart, and no more.
        assertTrue(masked.contains("1234567890"))
        assertTrue(masked.contains("abcd"))
    }

    @Test
    fun `the id is not the token`() {
        val id = WebhookUrls.idFor(real)
        assertFalse("an id ends up in a path and a log line", id.contains("abcdefghijklmnop"))
        assertEquals("the same URL always gets the same id", id, WebhookUrls.idFor(real))
    }

    // ------------------------------------------------------------ the gate

    private fun request(from: String, pin: String? = null) = Request(
        "POST", "/api/webhooks",
        if (pin != null) mapOf("pin" to pin) else emptyMap(),
        emptyMap(), ByteArray(0), false, from
    )

    @Test
    fun `the device itself needs no PIN`() {
        // Standing in front of the device is a stronger claim than a PIN typed
        // from across the house.
        val access = Access(pin = { "123456" })
        assertTrue(access.mayConfigure(request("127.0.0.1")))
        assertTrue(access.mayConfigure(request("::1")))
    }

    @Test
    fun `another device needs the right PIN`() {
        val access = Access(pin = { "123456" })
        assertFalse(access.mayConfigure(request("192.168.0.50")))
        assertFalse(access.mayConfigure(request("192.168.0.50", pin = "")))
        assertFalse(access.mayConfigure(request("192.168.0.50", pin = "000000")))
        assertFalse(access.mayConfigure(request("192.168.0.50", pin = "12345")))
        assertTrue(access.mayConfigure(request("192.168.0.50", pin = "123456")))
    }

    @Test
    fun `an empty PIN on the server does not open the gate`() {
        // A store that has not minted one yet must not accept "" as a match.
        val access = Access(pin = { "" })
        assertFalse(access.mayConfigure(request("192.168.0.50", pin = "")))
        assertFalse(access.mayConfigure(request("192.168.0.50", pin = "000000")))
    }

    // ------------------------------------------------------------ the post

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun hook() = Webhook("id", "Vinyl chat", server.url("/api/webhooks/1/token").toString())

    /**
     * THE CARD AND NOTHING ELSE.
     *
     * This used to put "**Album** by Artist" in the message's content, so
     * Discord drew a line of text above the picture saying exactly what the
     * picture says, in worse type. The card is the message.
     */
    @Test
    fun `the card is sent as a file, with no text above it and no pings`() {
        server.enqueue(MockResponse().setResponseCode(204))
        val outcome = DiscordPoster(OkHttpClient())
            .post(hook(), byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
        assertTrue(outcome.detail, outcome.ok)

        val sent = server.takeRequest()
        assertEquals("POST", sent.method)
        val body = sent.body.readUtf8()
        assertTrue("must be multipart", sent.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
        assertTrue("the picture is a file part", body.contains("files[0]"))
        assertFalse("nothing may be written above the card", body.contains("\"content\""))
        // Nothing here may notify a server, with or without text to ping from.
        assertTrue("pings must be suppressed", body.contains("\"allowed_mentions\""))
        assertTrue(body.contains("\"parse\":[]"))
        // And the payload is still valid JSON with the content field gone.
        val payload = Regex("\\{\"[\\s\\S]*?}}").find(body)!!.value
        assertEquals("[parse]", org.json.JSONObject(payload).getJSONObject("allowed_mentions").keySet().toString())
    }

    /**
     * As close to "posted by me" as a webhook is allowed to get.
     *
     * Discord lets a webhook set a display name and avatar per message. It does
     * NOT let it drop the APP tag — that is deliberate, so a reader can always
     * tell a person from an integration, and no field turns it off. Posting as
     * the account itself would mean driving a user token, which is self-botting
     * and against Discord's terms.
     */
    @Test
    fun `a display name and avatar ride along when set`() {
        server.enqueue(MockResponse().setResponseCode(204))
        val hook = Webhook(
            "id", "Vinyl chat", server.url("/api/webhooks/1/token").toString(),
            username = "Menzies",
            avatarUrl = "https://cdn.discordapp.com/avatars/1/2.png"
        )
        DiscordPoster(OkHttpClient()).post(hook, byteArrayOf(1))
        val body = server.takeRequest().body.readUtf8()
        assertTrue("the name must be sent: $body", body.contains("\"username\":\"Menzies\""))
        assertTrue(body.contains("avatar_url"))
        assertTrue(body.contains("cdn.discordapp.com"))
    }

    @Test
    fun `neither is sent when unset, so Discord's own settings still apply`() {
        server.enqueue(MockResponse().setResponseCode(204))
        DiscordPoster(OkHttpClient()).post(hook(), byteArrayOf(1))
        val body = server.takeRequest().body.readUtf8()
        assertFalse("an empty username would blank the webhook's own name", body.contains("username"))
        assertFalse(body.contains("avatar_url"))
    }

    @Test
    fun `a name Discord would refuse is caught here instead of in the channel`() {
        for (bad in listOf("Clyde", "my discord bot", "x".repeat(81))) {
            var refused = false
            try {
                WebhookUrls.validateUsername(bad)
            } catch (e: WebhookRejected) {
                refused = true
            }
            assertTrue("should have been refused: $bad", refused)
        }
        assertEquals("Menzies", WebhookUrls.validateUsername("  Menzies "))
        assertEquals("", WebhookUrls.validateUsername("   "))
    }

    @Test
    fun `an avatar Discord could never fetch is refused with the reason`() {
        // Discord fetches the avatar itself, so a LAN address is invisible to
        // it and would silently fall back to the default picture.
        val local = runCatching { WebhookUrls.validateAvatar("https://192.168.0.5/me.png") }
            .exceptionOrNull()
        assertTrue(local!!.message!!.contains("public"))

        val plain = runCatching { WebhookUrls.validateAvatar("http://example.com/me.png") }
            .exceptionOrNull()
        assertTrue(plain!!.message!!.contains("https"))

        assertEquals(
            "https://cdn.discordapp.com/avatars/1/2.png",
            WebhookUrls.validateAvatar("https://cdn.discordapp.com/avatars/1/2.png")
        )
        assertEquals("", WebhookUrls.validateAvatar(""))
    }

    /**
     * A photo, not a URL.
     *
     * Discord fetches an `avatar_url` from its own servers, so a picture this
     * app served from a home network would be invisible to it — and a photo on
     * a phone has no URL at all. Editing the webhook hands Discord the bytes
     * instead, and it keeps them.
     */
    @Test
    fun `an uploaded picture is PATCHed onto the webhook itself`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val outcome = DiscordPoster(OkHttpClient())
            .setAvatar(hook(), "data:image/png;base64,iVBORw0KGgo=", "Menzies")
        assertTrue(outcome.detail, outcome.ok)

        val sent = server.takeRequest()
        assertEquals("editing the webhook, not posting to it", "PATCH", sent.method)
        val body = sent.body.readUtf8()
        assertTrue("the bytes must be sent: $body", body.contains("data:image/png;base64"))
        assertTrue("and the name alongside them", body.contains("Menzies"))
    }

    @Test
    fun `something that is not an image is refused before Discord sees it`() {
        val outcome = DiscordPoster(OkHttpClient())
            .setAvatar(hook(), "https://example.com/me.png")
        assertFalse(outcome.ok)
        assertEquals("nothing should have been sent", 0, server.requestCount)
    }

    @Test
    fun `an unscaled phone photo is caught here, not as an opaque 400`() {
        // A camera photo is several thousand pixels wide; the page scales it to
        // 128 before sending. One that skipped that is refused with a reason.
        val huge = "data:image/png;base64," + "A".repeat(500_000)
        val outcome = DiscordPoster(OkHttpClient()).setAvatar(hook(), huge)
        assertFalse(outcome.ok)
        assertTrue(outcome.detail, outcome.detail.contains("too large"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a deleted webhook is reported in words, not as a status code`() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("{}"))
        val outcome = DiscordPoster(OkHttpClient()).post(hook(), byteArrayOf(1))
        assertFalse(outcome.ok)
        assertTrue(outcome.detail, outcome.detail.contains("no longer exists"))
    }

    @Test
    fun `rate limiting says to try again rather than looking broken`() {
        server.enqueue(MockResponse().setResponseCode(429).setBody("{}"))
        val outcome = DiscordPoster(OkHttpClient()).post(hook(), byteArrayOf(1))
        assertFalse(outcome.ok)
        assertTrue(outcome.detail, outcome.detail.contains("rate-limiting"))
    }

    @Test
    fun `an empty card is refused before anything is sent`() {
        val outcome = DiscordPoster(OkHttpClient()).post(hook(), ByteArray(0))
        assertFalse(outcome.ok)
        assertEquals("nothing should have been sent", 0, server.requestCount)
    }
}
