package com.musicd.sharecard

import com.musicd.sharecard.api.ArtProxy
import com.musicd.sharecard.api.Assets
import com.musicd.sharecard.api.CardApi
import com.musicd.sharecard.http.HttpServer
import com.musicd.sharecard.http.Request
import com.musicd.sharecard.meta.Metadata
import com.musicd.sharecard.meta.Pitchfork
import com.musicd.sharecard.meta.metadataHttpClient
import com.musicd.sharecard.sonos.Household
import com.musicd.sharecard.sonos.SonosScan
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/**
 * An Error must not end the process.
 *
 * `catch (e: Exception)` does not catch `Error`, and a class that fails to
 * initialise throws `NoClassDefFoundError` — which is one. It went straight
 * through every guard in the request path, killed the HTTP worker thread, and
 * took the app down with no message. The same Error in the previous release was
 * swallowed by a `runCatching` and reported as "would not describe the
 * household", which is why it took three goes to find.
 *
 * Neither behaviour is right. A failed request is a 500 and the app stays up.
 */
class ErrorEscapeTest {

    private var server: HttpServer? = null

    @After
    fun tearDown() {
        server?.stop()
    }

    private fun household() = Household(
        playerAt = { throw NoClassDefFoundError("com.example.Missing") },
        seedHosts = listOf("10.0.0.1"),
        discover = { emptyList() },
        scan = { SonosScan.Result(emptyList(), emptyList()) }
    )

    private fun api(h: Household = household()) = CardApi(
        h,
        Metadata(metadataHttpClient(), "test"),
        Pitchfork(metadataHttpClient(), "test"),
        ArtProxy(metadataHttpClient()),
        Assets { null },
        "test"
    )

    private fun get(path: String) =
        Request("GET", path, emptyMap(), emptyMap(), ByteArray(0), false, "10.0.0.9")

    @Test
    fun `a player whose class will not load is reported, not fatal`() {
        val h = household()
        h.refresh()
        assertTrue("the household is simply unreachable", !h.reachable)
        assertTrue(
            "and it says what happened: ${h.lastTopologyErrors}",
            h.lastTopologyErrors.any { it.contains("NoClassDefFoundError") }
        )
    }

    @Test
    fun `an unreachable household degrades to an empty answer, not a 500`() {
        // The player throwing an Error is handled where it happens, so the page
        // gets a normal reply saying nothing was found — which is what somebody
        // can act on. A 500 here would be the app failing rather than reporting.
        val response = api().handle(get("/api/zones"))
        assertEquals(200, response.status)
        assertTrue(
            "and there are simply no zones: ${String(response.body, Charsets.UTF_8)}",
            String(response.body, Charsets.UTF_8).contains("\"zones\":[]")
        )
    }

    @Test
    fun `an Error anywhere else in routing becomes a 500, not a dead app`() {
        val api = CardApi(
            household(),
            Metadata(metadataHttpClient(), "test"),
            Pitchfork(metadataHttpClient(), "test"),
            ArtProxy(metadataHttpClient()),
            // Exactly the failure that happened: a class that will not load,
            // reached from the request path.
            Assets { throw NoClassDefFoundError("com.musicd.sharecard.sonos.Xml") },
            "test"
        )
        val response = api.handle(get("/"))
        assertEquals(500, response.status)
        assertTrue(
            "the reply should name what went wrong: ${String(response.body, Charsets.UTF_8)}",
            String(response.body, Charsets.UTF_8).contains("NoClassDefFoundError")
        )
    }

    @Test
    fun `the server survives a handler that throws an Error, and keeps serving`() {
        var thrown = false
        val s = HttpServer(
            { request ->
                if (request.path == "/boom") {
                    thrown = true
                    throw NoClassDefFoundError("com.example.Missing")
                }
                com.musicd.sharecard.http.Response.text(200, "still here")
            },
            0, HttpServer.LOOPBACK
        )
        s.start()
        server = s

        assertEquals(500, status(s.rootUrl + "/boom"))
        assertTrue("the handler really did throw", thrown)
        // The point of the whole test: the next request still works, because
        // the worker thread was not killed by an uncaught Error.
        assertEquals(200, status(s.rootUrl + "/fine"))
    }

    private fun status(url: String): Int {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 4000
            readTimeout = 4000
        }
        return try {
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }
}
