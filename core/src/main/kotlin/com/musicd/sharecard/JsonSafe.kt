package com.musicd.sharecard

import org.json.JSONObject

/**
 * A JSON string field, reading an explicit `null` as absent.
 *
 * `optString` must not be used on anything that came off a network, because
 * the two org.json implementations disagree about JSON null:
 *
 *   {"artist": null}
 *     desktop org.json   -> optString("artist") == ""
 *     Android's org.json -> optString("artist") == "null"   (the LITERAL text)
 *
 * Android's version routes through `JSON.toString`, which is `String.valueOf`
 * for any non-null reference — and `JSONObject.NULL.toString()` is "null".
 *
 * The unit tests here run on the JVM against the desktop implementation, where
 * the bug does not exist, so they cannot catch a slip back to optString.
 * [JsonSafeTest] scans the source instead and fails if one comes back.
 */
fun JSONObject.str(key: String, fallback: String = ""): String =
    if (isNull(key)) fallback else optString(key, fallback)

/** As [str], for a field that is absent, empty or JSON null. */
fun JSONObject.strOrNull(key: String): String? = str(key).takeIf { it.isNotEmpty() }

/**
 * A JSON boolean field, reading the shapes that are not booleans.
 *
 * `optBoolean` IS THE SAME FAMILY OF TRAP AS `optString`. It answers false for
 * the number 1 and for the string "true", and this app has already been bitten
 * once: Lyrion writes its booleans as 1 and 0, so reading `connected` with it
 * marked a whole household asleep — see `LmsClient.truthy`, which is this
 * function's older twin.
 *
 * The settings screen sends real JSON booleans, so nothing is wrong today. It
 * is written this way because the body arrives over the network from a page
 * this app does not get to check, and "the caller is us" has been wrong before.
 */
fun JSONObject.bool(key: String, fallback: Boolean = false): Boolean {
    if (isNull(key)) return fallback
    return when (val value = opt(key)) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> value.trim().lowercase() in TRUTHY
        else -> fallback
    }
}

private val TRUTHY = setOf("true", "1", "yes", "on")
