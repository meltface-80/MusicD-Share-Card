package com.musicd.sharecard.api

import com.musicd.sharecard.Log
import com.musicd.sharecard.bool
import com.musicd.sharecard.describe
import com.musicd.sharecard.str
import com.musicd.sharecard.api.Json.putOrNull
import com.musicd.sharecard.http.HttpServer
import com.musicd.sharecard.discover.Editorial
import com.musicd.sharecard.discover.NewMusic
import com.musicd.sharecard.lms.LmsQueue
import com.musicd.sharecard.device.DeviceAudio
import com.musicd.sharecard.discover.PlayHistory
import com.musicd.sharecard.http.Request
import com.musicd.sharecard.http.Response
import com.musicd.sharecard.meta.Metadata
import com.musicd.sharecard.meta.Pitchfork
import com.musicd.sharecard.meta.Reviews
import com.musicd.sharecard.roon.RoonBrowse
import com.musicd.sharecard.meta.QobuzAlbum
import com.musicd.sharecard.meta.Similar
import com.musicd.sharecard.meta.StreamingLinks
import com.musicd.sharecard.meta.Updater
import com.musicd.sharecard.source.Playing
import com.musicd.sharecard.source.Room
import com.musicd.sharecard.webhook.DiscordPoster
import com.musicd.sharecard.webhook.Webhook
import com.musicd.sharecard.webhook.WebhookRejected
import com.musicd.sharecard.settings.Settings
import com.musicd.sharecard.settings.SettingsStore
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
    private val qobuz: QobuzAlbum? = null,
    /** Null where suggestions are switched off; the row simply never appears. */
    private val similar: Similar? = null,
    /**
     * What the household has switched on. Defaults to remembering nothing,
     * which for a test means every service on and, deliberately, no zones.
     */
    private val settingsStore: SettingsStore = SettingsStore.inMemory(),
    /**
     * Whether a device that is not this one needs the PIN to change anything.
     * The container turns this off unless a PIN is configured — see [Access].
     */
    private val requirePin: Boolean = true,
    /** Null where Roon is not in play, which is every test but one. */
    private val roonBrowse: RoonBrowse? = null,
    /** Lyrion's half of the same feature. Null where no server is configured. */
    private val lmsQueue: LmsQueue? = null,
    /**
     * What is playing on the device running this, where it can tell.
     *
     * A PROBE, NOT A SOURCE: it never reaches a card and has no zone. Null on
     * any host that cannot see its own audio, which is every host but the
     * Android app — see [com.musicd.sharecard.device.DeviceAudio].
     */
    private val deviceAudio: DeviceAudio? = null,
    /**
     * What this app has drawn a card for, which is what "based on your
     * listening" is based on. Remembers nothing by default, so a test and a
     * host with no storage behave exactly as this class did before it existed.
     */
    private val history: PlayHistory = PlayHistory.inMemory(),
    /** Null where this host has no route to the internet; the screen says so. */
    private val newMusic: NewMusic? = null,
    /** Its feed reads land in the same diagnostics section as the rest. */
    private val editorial: Editorial? = null
) : HttpServer.Handler {

    private val access = Access({ webhooks.pin() }, requirePin)

    init {
        /*
         * THE FILTER IS INSTALLED ONCE, AND IT READS THE STORE EVERY TIME.
         *
         * Handing Sources a snapshot of the enabled set would freeze it at
         * startup, so enabling a room would do nothing until the next restart
         * — on a device that is never restarted. The lambda asks the store
         * instead, and the store holds its answer in memory after the first
         * read, so this costs nothing on the request path.
         */
        sources.zoneFilter = { id -> settingsStore.read().zoneEnabled(id) }
    }

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

    /**
     * WHERE THE HISTORY IS WRITTEN, AND IT IS NOT THE REQUEST THREAD.
     *
     * The rule every store here keeps: the cache is written by the lookup path
     * on its own thread AFTER the request that triggered it has been answered,
     * and nothing on the network ever waits on a disk write. A card must not be
     * a millisecond slower because something is being remembered about it, and
     * a disk that has filled up must not be able to fail a card.
     *
     * One thread, so two cards drawn at once cannot interleave into the file,
     * and a daemon, so it cannot hold the process open.
     */
    private val remembering = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "sharecard-history").apply { isDaemon = true }
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
        "/api/queue" -> queueRoute(request)
        "/api/settings" -> when (request.method) {
            "POST" -> settingsWrite(request)
            in READ_METHODS -> settingsRead(request.param("zones") == "1")
            else -> Json.error(405, "That method is not used here.")
        }
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
        "/api/similar" -> similarActs(request)
        "/api/new" -> newMusicRoute(request)
        "/api/art" -> artwork(request)
        "/api/debug" -> Json.obj(
            Diagnostics(
                sources, hostNotes, pitchfork::attempts,
                { similar?.attempts().orEmpty() },
                art::attempts,
                /*
                 * TWO SOURCES, ONE SECTION, AND EACH LINE SAYS WHICH.
                 * The attempts merged into one list read identically —
                 * "no match in 3 row(s)" is the same sentence from Roon and
                 * from Lyrion, and the fixes are in different files. Same
                 * rule as `Pitchfork.Outcome` naming which failure it was.
                 */
                {
                    roonBrowse?.attempts().orEmpty().map { "Roon: $it" } +
                        lmsQueue?.attempts().orEmpty().map { "Lyrion: $it" }
                },
                { newMusic?.attempts().orEmpty() + editorial?.attempts().orEmpty() },
                { deviceAudio?.diagnostics().orEmpty() }
            ).run()
        )
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

    /** What is switched on right now. Held in memory by the store. */
    private val enabledServices: Settings get() = settingsStore.read()

    /**
     * Can this room be asked to put a record on the end of its queue?
     *
     * ONE LIST, read by the route that does it and by the card that offers
     * it. A source appearing in one and not the other is a chip that queues
     * nothing, or a room that could and is never asked.
     */
    internal fun canQueue(zoneId: String): Boolean = when (ZoneRef.sourceOf(zoneId)) {
        ROON_SOURCE -> roonBrowse != null
        LYRION_SOURCE -> lmsQueue != null
        else -> false
    }

    /**
     * PUT A SUGGESTED RECORD ON THE END OF A ROOM'S QUEUE.
     *
     * ONE ROUTE, DISPATCHING ON THE ZONE'S SOURCE, AND THAT IS DELIBERATE.
     * It was `/api/roon/queue`, and adding Lyrion beside it would have meant
     * either a second gated write route — a second place to get the gate
     * wrong — or the PAGE choosing a URL per source, which is a rule, and
     * rules live in :core where they have tests. The page posts a zone; this
     * decides what that zone can be asked.
     *
     * Still POST, because a GET that touches playback is one a link prefetch
     * can fire by itself. Still behind [Access.mayConfigure], because choosing
     * a room on somebody's behalf is choosing which room to play into. And a
     * zone whose source cannot queue is REFUSED rather than guessed at.
     */
    private fun queueRoute(request: Request): Response {
        if (request.method != "POST") return Json.error(405, "That method is not used here.")
        if (!access.mayConfigure(request)) return needsPin()

        val body = Json.body(request)
        val album = body.str("album").trim()
        val artist = body.str("artist").trim()
        val zone = body.str("zone").trim()
        if (album.isEmpty()) return Json.error(400, "No album named.")

        val raw = ZoneRef.rawOf(zone)
        val outcome = when (ZoneRef.sourceOf(zone)) {
            ROON_SOURCE -> {
                val browse = roonBrowse ?: return Json.error(503, "Roon is not available here.")
                val result = browse.queueAlbum(album, artist, zone)
                result.queued to result.detail
            }
            LYRION_SOURCE -> {
                val queue = lmsQueue ?: return Json.error(503, "Lyrion is not available here.")
                val result = queue.queueAlbum(album, artist, raw)
                result.queued to result.detail
            }
            else -> return Json.error(400, "That room cannot take a queue.")
        }
        return Json.obj(
            JSONObject().put("queued", outcome.first).put("detail", outcome.second)
        )
    }

    // ------------------------------------------------------------- settings

    /**
     * Everything the settings screen draws: what exists, and what is on.
     *
     * THE ZONES HERE ARE [Sources.allZones] AND NOT [Sources.zones], which is
     * the one place in this app that difference matters. Everywhere else a
     * switched-off room does not exist; on this screen it has to be visible,
     * or there would be no way to switch it on. A television that is powered
     * off is simply not in the list yet, and that is not an error — it appears
     * when it next answers discovery.
     */
    private fun settingsRead(withZones: Boolean = false): Response {
        val settings = settingsStore.read()
        /*
         * THE ZONE LIST IS ASKED FOR SEPARATELY, AND THAT IS NOT TIDINESS.
         *
         * Listing zones goes to the sources, which serialise against a
         * discovery sweep already in progress — so while the house is being
         * searched, this call blocks. Bundling the zones in meant the SERVICES
         * screen, which needs nothing from the network at all, sat waiting on
         * a sweep it had no use for: measured in a browser against the real
         * server, the screen simply did not appear until discovery gave up.
         *
         * Services and Reviews now cost no network. Zones still waits, because
         * a list of discovered devices cannot be produced without discovering
         * them — but the page draws its frame first and says it is looking.
         */
        val zones = if (!withZones) emptyList() else runCatching { sources.allZones() }
            .onFailure { Log.w(TAG, "could not list zones for settings: ${it.message}") }
            .getOrDefault(emptyList())
        return Json.obj(
            JSONObject()
                .put(
                    "services",
                    JSONArray(
                        StreamingLinks.services().map {
                            JSONObject()
                                .put("id", it.service)
                                .put("name", it.name)
                                .put("enabled", settings.serviceEnabled(it.service))
                        }
                    )
                )
                .put(
                    "zones",
                    JSONArray(
                        zones.map {
                            zoneJson(it).put("enabled", settings.zoneEnabled(it.id))
                        }
                    )
                )
                .put(
                    "reviews",
                    JSONArray(
                        Reviews.ALL.map {
                            JSONObject()
                                .put("id", it.id)
                                .put("name", it.name)
                                // The page groups on this: album sources are
                                // about the record in front of you, artist
                                // ones are about whoever made it.
                                .put("kind", it.kind.name.lowercase())
                                .put("enabled", settings.reviewEnabled(it.id))
                        }
                    )
                )
                // So the screen can say "nothing is switched on yet" rather
                // than drawing an empty list that looks like a failed scan.
                .put("anyZoneEnabled", !settings.noZonesChosen)
        )
    }

    /**
     * Turn things on and off.
     *
     * GATED, AND THAT IS THIS REPO'S STANDING RULE RATHER THAN A JUDGEMENT
     * ABOUT ROOM NAMES. A new route that writes goes behind
     * [Access.mayConfigure] in the same change that adds it. Loopback is
     * trusted without a PIN, so on the Android build the app's own WebView
     * configures freely; a browser across the house needs the PIN, exactly as
     * it does for a webhook.
     *
     * PARTIAL BY DESIGN. The body names only what changed, so two people with
     * the page open cannot overwrite each other's unrelated choices with a
     * stale snapshot of the whole thing.
     */
    private fun settingsWrite(request: Request): Response {
        if (!access.mayConfigure(request)) return needsPin()
        val body = Json.body(request)
        var settings = settingsStore.read()

        body.optJSONObject("services")?.let { services ->
            for (id in services.keys()) {
                settings = settings.withService(id, services.bool(id))
            }
        }
        body.optJSONObject("zones")?.let { zones ->
            for (id in zones.keys()) {
                settings = settings.withZone(id, zones.bool(id))
            }
        }
        body.optJSONObject("reviews")?.let { reviews ->
            for (id in reviews.keys()) {
                // Only ids this version knows. A stored set is materialised
                // from the defaults on first touch, so letting an unknown name
                // in would put a source in the file that nothing can ever draw
                // or switch off again.
                if (id in Reviews.IDS) settings = settings.withReview(id, reviews.bool(id))
            }
        }

        settingsStore.write(settings)
        // Answered WITHOUT the zone list: flipping a switch must not trigger a
        // discovery sweep, and the page already has the list it drew from.
        return settingsRead()
    }

    /**
     * What the card is about.
     *
     * The zone is chosen here rather than by the page, because the choice needs
     * the transport state of every room and the page should not be making a
     * round trip per speaker to find out.
     *
     * TWO QUESTIONS, AND THEY ARE NOT THE SAME ONE. `zone=` asks about ONE
     * room and is answered about that room, silence included — [Sources.inZone].
     * Left off, it asks what is on in the house and [Sources.nowPlaying] walks
     * its ladder to find the best answer anywhere. This comment claimed the
     * first behaviour while the code did the second for both, which is how a
     * card for an idle WiiM came back describing a Roon zone.
     */
    private fun nowPlaying(request: Request): Response {
        // EMPTY IS NOT A ZONE. "Whatever's playing" sends no zone at all, and
        // a named one is a lock rather than a hint — see [Sources.inZone].
        val wanted = request.param("zone")?.takeIf { it.isNotBlank() }
        sources.preferredZoneId = wanted
        if (request.param("refresh") == "1") sources.refresh()

        // MORE THAN ONE ROOM ON MEANS THERE IS A CHOICE TO MAKE, and the app
        // should not make it. Answered as a single card, "whatever's playing"
        // had to pick one and silently discard the rest; answered as a grid,
        // the person looking picks. One room on is not a choice — a grid of
        // one tile costs a tap and shows nothing the card would not — so that
        // still draws the card, and so does a house where everything is paused.
        if (wanted == null) {
            val rooms = sources.rooms()
            if (rooms.count { it.playing?.state?.isPlaying == true } > 1) {
                return Json.obj(chooserJson(rooms))
            }
        }

        val playing = if (wanted != null) sources.inZone(wanted) else sources.nowPlaying(null)
        if (playing == null || playing.isEmpty) {
            return Json.obj(
                JSONObject()
                    .put("playing", false)
                    .put("reason", reasonForNothing(wanted))
                    // Something the user can act on beats a description of the
                    // symptom — a first Roon run is not a broken app, it is one
                    // waiting to be let in.
                    .put("notices", Json.strings(sources.notices()))
            )
        }

        // A named zone stays named. An unnamed one is NOT pinned to whatever
        // answered this time: "whatever's playing" has to keep meaning that,
        // and pinning it made the next refresh answer about a room the user
        // never chose.
        /*
         * REMEMBERED AFTER THE ANSWER IS BUILT, NEVER BEFORE IT.
         *
         * This is the one place that knows a card was really drawn for a real
         * record, which is what makes it the right place — and it is handed to
         * another thread so that nothing about the card waits on a file. See
         * [PlayHistory] for what is kept and what deliberately is not.
         */
        val answer = cardJson(playing).put("notices", Json.strings(sources.notices()))
        val artist = playing.artist
        val album = playing.album
        if (artist.isNotBlank()) {
            // Catch Throwable, not Exception, at both levels: a rejected
            // execution and a class that will not initialise are both Errors,
            // and neither may take a card down with it. Remembering is the
            // least important thing this method does.
            try {
                remembering.execute {
                    try {
                        history.remember(artist, album)
                    } catch (t: Throwable) {
                        Log.w(TAG, "could not remember $artist: $t")
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "could not queue the history write: $t")
            }
        }
        return Json.obj(answer)
    }

    /**
     * Why there is no card, in the terms the question was asked in.
     *
     * A named room that is silent is not "nothing is playing" — the house may
     * be full of music. It is that ROOM that is quiet, and saying so is the
     * difference between an answer and a shrug.
     */
    private fun reasonForNothing(zoneId: String?): String = when {
        /*
         * "NO PLAYERS FOUND" IS A LIE WHEN THE PLAYERS ARE SIMPLY SWITCHED OFF,
         * and it is the worst possible lie here: it sends somebody to the
         * network — hosts.txt, multicast, VLANs — for a problem whose fix is
         * two taps in Settings. Rooms are opt-in, so a first run finds plenty
         * and may show none, and the message has to say which of those it is.
         */
        settingsStore.read().noZonesChosen && sources.allZones().isNotEmpty() ->
            "No rooms are switched on yet. Open Settings \u2192 Zones and choose which to show."
        settingsStore.read().noZonesChosen ->
            "No players found yet. They appear in Settings \u2192 Zones as they answer."
        !sources.anyZones() -> "No players found on the network."
        zoneId == null -> "Nothing is playing."
        else -> {
            val name = sources.zones().firstOrNull { it.id == zoneId }?.name
            if (name != null) "Nothing is playing in $name." else "Nothing is playing there."
        }
    }

    /**
     * The chooser payload: every room, and what each one is playing.
     *
     * `choose` is what the page keys on, rather than counting the rooms
     * itself. The rule for when a grid beats a card lives here, in the module
     * with the tests — a copy of it in app.js would be a second place for it
     * to drift, and nothing on the page can be tested.
     *
     * EVERY room is listed, silent ones included, because "not playing" is an
     * answer. A grid of only the live rooms reads as the others having dropped
     * off the network.
     */
    internal fun chooserJson(rooms: List<Room>): JSONObject = JSONObject()
        .put("choose", true)
        // The page hides the card's own rows on this, and they have nothing to
        // act on, so say plainly there is no card rather than leaving it out.
        .put("playing", false)
        .put(
            "rooms",
            Json.array(
                rooms.map { room ->
                    val playing = room.playing
                    JSONObject()
                        .put("uid", room.zone.id)
                        .put("name", room.zone.name)
                        .put("source", room.zone.source)
                        .put("playing", playing?.state?.isPlaying == true)
                        .putOrNull("album", playing?.album.orEmpty())
                        .putOrNull("artist", playing?.artist.orEmpty())
                        .putOrNull("track", playing?.track.orEmpty())
                        .putOrNull("art", playing?.let(::artPath))
                }
            )
        )
        .put("notices", Json.strings(sources.notices()))

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
                /*
                 * WHETHER THIS ROOM CAN BE ASKED TO QUEUE, DECIDED HERE.
                 *
                 * The page used to test `uid.indexOf("roon:") === 0` — a rule,
                 * in app.js, where nothing can test it and where adding Lyrion
                 * would have meant editing a second copy. Which sources have a
                 * queue is a fact about the sources, so `canQueue` is computed
                 * from the SAME list `/api/queue` dispatches on and the page
                 * simply reads it. Same argument as the chooser's `choose`
                 * flag and Discover's `why`.
                 */
                .put("canQueue", canQueue(playing.zoneId))
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
        return artLink(playing.artUrl)
    }

    /**
     * EVERY PICTURE THIS APP SERVES GOES THROUGH ONE FUNCTION, AND THE SECOND
     * PLACE THAT BUILT THE STRING BY HAND GOT IT WRONG.
     *
     * The parameter is `u`. The Discover grid wrote `url=` — so every sleeve
     * asked for a parameter [artwork] does not read, was answered 400 before
     * [ArtProxy] was ever called, and drew a broken image. Twelve of them a
     * screen, on a feature whose whole point is cover art.
     *
     * WHAT FOUND IT WAS THE DIAGNOSTICS BEING EMPTY. `/api/debug` had no "art"
     * section at all — and `ArtProxy` notes every outcome it has, refusals
     * included, so no notes means it was never reached. The ABSENCE of a
     * report was the report. `CardApiTest` asserts every art URL any route
     * emits goes through here, rather than naming the two it knew about.
     */
    private fun artLink(url: String): String = "/api/art?u=" + urlEncode(url)

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

        val chosen = settingsStore.read()

        /*
         * A SWITCHED-OFF SOURCE IS NOT ASKED, not merely undrawn. Pitchfork is
         * a page fetch behind a rate gate and the Wikipedia blurb is two more,
         * so hiding the chip while still paying for it would be the cosmetic
         * half of what the switch says it does — the same rule the services
         * screen keeps for Qobuz.
         *
         * The metadata lookup is ONE call that returns the year, the album
         * blurb and the artist blurb together, so it still runs when Wikipedia
         * is off: the year is not a review and the card draws it regardless.
         * What the switch controls is whether the words are used.
         */
        val extras = if (fast) metadata.cachedExtras(album, artist)
        else metadata.extras(album, artist)
        val albumBio = extras?.album?.takeIf { chosen.reviewEnabled(Reviews.WIKIPEDIA) }
        val review = when {
            !chosen.reviewEnabled(Reviews.PITCHFORK) -> null
            fast -> pitchfork.cachedReviewFor(album, artist)
            else -> pitchfork.reviewFor(album, artist)
        }

        /*
         * The chips that are a LINK and nothing else — no lookup, no network,
         * so they cost nothing on the fast path and are on screen with the
         * first paint. The page draws whatever is in here rather than knowing
         * the names, so a source added later labels its own chip.
         *
         * EVERY LABEL IS A CONSTANT OUT OF [Reviews] and none is built from
         * the record. The artist ones carried the artist string, and a
         * four-name Roon one in a cell a quarter of a phone wide made the
         * whole row draw as circles — see Reviews.Source.chip.
         */
        val reading = JSONArray()
        fun chip(id: String, url: String?) {
            val name = Reviews.chip(id) ?: return
            url?.let { reading.put(JSONObject().put("name", name).put("url", it)) }
        }
        if (chosen.reviewEnabled(Reviews.ALLMUSIC)) {
            chip(Reviews.ALLMUSIC, Reviews.albumUrl(artist, album))
        }
        if (chosen.reviewEnabled(Reviews.WIKIPEDIA_ARTIST)) {
            // ALREADY FETCHED AND NEVER DRAWN. The metadata lookup has always
            // brought back the artist's article because it comes with the same
            // search; the card does not use it because a card is about a
            // record. Offering it costs no request at all.
            chip(Reviews.WIKIPEDIA_ARTIST, extras?.artist?.url)
        }
        if (chosen.reviewEnabled(Reviews.ALLMUSIC_ARTIST)) {
            chip(Reviews.ALLMUSIC_ARTIST, Reviews.artistUrl(artist))
        }

        return Json.obj(
            JSONObject()
                .put("release", extras?.year?.toString() ?: JSONObject.NULL)
                .putOrNull("bio", albumBio?.description)
                .putOrNull("bioSource", albumBio?.source)
                // The article the blurb was taken from. It was already
                // fetched — Metadata has carried this URL since the port and
                // nothing ever offered it — and the card credits "Wikipedia"
                // in type too small to be a link, which left the one source
                // the words actually came from as the only thing on the page
                // you could not follow.
                .putOrNull("bioUrl", albumBio?.url)
                .put("reading", reading)
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
                // A SWITCHED-OFF SERVICE IS FILTERED HERE, on the server, and
                // not hidden by the page. The page would be the easy place and
                // the wrong one: the chips would still be built, and the Qobuz
                // lookup below would still run for a service nobody wants.
                .put(
                    "links",
                    JSONArray(
                        StreamingLinks.forAlbum(artist, album)
                            .filter { enabledServices.serviceEnabled(it.service) }
                            .map {
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
                // So the page can leave the PIN field out entirely rather than
                // drawing an input nobody needs to fill in.
                .put("needsPin", requirePin)
                // WHAT THIS BUILD IS, ANSWERED ONCE AND EARLY.
                //
                // The version so the settings screen can show which build a
                // bug report came from without opening the diagnostics, and
                // the variant because the two shells do not want the same
                // action row: a container is browsed from somewhere else,
                // where the picture is saved by holding it or right-clicking
                // it, so a Download button there is a third way to do what the
                // browser already does. /api/update/status carries the same
                // variant, but that is fetched for the update bar and may not
                // have landed when the card is drawn.
                .put("version", version)
                .put("variant", (updater?.variant ?: Updater.Variant.ANDROID).wire)
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
        /*
         * SWITCHED OFF MEANS NOT LOOKED UP, and Qobuz is the service where
         * that sentence has teeth. Every other chip is a URL built from the
         * album and the artist — no network at all — but this one reads a page
         * off www.qobuz.com behind a rate gate to resolve a real album id. So
         * turning Qobuz off in Settings has to stop the REQUEST, not just hide
         * the chip it would have upgraded.
         */
        if (!enabledServices.serviceEnabled("qobuz")) {
            return Json.obj(JSONObject().put("url", JSONObject.NULL))
        }
        val q = qobuz ?: return Json.obj(JSONObject().put("url", JSONObject.NULL))
        val url = if (request.param("fast") == "1") q.cachedDeepLink(artist, album)
        else q.deepLink(artist, album)
        return Json.obj(JSONObject().putOrNull("url", url))
    }

    /**
     * Acts worth hearing next, given the one playing.
     *
     * ITS OWN ROUTE, LIKE THE QOBUZ ID AND FOR THE SAME REASON. This is up to
     * six requests to two outside hosts behind rate gates; folding it into
     * /api/extras would hold the whole card back for a row the card does not
     * contain. The page asks for it after the card is already drawn.
     *
     * The ARTIST is what this is about, but the ALBUM is taken too: the
     * MusicBrainz id the lookup wants is a by-product of the metadata search
     * for that record, so naming the album is the difference between a free id
     * and a search of its own. `fast=1` answers from the shelf and opens no
     * socket.
     */
    /**
     * WHAT IS NEW, AND WHY EACH ONE IS ON THE SCREEN.
     *
     * A READ, like everything else that is not a webhook or an update: it
     * reaches two public endpoints and returns titles, artists and sleeve
     * URLs. NOTHING EDITORIAL COMES BACK THROUGH IT. What has been written
     * about a record stays a link to whoever wrote it — the screen draws the
     * sleeve, the tap opens the source — which is the whole reason this can be
     * "our own page" without being anybody else's article.
     *
     * The sleeves go through the ART PROXY like the card's does, so the page
     * stays same-origin and the host allowlist still applies to a URL that
     * arrived over the network.
     */
    private fun newMusicRoute(request: Request): Response {
        val engine = newMusic ?: return Json.obj(JSONObject().put("picks", JSONArray()))
        // Refresh forces, which is the bargain every source here makes: a
        // screen that is an hour old is one tap from being a fresh one, and
        // nothing looks again by itself.
        if (request.param("refresh") == "1") engine.forget()
        val picks = runCatching { engine.picks() }
            .onFailure { Log.w(TAG, "could not find new music: ${it.message}") }
            .getOrDefault(emptyList())
        return Json.obj(
            JSONObject().put(
                "picks",
                JSONArray(
                    picks.map {
                        JSONObject()
                            .put("artist", it.artist)
                            .put("album", it.album)
                            .put("released", it.released)
                            .put("why", it.why)
                            .put("heard", it.heard)
                            .putOrNull("art", it.art?.let(::artLink))
                            // A LINK AND NOTHING ELSE. The record was
                            // identified out of a feed; the writing stays with
                            // whoever wrote it, and no route here could return
                            // a word of it.
                            .putOrNull("readAt", it.readAt)
                            .putOrNull("readAtName", it.readAtName)
                    }
                )
            )
        )
    }

    private fun similarActs(request: Request): Response {
        val artist = request.param("artist").orEmpty()
        val album = request.param("album").orEmpty()
        if (artist.isEmpty()) return Json.error(400, "No artist named.")
        val s = similar ?: return Json.obj(JSONObject().put("acts", JSONArray()))
        // Which service the chips point at. The page holds that preference —
        // it is per-device and it is not worth a write on this server — and
        // names it here so the URL is still built where the storefront and
        // encoding rules live. An unknown name falls back rather than failing:
        // the worst case is a link to the first service instead of a 500.
        val service = request.param("service").orEmpty()

        // Switched off in Settings -> Reviews means no review chip on a
        // suggestion either, the same rule the card's own row follows.
        val allmusicOn = settingsStore.read().reviewEnabled(Reviews.ALLMUSIC)

        val fast = request.param("fast") == "1"
        val acts = if (fast) s.cachedForArtist(artist)
        else s.forArtist(artist, metadata.extras(album, artist).artistMbid)

        return Json.obj(
            JSONObject()
                .put("cached", fast)
                .put(
                    "acts",
                    JSONArray(
                        acts.orEmpty().map {
                            JSONObject()
                                .put("name", it.name)
                                .putOrNull("album", it.album)
                                .put("year", it.year ?: JSONObject.NULL)
                                // BUILT HERE, NOT BY THE PAGE. Qobuz's search
                                // needs a storefront segment or it 404s, the
                                // query rides in the path so a space must be
                                // %20, and a slash has to be spent rather than
                                // encoded. Those rules live in StreamingLinks
                                // with a test each; a second copy in app.js is
                                // how they drift. One chip, so one service —
                                // the first, which is the storefront-aware one.
                                .putOrNull("url", linkFor(it.name, it.album, service))
                                /*
                                 * SOMEWHERE TO READ ABOUT IT, NOT ONLY
                                 * SOMEWHERE TO PLAY IT.
                                 *
                                 * Asked for directly: a suggestion should
                                 * offer an album review the way the card does.
                                 * ALLMUSIC IS THE ONE THAT COSTS NOTHING —
                                 * its link is built from the two names with no
                                 * lookup at all, which matters here because
                                 * there are three of these and they are drawn
                                 * after a card that must not wait for them.
                                 * Wikipedia's is the ARTICLE the blurb came
                                 * from and Pitchfork's is a real review, and
                                 * neither exists for a record nobody has
                                 * played: both are a request apiece, and for a
                                 * row of suggestions that is six requests to
                                 * two rate-gated hosts to decorate something
                                 * nobody has tapped yet.
                                 *
                                 * It is a search link, exactly as the card's
                                 * own AllMusic chip is — their album ids are
                                 * opaque and cannot be built from a name — so
                                 * this promises no more than that chip does.
                                 * Switched off in Settings -> Reviews, there is
                                 * no chip: switched off means not offered.
                                 */
                                .putOrNull(
                                    "review",
                                    it.album?.takeIf { allmusicOn }
                                        ?.let { album -> Reviews.albumUrl(it.name, album) }
                                )
                                .put("reviewName", Reviews.chip(Reviews.ALLMUSIC).orEmpty())
                        }
                    )
                )
        )
    }

    /**
     * One search link for a suggested act, on the service the page asked for.
     *
     * BUILT HERE, NOT BY THE PAGE. Qobuz's search 404s without a storefront
     * segment, the query rides in the path for four of the seven so a space
     * must be %20 rather than a plus, and a slash has to be spent rather than
     * encoded — three rules that already live in [StreamingLinks] with a test
     * each. A second copy of them in app.js is how they drift apart.
     *
     * The act's own name stands in for the album when no record could be
     * named, so a lookup that managed only a name still lands somewhere.
     */
    private fun linkFor(act: String, album: String?, service: String): String? {
        val links = StreamingLinks.forAlbum(act, album ?: act)
        return (links.firstOrNull { it.service == service } ?: links.firstOrNull())?.url
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

    /**
     * IT NO LONGER SAYS "webhooks", because it no longer only guards them.
     * Settings goes through the same gate, and a message naming the wrong
     * screen is how somebody decides the PIN prompt is a bug.
     */
    private fun needsPin(): Response = Json.error(
        401,
        "Enter the PIN shown on the device running Share Card to change settings."
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

        /** Zone ids are prefixed with their source; Roon's is this one. */

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
        val WRITE_ROUTES =
            setOf(
                "/api/webhooks", "/api/settings", "/api/queue",
                "/api/update/check", "/api/update/apply"
            )

        /** The two sources that can be asked to queue, by their zone prefix. */
        const val ROON_SOURCE = "roon"
        const val LYRION_SOURCE = "lyrion"

        /** The methods a route that changes nothing may be asked with. */
        val READ_METHODS = setOf("GET", "HEAD")
    }
}
