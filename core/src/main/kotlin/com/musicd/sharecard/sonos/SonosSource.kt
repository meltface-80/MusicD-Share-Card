package com.musicd.sharecard.sonos

import com.musicd.sharecard.source.PlayState
import com.musicd.sharecard.source.Playing
import com.musicd.sharecard.source.Source
import com.musicd.sharecard.source.ZoneRef

/**
 * Sonos, behind the common source interface.
 *
 * [Household] does the work and is unchanged — this is the adapter that lets a
 * speaker sit alongside a Roon Core and a DLNA renderer as peers rather than as
 * the thing the whole app is shaped around.
 *
 * Sonos is the right source for anything the SPEAKERS are streaming themselves:
 * Spotify Connect, Apple Music through the Sonos app, a Sonos playlist, radio.
 * It is the wrong one for Roon, which hands it a session id and keeps the
 * record to itself.
 */
class SonosSource(private val household: Household) : Source {

    override val name: String = NAME

    override fun start() {
        // Discovery is on demand: the first question asked does it. Doing it at
        // startup would mean a scan every boot for an answer nobody wanted yet.
    }

    override fun refresh() {
        household.refresh(force = true)
    }

    override fun zones(): List<ZoneRef> {
        household.refresh()
        return household.groups().map { group ->
            ZoneRef(
                source = name,
                id = ZoneRef.idFor(name, group.uid),
                name = group.displayName,
                rooms = group.roomNames
            )
        }
    }

    override fun nowPlaying(zoneId: String?): Playing? {
        household.refresh()
        // zoneId arrives already stripped of its "sonos:" prefix, which is
        // exactly the uid Household knows this group by.
        val state = household.nowPlaying(zoneId) ?: return null
        return playingOf(state)
    }

    internal fun playingOf(state: ZoneState): Playing? {
        val np = state.nowPlaying
        // A station announcing "Artist - Title" is the only metadata some
        // streams ever send.
        val announced = if (np.artist.isEmpty() && np.streamContent.isNotEmpty()) {
            Didl.splitStreamContent(np.streamContent)
        } else {
            null
        }
        val album = np.displayAlbum.ifEmpty { announced?.second.orEmpty() }
        val artist = np.displayArtist.ifEmpty { announced?.first.orEmpty() }
        val track = np.track.ifEmpty { announced?.second.orEmpty() }

        return Playing(
            source = name,
            zoneId = ZoneRef.idFor(name, state.group.uid),
            zoneName = state.group.displayName,
            album = album,
            artist = artist,
            // A title that is an opaque session id is not a track name — see
            // Didl.looksLikeStreamId. Roon streaming here is exactly that case.
            track = if (Didl.looksLikeStreamId(track)) "" else track,
            state = when (state.state) {
                TransportState.PLAYING -> PlayState.PLAYING
                TransportState.PAUSED -> PlayState.PAUSED
                TransportState.STOPPED -> PlayState.STOPPED
                else -> PlayState.UNKNOWN
            },
            isStream = state.isStream,
            artUrl = Didl.absoluteArt(np.artUri, state.group.coordinator.baseUrl)
        )
    }

    /** The speakers themselves, which serve their own `/getaa` art. */
    override fun artHosts(): Collection<String> = household.knownHosts

    override fun diagnostics(): List<String> {
        val lines = ArrayList<String>()
        lines += "reachable: ${household.reachable}"
        lines += household.lastDiscovery
        lines += "addresses: ${household.knownHosts}"
        if (household.lastTopologyErrors.isNotEmpty()) {
            lines += household.lastTopologyErrors.map { "error: $it" }
        }
        for (group in household.groups()) {
            val state = household.stateOf(group)
            lines += "  ${group.displayName} (${group.coordinator.ip}) — " +
                (state?.state?.name ?: "no answer")
        }
        return lines
    }

    companion object {
        const val NAME = "Sonos"
    }
}
