package com.musicd.sharecard

/**
 * The core module is plain Kotlin/JVM so that the Sonos protocol, the metadata
 * lookups and the API can be compiled and unit-tested without an Android SDK.
 * That rules out android.util.Log, so logging goes through a sink the Android
 * module replaces at startup and the tests leave alone.
 */
object Log {

    interface Sink {
        fun write(level: Char, tag: String, message: String, error: Throwable?)
    }

    /** Discards everything. Replaced by the app with a Logcat-backed sink. */
    @Volatile
    var sink: Sink? = null

    /** Verbose tracing of every SOAP call. Off unless the app turns it on. */
    @Volatile
    var debug: Boolean = false

    fun d(tag: String, message: String) {
        if (debug) sink?.write('D', tag, message, null)
    }

    fun i(tag: String, message: String) = sink?.write('I', tag, message, null)

    fun w(tag: String, message: String, error: Throwable? = null) =
        sink?.write('W', tag, message, error)

    fun e(tag: String, message: String, error: Throwable? = null) =
        sink?.write('E', tag, message, error)
}

/**
 * A throwable as one line that actually says what went wrong.
 *
 * "ExceptionInInitializerError: null" is a real message this app has shown on a
 * device, and it names nothing: the Error itself carries no message and the
 * fault is entirely in its CAUSE. The class that failed to load, and why, is
 * what somebody standing in front of the thing needs — so the chain is walked
 * rather than the top frame printed.
 */
fun describe(t: Throwable): String {
    val out = StringBuilder()
    var cause: Throwable? = t
    var depth = 0
    while (cause != null && depth < 5) {
        if (depth > 0) out.append(" <- ")
        out.append(cause.javaClass.simpleName)
        cause.message?.takeIf { it.isNotBlank() }?.let { out.append(": ").append(it) }
        // The frame in OUR code, which names the class whose initialiser threw.
        cause.stackTrace.firstOrNull { it.className.startsWith("com.musicd.sharecard") }
            ?.let { out.append(" at ").append(it.className.substringAfterLast('.'))
                .append('.').append(it.methodName).append(':').append(it.lineNumber) }
        cause = cause.cause?.takeIf { it !== cause }
        depth++
    }
    return out.toString()
}
