package com.musicd.sharecard.api

import com.musicd.sharecard.http.Response
import org.json.JSONArray
import org.json.JSONObject

/** JSON shapes shared by the endpoint handlers. */
object Json {

    fun obj(body: JSONObject): Response = Response.json(200, body.toString())

    fun error(status: Int, message: String): Response =
        Response.json(status, JSONObject().put("error", message).toString())

    fun array(items: List<JSONObject>): JSONArray =
        JSONArray().also { a -> items.forEach(a::put) }

    fun strings(items: Collection<String>): JSONArray =
        JSONArray().also { a -> items.forEach(a::put) }

    /** A POST body as JSON, or an empty object when there is not one. */
    fun body(request: com.musicd.sharecard.http.Request): JSONObject {
        val text = request.bodyText
        if (text.isBlank()) return JSONObject()
        return try {
            JSONObject(text)
        } catch (e: Exception) {
            // A body that is not JSON is the same as no body: the caller reads
            // the fields it wants and finds them missing.
            JSONObject()
        }
    }

    /** A string field that writes JSON null rather than "" when empty. */
    fun JSONObject.putOrNull(key: String, value: String?): JSONObject =
        put(key, value?.takeIf { it.isNotEmpty() } ?: JSONObject.NULL)
}
