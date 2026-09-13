package com.musicd.sharecard.lms

import com.musicd.sharecard.Log
import com.musicd.sharecard.str
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lyrion Music Server, over the JSON-RPC endpoint it has always published.
 *
 * ONE ENDPOINT, ONE SHAPE. Everything goes to `POST /jsonrpc.js` as
 *
 *     {"id":1,"method":"slim.request","params":[<player>,[<command>,<args>…]]}
 *
 * and the answer arrives under `result`. `<player>` is a player's id — its MAC
 * address — or the empty string for a question about the server itself. That is
 * the whole protocol this app needs; LMS's CLI has hundreds of commands and
 * this asks two of them.
 *
 * WHY NOT THE TELNET CLI. LMS also speaks a line-based protocol on 9090, which
 * is where most of its documentation points. It is stateful, it URI-encodes its
 * fields, and it would need a connection held open — against an app whose whole
 * rule is that it asks only when somebody is looking. JSON-RPC is the same
 * command set as one stateless request.
 *
 * WHAT IS UNVERIFIED. The network here cannot reach a Lyrion server, so the
 * wire shapes below are written from the documented command set and NOT from an
 * observed response. The parsing is therefore deliberately lenient — every
 * field is looked for in more than one place — and [LmsSource.diagnostics] says
 * what was asked and what came back, so the first real run reports the truth
 * rather than an empty card. See the Deezer note in CLAUDE.md for the same
 * lesson learned the expensive way.
 */
class LmsClient(private val http: OkHttpClient) {

    /** One player, as the picker needs it. */
    data class Player(val id: String, val name: String, val connected: Boolean)

    /**
     * Ask one question and hand back `result`, or null.
     *
     * Null covers every failure alike — refused, timed out, not JSON, no
     * `result` — because a source that cannot answer is the same fact to the
     * caller however it failed. The reason is logged and shown in diagnostics.
     */
    fun request(base: String, player: String, command: List<Any>): JSONObject? {
        val body = JSONObject()
            .put("id", 1)
            .put("method", "slim.request")
            .put("params", JSONArray().put(player).put(JSONArray(command)))
        return try {
            val call = Request.Builder()
                .url(base.trimEnd('/') + "/jsonrpc.js")
                .post(body.toString().toRequestBody(JSON_TYPE))
                .build()
            http.newCall(call).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "$base answered ${response.code} to ${command.firstOrNull()}")
                    return null
                }
                val text = response.body?.string().orEmpty()
                JSONObject(text).optJSONObject("result")
            }
        } catch (e: Throwable) {
            // Throwable, not Exception: a class that fails to initialise throws
            // an Error, and one source falling over must not end the request.
            Log.w(TAG, "$base would not answer ${command.firstOrNull()}: ${e.message}")
            null
        }
    }

    fun players(base: String): List<Player> =
        readPlayers(request(base, "", listOf("players", "0", MAX_PLAYERS)))

    /**
     * What one player is doing.
     *
     * `"-"` is LMS's own word for "the track playing now" and `1` asks for that
     * one item. The tag string is what decides which fields come back at all:
     * ask for too few and the album is simply absent, which reads exactly like
     * a player with no album.
     */
    fun status(base: String, player: String): JSONObject? =
        request(base, player, listOf("status", "-", 1, "tags:$TAGS"))

    internal companion object {
        const val TAG = "Lms"

        /** The port Lyrion serves its web interface and this endpoint on. */
        const val DEFAULT_PORT = 9000

        private const val MAX_PLAYERS = 999
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

        /**
         * The fields asked for, and every one of them is load-bearing:
         *
         *   a  artist        l  album         K  artwork_url
         *   c  coverid       J  artwork id    x  remote (a stream, not a file)
         *   d  duration      t  track number  N  a remote stream's own title
         *
         * Asked for generously on purpose. A tag LMS does not know is ignored,
         * and an absent field is indistinguishable from a record that has none.
         */
        const val TAGS = "aKclJxdtN"

        /**
         * Every player LMS knows, from a `players` answer.
         *
         * DISCONNECTED PLAYERS ARE KEPT. A Squeezebox that is asleep is still a
         * room in the house, and dropping it would make the picker's contents
         * depend on what happened to be powered on — see the rule that a silent
         * room is listed rather than dropped.
         */
        internal fun readPlayers(result: JSONObject?): List<Player> {
            val loop = result?.optJSONArray("players_loop") ?: return emptyList()
            val out = ArrayList<Player>(loop.length())
            for (i in 0 until loop.length()) {
                val row = loop.optJSONObject(i) ?: continue
                val id = row.str("playerid").ifEmpty { row.str("id") }
                if (id.isEmpty()) continue
                out += Player(
                    id = id,
                    name = row.str("name").ifEmpty { id },
                    connected = truthy(row.opt("connected"))
                )
            }
            return out
        }

        /**
         * LMS writes its booleans as 1 and 0, and sometimes as real booleans.
         *
         * `optBoolean` reads the number 1 as false, which would mark every
         * connected player disconnected — a whole household of rooms that look
         * asleep.
         */
        internal fun truthy(value: Any?): Boolean = when (value) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> value == "1" || value.equals("true", ignoreCase = true)
            else -> false
        }
    }
}
