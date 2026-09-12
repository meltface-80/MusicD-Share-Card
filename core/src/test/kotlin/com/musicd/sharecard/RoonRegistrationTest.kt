package com.musicd.sharecard

import com.musicd.sharecard.roon.Moo
import com.musicd.sharecard.roon.MooSocket
import com.musicd.sharecard.roon.RoonClient
import com.musicd.sharecard.roon.RoonServices
import com.musicd.sharecard.roon.RoonStage
import com.musicd.sharecard.roon.TokenStore
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Pairing with a Roon Core that nobody has approved yet.
 *
 * THIS IS THE SIX-EXTENSIONS BUG. Roon's Settings → Extensions list filled up
 * with copies of this app, all of them named "MusicD Share Card", so the entry
 * to enable was a guess.
 *
 * The cause was a deadline on a wait that has no deadline. `register` went
 * through `MooSocket.call`, which gives up after ninety seconds; but Roon does
 * not answer `register` at ALL until a human presses Enable, which can be
 * minutes or a day. So the first pair always failed — not sometimes, always —
 * leaving a closed socket and "Roon did not answer … in time" on screen. The
 * only way out is the Refresh button, which the app's own Roon notice asks for,
 * and refreshing introduced the extension all over again. Every press, another
 * line in the list.
 *
 * So there are two things to hold: registration must not give up, and Refresh
 * must not restart a conversation that is already going.
 *
 * The fake Core below does what a real un-approved one does — answers `info`,
 * then says nothing. The call deadline is shortened to 400ms so a test does not
 * have to sit out the real ninety seconds.
 */
class RoonRegistrationTest {

    /** A Core that answers `info` and then waits for a human, like the real one. */
    private class FakeCore : WebSocketListener() {

        val connections = AtomicInteger(0)

        /** Held so the fake can hang up: MockWebServer will not shut down
         *  while a WebSocket is still open, and reports it as a test failure
         *  rather than as the tidy-up problem it is. */
        private val open = CopyOnWriteArrayList<WebSocket>()

        /** One entry per `register` REQUEST — i.e. per line in Roon's list. */
        val registers = CopyOnWriteArrayList<String>()

        val sawRegister = CountDownLatch(1)

        /** The conversation the approval belongs to: the FIRST one asked. */
        private val firstRegistration = AtomicReference<Pair<WebSocket, String>?>(null)

        override fun onOpen(webSocket: WebSocket, response: Response) {
            connections.incrementAndGet()
            open.add(webSocket)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            open.remove(webSocket)
        }

        /** Hang up on everything, so the server can be shut down. */
        fun hangUp() {
            for (ws in open) runCatching { ws.close(1000, "test over") }
            open.clear()
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val msg = Moo.parse(bytes.toByteArray()) ?: return
            if (msg.verb != Moo.VERB_REQUEST) return
            val id = msg.requestId.toIntOrNull() ?: return
            when {
                msg.service == RoonServices.REGISTRY && msg.name == "info" ->
                    reply(webSocket, "Success", id, coreJson())

                msg.service == RoonServices.REGISTRY && msg.name == "register" -> {
                    registers.add(msg.requestId)
                    firstRegistration.compareAndSet(null, webSocket to msg.requestId)
                    sawRegister.countDown()
                    // ...and then nothing at all. Somebody has to walk over to
                    // a Roon remote and press Enable.
                }

                msg.service == RoonServices.TRANSPORT && msg.name == "subscribe_zones" ->
                    reply(webSocket, "Subscribed", id, JSONObject().put("zones", JSONArray()))

                else -> reply(webSocket, "Success", id, null)
            }
        }

        /** What pressing Enable in Roon → Settings → Extensions does. */
        fun approve(): Boolean {
            val (ws, id) = firstRegistration.get() ?: return false
            val body = coreJson().put("token", TOKEN)
            return reply(ws, "Registered", id.toInt(), body)
        }

        private fun coreJson() = JSONObject()
            .put("core_id", CORE_ID)
            .put("display_name", "Test Core")

        private fun reply(ws: WebSocket, name: String, id: Int, body: JSONObject?): Boolean {
            val bytes = Moo.encode(
                Moo.VERB_COMPLETE, name, id, body?.toString()?.toByteArray(Charsets.UTF_8)
            )
            return ws.send(bytes.toByteString(0, bytes.size))
        }
    }

    private class MemoryStore(private var core: Pair<String, Int>?) : TokenStore {
        val tokens = ConcurrentHashMap<String, String>()
        override fun tokenFor(coreId: String): String? = tokens[coreId]
        override fun saveToken(coreId: String, token: String) { tokens[coreId] = token }
        override fun lastCore(): Pair<String, Int>? = core
        override fun saveLastCore(host: String, port: Int) { core = host to port }
        override fun forgetLastCore() { core = null }
    }

