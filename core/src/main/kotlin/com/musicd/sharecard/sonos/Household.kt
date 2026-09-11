package com.musicd.sharecard.sonos

import com.musicd.sharecard.Log

/**
 * One group of rooms, as the card app cares about it.
 *
 * Sonos groups are what actually play: a grouped room has no transport of its
 * own, and asking a member what it is playing answers with the coordinator's
 * stream second-hand or not at all. So the unit here is the GROUP, named after
 * its coordinator, and [members] is what to print when a group spans rooms.
 */
data class Group(
    val coordinator: Zone,
    val members: List<Zone>
) {
    val uid: String get() = coordinator.uid

    /**
     * "Kitchen" for a room on its own, "Kitchen + 2" when it leads a group.
     * The coordinator leads the name because that is the room whose screen and
     * whose queue the music is actually coming from.
     */
    val displayName: String
        get() = if (members.size <= 1) coordinator.name
        else "${coordinator.name} + ${members.size - 1}"

    val roomNames: List<String> get() = members.map { it.name }
}

/** Everything the card needs to know about one group's transport. */
data class ZoneState(
    val group: Group,
    val state: TransportState,
    val nowPlaying: NowPlaying,
    /** The transport URI, whose scheme is how a radio stream is recognised. */
    val currentUri: String = ""
) {
    val isPlaying: Boolean get() = state == TransportState.PLAYING

    /**
     * A live stream rather than a record.
     *
     * Radio has no album to make a card about, and the scheme is the honest
     * test: `x-sonosapi-stream` and `x-rincon-mp3radio` are stations,
     * `x-sonos-http`, `x-file-cifs` and the rest are tracks. Line-in is not a
     * stream in this sense but has no metadata either, and is caught by the
     * emptiness check rather than here.
     */
    val isStream: Boolean
        get() = STREAM_SCHEMES.any { currentUri.startsWith(it, ignoreCase = true) }

    private companion object {
        val STREAM_SCHEMES = listOf(
            "x-sonosapi-stream:", "x-rincon-mp3radio:", "x-sonosapi-radio:",
            "x-sonosapi-hls:", "aac:", "hls-radio:"
        )
    }
}

/**
 * The Sonos household: who is here, and what is playing.
 *
 * WHY THIS DOES NOT POLL. The app's whole job is to answer "make a card for
 * what is on right now", and it is asked that when somebody opens the page or
 * presses Refresh. A timer ticking at a speaker every few seconds for the rest
 * of the day — on a device that is, by design, always on — would be a
 * continuous load on the household to answer a question nobody asked. Every
 * lookup here is on demand; the only thing cached is the topology, because
 * rooms do not appear and vanish between one card and the next.
 */
