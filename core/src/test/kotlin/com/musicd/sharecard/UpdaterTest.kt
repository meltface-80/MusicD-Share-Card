package com.musicd.sharecard

import com.musicd.sharecard.meta.Updater
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The parts of an update that are not Android.
 *
 * Downloading and installing need a device. Deciding WHETHER to update, and
 * refusing a manifest that should not be trusted, do not — and those are the
 * parts that can be wrong without anybody noticing until an update either never
 * offers itself or offers the wrong thing.
 */
class UpdaterTest {

    private val manifestUrl = "https://raw.githubusercontent.com/o/r/main/dist/latest.json"

    private fun updater(current: String, onInstall: (File) -> Unit = {
        throw AssertionError("no install should happen in these tests")
    }) = Updater(
        http = OkHttpClient(),
        currentVersion = current,
        manifestUrl = manifestUrl,
        downloadDir = Files.createTempDirectory("updater-test").toFile(),
        install = onInstall
    )

    /**
     * A MockWebServer speaking https, with a client that trusts exactly it.
     *
     * The manifest rule is that an APK URL must be https — so testing the happy
     * path over plain http would mean either weakening the rule or not covering
     * the path at all. A throwaway certificate costs less than either.
     */
    private class Tls {
        private val held = okhttp3.tls.HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .build()
        val certificates: okhttp3.tls.HandshakeCertificates =
            okhttp3.tls.HandshakeCertificates.Builder()
                .addTrustedCertificate(held.certificate)
                .heldCertificate(held)
                .build()

        fun server(dispatcher: okhttp3.mockwebserver.Dispatcher): okhttp3.mockwebserver.MockWebServer {
            val server = okhttp3.mockwebserver.MockWebServer()
            server.useHttps(certificates.sslSocketFactory(), false)
            server.dispatcher = dispatcher
            server.start()
            return server
        }

        fun client(): OkHttpClient = OkHttpClient.Builder()
            .sslSocketFactory(certificates.sslSocketFactory(), certificates.trustManager)
            .build()
    }

    private fun dispatcher(
        body: (okhttp3.mockwebserver.RecordedRequest) -> okhttp3.mockwebserver.MockResponse
    ) = object : okhttp3.mockwebserver.Dispatcher() {
        override fun dispatch(
            request: okhttp3.mockwebserver.RecordedRequest
        ): okhttp3.mockwebserver.MockResponse = body(request)
    }

    private fun manifest(
        version: String = "0.14.0",
        url: String = "https://raw.githubusercontent.com/o/r/main/dist/app.apk",
        signed: Boolean = true
    ) = JSONObject()
        .put("version", version)
        .put("url", url)
        .put("sha256", "ab")
        .put("notes", "n")
        .put("signed", signed)

    @Test
    fun `versions compare numerically, not as text`() {
        // The one that bites: as strings "0.1.10" sorts BEFORE "0.1.9", so a
        // tenth release would never offer itself as an update.
        assertTrue(Updater.compareVersions("0.1.10", "0.1.9") > 0)
        assertTrue(Updater.compareVersions("0.2.0", "0.1.99") > 0)
        assertTrue(Updater.compareVersions("1.0.0", "0.9.9") > 0)
        assertEquals(0, Updater.compareVersions("0.13.0", "0.13.0"))
        assertTrue(Updater.compareVersions("0.12.0", "0.13.0") < 0)
        // Shorter is not smaller when the tail is zeros.
        assertEquals(0, Updater.compareVersions("1.0", "1.0.0"))
    }

    @Test
    fun `a manifest needs a version and an https url`() {
        val u = updater("0.13.0")
        assertNull(u.parseManifest(JSONObject("""{"url":"https://raw.githubusercontent.com/a"}""")))
        assertNull(u.parseManifest(JSONObject("""{"version":"0.14.0"}""")))
        // An update is an APK this app asks Android to install. A manifest
        // naming a plain-HTTP URL is one anybody on the network could rewrite.
        assertNull(
            u.parseManifest(manifest(url = "http://raw.githubusercontent.com/o/r/main/a.apk"))
        )
        assertNotNull(u.parseManifest(manifest()))
    }

    @Test
    fun `a manifest cannot point the installer at another host`() {
        // THIS IS THE ONE THE PORT GOT WRONG. MusicD Remote Lite's Updater
        // carries the comment "only ever fetch the APK from the host the
        // manifest itself came from" above a check that only tests the scheme —
        // so a manifest served from GitHub could name an APK anywhere.
        val u = updater("0.13.0")
        assertNull(u.parseManifest(manifest(url = "https://example.invalid/evil.apk")))
        assertNull(u.parseManifest(manifest(url = "https://raw.githubusercontent.com.evil.test/a.apk")))
        assertNotNull(u.parseManifest(manifest(url = "https://raw.githubusercontent.com/o/r/x.apk")))
    }

    @Test
    fun `an unsigned build is never downloaded`() {
        // CI mints a fresh debug key on every runner, so an unsigned build can
        // never install over the existing app. Android's only word on that is
        // "App not installed", with no reason — so the refusal happens here,
        // before two megabytes are fetched, and says what to do instead.
        val u = updater("0.13.0")
        u.parseManifest(manifest(signed = false))!!.let { release ->
            assertFalse(release.signed)
        }
    }

