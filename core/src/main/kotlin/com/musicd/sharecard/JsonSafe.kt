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
