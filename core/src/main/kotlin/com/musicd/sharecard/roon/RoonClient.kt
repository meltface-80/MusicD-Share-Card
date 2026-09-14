package com.musicd.sharecard.roon

import com.musicd.sharecard.Log
import com.musicd.sharecard.str
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * ABSENT is not an error, and that distinction is the whole of it: it means the
 * network was asked and holds no Roon Core. Most people running this app do not
 * run Roon, and telling them so on every screen is noise about a product they
 * have not bought. ERROR is kept for a Core that WAS found and then would not
 * talk — which is worth saying, because it is a thing that can be fixed.
 */
enum class RoonStage { IDLE, DISCOVERING, CONNECTING, AWAITING_APPROVAL, PAIRED, ABSENT, ERROR }

data class RoonStatus(
    val stage: RoonStage,
    val coreId: String? = null,
    val coreName: String? = null,
    val detail: String? = null
)

/**
 * This app's whole relationship with a Roon Core: find it, pair with it, keep a
 * MOO session alive, and hold the zone feed.
 *
 * Ported from MusicD Remote Lite, which speaks the same protocol to the same
 * Cores — and trimmed hard. That app is a REMOTE: it browses, queues, seeks and
 * changes volume. This one makes a picture. So the browse tree, the queue, the
 * transport verbs and the settings panel are all left behind, and what remains
 * is the shortest path to `now_playing`.
 *
 * Lifecycle:
 *   discover -> ws://host:port/api -> registry:1/info -> registry:1/register
 *   -> (the user enables the extension in Roon, once) -> "Registered" + token
 *   -> transport:2/subscribe_zones
 *
 * THE FIRST RUN NEEDS A HUMAN. Roon does not answer `register` until somebody
 * has enabled the extension in Settings → Extensions, and that wait is open
 * ended — so [status] carries AWAITING_APPROVAL and the page says what to do
 * rather than looking broken.
 */
