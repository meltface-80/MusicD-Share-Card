package com.musicd.sharecard.api

import com.musicd.sharecard.Log
import com.musicd.sharecard.describe
import com.musicd.sharecard.str
import com.musicd.sharecard.api.Json.putOrNull
import com.musicd.sharecard.http.HttpServer
import com.musicd.sharecard.http.Request
import com.musicd.sharecard.http.Response
import com.musicd.sharecard.meta.Metadata
import com.musicd.sharecard.meta.Pitchfork
import com.musicd.sharecard.meta.QobuzAlbum
import com.musicd.sharecard.meta.StreamingLinks
import com.musicd.sharecard.meta.Updater
import com.musicd.sharecard.source.Playing
import com.musicd.sharecard.webhook.DiscordPoster
import com.musicd.sharecard.webhook.Webhook
import com.musicd.sharecard.webhook.WebhookRejected
import com.musicd.sharecard.webhook.WebhookStore
import com.musicd.sharecard.webhook.WebhookUrls
import com.musicd.sharecard.source.Sources
import com.musicd.sharecard.source.ZoneRef
import org.json.JSONArray
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
    private val hostNotes: () -> List<String> = { emptyList() },
    private val webhooks: WebhookStore = WebhookStore.inMemory(),
    private val discord: DiscordPoster = DiscordPoster(),
    /** Null where this host cannot install an APK — see [ShareCardApp]. */
    private val updater: Updater? = null,
    private val qobuz: QobuzAlbum? = null
) : HttpServer.Handler {

    private val access = Access { webhooks.pin() }

    /**
     * One thread, for the APK download only.
     *
     * The request that starts it returns straight away and the page polls, so
     * a two-megabyte download never holds an HTTP thread open — and one thread
     * means a second tap cannot start a second download beside the first.
     */
    private val downloads = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "sharecard-update").apply { isDaemon = true }
    }

    override fun handle(request: Request): Response {
        // Reads are open, as they have always been. The few routes that write
        // are POST/DELETE and are gated in [route] — see [Access] for why the
        // gate guards configuration and not the card itself.
        if (request.method !in ALLOWED_METHODS) {
            return Json.error(405, "That method is not used here.")
        }
        return try {
            route(request)
        } catch (e: Throwable) {
            // Throwable, not Exception: a NoClassDefFoundError from a class that
            // failed to initialise is an Error, and catching only Exception let
            // one escape all the way out and end the process.
            Log.w(TAG, "${request.path} threw: $e", e)
            Json.error(500, describe(e))
        }
    }

    private fun route(request: Request): Response {
        // The one route with a variable path segment.
        if (request.path.startsWith("/api/webhooks/")) return webhookById(request)
        return fixedRoute(request)
    }

    private fun fixedRoute(request: Request): Response {
        // EVERY ROUTE BELOW IS A READ, and a POST to one must still be refused.
        // Allowing POST at the top level so the webhook routes could use it
        // quietly made "POST /api/now-playing" answer 200 — the method gate had
        // become a formality rather than a rule. The write routes are named,
        // and everything else is GET-only exactly as before.
        if (request.method !in READ_METHODS && request.path !in WRITE_ROUTES) {
            return Json.error(405, "That route only answers GET.")
        }
        return readRoute(request)
    }

    private fun readRoute(request: Request): Response = when (request.path) {
        "/api/webhooks" -> when (request.method) {
            "POST" -> addWebhook(request)
            in READ_METHODS -> listWebhooks(request)
            else -> Json.error(405, "That method is not used here.")
        }
        "/api/setup" -> setup(request)
        // Reading what version is out there changes nothing. Fetching an APK
        // and pointing Android's installer at it changes everything on the
        // device, so both of those are gated exactly like adding a webhook.
        "/api/update/status" -> updateRoute(request) {
            // onDevice is what the page hides the whole update bar on. The
            // APK installs on THIS device, so a page open on an iPad across
            // the house has nothing to offer: the button there asked for a PIN
            // and then updated a machine in another room, which is not what
            // anybody pressing it meant.
            Json.obj(it.status().put("onDevice", access.isLoopback(request.remoteAddress)))
        }
        // POST, not GET, even though the gate would hold either way: a GET that
        // installs software is one a link prefetch or a browser's speculative
        // fetch can fire on its own.
        "/api/update/check" -> updateRoute(request, gated = true, post = true) {
            Json.obj(it.check())
        }
        "/api/update/apply" -> updateRoute(request, gated = true, post = true) { u ->
            Json.obj(u.apply { runnable -> downloads.execute(runnable) })
        }
        "/api/health" -> health()
        "/api/zones" -> zones(request)
        "/api/now-playing" -> nowPlaying(request)
        "/api/extras" -> extras(request)
        "/api/qobuz" -> qobuzLink(request)
        "/api/art" -> artwork(request)
        "/api/debug" -> Json.obj(Diagnostics(sources, hostNotes, pitchfork::attempts).run())
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
                // The review this app just read the score off. It was already
                // fetched; not offering it meant somebody who wanted the words
                // behind the number had to go and search for the page the app
                // had open a second ago.
                .putOrNull("reviewUrl", review?.url)
                // Where to hear it. No lookup and no network — these are a
                // function of the album and the artist, so they come back on
                // the fast path too and are on screen with the first paint.
                .put(
                    "links",
                    JSONArray(
                        StreamingLinks.forAlbum(artist, album).map {
                            JSONObject()
                                .put("service", it.service)
                                .put("name", it.name)
                                .put("url", it.url)
                        }
                    )
                )
                // The page shows nothing different for a cached miss than for a
                // lookup never made, but the distinction is what tells a
                // developer whether fast=1 is working.
                .put("cached", fast)
        )
    }

    // --------------------------------------------------------------- webhooks

    /**
     * The configured webhooks, MASKED.
     *
     * Open to read, because the page needs to draw a button per webhook and a
     * name is not a secret. The URL never appears here — see [Webhook.masked].
     */
    private fun listWebhooks(request: Request): Response = Json.obj(
        JSONObject()
            .put(
                "webhooks",
                Json.array(
                    webhooks.all().map {
                        JSONObject()
                            .put("id", it.id)
                            .put("name", it.name)
                            .put("masked", it.masked)
                            .put("kind", it.kind)
                            // Not secret, and the page needs it to show what a
                            // post will look like before one is sent.
                            .put("username", it.username)
                            .put("avatarUrl", it.avatarUrl)
                    }
                )
            )
            // So the page knows whether to ask for a PIN before offering to add
            // one, rather than letting somebody type a URL and then refusing it.
            .put("mayConfigure", access.mayConfigure(request))
    )

    /**
     * Add one. Gated: this is where the credential enters.
     */
    private fun addWebhook(request: Request): Response {
        if (!access.mayConfigure(request)) return needsPin()
        val body = Json.body(request)
        val name = body.str("name").trim().take(40)
        val raw = body.str("url").trim()
        return try {
            val url = WebhookUrls.validate(raw)
            val webhook = Webhook(
                id = WebhookUrls.idFor(url),
                name = name.ifEmpty { "Discord" },
                url = url,
                username = WebhookUrls.validateUsername(body.str("username")),
                avatarUrl = WebhookUrls.validateAvatar(body.str("avatarUrl"))
            )
            webhooks.add(webhook)
            Log.i(TAG, "added webhook ${webhook.name} (${webhook.masked})")
            Json.obj(
                JSONObject().put("ok", true).put("id", webhook.id)
                    .put("name", webhook.name).put("masked", webhook.masked)
            )
        } catch (e: WebhookRejected) {
            Json.error(400, e.message ?: "That webhook URL was refused.")
        }
    }

    /** `/api/webhooks/<id>` — DELETE removes it, POST sends the card to it. */
    private fun webhookById(request: Request): Response {
        val rest = request.path.removePrefix("/api/webhooks/").trim('/')
        val id = rest.substringBefore('/')
        val action = rest.substringAfter('/', "")
        val webhook = webhooks.all().firstOrNull { it.id == id }
            ?: return Json.error(404, "No such webhook.")

        return when {
            request.method == "DELETE" -> {
                if (!access.mayConfigure(request)) return needsPin()
                webhooks.remove(id)
                Json.obj(JSONObject().put("ok", true))
            }
            request.method == "POST" && action == "post" -> postCard(request, webhook)
            request.method == "POST" && action == "avatar" -> setAvatar(request, webhook)
            else -> Json.error(405, "That method is not used here.")
        }
    }

    /**
     * Send the card the page just drew.
     *
     * NOT gated, deliberately. This is the everyday action — "tap the button"
     * must not mean "find the PIN first" — and the worst it offers a stranger
     * on the network is posting a picture of the owner's own album to the
     * owner's own channel. Adding a webhook is where the real damage would be,
     * and that is gated.
     *
     * The PNG arrives as the raw request body rather than base64 in JSON: it is
     * around a megabyte, and base64 would make it a third larger for nothing.
     */
    private fun postCard(request: Request, webhook: Webhook): Response {
        val png = request.body
        if (png.isEmpty()) return Json.error(400, "No card was sent.")
        val outcome = discord.post(webhook, png)
        return if (outcome.ok) {
            Json.obj(JSONObject().put("ok", true).put("detail", outcome.detail))
        } else {
            // 502: this app is fine, the other end refused. A 500 would send
            // somebody looking at the wrong thing.
            Json.error(502, outcome.detail)
        }
    }

    /**
     * Give a webhook a picture from an uploaded photo.
     *
     * Gated, like adding one: it changes the webhook on Discord's side, and
     * that is configuration rather than an everyday tap.
     *
     * The body is a `data:image/...;base64,` string the page produced by
     * scaling the chosen photo down — the scaling happens there because the
     * page already has a canvas and this module has no image decoder at all.
     */
    private fun setAvatar(request: Request, webhook: Webhook): Response {
        if (!access.mayConfigure(request)) return needsPin()
        val dataUri = request.bodyText.trim()
        if (dataUri.isEmpty()) return Json.error(400, "No picture was sent.")
        val outcome = discord.setAvatar(webhook, dataUri, webhook.username)
        return if (outcome.ok) {
            Json.obj(JSONObject().put("ok", true).put("detail", outcome.detail))
        } else {
            Json.error(502, outcome.detail)
        }
    }

    /**
     * What a device across the house needs in order to configure anything.
     *
     * The PIN itself is returned ONLY to loopback — that is, to the app's own
     * screen on the device. Serving it to the LAN would make it decoration.
     */
    private fun setup(request: Request): Response {
        val onDevice = access.isLoopback(request.remoteAddress)
        return Json.obj(
            JSONObject()
                .put("onDevice", onDevice)
                .put("mayConfigure", access.mayConfigure(request))
                .put("pin", if (onDevice) webhooks.pin() else JSONObject.NULL)
        )
    }

    /**
     * The Qobuz album id, which is the only link that opens the Qobuz APP.
     *
     * ITS OWN ROUTE, NOT A FIELD ON /api/extras, and that is the point. This
     * one reads a page off www.qobuz.com and is rate-gated to one request every
     * second and a half, so putting it beside the metadata would hold the whole
     * card back for a link. The page paints with the search link from
     * StreamingLinks and swaps it for this one when it lands — or never, which
     * is the honest answer for a record Qobuz does not carry.
     *
     * `fast=1` answers from the cache only, so a second look at the same record
     * costs nothing and asks nobody.
     */
    private fun qobuzLink(request: Request): Response {
        val album = request.param("album").orEmpty()
        val artist = request.param("artist").orEmpty()
        if (album.isEmpty()) return Json.error(400, "No album named.")
        val q = qobuz ?: return Json.obj(JSONObject().put("url", JSONObject.NULL))
        val url = if (request.param("fast") == "1") q.cachedDeepLink(artist, album)
        else q.deepLink(artist, album)
        return Json.obj(JSONObject().putOrNull("url", url))
    }

    /**
     * The three update routes, with the two things they share.
     *
     * A host that cannot install an APK answers with the shape the page reads
     * as "nothing to offer" rather than an error, so a desktop browser pointed
     * at this app simply shows no update button — see [ShareCardApp.updater].
     */
    private fun updateRoute(
        request: Request,
        gated: Boolean = false,
        post: Boolean = false,
        body: (Updater) -> Response
    ): Response {
        val u = updater ?: return Json.obj(
            JSONObject()
                .put("available", false)
                .put("current", version)
                .put("supported", false)
                .put("blocked", "Updates are only available in the Android app.")
        )
        if (post && request.method != "POST") {
            return Json.error(405, "That route only answers POST.")
        }
        if (gated && !access.mayConfigure(request)) return needsPin()
        return body(u)
    }

    private fun needsPin(): Response = Json.error(
        401,
        "Enter the PIN shown on the device running Share Card to change webhooks."
    )

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

        val ALLOWED_METHODS = setOf("GET", "HEAD", "POST", "DELETE")

        /**
         * The only paths a POST or DELETE may reach.
         *
         * NAMED, NOT INFERRED. This gate was once "anything, so the webhook
         * routes work", and that quietly made POST /api/now-playing answer 200.
         * Every route not in here is GET-only.
         */
        val WRITE_ROUTES = setOf("/api/webhooks", "/api/update/check", "/api/update/apply")

        /** The methods a route that changes nothing may be asked with. */
        val READ_METHODS = setOf("GET", "HEAD")
    }
}
