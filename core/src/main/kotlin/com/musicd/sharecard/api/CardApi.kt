package com.musicd.sharecard.api

import com.musicd.sharecard.Log
import com.musicd.sharecard.api.Json.putOrNull
import com.musicd.sharecard.http.HttpServer
import com.musicd.sharecard.http.Request
import com.musicd.sharecard.http.Response
import com.musicd.sharecard.meta.Metadata
import com.musicd.sharecard.meta.Pitchfork
import com.musicd.sharecard.source.Playing
import com.musicd.sharecard.source.Sources
import com.musicd.sharecard.source.ZoneRef
import org.json.JSONObject

/**
 * Every route this app serves.
 *
 * ALL OF THEM ARE GETS, AND NONE OF THEM CHANGES ANYTHING. Nothing here starts,
 * stops, skips or reconfigures a player, and nothing is written to disk. That
 * is what makes it safe to answer the whole LAN without a password — see
 * [HttpServer]'s class comment — and it is a property to preserve, not a stage
 * this app is passing through.
 */
class CardApi(
    private val sources: Sources,
    private val metadata: Metadata,
    private val pitchfork: Pitchfork,
    private val art: ArtProxy,
    private val assets: Assets,
    private val version: String,
    private val hostNotes: () -> List<String> = { emptyList() }
) : HttpServer.Handler {

    override fun handle(request: Request): Response {
        if (request.method != "GET" && request.method != "HEAD") {
            return Json.error(405, "This server only answers GET.")
        }
        return try {
            route(request)
        } catch (e: Throwable) {
            // Throwable, not Exception: a NoClassDefFoundError from a class that
            // failed to initialise is an Error, and catching only Exception let
            // one escape all the way out and end the process.
            Log.w(TAG, "${request.path} threw: $e", e)
            Json.error(500, "${e.javaClass.simpleName}: ${e.message ?: "no message"}")
        }
    }

    private fun route(request: Request): Response = when (request.path) {
        "/api/health" -> health()
        "/api/zones" -> zones(request)
        "/api/now-playing" -> nowPlaying(request)
        "/api/extras" -> extras(request)
        "/api/art" -> artwork(request)
        "/api/debug" -> Json.obj(Diagnostics(sources, hostNotes).run())
        else -> static(request.path)
    }

    // ------------------------------------------------------------- the card

    /**
     * Is the server up. Nothing more, and deliberately so.
     *
     * This used to report the zone count, which meant a liveness check ran a
     * multicast sweep and a device-description fetch per renderer. A health
     * endpoint that takes four seconds and talks to every box on the network is
     * not a health endpoint.
     */
    private fun health(): Response = Json.obj(
        JSONObject().put("ok", true).put("version", version)
    )

    /** Which rooms exist, across every source, and which one is selected. */
    private fun zones(request: Request): Response {
        if (request.param("refresh") == "1") sources.refresh()
        return Json.obj(
            JSONObject()
                .put("zones", Json.array(sources.zones().map(::zoneJson)))
                .putOrNull("selected", sources.preferredZoneId)
        )
    }

    /**
     * A zone, named by its source when more than one source has any.
     *
     * "Kitchen" is enough when only the speakers are seen. With Roon in the
     * house there can be two entries for the same room — one per source — and
     * they answer differently, so the caption has to say which is which.
     */
    private fun zoneJson(zone: ZoneRef): JSONObject = JSONObject()
        .put("uid", zone.id)
        .put("name", zone.name)
        .put("room", zone.name)
        .put("source", zone.source)
        .put("rooms", Json.strings(zone.rooms))

    /**
     * What the card is about.
     *
     * The zone is chosen here rather than by the page, because the choice needs
     * the transport state of every room and the page should not be making a
     * round trip per speaker to find out. `zone=` narrows it to one room; left
     * off, [Household.nowPlaying] picks whichever is playing.
     */
    private fun nowPlaying(request: Request): Response {
        val wanted = request.param("zone")
        if (wanted != null) sources.preferredZoneId = wanted
        if (request.param("refresh") == "1") sources.refresh()

        val playing = sources.nowPlaying(wanted ?: sources.preferredZoneId)
        if (playing == null || playing.isEmpty) {
            return Json.obj(
                JSONObject()
                    .put("playing", false)
                    .put("reason", reasonForNothing())
                    // Something the user can act on beats a description of the
                    // symptom — a first Roon run is not a broken app, it is one
                    // waiting to be let in.
                    .put("notices", Json.strings(sources.notices()))
            )
        }

        // Remember what actually answered, so the next card without a zone=
        // comes from the same place rather than re-deciding on a tie.
        sources.preferredZoneId = playing.zoneId
        return Json.obj(cardJson(playing).put("notices", Json.strings(sources.notices())))
    }

    private fun reasonForNothing(): String =
        if (!sources.anyZones()) "No players found on the network."
        else "Nothing is playing."

    /**
     * The card payload.
     *
     * Every source has already resolved its own quirks by this point — whether
     * `dc:creator` beat `upnp:artist`, or which of Roon's three lines is the
     * album — so this is a straight copy.
     */
    internal fun cardJson(playing: Playing): JSONObject = JSONObject()
        .put("playing", playing.state.isPlaying)
        .put("state", playing.state.name)
        .put("stream", playing.isStream)
        .put("source", playing.source)
        .put(
            "zone",
            JSONObject()
                .put("uid", playing.zoneId)
                .put("name", playing.zoneName)
                .put("room", playing.zoneName)
                .put("source", playing.source)
        )
        .putOrNull("album", playing.album)
        .putOrNull("artist", playing.artist)
        .putOrNull("track", playing.track)
        // The page asks this server for the picture, never the player — see
        // ArtProxy for why the canvas depends on it.
        .putOrNull("art", artPath(playing))

    /**
     * The path the page should ask for the cover, or null when there is none.
     *
     * The source's absolute URL is carried in the query rather than resolved
     * again later, because only the source that answered knows where its art
     * lives — a speaker's `/getaa`, a Roon Core's image service, a streaming
     * service's CDN.
     */
    private fun artPath(playing: Playing): String? {
        if (playing.artUrl.isEmpty()) return null
        return "/api/art?u=" + urlEncode(playing.artUrl)
    }

    private fun artwork(request: Request): Response {
        val url = request.param("u") ?: return Json.error(400, "No picture asked for.")
        val picture = art.fetch(url) ?: return Json.error(404, "No cover.")
        return Response.bytes(
            200, picture.contentType, picture.bytes,
            // The card is redrawn on every open and the URL carries the
            // player's own version stamp, so this is safe to hold briefly and
            // saves re-fetching the same sleeve for the softened background.
            mapOf("Cache-Control" to "private, max-age=300")
        )
    }

    // ---------------------------------------------------------- the extras

    /**
     * Release year, blurb and score for one record.
     *
     * `fast=1` answers from the caches and never opens a socket. That split is
     * the difference between a card appearing at once and a spinner sitting
     * through five sequential requests to MusicBrainz, Wikipedia and Pitchfork
     * behind a rate gate — the page draws on the fast answer and redraws if the
     * slow one turns up while the card is still open.
     */
    private fun extras(request: Request): Response {
        val album = request.param("album").orEmpty()
        val artist = request.param("artist").orEmpty()
        if (album.isEmpty()) return Json.error(400, "No album named.")
        val fast = request.param("fast") == "1"

        val extras = if (fast) metadata.cachedExtras(album, artist)
        else metadata.extras(album, artist)
        val review = if (fast) pitchfork.cachedReviewFor(album, artist)
        else pitchfork.reviewFor(album, artist)

        return Json.obj(
            JSONObject()
                .put("release", extras?.year?.toString() ?: JSONObject.NULL)
                .putOrNull("bio", extras?.album?.description)
                .putOrNull("bioSource", extras?.album?.source)
                .put("score", review?.score ?: JSONObject.NULL)
                .put("isBestNewMusic", review?.isBestNewMusic ?: false)
                // The page shows nothing different for a cached miss than for a
                // lookup never made, but the distinction is what tells a
                // developer whether fast=1 is working.
                .put("cached", fast)
        )
    }

    // ------------------------------------------------------------- the page

    private fun static(path: String): Response {
        val clean = if (path == "/" || path.isEmpty()) "index.html"
        else HttpServer.decodePath(path.trimStart('/'))

        // The path comes off the network, so it must not be able to name a file
        // outside the bundle. Rejecting any segment of ".." is enough here
        // because the asset source has no directory to escape into, and it
        // stays correct if that ever changes.
        if (clean.split('/').any { it == ".." || it == "." }) return Response.notFound()

        val bytes = assets.open(clean) ?: return Response.notFound()
        return Response.bytes(200, MimeTypes.of(clean), bytes, NO_STORE)
    }

    private fun urlEncode(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private companion object {
        const val TAG = "CardApi"

        /**
         * The page is versioned by the APK, not by a URL, so a browser holding
         * yesterday's JavaScript against today's API is a real way for this to
         * break after an update — and the phone that did it is the one place
         * nobody can see the mismatch.
         */
        val NO_STORE = mapOf("Cache-Control" to "no-store")
    }
}
