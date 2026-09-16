package com.musicd.sharecard

import com.musicd.sharecard.sonos.SoapClient
import com.musicd.sharecard.source.PlayState
import com.musicd.sharecard.upnp.UpnpSource
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WHAT A DLNA RENDERER IS PLAYING, ASKED THE WAY SONOS HAS ALWAYS ASKED.
 *
 * Reported from the field: Spotify Connect to a WiiM Pro Plus draws a card
 * and Qobuz Connect to the SAME BOX draws nothing at all. One renderer, one
 * code path, two services — so the difference cannot be the source and has to
 * be the reply.
 *
 * `GetPositionInfo` describes the TRACK on the transport; `GetMediaInfo`
 * describes what the transport as a whole is playing. [SonosSource] has asked
 * the second whenever the first came back short since the first release —
 * radio and line-in put the station's name there and nowhere else — and
 * `UpnpSource` never asked it at all. A renderer answering "PLAYING" with an
 * empty TrackMetaData was therefore read as describing nothing and dropped,
 * with the record sitting in the reply this app declined to fetch.
 *
 * DRIVEN THROUGH A REAL HTTP SERVER, like [LmsQueueTest] and for its reason:
 * the SOAP envelope this app builds is the likeliest thing to be wrong against
 * real hardware, and a stubbed client would never look at it. What is NOT
 * verified is the WiiM itself — no such box is reachable from here, which is
 * why `UpnpSource.diagnostics` now prints both replies verbatim. Treat the
 * first real /api/debug as the verification.
 */
class UpnpTransportTest {

    private val server = MockWebServer()

    /** Which AVTransport actions the app actually sent. */
    private val asked = mutableListOf<String>()

