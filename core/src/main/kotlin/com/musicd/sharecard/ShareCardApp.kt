package com.musicd.sharecard

import com.musicd.sharecard.api.ArtProxy
import com.musicd.sharecard.api.Assets
import com.musicd.sharecard.api.CardApi
import com.musicd.sharecard.http.HttpServer
import com.musicd.sharecard.lms.LmsClient
import com.musicd.sharecard.lms.LmsSource
import com.musicd.sharecard.meta.Metadata
import com.musicd.sharecard.meta.CacheStore
import com.musicd.sharecard.meta.Pitchfork
import com.musicd.sharecard.meta.QobuzAlbum
import com.musicd.sharecard.meta.Similar
import com.musicd.sharecard.meta.Updater
import com.musicd.sharecard.meta.metadataHttpClient
import com.musicd.sharecard.roon.RoonClient
import com.musicd.sharecard.roon.RoonSource
import com.musicd.sharecard.roon.TokenStore
import com.musicd.sharecard.discover.PlayHistory
import com.musicd.sharecard.settings.SettingsStore
import com.musicd.sharecard.sonos.Household
import com.musicd.sharecard.sonos.SoapClient
import com.musicd.sharecard.sonos.SonosPlayer
import com.musicd.sharecard.sonos.SonosSource
import com.musicd.sharecard.sonos.soapHttpClient
import com.musicd.sharecard.source.Sources
import com.musicd.sharecard.upnp.UpnpSource
import com.musicd.sharecard.webhook.DiscordPoster
import com.musicd.sharecard.webhook.WebhookStore
import com.musicd.sharecard.webhook.webhookHttpClient
import java.io.File
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
    private val hostNotes: () -> List<String> = { emptyList() },
    /** Where the Roon pairing token is kept. Never reachable from a route. */
    tokenStore: TokenStore = TokenStore.NONE,
    /** Android needs a MulticastLock for Roon's SOOD replies to arrive. */
    roonMulticastLock: RoonClient.MulticastLock = RoonClient.MulticastLock.NONE,
    /**
     * The sources to use instead of the real three.
     *
     * Only a test passes this. Without it, constructing this class starts
     * Roon discovery and an SSDP sweep, so a test of the HTTP layer would be a
     * test of whatever happens to be on the network running it.
     */
    sourcesOverride: Sources? = null,
    /**
     * Where Discord webhooks are kept. The URLs are credentials, so this never
     * hands one back out — see [com.musicd.sharecard.webhook.Webhook.masked].
     */
    webhookStore: WebhookStore = WebhookStore.inMemory(),
    /**
     * How this host installs an APK, or null where it cannot — a JVM test, for
     * one. With it null the update routes answer "not available here" rather
     * than offering a button that could not work.
     */
    updateInstaller: UpdateInstaller? = null,
    /**
     * Where the metadata lookups are written down so they survive a restart.
     *
     * The device is never switched off; the SERVICE is — a reboot, an update,
     * Android reclaiming memory — and every one of those used to throw away
     * every album the app had ever looked up. Defaults to remembering nothing,
     * which is what the tests and any host with no storage get.
     */
    cacheStore: CacheStore = CacheStore.NONE,
    /**
     * What the household has switched on: which streaming services are linked,
     * and which rooms this app may answer about.
     *
     * Defaults to remembering nothing, which is not a neutral default and is
     * meant not to be: with no zones enabled the app finds the house and shows
     * none of it, pointing at Settings. That is the opt-in rule, and a host
     * that passes no store gets it too rather than quietly behaving differently
     * from the real thing.
     */
    settingsStore: SettingsStore = SettingsStore.inMemory(),
    /**
     * What this app has made a card for, which is what the Discover screen is
     * based on.
     *
     * Remembers nothing by default, so a host that passes no store — and every
     * test — behaves exactly as this class did before it existed. See
     * [com.musicd.sharecard.discover.PlayHistory] for what is kept and what
     * deliberately is not.
     */
    history: PlayHistory = PlayHistory.inMemory(),
    /** See [com.musicd.sharecard.api.Access]. The container may turn this off. */
    requirePin: Boolean = true
) {

    /**
     * What a host supplies so [updater] can finish the job.
     *
     * [variant] is which half of the manifest to read and which rules apply.
     * The Android shell hands an APK to the system installer; the container
     * unpacks a build beside its own and restarts into it — see
     * [com.musicd.sharecard.meta.ServerRelease].
     */
    class UpdateInstaller(
        val downloadDir: File,
        val install: (File, String) -> Unit,
        val variant: Updater.Variant = Updater.Variant.ANDROID
    )

    private val soap = SoapClient(soapHttpClient())
    private val metaHttp = metadataHttpClient()

    /** A device on the LAN answers quickly or not at all — the SOAP profile fits. */
    private val lmsHttp = soapHttpClient()

    val household = Household(
        playerAt = { ip -> SonosPlayer(ip, soap) },
        seedHosts = seedHosts
    )

    val roon = RoonClient(
        store = tokenStore,
        extension = RoonClient.ExtensionInfo(
            id = "com.musicd.sharecard",
            displayName = "MusicD Share Card",
            version = version,
            publisher = "Music Duck",
            email = "noreply@example.com",
            website = "https://github.com/meltface-80/MusicD-Share-Card"
        ),
        multicastLock = roonMulticastLock
    )

    /**
     * ROON FIRST, AND THE ORDER IS THE POINT.
     *
     * When Roon plays to a Sonos speaker, the speaker sees a stream with a
     * session id where the title should be. Both sources can see that zone;
     * only one of them knows what the record is.
     */
    val sources = sourcesOverride ?: Sources(
        listOf(
            RoonSource(roon),
            // LYRION SITS WITH ROON, ABOVE THE SPEAKERS, for exactly Roon's
            // reason: it owns the library and resolves the metadata, while a
            // squeezelite endpoint — or a UPnP renderer it is streaming to —
            // sees only a stream. Asked about the same room, the server is the
            // one that knows what the record is.
            LmsSource(LmsClient(lmsHttp), hosts = { seedHosts }),
            SonosSource(household),
            UpnpSource(soap, metaHttp)
        )
    )

    private val metadata = Metadata(metaHttp, userAgent(version), cacheStore)
    private val pitchfork = Pitchfork(metaHttp, userAgent(version), Pitchfork.HOST, cacheStore)

    /**
     * The Qobuz album id behind the "Open in Qobuz" link. A search URL can
     * never open that app on the record — see [QobuzAlbum] for why only an id
     * will do.
     */
    private val qobuz = QobuzAlbum(metaHttp, userAgent(version), cacheStore)

    /**
     * Acts to hear next. Keyless on both hosts it asks — see [Similar] for why
     * the first of them is allowed to be the fragile one.
     */
    private val similar = Similar(
        metaHttp, userAgent(version),
        store = cacheStore
    )
    // The art proxy is told which players exist so a LAN fetch is limited to
    // them rather than to "anything that looks local".
    private val art = ArtProxy(metaHttp) { sources.artHosts() }

    /**
     * The manifest CI writes beside the APK on the default branch.
     *
     * THE DEFAULT BRANCH AND NOT THIS ONE. Every branch build publishes its own
     * dist/, so pointing this at whatever branch happened to build would have
     * the app offering itself a feature branch. Updates come from main, which
     * is also where the README's download link points.
     */
    val updater: Updater? = updateInstaller?.let {
        Updater(
            http = metaHttp,
            currentVersion = version,
            manifestUrl = UPDATE_MANIFEST_URL,
            downloadDir = it.downloadDir,
            install = it.install,
            variant = it.variant
        )
    }

    /**
     * Putting a suggestion at the end of a Roon zone's queue.
     *
     * The one thing this app does that is not a read, and it is narrow on
     * purpose: one action, on a card that already came from Roon, behind the
     * configuration gate. See [com.musicd.sharecard.roon.RoonBrowse].
     */
    private val roonBrowse = com.musicd.sharecard.roon.RoonBrowse { roon.browseSocket() }

    /**
     * What is new, and which of it this house has a reason to care about.
     *
     * See [com.musicd.sharecard.discover.NewMusic]: two public endpoints, one
     * request each, and the filtering against [history] happens here rather
     * than as a request per act.
     */
    private val editorial =
        com.musicd.sharecard.discover.Editorial(metaHttp, userAgent(version))

    private val newMusic = com.musicd.sharecard.discover.NewMusic(
        metaHttp, userAgent(version), history, editorial, store = cacheStore
    )

    private val api = CardApi(
        sources, metadata, pitchfork, art, assets, version, hostNotes,
        webhookStore, DiscordPoster(webhookHttpClient()), updater, qobuz, similar,
        settingsStore, requirePin, roonBrowse, history, newMusic, editorial
    )

    private val server = HttpServer(api, port, bindAddress)

    /** The URL the app's own WebView loads. Always loopback. */
    val rootUrl: String get() = server.rootUrl

    val port: Int get() = server.port

    fun start() {
        sources.start()
        server.start()
        Log.i(TAG, "serving on $rootUrl (and ${lanUrls().joinToString()})")
    }

    fun stop() {
        server.stop()
        sources.stop()
    }

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
         * The Android build serves here instead, so the two can coexist.
         *
         * Both shells answered on 8747, which is fine right up until somebody
         * runs both — and running both is the ordinary case while a phone in a
         * dock and a box in a cupboard are being compared. Same port on two
         * addresses is not a clash the OS will report; it is a bookmark that
         * silently starts answering for the wrong one, and two devices that
         * cannot be told apart by their URL.
         */
        const val ANDROID_PORT = 8748

        /** Published by CI beside the APK it describes. */
        const val UPDATE_MANIFEST_URL =
            "https://raw.githubusercontent.com/meltface-80/MusicD-Share-Card/main/dist/latest.json"

        /**
         * MusicBrainz requires a contactable User-Agent and blocks clients that
         * send a generic one. Wikipedia and Pitchfork are less strict and get
         * the same string.
         */
        fun userAgent(version: String): String =
            "MusicDShareCard/$version ( https://github.com/meltface-80/MusicD-Share-Card )"
    }
}
