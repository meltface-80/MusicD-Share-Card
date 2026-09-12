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

enum class RoonStage { IDLE, DISCOVERING, CONNECTING, AWAITING_APPROVAL, PAIRED, ERROR }

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
    private val multicastLock: MulticastLock = MulticastLock.NONE
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

    /** Forget the saved address and look again. What Refresh asks for. */
    fun rediscover() = onNet {
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
            publish(
                RoonStatus(
                    RoonStage.ERROR,
                    detail = "No Roon Core found. Check this device is on the same network " +
                        "as the Core."
                )
            )
            net.schedule({ connectOrDiscover() }, 30, TimeUnit.SECONDS)
        }
    }

    private fun openSocket(h: String, p: Int) {
        if (!running.get()) return
        publish(RoonStatus(RoonStage.CONNECTING, coreId, coreName, "Connecting to $h:$p"))
        val ws = MooSocket(http, "ws://$h:$p/api", SocketEvents())
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

    private fun register() {
        val ws = socket.get() ?: return
        backoffMs = BACKOFF_START_MS
        try {
            val info = ws.call(RoonServices.REGISTRY, "info", expect = null)
            info.bodyText?.let { JSONObject(it) }?.let { body ->
                coreId = body.str("core_id").takeIf { it.isNotEmpty() } ?: coreId
                coreName = body.str("display_name").takeIf { it.isNotEmpty() } ?: coreName
            }

            publish(
                RoonStatus(
                    RoonStage.AWAITING_APPROVAL, coreId, coreName,
                    "Enable “${extension.displayName}” in Roon → Settings → Extensions"
                )
            )

            val reginfo = JSONObject()
                .put("extension_id", extension.id)
                .put("display_name", extension.displayName)
                .put("display_version", extension.version)
                .put("publisher", extension.publisher)
                .put("email", extension.email)
                .put("website", extension.website)
                // TRANSPORT only. The remote this was ported from also requires
                // BROWSE, because it walks the library; this app never does, and
                // asking for a permission it will not use is asking the user to
                // grant more than the job needs.
                .put("required_services", JSONArray().put(RoonServices.TRANSPORT))
                .put("optional_services", JSONArray().put(RoonServices.IMAGE))
                .put(
                    "provided_services",
                    // Advertising a service means answering it: Roon subscribes
                    // as soon as it sees one listed. No settings panel is
                    // offered, because there is nothing here to configure.
                    JSONArray().put(RoonServices.PING).put(RoonServices.STATUS)
                )
            coreId?.let { id -> store.tokenFor(id)?.let { reginfo.put("token", it) } }

            // Registration is not a one-shot reply: Roon answers "Registered"
            // only once the user has enabled the extension, which on a first
            // pair can be minutes. Everything after that arrives on the same id.
            val registered = ws.call(RoonServices.REGISTRY, "register", reginfo, expect = "Registered")
            val body = registered.bodyText?.let { JSONObject(it) }
            val id = body?.str("core_id")?.takeIf { it.isNotEmpty() } ?: coreId
            coreId = id
            coreName = body?.str("display_name")?.takeIf { it.isNotEmpty() } ?: coreName
            body?.str("token")?.takeIf { it.isNotEmpty() }?.let { token ->
                if (id != null) store.saveToken(id, token)
            }

            subscribeZones()
            publish(RoonStatus(RoonStage.PAIRED, coreId, coreName, "Paired with ${coreName ?: "Roon"}"))
        } catch (e: Exception) {
            Log.w(TAG, "registration failed: ${e.message}")
            publish(RoonStatus(RoonStage.ERROR, coreId, coreName, e.message ?: "Registration failed"))
            // The socket listener drives the reconnect when the socket itself
            // died. If it is still open, this was a refusal — back off and retry.
            if (socket.get()?.isOpen == true) {
                socket.getAndSet(null)?.close("registration failed")
            }
        }
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
