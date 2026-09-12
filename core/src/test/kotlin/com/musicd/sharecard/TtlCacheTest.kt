package com.musicd.sharecard

import com.musicd.sharecard.meta.CacheStore
import com.musicd.sharecard.meta.TtlCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cache, and the shelf it can now be written down on.
 *
 * THE FIRST TEST HERE IS THE IMPORTANT ONE. Persistence is opt-in, and a cache
 * built the way every cache in this app was built before it existed has to
 * behave exactly as it did — no file touched, no behaviour changed. The art
 * bytes and Pitchfork's hourly index still pass nothing, and they must stay
 * exactly as fast and exactly as forgetful.
 */
class TtlCacheTest {

    /** A shelf in memory, counting what it was asked to do. */
    private class FakeStore : CacheStore {
        val shelves = HashMap<String, MutableMap<String, String>>()
        var writes = 0
        var reads = 0
        var failOnPut = false

        override fun load(namespace: String): Map<String, String> {
            reads++
            return shelves[namespace].orEmpty().toMap()
        }

        override fun put(namespace: String, key: String, value: String) {
            if (failOnPut) throw IllegalStateException("no space left on device")
            writes++
            shelves.getOrPut(namespace) { LinkedHashMap() }[key] = value
        }

        override fun remove(namespace: String, key: String) {
            shelves[namespace]?.remove(key)
        }
    }

    private fun strings(store: CacheStore, ttl: Long = 60_000, max: Int = 8) =
        TtlCache<String, String>(
            ttl, max, TtlCache.Persist(store, "test", { it }, { it })
        )

    @Test
    fun `a cache with no shelf touches nothing and behaves as it always did`() {
        val cache = TtlCache<String, String>(60_000, 4)
        assertEquals("one", cache.get("a") { "one" })
        // Computed once, then remembered.
        assertEquals("one", cache.get("a") { "two" })
        assertEquals("one", cache.peek("a"))
        assertNull(cache.peek("b"))
        cache.clear()
        assertNull(cache.peek("a"))
    }

    @Test
    fun `what was looked up once is there after a restart`() {
        val store = FakeStore()
        strings(store).get("massive attack||mezzanine") { "9.3" }

        // A new cache is a new process: nothing in memory, everything on the
        // shelf. Its compute must not run.
        val second = strings(store)
        assertEquals("9.3", second.get("massive attack||mezzanine") {
            throw AssertionError("it went and looked again")
        })
    }

    @Test
    fun `an entry that has expired is not served from the shelf`() {
        val store = FakeStore()
        strings(store, ttl = 60_000).get("k") { "old" }
        // The same shelf, read by a cache that keeps things for a millisecond.
        Thread.sleep(5)
        val strict = strings(store, ttl = 1)
        assertEquals("fresh", strict.get("k") { "fresh" })
    }

    @Test
    fun `a shelf that cannot be written does not break the lookup`() {
        // A full disk, a permission that changed, a file from a version that
        // shaped it differently. Every one of those has to end as "we did not
        // remember that one", never as a failed card.
        val store = FakeStore()
        store.failOnPut = true
        val cache = strings(store)
        assertEquals("still works", cache.get("k") { "still works" })
        assertEquals("still works", cache.peek("k"))
    }

    @Test
    fun `rubbish on the shelf is skipped, not thrown`() {
        val store = FakeStore()
        store.shelves["test"] = linkedMapOf(
            "good" to "${System.currentTimeMillis()}|yes",
            "no-timestamp" to "yes",
            "bad-timestamp" to "not-a-number|yes",
            "empty" to ""
        )
        val cache = strings(store)
        assertEquals("yes", cache.peek("good"))
        assertNull(cache.peek("no-timestamp"))
        assertNull(cache.peek("bad-timestamp"))
        assertNull(cache.peek("empty"))
    }

    @Test
    fun `a value the codec refuses is simply not written down`() {
        val store = FakeStore()
        val cache = TtlCache<String, String>(
            60_000, 8,
            TtlCache.Persist(store, "test", { v -> v.takeIf { it != "half" } }, { it })
        )
        cache.get("a") { "whole" }
        cache.get("b") { "half" }
        assertEquals(setOf("a"), store.shelves["test"]?.keys)
        // And it is still in memory, because refusing to write it down is not
        // the same as refusing to remember it.
        assertEquals("half", cache.peek("b"))
    }

    @Test
    fun `evicted from memory means evicted from the shelf`() {
        // Otherwise the file grows for ever behind a cache that is bounded.
        val store = FakeStore()
        val cache = strings(store, max = 3)
        for (k in listOf("a", "b", "c", "d", "e")) cache.get(k) { k }
        assertEquals(3, cache.size())
        assertTrue("the shelf kept ${store.shelves["test"]?.keys}", (store.shelves["test"]?.size ?: 0) <= 3)
    }

    @Test
    fun `the shelf is read once, not on every lookup`() {
        val store = FakeStore()
        val cache = strings(store)
        cache.get("a") { "1" }
        cache.peek("b")
        cache.get("c") { "3" }
        assertEquals("loading is a file read; it happens once", 1, store.reads)
    }

    @Test
    fun `the shelf is not read while the cache is being built`() {
        // startForeground() has about five seconds and the caches are built
        // inside it — see CardService. A file read in a constructor is exactly
        // the kind of work that window has no room for.
        val store = FakeStore()
        strings(store)
        assertEquals(0, store.reads)
    }
}
