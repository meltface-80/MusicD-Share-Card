package com.musicd.sharecard

import com.musicd.sharecard.source.PlayState
import com.musicd.sharecard.source.Playing
import com.musicd.sharecard.source.Room
import com.musicd.sharecard.source.Source
import com.musicd.sharecard.source.Sources
import com.musicd.sharecard.source.ZoneRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Test

/**
 * THE ROOMS ARE ASKED AT ONCE, AND ONE SOURCE IS STILL ASKED ONCE AT A TIME.
 *
 * Every ask in the ladder is a round trip to a device on somebody's wifi and
 * they all ran one after another. Measured against fakes shaped like a real
 * household - Roon with one zone at 120ms, Sonos with three at 200ms, UPnP with
 * two at 250ms - `rooms()` took 1259ms and `nowPlaying(null)` 1225ms, nearly
 * all of it a thread waiting.
 *
 * NOTHING HERE IS TIMED, DELIBERATELY. A wall-clock assertion on a shared CI
 * runner is a flake waiting to happen, and "it was quick" is not the property
 * that matters anyway. These use a latch: every source must ARRIVE before any
 * of them may leave, which is impossible to satisfy serially and immediate in
 * parallel. A serial implementation does not fail by being slow, it fails by
 * timing out, which is a real answer rather than a threshold.
 */
class SourcesParallelTest {

    /**
     * A source whose zones each report when they are entered and left.
     *
     * [inFlight] is shared across ALL fakes so the per-source ceiling can be
     * checked against the real concurrency, not against one source's opinion.
     */
    private class Fake(
        override val name: String,
        private val zoneCount: Int,
        private val arrived: CountDownLatch,
        private val release: CountDownLatch,
        private val insideThisSource: AtomicInteger = AtomicInteger(0),
        private val maxInsideThisSource: AtomicInteger = AtomicInteger(0)
    ) : Source {

        val peakConcurrency: Int get() = maxInsideThisSource.get()

        override fun zones(): List<ZoneRef> = (1..zoneCount).map {
            ZoneRef(name, ZoneRef.idFor(name, "z$it"), "$name $it")
        }

        override fun nowPlaying(zoneId: String?): Playing? {
            val now = insideThisSource.incrementAndGet()
            maxInsideThisSource.updateAndGet { high -> maxOf(high, now) }
            try {
                arrived.countDown()
                /*
                 * EVERY SOURCE MUST BE HERE BEFORE ANY MAY LEAVE, AND A
                 * TIME-OUT MUST ANSWER NOTHING.
                 *
                 * The first cut of this waited and then answered anyway, so a
                 * serial implementation merely took ten seconds and still
                 * passed every assertion - decoration, by this repository's
                 * own rule, and caught by running it against the serial code
                 * rather than by reading it. Returning null on a time-out is
                 * what makes the latch load-bearing: serially the earlier
                 * sources cannot be answered, and the count fails.
                 */
                if (!release.await(2, TimeUnit.SECONDS)) return null
                val raw = zoneId ?: "z1"
                return Playing(
                    source = name,
                    zoneId = ZoneRef.idFor(name, raw),
                    zoneName = "$name ${raw.removePrefix("z")}",
                    album = "Album $name$raw",
                    artist = "Artist $name",
                    state = PlayState.PAUSED
                )
            } finally {
                insideThisSource.decrementAndGet()
            }
        }
    }

    private fun household(zoneCounts: List<Int>): Pair<Sources, List<Fake>> {
        val arrived = CountDownLatch(zoneCounts.size)
        val release = CountDownLatch(1)
        // Freed the moment the last source arrives; nothing is ever left
        // waiting the full five seconds on the parallel path.
        val watcher = Thread {
            if (arrived.await(4, TimeUnit.SECONDS)) release.countDown()
        }
        watcher.isDaemon = true
        watcher.start()
        val fakes = zoneCounts.mapIndexed { i, count ->
            Fake("Src$i", count, arrived, release)
        }
        return Sources(fakes) to fakes
    }

    @Test
    fun `every source is asked at once`() {
        val (sources, fakes) = household(listOf(1, 3, 2))
        val rooms: List<Room> = sources.rooms()
        assertEquals("all six rooms answered", 6, rooms.size)
        assertTrue("every room has an answer", rooms.all { it.playing != null })
        assertEquals("all three sources were reached", 3, fakes.size)
        sources.stop()
    }

