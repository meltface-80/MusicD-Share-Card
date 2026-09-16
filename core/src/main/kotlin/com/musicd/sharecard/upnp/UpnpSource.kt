package com.musicd.sharecard.upnp

import com.musicd.sharecard.Log
import com.musicd.sharecard.sonos.Didl
import com.musicd.sharecard.sonos.NowPlaying
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
        val controlUrl: String,
        /**
         * EVERY SERVICE THE DESCRIPTION LISTS, AND IT IS A REPORT, NOT A
         * DECISION. Nothing here reads this to choose a code path — it is
         * carried so [diagnostics] can print it, because what a renderer can
         * be ASKED to do cannot be settled from this machine and a device
         * description is the only place it is written down. See the note on
         * [shortService].
         */
        val services: List<Service> = emptyList(),
        /**
         * The verbs a queue service actually publishes, read off its own SCPD.
         *
         * Empty where nothing was asked or nothing answered. Filled only for
         * the services [queueability] names, because a description lists half
         * a dozen and the other four hold no queue.
         */
        val queueActions: Map<String, List<String>> = emptyMap(),
        /**
         * And the ARGUMENTS of the few verbs that would actually add a record.
         *
         * A name says a queue can be appended to; it does not say what to put
         * in one. That is the whole remaining question for UPnP, and the same
         * document already fetched for the names answers it.
         */
        val queueSignatures: Map<String, List<String>> = emptyMap()
    )

    /**
     * One service on a renderer: what it is, where its verbs are written down,
     * and where to send them.
     *
     * `SCPDURL` is the half that makes this worth modelling. Every UPnP
     * service publishes a document enumerating every action and every
     * argument, FROM THE DEVICE ITSELF — so "what can this box be asked" has
     * an authoritative answer that costs one GET, and never needs guessing at
     * from a vendor's documentation or somebody's reverse engineering.
     */
    data class Service(
        val type: String,
        val scpdUrl: String? = null,
        val controlUrl: String? = null
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

    /**
     * AN EMPTY SWEEP IS AN ANSWER AND IS REMEMBERED LIKE ANY OTHER.
     *
     * `renderers.isNotEmpty()` defeated the TTL, so a network with no DLNA
     * renderer on it was searched again on every question — a three-second
     * SSDP window per interface, in the request, for ever. The fallback ladder
     * asks each source and then each room, so one `/api/now-playing` paid it
     * several times over. See Household.refresh for the measurements and for
     * why this is the no-polling rule rather than a tuning choice.
     */
    @Synchronized
    private fun scan() {
        if (System.currentTimeMillis() - scannedAt < RESCAN_MS) return
        scannedAt = System.currentTimeMillis()
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
            if (found.none { it.udn == description.renderer.udn }) {
                found += withQueueActions(description.renderer)
            }
            lines += "${description.renderer.host}: ${description.renderer.friendlyName}"
        }

        renderers = found
        notes = lines
    }

    internal class Description(val renderer: Renderer, val isSonos: Boolean)

    /**
     * ASK THE QUEUE SERVICE WHAT IT CAN BE ASKED. ONE GET, AND ONLY WHERE
     * THERE IS A QUEUE TO ASK ABOUT.
     *
     * The first probe answered "this box advertises wiimu/PlayQueue" and no
     * more, which is one step short of useful: a service NAME does not say
     * whether it can append, only replace, or anything at all. Every UPnP
     * service publishes an SCPD enumerating its actions, from the device
     * itself — so the next question has an authoritative answer rather than a
     * reverse-engineered one, and this app should never be reasoning from a
     * vendor's PDF about a box it can talk to directly.
     *
     * Bounded on purpose: only services [queueability] recognises are asked,
     * so a description listing six costs one or two GETs, inside the same
     * scan the descriptions were already fetched by and behind the same TTL.
     * A service that will not answer costs its own line and nothing else.
     */
    private fun withQueueActions(renderer: Renderer): Renderer {
        val wanted = renderer.services.filter { isQueueService(it.type) && it.scpdUrl != null }
        if (wanted.isEmpty()) return renderer
        val actions = LinkedHashMap<String, List<String>>()
        val signatures = LinkedHashMap<String, List<String>>()
        for (service in wanted) {
            val xml = fetch(service.scpdUrl!!)
            val names = if (xml == null) emptyList() else runCatching { parseActions(xml) }
                .getOrDefault(emptyList())
            actions[shortService(service.type)] = names
            if (xml != null) {
                signatures[shortService(service.type)] =
                    runCatching { parseSignatures(xml) }.getOrDefault(emptyList())
            }
        }
        return renderer.copy(queueActions = actions, queueSignatures = signatures)
    }

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
        val services = ArrayList<Service>()
        for (service in Xml.descendants(root)) {
            if (Xml.localName(service) != "service") continue
            fun child(name: String) = Xml.children(service)
                .firstOrNull { Xml.localName(it) == name }?.let(Xml::text)
            val type = child("serviceType").orEmpty()
            if (type.isEmpty()) continue
            if (services.size < MAX_SERVICES) {
                services += Service(
                    type = type,
                    scpdUrl = child("SCPDURL")?.takeIf { it.isNotEmpty() }
                        ?.let { resolve(documentUrl, it) },
                    controlUrl = child("controlURL")?.takeIf { it.isNotEmpty() }
                        ?.let { resolve(documentUrl, it) }
                )
            }
            if (!type.contains("AVTransport", ignoreCase = true)) continue
            // NOT `break` ANY MORE. The loop used to stop at AVTransport, which
            // was right while its control URL was the only thing wanted — and
            // would now report the services listed BEFORE it and none after,
            // which is a report that quietly depends on a manufacturer's
            // ordering. The first AVTransport control URL is still the one
            // taken; the walk simply finishes.
            if (control.isNullOrEmpty()) control = child("controlURL")
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
                controlUrl = controlUrl,
                services = services
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

    private fun avt(renderer: Renderer, action: String): Map<String, String> =
        soap.call(
            renderer.controlUrl, AV_TRANSPORT, action,
            listOf<Pair<String, Any>>("InstanceID" to 0)
        )

    private fun read(renderer: Renderer): Playing? = try {
        val transport = TransportState.of(
            avt(renderer, "GetTransportInfo")["CurrentTransportState"].orEmpty()
        )
        val position = avt(renderer, "GetPositionInfo")
        val track = Didl.parse(position["TrackMetaData"].orEmpty())

        /*
         * AND THEN THE TRANSPORT, WHEN THE TRACK CAME BACK SHORT — WHICH
         * [SonosSource] HAS DONE SINCE THE FIRST RELEASE AND THIS HAD NEVER
         * DONE AT ALL.
         *
         * `GetPositionInfo` describes the TRACK on the transport;
         * `GetMediaInfo` describes what the transport as a whole is playing.
         * For a radio station that is where the station's name lives, and for
         * a service driving the box over its own protocol — Qobuz Connect,
         * TIDAL Connect, anything casting — it is commonly the only one of the
         * two that is filled in at all. So a renderer answering "PLAYING" with
         * an empty TrackMetaData was read as describing nothing and dropped,
         * while the record it was playing sat in the reply this app never
         * asked for.
         *
         * A SECOND ROUND TRIP, AND IT IS GATED EXACTLY AS SONOS GATES IT: only
         * when the first reply would not draw a card. A renderer playing a
         * record it has fully described costs nothing extra, which is most of
         * them most of the time.
         *
         * UNVERIFIED AGAINST THE BOX THAT REPORTED THE BUG — no WiiM is
         * reachable from here. [diagnostics] prints both replies now, so the
         * first real run says which of the two the renderer filled in.
         */
        val media = if (track.album.isEmpty() || track.isEmpty) {
            Didl.parse(avt(renderer, "GetMediaInfo")["CurrentURIMetaData"].orEmpty())
        } else {
            NowPlaying()
        }
        val np = Didl.merge(track, media)

        // A station announcing "Artist - Title" is the only metadata some
        // streams ever send — read by Sonos since the beginning and thrown
        // away here, which is the same omission one field over.
        val announced = if (np.artist.isEmpty() && np.streamContent.isNotEmpty()) {
            Didl.splitStreamContent(np.streamContent)
        } else {
            null
        }

        // A DLNA renderer is fed by a server on the network exactly as a Sonos
        // is, and that server's art URL is refused for the same reason.
        streamHosts.remember(np.uri)
        Playing(
            source = name,
            zoneId = ZoneRef.idFor(name, renderer.udn),
            zoneName = renderer.friendlyName,
            album = np.displayAlbum.ifEmpty { announced?.second.orEmpty() },
            artist = np.displayArtist.ifEmpty { announced?.first.orEmpty() },
            track = np.track.ifEmpty { announced?.second.orEmpty() }
                .let { if (Didl.looksLikeStreamId(it)) "" else it },
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
        return notes + renderers.flatMap { renderer ->
            listOf(
                "  ${renderer.friendlyName} -> ${renderer.controlUrl}",
                "    services: " + renderer.services
                    .joinToString(", ") { shortService(it.type) }
                    .ifEmpty { "none listed" },
                "    can be asked to queue: " + queueability(renderer.services.map { it.type })
            ) + rawReply(renderer) + renderer.queueActions.map { (name, actions) ->
                "    $name actions: " +
                    actions.joinToString(", ").ifEmpty { "none published, or it would not answer" }
            } + renderer.queueSignatures.flatMap { (name, signatures) ->
                signatures.map { "    $name $it" }
            }
        }
    }

    /**
     * EXACTLY WHAT THE RENDERER SAID, UNPARSED — the section this app has
     * needed for a year and did not have.
     *
     * Reported from the field: Spotify Connect to a WiiM Pro Plus draws a
     * card and Qobuz Connect to the SAME BOX draws nothing. One renderer, one
     * code path, two services — so the difference is in the reply, and every
     * line above this one reports what this app MADE of a reply rather than
     * what arrived. The Roon queue cost four releases to exactly that: the
     * round that fixed it printed what the Core actually sent and the answer
     * was in the first two lines.
     *
     * Both replies, always, whatever the parse made of them: the whole
     * question is which of the two a Connect session fills in, and printing
     * only the one that came back short would hide the half that answered.
     * `GetTransportInfo` is here too because "the box says STOPPED" and "the
     * box says PLAYING with nothing in it" are two different bugs.
     *
     * The cost is three SOAP calls per renderer on the one page that exists
     * to be slow — Sonos's own diagnostics line already asks its coordinator
     * twice, for the same reason.
     */
    private fun rawReply(renderer: Renderer): List<String> =
        listOf("GetTransportInfo", "GetPositionInfo", "GetMediaInfo").flatMap { action ->
            try {
                val reply = avt(renderer, action)
                val fields = reply.entries
                    .filter { it.value.isNotBlank() }
                    .map { "      ${it.key}: ${snippet(it.value)}" }
                listOf("    $action:") + fields.ifEmpty {
                    listOf("      answered, every field empty")
                }
            } catch (e: Throwable) {
                listOf("    $action: ${e.javaClass.simpleName}: ${e.message ?: "no message"}")
            }
        }

    internal companion object {
        const val TAG = "Upnp"
        const val NAME = "UPnP"
        const val MEDIA_RENDERER_ST = "urn:schemas-upnp-org:device:MediaRenderer:1"
        const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"

        const val RESCAN_MS = 60_000L

        /** A description with more than this is listing more than is readable. */
        const val MAX_SERVICES = 16

        /**
         * One DIDL document, on one line, short enough to photograph.
         *
         * A cap that falls one word short of the answer is the expensive way
         * to be wrong — see `Similar.MAX_REASON`, which cut off at exactly the
         * word introducing the list it existed to carry. So the length is
         * stated when it cuts, and it cuts well past where a title, an artist
         * and an album have all appeared.
         */
        internal fun snippet(value: String): String {
            // Folded by hand rather than with a Regex: this is a companion
            // object, and a Regex property in one is a static initialiser
            // Android's stricter engine gets to refuse — see
            // RegexPortabilityTest and the release that would not open.
            val flat = buildString {
                for (c in value) {
                    val ch = if (c.isWhitespace()) ' ' else c
                    if (ch == ' ' && (isEmpty() || last() == ' ')) continue
                    append(ch)
                }
            }.trim()
            if (flat.length <= MAX_RAW) return flat
            return flat.take(MAX_RAW) + "… (" + flat.length + " chars)"
        }

        const val MAX_RAW = 600

        /**
         * Is this one of the services that holds a real queue?
         *
         * Kept beside [queueability] and asked the same question, so the list
         * that decides what gets REPORTED and the list that decides what gets
         * ASKED cannot drift apart.
         */
        internal fun isQueueService(type: String): Boolean =
            (type.contains("av-openhome-org", true) && type.contains("Playlist", true)) ||
                (type.contains("sonos", true) && type.contains("Queue", true)) ||
                type.contains("wiimu", true) || type.contains("PlayQueue", true)

        /**
         * Every action name in a service's SCPD.
         *
         * The document is the device's own, so this is what it can be asked
         * and not what anybody wrote down about it. Names only: the argument
         * lists are longer than a diagnostics page can hold, and the names are
         * what settle whether a queue can be APPENDED to at all.
         */
        internal fun parseActions(xml: String): List<String> {
            val root = Xml.parse(xml) ?: return emptyList()
            val out = ArrayList<String>()
            for (node in Xml.descendants(root)) {
                if (Xml.localName(node) != "action") continue
                val name = Xml.children(node)
                    .firstOrNull { Xml.localName(it) == "name" }?.let(Xml::text)
                if (!name.isNullOrEmpty() && out.size < MAX_ACTIONS) out += name
            }
            return out
        }

        /** Enough to see the shape of a service without filling the page. */
        const val MAX_ACTIONS = 40

        /**
         * THE VERBS WORTH KNOWING THE ARGUMENTS OF.
         *
         * A service can publish thirty-four actions — this one does — and
         * printing every argument of every one would bury the answer rather
         * than give it. These are the ones that ADD a record or FIND one to
         * add: everything else on that list logs a user in, sets a loop mode
         * or rates a track.
         *
         * A RULE RATHER THAN A LIST, because the next box will name them
         * differently and a list would silently answer "nothing" for it.
         */
        internal fun worthSigning(action: String): Boolean =
            listOf("append", "insert", "add", "search", "browse", "online")
                .any { action.contains(it, ignoreCase = true) }

        /**
         * `AppendQueue(in QueueContext)` — one line per verb that adds.
         *
         * THE NAME SAID A QUEUE CAN BE APPENDED TO; THIS SAYS WHAT TO PUT IN
         * ONE, which is the whole remaining question. It comes out of the SCPD
         * already fetched for the names, so it costs no extra request — and it
         * is the DEVICE's own answer rather than a vendor's PDF or somebody's
         * reverse engineering, which for a box on the same network is the only
         * kind worth having.
         */
        internal fun parseSignatures(xml: String): List<String> {
            val root = Xml.parse(xml) ?: return emptyList()
            val out = ArrayList<String>()
            for (node in Xml.descendants(root)) {
                if (Xml.localName(node) != "action") continue
                val name = Xml.children(node)
                    .firstOrNull { Xml.localName(it) == "name" }?.let(Xml::text)
                if (name.isNullOrEmpty() || !worthSigning(name)) continue
                if (out.size >= MAX_SIGNATURES) break
                val args = Xml.descendants(node)
                    .filter { Xml.localName(it) == "argument" }
                    .mapNotNull { argument ->
                        val argName = Xml.children(argument)
                            .firstOrNull { Xml.localName(it) == "name" }?.let(Xml::text)
                        val direction = Xml.children(argument)
                            .firstOrNull { Xml.localName(it) == "direction" }?.let(Xml::text)
                        if (argName.isNullOrEmpty()) null
                        else (direction?.takeIf { it.isNotEmpty() }?.plus(" ") ?: "") + argName
                    }
                    .toList()
                out += "$name(" + args.joinToString(", ") + ")"
            }
            return out
        }

        /** Six verbs is a readable answer; thirty-four is a wall. */
        const val MAX_SIGNATURES = 8

        /**
         * A service URN, short enough to read off a phone in another room.
         *
         * `urn:av-openhome-org:service:Playlist:1` becomes `openhome/Playlist`.
         * The VENDOR half is the point: base UPnP gives a renderer one track
         * and a "play this next" slot, and every real QUEUE is somebody's
         * extension — OpenHome's, Sonos's, LinkPlay's. Which of those a box
         * speaks is written in its description and nowhere else, and cannot be
         * discovered from a machine that is not on the network with it.
         */
        internal fun shortService(urn: String): String {
            val parts = urn.split(':')
            if (parts.size < 4) return urn
            // `schemas-upnp-org`, `av-openhome-org`, `schemas-sonos-com`,
            // `schemas-wiimu-com` — the decoration differs per vendor and none
            // of it carries meaning, so both ends come off.
            val vendor = parts[1]
                .removePrefix("schemas-").removePrefix("av-")
                .removeSuffix("-org").removeSuffix("-com")
            val name = parts.getOrNull(parts.indexOf("service") + 1) ?: return urn
            return (if (vendor == "upnp") "upnp" else vendor) + "/" + name
        }

        /**
         * WHAT THIS BOX COULD BE ASKED TO DO, READ OFF ITS OWN DESCRIPTION.
         *
         * A REPORT AND NOT A PROMISE. Naming a service here says the device
         * advertises it, which is not the same as it working, not the same as
         * having a URI worth sending, and not the same as this app being able
         * to build one. Base UPnP `AVTransport` has NO queue at all: it holds
         * one URI, plus one "next" slot, so anything sent to it REPLACES what
         * is playing rather than joining a list behind it. That distinction is
         * the whole reason this line exists — "queue" and "play" are one word
         * apart and a very long way apart in what they do to a room somebody
         * is listening to.
         */
        internal fun queueability(services: List<String>): String {
            val found = ArrayList<String>()
            if (services.any { it.contains("av-openhome-org", true) && it.contains("Playlist", true) }) {
                found += "OpenHome Playlist (a real queue: Insert/DeleteId/ReadList)"
            }
            if (services.any { it.contains("sonos", true) && it.contains("Queue", true) }) {
                found += "Sonos Queue (AddURIToQueue)"
            }
            if (services.any { it.contains("wiimu", true) || it.contains("PlayQueue", true) }) {
                found += "LinkPlay PlayQueue"
            }
            if (found.isEmpty()) {
                val avt = services.any { it.contains("AVTransport", true) }
                return if (avt) {
                    "nothing. AVTransport alone holds ONE uri and one next slot, " +
                        "so sending to it replaces what is playing"
                } else {
                    "nothing, and it lists no AVTransport either"
                }
            }
            return found.joinToString("; ")
        }
    }
}
