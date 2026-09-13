package com.musicd.sharecard.lms

import com.musicd.sharecard.Log
import com.musicd.sharecard.source.PlayState
import com.musicd.sharecard.source.Playing
import com.musicd.sharecard.source.Source
import com.musicd.sharecard.source.ZoneRef

/**
 * Lyrion Music Server as a [Source].
 *
 * ASK THE SERVER, NOT THE PLAYER. A Squeezebox, a piCorePlayer or a squeezelite
 * process knows almost nothing about what it is playing — the server holds the
 * library, resolves the metadata and owns the artwork. This is the same rule
 * Roon taught: ask whoever actually knows, which here is the one machine rather
 * than the seven in the house.
 *
 * FINDING IT IS THE FRAGILE PART, so there are two ways and both are reported.
 * Lyrion answers a UDP broadcast on 3483, which is how its own players find it
 * and which nothing behind a mesh router, a guest VLAN or a Docker bridge will
 * ever see. An address typed into the same file Sonos already uses is the
 * fallback, and on those networks it is the only thing that works.
 */
class LmsSource(
    private val client: LmsClient,
    /** Addresses typed in by hand — the same list a Sonos player can be named in. */
    private val hosts: () -> List<String> = { emptyList() },
    /** Servers that answered the broadcast. Injected so a test need not open a socket. */
    private val discover: () -> List<LmsDiscovery.Server> = { LmsDiscovery.discover() },
    private val port: Int = LmsClient.DEFAULT_PORT
) : Source {

    override val name: String = NAME

    /** The server that answered, as a base URL, or null while none has. */
    @Volatile
    private var base: String? = null

    @Volatile
    private var players: List<LmsClient.Player> = emptyList()

    @Volatile
    private var notes: List<String> = emptyList()

    @Volatile
    private var scannedAt: Long = 0

    override fun refresh() {
        scannedAt = 0
        base = null
        scan()
    }

    /**
     * Find a server and remember what it holds.
     *
     * The found server is kept and re-asked rather than re-discovered, because
     * discovery is a broadcast and this runs on a device that is never switched
     * off. It is dropped only when it stops answering, or on Refresh.
     */
    @Synchronized
    private fun scan() {
        val known = base
        if (known != null && System.currentTimeMillis() - scannedAt < RESCAN_MS) return

        val found = ArrayList<String>()
        val candidates = LinkedHashSet<String>()
        known?.let { candidates += it }
        for (server in runCatching { discover() }.getOrElse {
            found += "broadcast failed: ${it.message}"
            emptyList()
        }) {
            candidates += "http://${server.host}:${server.port}"
            found += "broadcast: ${server.name.ifEmpty { server.host }} at ${server.host}:${server.port}"
        }
        for (host in runCatching { hosts() }.getOrDefault(emptyList())) {
            candidates += "http://$host:$port"
        }
        if (candidates.isEmpty()) {
            notes = found + "no server found, and no address configured"
            players = emptyList()
            return
        }

        for (candidate in candidates) {
            val answered = client.players(candidate)
            if (answered.isEmpty()) {
                found += "$candidate -> no players"
                continue
            }
            base = candidate
            players = answered
            scannedAt = System.currentTimeMillis()
            notes = found + "$candidate -> ${answered.size} player(s)"
            return
        }
        // Nothing answered. Forget the old server too — keeping it would mean
        // every later read failing against a machine that has gone.
        base = null
        players = emptyList()
        notes = found + "asked ${candidates.size}, none answered"
    }

    override fun zones(): List<ZoneRef> {
        scan()
        return players.map {
            ZoneRef(
                source = name,
                id = ZoneRef.idFor(name, it.id),
                name = it.name,
                rooms = listOf(it.name)
            )
        }
    }

    override fun nowPlaying(zoneId: String?): Playing? {
        scan()
        val server = base ?: return null
        // A NAMED PLAYER IS A LOCK, like every other source: answer for that
        // room, silence included, and never for a neighbour that happens to be
        // louder. See the zone-independence rule in CLAUDE.md.
        val candidates = if (zoneId != null) players.filter { it.id == zoneId } else players
        var fallback: Playing? = null
        for (player in candidates) {
            val playing = read(server, player) ?: continue
            if (playing.state.isPlaying) return playing
            if (fallback == null && !playing.isEmpty) fallback = playing
        }
        return fallback
    }

    private fun read(server: String, player: LmsClient.Player): Playing? = try {
        val now = LmsStatus.read(client.status(server, player.id), server)
        Playing(
            source = name,
            zoneId = ZoneRef.idFor(name, player.id),
            zoneName = player.name,
            album = now.album,
            artist = now.artist,
            track = now.title,
            state = now.state,
            isStream = now.isStream,
            artUrl = now.artUrl
        )
    } catch (e: Throwable) {
        Log.w(TAG, "${player.name} would not answer: ${e.message}")
        null
    }

    /**
     * The server's own host, so the art proxy will fetch a cover from it.
     *
     * Only the server: the players serve no artwork, and naming them would open
     * the proxy to addresses for no reason.
     */
    override fun artHosts(): Collection<String> =
        listOfNotNull(base?.substringAfter("://")?.substringBefore(':'))

    override fun diagnostics(): List<String> {
        scan()
        return notes + players.map {
            "  ${it.name} (${it.id})" + if (it.connected) "" else " — not connected"
        }
    }

    /**
     * A server nobody can find is the same dead end Roon's approval wait was.
     *
     * Silent when no address is configured AND nothing answered: that is the
     * ordinary state of a house with no Lyrion in it, and a notice there would
     * nag every user who does not run one.
     */
    override fun notice(): String? = null

    private companion object {
        const val TAG = "Lms"
        const val NAME = "Lyrion"

        const val RESCAN_MS = 60_000L
    }
}
