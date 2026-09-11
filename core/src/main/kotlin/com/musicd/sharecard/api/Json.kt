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

    /** A string field that writes JSON null rather than "" when empty. */
    fun JSONObject.putOrNull(key: String, value: String?): JSONObject =
        put(key, value?.takeIf { it.isNotEmpty() } ?: JSONObject.NULL)
}
