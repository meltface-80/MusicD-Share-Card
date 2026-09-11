package com.musicd.sharecard.sonos

import java.net.URI

/** The Sonos service types and control paths this app uses. */
object Sonos {
    const val PORT = 1400
    const val DEVICE_DESCRIPTION_PATH = "/xml/device_description.xml"

    const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
    const val ZONE_GROUP_TOPOLOGY = "urn:schemas-upnp-org:service:ZoneGroupTopology:1"

    /** Sonos does not use the control URLs a stock MediaRenderer would. */
    val CONTROL_PATHS = mapOf(
        AV_TRANSPORT to "/MediaRenderer/AVTransport/Control",
        ZONE_GROUP_TOPOLOGY to "/ZoneGroupTopology/Control"
    )

    /** The SSDP search target that finds players and nothing else. */
    const val ZONE_PLAYER_ST = "urn:schemas-upnp-org:device:ZonePlayer:1"

    fun baseUrl(ip: String): String = "http://$ip:$PORT"
}

/** One Sonos room, as ZoneGroupTopology describes it. */
data class Zone(
    val uid: String,
    val name: String,
    val ip: String,
    val coordinatorUid: String = "",
    val groupId: String = "",
    val invisible: Boolean = false,
    val isBridge: Boolean = false
) {
    val isCoordinator: Boolean
        get() = coordinatorUid.isEmpty() || coordinatorUid == uid

    val baseUrl: String get() = Sonos.baseUrl(ip)

    /**
     * Satellites, subs and BOOST/BRIDGE units are not rooms you play to, and a
     * card for one would be a card for a speaker that has no transport of its
     * own.
     */
    val playable: Boolean get() = !invisible && !isBridge
}

/** What a zone's transport is doing. */
enum class TransportState {
    PLAYING, PAUSED, STOPPED, TRANSITIONING, UNKNOWN;

    companion object {
        fun of(raw: String): TransportState = when (raw.uppercase()) {
            "PLAYING" -> PLAYING
            "PAUSED_PLAYBACK" -> PAUSED
            "STOPPED" -> STOPPED
            "TRANSITIONING" -> TRANSITIONING
            else -> UNKNOWN
        }
    }
}

/**
 * The player calls this app makes, behind an interface.
 *
 * [Household] does the deciding — which room is playing, whose metadata wins —
 * and that is the part worth testing. An interface here is what lets the tests
 * drive it with scripted replies instead of a speaker, which is the difference
 * between the selection logic being covered and it being hoped about.
 */
interface Transport {
    val baseUrl: String
    fun zoneGroupState(): String
    fun transportState(): TransportState
    fun positionInfo(): NowPlaying
    fun mediaInfo(): Pair<NowPlaying, String>

    /**
     * The unparsed AVTransport reply, for the diagnostics page.
     *
     * Every source populates DIDL-Lite differently and two of them arrived in
     * shapes that had to be guessed at from a photograph of a card. Showing
     * exactly what a player said is how that stops being guesswork.
     */
    fun rawNowPlaying(): Map<String, String> = emptyMap()
}

/** A thin, typed wrapper around one player's UPnP services. */
class SonosPlayer(val ip: String, private val soap: SoapClient) : Transport {

    override val baseUrl: String get() = Sonos.baseUrl(ip)

    private fun controlUrl(serviceType: String): String =
        baseUrl + Sonos.CONTROL_PATHS.getValue(serviceType)

    private fun avt(action: String, vararg args: Pair<String, Any>): Map<String, String> =
        soap.call(
            controlUrl(Sonos.AV_TRANSPORT), Sonos.AV_TRANSPORT, action,
            listOf<Pair<String, Any>>("InstanceID" to 0) + args
        )

    /** The whole household from any one player, which is why discovery only
     *  has to find one. */
    override fun zoneGroupState(): String =
        soap.call(
            controlUrl(Sonos.ZONE_GROUP_TOPOLOGY), Sonos.ZONE_GROUP_TOPOLOGY, "GetZoneGroupState"
        )["ZoneGroupState"].orEmpty()

    override fun transportState(): TransportState =
        TransportState.of(avt("GetTransportInfo")["CurrentTransportState"].orEmpty())

    /** The track on the transport, with its DIDL already parsed. */
    override fun positionInfo(): NowPlaying =
        Didl.parse(avt("GetPositionInfo")["TrackMetaData"].orEmpty())

    /**
     * What the transport as a whole is playing, as opposed to the current
     * track. For a radio station this is where the station's own name lives —
     * GetPositionInfo carries only whatever the stream is announcing.
     */
    override fun mediaInfo(): Pair<NowPlaying, String> {
        val result = avt("GetMediaInfo")
        return Didl.parse(result["CurrentURIMetaData"].orEmpty()) to
            result["CurrentURI"].orEmpty()
    }

    override fun rawNowPlaying(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        runCatching { avt("GetPositionInfo") }
            .onSuccess { for ((k, v) in it) if (v.isNotEmpty()) out["position.$k"] = v }
            .onFailure { out["position.error"] = it.message.orEmpty() }
        runCatching { avt("GetMediaInfo") }
            .onSuccess { for ((k, v) in it) if (v.isNotEmpty()) out["media.$k"] = v }
            .onFailure { out["media.error"] = it.message.orEmpty() }
        return out
    }
}

/**
 * Parse a `GetZoneGroupState` payload.
 *
 * Handles both the modern `<ZoneGroupState><ZoneGroups>` wrapper and older
 * firmware that returns `<ZoneGroups>` at the top level.
 */
fun parseZoneGroupState(xml: String): List<Zone> {
    val root = Xml.parse(xml) ?: return emptyList()
    val zones = ArrayList<Zone>()

    for (group in Xml.descendants(root)) {
        if (Xml.localName(group) != "ZoneGroup") continue
        val coordinator = group.getAttribute("Coordinator").orEmpty()
        val groupId = group.getAttribute("ID").orEmpty()
        for (member in Xml.children(group)) {
            if (Xml.localName(member) != "ZoneGroupMember") continue
            val uid = member.getAttribute("UUID").orEmpty()
            if (uid.isEmpty()) continue
            val location = member.getAttribute("Location").orEmpty()
            val ip = if (location.isEmpty()) "" else
                runCatching { URI(location).host }.getOrNull().orEmpty()
            zones += Zone(
                uid = uid,
                name = member.getAttribute("ZoneName").orEmpty().ifEmpty { uid },
                ip = ip,
                coordinatorUid = coordinator,
                groupId = groupId,
                invisible = member.getAttribute("Invisible") == "1",
                isBridge = member.getAttribute("IsZoneBridge") == "1"
            )
        }
    }
    return zones
}
