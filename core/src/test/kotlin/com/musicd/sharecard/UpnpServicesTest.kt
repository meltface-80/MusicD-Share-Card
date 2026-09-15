package com.musicd.sharecard

import com.musicd.sharecard.upnp.UpnpSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WHAT A RENDERER CAN BE ASKED TO DO, READ OFF ITS OWN DESCRIPTION.
 *
 * Asked for as "explore ways I can add suggestions to other zones… the likes of
 * Spotify, Qobuz and Tidal which are all detected on UPnP zones". The honest
 * answer is that it depends entirely on the box, and it CANNOT BE SETTLED FROM
 * HERE: base UPnP has no queue at all, every real one is a vendor extension,
 * and a device description is the only place it is written down.
 *
 * So this change reports and decides nothing — the repo's own rule, that the
 * report comes before the theory. Three rounds of the Roon queue were spent
 * reasoning about a protocol nobody here could reach; the round that fixed it
 * printed what the Core actually sent and read the answer off the first two
 * lines.
 *
 * The fixtures are the documented shapes: base UPnP, OpenHome (Linn, WiiM,
 * Volumio, BubbleUPnP), Sonos and LinkPlay. NONE HAS BEEN SEEN ON THE WIRE
 * FROM HERE — treat the first real /api/debug as the verification.
 */
class UpnpServicesTest {

    private val source = UpnpSource(
        soap = com.musicd.sharecard.sonos.SoapClient(okhttp3.OkHttpClient()),
        http = okhttp3.OkHttpClient(),
        discover = { emptyList() }
    )

    private fun describe(services: String, name: String = "A Box"): UpnpSource.Renderer? =
        source.parseDescription(
            """
            <root><device>
              <friendlyName>$name</friendlyName>
              <UDN>uuid:abc-123</UDN>
              <manufacturer>Somebody</manufacturer>
              <serviceList>$services</serviceList>
            </device></root>
            """.trimIndent(),
            "http://192.168.0.236:49152/description.xml"
        )?.renderer

    private fun service(type: String, control: String = "/ctrl") =
        "<service><serviceType>$type</serviceType><controlURL>$control</controlURL></service>"

    private val avTransport = "urn:schemas-upnp-org:service:AVTransport:1"

    // -------------------------------------------------------- every service

    @Test
    fun `every service the description lists is carried, not only AVTransport`() {
        val r = describe(
            service("urn:schemas-upnp-org:service:RenderingControl:1", "/rc") +
                service(avTransport, "/avt") +
                service("urn:av-openhome-org:service:Playlist:1", "/pl")
        )
        assertEquals(
            listOf(
                "urn:schemas-upnp-org:service:RenderingControl:1",
                avTransport,
                "urn:av-openhome-org:service:Playlist:1"
            ),
            r?.services
        )
    }

    @Test
    fun `a service listed after AVTransport is still reported`() {
        // The walk used to stop at AVTransport, which was right while its
        // control URL was the only thing wanted. Left alone it would have made
        // this report depend on a manufacturer's ordering — and the service
        // that matters most here, OpenHome's Playlist, is commonly listed last.
        val r = describe(service(avTransport, "/avt") + service("urn:av-openhome-org:service:Playlist:1"))
        assertTrue(
            "a queue service listed after AVTransport was dropped",
            r!!.services.any { it.contains("Playlist") }
        )
    }

    @Test
    fun `the AVTransport control URL is still the first one, and still resolved`() {
        // The behaviour this change must not disturb.
        val r = describe(
            service("urn:schemas-upnp-org:service:ConnectionManager:1", "/cm") +
                service(avTransport, "/upnp/control/rendertransport1")
        )
        assertEquals("http://192.168.0.236:49152/upnp/control/rendertransport1", r?.controlUrl)
    }

    @Test
    fun `a description with no AVTransport is still no renderer`() {
        assertEquals(null, describe(service("urn:av-openhome-org:service:Playlist:1")))
    }

    // ------------------------------------------------------- short names

    @Test
    fun `a service URN is shortened to vendor and name`() {
        assertEquals("upnp/AVTransport", UpnpSource.shortService(avTransport))
        assertEquals("openhome/Playlist", UpnpSource.shortService("urn:av-openhome-org:service:Playlist:1"))
        assertEquals("sonos/Queue", UpnpSource.shortService("urn:schemas-sonos-com:service:Queue:1"))
        assertEquals("wiimu/PlayQueue", UpnpSource.shortService("urn:schemas-wiimu-com:service:PlayQueue:1"))
    }

    @Test
    fun `anything that is not a service URN is printed as it arrived`() {
        // A report that quietly rewrites what it was given is worse than none.
        assertEquals("nonsense", UpnpSource.shortService("nonsense"))
        assertEquals("urn:x:y", UpnpSource.shortService("urn:x:y"))
    }

    // --------------------------------------------------- what it can be asked

    @Test
    fun `AVTransport ALONE is reported as no queue, and says what it does instead`() {
        /*
         * THE DISTINCTION THIS WHOLE LINE EXISTS FOR. Base UPnP AVTransport
         * holds ONE uri plus one "next" slot, so sending to it REPLACES what is
         * playing. "Queue" and "play" are one word apart and a very long way
         * apart in what they do to a room somebody is listening to — which is
         * the same rule `RoonBrowse.pickQueueAction` is built from, where
         * reaching for "the first action" would have hit Play Now.
         */
        val verdict = UpnpSource.queueability(listOf(avTransport))
        assertTrue(verdict, verdict.startsWith("nothing"))
        assertTrue("it must say what AVTransport does instead", verdict.contains("replaces"))
    }

    @Test
    fun `OpenHome Playlist is named as a real queue`() {
        val verdict = UpnpSource.queueability(
            listOf(avTransport, "urn:av-openhome-org:service:Playlist:1")
        )
        assertTrue(verdict, verdict.contains("OpenHome Playlist"))
    }

    @Test
    fun `Sonos and LinkPlay each name their own`() {
        assertTrue(
            UpnpSource.queueability(listOf("urn:schemas-sonos-com:service:Queue:1"))
                .contains("Sonos Queue")
        )
        assertTrue(
            UpnpSource.queueability(listOf("urn:schemas-wiimu-com:service:PlayQueue:1"))
                .contains("LinkPlay")
        )
    }

    @Test
    fun `a box listing nothing at all says so rather than guessing`() {
        val verdict = UpnpSource.queueability(emptyList())
        assertTrue(verdict, verdict.contains("no AVTransport"))
    }
}
