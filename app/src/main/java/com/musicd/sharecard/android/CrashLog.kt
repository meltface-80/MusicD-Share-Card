package com.musicd.sharecard.android

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the stack trace of the last crash, so the app can say what happened.
 *
 * WHY THIS EXISTS. This app was reported as "crashing/closing but without any
 * message", from a device in another room with no adb attached — and an Android
 * process killed by the system does not even leave a dialog behind. There was
 * no way to tell a Java exception from an out-of-memory kill from the
 * foreground-service timeout, and those need three different fixes.
 *
 * So the app catches its own crashes and writes them down. The next launch shows
 * the trace on screen and serves it at /api/debug, which turns "it just closes"
 * into something somebody can photograph and send.
 *
 * The previous handler is always chained to. Replacing it outright would stop
 * the system recording the crash at all, which trades one blind spot for
 * another.
 */
object CrashLog {

    private const val TAG = "CrashLog"
    private const val FILE_NAME = "last-crash.txt"

    /** Trimmed so a deep recursive trace cannot fill the disk. */
    private const val MAX_CHARS = 24_000

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                write(appContext, thread, error)
            } catch (e: Throwable) {
                // Never let the crash handler become the crash.
                Log.e(TAG, "could not record the crash", e)
            }
            // The system handler still runs: it is what actually ends the
            // process, and skipping it leaves a half-dead app on screen.
            previous?.uncaughtException(thread, error)
        }
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val when_ = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.UK).format(Date())
        val text = buildString {
            append("MusicD Share Card ").append(BuildConfig.VERSION_NAME).append('\n')
            append(when_).append(" on thread \"").append(thread.name).append("\"\n")
            append("Android ").append(android.os.Build.VERSION.SDK_INT)
            append(" · ").append(android.os.Build.MANUFACTURER).append(' ')
            append(android.os.Build.MODEL).append("\n\n")
            append(trace)
        }.take(MAX_CHARS)
        File(context.filesDir, FILE_NAME).writeText(text)
        Log.e(TAG, "recorded a crash on \"${thread.name}\"", error)
    }

    /** The last recorded crash, or null. */
    fun read(context: Context): String? = try {
        val file = File(context.filesDir, FILE_NAME)
        if (file.isFile) file.readText().takeIf { it.isNotBlank() } else null
    } catch (e: Exception) {
        Log.w(TAG, "could not read the crash log", e)
        null
    }

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE_NAME).delete() }
    }
}
