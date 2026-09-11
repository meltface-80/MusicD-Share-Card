package com.musicd.sharecard

import com.musicd.sharecard.sonos.SonosScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.Inet4Address

/**
 * Which addresses the multicast-free fallback will knock on.
 *
 * The arithmetic here decides both whether the scan finds the speakers and
 * whether it is a reasonable thing to do at all — a wrong prefix turns a
 * 254-address sweep into 65,000 connects from a phone.
 */
class SubnetScanTest {

    private fun subnet(ip: String, prefix: Int) =
        SonosScan.Subnet(InetAddress.getByName(ip) as Inet4Address, prefix.toShort(), "test0")

    @Test
    fun `a 24 covers every host except ourselves, network and broadcast`() {
        val addresses = subnet("192.168.1.40", 24).addresses()
        assertEquals(253, addresses.size)
        assertTrue(addresses.contains("192.168.1.1"))
        assertTrue(addresses.contains("192.168.1.254"))
        assertFalse("our own address is not worth knocking on", addresses.contains("192.168.1.40"))
        assertFalse("the network address is not a host", addresses.contains("192.168.1.0"))
        assertFalse("the broadcast address is not a host", addresses.contains("192.168.1.255"))
    }

    @Test
    fun `a 23 spans both halves, which is where a mesh often puts things`() {
        val addresses = subnet("10.0.2.5", 23).addresses()
        assertTrue(addresses.contains("10.0.2.1"))
        assertTrue("the other half of the range must be reached", addresses.contains("10.0.3.7"))
    }

    @Test
    fun `a range bigger than a home network is refused rather than attempted`() {
        // A /16 is 65,000 connects. That is not discovery, it is a denial of
        // service against the user's own network.
        assertTrue(subnet("172.16.4.9", 16).addresses().isEmpty())
        assertTrue(subnet("10.0.0.1", 8).addresses().isEmpty())
    }

    @Test
    fun `a point-to-point or single-host prefix yields nothing to scan`() {
        assertTrue(subnet("192.168.1.40", 31).addresses().isEmpty())
        assertTrue(subnet("192.168.1.40", 32).addresses().isEmpty())
    }

    @Test
    fun `the octets are computed unsigned`() {
        // A byte above 127 is negative in Kotlin, and a sign-extended octet
        // turns 192.168.200.x into nonsense.
        val addresses = subnet("192.168.200.40", 24).addresses()
        assertTrue(addresses.contains("192.168.200.1"))
        assertTrue(addresses.contains("192.168.200.254"))
        assertEquals(253, addresses.size)
    }
}
