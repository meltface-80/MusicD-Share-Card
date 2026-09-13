package com.musicd.sharecard.webhook

import com.musicd.sharecard.Log
import com.musicd.sharecard.str
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom

/**
 * Discord webhooks, kept in one JSON file.
 *
 * THE FILE HOLDS CREDENTIALS. On Android it is `filesDir`, private to the app
 * and not on shared storage — unlike `hosts.txt`, which is deliberately
 * world-editable because a speaker's IP address is not a secret. In a container
 * it is the mounted data directory, which is the operator's to protect; the
 * file itself is written owner-only either way.
 *
 * Nothing on the network can read it back: no route returns a URL, only the
 * mask. See [Webhook.masked].
 *
 * IT LIVES IN :core NOW so both shells share one file format and one set of
 * tests — see [com.musicd.sharecard.roon.FileTokenStore] for the same reasoning
 * at more length.
 */
open class FileWebhookStore(private val file: File) : WebhookStore {

    private val lock = Any()

    private fun read(): JSONObject = synchronized(lock) {
        try {
            if (file.isFile) JSONObject(file.readText()) else JSONObject()
        } catch (e: Exception) {
            Log.w(TAG, "could not read ${file.name}: ${e.message}")
            JSONObject()
        }
    }

    private fun write(json: JSONObject) = synchronized(lock) {
        try {
            file.parentFile?.mkdirs()
            file.writeText(json.toString())
            // Belt and braces on top of a private directory: no group or other
            // access, in case the file was ever created differently.
            runCatching {
                file.setReadable(false, false)
                file.setReadable(true, true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not write ${file.name}: ${e.message}")
        }
    }

    override fun all(): List<Webhook> {
        val array = read().optJSONArray("webhooks") ?: return emptyList()
        val out = ArrayList<Webhook>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val id = o.str("id")
            val url = o.str("url")
            if (id.isEmpty() || url.isEmpty()) continue
            out += Webhook(
                id,
                o.str("name").ifEmpty { "Discord" },
                url,
                o.str("username"),
                o.str("avatarUrl")
            )
        }
        return out
    }

    override fun add(webhook: Webhook) {
        val json = read()
        // Replace rather than duplicate: adding the same URL twice is somebody
        // renaming it, not asking for two buttons that post to one channel.
        val kept = all().filter { it.id != webhook.id } + webhook
        json.put("webhooks", rows(kept))
        write(json)
    }

    override fun remove(id: String): Boolean {
        val before = all()
        val kept = before.filter { it.id != id }
        if (kept.size == before.size) return false
        write(read().put("webhooks", rows(kept)))
        return true
    }

    private fun rows(webhooks: List<Webhook>) = JSONArray().also { array ->
        for (w in webhooks) {
            array.put(
                JSONObject()
                    .put("id", w.id).put("name", w.name).put("url", w.url)
                    .put("username", w.username).put("avatarUrl", w.avatarUrl)
            )
        }
    }

    /**
     * The PIN another device needs in order to add or remove a webhook.
     *
     * Six digits from SecureRandom, minted once and kept. Not derived from
     * anything — a PIN computed from the device id or the install time is a PIN
     * somebody else can compute.
     */
    override fun pin(): String {
        read().str("pin").takeIf { it.length == PIN_LENGTH }?.let { return it }
        val random = SecureRandom()
        val pin = buildString { repeat(PIN_LENGTH) { append(random.nextInt(10)) } }
        write(read().put("pin", pin))
        Log.i(TAG, "minted a setup PIN")
        return pin
    }

    protected companion object {
        const val TAG = "Webhooks"
        const val PIN_LENGTH = 6
    }
}
