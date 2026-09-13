package com.musicd.sharecard.roon

import com.musicd.sharecard.Log
import com.musicd.sharecard.str
import com.musicd.sharecard.strOrNull
import org.json.JSONObject
import java.io.File

/**
 * Roon's pairing token, kept in one small JSON file.
 *
 * Without this the user would have to enable the extension in Roon → Settings →
 * Extensions on every restart — on a machine that is meant to sit in a rack and
 * never be touched.
 *
 * A JSON file rather than a database or a preferences API: there are three
 * values in it, it is written perhaps twice in the life of an install, and a
 * file can be deleted to unpair without a settings screen to build.
 *
 * IT LIVES IN :core NOW, AND THE POINT OF THAT IS THE TESTS. It was Android's
 * `RoonTokenFile`, whose only Android-shaped part was asking a Context where
 * `filesDir` is — one line, wrapped around parsing that decides whether a pair
 * survives a restart and that had nothing but the compiler behind it. The
 * Docker build needs the same three values in the same shape, and two copies of
 * a file format is how one of them silently stops reading what the other wrote.
 *
 * NOTHING ON THE NETWORK REACHES THIS. No route touches it, and the server's
 * rule that every route is a read stands unchanged.
 */
open class FileTokenStore(private val file: File) : TokenStore {

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
            file.parentFile?.mkdirs()
            file.writeText(json.toString())
        } catch (e: Exception) {
            // Losing the token costs one re-approval; failing here must not
            // take the Roon connection down with it.
            Log.w(TAG, "could not write ${file.name}: ${e.message}")
        }
    }

    override fun tokenFor(coreId: String): String? =
        read().optJSONObject("tokens")?.strOrNull(coreId)

    override fun saveToken(coreId: String, token: String) {
        val json = read()
        val tokens = json.optJSONObject("tokens") ?: JSONObject()
        tokens.put(coreId, token)
        write(json.put("tokens", tokens))
        Log.i(TAG, "paired with Roon core $coreId")
    }

    override fun lastCore(): Pair<String, Int>? {
        val json = read()
        val host = json.strOrNull("host") ?: return null
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
