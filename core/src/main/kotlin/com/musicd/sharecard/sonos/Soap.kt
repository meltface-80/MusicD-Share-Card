package com.musicd.sharecard.sonos

import com.musicd.sharecard.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** A player answered, but not with what was asked for. */
class SoapError(val status: Int, message: String) : Exception(message)

/**
 * UPnP SOAP over HTTP, which is all a Sonos player speaks.
 *
 * Sonos does not use the control URLs a stock MediaRenderer would, and it is
 * fussy about the envelope: the action must be namespaced to the service type
 * and the SOAPACTION header must repeat both. Getting either wrong is answered
 * with a 500 and an error code rather than anything that names the mistake.
 */
class SoapClient(private val http: OkHttpClient = soapHttpClient()) {

    /**
     * Call [action] on [serviceType] at [controlUrl].
     *
     * Arguments are written in the order given, because UPnP is positional
     * despite looking like it is not — a player will reject an envelope whose
     * arguments are shuffled, and a Map's iteration order is what supplies it.
     */
    fun call(
        controlUrl: String,
        serviceType: String,
        action: String,
        args: List<Pair<String, Any>> = emptyList()
    ): Map<String, String> {
        val body = buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
            append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
            append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">")
            append("<s:Body>")
            append("<u:").append(action).append(" xmlns:u=\"").append(serviceType).append("\">")
            for ((name, value) in args) {
                append('<').append(name).append('>')
                append(escape(value.toString()))
                append("</").append(name).append('>')
            }
            append("</u:").append(action).append('>')
            append("</s:Body></s:Envelope>")
        }

        val request = Request.Builder()
            .url(controlUrl)
            .addHeader("SOAPACTION", "\"$serviceType#$action\"")
            .addHeader("Connection", "close")
            .post(body.toRequestBody(XML_MEDIA_TYPE))
            .build()

        val text: String
        val code: Int
        try {
            http.newCall(request).execute().use { response ->
                code = response.code
                text = response.body?.string().orEmpty()
            }
        } catch (e: Exception) {
            throw SoapError(0, "$action to $controlUrl failed: ${e.message}")
        }

        if (code != 200) {
            // A UPnP fault carries its real reason in an errorCode inside the
            // body; the HTTP status is 500 for all of them.
            val detail = Xml.parse(text)?.let { Xml.find(it, "errorCode") }?.let(Xml::text)
            throw SoapError(code, "$action -> HTTP $code" + (detail?.let { " (UPnP $it)" } ?: ""))
        }

        val root = Xml.parse(text) ?: throw SoapError(code, "$action -> unparseable reply")
        val response = Xml.find(root, "${action}Response")
            ?: throw SoapError(code, "$action -> no ${action}Response in reply")

        val out = LinkedHashMap<String, String>()
        for (child in Xml.children(response)) {
            out[Xml.localName(child)] = child.textContent.orEmpty()
        }
        Log.d(TAG, "$action -> ${out.keys}")
        return out
    }

    private fun escape(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(c)
        }
    }

    private companion object {
        const val TAG = "Soap"
        val XML_MEDIA_TYPE = "text/xml; charset=\"utf-8\"".toMediaType()
    }
}

/**
 * okhttp for the players: short timeouts, nothing held open.
 *
 * The timeouts are deliberately tight. Every one of these calls happens with
 * somebody waiting on a card, and a speaker that has dropped off the network
 * must not hold the whole household scan for the default ten seconds — the
 * scan asks several players in turn and a slow one is simply skipped.
 */
fun soapHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(2, TimeUnit.SECONDS)
    .readTimeout(4, TimeUnit.SECONDS)
    .callTimeout(6, TimeUnit.SECONDS)
    .retryOnConnectionFailure(false)
    .build()
