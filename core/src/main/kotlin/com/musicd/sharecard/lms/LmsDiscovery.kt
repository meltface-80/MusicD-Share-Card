package com.musicd.sharecard.lms

import com.musicd.sharecard.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Finding a Lyrion server the way its own players do.
 *
 * Lyrion listens on UDP 3483 and answers a broadcast with a handful of
 * tag/value pairs. The request is the byte `e` followed by the tags being
 * asked for, each a four-character name and a length of zero; the reply is `E`
 * followed by the same names, each with a real length and value.
 *
 *     ->  'e' "NAME" 00 "JSON" 00 "VERS" 00
 *     <-  'E' "NAME" 07 "attic" … "JSON" 04 "9000" …
 *
 * `JSON` is the one that matters: it carries the port the web interface and the
 * JSON-RPC endpoint are on, so a server moved off 9000 is still found. The rest
 * is for the diagnostics line.
 *
 * THIS IS A BROADCAST, so it is the first thing to fail on a mesh network, a
 * guest VLAN, a switch with client isolation, or a Docker bridge — exactly the
 * cases [com.musicd.sharecard.SeedHosts] exists for. A configured address skips
 * all of this, and [LmsSource] tries both.
 *
 * UNVERIFIED ON THE WIRE. The network here reaches no Lyrion server, so the
 * framing above is written from the documented protocol and not from a captured
 * packet. [parseReply] is therefore separated out and tested against bytes
 * assembled by hand: if the real framing differs, that is one function to
 * correct rather than a socket to debug at a distance.
 */
object LmsDiscovery {

    /** A server that answered, and where to talk to it. */
    data class Server(val host: String, val port: Int, val name: String = "")

    const val PORT = 3483

    /**
     * Broadcast, and collect whatever answers before the timeout.
     *
     * Every answer is kept rather than the first, because a house can run more
     * than one server and picking whichever replied fastest would make the
     * choice depend on the weather.
     */
    fun discover(timeoutMs: Int = 1200): List<Server> {
        val found = LinkedHashMap<String, Server>()
        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = timeoutMs
                val request = request()
                socket.send(
                    DatagramPacket(
                        request, request.size,
                        InetSocketAddress(InetAddress.getByName("255.255.255.255"), PORT)
                    )
                )
                val deadline = System.currentTimeMillis() + timeoutMs
                val buffer = ByteArray(1500)
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (e: Throwable) {
                        break
                    }
                    val host = packet.address?.hostAddress ?: continue
                    val fields = parseReply(packet.data.copyOf(packet.length))
                    if (fields.isEmpty()) continue
                    found[host] = Server(
                        host = host,
                        port = fields["JSON"]?.toIntOrNull() ?: LmsClient.DEFAULT_PORT,
                        name = fields["NAME"].orEmpty()
                    )
                }
            }
        } catch (e: Throwable) {
            // A device with no network, a blocked socket, a VPN that refuses
            // broadcast — none of those may end the request that asked.
            Log.w(TAG, "broadcast failed: ${e.message}")
        }
        return found.values.toList()
    }

    /** `'e'` and the tags wanted, each with a zero length. */
    internal fun request(): ByteArray {
        val out = ArrayList<Byte>()
        out += 'e'.code.toByte()
        for (tag in WANTED) {
            for (c in tag) out += c.code.toByte()
            out += 0
        }
        return out.toByteArray()
    }

    /**
     * The tag/value pairs out of a reply, or empty if it is not one.
     *
     * Deliberately forgiving: a tag whose declared length runs off the end of
     * the packet ends the walk and keeps what was read, rather than throwing
     * away the fields that did parse. A truncated NAME is worth less than the
     * JSON port beside it, and losing both to be strict helps nobody.
     */
    internal fun parseReply(packet: ByteArray): Map<String, String> {
        if (packet.isEmpty() || packet[0] != 'E'.code.toByte()) return emptyMap()
        val fields = LinkedHashMap<String, String>()
        var i = 1
        while (i + TAG_LENGTH < packet.size) {
            val tag = String(packet, i, TAG_LENGTH, Charsets.US_ASCII)
            i += TAG_LENGTH
            val length = packet[i].toInt() and 0xFF
            i += 1
            if (i + length > packet.size) break
            fields[tag] = String(packet, i, length, Charsets.US_ASCII).trim()
            i += length
        }
        return fields
    }

    private const val TAG = "Lms"
    private const val TAG_LENGTH = 4

    /** NAME for the diagnostics line, JSON for the port, VERS to prove it is Lyrion. */
    private val WANTED = listOf("NAME", "JSON", "VERS")
}
