package com.musicd.sharecard.upnp

import com.musicd.sharecard.Log
import com.musicd.sharecard.sonos.Didl
import com.musicd.sharecard.sonos.SoapClient
import com.musicd.sharecard.sonos.Ssdp
import com.musicd.sharecard.sonos.TransportState
import com.musicd.sharecard.sonos.Xml
import com.musicd.sharecard.source.PlayState
import com.musicd.sharecard.source.Playing
import com.musicd.sharecard.source.StreamHosts
import com.musicd.sharecard.source.Source
import com.musicd.sharecard.source.ZoneRef
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI

/**
 * Any DLNA / UPnP MediaRenderer on the network.
 *
 * Sonos is a MediaRenderer with private extensions — its own port, its own
 * control paths, its own topology service. Strip those away and what is left is
 * the standard conversation every DLNA renderer speaks, which is the same
 * AVTransport and the same DIDL-Lite this app already parses.
 *
 * ONE REAL DIFFERENCE, AND IT IS THE WHOLE OF THIS CLASS. Sonos publishes its
 * control URLs at fixed paths, so they can be constants. A standard renderer
 * does not: its device description document names them, and they differ per
 * manufacturer. So discovery here is two steps — SSDP finds the description,
 * the description names the AVTransport control URL — where for Sonos it was
 * one.
 *
 * Sonos players answer MediaRenderer searches too, and are skipped: [SonosSource]
 * reads them better, and a household appearing twice in the zone picker is
 * worse than one that appears once.
 */
