package com.musicd.sharecard

import com.musicd.sharecard.upnp.UpnpSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private fun service(type: String, control: String = "/ctrl", scpd: String = "/scpd.xml") =
        "<service><serviceType>$type</serviceType>" +
            "<SCPDURL>$scpd</SCPDURL><controlURL>$control</controlURL></service>"

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
            r?.services?.map { it.type }
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
            r!!.services.any { it.type.contains("Playlist") }
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

    // ----------------------------------------------------- where the verbs are

    @Test
    fun `a service carries where its verbs are written down, resolved like any URL`() {
        /*
         * THE FIRST PROBE ANSWERED ONE STEP SHORT OF USEFUL. Off a real
         * network it said "this box advertises wiimu/PlayQueue" and no more —
         * and a service NAME does not say whether it can append, only replace,
         * or anything at all. Every UPnP service publishes an SCPD listing its
         * actions, FROM THE DEVICE, so the next question has an authoritative
         * answer rather than a reverse-engineered one.
         */
        val r = describe(
            service(avTransport, "/avt", "/AVTransport/scpd.xml") +
                service("urn:schemas-wiimu-com:service:PlayQueue:1", "/pq", "PlayQueue1.xml")
        )
        val queue = r!!.services.last()
        assertEquals("http://192.168.0.236:49152/PlayQueue1.xml", queue.scpdUrl)
        assertEquals("http://192.168.0.236:49152/pq", queue.controlUrl)
    }

    @Test
    fun `only a service that holds a queue is asked for its verbs`() {
        // A description lists half a dozen and the other four hold no queue,
        // so a report that fetched every SCPD would cost six GETs to answer a
        // question about one. The list that decides what is REPORTED and the
        // list that decides what is ASKED are the same list.
        assertTrue(UpnpSource.isQueueService("urn:schemas-wiimu-com:service:PlayQueue:1"))
        assertTrue(UpnpSource.isQueueService("urn:av-openhome-org:service:Playlist:1"))
        assertTrue(UpnpSource.isQueueService("urn:schemas-sonos-com:service:Queue:1"))
        assertFalse(UpnpSource.isQueueService(avTransport))
        assertFalse(UpnpSource.isQueueService("urn:schemas-upnp-org:service:RenderingControl:1"))
        assertFalse(UpnpSource.isQueueService("urn:schemas-tencent-com:service:QPlay:1"))
    }

    @Test
    fun `every action in an SCPD is read, and nothing else is`() {
        val scpd = """
            <scpd xmlns="urn:schemas-upnp-org:service-1-0">
              <actionList>
                <action><name>CreateQueue</name>
                  <argumentList><argument><name>QueueContext</name></argument></argumentList>
                </action>
                <action><name>AppendQueue</name></action>
                <action><name>PlayQueueWithIndex</name></action>
              </actionList>
              <serviceStateTable>
                <stateVariable><name>NotAnAction</name></stateVariable>
              </serviceStateTable>
            </scpd>
        """.trimIndent()
        assertEquals(
            listOf("CreateQueue", "AppendQueue", "PlayQueueWithIndex"),
            UpnpSource.parseActions(scpd)
        )
    }

    @Test
    fun `an SCPD that is not readable is an empty list, never a throw`() {
        // A box that will not answer costs its own line and nothing else.
        assertEquals(emptyList<String>(), UpnpSource.parseActions(""))
        assertEquals(emptyList<String>(), UpnpSource.parseActions("not xml at all"))
        assertEquals(emptyList<String>(), UpnpSource.parseActions("<scpd></scpd>"))
    }

    // ------------------------------------------------- and what to put in it

    /*
     * A WiiM Pro Plus published all thirty-four of these off a real network.
     * The two that matter are AppendQueue and AppendTracksInQueue: a queue
     * that can be APPENDED to is one a suggestion can join without stopping
     * what somebody is listening to, which is the distinction this whole
     * feature turns on. ReplaceQueue sitting beside them is the proof the
     * device draws it too.
     */
    private val playQueueScpd = """
        <scpd xmlns="urn:schemas-upnp-org:service-1-0">
          <actionList>
            <action><name>CreateQueue</name>
              <argumentList><argument>
                <name>QueueContext</name><direction>in</direction>
              </argument></argumentList>
            </action>
            <action><name>ReplaceQueue</name>
              <argumentList><argument>
                <name>QueueContext</name><direction>in</direction>
              </argument></argumentList>
            </action>
            <action><name>AppendQueue</name>
              <argumentList><argument>
                <name>QueueContext</name><direction>in</direction>
              </argument></argumentList>
            </action>
            <action><name>SearchQueueOnline</name>
              <argumentList>
                <argument><name>QueueName</name><direction>in</direction></argument>
                <argument><name>SearchKey</name><direction>in</direction></argument>
                <argument><name>Queue</name><direction>out</direction></argument>
              </argumentList>
            </action>
            <action><name>SetQueueLoopMode</name>
              <argumentList><argument>
                <name>LoopMode</name><direction>in</direction>
              </argument></argumentList>
            </action>
          </actionList>
        </scpd>
    """.trimIndent()

    @Test
    fun `the verbs that ADD a record are the ones signed`() {
        val signed = UpnpSource.parseSignatures(playQueueScpd)
        assertEquals(
            listOf(
                "AppendQueue(in QueueContext)",
                "SearchQueueOnline(in QueueName, in SearchKey, out Queue)"
            ),
            signed
        )
    }

    @Test
    fun `setting a loop mode is not one of them, and neither is replacing`() {
        /*
         * A service can publish thirty-four actions and printing every
         * argument of every one buries the answer rather than giving it. And
         * REPLACE is deliberately not here: it is the verb that stops what
         * somebody is listening to, so it must never be the one this app
         * reaches for by accident — the rule RoonBrowse.pickQueueAction exists
         * for, one protocol over.
         */
        val signed = UpnpSource.parseSignatures(playQueueScpd)
        assertTrue(signed.none { it.startsWith("SetQueueLoopMode") })
        assertTrue(signed.none { it.startsWith("ReplaceQueue") })
        assertTrue(signed.none { it.startsWith("CreateQueue") })
    }

    @Test
    fun `it is a rule rather than a list, because the next box names them differently`() {
        assertTrue(UpnpSource.worthSigning("AppendTracksInQueueEx"))
        assertTrue(UpnpSource.worthSigning("Insert"))
        assertTrue(UpnpSource.worthSigning("AddURIToQueue"))
        assertTrue(UpnpSource.worthSigning("GetQueueOnline"))
        assertFalse(UpnpSource.worthSigning("UserLogin"))
        assertFalse(UpnpSource.worthSigning("SetRating"))
        assertFalse(UpnpSource.worthSigning("TakePlayControl"))
    }

    @Test
    fun `an argument with no direction is still named`() {
        // Lenient like every other parser here: a field missing costs its own
        // detail and never the line it is on.
        val scpd = """
            <scpd><actionList><action><name>AppendQueue</name>
              <argumentList><argument><name>QueueContext</name></argument></argumentList>
            </action></actionList></scpd>
        """.trimIndent()
        assertEquals(listOf("AppendQueue(QueueContext)"), UpnpSource.parseSignatures(scpd))
    }

    @Test
    fun `an action with no arguments at all reads as taking none`() {
        val scpd = "<scpd><actionList><action><name>BrowseQueue</name></action></actionList></scpd>"
        assertEquals(listOf("BrowseQueue()"), UpnpSource.parseSignatures(scpd))
    }
}
