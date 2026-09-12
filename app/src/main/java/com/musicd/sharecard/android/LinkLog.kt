package com.musicd.sharecard.android

/**
 * The last few links this app handed to Android, and what happened to them.
 *
 * WHY THIS EXISTS. "The Qobuz button opens the app but not the album" has at
 * least three causes that look identical from the outside: the app-scheme
 * intent was never built, it was built and nothing answered it, or it was
 * answered and Qobuz landed on its own Home screen anyway. Telling them apart
 * otherwise means adb on a device that lives in another room.
 *
 * In memory only, bounded, and read through /api/debug alongside the crash log.
 * Nothing here is written to disk and nothing leaves the device that the card
 * itself does not already reveal.
 */
object LinkLog {

    private const val MAX = 10

    private val lines = ArrayList<String>()

    fun note(line: String) {
        synchronized(lines) {
            lines += line
            while (lines.size > MAX) lines.removeAt(0)
        }
    }

    fun lines(): List<String> = synchronized(lines) { lines.toList() }
}
