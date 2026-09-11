package com.musicd.sharecard.sonos

import com.musicd.sharecard.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * A player answered, but not with what was asked for.
 *
 * [detail] is the whole point of this class: it is what gets shown on the
 * diagnostics page. "Would not describe the household" is a symptom, and the
 * difference between a UPnP 401, a read timeout and a reply this code could not
 * parse is the difference between three completely different fixes.
 */
class SoapError(
    val status: Int,
    val detail: String,
    /** The UPnP error code from a SOAP Fault, when the reply carried one. */
    val upnpCode: Int? = null
) : Exception(detail)

/**
 * UPnP SOAP over HTTP, which is all a Sonos player speaks.
 *
 * The envelope and headers here are the same ones
 * [the UPnP-to-Sonos bridge](https://github.com/meltface-80/UPnP-to-Sonos-UPnP-bridge)
 * uses, and that is not a coincidence — it is working code against real
 * players, so where this differed from it, this was wrong. Three things came
 * from reading it after every player in a household refused to answer:
 *
 *  - **Faults are parsed even on a non-200.** UPnP reports action failures as
 *    HTTP 500 with a Fault in the body, so treating any 500 as "unreachable"
 *    throws away the error code that says what actually went wrong.
 *  - **The response wrapper is matched leniently.** The bridge carries the
 *    comment "some devices answer with an unexpected wrapper name" and falls
 *    back to the first child of Body. Insisting on an exact
 *    `<action>Response` turns a usable reply into a hard failure.
 *  - **Ten seconds, not six.** GetZoneGroupState on a real household is a large
 *    document and a player can take its time producing it.
 */
class SoapClient(private val http: OkHttpClient = soapHttpClient()) {

    /**
     * Call [action] on [serviceType] at [controlUrl].
     *
     * Arguments are written in the order given, because UPnP is positional
     * despite looking like it is not — a player rejects an envelope whose
     * arguments are shuffled, and a List preserves what a Map would not.
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
            // An unreachable player is an ordinary condition on a home network.
            // The exception type is kept because "timeout" and "connection
            // refused" point at different problems.
            throw SoapError(
                0,
                "$action: no reply (${e.javaClass.simpleName}: ${e.message})"
            )
        }

        val root = Xml.parse(text)
            ?: throw SoapError(code, "$action: HTTP $code, reply was not XML — ${snippet(text)}")

        // A Fault can arrive with any status, and on UPnP it usually comes with
        // a 500. Parse it before judging the status code, or the error code the
        // player went to the trouble of sending is discarded.
        faultOf(root)?.let { throw it.withAction(action) }

        if (code != 200) {
            throw SoapError(code, "$action: HTTP $code — ${snippet(text)}")
        }

        val body_ = Xml.find(root, "Body")
            ?: throw SoapError(code, "$action: no SOAP Body in the reply — ${snippet(text)}")

        val wrapper = Xml.children(body_).firstOrNull {
            val name = Xml.localName(it)
            name == "${action}Response" || name == action
        // Some devices answer with an unexpected wrapper name — the bridge hit
        // this against real hardware, so the first child of Body is taken
        // rather than failing on a reply that is perfectly usable.
        } ?: Xml.children(body_).firstOrNull()
        ?: throw SoapError(code, "$action: empty SOAP Body — ${snippet(text)}")

        val out = LinkedHashMap<String, String>()
        for (child in Xml.children(wrapper)) {
            out[Xml.localName(child)] = child.textContent.orEmpty()
        }
        Log.d(TAG, "$action -> ${out.keys}")
        return out
    }

    /** A SOAP Fault anywhere in the reply, as a [SoapError]. */
    private fun faultOf(root: org.w3c.dom.Element): SoapError? {
        val fault = Xml.find(root, "Fault") ?: return null
        var code: Int? = null
        var description = ""
        for (node in Xml.descendants(fault)) {
            val value = Xml.text(node)
            when (Xml.localName(node)) {
                "errorCode" -> code = value.trim().toIntOrNull()
                "errorDescription" -> if (value.isNotEmpty()) description = value
                // "UPnPError" is the literal the spec mandates here and it says
                // nothing, so the error code is left to speak instead.
                "faultstring" ->
                    if (description.isEmpty() && value.isNotEmpty() && value != "UPnPError") {
                        description = value
                    }
            }
        }
        val named = code?.let { UPNP_ERRORS[it] }
        val text = listOfNotNull(
            code?.let { "UPnP $it" },
            named,
            description.takeIf { it.isNotEmpty() && it != named }
        ).joinToString(" — ").ifEmpty { "SOAP Fault with no error code" }
        return SoapError(500, text, code)
    }

    private fun SoapError.withAction(action: String) =
        SoapError(status, "$action: $detail", upnpCode)

    /** Enough of a reply to recognise it, without pasting a whole document. */
    private fun snippet(text: String): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= 160) flat else flat.take(160) + "…"
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

        /** The UPnP codes worth naming, from the bridge's own table. */
        val UPNP_ERRORS = mapOf(
            401 to "Invalid Action",
            402 to "Invalid Args",
            412 to "Not authorised",
            501 to "Action Failed",
            600 to "Argument Value Invalid",
            701 to "Transition not available",
            702 to "No contents",
            705 to "Transport is locked",
            718 to "Invalid InstanceID"
        )
    }
}

/**
 * okhttp for the players.
 *
 * TEN SECONDS, matching the bridge, and up from six. GetZoneGroupState on a
 * real household is a large document and a player under load takes its time —
 * a timeout here is indistinguishable, from the outside, from a device that is
 * not a Sonos at all, which is exactly the wrong conclusion to be pushed
 * towards.
 */
fun soapHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(4, TimeUnit.SECONDS)
    .readTimeout(10, TimeUnit.SECONDS)
    .callTimeout(12, TimeUnit.SECONDS)
    .retryOnConnectionFailure(false)
    .build()
