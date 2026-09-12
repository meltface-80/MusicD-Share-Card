package com.musicd.sharecard.webhook

import com.musicd.sharecard.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Posting a card to a Discord webhook.
 *
 * THE SERVER POSTS, NOT THE PAGE, and that is a decision rather than an
 * accident. Posting from the browser would mean handing the webhook URL to
 * every device that opens the page — so the secret would travel to a phone, a
 * tablet and anything else that ever loaded the card, and live in each of their
 * caches. Keeping the post server-side means the URL is typed once and never
 * leaves.
 *
 * It also sidesteps the question of whether Discord would allow the request
 * cross-origin at all, which is not a thing to find out in the field.
 */
class DiscordPoster(private val http: OkHttpClient = webhookHttpClient()) {

    /** What Discord said. [ok] is all the page needs; the rest is for the log. */
    data class Outcome(val ok: Boolean, val status: Int, val detail: String)

    /**
     * Send [png] to [webhook], with an optional line of text above it.
     *
     * Discord takes the picture as a multipart file part. `payload_json`
     * carries everything else, and is used rather than plain form fields
     * because it is the form Discord documents and the one that keeps working
     * when a field needs to become an object.
     */
    fun post(webhook: Webhook, png: ByteArray, caption: String): Outcome {
        if (png.isEmpty()) return Outcome(false, 0, "There is no card to post yet.")
        if (png.size > MAX_BYTES) {
            return Outcome(false, 0, "That card is larger than Discord will accept.")
        }

        val payload = buildString {
            append('{')
            append("\"content\":").append(quote(caption.take(MAX_CONTENT)))
            // Suppress @everyone and role pings outright. A card is a picture;
            // it has no business notifying a server, and an album title that
            // happens to contain "@everyone" must not become an announcement.
            append(",\"allowed_mentions\":{\"parse\":[]}")
            append('}')
        }

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("payload_json", payload)
            .addFormDataPart(
                "files[0]", "share-card.png", png.toRequestBody(PNG, 0, png.size)
            )
            .build()

        val request = Request.Builder()
            .url(webhook.url)
            .header("User-Agent", USER_AGENT)
            .post(body)
            .build()

        return try {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                // Discord answers 204 with no body on success, and 200 with the
                // message when ?wait=true. Both are fine.
                if (response.isSuccessful) {
                    Log.i(TAG, "posted to ${webhook.name} (${response.code})")
                    Outcome(true, response.code, "Posted to ${webhook.name}.")
                } else {
                    Log.w(TAG, "${webhook.name} -> ${response.code}: ${text.take(200)}")
                    Outcome(false, response.code, explain(response.code, text))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "posting to ${webhook.name} failed: ${e.message}")
            Outcome(false, 0, "Could not reach Discord: ${e.message}")
        }
    }

    /**
     * Turn Discord's status into something worth reading.
     *
     * A raw "404" tells somebody nothing; "that webhook has been deleted" tells
     * them exactly what to do about it.
     */
    private fun explain(status: Int, body: String): String = when (status) {
        401, 403 -> "Discord refused that webhook. It may have been revoked."
        404 -> "That webhook no longer exists in Discord — remove it here and add it again."
        413 -> "Discord says the card is too large."
        429 -> "Discord is rate-limiting this webhook. Try again shortly."
        in 500..599 -> "Discord is having trouble ($status). Try again shortly."
        else -> "Discord refused it ($status)." + body.take(140).let { if (it.isEmpty()) "" else " $it" }
    }

    private fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private companion object {
        const val TAG = "Discord"
        val PNG = "image/png".toMediaType()
        const val USER_AGENT = "MusicDShareCard (https://github.com/meltface-80/New)"

        /** Discord's own limit for a webhook attachment on a free server. */
        const val MAX_BYTES = 8 * 1024 * 1024

        const val MAX_CONTENT = 1800
    }
}

/** Uploading a picture takes longer than asking a speaker a question. */
fun webhookHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .callTimeout(60, TimeUnit.SECONDS)
    .build()