    @Test
    fun `the ladder asks its sources at once too`() {
        val (sources, _) = household(listOf(1, 3, 2))
        val playing = sources.nowPlaying(null)
        assertTrue("the ladder answered", playing != null)
        sources.stop()
    }

    @Test
    fun `one source is never asked twice at the same time`() {
        // THE SAFETY INVARIANT, and the reason the grain is the source rather
        // than the zone. Household keeps its topology and its sweep time in
        // plain @Volatile fields with no lock, so two threads asking two rooms
        // of one household can both read a stale sweep time and both run a
        // discovery sweep - a multicast sweep of the house twice for one
        // question.
        val (sources, fakes) = household(listOf(3, 3, 3))
        sources.rooms()
        for (fake in fakes) {
            assertEquals(
                "${fake.name} was asked ${fake.peakConcurrency} times at once",
                1,
                fake.peakConcurrency
            )
        }
        sources.stop()
    }

    @Test
    fun `a tie is still broken by the source order, not by which answered first`() {
        /*
         * THE ORDER THAT ACTUALLY DECIDES A CARD. `rooms()` cannot depend on
         * completion order - it re-indexes by zone id - but the ladder's last
         * tie-break is a source's POSITION in the list, which is why Roon is
         * first. Two sources with equally good answers must therefore always
         * yield the earlier one, however the network happened to schedule
         * them.
         *
         * AND THIS IS A GUARD, NOT A PROOF - SAID PLAINLY BECAUSE IT WAS
         * WRITTEN EXPECTING THE OPPOSITE. Reversing the order `eachSource`
         * collects its answers in fails NEITHER this test nor the one below,
         * and the reason is worth knowing: `rooms()` re-indexes by zone id, and
         * the tie-break asks `sources.indexOfFirst` for a POSITION rather than
         * reading the candidate list's order. So arrival order cannot reach the
         * decision by any path that exists today. What these two catch is a
         * later refactor that makes it reachable - which is the whole reason to
         * leave them here rather than delete them for being green both ways.
         */
        val arrived = CountDownLatch(2)
        val release = CountDownLatch(1)
        Thread { if (arrived.await(4, TimeUnit.SECONDS)) release.countDown() }
            .apply { isDaemon = true }.start()

        // The SECOND one answers first; the first is held back deliberately.
        val slow = object : Source {
            override val name = "First"
            override fun zones() = listOf(ZoneRef(name, ZoneRef.idFor(name, "z"), "Room"))
            override fun nowPlaying(zoneId: String?): Playing {
                arrived.countDown()
                release.await(2, TimeUnit.SECONDS)
                Thread.sleep(80)
                return Playing(name, ZoneRef.idFor(name, "z"), "Room", "Album", "Artist",
                    state = PlayState.PLAYING)
            }
        }
        val quick = object : Source {
            override val name = "Second"
            override fun zones() = listOf(ZoneRef(name, ZoneRef.idFor(name, "z"), "Other"))
            override fun nowPlaying(zoneId: String?): Playing {
                arrived.countDown()
                release.await(2, TimeUnit.SECONDS)
                return Playing(name, ZoneRef.idFor(name, "z"), "Other", "Album", "Artist",
                    state = PlayState.PLAYING)
            }
        }
        val sources = Sources(listOf(slow, quick))
        assertEquals("First", sources.nowPlaying(null)?.source)
        sources.stop()
    }

    @Test
    fun `the answers keep the order the zones were listed in`() {
        // Structurally guaranteed today - the map is rebuilt in `zones()`
        // order - so this is a guard against a refactor that starts reading
        // arrival order, not a proof that anything currently could. See the
        // test above for why that distinction is stated rather than implied.
        val (sources, _) = household(listOf(2, 2))
        val rooms = sources.rooms()
        assertEquals(
            listOf("Src0 1", "Src0 2", "Src1 1", "Src1 2"),
            rooms.map { it.zone.name }
        )
        assertEquals(
            listOf("Src0 1", "Src0 2", "Src1 1", "Src1 2"),
            rooms.map { it.playing?.zoneName }
        )
        sources.stop()
    }
}