    private var state = "PLAYING"
    private var trackMeta = ""
    private var mediaMeta = ""
    private var mediaUri = ""

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path.orEmpty().contains("description")) {
                    return MockResponse().setBody(description)
                        .setHeader("Content-Type", "text/xml")
                }
                val action = request.getHeader("SOAPACTION").orEmpty()
                    .substringAfterLast('#').trim('"')
                asked += action
                val fields = when (action) {
                    "GetTransportInfo" -> mapOf("CurrentTransportState" to state)
                    "GetPositionInfo" -> mapOf("Track" to "1", "TrackMetaData" to trackMeta)
                    "GetMediaInfo" -> mapOf(
                        "CurrentURI" to mediaUri,
                        "CurrentURIMetaData" to mediaMeta
                    )
                    else -> emptyMap()
                }
                return MockResponse().setBody(envelope(action, fields))
                    .setHeader("Content-Type", "text/xml")
            }
        }
        server.start()
    }

    @After
    fun stop() = server.shutdown()

    private val description: String
        get() = """
            <root><device>
              <friendlyName>WiiM Pro Plus</friendlyName>
              <UDN>uuid:wiim-1</UDN>
              <manufacturer>Linkplay</manufacturer>
              <serviceList>
                <service>
                  <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                  <SCPDURL>/AVTransport.xml</SCPDURL>
                  <controlURL>/upnp/control/AVTransport1</controlURL>
                </service>
              </serviceList>
            </device></root>
        """.trimIndent()

    private fun envelope(action: String, fields: Map<String, String>): String = buildString {
        append("<?xml version=\"1.0\"?>")
        append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>")
        append("<u:").append(action).append("Response xmlns:u=\"")
        append("urn:schemas-upnp-org:service:AVTransport:1\">")
        for ((name, value) in fields) {
            append('<').append(name).append('>')
            append(value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))
            append("</").append(name).append('>')
        }
        append("</u:").append(action).append("Response></s:Body></s:Envelope>")
    }

    private fun didl(title: String, artist: String, album: String, art: String = ""): String =
        """
        <DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"
                   xmlns:dc="http://purl.org/dc/elements/1.1/"
                   xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">
          <item><dc:title>$title</dc:title>
            <upnp:artist>$artist</upnp:artist>
            <upnp:album>$album</upnp:album>
            ${if (art.isEmpty()) "" else "<upnp:albumArtURI>$art</upnp:albumArtURI>"}
          </item>
        </DIDL-Lite>
        """.trimIndent()

    private fun source() = UpnpSource(
        soap = SoapClient(OkHttpClient()),
        http = OkHttpClient(),
        discover = { listOf(server.url("/description.xml").toString()) }
    )

    // --------------------------------------------------- the reported bug

    @Test
    fun `a renderer that describes the record only on its transport still makes a card`() {
        // The shape the bug report points at: the box says PLAYING, its
        // TrackMetaData is the sentinel for "nothing loaded", and what it is
        // actually playing is on the transport. Before GetMediaInfo was asked
        // this answered null and the card said nothing was playing.
        trackMeta = "NOT_IMPLEMENTED"
        mediaMeta = didl("Nightmares", "Christine and the Queens", "Redcar")
        val playing = source().nowPlaying(null)

        assertEquals("Redcar", playing?.album)
        assertEquals("Christine and the Queens", playing?.artist)
        assertEquals(PlayState.PLAYING, playing?.state)
    }

    @Test
    fun `the transport fills only the gaps the track left`() {
        // Field by field, never one record or the other: the track's own title
        // survives and the transport supplies the album it did not carry.
        trackMeta = didl("Nightmares", "", "")
        mediaMeta = didl("Redcar", "Christine and the Queens", "Redcar")
        val playing = source().nowPlaying(null)

        assertEquals("Nightmares", playing?.track)
        assertEquals("Redcar", playing?.album)
        assertEquals("Christine and the Queens", playing?.artist)
    }

    @Test
    fun `a renderer that described the record fully is not asked twice`() {
        // The second round trip is a cost paid only where it buys something.
        trackMeta = didl("Nightmares", "Christine and the Queens", "Redcar")
        source().nowPlaying(null)

        assertTrue("asked: $asked", asked.contains("GetPositionInfo"))
        assertEquals("asked: $asked", 0, asked.count { it == "GetMediaInfo" })
    }

    @Test
    fun `a renderer with nothing on either reply still describes nothing`() {
        // The fix must not turn silence into a blank card, which is the one
        // thing worse than saying nothing is playing.
        state = "STOPPED"
        trackMeta = "NOT_IMPLEMENTED"
        mediaMeta = "NOT_IMPLEMENTED"

        assertNull(source().nowPlaying(null))
    }

    @Test
    fun `a station announcing Artist - Title is the only metadata some streams send`() {
        // Read by SonosSource since the beginning and thrown away here, which
        // is the same omission as GetMediaInfo one field over: the DIDL parser
        // has always produced this and only one of the two sources read it.
        trackMeta = """
            <DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"
                       xmlns:r="urn:schemas-rinconnetworks-com:metadata-1-0/">
              <item><r:streamContent>Bill Callahan - Drover</r:streamContent></item>
            </DIDL-Lite>
        """.trimIndent()
        mediaMeta = "NOT_IMPLEMENTED"
        val playing = source().nowPlaying(null)

        assertEquals("Bill Callahan", playing?.artist)
        assertEquals("Drover", playing?.track)
    }

    // ------------------------------------------------------- the raw report

    @Test
    fun `the diagnostics carry both replies verbatim`() {
        // No WiiM is reachable from here, so this section IS the verification
        // of everything above it — and it has to show the reply the parse made
        // nothing of, or it reports this app's reading rather than the device's
        // words. Same argument as RoonBrowse.attempts carrying reply.toString().
        trackMeta = "NOT_IMPLEMENTED"
        mediaMeta = didl("Nightmares", "Christine and the Queens", "Redcar")
        val lines = source().diagnostics().joinToString("\n")

        assertTrue(lines, lines.contains("GetTransportInfo"))
        assertTrue(lines, lines.contains("CurrentTransportState: PLAYING"))
        assertTrue(lines, lines.contains("TrackMetaData: NOT_IMPLEMENTED"))
        assertTrue(lines, lines.contains("Redcar"))
    }

    @Test
    fun `a long document is cut with its length stated, never silently`() {
        val long = "x".repeat(UpnpSource.MAX_RAW + 50)
        val cut = UpnpSource.snippet(long)

        assertTrue(cut, cut.endsWith("(${UpnpSource.MAX_RAW + 50} chars)"))
        assertEquals("a b c", UpnpSource.snippet("  a\n\n  b\tc  "))
    }
}
