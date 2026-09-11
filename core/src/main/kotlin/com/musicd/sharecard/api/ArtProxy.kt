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
class ArtProxy(private val http: OkHttpClient) {

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

    internal fun isAllowed(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host?.takeIf { it.isNotEmpty() } ?: return false
        return isPrivateHost(host)
    }

    /**
     * A literal address on the local network, or localhost.
     *
     * Matched on the TEXT of the host rather than by resolving it, deliberately:
     * resolving first would let a name that points at a private address pass
     * this check and a name that points anywhere else be fetched anyway if DNS
     * answered differently the second time. A Sonos art URL is always a literal
     * IP, so requiring one costs nothing.
     */
    internal fun isPrivateHost(host: String): Boolean {
        val h = host.trim().trim('[', ']').lowercase()
        if (h == "localhost" || h == "::1") return true
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
