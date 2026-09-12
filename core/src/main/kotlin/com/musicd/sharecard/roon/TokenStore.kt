package com.musicd.sharecard.roon

/**
 * The one thing this app has to remember between runs.
 *
 * Roon issues a token when the user enables the extension in Settings →
 * Extensions. Without keeping it, that approval would be asked for on every
 * restart — on a device that is meant to sit in a rack and never be touched.
 *
 * THIS IS THE ONLY WRITE IN THE APP, and it is deliberately not reachable from
 * the network: no route touches it, it is written by the Roon client on its own
 * thread, and the server's rule that every route is a read stands unchanged.
 */
interface TokenStore {

    /** The extension token Roon issued for a Core, or null before approval. */
    fun tokenFor(coreId: String): String?

    fun saveToken(coreId: String, token: String)

    /** Last Core address, so a restart reconnects without waiting on discovery. */
    fun lastCore(): Pair<String, Int>?

    fun saveLastCore(host: String, port: Int)

    fun forgetLastCore()

    /** Remembers nothing. The tests use it; so does a build with no storage. */
    companion object {
        val NONE: TokenStore = object : TokenStore {
            override fun tokenFor(coreId: String): String? = null
            override fun saveToken(coreId: String, token: String) {}
            override fun lastCore(): Pair<String, Int>? = null
            override fun saveLastCore(host: String, port: Int) {}
            override fun forgetLastCore() {}
        }
    }
}
