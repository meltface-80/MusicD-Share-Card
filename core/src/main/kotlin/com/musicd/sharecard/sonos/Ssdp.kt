package com.musicd.sharecard.sonos

import com.musicd.sharecard.Log
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URI

/**
 * Finding a Sonos player by multicast.
 *
 * Only ONE has to be found. A single reachable player describes the entire
 * household through ZoneGroupTopology — including rooms whose own replies were
 * dropped — so this is a best-effort sweep rather than an inventory, and a
 * partial answer is a complete success.
 *
 * SENT FROM EVERY INTERFACE, ONE SOCKET EACH. This used to be a single socket
 * bound to the wildcard address, letting the kernel choose where the datagram
 * went — and on Android that is a well-known way to get nothing back at all.
 * A phone or a streamer commonly has several interfaces up at once (wifi,
 * ethernet, a VPN, `dummy0`), the default route is not necessarily the one the
 * speakers are on, and a multicast send follows the route rather than the
 * network the app cares about. Binding per interface and sending from each is
 * the only way to be sure the probe leaves on the one with the music on it.
 *
 * ANDROID ALSO DROPS INBOUND MULTICAST unless a MulticastLock is held. The
 * replies to an M-SEARCH are unicast, so the lock is not strictly needed for
 * this — but it is held anyway (see CardService), because it costs nothing and
 * the failure it prevents is silent.
 *
 * Even with all of that, multicast is filtered outright by plenty of mesh
 * systems, guest VLANs and switches with client isolation. [SonosScan] is the
 * fallback for those networks, and it needs no multicast whatsoever.
 */
object Ssdp {

    private const val TAG = "Ssdp"
    const val ADDRESS = "239.255.255.250"
    const val PORT = 1900

    /** What a sweep saw, kept so the diagnostics page can show its working. */
    data class Result(
        val hosts: List<String>,
        /** One line per interface tried, and what it managed. */
        val notes: List<String>,
        /**
         * The full LOCATION URLs, not just the hosts.
         *
         * Sonos publishes its control paths at fixed addresses, so a host was
         * all that was needed for it. A standard DLNA renderer does not: its
         * description document can be on any port at any path, and LOCATION is
         * the only thing that says where. Throwing that away and guessing ports
         * was how the first version of the UPnP source was written, and it
         * would have found almost nothing.
         */
        val locations: List<String> = emptyList()
    )

    fun discover(
        searchTarget: String = Sonos.ZONE_PLAYER_ST,
        mx: Int = 2,
        attempts: Int = 2,
        timeoutMs: Int = 3000
    ): List<String> = sweep(searchTarget, mx, attempts, timeoutMs).hosts

    /** The full description-document URLs, which a DLNA renderer needs. */
    fun discoverLocations(
        searchTarget: String,
        timeoutMs: Int = 3000
    ): List<String> = sweep(searchTarget, timeoutMs = timeoutMs).locations

    /** As [discover], but reporting what each interface did. */
    fun sweep(
        searchTarget: String = Sonos.ZONE_PLAYER_ST,
        mx: Int = 2,
        attempts: Int = 2,
        timeoutMs: Int = 3000
    ): Result {
        val message = (
            "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: $ADDRESS:$PORT\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: $mx\r\n" +
                "ST: $searchTarget\r\n" +
                "\r\n"
            ).toByteArray(Charsets.US_ASCII)

        val found = LinkedHashSet<String>()
        val locations = LinkedHashSet<String>()
        val notes = ArrayList<String>()

        val interfaces = usableInterfaces()
        if (interfaces.isEmpty()) notes += "no usable network interface is up"

        // Every interface gets its own socket and its own listen window. One
        // that refuses to send must not stop the others being tried.
        for (nic in interfaces) {
            val before = found.size
            val note = probe(nic, message, attempts, timeoutMs, found, locations)
            notes += "${nic.name}: $note, ${found.size - before} new"
        }

        if (found.isEmpty()) {
            Log.w(TAG, "no player answered SSDP on any interface: $notes")
        } else {
            Log.i(TAG, "SSDP found players at $found")
        }
        return Result(found.toList(), notes, locations.toList())
    }

    /**
     * The interfaces worth probing: up, not loopback, and carrying an IPv4
     * address. Sonos is IPv4-only, so an interface with no IPv4 address on it
     * cannot reach a speaker whatever else is true of it.
     */
    private fun usableInterfaces(): List<NetworkInterface> = try {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter {
                runCatching {
                    it.isUp && !it.isLoopback && it.supportsMulticast() &&
                        it.inetAddresses.asSequence().any { a -> a is Inet4Address }
                }.getOrDefault(false)
            }
            .toList()
    } catch (e: Exception) {
        Log.w(TAG, "could not list network interfaces: ${e.message}", e)
        emptyList()
    }

    private fun probe(
        nic: NetworkInterface,
        message: ByteArray,
        attempts: Int,
        timeoutMs: Int,
        into: MutableSet<String>,
        locations: MutableSet<String> = LinkedHashSet()
    ): String = try {
        MulticastSocket().use { socket ->
            socket.soTimeout = 300
            socket.timeToLive = 4
            // The line this whole class turns on: send from THIS interface
            // rather than wherever the routing table would have gone.
            runCatching { socket.networkInterface = nic }
                .onFailure { return "cannot bind (${it.message})" }

            val group = InetSocketAddress(InetAddress.getByName(ADDRESS), PORT)
            val deadline = System.currentTimeMillis() + timeoutMs

            repeat(attempts) { attempt ->
                try {
                    socket.send(DatagramPacket(message, message.size, group))
                } catch (e: Exception) {
                    return "send failed (${e.message})"
                }
                // Listen between sends, not only after the last one, so a
                // player that answers the first probe is recorded either way.
                val slice = if (attempt + 1 < attempts) 400L else timeoutMs.toLong()
                collectUntil(
                    socket, minOf(deadline, System.currentTimeMillis() + slice), into, locations
                )
            }
            "probed"
        }
    } catch (e: Exception) {
        "failed (${e.message})"
    }

    private fun collectUntil(
        socket: MulticastSocket,
        until: Long,
        into: MutableSet<String>,
        locations: MutableSet<String> = LinkedHashSet()
    ) {
        val buffer = ByteArray(2048)
        while (System.currentTimeMillis() < until) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (e: SocketTimeoutException) {
                continue
            } catch (e: Exception) {
                Log.w(TAG, "receive failed: ${e.message}")
                return
            }
            val reply = String(packet.data, 0, packet.length, Charsets.US_ASCII)
            if (!isSearchResponse(reply)) continue
            // The LOCATION header names the player's own address; the packet's
            // source address is the fallback for a reply that omits it.
            val location = headerOf(reply, "location")
            if (!location.isNullOrBlank()) locations += location.trim()
            val host = hostOfLocation(location) ?: packet.address?.hostAddress
            if (!host.isNullOrEmpty()) into += host
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
