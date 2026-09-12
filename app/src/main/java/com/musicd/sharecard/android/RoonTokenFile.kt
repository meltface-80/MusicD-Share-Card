package com.musicd.sharecard.android

import android.content.Context
import com.musicd.sharecard.Log
import com.musicd.sharecard.roon.TokenStore
import org.json.JSONObject
import java.io.File

/**
 * Roon's pairing token, kept in the app's own storage.
 *
 * Without this the user would have to enable the extension in Roon → Settings →
 * Extensions on every restart — on a device that is meant to sit in a rack and
 * never be touched.
 *
 * A JSON file rather than SharedPreferences or SQLite: there are three values
 * in it, it is written perhaps twice in the life of an install, and a file can
 * be deleted to unpair without a settings screen to build.
 *
 * THIS IS THE ONLY THING THE APP WRITES, and nothing on the network can reach
 * it: no route touches it, and the server's rule that every route is a read
 * stands unchanged.
 */
class RoonTokenFile(context: Context) : TokenStore {

    private val file = File(context.filesDir, "roon.json")
    private val lock = Any()

    private fun read(): JSONObject = synchronized(lock) {
        try {
            if (file.isFile) JSONObject(file.readText()) else JSONObject()
        } catch (e: Exception) {
            // A corrupt file means pairing again, which is one tap in Roon —
            // far better than refusing to start.
            Log.w(TAG, "could not read ${file.name}: ${e.message}")
            JSONObject()
        }
    }

    private fun write(json: JSONObject) = synchronized(lock) {
        try {
            file.writeText(json.toString())
        } catch (e: Exception) {
            // Losing the token costs one re-approval; failing here must not
            // take the Roon connection down with it.
            Log.w(TAG, "could not write ${file.name}: ${e.message}")
        }
    }

    override fun tokenFor(coreId: String): String? =
        read().optJSONObject("tokens")?.optString(coreId)?.takeIf { it.isNotEmpty() }

    override fun saveToken(coreId: String, token: String) {
        val json = read()
        val tokens = json.optJSONObject("tokens") ?: JSONObject()
        tokens.put(coreId, token)
        write(json.put("tokens", tokens))
        Log.i(TAG, "paired with Roon core $coreId")
    }

    override fun lastCore(): Pair<String, Int>? {
        val json = read()
        val host = json.optString("host").takeIf { it.isNotEmpty() } ?: return null
        val port = json.optInt("port", 0)
        return if (port > 0) host to port else null
    }

    override fun saveLastCore(host: String, port: Int) {
        write(read().put("host", host).put("port", port))
    }

    override fun forgetLastCore() {
        write(read().put("host", "").put("port", 0))
    }

    private companion object {
        const val TAG = "RoonToken"
    }
}
