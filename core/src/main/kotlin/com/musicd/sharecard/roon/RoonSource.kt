package com.musicd.sharecard.roon

import com.musicd.sharecard.source.PlayState
import com.musicd.sharecard.source.Playing
import com.musicd.sharecard.source.Source
import com.musicd.sharecard.source.ZoneRef
import java.net.URI

/**
 * Roon, as a source of cards.
 *
 * THIS IS WHY THE ABSTRACTION EXISTS. Roon streaming to a Sonos speaker hands
 * it a session id — "Roon" followed by 32 hex characters — where the title
 * should be, and no artist, no album and no cover at all. Read through the
 * speaker the record is simply not there. Asked directly, Roon knows exactly
 * what it is playing, because it is the thing playing it.
 *
 * `now_playing.three_line` is the shape that matters: line1 is the track,
 * line2 the artist, line3 the ALBUM. The two_line and one_line variants fold
 * those together, which is why [NowPlaying.parse] prefers three.
 */
class RoonSource(private val client: RoonClient) : Source {

    override val name: String = NAME

    override fun start() = client.start()

    override fun stop() = client.stop()

    override fun refresh() = client.rediscover()

    override fun zones(): List<ZoneRef> = client.zones().map { zone ->
        ZoneRef(
            source = name,
            id = ZoneRef.idFor(name, zone.zoneId),
            name = zone.displayName,
            rooms = listOf(zone.displayName)
        )
    }

    override fun nowPlaying(zoneId: String?): Playing? {
        val zone = if (zoneId != null) {
            client.zone(zoneId) ?: return null
        } else {
            // Roon holds the whole household in memory, so picking the playing
            // one costs nothing — unlike a speaker, which has to be asked.
            client.zones().firstOrNull { it.isPlaying }
                ?: client.zones().firstOrNull { it.nowPlaying != null }
                ?: return null
        }
        return playingOf(zone)
    }

    internal fun playingOf(zone: Zone): Playing? {
        val np = zone.nowPlaying ?: return null
        return Playing(
            source = name,
            zoneId = ZoneRef.idFor(name, zone.zoneId),
            zoneName = zone.displayName,
            // three_line puts the album on line3 and the artist on line2. Read
            // in the wrong order this produces a card headed with a track name,
            // which looks almost right and is not.
            album = np.line3,
            artist = np.line2,
            track = np.line1,
            state = when (zone.state) {
                "playing" -> PlayState.PLAYING
                "paused" -> PlayState.PAUSED
                "stopped" -> PlayState.STOPPED
                else -> PlayState.UNKNOWN
            },
            // Roon knows what a live stream is and reports it as a record
            // anyway; there is no separate flag worth inventing here.
            isStream = false,
            artUrl = np.imageKey?.let { client.imageUrl(it) }.orEmpty()
        )
    }

    /**
     * What the user has to do about Roon, in one sentence, or nothing.
     *
     * EVERY stage that is not PAIRED gets a line. Only AWAITING_APPROVAL did at
     * first, which meant a Core that was never found, or one that refused the
     * registration, said nothing at all — and "Roon is not working" with no
     * explanation is exactly the position this app kept putting people in.
     */
    override fun notice(): String? = when (client.status.stage) {
        RoonStage.AWAITING_APPROVAL ->
            "Roon found. Enable \u201CMusicD Share Card\u201D in Roon \u2192 Settings \u2192 " +
                "Extensions, then press refresh."
        RoonStage.DISCOVERING -> "Looking for your Roon Core\u2026"
        RoonStage.CONNECTING -> "Connecting to Roon\u2026"
        RoonStage.ERROR ->
            "Roon: " + (client.status.detail ?: "not connected") + "."
        RoonStage.IDLE -> "Roon has not started looking yet."
        RoonStage.PAIRED -> null
    }

    /**
     * The Core's own address, so the art proxy will fetch from its image
     * service and from nothing else on the network.
     */
    override fun artHosts(): Collection<String> =
        listOfNotNull(client.coreHost)

    override fun diagnostics(): List<String> {
        val status = client.status
        val lines = ArrayList<String>()
        lines += "${status.stage}: ${status.detail ?: "—"}"
        status.coreName?.let { lines += "core: $it" }
        client.coreHost?.let { lines += "address: $it" }
        val zones = client.zones()
        lines += "${zones.size} zone(s)"
        for (zone in zones) {
            val np = zone.nowPlaying
            lines += "  ${zone.displayName} — ${zone.state}" +
                if (np != null) ": ${np.line3.ifEmpty { "?" }} / ${np.line2.ifEmpty { "?" }}" else ""
        }
        if (status.stage == RoonStage.AWAITING_APPROVAL) {
            lines += "ACTION NEEDED: enable this extension in Roon → Settings → Extensions"
        }
        return lines
    }

    companion object {
        const val NAME = "Roon"

        /** The host half of an image URL, for the art allow-list. */
        internal fun hostOf(url: String): String? =
            runCatching { URI(url).host }.getOrNull()?.takeIf { it.isNotEmpty() }
    }
}
