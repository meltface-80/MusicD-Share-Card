package com.musicd.sharecard

import com.musicd.sharecard.api.ArtProxy
import com.musicd.sharecard.api.Assets
import com.musicd.sharecard.api.CardApi
import com.musicd.sharecard.meta.CacheStore
import com.musicd.sharecard.meta.Metadata
import com.musicd.sharecard.meta.Pitchfork
import com.musicd.sharecard.meta.metadataHttpClient
import com.musicd.sharecard.http.Request
import com.musicd.sharecard.settings.SettingsStore
import com.musicd.sharecard.source.Sources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE PAGE'S ASSETS MAY BE KEPT BY A BROWSER, BUT NEVER USED WITHOUT ASKING.
 *
 * They were served `Cache-Control: no-store`, which forbids reuse outright, so
 * every asset came down again on every single visit — MEASURED against the real
 * server in a real browser at **263 KB and 14 requests per visit, identical on
 * a revisit, zero 304s**. The requirement behind that header is real and
 * unchanged: the page is versioned by the APK rather than by its URL, so a
 * browser running yesterday's JavaScript against today's API is a genuine way
 * for this to break after an update, on the one device nobody can see.
 *
 * `no-cache` is the accurate word for that requirement. The browser may keep a
 * copy but must REVALIDATE before every use, so an update is picked up on the
 * very next request — and with an ETag beside it, "ask" costs a 304 with no
 * body instead of the file. Measured after: 75 KB on a revisit.
 *
 * The two directives read as synonyms and are opposites: `no-store` keeps
 * nothing, `no-cache` keeps it and always asks.
 */
class AssetCacheTest {

    private val bundle = mapOf(
        "index.html" to "<!doctype html><title>x</title>".toByteArray(),
        "app.js" to "console.log(1)".toByteArray(),
        "style.css" to "body{color:red}".toByteArray()
    )

    private fun apiFor(files: Map<String, ByteArray>): CardApi {
        val http = metadataHttpClient()
        return CardApi(
            Sources(emptyList()),
            Metadata(http, "test", CacheStore.NONE),
            Pitchfork(http, "test"),
            ArtProxy(http),
            Assets { path -> files[path] },
            "1.0.0",
            settingsStore = SettingsStore.inMemory()
        )
    }

    private fun api() = apiFor(bundle)

    private fun fetch(path: String, ifNoneMatch: String? = null) = api().handle(
        Request(
            "GET", path, emptyMap(),
            if (ifNoneMatch == null) emptyMap() else mapOf("if-none-match" to ifNoneMatch),
            ByteArray(0), false, "127.0.0.1"
        )
    )

    @Test
    fun `an asset may be kept, and must always be revalidated`() {
        val response = fetch("/app.js")
        assertEquals(200, response.status)
        assertEquals("no-cache", response.headers["Cache-Control"])
        assertTrue("an ETag is what makes revalidation cheap", !response.headers["ETag"].isNullOrEmpty())
    }

    @Test
    fun `no-store is gone, and that is the whole change`() {
        // Named because it is the thing that cost 263 KB a visit, and because
        // the two directives are one letter apart in meaning and opposite in
        // effect.
        for (path in listOf("/", "/app.js", "/style.css")) {
            assertNotEquals(
                "$path must not forbid storing outright",
                "no-store",
                fetch(path).headers["Cache-Control"]
            )
        }
    }

    @Test
    fun `an unchanged asset answers 304 with no body at all`() {
        val first = fetch("/app.js")
        val tag = first.headers["ETag"]!!
        val again = fetch("/app.js", tag)
        assertEquals(304, again.status)
        assertEquals("a 304 carrying a body is not saving anything", 0, again.body.size)
        assertEquals(tag, again.headers["ETag"])
    }

    @Test
    fun `a stale tag gets the file, which is the update guarantee`() {
        // THE POINT OF THE WHOLE DESIGN. A browser holding yesterday's script
        // must never be allowed to keep using it.
        val response = fetch("/app.js", "\"something-older\"")
        assertEquals(200, response.status)
        assertEquals("console.log(1)", String(response.body))
    }

    @Test
    fun `different assets never share a tag`() {
        // A tag over the bytes, not over the version: two files in one build
        // must not collide, or one would be served for the other after a
        // revalidation.
        assertNotEquals(fetch("/app.js").headers["ETag"], fetch("/style.css").headers["ETag"])
    }

    @Test
    fun `the tag follows the bytes, so a new build invalidates it`() {
        val before = fetch("/app.js").headers["ETag"]
        val changed = mapOf("app.js" to "console.log(2)".toByteArray())
        val other = apiFor(changed).handle(
            Request("GET", "/app.js", emptyMap(), emptyMap(), ByteArray(0), false, "127.0.0.1")
        )
        assertNotEquals("one byte different must mean a different tag", before, other.headers["ETag"])
    }

    @Test
    fun `a path that escapes the bundle is still refused`() {
        // The tag cache must not have become a second way in.
        assertEquals(404, fetch("/../secret").status)
        assertEquals(404, fetch("/nope.js").status)
    }
}
