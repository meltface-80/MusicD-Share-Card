package com.musicd.sharecard

import com.musicd.sharecard.api.ArtProxy
import com.musicd.sharecard.api.Assets
import com.musicd.sharecard.api.CardApi
import com.musicd.sharecard.http.HttpServer
import com.musicd.sharecard.meta.Metadata
import com.musicd.sharecard.meta.Pitchfork
import com.musicd.sharecard.meta.metadataHttpClient
import com.musicd.sharecard.sonos.Household
import com.musicd.sharecard.sonos.SoapClient
import com.musicd.sharecard.sonos.SonosPlayer
import com.musicd.sharecard.sonos.soapHttpClient
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * The whole app, assembled.
 *
 * Everything that is not Android lives here so that the Sonos protocol layer,
 * the metadata lookups and the API are testable on a plain JVM with no
 * emulator. The Android module is the shell: a WebView, a foreground service,
 * and the share sheet.
 */
class ShareCardApp(
    assets: Assets,
    /**
     * Player addresses to try before searching. Plenty of mesh routers and
     * guest VLANs drop multicast, and on those networks an address typed in by
     * hand is the difference between this app working and not.
     */
    seedHosts: List<String> = emptyList(),
    port: Int = DEFAULT_PORT,
    bindAddress: String = HttpServer.ANY,
    val version: String = "dev",
    /**
     * Anything the Android shell knows that the diagnostics page should show —
     * chiefly the last recorded crash. Supplied as a function because it is read
     * when somebody asks, not when the app starts.
     */
    private val hostNotes: () -> List<String> = { emptyList() }
) {

    private val soap = SoapClient(soapHttpClient())
    private val metaHttp = metadataHttpClient()

    val household = Household(
        playerAt = { ip -> SonosPlayer(ip, soap) },
        seedHosts = seedHosts
    )

    private val metadata = Metadata(metaHttp, userAgent(version))
    private val pitchfork = Pitchfork(metaHttp, userAgent(version))
    private val art = ArtProxy(soapHttpClient())

    private val api = CardApi(household, metadata, pitchfork, art, assets, version, hostNotes)

    private val server = HttpServer(api, port, bindAddress)

    /** The URL the app's own WebView loads. Always loopback. */
    val rootUrl: String get() = server.rootUrl

    val port: Int get() = server.port

    fun start() {
        server.start()
        Log.i(TAG, "serving on $rootUrl (and ${lanUrls().joinToString()})")
    }

    fun stop() = server.stop()

    /**
     * The addresses another device can reach this on.
     *
     * Shown in the app so somebody can type one into a phone without going
     * looking in the router. Loopback and anything not on this network are
     * left out, because a URL that only works on the device displaying it is
     * worse than no URL.
     */
    fun lanUrls(): List<String> = lanAddresses().map { "http://$it:${server.port}" }

    private fun lanAddresses(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            .mapNotNull { it.hostAddress }
            .distinct()
            .toList()
    } catch (e: Exception) {
        Log.w(TAG, "could not read this device's addresses: ${e.message}")
        emptyList()
    }

    companion object {
        private const val TAG = "ShareCardApp"

        /**
         * A fixed port, unlike the app this was ported from, and for a reason
         * that only applies here: the URL is meant to be TYPED into another
         * device's browser, and a port the OS picked fresh on every start would
         * mean a different URL after every reboot. 1400 is Sonos's own and
         * 8080 is everybody's; this is neither.
         *
         * [HttpServer] still falls back to an OS-assigned port if this one will
         * not bind, because a card server on an unmemorable port beats one that
         * would not start.
         */
        const val DEFAULT_PORT = 8747

        /**
         * MusicBrainz requires a contactable User-Agent and blocks clients that
         * send a generic one. Wikipedia and Pitchfork are less strict and get
         * the same string.
         */
        fun userAgent(version: String): String =
            "MusicDShareCard/$version ( https://github.com/meltface-80/New )"
    }
}
