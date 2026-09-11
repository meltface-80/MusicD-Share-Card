package com.musicd.sharecard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parsing of the hand-entered player addresses.
 *
 * Pure logic, so it runs as a plain JVM test with no Robolectric — the file read
 * around it is three lines of platform call with nothing a test here could see
 * go wrong.
 */
class SeedHostsTest {

    @Test
    fun `a single address is kept`() {
        assertEquals(listOf("192.168.1.40"), SeedHosts.parse("192.168.1.40"))
    }

    @Test
    fun `a comment line is ignored, so the file can explain itself`() {
        assertEquals(
            listOf("10.0.0.5"),
            SeedHosts.parse("# one address per line\n10.0.0.5\n")
        )
    }

    @Test
    fun `a trailing comment on an address line is ignored`() {
        assertEquals(listOf("10.0.0.5"), SeedHosts.parse("10.0.0.5  # the kitchen"))
    }

    @Test
    fun `commas, spaces and newlines all separate`() {
        assertEquals(
            listOf("192.168.1.40", "192.168.1.41", "10.0.0.5"),
            SeedHosts.parse("192.168.1.40, 192.168.1.41\n10.0.0.5")
        )
    }

    @Test
    fun `a repeated address is stored once`() {
        assertEquals(listOf("10.0.0.5"), SeedHosts.parse("10.0.0.5 10.0.0.5"))
    }

    @Test
    fun `a hostname is refused, the same as the art proxy refuses one`() {
        assertTrue(SeedHosts.parse("sonos.local").isEmpty())
        assertEquals(listOf("10.0.0.5"), SeedHosts.parse("sonos.local, 10.0.0.5"))
    }

    @Test
    fun `nonsense is dropped rather than stored to fail later`() {
        assertTrue(SeedHosts.parse("").isEmpty())
        assertTrue(SeedHosts.parse("   ").isEmpty())
        assertTrue(SeedHosts.parse("192.168.1").isEmpty())
        assertTrue(SeedHosts.parse("192.168.1.999").isEmpty())
        assertTrue(SeedHosts.parse("not an address").isEmpty())
    }

    @Test
    fun `a leading zero is refused rather than read as octal by somebody`() {
        assertFalse(SeedHosts.isIpv4("010.0.0.1"))
        assertTrue(SeedHosts.isIpv4("10.0.0.1"))
        assertTrue(SeedHosts.isIpv4("0.0.0.0"))
    }
}
