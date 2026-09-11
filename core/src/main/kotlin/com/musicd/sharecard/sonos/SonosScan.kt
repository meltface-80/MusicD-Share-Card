package com.musicd.sharecard.sonos

import com.musicd.sharecard.Log
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Finding a Sonos player WITHOUT multicast, by knocking on port 1400.
 *
 * WHY THIS EXISTS. SSDP is multicast, and a great many home networks quietly
 * refuse to carry it: mesh systems that bridge wifi to ethernet, guest VLANs,
 * switches with client isolation, and Android builds that drop it before it
 * reaches userspace. On those networks [Ssdp] returns nothing however carefully
 * it is written, the app reports "No Sonos players found", and the user is left
 * with a message that describes the symptom and names no cause.
 *
 * Every Sonos player listens on TCP 1400. The device already knows its own
 * address and prefix length, so the set of addresses a speaker could have is
 * small and completely enumerable — a /24 is 254 of them. Knocking on each is
 * unglamorous and it is reliable in exactly the cases multicast is not.
 *
 * THIS IS A LAST RESORT, NOT THE DEFAULT. It only runs when SSDP has come back
 * empty, and only across the device's OWN subnets — never a range supplied from
 * outside, which would make this app a port scanner for anybody who can reach
 * it. Prefixes shorter than /22 are skipped: a /16 is 65,000 connects, which is
 * not a discovery strategy.
 */
object SonosScan {

    private const val TAG = "SonosScan"

    /** How long to wait for a speaker to accept. A LAN answers in single-digit ms. */
    private const val CONNECT_TIMEOUT_MS = 350

    /** Enough to cover a /24 in a few rounds without opening 254 sockets at once. */
    private const val THREADS = 48

    /** Shorter than this and the scan is bigger than a home network. */
    private const val MIN_PREFIX = 22

    private const val MAX_HOSTS = 1024

    data class Result(val hosts: List<String>, val notes: List<String>)

    /**
     * Scan the device's own subnets for anything listening on 1400.
     *
     * A hit is not proof it is a Sonos — the caller asks it for the household
     * topology next, and something that is not a player simply fails to answer
     * that. Verifying here as well would be a second round trip to be equally
     * sure.
     */
    fun scan(timeoutMs: Long = 8000): Result {
        val notes = ArrayList<String>()
        val targets = LinkedHashSet<String>()

        for (subnet in localSubnets()) {
            val addresses = subnet.addresses()
            notes += "${subnet.describe()}: ${addresses.size} addresses"
            targets += addresses
        }
        if (targets.isEmpty()) {
            notes += "no scannable IPv4 subnet on this device"
            return Result(emptyList(), notes)
        }

        val found = java.util.Collections.synchronizedList(ArrayList<String>())
        val pool = Executors.newFixedThreadPool(THREADS) { r ->
            Thread(r, "sonos-scan").apply { isDaemon = true }
        }
        try {
            for (host in targets) {
                pool.execute {
                    if (listening(host)) {
                        found += host
                        Log.i(TAG, "something is listening on $host:${Sonos.PORT}")
                    }
                }
            }
            pool.shutdown()
            if (!pool.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)) {
                notes += "scan hit its ${timeoutMs}ms budget before finishing"
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            notes += "scan interrupted"
        } finally {
            pool.shutdownNow()
        }

        val hosts = synchronized(found) { found.sorted() }
        notes += "found ${hosts.size} host(s) on port ${Sonos.PORT}"
        return Result(hosts, notes)
    }

    private fun listening(host: String): Boolean = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, Sonos.PORT), CONNECT_TIMEOUT_MS)
            true
        }
    } catch (e: Exception) {
        false
    }

    /** One of this device's IPv4 networks. */
    data class Subnet(val address: Inet4Address, val prefix: Short, val nic: String) {

        fun describe(): String = "$nic ${address.hostAddress}/$prefix"

        /**
         * Every host address in this subnet except our own, network and
         * broadcast.
         */
        fun addresses(): List<String> {
            if (prefix < MIN_PREFIX || prefix > 30) return emptyList()
            val self = address.address.foldIndexed(0L) { i, acc, b ->
                acc or ((b.toLong() and 0xFF) shl ((3 - i) * 8))
            }
            val mask = (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
            val network = self and mask
            val broadcast = network or mask.inv().and(0xFFFFFFFFL)
            val count = (broadcast - network - 1)
            if (count <= 0 || count > MAX_HOSTS) return emptyList()
            val out = ArrayList<String>(count.toInt())
            var ip = network + 1
            while (ip < broadcast) {
                if (ip != self) {
                    out += "${(ip shr 24) and 0xFF}.${(ip shr 16) and 0xFF}." +
                        "${(ip shr 8) and 0xFF}.${ip and 0xFF}"
                }
                ip++
            }
            return out
        }
    }

    fun localSubnets(): List<Subnet> = try {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { nic ->
                nic.interfaceAddresses.asSequence().mapNotNull { ia ->
                    val a = ia.address
                    if (a is Inet4Address && !a.isLoopbackAddress && !a.isLinkLocalAddress) {
                        Subnet(a, ia.networkPrefixLength, nic.name)
                    } else {
                        null
                    }
                }
            }
            .toList()
    } catch (e: Exception) {
        Log.w(TAG, "could not list local subnets: ${e.message}", e)
        emptyList()
    }
}
