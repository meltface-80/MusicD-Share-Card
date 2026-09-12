package com.musicd.sharecard.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import com.musicd.sharecard.Log
import com.musicd.sharecard.ShareCardApp
import com.musicd.sharecard.api.Assets
import com.musicd.sharecard.roon.RoonClient

/**
 * The card server, running as a foreground service.
 *
 * WHY A SERVICE AND NOT JUST THE ACTIVITY. The whole "open it in a browser"
 * feature is a socket that has to be listening when somebody on the sofa picks
 * up their phone — and this app is installed on a FiiO R7 that is always on and
 * whose screen is off most of the time. An HTTP server owned by an Activity
 * dies with the window, so the URL would work only while somebody was already
 * looking at the device it is running on, which is the one time nobody needs it.
 *
 * Foreground, with a notification, because Android stops background services
 * that hold sockets and gives no warning that it has.
 */
class CardService : Service() {

    @Volatile
    var app: ShareCardApp? = null
        private set

    /** Why the server did not come up, for the window to show. */
    @Volatile
    var startupError: String? = null
        private set

    /**
     * SSDP is multicast, and Android drops multicast before it reaches
     * userspace unless this is held. Without it discovery returns nothing at
     * all, on a network full of players, with no error to explain it — which is
     * exactly the kind of silent failure this project has been bitten by.
     */
    private var multicastLock: WifiManager.MulticastLock? = null

    override fun onCreate() {
        super.onCreate()
        Log.sink = LogcatSink()
        // ON, not off.
        //
        // Log.debug defaults to false, and every diagnosis of a failed lookup in
        // this app goes through Log.d — so with it off, a speaker that would not
        // answer, a SOAP call that threw and a cover that 404'd were all
        // completely silent, in logcat and everywhere else. The first real
        // failure was debugged without a single line of evidence because of it.
        Log.debug = true
        instance = this

        // STARTFOREGROUND FIRST. NOTHING BEFORE IT.
        //
        // A start delivered as startForegroundService MUST be answered with
        // startForeground within about five seconds, or the system kills the
        // process — with no dialog, no trace, and nothing in the app to say what
        // happened. "Crashing and closing without any message" is what that
        // looks like from the outside, and it is what this app was doing.
        //
        // Everything that used to run before this line — the multicast lock, a
        // file read off external storage, binding the socket — is work of
        // unbounded duration on the main thread, sitting inside that window.
        // None of it needs to happen before the notification exists.
        startForeground(NOTIFICATION_ID, notification("Starting…"))

        // And the work itself goes on a thread, because onCreate is the main
        // thread and binding a socket there is both slow and, on a strict
        // Android build, an exception in its own right.
        Thread({ startUp() }, "sharecard-startup").apply { isDaemon = true }.start()
    }

    /**
     * Everything the card server needs, off the main thread.
     *
     * The window polls for [app] rather than being told, so arriving late here
     * is ordinary rather than a race to be defended against.
     */
    private fun startUp() {
        try {
            takeMulticastLock()

            val assets = Assets { path ->
                try {
                    getAssets().open("web/$path").use { it.readBytes() }
                } catch (e: Exception) {
                    // A missing asset is a 404, not a crash: the path came off
                    // the network, and naming a file that is not there is
                    // ordinary.
                    Log.d(TAG, "no bundled asset web/$path")
                    null
                }
            }

            val started = ShareCardApp(
                assets = assets,
                seedHosts = HostsFile.read(this),
                version = BuildConfig.VERSION_NAME,
                // So a crash is visible from the phone in the next room, not
                // only on the device that crashed.
                hostNotes = {
                    CrashLog.read(this)?.let { listOf("Last crash:\n$it") }.orEmpty() +
                        // Which door each outgoing link went through. "Opens
                        // the app but not the album" has three causes that look
                        // the same from outside — see LinkLog.
                        LinkLog.lines().map { "Link: $it" }
                },
                // Roon's pairing token, so the extension is approved once and
                // not on every restart.
                tokenStore = RoonTokenFile(this),
                // Discord webhooks. Private storage, and never handed back out
                // over the network — only a mask is.
                webhookStore = WebhookFile(this),
                // Roon's discovery is SOOD, which is multicast — the same lock
                // SSDP needs, and the same silent failure without it.
                roonMulticastLock = object : RoonClient.MulticastLock {
                    override fun acquire() = takeMulticastLock()
                    override fun release() {
                        // Held for the life of the service rather than per
                        // scan: Roon rediscovers on every reconnect, and a lock
                        // that is dropped between them is a lock that is not
                        // held when it matters.
                    }
                },
                // Updating in place. Only the Android shell can do the last
                // step, so :core does everything up to it and calls back here.
                updateInstaller = ShareCardApp.UpdateInstaller(
                    downloadDir = ApkInstaller.downloadDir(this),
                    install = { apk -> ApkInstaller.install(this, apk) }
                )
            ).also { it.start() }

            app = started
            // Now the notification can name the address somebody would type in.
            update(started.lanUrls().firstOrNull() ?: "ready")
        } catch (e: Throwable) {
            // Loud, and never fatal: a service that dies here takes the whole
            // app with it, and the window's message is then the only clue left.
            Log.e(TAG, "the card server could not start", e)
            startupError = com.musicd.sharecard.describe(e)
            update("could not start — open the app")
        }
    }

    private fun update(text: String) {
        runCatching {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, notification(text))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Restarted if Android kills it: the socket is the product.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        app?.stop()
        app = null
        runCatching { multicastLock?.release() }
        multicastLock = null
        instance = null
        super.onDestroy()
    }

    @Synchronized
    private fun takeMulticastLock() {
        // Idempotent: both SSDP and Roon's SOOD ask for this, and a second
        // lock object would leak the first.
        if (multicastLock?.isHeld == true) return
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("musicd-sharecard").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            // Discovery will come back empty on wifi; a seed host still works,
            // and that is what the message in the app points at.
            Log.w(TAG, "no multicast lock — SSDP discovery may find nothing", e)
        }
    }

    private fun notification(text: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Card server", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Keeps the share card reachable from other devices." }
            )
        }

        // The LAN URL is the useful thing to put here: it is what somebody types
        // into a phone, and the shade is where they will look for it without
        // unlocking anything.
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Share card")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private class LogcatSink : Log.Sink {
        override fun write(level: Char, tag: String, message: String, error: Throwable?) {
            val full = "MusicDCard/$tag"
            when (level) {
                'D' -> android.util.Log.d(full, message, error)
                'I' -> android.util.Log.i(full, message, error)
                'W' -> android.util.Log.w(full, message, error)
                else -> android.util.Log.e(full, message, error)
            }
        }
    }

    companion object {
        private const val TAG = "Service"
        private const val CHANNEL = "card-server"
        private const val NOTIFICATION_ID = 1

        /**
         * The running service, for the window to read its URL off.
         *
         * Set in onCreate rather than by binding: the Activity needs one string
         * and binding for it would mean a connection callback, a disconnect, and
         * a window that shows nothing until both have happened.
         */
        @Volatile
        var instance: CardService? = null
            internal set
    }
}