    private fun extension() = RoonClient.ExtensionInfo(
        id = "com.musicd.sharecard",
        displayName = "MusicD Share Card",
        version = "0.0.0",
        publisher = "test",
        email = "test@example.com",
        website = "https://example.com"
    )

    private fun serverFor(core: FakeCore): MockWebServer {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            // A Dispatcher rather than a queued response: the whole point is to
            // catch a client that keeps coming back, so every connection must
            // be accepted, not just the first.
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().withWebSocketUpgrade(core)
        }
        server.start()
        return server
    }

    private fun waitUntil(millis: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return condition()
    }

    @Test
    fun `a Core that has not approved the extension is asked exactly once`() {
        val core = FakeCore()
        val server = serverFor(core)
        val client = RoonClient(
            MemoryStore(server.hostName to server.port), extension(), callTimeoutMs = DEADLINE
        )
        try {
            client.start()
            assertTrue("the extension never registered at all", core.sawRegister.await(10, TimeUnit.SECONDS))

            // Several times the deadline. If registration is still a call with
            // a timeout, this is where the second, third and fourth copies
            // appear in Roon's Extensions list.
            Thread.sleep(DEADLINE * 8)

            assertEquals(
                "Roon was asked to register ${core.registers.size} times: ${core.registers}",
                1, core.registers.size
            )
            assertEquals("the socket was reopened", 1, core.connections.get())
            assertEquals(RoonStage.AWAITING_APPROVAL, client.status.stage)
        } finally {
            client.stop()
            core.hangUp()
            server.shutdown()
        }
    }

    @Test
    fun `approval arriving long after the deadline still pairs`() {
        val core = FakeCore()
        val server = serverFor(core)
        val store = MemoryStore(server.hostName to server.port)
        val client = RoonClient(store, extension(), callTimeoutMs = DEADLINE)
        try {
            client.start()
            assertTrue("the extension never registered at all", core.sawRegister.await(10, TimeUnit.SECONDS))

            // The human takes their time. Well past the deadline — which is the
            // normal case, not an edge one.
            Thread.sleep(DEADLINE * 4)
            assertTrue("the approval went nowhere — that conversation is over", core.approve())

            assertTrue(
                "still ${client.status.stage}: ${client.status.detail}",
                waitUntil(5_000) { client.status.stage == RoonStage.PAIRED }
            )
            // The token is what stops the second run asking again.
            assertEquals(TOKEN, store.tokens[CORE_ID])
        } finally {
            client.stop()
            core.hangUp()
            server.shutdown()
        }
    }

    @Test
    fun `pressing refresh while waiting to be enabled does not register again`() {
        // The page's Roon notice says "then press refresh". Somebody waiting
        // for the extension to appear presses it more than once, and every
        // press used to put another line in Roon's Extensions list.
        val core = FakeCore()
        val server = serverFor(core)
        val client = RoonClient(
            MemoryStore(server.hostName to server.port), extension(), callTimeoutMs = DEADLINE
        )
        try {
            client.start()
            assertTrue("the extension never registered at all", core.sawRegister.await(10, TimeUnit.SECONDS))

            repeat(4) {
                client.rediscover()
                Thread.sleep(250)
            }

            assertEquals(
                "Refresh threw away a healthy connection and went looking again",
                RoonStage.AWAITING_APPROVAL, client.status.stage
            )
            assertEquals(
                "four presses of Refresh made ${core.registers.size} entries in Roon: ${core.registers}",
                1, core.registers.size
            )
            assertEquals("the socket was reopened", 1, core.connections.get())

            // And the registration is still live: approval works after all that.
            assertTrue("the approval went nowhere", core.approve())
            assertTrue(
                "still ${client.status.stage}: ${client.status.detail}",
                waitUntil(5_000) { client.status.stage == RoonStage.PAIRED }
            )
        } finally {
            client.stop()
            core.hangUp()
            server.shutdown()
        }
    }

    @Test
    fun `the shipped call deadline is only a backstop`() {
        // Guards the seam itself: the tests shorten it, the app must not.
        assertEquals(90_000L, MooSocket.DEFAULT_CALL_TIMEOUT_MS)
    }

    private companion object {
        const val CORE_ID = "core-1"
        const val TOKEN = "tok-123"

        /** Short enough that a ninety-second loop becomes a three-second one. */
        const val DEADLINE = 400L
    }
}
