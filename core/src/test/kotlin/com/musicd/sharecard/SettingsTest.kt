package com.musicd.sharecard

import com.musicd.sharecard.settings.FileSettingsStore
import com.musicd.sharecard.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * What is switched on, and what a missing answer means.
 *
 * THE ONE THING WORTH TESTING HERE IS THE ASYMMETRY. Services default ON and
 * zones default OFF, and each set stores the exception to its own default — so
 * a service this version has never heard of appears by itself, while a device
 * discovered next week stays out of the picker until it is asked for. Stored
 * the other way round, both of those invert the moment the file is written,
 * and the symptom is a fortnight later: an upgrade that silently hides every
 * streaming link, or a television that appears in the dropdown unasked.
 */
class SettingsTest {

    @get:Rule
    val temp = TemporaryFolder()

    // ------------------------------------------------------------ defaults

    @Test
    fun `a fresh install has nothing switched on at all`() {
        val fresh = Settings()
        // SERVICES USED TO DEFAULT ON and were reported as a bug on first run:
        // rooms that are opt-in beside services that are opt-out is one rule
        // wearing two faces. Both are opt-in now.
        assertFalse(fresh.serviceEnabled("qobuz"))
        assertFalse(fresh.serviceEnabled("bandcamp"))
        // The whole reason the zones screen exists: nothing answers until it
        // is asked for, so a first run says so rather than showing a card.
        assertFalse(fresh.zoneEnabled("sonos:RINCON_1"))
        assertTrue(fresh.noZonesChosen)
    }

    @Test
    fun `anything nobody has heard of is off`() {
        val configured = Settings(
            enabledServices = setOf("tidal"),
            enabledZones = setOf("sonos:RINCON_1")
        )
        // Added in a later version, named in neither set: it arrives switched
        // off like everything else, which is the duller and more predictable
        // half of the trade made when services became opt-in.
        assertFalse(configured.serviceEnabled("newthing"))
        assertFalse(configured.zoneEnabled("upnp:tv-that-was-off"))
        // And the ones that ARE named say what they were told to.
        assertTrue(configured.serviceEnabled("tidal"))
        assertTrue(configured.zoneEnabled("sonos:RINCON_1"))
    }

    @Test
    fun `switching back and forth leaves no trace`() {
        val once = Settings().withService("qobuz", true).withService("qobuz", false)
        assertEquals(Settings(), once)
        val room = Settings().withZone("roon:1", true).withZone("roon:1", false)
        assertEquals(Settings(), room)
    }

    // --------------------------------------------------------------- on disk

    @Test
    fun `choices survive a restart`() {
        val file = File(temp.root, "settings.json")
        FileSettingsStore(file).write(
            Settings(enabledServices = setOf("amazon"), enabledZones = setOf("roon:1", "sonos:2"))
        )
        val reopened = FileSettingsStore(file).read()
        assertEquals(setOf("amazon"), reopened.enabledServices)
        assertEquals(setOf("roon:1", "sonos:2"), reopened.enabledZones)
    }

    @Test
    fun `no file at all is the defaults, not a failure`() {
        val store = FileSettingsStore(File(temp.root, "never-written.json"))
        assertEquals(Settings(), store.read())
    }

    @Test
    fun `a file from another version falls back to the defaults`() {
        val file = File(temp.root, "settings.json")
        file.writeText("{ this is not json")
        val store = FileSettingsStore(file)
        assertEquals(Settings(), store.read())
        // And it is writable again afterwards: a bad file costs the settings,
        // never the ability to set them.
        store.write(Settings(enabledZones = setOf("roon:1")))
        assertEquals(setOf("roon:1"), FileSettingsStore(file).read().enabledZones)
    }

    @Test
    fun `a json null is not a zone called null`() {
        // Android's org.json hands back the literal text "null" here, which
        // would enable a room named after it.
        val file = File(temp.root, "settings.json")
        file.writeText("""{"enabledZones":["roon:1",null,"  "],"enabledServices":null}""")
        val read = FileSettingsStore(file).read()
        assertEquals(setOf("roon:1"), read.enabledZones)
        assertTrue(read.enabledServices.isEmpty())
    }

    @Test
    fun `a missing directory is created rather than losing the choice`() {
        val file = File(File(temp.root, "never/made"), "settings.json")
        FileSettingsStore(file).write(Settings(enabledZones = setOf("roon:1")))
        assertEquals(setOf("roon:1"), FileSettingsStore(file).read().enabledZones)
    }

    @Test
    fun `nothing is read until something asks`() {
        // Built while Android's startForeground five seconds are running, like
        // every other store here.
        val dir = File(temp.root, "lazy")
        FileSettingsStore(File(dir, "settings.json"))
        assertFalse(dir.exists())
    }
}
