package com.musicd.sharecard.source

import java.net.URI
import java.util.Collections

/**
 * Machines that are currently sending audio to a speaker in this house.
 *
 * WHY THIS EXISTS. The art proxy allows a known player or a confidently public
 * https host, and nothing else — see `ArtProxy`. That is right for a URL this
 * app found lying around, and wrong for the one case it kept refusing: a music
 * server on the LAN streaming to a Sonos or a DLNA renderer, which puts an
 * ABSOLUTE art URL on its own address into the DIDL.
 *
 * Reported from the field. A MusicD Server on a DietPi box at 192.168.0.57:3400
 * was streaming "Heaven or Las Vegas" to a Sonos, and the card came out with
 * the album, the artist and the Wikipedia blurb and a blank sleeve — while the
 * Sonos app three feet away showed the cover, because it fetches that URL
 * directly. The same happens to Lyrion, MinimServer, Plex and anything else
 * somebody runs at home.
 *
 * THE RULE, AND WHY IT IS NOT A WIDENING WORTH WORRYING ABOUT. A machine that
 * is already streaming audio INTO a speaker here is not a new trust decision:
 * it is playing the music. So the host named by the track's own transport URI
 * may also serve that track's picture. Nothing is inferred from the art URL
 * itself, which is the part an attacker would control — the host has to have
 * been observed carrying the audio first.
 *
 * What it deliberately does NOT do is trust an address because it looks local.
 * That was the bug the art proxy's whole comment block is about, and this does
 * not reintroduce it: an address gets in by being seen in a `res` element of a
 * live transport, or not at all.
 */
class StreamHosts(private val limit: Int = LIMIT) {

    /**
     * Insertion-ordered and capped, so a long-running device does not
     * accumulate every station it has ever played. Synchronised because the
     * card path writes while a later art request reads.
     */
    private val hosts = Collections.synchronizedSet(LinkedHashSet<String>())

    /**
     * Note the host of a transport URI, if it has one worth noting.
     *
     * Only http and https: Sonos's own `x-rincon:`, `x-sonos-htastream:`,
     * `x-file-cifs:` and friends name a speaker, a TV input or a share rather
     * than something that serves pictures over HTTP.
     */
    fun remember(uri: String) {
        val trimmed = uri.trim()
        if (!trimmed.startsWith("http://", true) && !trimmed.startsWith("https://", true)) return
        val host = runCatching { URI(trimmed).host }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return
        synchronized(hosts) {
            // Re-inserting moves it to the end, so the cap drops what has not
            // been played for longest rather than what was found first.
            hosts.remove(host)
            hosts += host
            while (hosts.size > limit) {
                val oldest = hosts.first()
                hosts.remove(oldest)
            }
        }
    }

    fun hosts(): Set<String> = synchronized(hosts) { LinkedHashSet(hosts) }

    private companion object {
        /** Enough for a household's servers and a few stations. */
        const val LIMIT = 8
    }
}
