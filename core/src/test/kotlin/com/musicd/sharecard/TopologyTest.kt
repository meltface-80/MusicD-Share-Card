package com.musicd.sharecard

import com.musicd.sharecard.sonos.parseZoneGroupState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ZoneGroupTopology, as a real household reports it. */
class TopologyTest {

    /** Two rooms grouped, one alone, one invisible sub. */
    private val state = """
        <ZoneGroupState>
          <ZoneGroups>
            <ZoneGroup Coordinator="RINCON_AAA" ID="RINCON_AAA:1">
              <ZoneGroupMember UUID="RINCON_AAA" ZoneName="Living Room"
                Location="http://192.168.1.40:1400/xml/device_description.xml"
                Invisible="0" IsZoneBridge="0"/>
              <ZoneGroupMember UUID="RINCON_BBB" ZoneName="Kitchen"
                Location="http://192.168.1.41:1400/xml/device_description.xml"
                Invisible="0" IsZoneBridge="0"/>
            </ZoneGroup>
            <ZoneGroup Coordinator="RINCON_CCC" ID="RINCON_CCC:7">
              <ZoneGroupMember UUID="RINCON_CCC" ZoneName="Study"
                Location="http://192.168.1.42:1400/xml/device_description.xml"
                Invisible="0" IsZoneBridge="0"/>
              <ZoneGroupMember UUID="RINCON_DDD" ZoneName="Study (Sub)"
                Location="http://192.168.1.43:1400/xml/device_description.xml"
                Invisible="1" IsZoneBridge="0"/>
            </ZoneGroup>
          </ZoneGroups>
        </ZoneGroupState>
    """.trimIndent()

    @Test
    fun `every member is read, with its address`() {
        val zones = parseZoneGroupState(state)
        assertEquals(4, zones.size)
        val living = zones.first { it.uid == "RINCON_AAA" }
        assertEquals("Living Room", living.name)
        assertEquals("192.168.1.40", living.ip)
        assertEquals("http://192.168.1.40:1400", living.baseUrl)
    }

    @Test
    fun `the coordinator of a group is marked, and its members are not`() {
        val zones = parseZoneGroupState(state)
        assertTrue(zones.first { it.uid == "RINCON_AAA" }.isCoordinator)
        assertFalse(zones.first { it.uid == "RINCON_BBB" }.isCoordinator)
        assertEquals("RINCON_AAA", zones.first { it.uid == "RINCON_BBB" }.coordinatorUid)
    }

    @Test
    fun `an invisible sub is not a room you can play to`() {
        val zones = parseZoneGroupState(state)
        assertFalse(zones.first { it.uid == "RINCON_DDD" }.playable)
        assertTrue(zones.first { it.uid == "RINCON_CCC" }.playable)
    }

    @Test
    fun `older firmware returning ZoneGroups at the top level still parses`() {
        val old = """
            <ZoneGroups>
              <ZoneGroup Coordinator="RINCON_AAA" ID="RINCON_AAA:1">
                <ZoneGroupMember UUID="RINCON_AAA" ZoneName="Hall"
                  Location="http://10.0.0.5:1400/xml/device_description.xml"/>
              </ZoneGroup>
            </ZoneGroups>
        """.trimIndent()
        val zones = parseZoneGroupState(old)
        assertEquals(1, zones.size)
        assertEquals("Hall", zones[0].name)
        assertEquals("10.0.0.5", zones[0].ip)
    }

    @Test
    fun `a bridge is not a room`() {
        val withBridge = """
            <ZoneGroupState><ZoneGroups>
              <ZoneGroup Coordinator="RINCON_ZZZ" ID="g">
                <ZoneGroupMember UUID="RINCON_ZZZ" ZoneName="BOOST"
                  Location="http://10.0.0.9:1400/xml/device_description.xml"
                  IsZoneBridge="1"/>
              </ZoneGroup>
            </ZoneGroups></ZoneGroupState>
        """.trimIndent()
        assertFalse(parseZoneGroupState(withBridge)[0].playable)
    }

    @Test
    fun `nonsense answers with no zones rather than throwing`() {
        assertTrue(parseZoneGroupState("").isEmpty())
        assertTrue(parseZoneGroupState("not xml at all").isEmpty())
        assertTrue(parseZoneGroupState("<ZoneGroupState></ZoneGroupState>").isEmpty())
    }

    @Test
    fun `a member with no UUID is skipped rather than keyed on empty`() {
        val broken = """
            <ZoneGroupState><ZoneGroups>
              <ZoneGroup Coordinator="RINCON_AAA" ID="g">
                <ZoneGroupMember ZoneName="Ghost" Location="http://10.0.0.1:1400/x"/>
                <ZoneGroupMember UUID="RINCON_AAA" ZoneName="Real"
                  Location="http://10.0.0.2:1400/x"/>
              </ZoneGroup>
            </ZoneGroups></ZoneGroupState>
        """.trimIndent()
        val zones = parseZoneGroupState(broken)
        assertEquals(1, zones.size)
        assertEquals("Real", zones[0].name)
    }

    /**
     * An undeclared prefix must not take the whole document with it.
     *
     * THIS IS THE BUG THAT MADE A REAL HOUSEHOLD UNREACHABLE. Three players
     * answered on port 1400 and every one of them "would not describe the
     * household", because the parser was namespace-aware and a namespace-aware
     * parser rejects the ENTIRE document over one unbound prefix. Sonos replies
     * are assembled by several services and music providers and are full of
     * prefixes; nothing here reads a namespace URI, so strictness bought
     * nothing and cost everything.
     */
    @Test
    fun `an undeclared namespace prefix does not throw the whole reply away`() {
        val sloppy = """
            <ZoneGroupState><ZoneGroups>
              <ZoneGroup Coordinator="RINCON_A" ID="g">
                <ZoneGroupMember UUID="RINCON_A" ZoneName="Kitchen"
                  Location="http://192.168.0.93:1400/xml/device_description.xml"/>
                <u:Vanished xmlns:ignored="urn:x"/>
              </ZoneGroup>
            </ZoneGroups></ZoneGroupState>
        """.trimIndent()
        val zones = parseZoneGroupState(sloppy)
        assertEquals(1, zones.size)
        assertEquals("Kitchen", zones[0].name)
        assertEquals("192.168.0.93", zones[0].ip)
    }

    /**
     * A DOCTYPE naming an external entity must not be resolved. The parser is
     * handed XML by any device on the LAN, and on the Android host that would
     * be a file read out of the app's own sandbox.
     */
    @Test
    fun `an external entity is refused rather than expanded`() {
        val attack = """
            <?xml version="1.0"?>
            <!DOCTYPE foo [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
            <ZoneGroupState><ZoneGroups>
              <ZoneGroup Coordinator="A" ID="g">
                <ZoneGroupMember UUID="A" ZoneName="&xxe;" Location="http://10.0.0.1:1400/x"/>
              </ZoneGroup>
            </ZoneGroups></ZoneGroupState>
        """.trimIndent()
        // Refusing the DOCTYPE outright means the whole document fails to
        // parse, which is the correct outcome: no zones, and nothing read.
        assertTrue(parseZoneGroupState(attack).isEmpty())
    }
}
