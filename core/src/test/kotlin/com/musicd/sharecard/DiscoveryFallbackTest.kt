package com.musicd.sharecard

import com.musicd.sharecard.sonos.Household
import com.musicd.sharecard.sonos.SonosScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What happens when multicast does not work.
 *
 * This is not hypothetical: the first install of this app reported "No Sonos
 * players found" on a network full of speakers, because SSDP was the only way
 * it knew how to look. Mesh systems, guest VLANs and switches with client
 * isolation all drop multicast, and on those networks no amount of care in the
 * SSDP code helps.
 */
class DiscoveryFallbackTest {

    private val topology = """
        <ZoneGroupState><ZoneGroups>
          <ZoneGroup Coordinator="RINCON_A" ID="g1">
            <ZoneGroupMember UUID="RINCON_A" ZoneName="Living Room"
              Location="http://10.0.0.1:1400/x"/>
          </ZoneGroup>
        </ZoneGroups></ZoneGroupState>
    """.trimIndent()

    private fun household(
        ssdp: List<String>,
        scan: List<String>,
        reachableHosts: Set<String> = setOf("10.0.0.1")
    ): Pair<Household, MutableList<String>> {
        val scanCalls = mutableListOf<String>()
        val h = Household(
            playerAt = { ip ->
                FakePlayer(ip, topology = topology).also {
                    it.offline = ip !in reachableHosts
                }
            },
            seedHosts = emptyList(),
            discover = { ssdp },
            scan = {
                scanCalls += "scanned"
                SonosScan.Result(scan, listOf("test scan"))
            }
        )
        return h to scanCalls
    }

    @Test
    fun `a player found by scan is used when multicast finds nothing`() {
        val (h, scans) = household(ssdp = emptyList(), scan = listOf("10.0.0.1"))
        h.refresh()
        assertEquals(1, scans.size)
        assertEquals(1, h.groups().size)
        assertEquals("Living Room", h.groups()[0].coordinator.name)
        assertTrue(h.reachable)
    }

    @Test
    fun `the scan is NOT run when multicast already found a player`() {
        // Hundreds of TCP connects where one datagram did the job is not a
        // reasonable thing to do on every cold start.
        val (h, scans) = household(ssdp = listOf("10.0.0.1"), scan = listOf("10.0.0.1"))
        h.refresh()
        assertTrue("the scan should not have run: $scans", scans.isEmpty())
        assertEquals(1, h.groups().size)
    }

    @Test
    fun `a hand-entered address skips discovery entirely`() {
        var discoverCalls = 0
        val h = Household(
            playerAt = { ip -> FakePlayer(ip, topology = topology) },
            seedHosts = listOf("10.0.0.1"),
            discover = { discoverCalls++; emptyList() },
            scan = { SonosScan.Result(emptyList(), emptyList()) }
        )
        h.refresh()
        assertEquals("a supplied address is the whole point of supplying it", 0, discoverCalls)
        assertEquals(1, h.groups().size)
    }

    @Test
    fun `finding nothing at all is reported as unreachable, not as silence`() {
        // The distinction that matters: "I cannot see your speakers" and "your
        // speakers are not playing anything" are different problems, and
        // reporting the second for the first sent the first round of debugging
        // in the wrong direction.
        val (h, _) = household(ssdp = emptyList(), scan = emptyList())
        h.refresh()
        assertTrue(h.groups().isEmpty())
        assertFalse(h.reachable)
    }

    @Test
    fun `a host that answers nothing leaves the household unreachable`() {
        val (h, _) = household(
            ssdp = emptyList(),
            scan = listOf("10.0.0.99"),
            reachableHosts = emptySet()
        )
        h.refresh()
        assertFalse("nothing answered, so this is not a quiet house", h.reachable)
        assertTrue(h.groups().isEmpty())
    }

    @Test
    fun `discovery records what it did, for the diagnostics page`() {
        val (h, _) = household(ssdp = emptyList(), scan = listOf("10.0.0.1"))
        h.refresh()
        val notes = h.lastDiscovery.joinToString(" ")
        assertTrue("should say SSDP found nothing: $notes", notes.contains("SSDP found 0"))
        assertTrue("should say it fell back to a scan: $notes", notes.contains("scanning"))
    }
}
