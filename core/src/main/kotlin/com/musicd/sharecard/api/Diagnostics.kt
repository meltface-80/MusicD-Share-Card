package com.musicd.sharecard.api

import com.musicd.sharecard.Log
import com.musicd.sharecard.sonos.SonosScan
import com.musicd.sharecard.sonos.Ssdp
import com.musicd.sharecard.source.Sources
import org.json.JSONObject

/**
 * Why the app cannot see the household.
 *
 * "No Sonos players found" names a symptom and no cause, and there are at least
 * five of them: the device is on a different network from the speakers, its
 * multicast is filtered, no interface carries an address at all, a player
 * answered but not with a topology, or there genuinely are no players. Without
 * this page, telling those apart means reading logcat off a device that is
 * usually in another room — which is exactly the position this app was in the
 * first time it failed.
 *
 * So this runs the whole discovery chain ON DEMAND and reports every step. It
 * is deliberately slow and deliberately not cached: it is for the one moment
 * somebody is standing in front of the thing wondering what is wrong.
 *
 * READ-ONLY, like everything else here. It reports this device's own addresses
 * and what answered on them, which is no more than the card itself reveals to
 * anyone already on the network.
 */
class Diagnostics(
    private val sources: Sources,
    /** What the Android shell knows — chiefly the last recorded crash. */
    private val hostNotes: () -> List<String> = { emptyList() },
    /**
     * The last few Pitchfork lookups, URL and outcome.
     *
     * A missing score is silent otherwise: there is no way from the card to
     * tell "Pitchfork never reviewed it" from "the URL this app built was not
     * the one Pitchfork used". That distinction took a bug report to notice.
     */
    private val reviewNotes: () -> List<String> = { emptyList() },
    /**
     * The last few similar-artist lookups.
     *
     * An empty row has three causes that look identical from the page — no
     * MusicBrainz id to ask with, ListenBrainz answering something unreadable,
     * and Deezer genuinely not knowing the act — and the first of those is the
     * only one that is not a bug. Same lesson as [reviewNotes].
     */
    private val similarNotes: () -> List<String> = { emptyList() },
    /**
     * The last few covers fetched, and what became of each.
     *
     * "No cover" has four causes that look identical on a card — no art url at
     * all, a host the proxy refused, a 404, or bytes that were not an image —
     * and telling them apart had already cost two rounds of diagnosis.
     */
    private val artNotes: () -> List<String> = { emptyList() }
) {

    fun run(): JSONObject {
        val report = JSONObject()

        // First, because a crash from last launch explains more than anything
        // below it and would otherwise be scrolled past.
        val notes = runCatching { hostNotes() }.getOrDefault(emptyList())
        if (notes.isNotEmpty()) report.put("app", Json.strings(notes))

        val reviews = runCatching { reviewNotes() }.getOrDefault(emptyList())
        if (reviews.isNotEmpty()) report.put("reviews", Json.strings(reviews))

        val similar = runCatching { similarNotes() }.getOrDefault(emptyList())
        if (similar.isNotEmpty()) report.put("similar", Json.strings(similar))
        val art = runCatching { artNotes() }.getOrDefault(emptyList())
        if (art.isNotEmpty()) report.put("art", Json.strings(art))

        // 1. What this device thinks it is attached to. An empty list here is
        //    the whole answer: no network, no speakers.
        val subnets = runCatching { SonosScan.localSubnets() }.getOrDefault(emptyList())
        report.put(
            "interfaces",
            Json.strings(subnets.map { it.describe() + " (${it.addresses().size} scannable)" })
        )

        // 2. Multicast, per interface, with each one's own verdict.
        val sweep = runCatching { Ssdp.sweep() }
            .onFailure { Log.w(TAG, "diagnostic SSDP failed: ${it.message}", it) }
            .getOrNull()
        report.put(
            "ssdp",
            JSONObject()
                .put("hosts", Json.strings(sweep?.hosts.orEmpty()))
                .put("notes", Json.strings(sweep?.notes.orEmpty()))
        )

        // 3. The multicast-free fallback, run whatever SSDP said — on this page
        //    the question is "what CAN be reached", not "what is the quickest
        //    way to find one".
        val scan = runCatching { SonosScan.scan() }
            .onFailure { Log.w(TAG, "diagnostic scan failed: ${it.message}", it) }
            .getOrNull()
        report.put(
            "scan",
            JSONObject()
                .put("hosts", Json.strings(scan?.hosts.orEmpty()))
                .put("notes", Json.strings(scan?.notes.orEmpty()))
        )

        // 4. What each source found, in its own words. Roon's line is where a
        //    "not approved in Settings → Extensions yet" shows up, and that is
        //    not a network problem however much it looks like one.
        sources.refresh()
        report.put("sources", Json.strings(sources.diagnostics()))
        // Anything the user must act on, first in the object so it is first on
        // the page. A Roon Core waiting to be approved is not a network fault
        // and must not be read as one.
        report.put("notices", Json.strings(sources.notices()))

        val zones = sources.zones()
        report.put(
            "zones",
            Json.array(
                zones.map { zone ->
                    // inZone, NOT nowPlaying: the second falls back to the
                    // best answer anywhere when this room is idle, so every
                    // row in this report used to carry the same record and the
                    // report contradicted the source lines above it.
                    val playing = runCatching {
                        sources.inZone(zone.id)
                    }.getOrNull()
                    JSONObject()
                        .put("name", zone.name)
                        .put("source", zone.source)
                        .put("answered", playing != null)
                        .put("state", playing?.state?.name ?: "no answer")
                        .put("album", playing?.album.orEmpty())
                        .put("artist", playing?.artist.orEmpty())
                        .put("track", playing?.track.orEmpty())
                        .put("art", playing?.artUrl.orEmpty())
                }
            )
        )

        report.put("advice", advice(subnets, sweep, scan, zones.size))
        return report
    }

    /**
     * The next thing to try, in plain words.
     *
     * Ordered so the first true statement is the most likely cause — a reader
     * looking at this is not going to work through five maybes.
     */
    private fun advice(
        subnets: List<SonosScan.Subnet>,
        sweep: Ssdp.Result?,
        scan: SonosScan.Result?,
        groupCount: Int
    ): String = when {
        groupCount > 0 ->
            "Players were found. If the card still says nothing is playing, the " +
                "zones below show what each room reported."
        subnets.isEmpty() ->
            "This device has no usable IPv4 network address. It is not on the " +
                "network the speakers are on — check wifi or ethernet."
        !scan?.hosts.isNullOrEmpty() || !sweep?.hosts.isNullOrEmpty() ->
            "Players were found and reached, but none would describe the " +
                "household. The exact reason each one gave is under “What the " +
                "players said” below — that is the thing to act on."
        sweep?.hosts.isNullOrEmpty() != false ->
            "Neither multicast nor a direct scan of this device's own subnet " +
                "found a player. The most likely cause is that this device is on " +
                "a different subnet or VLAN from the speakers — a guest network, " +
                "or a mesh system putting wifi and ethernet on separate ranges. " +
                "Put a speaker's IP address in hosts.txt to cross the gap."
        else ->
            "Multicast found a player but it would not answer. Check the address " +
                "below is really a Sonos device."
    }

    private companion object {
        const val TAG = "Diagnostics"
    }
}
