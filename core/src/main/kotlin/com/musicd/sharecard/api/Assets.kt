package com.musicd.sharecard.api

/**
 * Where the bundled page comes from.
 *
 * :core is a plain Kotlin module and has no AssetManager, so the Android
 * module supplies one of these over its own assets. A test supplies a map.
 */
fun interface Assets {
    /** The file's bytes, or null when the path is not in the bundle. */
    fun open(path: String): ByteArray?
}

/** Content types for the handful of file kinds the bundle contains. */
object MimeTypes {
    fun of(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "html" -> "text/html; charset=utf-8"
        "js" -> "application/javascript; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "json" -> "application/json; charset=utf-8"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "ico" -> "image/x-icon"
        "woff2" -> "font/woff2"
        else -> "application/octet-stream"
    }
}
