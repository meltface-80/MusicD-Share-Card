package com.musicd.sharecard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The two things that make a file input work, neither of which a JVM test can
 * exercise directly.
 *
 * The avatar picker did nothing when tapped, and there were two independent
 * reasons for that — one on each side of the WebView:
 *
 *  1. A WebView does not open a file picker by itself. It asks its
 *     WebChromeClient to, and with none set there is nothing to ask: the tap
 *     is silently inert, with no picker, no error and no log line.
 *  2. The input was `display: none`, which removes it from the layout — and
 *     several browsers will not open a picker for a control that is not there,
 *     even when a label points at it.
 *
 * Neither can be reproduced here: there is no WebView and no browser. So the
 * source is scanned, the same way JsonSafeTest scans for `optString` — because
 * the failure mode is silence, and silence is what nobody notices coming back.
 */
class FilePickerContractTest {

    private fun read(path: String): String {
        val file = File("../$path")
        assertTrue("cannot find $path at ${file.absolutePath}", file.isFile)
        return file.readText()
    }

    @Test
    fun `the WebView is given a chrome client that answers file chooser requests`() {
        val source = read("app/src/main/java/com/musicd/sharecard/android/MainActivity.kt")
        assertTrue(
            "without a WebChromeClient, <input type=file> does nothing at all",
            source.contains("webChromeClient")
        )
        assertTrue(
            "onShowFileChooser is the callback that opens the picker",
            source.contains("onShowFileChooser")
        )
        assertTrue(
            "and its result has to be handed back to the page",
            source.contains("onActivityResult") && source.contains("parseResult")
        )
    }

    /**
     * A callback that is never answered leaves that input dead for the life of
     * the page — every later tap does nothing, with no way back short of a
     * reload. Cancelling the picker must still answer it, with null.
     */
    @Test
    fun `a pending file callback is always answered`() {
        val source = read("app/src/main/java/com/musicd/sharecard/android/MainActivity.kt")
        assertTrue(
            "a cancelled or failed pick must still release the input",
            source.contains("onReceiveValue(null)")
        )
        assertTrue(
            "including when the window goes away with a picker open",
            source.substringAfter("override fun onDestroy").contains("onReceiveValue(null)")
        )
    }

    @Test
    fun `the file input is hidden without being removed from the layout`() {
        val css = read("app/src/main/assets/web/style.css")
        val block = css.substringAfter(".wh-file input").substringBefore('}')
        assertFalse(
            "display:none removes the control, and a picker will not open for one " +
                "that is not there: $block",
            block.contains("display: none") || block.contains("display:none")
        )
        assertTrue("it still has to be invisible: $block", block.contains("opacity: 0"))
    }
}