class Household(
    private val playerAt: (String) -> Transport,
    /**
     * Addresses to try before searching. A player's IP put in by hand is what
     * makes this work on a network where multicast is filtered — plenty of
     * mesh systems and guest VLANs drop it — and it is the first thing to
     * reach for when discovery comes back empty.
     */
    seedHosts: List<String> = emptyList(),
    private val discover: () -> List<String> = { Ssdp.discover() },
    /**
     * The multicast-free fallback. Injected so the tests can drive the
     * SSDP-fails-then-scan-succeeds path without a network.
     */
    private val scan: () -> SonosScan.Result = { SonosScan.scan() }
) {

    private val seeds = LinkedHashSet<String>(seedHosts)

    /**
     * What the last discovery attempt actually did, for the diagnostics page.
     * "No Sonos players found" is a symptom with several possible causes, and
     * without this the user is told the symptom and left to guess.
     */
    @Volatile
    var lastDiscovery: List<String> = emptyList()
        private set

    /**
     * True when a scan ran and not one player would answer. Distinct from
     * "nothing is playing": one means the app cannot see the household at all,
     * the other means it can and the house is quiet. Reporting the second when
     * the first is true is how a network fault gets mistaken for silence.
     */
    @Volatile
    var reachable: Boolean = false
        private set

    @Volatile
    private var zones: List<Zone> = emptyList()

    @Volatile
    private var topologyAt: Long = 0

    /** The group the user last chose, so a refresh does not wander. */
    @Volatile
    var preferredZoneUid: String? = null

    val knownHosts: List<String>
        get() = (zones.mapNotNull { it.ip.takeIf(String::isNotEmpty) } + seeds).distinct()

    /**
     * Re-read the household, searching for a player only when it has to.
     *
     * [force] is what the Refresh button sends: a regroup in the Sonos app
     * changes coordinators without changing anything this app would otherwise
     * notice, and a card headed with the wrong room is the result.
     */
    @Synchronized
    fun refresh(force: Boolean = false): List<Zone> {
        val fresh = System.currentTimeMillis() - topologyAt < TOPOLOGY_TTL_MS
        if (!force && fresh && zones.isNotEmpty()) return zones

        // AN ADDRESS WE ALREADY HAVE IS TRIED FIRST, ALWAYS.
        //
        // This used to search whenever `zones` was empty, which is true on
        // every cold start — so somebody who had put a speaker's address in
        // hosts.txt, precisely BECAUSE discovery does not work on their
        // network, still sat through a full multicast sweep and then a full
        // subnet scan before their own address was tried. Eleven seconds to
        // reach an answer that was in hand the whole time.
        var state = if (knownHosts.isEmpty()) null else fetchTopology()

        if (state.isNullOrEmpty()) {
            val notes = ArrayList<String>()
            if (knownHosts.isNotEmpty()) {
                notes += "${knownHosts.size} known address(es) did not answer; searching"
            }
            val byMulticast = discover()
            notes += "SSDP found ${byMulticast.size} player(s)"
            seeds += byMulticast

            // The fallback, and the reason this app works on networks that
            // filter multicast. Only once SSDP has come back empty — never as
            // the first move, because it is hundreds of connects where one
            // datagram would have done.
            if (byMulticast.isEmpty()) {
                notes += "SSDP found nothing; scanning this device's own subnets for port ${Sonos.PORT}"
                val scanned = scan()
                notes += scanned.notes
                seeds += scanned.hosts
            }
            lastDiscovery = notes
            state = fetchTopology()
        }

        if (state.isNullOrEmpty()) {
            // Keep the last good picture rather than emptying the UI: a single
            // failed scan is far more often a moment of wifi than a household
            // that has gone away.
            reachable = false
            Log.w(
                TAG,
                "no player answered. tried ${knownHosts.size} host(s): ${knownHosts.take(8)}. " +
                    "discovery: $lastDiscovery"
            )
            return zones
        }
        reachable = true

        val parsed = parseZoneGroupState(state)
        if (parsed.isEmpty()) return zones

        zones = parsed
        topologyAt = System.currentTimeMillis()
        // Learn every address, so the next scan does not depend on multicast
        // working twice.
        seeds += parsed.mapNotNull { it.ip.takeIf(String::isNotEmpty) }
        Log.i(TAG, "topology: ${parsed.size} zones, ${groups().size} groups")
        return zones
    }

    /**
     * What each host said when asked to describe the household, most recent
     * attempt only. Shown on the diagnostics page: "would not describe the
     * household" is a symptom, and a UPnP 401, a read timeout and an unparseable
     * reply need three different fixes.
     */
    @Volatile
    var lastTopologyErrors: List<String> = emptyList()
        private set

    private fun fetchTopology(): String? {
        val errors = ArrayList<String>()
        for (host in knownHosts) {
            val answer = try {
                playerAt(host).zoneGroupState()
            } catch (e: Throwable) {
                // Warn, not debug. This is the failure that produces "no players
                // found", and a message nobody can see is the reason that was a
                // guessing game twice over.
                val why = (e as? SoapError)?.detail
                    ?: "${e.javaClass.simpleName}: ${e.message ?: "no message"}"
                Log.w(TAG, "$host would not describe the household: $why")
                errors += "$host — $why"
                null
            }
            if (!answer.isNullOrEmpty()) {
                lastTopologyErrors = errors
                return answer
            }
            if (answer != null) errors += "$host — answered, but with an empty topology"
        }
        lastTopologyErrors = errors
        return null
    }

    /** The playable groups, coordinators first, in a stable order. */
    fun groups(): List<Group> {
        val all = zones
        val byUid = all.associateBy { it.uid }
        val out = LinkedHashMap<String, MutableList<Zone>>()

        for (zone in all) {
            if (!zone.playable) continue
            // A member whose coordinator is missing from the topology is
            // treated as its own coordinator rather than dropped: a room the
            // user can see in the Sonos app must not vanish from this list.
            val coordinatorUid =
                if (byUid.containsKey(zone.coordinatorUid)) zone.coordinatorUid else zone.uid
            out.getOrPut(coordinatorUid) { ArrayList() } += zone
        }

        return out.mapNotNull { (uid, members) ->
            val coordinator = byUid[uid] ?: return@mapNotNull null
            if (!coordinator.playable) return@mapNotNull null
            // The coordinator prints first; the rest alphabetically, so a
            // group's caption does not reshuffle between two cards.
            val ordered = listOf(coordinator) +
                members.filter { it.uid != uid }.sortedBy { it.name.lowercase() }
            Group(coordinator, ordered)
        }.sortedBy { it.coordinator.name.lowercase() }
    }

    fun group(uid: String?): Group? {
        if (uid.isNullOrEmpty()) return null
        val all = groups()
        return all.firstOrNull { it.uid == uid }
        // A room that has since been grouped into another is not gone — its
        // music is now coming out of the coordinator, and that is the card the
        // user is asking for.
            ?: all.firstOrNull { group -> group.members.any { it.uid == uid } }
    }

    /** Exactly what a coordinator reports, unparsed. For the diagnostics page. */
    fun rawFor(group: Group): Map<String, String> = try {
        playerAt(group.coordinator.ip).rawNowPlaying()
    } catch (e: Throwable) {
        mapOf("error" to "${e.javaClass.simpleName}: ${e.message ?: "no message"}")
    }

    /** What one group's transport is doing, asked directly. */
    fun stateOf(group: Group): ZoneState? {
        val player = playerAt(group.coordinator.ip)
        return try {
            val transport = player.transportState()
            val track = player.positionInfo()
            // GetMediaInfo is a second call, so it is made only when the first
            // one came back short — which is radio and line-in, where the
            // station name lives on the transport rather than the track.
            val (media, uri) = if (track.album.isEmpty() || track.isEmpty) {
                player.mediaInfo()
            } else {
                NowPlaying() to ""
            }
            ZoneState(group, transport, merge(track, media), uri)
        } catch (e: Throwable) {
            Log.w(TAG, "${group.coordinator.name} would not answer: ${e.message}")
            null
        }
    }

    /**
     * Fill the gaps in a track's metadata from the transport's.
     *
     * Field by field rather than "use one or the other": a radio stream sends
     * a title per song and a station name on the transport, and the card wants
     * the song with the station as its context — not one of the two.
     */
    internal fun merge(track: NowPlaying, media: NowPlaying): NowPlaying {
        if (media.isEmpty && media.artUri.isEmpty()) return track
        return NowPlaying(
            track = track.track.ifEmpty { media.track },
            album = track.album.ifEmpty { media.album },
            artist = track.artist.ifEmpty { media.artist },
            albumArtist = track.albumArtist.ifEmpty { media.albumArtist },
            artUri = track.artUri.ifEmpty { media.artUri },
            uri = track.uri.ifEmpty { media.uri },
            streamContent = track.streamContent.ifEmpty { media.streamContent }
        )
    }

    /**
     * The group the card should be about.
     *
     * The rule, in order:
     *   1. the group the user picked, if it is still playing something;
     *   2. any group that is PLAYING — the common case, one room is on;
     *   3. the group the user picked, playing or not;
     *   4. any group with metadata on its transport, so a paused room still
     *      makes a card;
     *   5. nothing, and the page says so.
     *
     * A user's choice does NOT beat a room that is actually playing when the
     * chosen room has fallen silent — the app's purpose is a card for what is
     * on, and answering with an hour-old paused album because that room was
     * picked once is the wrong answer to the question being asked.
     */
    fun nowPlaying(preferUid: String? = preferredZoneUid): ZoneState? {
        val groups = groups()
        if (groups.isEmpty()) return null

        val chosen = preferUid?.let { group(it) }
        // Ask the chosen room first so the common case — one room, picked,
        // playing — costs exactly one round trip to one speaker.
        val chosenState = chosen?.let { stateOf(it) }
        if (chosenState != null && chosenState.isPlaying) return chosenState

        val others = groups.filter { it.uid != chosen?.uid }.mapNotNull { stateOf(it) }
        others.firstOrNull { it.isPlaying }?.let { return it }

        if (chosenState != null && !chosenState.nowPlaying.isEmpty) return chosenState
        return others.firstOrNull { !it.nowPlaying.isEmpty } ?: chosenState
    }

    private companion object {
        const val TAG = "Household"

        /**
         * Rooms do not come and go between one card and the next, so the
         * topology is held rather than re-read on every request. Short enough
         * that a regroup done in the Sonos app is picked up without anybody
         * having to press anything.
         */
        const val TOPOLOGY_TTL_MS = 60_000L
    }
}
