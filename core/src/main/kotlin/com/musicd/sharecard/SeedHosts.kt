package com.musicd.sharecard

/**
 * Player addresses typed in by hand, for networks where discovery cannot work.
 *
 * SSDP is multicast, and plenty of mesh systems, guest VLANs and switches with
 * "client isolation" drop it. On those networks nothing this app does to its own
 * discovery will help — and one speaker's IP address fixes it completely,
 * because the whole household topology comes from any single player.
 *
 * The PARSING lives here rather than in the Android module because :core is the
 * only place with tests, and a bad address stored here fails later as "no Sonos
 * players found" — the same message a network problem produces, which is the
 * worst kind of failure to debug at a distance.
 *
 * NOT ONLY SONOS ANY MORE. Lyrion is found by a UDP broadcast on 3483, which
 * the same networks drop for the same reasons, so an address here is tried as a
 * Lyrion server too. One list, because from the user's side the question is the
 * same one — "discovery cannot see it, here is where it lives" — and a second
 * file asking that again per protocol would be a worse answer.
 */
object SeedHosts {

    /** The file the Android module reads this out of. */
    const val FILE_NAME = "hosts.txt"

    /**
     * Addresses from the file's text.
     *
     * One per line or comma-separated; blank lines are skipped and anything
     * after a `#` is a comment, so the file can explain itself to whoever opens
     * it in six months.
     */
    fun parse(raw: String): List<String> =
        raw.lineSequence()
            .map { it.substringBefore('#').trim() }
            .flatMap { it.split(',', ' ', '\t').asSequence() }
            .map { it.trim() }
            .filter { it.isNotEmpty() && isIpv4(it) }
            .distinct()
            .toList()

    /**
     * A literal IPv4 address, and nothing else.
     *
     * A hostname is refused for the same reason the art proxy refuses one: what
     * is wanted here is an address on the local network, and a name is a
     * resolver's answer rather than a fact. A leading zero is refused because
     * some resolvers read it as octal and some as decimal, so "010.0.0.1" names
     * two different hosts depending on who is asking.
     */
    fun isIpv4(s: String): Boolean {
        val parts = s.split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            val n = part.toIntOrNull() ?: return false
            n in 0..255 && (part.length == 1 || !part.startsWith("0"))
        }
    }
}