class RoonClient(
    private val store: TokenStore,
    private val extension: ExtensionInfo,
    /** Held while discovery runs; Android filters multicast out of userspace. */
    private val multicastLock: MulticastLock = MulticastLock.NONE,
    /** A seam for the tests — see [MooSocket]. Nothing in the app passes it. */
    private val callTimeoutMs: Long = MooSocket.DEFAULT_CALL_TIMEOUT_MS
) {

    /** How the extension introduces itself in Roon → Settings → Extensions. */
    data class ExtensionInfo(
        val id: String,
        val displayName: String,
        val version: String,
        val publisher: String,
        val email: String,
        val website: String
    )

    /** Android needs a WifiManager.MulticastLock for SOOD replies to arrive. */
    interface MulticastLock {
        fun acquire()
        fun release()

        companion object {
            val NONE = object : MulticastLock {
                override fun acquire() {}
                override fun release() {}
            }
        }
    }

    private val http = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)
        // No read timeout: a MOO socket is idle for as long as nothing in the
        // house changes, and closing it for quietness would mean reconnecting
        // all day.
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(6, TimeUnit.SECONDS)
        .build()

    private val net = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "roon-net").apply { isDaemon = true }
    }

    private val running = AtomicBoolean(false)
    private val socket = AtomicReference<MooSocket?>(null)
    private val zoneStore = ZoneStore()
    private val services = ExtensionServices()

    @Volatile private var backoffMs = BACKOFF_START_MS
    @Volatile private var host: String? = null
    @Volatile private var port: Int = 0
    @Volatile private var coreId: String? = null
    @Volatile private var coreName: String? = null

    @Volatile
    var status: RoonStatus = RoonStatus(RoonStage.IDLE)
        private set

    val isPaired: Boolean
        get() = status.stage == RoonStage.PAIRED && socket.get()?.isOpen == true

    val coreHost: String? get() = host

    fun zones(): List<Zone> = zoneStore.all()

    fun zone(id: String?): Zone? = zoneStore.byId(id)

    /**
     * The live socket, for the one caller that needs to talk past the zones.
     *
     * Null unless this app is actually paired, which is what stops a queue
     * request going out to a Core that has not let it in yet. Nothing else
     * reaches for this: the card's reads all go through [zones].
     */
    fun browseSocket(): MooSocket? = socket.get()?.takeIf { it.isOpen && isPaired }

    /**
     * Album art, straight off the Core's image service.
     *
     * It listens on the same host and port as the extension API over plain
     * HTTP, so the picture never has to travel over the MOO socket. 800px is
     * what the card draws its sharp cover at.
     */
    fun imageUrl(imageKey: String, size: Int = 800): String? {
        val h = host ?: return null
        if (port <= 0) return null
        return "http://$h:$port/api/image/$imageKey" +
            "?scale=fit&width=$size&height=$size&format=image/jpeg"
    }

    // ---------------------------------------------------------------- control

    fun start() {
        if (!running.compareAndSet(false, true)) return
        onNet { connectOrDiscover() }
    }

    fun stop() {
        running.set(false)
        socket.getAndSet(null)?.close("stopping")
        net.shutdownNow()
    }

    /**
     * Forget the saved address and look again. What Refresh asks for.
     *
     * AND IT DOES NOTHING WHILE A SOCKET IS OPEN, which is the other half of
     * the six-extensions bug — see [register]. Waiting to be enabled is a
     * healthy state: the socket is up and the registration is outstanding, so
     * there is nothing to rediscover. Tearing it down and asking again is what
     * put another line in Roon's Extensions list on every press, and the notice
     * on the page asks for exactly that press.
     *
     * Once paired there is nothing to refresh either: zones arrive on a live
     * subscription. So a press only means anything when nothing is connected.
     */
    fun rediscover() = onNet {
        if (socket.get()?.isOpen == true) {
            Log.i(TAG, "refresh ignored: still connected (${status.stage})")
            return@onNet
        }
        store.forgetLastCore()
        host = null
        port = 0
        socket.getAndSet(null)?.close("rediscover")
        backoffMs = BACKOFF_START_MS
        connectOrDiscover()
    }

    /** Connect to an address entered by hand, for a network that filters SOOD. */
    fun connectTo(hostName: String, portNumber: Int) = onNet {
        socket.getAndSet(null)?.close("manual reconnect")
        host = hostName
        port = portNumber
        store.saveLastCore(hostName, portNumber)
        backoffMs = BACKOFF_START_MS
        openSocket(hostName, portNumber)
    }

    /** Submit to the network thread, ignoring work queued after stop(). */
    private fun onNet(body: () -> Unit) {
        if (!running.get()) return
        runCatching { net.execute(body) }
    }

    private fun publish(next: RoonStatus) {
        status = next
        Log.i(TAG, next.stage.name + ": " + (next.detail ?: ""))
        // Roon's own Extensions screen shows this line. It is the one place
        // this app can report itself on a screen that is not its own.
        val pushes = services.setStatus(
            next.detail ?: next.stage.name, next.stage == RoonStage.ERROR
        )
        if (pushes.isNotEmpty()) {
            val ws = socket.get()
            for (push in pushes) runCatching { ws?.push(push.requestId, push.name, push.body) }
        }
    }

    private fun connectOrDiscover() {
        if (!running.get()) return
        val saved = store.lastCore()
        if (saved != null && saved.second > 0) {
            host = saved.first
            port = saved.second
            openSocket(saved.first, saved.second)
            return
        }
        discover()
    }

    private fun discover() {
        if (!running.get()) return
        publish(RoonStatus(RoonStage.DISCOVERING, detail = "Looking for a Roon Core"))
        var found = false
        multicastLock.acquire()
        try {
            Sood.discover(DISCOVERY_MS, fun(core: Sood.Found) {
                if (found) return
                found = true
                host = core.host
                port = core.port
                coreName = core.displayName
                store.saveLastCore(core.host, core.port)
                onNet { openSocket(core.host, core.port) }
            })
        } catch (e: Exception) {
            Log.w(TAG, "discovery failed", e)
        } finally {
            multicastLock.release()
        }
        if (!found && running.get()) {
            // LOOKED ONCE, FOUND NOTHING, STOPPED. This used to reschedule
            // itself every thirty seconds for as long as the app was up, which
            // on a device that is never switched off is a multicast sweep of
            // the house twice a minute, for ever, to answer a question nobody
            // asked — the very thing the no-polling rule exists to forbid, and
            // it had been sitting inside the one source nobody thought to
            // check. Refresh calls rediscover() and starts a fresh look, which
            // is the same bargain every other source here makes.
            publish(
                RoonStatus(
                    RoonStage.ABSENT,
                    detail = "No Roon Core on this network. Press refresh to look again."
                )
            )
        }
    }

    private fun openSocket(h: String, p: Int) {
        if (!running.get()) return
        publish(RoonStatus(RoonStage.CONNECTING, coreId, coreName, "Connecting to $h:$p"))
        val ws = MooSocket(http, "ws://$h:$p/api", SocketEvents(), callTimeoutMs)
        socket.set(ws)
        ws.connect()
    }

    private fun scheduleReconnect() {
        if (!running.get()) return
        val delay = backoffMs
        backoffMs = (backoffMs * 2).coerceAtMost(BACKOFF_MAX_MS)
        net.schedule({
            if (!running.get()) return@schedule
            val h = host
            if (h != null && port > 0) openSocket(h, port) else connectOrDiscover()
        }, delay, TimeUnit.MILLISECONDS)
    }

    private inner class SocketEvents : MooSocket.Events {
        override fun onOpen() {
            onNet { register() }
        }

        override fun onCoreRequest(msg: Moo.Message): ExtensionServices.Reply? {
            val body = msg.bodyText?.takeIf { it.isNotBlank() }
                ?.let { runCatching { JSONObject(it) }.getOrNull() }
            val service = msg.service ?: return null
            val outcome = services.onRequest(service, msg.name, msg.requestId, body) ?: return null
            if (outcome.pushes.isNotEmpty()) {
                val ws = socket.get()
                for (push in outcome.pushes) runCatching { ws?.push(push.requestId, push.name, push.body) }
            }
            return outcome.reply
        }

        override fun onClosed(reason: String) {
            onNet {
                // The subscriptions the Core held were on that socket.
                services.onDisconnected()
                zoneStore.clear()
                publish(RoonStatus(RoonStage.ERROR, coreId, coreName, "Lost the connection: $reason"))
                scheduleReconnect()
            }
        }
    }

    /**
     * Introduce the extension, then WAIT — for as long as it takes.
     *
     * THIS IS WHERE SIX COPIES OF THIS EXTENSION CAME FROM, and the mechanism
     * is not the obvious one. Registration used to go through
     * `ws.call(…, expect = "Registered")`, which gives up after ninety seconds.
     * But Roon does not answer `register` until a human has enabled the
     * extension in Settings → Extensions, and that wait is open ended — so on a
     * first pair the call ALWAYS failed. Not sometimes: always.
     *
     * What that left on screen was "Roon: Roon did not answer
     * com.roonlabs.registry:1/register in time", a closed socket and no
     * reconnect. The only way out of it is the Refresh button — which this
     * app's own Roon notice tells people to press — and [rediscover] introduced
     * the extension again. Every press, another line in Roon's list, all of
     * them named "MusicD Share Card", so the one to enable was a guess.
     *
     * The comment that used to sit here already said Roon "answers Registered
     * only once the user has enabled the extension, which on a first pair can
     * be minutes". It was right; the code under it used a deadline anyway.
     *
     * So the registration is sent and not awaited. The reply handler stays
     * armed for the life of the socket, the socket stays open, and the answer
     * arrives whenever somebody presses Enable — a minute later or tomorrow.
     * One entry, one connection, nothing to press.
     */
    private fun register() {
        val ws = socket.get() ?: return
        backoffMs = BACKOFF_START_MS
        try {
            // `info` IS answered immediately, so this one can be awaited.
            val info = ws.call(RoonServices.REGISTRY, "info", expect = null)
            info.bodyText?.let { JSONObject(it) }?.let { body ->
                coreId = body.str("core_id").takeIf { it.isNotEmpty() } ?: coreId
                coreName = body.str("display_name").takeIf { it.isNotEmpty() } ?: coreName
            }

            val known = coreId?.let { store.tokenFor(it) } != null
            publish(
                RoonStatus(
                    RoonStage.AWAITING_APPROVAL, coreId, coreName,
                    if (known) "Reconnecting to ${coreName ?: "Roon"}"
                    else "Enable \u201C${extension.displayName}\u201D in Roon " +
                        "\u2192 Settings \u2192 Extensions"
                )
            )

            val reginfo = JSONObject()
                .put("extension_id", extension.id)
                .put("display_name", extension.displayName)
                .put("display_version", extension.version)
                .put("publisher", extension.publisher)
                .put("email", extension.email)
                .put("website", extension.website)
                /*
                 * TRANSPORT, and BROWSE as an OPTIONAL service.
                 *
                 * It was TRANSPORT alone, on the grounds that this app reads
                 * and never walks the library — asking for a permission it
                 * will not use is asking for more than the job needs. Queueing
                 * a suggestion needs the library, so BROWSE arrives with it.
                 *
                 * OPTIONAL AND NOT REQUIRED, deliberately. A required service
                 * is a condition of pairing at all: a Core that would not give
                 * it would leave this app unable to read what is playing,
                 * which is the whole product, to support one tap on a
                 * suggestion. Optional means the card keeps working and only
                 * the queueing goes quiet.
                 *
                 * UNVERIFIED, AND IT IS THE RISK IN THIS CHANGE: whether
                 * adding a service to the registration makes Roon ask for the
                 * extension to be enabled again in Settings → Extensions. This
                 * repo's notes have carried that question unanswered since the
                 * port. If it does, the app says so — Source.notice() speaks
                 * for AWAITING_APPROVAL — and one tap in Roon fixes it.
                 */
                .put("required_services", JSONArray().put(RoonServices.TRANSPORT))
                .put(
                    "optional_services",
                    JSONArray().put(RoonServices.IMAGE).put(RoonServices.BROWSE)
                )
                .put(
                    "provided_services",
                    // Advertising a service means answering it: Roon subscribes
                    // as soon as it sees one listed. No settings panel is
                    // offered, because there is nothing here to configure.
                    JSONArray().put(RoonServices.PING).put(RoonServices.STATUS)
                )
            coreId?.let { id -> store.tokenFor(id)?.let { reginfo.put("token", it) } }

            // Sent, NOT awaited. See the comment above.
            ws.send(RoonServices.REGISTRY, "register", reginfo) { msg ->
                when {
                    msg == null -> Unit
                    msg.name == "Registered" -> onRegistered(msg)
                    else -> {
                        // A refusal, which is different from silence: Roon has
                        // an opinion and it is not "wait". Say it and stop —
                        // retrying would put another entry in the list.
                        val detail = msg.bodyText?.take(200).orEmpty()
                        Log.w(TAG, "Roon refused the registration: ${msg.name} $detail")
                        publish(
                            RoonStatus(
                                RoonStage.ERROR, coreId, coreName,
                                "Roon refused this extension (${msg.name})."
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            // Only `info` and the send itself can land here now, and both mean
            // the socket is in trouble rather than the user being slow.
            Log.w(TAG, "could not introduce the extension: ${e.message}")
            publish(RoonStatus(RoonStage.ERROR, coreId, coreName, e.message ?: "Registration failed"))
            if (socket.get()?.isOpen == true) {
                socket.getAndSet(null)?.close("registration failed")
            }
        }
    }

    /** Roon said yes — which may be a minute after asking, or a day. */
    private fun onRegistered(msg: Moo.Message) {
        val body = msg.bodyText?.let { runCatching { JSONObject(it) }.getOrNull() }
        val id = body?.str("core_id")?.takeIf { it.isNotEmpty() } ?: coreId
        coreId = id
        coreName = body?.str("display_name")?.takeIf { it.isNotEmpty() } ?: coreName
        body?.str("token")?.takeIf { it.isNotEmpty() }?.let { token ->
            // Kept so the approval is asked for once and never again.
            if (id != null) store.saveToken(id, token)
        }
        subscribeZones()
        publish(RoonStatus(RoonStage.PAIRED, coreId, coreName, "Paired with ${coreName ?: "Roon"}"))
    }

    private fun subscribeZones() {
        val ws = socket.get() ?: return
        ws.send(
            RoonServices.TRANSPORT, "subscribe_zones", JSONObject().put("subscription_key", 0)
        ) { msg ->
            val json = msg?.bodyText?.let { JSONObject(it) } ?: return@send
            when (msg.name) {
                "Subscribed" -> zoneStore.applySubscribed(json)
                "Changed" -> zoneStore.applyChanged(json)
                "Unsubscribed" -> zoneStore.clear()
            }
        }
    }

    private companion object {
        const val TAG = "Roon"
        const val DISCOVERY_MS = 8_000L
        const val BACKOFF_START_MS = 1_000L
        const val BACKOFF_MAX_MS = 30_000L
    }
}