    @Test
    fun `apply refuses an unsigned build instead of downloading it`() {
        // The server here WOULD serve a perfectly good APK. The point is that
        // it is never asked: an unsigned build cannot install over the existing
        // app whatever else is right about it, and Android's only word on that
        // is "App not installed", with no reason given. So the refusal happens
        // before the download, and says what to do instead.
        val tls = Tls()
        val apk = "a build signed with a throwaway key".toByteArray()
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(apk).joinToString("") { "%02x".format(it) }
        lateinit var server: okhttp3.mockwebserver.MockWebServer
        server = tls.server(dispatcher { request ->
            if (request.path?.endsWith(".apk") == true) {
                okhttp3.mockwebserver.MockResponse().setBody(okio.Buffer().write(apk))
            } else {
                okhttp3.mockwebserver.MockResponse().setBody(
                    JSONObject()
                        .put("version", "0.14.0")
                        .put("url", server.url("/dist/app.apk").toString())
                        .put("sha256", digest)
                        .put("signed", false)
                        .toString()
                )
            }
        })
        var installed: File? = null
        val u = Updater(
            http = tls.client(),
            currentVersion = "0.13.0",
            manifestUrl = server.url("/dist/latest.json").toString(),
            downloadDir = Files.createTempDirectory("updater-unsigned").toFile(),
            install = { installed = it }
        )
        try {
            val before = u.check()
            assertTrue("a newer version is still a newer version", before.getBoolean("available"))
            assertFalse("but it cannot go over the top", before.getBoolean("installable"))
            assertTrue(before.getString("blocked").contains("uninstall", ignoreCase = true))

            val after = u.apply { it.run() }
            assertEquals("error", after.getJSONObject("phase").getString("name"))
            assertTrue(
                after.getJSONObject("phase").getString("error").contains("uninstall", ignoreCase = true)
            )
            assertNull("nothing may be handed to the installer", installed)
            assertEquals(
                "the APK must not even be fetched", 1, server.requestCount
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `apply refuses a version that is not newer`() {
        var installed: File? = null
        val u = updater("0.13.0") { installed = it }
        u.accept(u.parseManifest(manifest(version = "0.13.0")))
        val after = u.apply { it.run() }
        assertEquals("error", after.getJSONObject("phase").getString("name"))
        assertTrue(after.getJSONObject("phase").getString("error").contains("newest"))
        assertNull(installed)
    }

    @Test
    fun `a signed newer build is downloaded, checked and handed over`() {
        val tls = Tls()
        val apk = "not really an apk, but the digest does not care".toByteArray()
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(apk).joinToString("") { "%02x".format(it) }
        lateinit var server: okhttp3.mockwebserver.MockWebServer
        server = tls.server(dispatcher { request ->
            if (request.path?.endsWith(".apk") == true) {
                okhttp3.mockwebserver.MockResponse().setBody(okio.Buffer().write(apk))
            } else {
                okhttp3.mockwebserver.MockResponse().setBody(
                    JSONObject()
                        .put("version", "0.14.0")
                        .put("url", server.url("/dist/app.apk").toString())
                        .put("sha256", digest)
                        .put("signed", true)
                        .toString()
                )
            }
        })
        var installed: File? = null
        val u = Updater(
            http = tls.client(),
            currentVersion = "0.13.0",
            manifestUrl = server.url("/dist/latest.json").toString(),
            downloadDir = Files.createTempDirectory("updater-ok").toFile(),
            install = { installed = it }
        )
        try {
            assertTrue(u.check().getBoolean("available"))
            // Runs the download inline so the assertions do not race it.
            val after = u.apply { it.run() }
            assertEquals("installing", after.getJSONObject("phase").getString("name"))
            assertNotNull("the APK never reached the installer", installed)
            assertEquals(apk.size.toLong(), installed!!.length())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `a download that does not match its checksum is thrown away`() {
        val tls = Tls()
        lateinit var server: okhttp3.mockwebserver.MockWebServer
        server = tls.server(dispatcher { request ->
            if (request.path?.endsWith(".apk") == true) {
                okhttp3.mockwebserver.MockResponse().setBody("something else entirely")
            } else {
                okhttp3.mockwebserver.MockResponse().setBody(
                    JSONObject()
                        .put("version", "0.14.0")
                        .put("url", server.url("/dist/app.apk").toString())
                        .put("sha256", "00".repeat(32))
                        .put("signed", true)
                        .toString()
                )
            }
        })
        var installed: File? = null
        val dir = Files.createTempDirectory("updater-bad").toFile()
        val u = Updater(
            http = tls.client(),
            currentVersion = "0.13.0",
            manifestUrl = server.url("/dist/latest.json").toString(),
            downloadDir = dir,
            install = { installed = it }
        )
        try {
            u.check()
            val after = u.apply { it.run() }
            assertEquals("error", after.getJSONObject("phase").getString("name"))
            assertTrue(after.getJSONObject("phase").getString("error").contains("checksum"))
            assertNull("a file that failed its digest must not be installed", installed)
            assertEquals("and it must not be left on disk", 0, dir.listFiles()?.size ?: 0)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `status is the shape the page reads`() {
        val s = updater("0.13.0").status()
        assertEquals("0.13.0", s.getString("current"))
        assertFalse(s.getBoolean("available"))
        assertTrue(s.isNull("latest"))
        assertEquals("idle", s.getJSONObject("phase").getString("name"))
        assertTrue(s.getJSONObject("phase").isNull("error"))
    }

    @Test
    fun `the phase names are the ones the page understands`() {
        // The page maps exactly these to its progress text and treats anything
        // else as "nothing happening", so they are a contract.
        assertEquals(
            listOf("idle", "checking", "downloading", "verifying", "installing", "error"),
            Updater.Phase.values().map { it.wire }
        )
    }

    @Test
    fun `sameHost compares hosts and not prefixes`() {
        assertTrue(Updater.sameHost("https://a.test/x", "https://A.TEST/y"))
        assertFalse(Updater.sameHost("https://a.test.evil/x", "https://a.test/y"))
        assertFalse(Updater.sameHost("not a url", "https://a.test/y"))
    }
}
