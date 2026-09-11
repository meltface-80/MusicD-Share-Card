package com.musicd.sharecard.api

import com.musicd.sharecard.Log
import com.musicd.sharecard.sonos.SonosScan
import com.musicd.sharecard.sonos.Ssdp
import com.musicd.sharecard.sonos.Household
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
    private val household: Household,
    /** What the Android shell knows — chiefly the last recorded crash. */
    private val hostNotes: () -> List<String> = { emptyList() }
) {

    fun run(): JSONObject {
        val report = JSONObject()

        // First, because a crash from last launch explains more than anything
        // below it and would otherwise be scrolled past.
        val notes = runCatching { hostNotes() }.getOrDefault(emptyList())
        if (notes.isNotEmpty()) report.put("app", Json.strings(notes))

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

        // 4. What the app is actually working from, and whether any of it
        //    answered.
        household.refresh(force = true)
        val groups = household.groups()
        report.put("hosts", Json.strings(household.knownHosts))
        report.put("discovery", Json.strings(household.lastDiscovery))
        report.put("reachable", household.reachable)
        // The line that turns "would not describe the household" into something
        // actionable: what each player actually said back.
        report.put("errors", Json.strings(household.lastTopologyErrors))
        report.put(
            "zones",
            Json.array(
                groups.map { group ->
                    val state = runCatching { household.stateOf(group) }.getOrNull()
                    JSONObject()
                        .put("name", group.displayName)
                        .put("ip", group.coordinator.ip)
                        .put("answered", state != null)
                        .put("state", state?.state?.name ?: "no answer")
                        .put("album", state?.nowPlaying?.displayAlbum.orEmpty())
                        .put("artist", state?.nowPlaying?.displayArtist.orEmpty())
                        .put("track", state?.nowPlaying?.track.orEmpty())
                        .put("hasArt", !state?.nowPlaying?.artUri.isNullOrEmpty())
                        .put("art", state?.nowPlaying?.artUri.orEmpty())
                        // The unparsed reply. Every source fills DIDL-Lite in
                        // differently, and reading the real thing beats
                        // inferring it from what a card came out looking like.
                        .put(
                            "raw",
                            Json.strings(
                                household.rawFor(group).map { (k, v) ->
                                    k + " = " + (if (v.length > 400) v.take(400) + "…" else v)
                                }
                            )
                        )
                }
            )
        )

        report.put("advice", advice(subnets, sweep, scan, groups.size))
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
