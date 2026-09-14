package com.musicd.sharecard

import com.musicd.sharecard.lms.LmsClient
import com.musicd.sharecard.lms.LmsDiscovery
import com.musicd.sharecard.lms.LmsSource
import com.musicd.sharecard.sonos.Household
import com.musicd.sharecard.sonos.SonosScan
import com.musicd.sharecard.sonos.Transport
import com.musicd.sharecard.upnp.UpnpSource
import com.musicd.sharecard.sonos.SoapClient
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A SWEEP THAT FOUND NOTHING IS AN ANSWER, AND IS REMEMBERED LIKE ANY OTHER.
 *
 * All three of these cached a discovery only when it SUCCEEDED — `zones
 * .isNotEmpty()`, `renderers.isNotEmpty()`, `known != null` — so a source that
 * found nothing searched again on the very next question, for ever, inside the
 * request. Measured against the real server on a network with no players:
 * `/api/zones` 9.3 seconds and `/api/now-playing` 37 seconds, on EVERY request,
 * because the fallback ladder asks each source and then each room and every one
 * of those re-swept. With the answer remembered: 9.4 seconds once, then about a
 * millisecond until the TTL runs out or Refresh forces one. Reported as "very
 * slow to detect zones and populate sharecards".
 *
 * It is the same fault Roon's discovery had — it looked again every thirty
 * seconds for ever rather than looking once — and the same rule: asking the
 * household a question nobody is reading is what "never poll" forbids.
 *
 * REFRESH MUST STILL SWEEP. That is the bargain: a speaker switched on a moment
 * ago is one tap away rather than automatic, and a test for each says so.
 */
class EmptySweepTest {

    // ------------------------------------------------------------- Sonos

    @Test
    fun `a household that found nothing is not searched again until refresh`() {
        var sweeps = 0
        var scans = 0
        val household = Household(
            playerAt = { error("no player should ever be reached") },
            discover = { sweeps++; emptyList() },
            scan = { scans++; SonosScan.Result(emptyList(), emptyList()) }
        )

        household.refresh()
        household.refresh()
        household.refresh()
        assertEquals("SSDP ran once per question", 1, sweeps)
        assertEquals("the subnet scan ran once per question", 1, scans)

        household.refresh(force = true)
        assertEquals("Refresh must still look", 2, sweeps)
        assertEquals("Refresh must still look", 2, scans)
    }

    // -------------------------------------------------------------- UPnP

    @Test
    fun `a renderer search that found nothing is not repeated until refresh`() {
        var sweeps = 0
        val upnp = UpnpSource(
            soap = SoapClient(OkHttpClient()),
            http = OkHttpClient(),
            discover = { sweeps++; emptyList() }
        )

        upnp.zones()
        upnp.zones()
        upnp.nowPlaying(null)
        assertEquals("SSDP ran once per question", 1, sweeps)

        upnp.refresh()
        assertEquals("Refresh must still look", 2, sweeps)
    }

    // ------------------------------------------------------------ Lyrion

    @Test
    fun `a broadcast that found no server is not repeated until refresh`() {
        var broadcasts = 0
        val lms = LmsSource(
            client = LmsClient(OkHttpClient()),
            hosts = { emptyList() },
            discover = { broadcasts++; emptyList<LmsDiscovery.Server>() }
        )

        lms.zones()
        lms.zones()
        lms.nowPlaying(null)
        assertEquals("the broadcast went out once per question", 1, broadcasts)

        lms.refresh()
        assertEquals("Refresh must still look", 2, broadcasts)
    }
}
