package com.musicd.sharecard.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The window: a WebView showing the same page any other device gets.
 *
 * There is no native UI here on purpose, and the reason is stronger than it was
 * in the app this came from. The page is served over a real socket to the whole
 * network, so a native screen would be a SECOND implementation of the card — one
 * that could drift from the one everybody else sees, and that only the person
 * holding this device would ever look at. One page, one card, served identically
 * to the FiiO's own screen and to a phone in another room.
 */
class MainActivity : Activity() {

    private companion object {
        const val TAG = "MainActivity"
        const val NOTIFICATION_PERMISSION = 1

        /** Matches the page background, so nothing flashes white on launch. */
        const val BACKGROUND = 0xFF0E1012.toInt()

        /** How long to wait for the local server before saying something. */
        const val SERVER_WAIT_MS = 10_000L

        const val FILE_CHOOSER = 2
    }

    /**
     * The page's pending `<input type="file">`, waiting on the file picker.
     *
     * It MUST be answered — with the files, or with null when the picker is
     * cancelled. A WebView whose callback is never invoked leaves that input
     * permanently dead: every later tap on it does nothing, with no way back
     * short of reloading the page.
     */
    private var pendingFiles: ValueCallback<Array<Uri>>? = null

    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private lateinit var message: TextView
    private val main = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        root = FrameLayout(this).apply { setBackgroundColor(BACKGROUND) }

        message = TextView(this).apply {
            setPadding(64, 64, 64, 64)
            setTextColor(0xFFBFC7CE.toInt())
            textSize = 15f
            text = "Starting…"
        }
        root.addView(message)

