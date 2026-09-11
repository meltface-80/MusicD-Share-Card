package com.musicd.sharecard.sonos

import com.musicd.sharecard.Log
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import java.net.URI

/**
 * Finding a Sonos player on the network.
 *
 * Only ONE has to be found. A single reachable player can describe the entire
 * household through ZoneGroupTopology — including rooms whose own SSDP replies
 * were dropped — so this is a best-effort sweep rather than an inventory, and
 * a partial answer is a complete success.
 *
 * ANDROID DROPS MULTICAST BEFORE IT REACHES USERSPACE unless a MulticastLock is
 * held. That lock is the Android module's to take (see CardService); without it
 * this returns nothing at all on a phone, on a network full of players, with no
 * error to explain it.
 */
object Ssdp {

    private const val TAG = "Ssdp"
    const val ADDRESS = "239.255.255.250"
    const val PORT = 1900

    /**
     * Send M-SEARCH and collect replies for a little longer than MX.
     *
     * [mx] is what the spec asks responders to spread their replies over, so
     * the listen window has to exceed it or the slowest player is cut off.
     * Sent more than once because a dropped datagram is normal on wifi and
     * there is no retransmission underneath us.
     */
    fun discover(
        searchTarget: String = Sonos.ZONE_PLAYER_ST,
        mx: Int = 2,
        attempts: Int = 3,
        timeoutMs: Int = 4000
    ): List<String> {
        val message = (
            "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: $ADDRESS:$PORT\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: $mx\r\n" +
                "ST: $searchTarget\r\n" +
                "\r\n"
            ).toByteArray(Charsets.US_ASCII)

        val found = LinkedHashSet<String>()
        try {
            // Bound to an ephemeral port rather than 1900: binding the SSDP
            // port itself needs it to be free, and on Android it very often is
            // not. Replies to an M-SEARCH are unicast back to the source port,
            // so a random one is all this needs.
            MulticastSocket().use { socket ->
                socket.soTimeout = 400
                socket.timeToLive = 4
                val group = InetSocketAddress(InetAddress.getByName(ADDRESS), PORT)
                val deadline = System.currentTimeMillis() + timeoutMs

                repeat(attempts) { attempt ->
                    runCatching {
                        socket.send(DatagramPacket(message, message.size, group))
                    }.onFailure {
                        Log.w(TAG, "could not send M-SEARCH: ${it.message}")
                        return@repeat
                    }
                    // Listen between sends rather than only after the last one,
                    // so a player that answers the first probe is recorded even
                    // if the socket would otherwise be busy sending.
                    val until = minOf(
                        deadline,
                        System.currentTimeMillis() + if (attempt + 1 < attempts) 500 else timeoutMs
                    )
                    collectUntil(socket, until, found)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "discovery failed: ${e.message}")
        }

        if (found.isEmpty()) {
            Log.i(
                TAG,
                "no Sonos player answered SSDP — check wifi, and that the app holds " +
                    "a MulticastLock"
            )
        } else {
            Log.i(TAG, "found players at $found")
        }
        return found.toList()
    }

    private fun collectUntil(socket: MulticastSocket, until: Long, into: MutableSet<String>) {
        val buffer = ByteArray(2048)
        while (System.currentTimeMillis() < until) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (e: SocketTimeoutException) {
                continue
            } catch (e: Exception) {
                Log.d(TAG, "receive failed: ${e.message}")
                return
            }
            val reply = String(packet.data, 0, packet.length, Charsets.US_ASCII)
            // The LOCATION header names the player's own address; the packet's
            // source address is the fallback for a reply that omits it.
            val host = hostOfLocation(headerOf(reply, "location"))
                ?: packet.address?.hostAddress
            if (!host.isNullOrEmpty() && isSearchResponse(reply)) into += host
        }
    }

    internal fun isSearchResponse(reply: String): Boolean =
        reply.lineSequence().firstOrNull()?.trim()?.uppercase()?.startsWith("HTTP/1.1 200") == true

    /** A header value from an SSDP reply, matched case-insensitively. */
    internal fun headerOf(reply: String, name: String): String? {
        val wanted = name.lowercase()
        for (line in reply.lineSequence()) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            if (line.substring(0, colon).trim().lowercase() == wanted) {
                return line.substring(colon + 1).trim()
            }
        }
        return null
    }

    internal fun hostOfLocation(location: String?): String? {
        if (location.isNullOrBlank()) return null
        return runCatching { URI(location).host }.getOrNull()?.takeIf { it.isNotEmpty() }
    }
}
