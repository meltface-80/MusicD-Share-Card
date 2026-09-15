package com.musicd.sharecard.source

/**
 * Where "what is playing" can come from.
 *
 * THE APP IS NOT A SONOS APP. Sonos was the first thing wired up and for a
 * while it was the only thing, which left its shape pressed into every layer —
 * "zone" meant a Sonos group, art meant a player path, and a source that
 * reported none of that could not be represented at all. Roon proved the point
 * by handing Sonos its session id as a track title: read through the speaker,
 * the record was simply not there, however carefully the DIDL was parsed.
 *
 * So a source is anything that can answer the one question this app asks, and
 * the good answer comes from whoever actually knows:
 *
 *   - [com.musicd.sharecard.roon.RoonSource] asks Roon, which has the record,
 *     the artist and a real cover, because Roon is the thing playing it.
 *   - [com.musicd.sharecard.sonos.SonosSource] asks the speakers, which is
 *     right for anything the speakers themselves are streaming.
 *   - [com.musicd.sharecard.upnp.UpnpSource] asks any DLNA renderer, which is
 *     the same conversation without Sonos's private extensions.
 *
 * They are peers, and more can be added without touching the card, the page or
 * the API.
 */
interface Source {

    /** Shown next to the room name when more than one source has zones. */
    val name: String

    /** Begin whatever long-lived work this source needs. Called once. */
    fun start() {}

    fun stop() {}

    /** Everything this source could make a card about, for the picker. */
    fun zones(): List<ZoneRef>

    /**
     * What is playing.
     *
     * [zoneId] is one of this source's own ids, already stripped of its prefix.
     * Null means "whichever of yours is playing" — the source picks, because
     * only it knows what asking costs.
     */
    fun nowPlaying(zoneId: String?): Playing?

    /**
     * Hosts this source's artwork may be fetched from.
     *
     * The art proxy allows a LAN address only if a source names it, so a Roon
     * Core's image service and a speaker's `/getaa` are reachable and nothing
     * else on the network is.
     */
    fun artHosts(): Collection<String> = emptyList()

    /** Lines for the diagnostics page: what this source found, and what it did not. */
    fun diagnostics(): List<String> = emptyList()

    /**
     * One sentence the user must act on, or null.
     *
     * Roon does not answer a registration until somebody has enabled the
     * extension in Settings → Extensions, and that wait is open ended. Without
     * saying so the first run looks exactly like a broken app: no zones, no
     * card, no reason.
     */
    fun notice(): String? = null

    /** Re-look for devices. What the Refresh button asks for. */
    fun refresh() {}
}

/*
 * THE RANK LIVES HERE, BESIDE THE ANSWER IT RANKS, AND NOT INSIDE ONE CALLER.
 *
 * It was a member of [Sources] while the ladder was its only user. A source
 * that holds SEVERAL answers of its own needs exactly the same rule to pick
 * between them — DeviceSource sees every app on the phone at once — and two
 * copies of a folding rule is how one caller ranks an album over an artist and
 * the next does not. The same move `Normalize.namesOverlap` and
 * `Normalize.stripEdition` both made when they got a second caller.
 */
/**
 * How much of a record an answer actually describes.
 *
 * THIS IS WHAT "ASK WHOEVER KNOWS" ACTUALLY MEANS, and the ordering of
 * sources alone was not enough to deliver it. When Roon plays to a Sonos
 * speaker BOTH sources see that room and both say "playing" — so a rule
 * that returned the first playing answer returned whichever was asked
 * first, and if the user had picked the Sonos zone that was a card headed
 * with a session id while Roon sat there knowing the album.
 *
 * Ranking on the answer rather than on the source also means this does not
 * depend on recognising a hash. `Didl.looksLikeStreamId` catches the shapes
 * it knows; a source that reports some other kind of rubbish still loses to
 * one that reports an album and an artist.
 */
internal fun quality(playing: Playing): Int = when {
    playing.album.isNotEmpty() && playing.artist.isNotEmpty() -> 3
    playing.artist.isNotEmpty() -> 2
    playing.album.isNotEmpty() -> 1
    playing.track.isNotEmpty() -> 0
    else -> -1
}

/** One thing a card can be made about, named uniquely across sources. */
data class ZoneRef(
    val source: String,
    /** Unique across every source: "roon:16018…", "sonos:RINCON_…". */
    val id: String,
    val name: String,
    /** The rooms this zone covers, when it spans several. */
    val rooms: List<String> = emptyList()
) {
    companion object {
        /** Ids are prefixed so two sources cannot collide on one. */
        fun idFor(source: String, raw: String): String = "${source.lowercase()}:$raw"

        /** The source half of a prefixed id, or null when it carries no prefix. */
        fun sourceOf(id: String): String? =
            id.substringBefore(':', "").takeIf { it.isNotEmpty() && it != id }

        /** The source's own id, with the prefix taken off. */
        fun rawOf(id: String): String = id.substringAfter(':', id)
    }
}

enum class PlayState { PLAYING, PAUSED, STOPPED, UNKNOWN;

    val isPlaying: Boolean get() = this == PLAYING
}

/**
 * A record, as the card needs it, with every source's quirks already resolved.
 *
 * Deliberately flat and source-agnostic: by the time something reaches here,
 * the question of whether `dc:creator` beat `upnp:artist` or whether Roon's
 * three_line put the album on line 3 has been settled by the source that
 * understands it.
 */
data class Playing(
    val source: String,
    val zoneId: String,
    val zoneName: String,
    val album: String,
    val artist: String,
    val track: String = "",
    val state: PlayState = PlayState.UNKNOWN,
    /** Live radio rather than a record: there is no album to look up. */
    val isStream: Boolean = false,
    /**
     * An absolute URL the art proxy can fetch. Empty when the source has no
     * cover — which is a fact worth carrying, not a gap to paper over.
     */
    val artUrl: String = ""
) {
    /** Nothing worth drawing a card about. */
    val isEmpty: Boolean get() = album.isEmpty() && artist.isEmpty() && track.isEmpty()
}
