package com.musicd.sharecard

import com.musicd.sharecard.sonos.SoapClient
import com.musicd.sharecard.sonos.SoapError
import com.musicd.sharecard.sonos.Sonos
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * The SOAP exchange, against a server that answers like a player.
 *
 * Written after every player in a real household answered the scan on port 1400
 * and then refused to describe itself — a failure this code could report only
 * as "would not describe the household", which named no cause and pointed at no
 * fix. Each test here is one of the replies that produced that, and what the
 * user should be told about it.
 *
 * The shapes come from the UPnP-to-Sonos bridge, which is working code against
 * real Sonos hardware.
 */
class SoapTest {

    private lateinit var server: MockWebServer
    private lateinit var soap: SoapClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        soap = SoapClient(
            OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .callTimeout(3, TimeUnit.SECONDS)
                .build()
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun url() = server.url("/ZoneGroupTopology/Control").toString()

    private fun call(action: String = "GetZoneGroupState") =
        soap.call(url(), Sonos.ZONE_GROUP_TOPOLOGY, action)

    private fun envelope(inner: String) = """
        <?xml version="1.0" encoding="utf-8"?>
        <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
            s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
          <s:Body>$inner</s:Body>
        </s:Envelope>
    """.trimIndent()

    @Test
    fun `a normal reply is read, and the escaped topology comes back as XML`() {
        server.enqueue(
            MockResponse().setBody(
                envelope(
                    """<u:GetZoneGroupStateResponse
                         xmlns:u="urn:schemas-upnp-org:service:ZoneGroupTopology:1">
                         <ZoneGroupState>&lt;ZoneGroupState&gt;&lt;ZoneGroups /&gt;&lt;/ZoneGroupState&gt;</ZoneGroupState>
                       </u:GetZoneGroupStateResponse>"""
                )
            )
        )
        val result = call()
        assertEquals(
            "<ZoneGroupState><ZoneGroups /></ZoneGroupState>",
            result["ZoneGroupState"]
        )
    }

    @Test
    fun `the request is shaped the way a player expects`() {
        server.enqueue(MockResponse().setBody(envelope("<u:GetZoneGroupStateResponse/>")))
        call()
        val sent = server.takeRequest()
        assertEquals("POST", sent.method)
        assertEquals(
            "\"urn:schemas-upnp-org:service:ZoneGroupTopology:1#GetZoneGroupState\"",
            sent.getHeader("SOAPACTION")
        )
        assertTrue(
            "content type must be text/xml: " + sent.getHeader("Content-Type"),
            sent.getHeader("Content-Type").orEmpty().startsWith("text/xml")
        )
        val body = sent.body.readUtf8()
        assertTrue(body.contains("<s:Envelope"))
        assertTrue(body.contains("<u:GetZoneGroupState xmlns:u="))
    }

    @Test
    fun `a UPnP fault on a 500 is reported with its error code, not as unreachable`() {
        // This is the reply a player sends when it refuses an action, and
        // treating the 500 as "not responding" throws away the only thing that
        // says why.
        server.enqueue(
            MockResponse().setResponseCode(500).setBody(
                envelope(
                    """<s:Fault><faultcode>s:Client</faultcode>
                       <faultstring>UPnPError</faultstring>
                       <detail><UPnPError xmlns="urn:schemas-upnp-org:control-1-0">
                         <errorCode>401</errorCode>
                       </UPnPError></detail></s:Fault>"""
                )
            )
        )
        val error = runCatching { call() }.exceptionOrNull() as? SoapError
        assertEquals(401, error?.upnpCode)
        assertTrue(
            "the message should name the code: ${error?.detail}",
            error!!.detail.contains("401") && error.detail.contains("Invalid Action")
        )
    }

    @Test
    fun `an unexpected wrapper name is still read`() {
        // The bridge carries the comment "some devices answer with an
        // unexpected wrapper name" — learned against real hardware. Insisting
        // on an exact <action>Response turns a usable reply into a failure.
        server.enqueue(
            MockResponse().setBody(
                envelope("<u:GetZoneGroupStateResult xmlns:u=\"x\"><ZoneGroupState>ok</ZoneGroupState></u:GetZoneGroupStateResult>")
            )
        )
        assertEquals("ok", call()["ZoneGroupState"])
    }

    @Test
    fun `a reply that is not XML says so, and shows what came back`() {
        server.enqueue(MockResponse().setBody("<html><body>Not Found</body>"))
        val error = runCatching { call() }.exceptionOrNull() as? SoapError
        assertTrue(
            "should quote the reply: ${error?.detail}",
            error!!.detail.contains("Not Found") || error.detail.contains("not XML")
        )
    }

    @Test
    fun `a player that never answers is reported as no reply, with the reason`() {
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
        val error = runCatching { call() }.exceptionOrNull() as? SoapError
        assertEquals(0, error?.status)
        assertTrue(
            "the exception type is the clue to which problem this is: ${error?.detail}",
            error!!.detail.contains("no reply")
        )
    }

    @Test
    fun `the action name is on every error, because several are tried in a row`() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("nope"))
        val error = runCatching { call("GetTransportInfo") }.exceptionOrNull() as? SoapError
        assertTrue(
            "which call failed matters: ${error?.detail}",
            error!!.detail.contains("GetTransportInfo")
        )
    }
}
