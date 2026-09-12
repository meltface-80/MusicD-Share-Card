package com.musicd.sharecard.android

import android.content.Context
import com.musicd.sharecard.Log
import com.musicd.sharecard.webhook.Webhook
import com.musicd.sharecard.webhook.WebhookStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom

/**
 * Discord webhooks, kept in the app's own private storage.
 *
 * THE FILE HOLDS CREDENTIALS. It lives in `filesDir`, which is private to this
 * app and not on shared storage — unlike `hosts.txt`, which is deliberately
 * world-editable because a speaker's IP address is not a secret. A webhook URL
 * is, so this one is not reachable by a file manager or by adb pull on an
 * unrooted device.
 *
 * Nothing on the network can read it back either: no route returns a URL, only
 * the mask.
 */
class WebhookFile(context: Context) : WebhookStore {

    private val file = File(context.filesDir, "webhooks.json")
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
            file.writeText(json.toString())
            // Belt and braces on top of the private directory: no group or
            // other access, in case the file was ever created differently.
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
            val id = o.optString("id")
            val url = o.optString("url")
            if (id.isEmpty() || url.isEmpty()) continue
            out += Webhook(id, o.optString("name").ifEmpty { "Discord" }, url)
        }
        return out
    }

    override fun add(webhook: Webhook) {
        val json = read()
        // Replace rather than duplicate: adding the same URL twice is somebody
        // renaming it, not asking for two buttons that post to one channel.
        val kept = all().filter { it.id != webhook.id } + webhook
        json.put("webhooks", JSONArray().also { array ->
            for (w in kept) {
                array.put(
                    JSONObject().put("id", w.id).put("name", w.name).put("url", w.url)
                )
            }
        })
        write(json)
    }

    override fun remove(id: String): Boolean {
        val before = all()
        val kept = before.filter { it.id != id }
        if (kept.size == before.size) return false
        val json = read()
        json.put("webhooks", JSONArray().also { array ->
            for (w in kept) {
                array.put(
                    JSONObject().put("id", w.id).put("name", w.name).put("url", w.url)
                )
            }
        })
        write(json)
        return true
    }

    /**
     * The PIN another device needs in order to add or remove a webhook.
     *
     * Six digits from SecureRandom, minted once and kept. Not derived from
     * anything — a PIN computed from the device id or the install time is a PIN
     * somebody else can compute.
     */
    override fun pin(): String {
        read().optString("pin").takeIf { it.length == PIN_LENGTH }?.let { return it }
        val random = SecureRandom()
        val pin = buildString { repeat(PIN_LENGTH) { append(random.nextInt(10)) } }
        write(read().put("pin", pin))
        Log.i(TAG, "minted a setup PIN")
        return pin
    }

    private companion object {
        const val TAG = "Webhooks"
        const val PIN_LENGTH = 6
    }
}