class UpnpSource(
    private val soap: SoapClient,
    private val http: OkHttpClient,
    /** Full LOCATION URLs, because a renderer's description is not at a fixed path. */
    private val discover: (String) -> List<String> = { st -> Ssdp.discoverLocations(st) }
) : Source {

    override val name: String = NAME

    /** One renderer: where to talk to it, and what to call it. */
    data class Renderer(
        val udn: String,
        val friendlyName: String,
        val host: String,
        val controlUrl: String
    )

    @Volatile
    private var renderers: List<Renderer> = emptyList()

    /** Whoever is streaming into these renderers — same reason as Sonos. */
    private val streamHosts = StreamHosts()

    @Volatile
    private var notes: List<String> = emptyList()

    @Volatile
    private var scannedAt: Long = 0

    override fun refresh() {
        scannedAt = 0
        scan()
    }

    @Synchronized
    private fun scan() {
        if (System.currentTimeMillis() - scannedAt < RESCAN_MS && renderers.isNotEmpty()) return
        val found = ArrayList<Renderer>()
        val lines = ArrayList<String>()

        val locations = runCatching { discover(MEDIA_RENDERER_ST) }
            .onFailure { lines += "search failed: ${it.message}" }
            .getOrDefault(emptyList())
            .distinct()
        lines += "SSDP found ${locations.size} renderer description(s)"

        for (location in locations) {
            val description = describe(location)
            if (description == null) {
                lines += "$location: no usable device description"
                continue
            }
            if (description.isSonos) {
                // Read better by SonosSource, which understands grouping.
                lines += "${description.renderer.host}: Sonos, left to the Sonos source"
                continue
            }
            if (found.none { it.udn == description.renderer.udn }) found += description.renderer
            lines += "${description.renderer.host}: ${description.renderer.friendlyName}"
        }

        renderers = found
        notes = lines
        scannedAt = System.currentTimeMillis()
    }

    internal class Description(val renderer: Renderer, val isSonos: Boolean)

    /**
     * Read a renderer's description and find its AVTransport control URL.
     *
     * [location] is the URL straight out of the SSDP reply's LOCATION header,
     * which is the only thing that says where a renderer publishes this — the
     * port and the path are both the manufacturer's choice.
     */
    internal fun describe(location: String): Description? {
        val xml = fetch(location) ?: return null
        return parseDescription(xml, location)
    }

    private fun fetch(url: String): String? = try {
        http.newCall(Request.Builder().url(url).header("Connection", "close").build())
            .execute().use { response ->
                if (response.isSuccessful) response.body?.string() else null
            }
    } catch (e: Exception) {
        null
    }

    /**
     * Pull the friendly name and the AVTransport control URL out of a device
     * description. Relative control URLs are resolved against the document's
     * own address, which is what the spec says and what renderers rely on.
     */
    internal fun parseDescription(xml: String, documentUrl: String): Description? {
        val root = Xml.parse(xml) ?: return null
        val friendly = Xml.find(root, "friendlyName")?.let(Xml::text).orEmpty()
        val udn = Xml.find(root, "UDN")?.let(Xml::text).orEmpty().removePrefix("uuid:")
        val manufacturer = Xml.find(root, "manufacturer")?.let(Xml::text).orEmpty()
        val modelName = Xml.find(root, "modelName")?.let(Xml::text).orEmpty()

        // Find the AVTransport service and take ITS controlURL — not the first
        // controlURL in the document, which belongs to whichever service the
        // manufacturer happened to list first.
        var control: String? = null
        for (service in Xml.descendants(root)) {
            if (Xml.localName(service) != "service") continue
            val type = Xml.children(service)
                .firstOrNull { Xml.localName(it) == "serviceType" }?.let(Xml::text).orEmpty()
            if (!type.contains("AVTransport", ignoreCase = true)) continue
            control = Xml.children(service)
                .firstOrNull { Xml.localName(it) == "controlURL" }?.let(Xml::text)
            if (!control.isNullOrEmpty()) break
        }
        val controlUrl = control?.takeIf { it.isNotEmpty() }?.let { resolve(documentUrl, it) }
            ?: return null

        val host = runCatching { URI(documentUrl).host }.getOrNull().orEmpty()
        if (host.isEmpty() || udn.isEmpty()) return null

        return Description(
            Renderer(
                udn = udn,
                friendlyName = friendly.ifEmpty { modelName.ifEmpty { host } },
                host = host,
                controlUrl = controlUrl
            ),
            isSonos = manufacturer.contains("Sonos", ignoreCase = true) ||
                modelName.contains("Sonos", ignoreCase = true)
        )
    }

    internal fun resolve(documentUrl: String, controlUrl: String): String? = try {
        URI(documentUrl).resolve(controlUrl).toString()
    } catch (e: Exception) {
        null
    }

    override fun zones(): List<ZoneRef> {
        scan()
        return renderers.map {
            ZoneRef(
                source = name,
                id = ZoneRef.idFor(name, it.udn),
                name = it.friendlyName,
                rooms = listOf(it.friendlyName)
            )
        }
    }

    override fun nowPlaying(zoneId: String?): Playing? {
        scan()
        val candidates =
            if (zoneId != null) renderers.filter { it.udn == zoneId } else renderers
        var fallback: Playing? = null
        for (renderer in candidates) {
            val playing = read(renderer) ?: continue
            if (playing.state.isPlaying) return playing
            if (fallback == null && !playing.isEmpty) fallback = playing
        }
        return fallback
    }

    private fun read(renderer: Renderer): Playing? = try {
        val transport = TransportState.of(
            soap.call(
                renderer.controlUrl, AV_TRANSPORT, "GetTransportInfo",
                listOf<Pair<String, Any>>("InstanceID" to 0)
            )["CurrentTransportState"].orEmpty()
        )
        val position = soap.call(
            renderer.controlUrl, AV_TRANSPORT, "GetPositionInfo",
            listOf<Pair<String, Any>>("InstanceID" to 0)
        )
        val np = Didl.parse(position["TrackMetaData"].orEmpty())
        // A DLNA renderer is fed by a server on the network exactly as a Sonos
        // is, and that server's art URL is refused for the same reason.
        streamHosts.remember(np.uri)
        Playing(
            source = name,
            zoneId = ZoneRef.idFor(name, renderer.udn),
            zoneName = renderer.friendlyName,
            album = np.displayAlbum,
            artist = np.displayArtist,
            track = if (Didl.looksLikeStreamId(np.track)) "" else np.track,
            state = when (transport) {
                TransportState.PLAYING -> PlayState.PLAYING
                TransportState.PAUSED -> PlayState.PAUSED
                TransportState.STOPPED -> PlayState.STOPPED
                else -> PlayState.UNKNOWN
            },
            artUrl = Didl.absoluteArt(np.artUri, "http://${renderer.host}")
        )
    } catch (e: Throwable) {
        Log.w(TAG, "${renderer.friendlyName} would not answer: ${e.message}")
        null
    }

    override fun artHosts(): Collection<String> =
        renderers.map { it.host } + streamHosts.hosts()

    override fun diagnostics(): List<String> {
        scan()
        return notes + renderers.map { "  ${it.friendlyName} -> ${it.controlUrl}" }
    }

    private companion object {
        const val TAG = "Upnp"
        const val NAME = "UPnP"
        const val MEDIA_RENDERER_ST = "urn:schemas-upnp-org:device:MediaRenderer:1"
        const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"

        const val RESCAN_MS = 60_000L
    }
}
