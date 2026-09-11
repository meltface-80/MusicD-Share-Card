package com.musicd.sharecard.api

import com.musicd.sharecard.Log
import com.musicd.sharecard.api.Json.putOrNull
import com.musicd.sharecard.http.HttpServer
import com.musicd.sharecard.http.Request
import com.musicd.sharecard.http.Response
import com.musicd.sharecard.meta.Metadata
import com.musicd.sharecard.meta.Pitchfork
import com.musicd.sharecard.sonos.Didl
import com.musicd.sharecard.sonos.Group
import com.musicd.sharecard.sonos.Household
import com.musicd.sharecard.sonos.ZoneState
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
    private val household: Household,
    private val metadata: Metadata,
    private val pitchfork: Pitchfork,
    private val art: ArtProxy,
    private val assets: Assets,
    private val version: String
) : HttpServer.Handler {

    override fun handle(request: Request): Response {
        if (request.method != "GET" && request.method != "HEAD") {
            return Json.error(405, "This server only answers GET.")
        }
        return try {
            route(request)
        } catch (e: Exception) {
            Log.w(TAG, "${request.path} threw: ${e.message}", e)
            Json.error(500, e.message ?: "Internal error")
        }
    }

    private fun route(request: Request): Response = when (request.path) {
        "/api/health" -> health()
        "/api/zones" -> zones(request)
        "/api/now-playing" -> nowPlaying(request)
        "/api/extras" -> extras(request)
        "/api/art" -> artwork(request)
        else -> static(request.path)
    }

    // ------------------------------------------------------------- the card

    private fun health(): Response = Json.obj(
        JSONObject()
            .put("ok", true)
            .put("version", version)
            .put("zones", household.groups().size)
    )

    /** Which rooms exist, and which one the card would be about right now. */
    private fun zones(request: Request): Response {
        val refresh = request.param("refresh") == "1"
        household.refresh(force = refresh)
        val groups = household.groups()
        return Json.obj(
            JSONObject()
                .put("zones", Json.array(groups.map(::groupJson)))
                .putOrNull("selected", household.preferredZoneUid)
        )
    }

    private fun groupJson(group: Group): JSONObject = JSONObject()
        .put("uid", group.uid)
        .put("name", group.displayName)
        .put("room", group.coordinator.name)
        .put("rooms", Json.strings(group.roomNames))

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
        if (wanted != null) household.preferredZoneUid = wanted
        household.refresh(force = request.param("refresh") == "1")

        val state = household.nowPlaying(wanted ?: household.preferredZoneUid)
            ?: return Json.obj(
                JSONObject()
                    .put("playing", false)
                    .put("reason", reasonForNothing())
            )

        // Remember what actually answered, so the next card without a zone=
        // comes from the same room rather than re-deciding on a tie.
        household.preferredZoneUid = state.group.uid
        return Json.obj(cardJson(state))
    }

    private fun reasonForNothing(): String =
        if (household.groups().isEmpty())
            "No Sonos players found on the network."
        else
            "Nothing is playing."

    /**
     * The card payload.
     *
     * `album` and `artist` are what the card is HEADED with, and they are
     * chosen here so the page never has to decide: album falls back to the
     * track title for a source that carries no album, and the album artist
     * beats the track artist on a compilation.
     */
    internal fun cardJson(state: ZoneState): JSONObject {
        val np = state.nowPlaying
        // A station announcing "Artist - Title" is the only metadata some
        // streams ever send, and without splitting it the card is headed with
        // the whole string and no artist at all.
        val announced = if (np.artist.isEmpty() && np.streamContent.isNotEmpty())
            Didl.splitStreamContent(np.streamContent) else null

        val album = np.displayAlbum.ifEmpty { announced?.second.orEmpty() }
        val artist = np.displayArtist.ifEmpty { announced?.first.orEmpty() }

        return JSONObject()
            .put("playing", state.isPlaying)
            .put("state", state.state.name)
            .put("stream", state.isStream)
            .put("zone", groupJson(state.group))
            .putOrNull("album", album)
            .putOrNull("artist", artist)
            .putOrNull("track", np.track.ifEmpty { announced?.second.orEmpty() })
            // The page asks this server for the picture, never the speaker —
            // see ArtProxy for why the canvas depends on it.
            .putOrNull("art", artPath(state))
    }

    /**
     * The path the page should ask for the cover, or null when there is none.
     *
     * The absolute player URL is carried in the query rather than resolved
     * again later, because the player that answered is the only one that can
     * serve it: Sonos art paths are relative to the coordinator, and a group
     * that regroups between the card being drawn and the picture being fetched
     * would otherwise have the request go to the wrong speaker.
     */
    private fun artPath(state: ZoneState): String? {
        val absolute = Didl.absoluteArt(state.nowPlaying.artUri, state.group.coordinator.baseUrl)
        if (absolute.isEmpty()) return null
        return "/api/art?u=" + urlEncode(absolute)
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
