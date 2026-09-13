package com.musicd.sharecard

import com.musicd.sharecard.meta.FileCacheStore
import com.musicd.sharecard.roon.FileTokenStore
import com.musicd.sharecard.webhook.FileWebhookStore
import com.musicd.sharecard.webhook.Webhook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The three things this app writes to disk.
 *
 * THEY HAD NO TESTS AT ALL until they moved into :core, because they lived in
 * `app/`, where the compiler is the only thing that ever looks. Their one
 * Android-shaped line was asking a Context where `filesDir` is; everything else
 * — the file format, what a corrupt file costs, whether a pair survives a
 * restart — was parsing, which is the part that can be wrong. The Docker build
 * reads the same files, so a format that drifts between the two shells is a
 * pairing that silently stops working after a move.
 *
 * The theme of every case here is the same: LOSING THE CONTENTS IS ALLOWED,
 * THROWING IS NOT. A bad token costs one tap in Roon; a bad cache costs one
 * slow lookup. Either of them taking the process down costs the app.
 */
class FileStoresTest {

    @get:Rule
    val temp = TemporaryFolder()

    // --------------------------------------------------------- Roon's token

    @Test
    fun `a pairing survives a restart`() {
        val file = File(temp.root, "roon.json")
        FileTokenStore(file).apply {
            saveToken("core-1", "token-1")
            saveLastCore("192.168.0.9", 9330)
        }
        // A second instance is the restart: nothing is carried in memory.
        val reopened = FileTokenStore(file)
        assertEquals("token-1", reopened.tokenFor("core-1"))
        assertEquals("192.168.0.9" to 9330, reopened.lastCore())
    }

    @Test
    fun `an unknown core has no token`() {
        val store = FileTokenStore(File(temp.root, "roon.json"))
        store.saveToken("core-1", "token-1")
        assertNull(store.tokenFor("core-2"))
    }

    @Test
    fun `forgetting the last core does not forget the token`() {
        val file = File(temp.root, "roon.json")
        val store = FileTokenStore(file)
        store.saveToken("core-1", "token-1")
        store.saveLastCore("192.168.0.9", 9330)
        store.forgetLastCore()
        assertNull(store.lastCore())
        // The token is what stops Roon asking for approval again, and moving
        // house is not a reason to re-approve.
        assertEquals("token-1", FileTokenStore(file).tokenFor("core-1"))
    }

    @Test
    fun `a corrupt token file pairs again rather than throwing`() {
        val file = File(temp.root, "roon.json")
        file.writeText("{ this is not json")
        val store = FileTokenStore(file)
        assertNull(store.tokenFor("core-1"))
        assertNull(store.lastCore())
        // And it is writable again afterwards.
        store.saveToken("core-1", "token-1")
        assertEquals("token-1", FileTokenStore(file).tokenFor("core-1"))
    }

    @Test
    fun `a missing directory is created rather than refused`() {
        // The container mounts /data and the app writes below it; a first run
        // with nothing there must not lose the pairing it just made.
        val file = File(File(temp.root, "never/made"), "roon.json")
        FileTokenStore(file).saveToken("core-1", "token-1")
        assertEquals("token-1", FileTokenStore(file).tokenFor("core-1"))
    }

    @Test
    fun `a json null is not a token and not a host`() {
        // THE ANDROID org.json RETURNS THE LITERAL TEXT "null" HERE and the
        // desktop one does not, which is why JsonSafeTest scans the source
        // rather than running anything. On a device that shipped as a token
        // reading "null" handed to Roon, and a pairing that looks present and
        // is not. These files only came under that scan when they moved into
        // :core; in app/ nothing was looking.
        val file = File(temp.root, "roon.json")
        file.writeText("""{"tokens":{"core-1":null},"host":null,"port":9330}""")
        val store = FileTokenStore(file)
        assertNull(store.tokenFor("core-1"))
        assertNull(store.lastCore())
    }

    // ------------------------------------------------------------- webhooks

