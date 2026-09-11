package com.musicd.sharecard

import com.musicd.sharecard.api.Assets
import com.musicd.sharecard.http.HttpServer
import com.musicd.sharecard.http.Response
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/**
 * The server, over a real socket.
 *
 * [HttpServer] is a port rather than this project's own code, and the API tests
 * beside it call the handler directly — so nothing else here would notice if the
 * socket, the request parsing or the response writing had been broken by the
 * move. This boots it on loopback and makes real HTTP requests.
 */
class ServerTest {

    private var server: HttpServer? = null

    @After
    fun tearDown() {
        server?.stop()
    }

    private fun serve(handler: HttpServer.Handler): String {
        // Loopback and an OS-assigned port: a test must not answer the network,
        // and must not fight whatever else is using the app's usual port.
        val s = HttpServer(handler, 0, HttpServer.LOOPBACK)
        s.start()
        server = s
        return s.rootUrl
    }

    private class Result(val status: Int, val body: String, val contentType: String?)

    private fun get(url: String): Result {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 4000
            readTimeout = 4000
        }
        return try {
            val status = connection.responseCode
            val stream = if (status < 400) connection.inputStream else connection.errorStream
            Result(
                status,
                stream?.readBytes()?.toString(Charsets.UTF_8).orEmpty(),
                connection.contentType
            )
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun `a request reaches the handler and its answer comes back`() {
        val root = serve { request -> Response.text(200, "you asked for ${request.path}") }
        val result = get("$root/hello")
        assertEquals(200, result.status)
        assertEquals("you asked for /hello", result.body)
    }

    @Test
    fun `the query string is parsed`() {
        val root = serve { request ->
            Response.text(200, request.param("album") + "|" + request.param("artist"))
        }
        // Spaces and an ampersand in a value: the album titles this app is
        // handed are not URL-safe, and mis-parsing one would look like a
        // metadata lookup that simply found nothing.
        val result = get("$root/api/extras?album=Hounds%20of%20Love&artist=Kate%20Bush")
        assertEquals("Hounds of Love|Kate Bush", result.body)
    }

    @Test
    fun `a handler that throws answers 500 rather than dropping the connection`() {
        val root = serve { throw RuntimeException("boom") }
        assertEquals(500, get("$root/api/health").status)
    }

    @Test
    fun `bytes come back intact, which is what the cover depends on`() {
        // A cover is binary and goes through the same writer as the JSON. A
        // response mangled by a charset conversion would show up as a broken
        // image and nothing else.
        val png = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0xFF.toByte()
        )
        val root = serve { Response.bytes(200, "image/png", png) }
        val connection = (URL("$root/api/art").openConnection() as HttpURLConnection)
        val got = connection.inputStream.use { it.readBytes() }
        connection.disconnect()
        assertArrayEqualsMessage(png, got)
    }

    private fun assertArrayEqualsMessage(expected: ByteArray, actual: ByteArray) {
        assertEquals("length", expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals("byte $i", expected[i].toInt(), actual[i].toInt())
        }
    }

    @Test
    fun `the whole app serves its bundled page over the socket`() {
        val page = "<!doctype html><title>Card</title>"
        val app = ShareCardApp(
            assets = Assets { path -> if (path == "index.html") page.toByteArray() else null },
            // No seeds and no discovery: this test is about the server, and it
            // must not go looking for speakers on whatever network CI is on.
            seedHosts = emptyList(),
            port = 0,
            bindAddress = HttpServer.LOOPBACK,
            version = "1.2.3"
        )
        app.start()
        try {
            val root = app.rootUrl
            val index = get(root + "/")
            assertEquals(200, index.status)
            assertEquals(page, index.body)
            assertTrue(index.contentType!!.startsWith("text/html"))

            val health = get("$root/api/health")
            assertEquals(200, health.status)
            assertTrue(health.body.contains("\"version\":\"1.2.3\""))

            assertEquals(404, get("$root/missing.js").status)
        } finally {
            app.stop()
        }
    }
}
