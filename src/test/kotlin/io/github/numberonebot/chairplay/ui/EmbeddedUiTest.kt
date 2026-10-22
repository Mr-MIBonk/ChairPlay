package io.github.numberonebot.chairplay.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedUiTest {
    private val calls = mutableListOf<String>()
    private val logged = mutableListOf<String>()
    private val opened = mutableListOf<String>()
    private var embedded: Boolean? = null
    private var shows = true
    private var parentFocused = false

    private val backend = object : WebViewBackend {
        override fun create(parent: Long): Long {
            calls += "create $parent"
            return 7
        }
        override fun navigate(view: Long, url: String) {
            calls += "navigate $url"
        }
        override fun setBounds(view: Long, width: Int, height: Int) {
            calls += "bounds ${width}x$height"
        }
        override fun setVisible(view: Long, visible: Boolean) {
            calls += if (visible) "show" else "hide"
        }
        override fun focus(view: Long) {
            calls += "focus view"
            parentFocused = false
        }
        override fun focusParent(parent: Long) {
            calls += "focus window"
        }
        override fun parentHasFocus(parent: Long) = parentFocused
        override fun destroy(view: Long) {
            calls += "destroy $view"
        }
    }

    private fun ui(make: () -> WebViewBackend = { backend }) =
        EmbeddedUi("http://127.0.0.1:1/?token=t", { shows }, make, { opened += it }, { logged += it }, { embedded = it })

    @Test
    fun `the page opens hidden in the window and shows on the first layout`() {
        val ui = ui()

        ui.attach(42)
        ui.layout(1280, 720)

        assertEquals(listOf("create 42", "navigate http://127.0.0.1:1/?token=t", "bounds 1280x720", "show", "focus view"), calls)
        assertEquals(true, embedded)
    }

    // While CarPlay shows, the keys are CarPlay's again.
    @Test
    fun `CarPlay's turn hides the page and hands the keys back to the window`() {
        val ui = ui()
        ui.attach(42)
        ui.layout(1280, 720)
        calls.clear()

        shows = false
        ui.layout(1280, 720)
        ui.layout(1280, 720)

        assertEquals(listOf("hide", "focus window"), calls)
    }

    @Test
    fun `the page follows the window's size`() {
        val ui = ui()
        ui.attach(42)
        ui.layout(1280, 720)
        calls.clear()

        ui.layout(1600, 900)

        assertEquals(listOf("bounds 1600x900"), calls)
    }

    // Activating the window again (Alt+Tab, its title bar) gives Windows' keyboard focus to the window itself.
    @Test
    fun `the page takes the keys back while it shows`() {
        val ui = ui()
        ui.attach(42)
        ui.layout(1280, 720)
        calls.clear()

        parentFocused = true
        ui.layout(1280, 720)

        assertEquals(listOf("focus view"), calls)
    }

    @Test
    fun `without WebView2 the page opens in the browser`() {
        val ui = ui { throw WebViewUnavailable("WebView2 is unavailable") }

        ui.attach(42)
        ui.layout(1280, 720)
        ui.detach()

        assertEquals(listOf("http://127.0.0.1:1/?token=t"), opened)
        assertEquals(false, embedded)
        assertTrue(logged.single(), "WebView2 is unavailable" in logged.single() && "browser" in logged.single())
        assertEquals(emptyList<String>(), calls)
    }

    // A missing DLL is an Error, not an Exception, and must not take the window down.
    @Test
    fun `a missing native library falls back too`() {
        val ui = ui { throw UnsatisfiedLinkError("chairplay_webview.dll") }

        ui.attach(42)

        assertEquals(1, opened.size)
        assertEquals(false, embedded)
    }

    @Test
    fun `the view goes with the window`() {
        val ui = ui()
        ui.attach(42)

        ui.detach()
        ui.layout(1280, 720)

        assertEquals("destroy 7", calls.last())
    }
}