    @Test
    fun `webhooks survive a restart and a rename replaces rather than doubles`() {
        val file = File(temp.root, "webhooks.json")
        val store = FileWebhookStore(file)
        store.add(Webhook("a", "Music", "https://discord.com/api/webhooks/1/aaa", "DJ", ""))
        store.add(Webhook("b", "Other", "https://discord.com/api/webhooks/2/bbb", "", ""))
        // Same id: this is somebody renaming it, not asking for two buttons
        // that post to one channel.
        store.add(Webhook("a", "Now Playing", "https://discord.com/api/webhooks/1/aaa", "DJ", ""))

        val reopened = FileWebhookStore(file).all()
        assertEquals(2, reopened.size)
        assertEquals("Now Playing", reopened.single { it.id == "a" }.name)
        assertEquals("Other", reopened.single { it.id == "b" }.name)
        assertEquals("https://discord.com/api/webhooks/1/aaa", reopened.single { it.id == "a" }.url)
    }

    @Test
    fun `removing answers whether there was anything to remove`() {
        val file = File(temp.root, "webhooks.json")
        val store = FileWebhookStore(file)
        store.add(Webhook("a", "Music", "https://discord.com/api/webhooks/1/aaa", "", ""))
        assertFalse(store.remove("b"))
        assertTrue(store.remove("a"))
        assertTrue(FileWebhookStore(file).all().isEmpty())
    }

    @Test
    fun `the PIN is minted once and kept`() {
        val file = File(temp.root, "webhooks.json")
        val first = FileWebhookStore(file).pin()
        assertEquals(6, first.length)
        assertTrue(first.all { it in '0'..'9' })
        // A PIN that changed on restart would be one nobody could ever have
        // written down.
        assertEquals(first, FileWebhookStore(file).pin())
    }

    @Test
    fun `minting the PIN does not lose the webhooks`() {
        val file = File(temp.root, "webhooks.json")
        val store = FileWebhookStore(file)
        store.add(Webhook("a", "Music", "https://discord.com/api/webhooks/1/aaa", "", ""))
        store.pin()
        assertEquals(1, FileWebhookStore(file).all().size)
    }

    @Test
    fun `a webhook with no url is not a webhook`() {
        val file = File(temp.root, "webhooks.json")
        file.writeText("""{"webhooks":[{"id":"a","name":"Music"},{"id":"","url":"https://x"}]}""")
        assertTrue(FileWebhookStore(file).all().isEmpty())
        // Same row with an explicit JSON null, which is the shape Android's
        // org.json turns into the four characters n-u-l-l.
        file.writeText("""{"webhooks":[{"id":"a","name":null,"url":null}]}""")
        assertTrue(FileWebhookStore(file).all().isEmpty())
    }

    // ---------------------------------------------------------- the caches

    @Test
    fun `what a lookup found survives a restart`() {
        val dir = File(temp.root, "cache")
        FileCacheStore(dir).apply {
            put("extras", "spiderland|slint", """{"year":1991}""")
            flush()
        }
        assertEquals(
            mapOf("spiderland|slint" to """{"year":1991}"""),
            FileCacheStore(dir).load("extras")
        )
    }

    @Test
    fun `namespaces do not see each other`() {
        val dir = File(temp.root, "cache")
        val store = FileCacheStore(dir)
        store.put("extras", "k", "1")
        store.put("pitchfork", "k", "2")
        store.flush()
        val reopened = FileCacheStore(dir)
        assertEquals("1", reopened.load("extras")["k"])
        assertEquals("2", reopened.load("pitchfork")["k"])
    }

    @Test
    fun `removing is written out too`() {
        val dir = File(temp.root, "cache")
        val store = FileCacheStore(dir)
        store.put("extras", "k", "1")
        store.flush()
        store.remove("extras", "k")
        store.flush()
        assertTrue(FileCacheStore(dir).load("extras").isEmpty())
    }

    @Test
    fun `a cache file from another version starts empty rather than failing`() {
        val dir = File(temp.root, "cache").apply { mkdirs() }
        File(dir, "extras.json").writeText("[\"not\", \"an object\"]")
        val store = FileCacheStore(dir)
        assertTrue(store.load("extras").isEmpty())
        // And the next lookup still gets remembered.
        store.put("extras", "k", "1")
        store.flush()
        assertEquals("1", FileCacheStore(dir).load("extras")["k"])
    }

    @Test
    fun `nothing is read until something asks`() {
        // THE SHELF IS READ LAZILY. These caches are built while Android's
        // startForeground() five seconds are running, and a file read in there
        // is what killed the app before. Constructing must touch no disk.
        val dir = File(temp.root, "cache")
        FileCacheStore(dir)
        assertFalse(dir.exists())
    }
}
