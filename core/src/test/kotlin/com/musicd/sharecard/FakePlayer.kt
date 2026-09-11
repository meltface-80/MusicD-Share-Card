package com.musicd.sharecard

import com.musicd.sharecard.sonos.NowPlaying
import com.musicd.sharecard.sonos.Sonos
import com.musicd.sharecard.sonos.Transport
import com.musicd.sharecard.sonos.TransportState

/**
 * A player that answers from a script rather than from a speaker.
 *
 * [asked] is what makes the "does it cost one round trip or five" claims in
 * [Household] testable rather than asserted in a comment.
 */
class FakePlayer(
    private val ip: String,
    var state: TransportState = TransportState.STOPPED,
    var track: NowPlaying = NowPlaying(),
    var media: NowPlaying = NowPlaying(),
    var currentUri: String = "",
    var topology: String = "",
    /** When set, every call throws — a speaker that has dropped off wifi. */
    var offline: Boolean = false
) : Transport {

    val asked = mutableListOf<String>()

    override val baseUrl: String get() = Sonos.baseUrl(ip)

    private fun <T> record(what: String, value: () -> T): T {
        asked += what
        if (offline) throw RuntimeException("$ip is not answering")
        return value()
    }

    override fun zoneGroupState(): String = record("topology") { topology }
    override fun transportState(): TransportState = record("transport") { state }
    override fun positionInfo(): NowPlaying = record("position") { track }
    override fun mediaInfo(): Pair<NowPlaying, String> = record("media") { media to currentUri }
}
