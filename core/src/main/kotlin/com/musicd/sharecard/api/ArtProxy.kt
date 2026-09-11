package com.musicd.sharecard.api

import com.musicd.sharecard.Log
import com.musicd.sharecard.meta.TtlCache
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI

/**
 * The cover, fetched from the player and served from here.
 *
 * THIS IS NOT AN OPTIMISATION, IT IS WHAT MAKES THE CARD POSSIBLE. The card is
 * drawn into a `<canvas>` and read back out with `toBlob`, and a canvas that
 * has drawn an image from another origin is tainted — the read throws a
 * SecurityException and there is no picture to share. Sonos serves its art from
 * `http://<player>:1400/getaa?...` with no CORS header of any kind, so drawing
 * it directly can never work. Proxying it through this server makes it
 * same-origin, and the canvas stays clean.
 *
 * It also means a browser on a phone never talks to a speaker directly, which
 * is what lets the page work from anywhere the card server is reachable.
 */
class ArtProxy(
    private val http: OkHttpClient,
    /**
     * The player addresses this household actually has. The LAN half of the
     * rule is tied to real discovered players rather than to "looks private",
     * so a request can reach a speaker and nothing else on the network.
     */
    private val knownPlayers: () -> Collection<String> = { emptyList() }
) {

    /** One picture in flight, the one on screen, and a couple behind it. */
    private val cache = TtlCache<String, Art>(CACHE_MS, 8)

    class Art(val bytes: ByteArray, val contentType: String)

    /**
     * Fetch [url], or answer from the cache.
     *
     * Only http/https to a private address is allowed. That is not paranoia
     * about Sonos: the URL arrives as a query parameter on a route this app
     * serves to the whole LAN, so without this check anybody on the network
     * could hand it any URL and have the app fetch it for them — including one
     * naming a host only the app can reach. The card never needs an address
     * off the local network, so nothing legitimate is lost by refusing.
     */
    fun fetch(url: String): Art? {
        if (!isAllowed(url)) {
            Log.w(TAG, "refusing to fetch $url")
            return null
        }
        cache.peek(url)?.let { return it }

        val request = Request.Builder().url(url).header("Connection", "close").build()
        return try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.d(TAG, "$url -> ${response.code}")
                    return null
                }
                val body = response.body ?: return null
                val bytes = body.bytes()
                if (bytes.isEmpty() || bytes.size > MAX_BYTES) {
                    Log.d(TAG, "$url -> ${bytes.size} bytes, ignored")
                    return null
                }
                val type = body.contentType()?.toString()?.takeIf { it.startsWith("image/") }
                    ?: "image/jpeg"
                Art(bytes, type).also { cache.put(url, it) }
            }
        } catch (e: Exception) {
            Log.d(TAG, "$url failed: ${e.message}")
            null
        }
    }

    /**
     * TWO WAYS IN, AND THE OLD RULE WAS BACKWARDS.
     *
     * This used to allow any private address and refuse everything else, on the
     * reasoning that Sonos art lives on the LAN. Both halves were wrong:
     *
     *  - It refused the art that Spotify Connect actually reports, which is an
     *    absolute `https://i.scdn.co/...` CDN link, not a player path. That is
     *    why a Spotify card came out with a blank sleeve while the Sonos app
     *    showed the cover perfectly.
     *  - As an SSRF guard it pointed the wrong way. The addresses worth
     *    protecting are exactly the internal ones — `127.0.0.1`, and
     *    `169.254.169.254`, which is the cloud metadata endpoint — and it was
     *    letting both through while blocking the public host it needed.
     *
     * So now: a LAN address is allowed only if it is one of THIS household's
     * known players, and any other host must be public. That is narrower on the
     * inside and wider on the outside, which is the right shape for both jobs.
     */
    internal fun isAllowed(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host?.takeIf { it.isNotEmpty() } ?: return false

        // A player we have actually discovered. Not "an address that looks
        // local" — this one, on the port Sonos serves art from.
        if (host in knownPlayers()) return true

        // Anything else has to be somewhere this app could not otherwise
        // reach: a music service's CDN, over TLS.
        return scheme == "https" && isPublicHost(host)
    }

    /**
     * Confidently public — not merely "not recognised as private".
     *
     * That distinction is the whole of this function, and getting it backwards
     * is a hole rather than an inconvenience. The leading-zero check below was
     * written when a private address meant ALLOW, so refusing to classify
     * "0177.0.0.1" was the safe answer. Under the inverted rule the same
     * refusal makes it PUBLIC — and 0177.0.0.1 is octal for 127.0.0.1.
     *
     * So anything that looks like it is trying to be an IP address must prove
     * it is an ordinary, unambiguous, public dotted quad. A name is allowed
     * because a name is what a CDN uses; every numeric form is either a clean
     * public quad or refused.
     */
    internal fun isPublicHost(host: String): Boolean {
        val h = host.trim().trim('[', ']').lowercase()
        if (h.isEmpty()) return false

        // IPv6 has too many ways to spell loopback and mapped IPv4 to be worth
        // classifying here, and no music service needs one.
        if (h.contains(':')) return false

        val looksNumeric = h.all { it.isDigit() || it == '.' }
        if (!looksNumeric) {
            // A hostname. It could still resolve inwards, which is why the LAN
            // side of this rule is an explicit list of known players rather
            // than anything inferred from a name.
            return true
        }

        // From here it is trying to be an address, so it must be a clean quad.
        val parts = h.split('.')
        if (parts.size != 4) return false
        // "010" is octal to some resolvers and decimal to others.
        if (parts.any { it.isEmpty() || (it.length > 1 && it.startsWith("0")) }) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false

        val (a, b) = octets
        return when {
            isPrivateHost(h) -> false
            a == 0 -> false                        // "this network"
            a >= 224 -> false                      // multicast and reserved
            a == 100 && b in 64..127 -> false      // carrier-grade NAT
            a == 198 && b in 18..19 -> false       // benchmarking
            else -> true
        }
    }

    /**
     * An address on the local network, loopback, or link-local.
     *
     * Matched on the TEXT of the host rather than by resolving it, deliberately:
     * resolving first would let a name that points at a private address pass
     * this check and then be fetched from a different answer the second time.
     * A hostname is therefore not private by this test — it is refused by being
     * neither a known player nor, if it resolves inwards, something this app
     * needs.
     */
    internal fun isPrivateHost(host: String): Boolean {
        val h = host.trim().trim('[', ']').lowercase()
        if (h == "localhost" || h == "::1" || h.startsWith("fe80:") || h == "0:0:0:0:0:0:0:1") {
            return true
        }
        val parts = h.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false
        // Leading zeroes would be read as octal by some resolvers and decimal
        // by others, so "010.0.0.1" is refused rather than guessed at.
        if (parts.any { it.length > 1 && it.startsWith("0") }) return false
        val (a, b) = octets
        return when {
            a == 10 -> true                       // 10.0.0.0/8
            a == 127 -> true                      // loopback
            a == 172 && b in 16..31 -> true       // 172.16.0.0/12
            a == 192 && b == 168 -> true          // 192.168.0.0/16
            a == 169 && b == 254 -> true          // link-local
            else -> false
        }
    }

    private companion object {
        const val TAG = "Art"

        /** Long enough to cover a card being drawn and then redrawn. */
        const val CACHE_MS = 5L * 60 * 1000

        /** A sleeve. Anything larger is not one. */
        const val MAX_BYTES = 12 * 1024 * 1024
    }
}
