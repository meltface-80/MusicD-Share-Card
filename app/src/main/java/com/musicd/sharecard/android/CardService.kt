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
        instance = this

        takeMulticastLock()

        val assets = Assets { path ->
            try {
                getAssets().open("web/$path").use { it.readBytes() }
            } catch (e: Exception) {
                // A missing asset is a 404, not a crash: the path came off the
                // network, and naming a file that is not there is ordinary.
                Log.d(TAG, "no bundled asset web/$path")
                null
            }
        }

        val started = runCatching {
            ShareCardApp(
                assets = assets,
                seedHosts = HostsFile.read(this),
                version = BuildConfig.VERSION_NAME
            ).also { it.start() }
        }.onFailure {
            // Loud, because the app is useless without it and the window's
            // "could not start" message is the only other clue.
            Log.e(TAG, "the card server could not start", it)
        }.getOrNull()

        app = started
        startForeground(NOTIFICATION_ID, notification(started))
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

    private fun takeMulticastLock() {
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

    private fun notification(app: ShareCardApp?): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Card server", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Keeps the share card reachable from other devices." }
            )
        }

        // The LAN URL is the useful thing to put here: it is what somebody
        // types into a phone, and the notification shade is where they will
        // look for it without unlocking anything.
        val where = app?.lanUrls()?.firstOrNull() ?: "starting…"

        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Share card ready")
            .setContentText(where)
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