        web = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
            setBackgroundColor(BACKGROUND)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                // The page is served over plain HTTP from 127.0.0.1 and pulls
                // its webfont over HTTPS, so mixed content has to be allowed or
                // the font request is blocked — and a card drawn in the
                // fallback face lays out differently from every other copy.
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                cacheMode = WebSettings.LOAD_DEFAULT
                builtInZoomControls = false
                displayZoomControls = false
            }
            webViewClient = LocalClient()

            /*
             * WITHOUT THIS, `<input type="file">` DOES NOTHING AT ALL.
             *
             * A WebView does not open a file picker by itself: it asks its
             * WebChromeClient to, and with no WebChromeClient set there is
             * nothing to ask. Tapping the control is silently inert — no
             * picker, no error, no log line. That is exactly what the avatar
             * photo button did.
             */
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(
                    view: WebView?,
                    callback: ValueCallback<Array<Uri>>?,
                    params: FileChooserParams?
                ): Boolean {
                    // Answer any previous request first, or the input it came
                    // from stays dead for the life of the page.
                    pendingFiles?.onReceiveValue(null)
                    pendingFiles = callback
                    val intent = params?.createIntent()
                    if (intent == null) {
                        pendingFiles = null
                        return false
                    }
                    return try {
                        // createIntent() honours the accept attribute, so
                        // accept="image/*" produces a picker showing photos.
                        startActivityForResult(intent, FILE_CHOOSER)
                        true
                    } catch (e: Exception) {
                        Log.w(TAG, "nothing on this device can pick a file", e)
                        pendingFiles?.onReceiveValue(null)
                        pendingFiles = null
                        false
                    }
                }
            }
            // NOTE: unlike MusicD Remote, the native long-press is deliberately
            // LEFT ALONE. That app disabled it because its page implemented a
            // long-press of its own; here there is none to protect, and the
            // gesture is the only way to get an image off the screen on some
            // platforms. Taking it away would be removing a feature to fix a
            // conflict that does not exist.
            addJavascriptInterface(ShareBridge(this@MainActivity), ShareBridge.NAME)
        }
        root.addView(web)
        setContentView(root)

        askForNotificationPermission()
        startForegroundService(Intent(this, CardService::class.java))

        // A crash from LAST time is the most valuable thing this window can
        // show, and it must be shown before anything else can overwrite it.
        val crash = CrashLog.read(this)
        if (crash != null) {
            showCrash(crash)
            return
        }
        waitForServer(System.currentTimeMillis())
    }

    /**
     * What happened last time, in full, with no way to miss it.
     *
     * The app was reported as closing with no message at all, from a device in
     * another room with no adb attached — so the trace is put on screen where
     * somebody can photograph it. Dismissing is deliberate rather than a timer:
     * a crash report that clears itself before it is read is no report.
     */
    private fun showCrash(crash: String) {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 48)
        }
        column.addView(
            TextView(this).apply {
                text = "The app closed unexpectedly last time.\n\n" +
                    "This is what it recorded. Send it on, then carry on below."
                setTextColor(0xFFE8EDF2.toInt())
                textSize = 15f
                setPadding(0, 0, 0, 24)
            }
        )
        column.addView(
            ScrollView(this).apply {
                addView(
                    TextView(this@MainActivity).apply {
                        text = crash
                        setTextColor(0xFF9AA2AB.toInt())
                        textSize = 11f
                        setTextIsSelectable(true)
                        typeface = android.graphics.Typeface.MONOSPACE
                    }
                )
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )
        column.addView(
            Button(this).apply {
                text = "Dismiss and open the card"
                setOnClickListener {
                    CrashLog.clear(this@MainActivity)
                    root.removeAllViews()
                    root.addView(message)
                    root.addView(web)
                    message.visibility = View.VISIBLE
                    message.text = "Starting…"
                    waitForServer(System.currentTimeMillis())
                }
            }
        )
        root.removeAllViews()
        root.addView(column)
    }

    /**
     * The service owns the server, and it may not have bound its port yet when
     * the window opens. Wait briefly rather than racing it.
     */
    private fun waitForServer(startedAt: Long) {
        val url = CardService.instance?.app?.rootUrl
        if (url != null) {
            Log.i(TAG, "loading $url")
            message.visibility = View.GONE
            web.visibility = View.VISIBLE
            web.loadUrl(url)
            return
        }
        if (System.currentTimeMillis() - startedAt > SERVER_WAIT_MS) {
            // Say WHY when the service knows why. "Could not start" with no
            // reason is the message this app has already been caught giving.
            val why = CardService.instance?.startupError
            message.text = if (why != null) {
                "The card server could not start.\n\n$why"
            } else {
                "The card server did not come up.\n\n" +
                    "Close the app completely and open it again. If it keeps happening, " +
                    "restart the device — something else may be holding the port."
            }
            return
        }
        main.postDelayed({ waitForServer(startedAt) }, 100)
    }

    /**
     * Android 13+ needs permission before the service's notification is shown.
     * It is not needed for the service to RUN, so a refusal costs the
     * notification — and with it the place the LAN URL is displayed — and
     * nothing else.
     */
    private fun askForNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION)
    }

    /**
     * The file picker's answer, handed back to the page.
     *
     * `parseResult` turns a cancel into null, which is the right answer rather
     * than a missing one — the input is released either way.
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == FILE_CHOOSER) {
            val callback = pendingFiles
            pendingFiles = null
            callback?.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(resultCode, data)
            )
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onDestroy() {
        // A page being torn down with a picker still open would otherwise leak
        // the callback.
        pendingFiles?.onReceiveValue(null)
        pendingFiles = null
        // The service keeps running deliberately: closing the window must not
        // take the card server off the network. That is the whole point of it
        // being a service — see CardService.
        root.removeView(web)
        web.destroy()
        super.onDestroy()
    }

    private inner class LocalClient : WebViewClient() {
        override fun onPageFinished(view: WebView, url: String) {
            ShareBridge.install(view)
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url ?: return false
            val local = CardService.instance?.app?.rootUrl
            // Anything on our own server stays in the WebView; a link out
            // belongs to the browser.
            if (local != null && url.toString().startsWith(local)) return false
            return try {
                startActivity(
                    Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                true
            } catch (e: Exception) {
                Log.w(TAG, "nothing could open $url", e)
                true
            }
        }
    }
}
