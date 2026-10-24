package io.github.numberonebot.chairplay.media

import io.github.numberonebot.chairplay.config.ScreenArea
import io.github.numberonebot.chairplay.config.WindowPlacement
import io.github.numberonebot.chairplay.config.WindowSize
import io.github.numberonebot.chairplay.config.fittedTo
import io.github.numberonebot.chairplay.config.isVisibleOn
import io.github.numberonebot.chairplay.config.openingSize
import io.github.numberonebot.chairplay.config.limitFor
import io.github.numberonebot.chairplay.config.grownTo
import io.github.numberonebot.chairplay.input.PointerEvent
import io.github.numberonebot.chairplay.input.Size
import io.github.numberonebot.chairplay.input.toFramebuffer
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.glfw.GLFWErrorCallback
import org.lwjgl.glfw.GLFWNativeWin32.glfwGetWin32Window
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GLCapabilities
import org.lwjgl.opengl.GL33C.*
import java.awt.image.BufferedImage
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The CarPlay screen in a GLFW window. GLFW wants its window and event loop on one thread (the main
 * one), so [run] owns that thread until the window closes. Other threads only call [show] and [close]:
 * [show] leaves the newest frame in an [AtomicReference] and wakes the loop, so a frame that arrives
 * while the previous one is still being drawn simply replaces it — nothing queues, latency stays one frame.
 * Each frame is released exactly once, by whichever side takes it out of [latest]: [show] for a frame
 * replaced unseen, the GL thread once the upload has copied the planes into textures.
 *
 * The window opens [width] x [height] where Windows puts it, or at [placement] when that is still on a monitor,
 * and hands where it was to [onPlacement] when it closes. Several windows share one loop through [runWindows].
 */
class VideoWindow(
    private val width: Int,
    private val height: Int,
    private val placement: WindowPlacement? = null,
    private val title: String = "ChairPlay",
    /** The picture's shape: the window keeps it while resized and opens with it; null resizes freely. */
    private var aspect: WindowSize? = null,
    /** False keeps the window at its size: no resizing by its edges, no maximize button. */
    private val resizable: Boolean = true,
    /** The smallest size its edges can be dragged to, before Windows' display scale; null leaves it to Windows. */
    private var minSize: WindowSize? = null,
    /** False when [minSize] is in screen pixels already, for a window with no page to scale with the display. */
    private val minScales: Boolean = true,
    /** True makes the close button hide the window, which [setShown] brings back, instead of closing it. */
    private val hideOnClose: Boolean = false,
    /** Whether the window shows as it opens; one that does not waits for [setShown]. */
    shown: Boolean = true,
    /**
     * True when [width] and [height] are a page's, in the web view's pixels: the window opens at them times the
     * monitor's display scale, follows it to a monitor of another scale, and takes back only its place.
     */
    private val pageSized: Boolean = false,
    /** The picture it shows, in its pixels: [minSize] never keeps the window above it, so it can always show it 1:1. */
    stream: WindowSize? = null,
) : Closeable {
    /** The picture it shows, as it opened with it or [setStream] changed it since. */
    @Volatile
    var stream: WindowSize? = stream
        private set
    private val streamRequest = AtomicReference<WindowSize?>()
    private val minRequest = AtomicReference<WindowSize?>()
    // The picture the window last took the shape and size of, on the GL thread.
    private var appliedStream: WindowSize? = stream
    private val latest = AtomicReference<DecodedFrame?>()
    private val closing = AtomicBoolean()
    private val minimizing = AtomicBoolean()
    private val screenshotRequest = AtomicReference<((BufferedImage) -> Unit)?>()
    private val newTitle = AtomicReference<String?>()
    private val shownRequest = AtomicReference<Boolean?>()
    private val resizeRequest = AtomicReference<WindowSize?>()
    private val clearRequest = AtomicBoolean()

    /** Safe from any thread: drops the picture shown, the window black until the next one, as a session ends. */
    fun clearPicture() {
        clearRequest.set(true)
        wake()
    }

    /** Runs on the GL thread when the client area takes another size, minimized aside. */
    @Volatile
    var onResize: (Size) -> Unit = {}

    /** A safe area to frame (insets in the picture's pixels) until [until] on System.nanoTime's clock. */
    private class FrameRequest(val top: Int, val bottom: Int, val left: Int, val right: Int, val until: Long)

    @Volatile
    private var frame: FrameRequest? = null

    /**
     * Safe from any thread: frames the picture's safe area, the insets in its pixels, in green dots for [millis], over
     * whatever the window shows, the picture or the HUD.
     */
    fun showFrame(top: Int, bottom: Int, left: Int, right: Int, millis: Long = 3_000) {
        frame = FrameRequest(top, bottom, left, right, System.nanoTime() + millis * 1_000_000)
        wake()
        // One more pass when it is due to go.
        Thread({
            Thread.sleep(millis + 20)
            wake()
        }, "frame-end").apply { isDaemon = true }.start()
    }

    /**
     * Safe from any thread: the picture is [size] from now on, as a connection with another display size brings. The
     * window takes its shape, if it keeps one, and its smallest size; one at one pixel per pixel of the old picture
     * goes to the new one's size, another keeps its width. [min] replaces the smallest size, for a minimum that follows
     * the picture's shape.
     */
    fun setStream(size: WindowSize, min: WindowSize? = null) {
        stream = size
        minRequest.set(min)
        streamRequest.set(size)
        wake()
    }

    /** Safe from any thread: gives the client area [width] x [height] screen pixels, a maximized window restored first. */
    fun resizeTo(width: Int, height: Int) {
        resizeRequest.set(WindowSize(width, height))
        wake()
    }

    /** Whether the window is on screen rather than hidden by its close button or by [setShown]. */
    @Volatile
    var visible = shown
        private set

    /** Runs on the GL thread when the window shows or hides. */
    @Volatile
    var onVisibility: (Boolean) -> Unit = {}

    /** Safe from any thread: shows or hides the window on the next loop pass; a shown one comes to the front. */
    fun setShown(shown: Boolean) {
        shownRequest.set(shown)
        wake()
    }

    /** What the window shows: [overlay] on black (the AltScreen's HUD), or the picture of the iPhone's view [key]. */
    private data class View(val hud: Boolean, val key: Any?)

    @Volatile
    private var view = View(false, null)
    // What to do once the window is black on the view wanted: ask the iPhone for it.
    private val onBlack = AtomicReference<(() -> Unit)?>()

    /** Whether the window shows (or is going over to) [overlay] on black instead of the picture: the AltScreen's HUD. */
    val hud: Boolean get() = view.hud

    /**
     * Safe from any thread: shows the HUD ([hud]) or the iPhone's view [key], through black when that changes. [onBlack]
     * runs once the window is black, on a thread of its own, to ask the iPhone for the view: it starts drawing it only
     * then, so nothing of the change shows; the window stays black until the view's pictures are in (or [hud] needs
     * none). Unchanged, [onBlack] runs at once. [log] tells how long each change waited.
     */
    fun showView(hud: Boolean, key: Any? = null, onBlack: () -> Unit = {}) {
        // Under the HUD the iPhone's view is not seen: changing it is no change of what shows.
        this.onBlack.set(onBlack)
        view = View(hud, if (hud) null else key)
        wake()
    }

    /** The window's own lines in the app's log. */
    @Volatile
    var log: (String) -> Unit = {}
    private val reveal = RevealGate(log = { log(it) })

    /** What [hud] shows; set before [runWindows]. */
    var overlay: WindowOverlay? = null
    private var overlayOpen = false

    /** A native view over the picture (the web UI), laid out with the window; set before [runWindows]. */
    var child: WindowChild? = null

    /** Safe from any thread: draws the window again, as the HUD does when its data changes. */
    fun repaint() = wake()
    // GLFW rejects glfwPostEmptyEvent outside init..terminate, which frames and close() can fall outside of.
    private val running = AtomicBoolean()

    /**
     * Runs on the GL thread when a window that hides on its close button is asked to close from outside the app
     * (taskkill without /F), which asks the app to end; the close button and Alt+F4 only hide it.
     */
    @Volatile
    var onOutsideClose: () -> Unit = {}
    private val closeSource = CloseSource()

    /** Runs on the GL thread as the window closes, with its last place and restored size. */
    @Volatile
    var onPlacement: (WindowPlacement) -> Unit = {}

    /** Runs on the GL thread after a frame has been swapped to the screen. */
    @Volatile
    var onFrameShown: () -> Unit = {}

    /**
     * Receives left-button mouse samples on the GL thread, in framebuffer pixels (the space the picture is
     * letterboxed in). With [framebufferSize] and [pictureSize] that is all the mapping to the screen needs.
     */
    @Volatile
    private var inputHandler: (PointerEvent) -> Unit = {}

    @Volatile
    var framebufferSize = Size(0, 0)
        private set

    /** Size of the newest frame on screen; zero until the first one. */
    @Volatile
    var pictureSize = Size(0, 0)
        private set

    fun setInputHandler(handler: (PointerEvent) -> Unit) {
        inputHandler = handler
    }

    /** Receives GLFW key codes, actions (press, repeat, release) and modifier bits on the GL thread. */
    @Volatile
    var keyHandler: (key: Int, action: Int, mods: Int) -> Unit = { _, _, _ -> }

    /** Receives the vertical scroll offset GLFW reports, on the GL thread. */
    @Volatile
    var scrollHandler: (dy: Double) -> Unit = {}

    fun show(frame: DecodedFrame) {
        latest.getAndSet(frame)?.release()
        wake()
    }

    /** Safe from any thread: iconifies the window and leaves the session running, like a car's Home button. */
    fun minimize() {
        minimizing.set(true)
        wake()
    }

    /**
     * Safe from any thread: hands the picture on screen, at the size the iPhone sent it, to [onImage] on a thread of
     * its own. Nothing happens before the first picture.
     */
    fun screenshot(onImage: (BufferedImage) -> Unit) {
        screenshotRequest.set(onImage)
        wake()
    }

    /** Safe from any thread: GLFW sets a title on the main thread only, so the next loop pass applies it. */
    fun setTitle(title: String) {
        newTitle.set(title)
        wake()
    }

    /** Safe from any thread; [run] then returns and releases the window. */
    override fun close() {
        closing.set(true)
        wake()
    }

    private fun wake() {
        if (running.get()) glfwPostEmptyEvent()
    }

    /** Creates the window and draws until it is closed by the user or by [close]. Call on the main thread. */
    fun run() = runWindows(this)

    // GL-thread state of an open window.
    private var handle = 0L
    private var capabilities: GLCapabilities? = null
    private var tracker: PlacementTracker? = null
    private var program = 0
    private var sourceUniform = -1
    // Black over the window at the fades' depth: one layer dims the picture and the HUD alike.
    private var dimProgram = 0
    private var dimUniform = -1
    // The safe-area frame's dots.
    private var frameProgram = 0
    private var frameUniform = -1
    // The child (the settings over CarPlay) and the HUD (over the AltScreen's picture) each come and go through black.
    private var childFade: Fade<Boolean>? = null
    private var viewFade: Fade<View>? = null
    private var vao = 0
    private val textures = IntArray(3)
    private val planeSizes = IntArray(6)
    private var picture: Size? = null
    private val w = IntArray(1)
    private val h = IntArray(1)

    /** Creates the window, hidden until it is in place, with its own GL context. GLFW is initialised. */
    internal fun open() {
        glfwDefaultWindowHints()
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3)
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3)
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE)
        // Hidden until it is in place, so it does not flash at the default position first.
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE)
        glfwWindowHint(GLFW_RESIZABLE, if (resizable) GLFW_TRUE else GLFW_FALSE)
        // GLFW scales a page's size by the monitor's scale as it creates the window, and again on WM_DPICHANGED.
        if (pageSized) glfwWindowHint(GLFW_SCALE_TO_MONITOR, GLFW_TRUE)
        val restore = placement?.takeIf { it.isVisibleOn(workAreas()) }
        val remembered = openingSize(WindowSize(width, height), restore, pageSized)
        val size = aspect?.let { remembered.fittedTo(it) } ?: remembered
        val window = glfwCreateWindow(size.width, size.height, title, 0, 0)
        check(window != 0L) { "cannot create the window" }
        handle = window
        // Windows applies it to edge drags only, so a maximized window still fills the screen.
        aspect?.let { glfwSetWindowAspectRatio(window, it.width, it.height) }
        if (restore != null) glfwSetWindowPos(window, restore.x, restore.y)
        TitleBar.paintBlack(glfwGetWin32Window(window))
        // A remembered or configured size below it grows to it here. It scales with the monitor, as the web view's
        // pages do, and follows the window to a monitor of another scale.
        // A window that keeps the picture's shape stops at the smallest size of that shape covering the minimum, so
        // the picture fills it there too.
        if (minSize != null) {
            limitSize(window)
            glfwSetWindowContentScaleCallback(window) { handle, _, _ -> limitSize(handle) }
        }
        if (hideOnClose) {
            closeSource.watch(glfwGetWin32Window(window))
            glfwSetWindowCloseCallback(window) { handle ->
                glfwSetWindowShouldClose(handle, false)
                // The close button hides the window; a close asked from outside (taskkill) ends the app.
                if (closeSource.userClosed()) applyShown(handle, false) else onOutsideClose()
            }
        }
        WindowIcon.apply(window)
        tracker = PlacementTracker(window, restore?.maximized ?: false)
        if (visible) {
            if (restore?.maximized == true) glfwMaximizeWindow(window) else glfwShowWindow(window)
        }
        glfwMakeContextCurrent(window)
        // Draw as soon as a frame is ready instead of waiting for vertical blank, which would add up to a frame.
        glfwSwapInterval(0)
        capabilities = GL.createCapabilities()
        installInput(window)

        dimProgram = buildProgram(DIM_FRAGMENT)
        dimUniform = glGetUniformLocation(dimProgram, "alpha")
        frameProgram = buildProgram(FRAME_FRAGMENT)
        frameUniform = glGetUniformLocation(frameProgram, "rect")
        program = buildProgram()
        vao = glGenVertexArrays()
        for (plane in 0 until 3) textures[plane] = glGenTextures()
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1)
        glUseProgram(program)
        for (plane in 0 until 3) {
            glActiveTexture(GL_TEXTURE0 + plane)
            glBindTexture(GL_TEXTURE_2D, textures[plane])
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
            glUniform1i(glGetUniformLocation(program, arrayOf("planeY", "planeU", "planeV")[plane]), plane)
        }
        glBindVertexArray(vao)
        sourceUniform = glGetUniformLocation(program, "source")
        glUniform4f(sourceUniform, 0f, 0f, 1f, 1f)
        // Black from the start, like the title bar, instead of the system's white until the first frame.
        glfwGetFramebufferSize(window, w, h)
        clear(w[0], h[0])
        glfwSwapBuffers(window)
        // While an edge is dragged Windows runs its own message loop, and runWindows' passes wait for it to end: the
        // window draws, and lays out the web view, from the repaints Windows asks for meanwhile, or the part it grows
        // by stays white until the mouse lets go.
        glfwSetWindowRefreshCallback(window) { _ -> step() }
        running.set(true)
        child?.attach(glfwGetWin32Window(window))
    }

    private fun limitSize(window: Long) {
        val min = minSize?.let { aspect?.let(it::grownTo) ?: it } ?: return
        val scale = FloatArray(1)
        glfwGetWindowContentScale(window, scale, null)
        // In the web view's pixels, scaled with the display; never above the picture, so it can always show it 1:1.
        val size = min.limitFor(if (minScales) scale[0] else 1f, stream)
        glfwSetWindowSizeLimits(window, size.width, size.height, GLFW_DONT_CARE, GLFW_DONT_CARE)
        minimumSize = Size(size.width, size.height)
    }

    /** [setStream] on the GL thread: the new picture's shape and smallest size, and the window's size for it. */
    private fun applyStream(window: Long, size: WindowSize) {
        val old = appliedStream
        appliedStream = size
        if (size == old) return
        val w = IntArray(1)
        val h = IntArray(1)
        glfwGetWindowSize(window, w, h)
        val wasActual = old != null && w[0] == old.width && h[0] == old.height
        if (aspect != null) {
            aspect = size
            glfwSetWindowAspectRatio(window, size.width, size.height)
        }
        limitSize(window)
        // A maximized window keeps filling the screen.
        if (glfwGetWindowAttrib(window, GLFW_MAXIMIZED) == GLFW_TRUE) return
        when {
            wasActual -> glfwSetWindowSize(window, size.width, size.height)
            aspect != null -> WindowSize(w[0], h[0]).fittedTo(size).let { glfwSetWindowSize(window, it.width, it.height) }
        }
    }

    /** The smallest client area the window takes, in screen pixels, with the monitor's scale; zero without a limit. */
    @Volatile
    var minimumSize = Size(0, 0)
        private set

    private fun applyShown(window: Long, shown: Boolean) {
        if (shown) {
            glfwShowWindow(window)
            glfwFocusWindow(window)
        } else {
            glfwHideWindow(window)
        }
        if (shown != visible) {
            visible = shown
            onVisibility(shown)
        }
    }

    // Set while a pass runs, so a repaint Windows asks for in the middle of one does not start another.
    private var stepping = false

    /** One pass of the event loop for this window; false once it should close. */
    internal fun step(): Boolean {
        if (stepping) return true
        stepping = true
        try {
            return pass()
        } finally {
            stepping = false
        }
    }

    private fun pass(): Boolean {
        val window = handle
        if (glfwWindowShouldClose(window) || closing.get()) return false
        glfwMakeContextCurrent(window)
        GL.setCapabilities(capabilities)
        if (minimizing.getAndSet(false)) glfwIconifyWindow(window)
        shownRequest.getAndSet(null)?.let { applyShown(window, it) }
        newTitle.getAndSet(null)?.let { glfwSetWindowTitle(window, it) }
        streamRequest.getAndSet(null)?.let { size ->
            minRequest.getAndSet(null)?.let { minSize = it }
            applyStream(window, size)
        }
        resizeRequest.getAndSet(null)?.let { size ->
            // A maximized window keeps filling the screen whatever size it is given.
            if (glfwGetWindowAttrib(window, GLFW_MAXIMIZED) == GLFW_TRUE) glfwRestoreWindow(window)
            glfwSetWindowSize(window, size.width, size.height)
        }
        if (clearRequest.getAndSet(false)) {
            latest.getAndSet(null)?.release()
            picture = null
        }
        val fresh = latest.getAndSet(null)
        if (fresh != null) {
            // glTexImage2D and glTexSubImage2D have read client memory by the time they return.
            upload(fresh, textures, planeSizes)
            reveal.picture(meanLuma(fresh))
            fresh.release()
            val size = Size(fresh.width, fresh.height)
            picture = size
            pictureSize = size
        }
        // A resize repaints the last picture even when no new one has arrived, or black before the first.
        glfwGetFramebufferSize(window, w, h)
        val resized = Size(w[0], h[0]).takeIf { it != framebufferSize && it.width > 0 && it.height > 0 }
        framebufferSize = Size(w[0], h[0])
        resized?.let(onResize)
        // What the window shows changes through black: the settings over CarPlay, the AltScreen's HUD and its views.
        val cover = child?.let { visible -> (childFade ?: Fade(visible.covers).also { childFade = it }).apply { update(visible.covers) } }
        child?.layout(w[0], h[0], cover?.shown ?: true)
        val wanted = view
        val content = (viewFade ?: Fade(wanted).also { viewFade = it }).apply { update(wanted, reveal.open()) }
        if (content.shown == wanted) {
            onBlack.getAndSet(null)?.let { ask ->
                // Black on another of the iPhone's views: it waits for the view's pictures. The HUD needs none, and a
                // change taken back before black never left the view on screen.
                val wait = if (content.brightness == 0f && !wanted.hud) reveal.hold() else null
                Thread({
                    ask()
                    wait?.let(reveal::sent)
                }, "view-ask").apply { isDaemon = true }.start()
            }
        }
        val showsHud = content.shown.hud
        val shown = picture
        val layer = overlay
        when {
            showsHud -> {
                clear(w[0], h[0])
                if (layer != null) {
                    if (!overlayOpen) {
                        layer.open()
                        overlayOpen = true
                    }
                    layer.paint(w[0], h[0])
                    // The overlay changes GL state; the picture's own is set again for the next pass.
                    restoreVideoState()
                }
            }
            shown == null -> clear(w[0], h[0])
            else -> draw(shown, w[0], h[0])
        }
        dim(1f - minOf(cover?.brightness ?: 1f, content.brightness), w[0], h[0])
        drawFrame(w[0], h[0])
        val shot = screenshotRequest.getAndSet(null)
        if (shot != null && showsHud) {
            // The HUD is ours, not the iPhone's picture: save what the window shows.
            val pixels = readFramebuffer(w[0], h[0])
            val (fw, fh) = w[0] to h[0]
            Thread({ shot(screenshotImage(fw, fh, pixels)) }, "screenshot").apply { isDaemon = true }.start()
        }
        glfwSwapBuffers(window)
        if (fresh != null) onFrameShown()
        if (shot != null && !showsHud && shown != null) {
            val pixels = readPicture(shown)
            Thread({ shot(screenshotImage(shown.width, shown.height, pixels)) }, "screenshot").apply { isDaemon = true }.start()
        }
        return true
    }

    /** The program, vertex array, plane textures and blending the picture is drawn with. */
    private fun restoreVideoState() {
        glUseProgram(program)
        glBindVertexArray(vao)
        for (plane in 0 until 3) {
            glActiveTexture(GL_TEXTURE0 + plane)
            glBindTexture(GL_TEXTURE_2D, textures[plane])
        }
        glActiveTexture(GL_TEXTURE0)
        glDisable(GL_BLEND)
        glDisable(GL_CULL_FACE)
        glDisable(GL_STENCIL_TEST)
        glDisable(GL_SCISSOR_TEST)
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1)
        glUniform4f(sourceUniform, 0f, 0f, 1f, 1f)
    }

    /** Hands over the place, frees the GL objects and destroys the window. */
    internal fun dispose() {
        val window = handle
        if (window == 0L) return
        running.set(false)
        latest.getAndSet(null)?.release()
        tracker?.let { runCatching { onPlacement(it.placement()) } }
        glfwMakeContextCurrent(window)
        GL.setCapabilities(capabilities)
        if (overlayOpen) runCatching { overlay?.close() }
        overlayOpen = false
        child?.let { runCatching { it.detach() } }
        glDeleteTextures(textures)
        glDeleteVertexArrays(vao)
        glDeleteProgram(program)
        glDeleteProgram(dimProgram)
        glDeleteProgram(frameProgram)
        glfwMakeContextCurrent(0)
        glfwDestroyWindow(window)
        handle = 0L
    }

    /** The window's back buffer as drawn, bottom row first. */
    private fun readFramebuffer(width: Int, height: Int): ByteBuffer {
        val pixels = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glPixelStorei(GL_PACK_ALIGNMENT, 1)
        glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels)
        return pixels
    }

    /** Draws the current picture at its own size into an offscreen texture and reads it back, bottom row first. */
    private fun readPicture(picture: Size): ByteBuffer {
        val framebuffer = glGenFramebuffers()
        val target = glGenTextures()
        // Unit 3: units 0..2 hold the Y, U and V planes the shader samples.
        glActiveTexture(GL_TEXTURE3)
        glBindTexture(GL_TEXTURE_2D, target)
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, picture.width, picture.height, 0, GL_RGBA, GL_UNSIGNED_BYTE, null as ByteBuffer?)
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer)
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target, 0)
        glViewport(0, 0, picture.width, picture.height)
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
        val pixels = ByteBuffer.allocateDirect(picture.width * picture.height * 4).order(ByteOrder.nativeOrder())
        glPixelStorei(GL_PACK_ALIGNMENT, 1)
        glReadPixels(0, 0, picture.width, picture.height, GL_RGBA, GL_UNSIGNED_BYTE, pixels)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glDeleteFramebuffers(framebuffer)
        glDeleteTextures(target)
        glActiveTexture(GL_TEXTURE0)
        return pixels
    }

    private fun workAreas(): List<ScreenArea> {
        val monitors = glfwGetMonitors() ?: return emptyList()
        val x = IntArray(1)
        val y = IntArray(1)
        val w = IntArray(1)
        val h = IntArray(1)
        return (0 until monitors.limit()).map { i ->
            glfwGetMonitorWorkarea(monitors[i], x, y, w, h)
            ScreenArea(x[0], y[0], w[0], h[0])
        }
    }

    /**
     * Follows the window's normal (not maximized, not minimized) place and size, since GLFW reports the maximized
     * or minimized geometry once the window is in that state. A maximized window minimized and closed from the
     * taskbar stays maximized: GLFW keeps the flag across the minimize.
     */
    private class PlacementTracker(private val window: Long, @Volatile private var maximized: Boolean) {
        private var x = 0
        private var y = 0
        private var width = 0
        private var height = 0

        init {
            val a = IntArray(1)
            val b = IntArray(1)
            glfwGetWindowPos(window, a, b)
            x = a[0]
            y = b[0]
            glfwGetWindowSize(window, a, b)
            width = a[0]
            height = b[0]
            glfwSetWindowMaximizeCallback(window) { _, max -> maximized = max }
            glfwSetWindowPosCallback(window) { _, newX, newY -> if (normal()) { x = newX; y = newY } }
            glfwSetWindowSizeCallback(window) { _, newW, newH -> if (normal() && newW > 0 && newH > 0) { width = newW; height = newH } }
        }

        private fun normal() =
            glfwGetWindowAttrib(window, GLFW_MAXIMIZED) == GLFW_FALSE && glfwGetWindowAttrib(window, GLFW_ICONIFIED) == GLFW_FALSE

        fun placement() = WindowPlacement(x, y, width, height, maximized)
    }

    private fun installInput(window: Long) {
        var held = false
        // Hovering sends nothing, so only a held button turns cursor motion into events.
        glfwSetCursorPosCallback(window) { _, x, y -> if (held) emitPointer(window, x, y, true) }
        glfwSetMouseButtonCallback(window) { _, button, action, _ ->
            if (button != GLFW_MOUSE_BUTTON_LEFT) return@glfwSetMouseButtonCallback
            held = action == GLFW_PRESS
            val x = DoubleArray(1)
            val y = DoubleArray(1)
            glfwGetCursorPos(window, x, y)
            emitPointer(window, x[0], y[0], held)
        }
        glfwSetKeyCallback(window) { _, key, _, action, mods -> keyHandler(key, action, mods) }
        glfwSetScrollCallback(window) { _, _, dy -> scrollHandler(dy) }
    }

    /** Converts a cursor position in window units to framebuffer pixels, the space the letterbox is computed in. */
    private fun emitPointer(window: Long, x: Double, y: Double, down: Boolean) {
        val ww = IntArray(1)
        val wh = IntArray(1)
        val fw = IntArray(1)
        val fh = IntArray(1)
        glfwGetWindowSize(window, ww, wh)
        glfwGetFramebufferSize(window, fw, fh)
        // Cache the size the conversion used so the handler maps in exactly that space.
        framebufferSize = Size(fw[0], fh[0])
        inputHandler(PointerEvent(x, y, down).toFramebuffer(Size(ww[0], wh[0]), framebufferSize))
    }

    private fun upload(frame: DecodedFrame, textures: IntArray, sizes: IntArray) {
        val planes = arrayOf(frame.y, frame.u, frame.v)
        val widths = intArrayOf(frame.width, (frame.width + 1) / 2, (frame.width + 1) / 2)
        val heights = intArrayOf(frame.height, (frame.height + 1) / 2, (frame.height + 1) / 2)
        for (plane in 0 until 3) {
            glActiveTexture(GL_TEXTURE0 + plane)
            glBindTexture(GL_TEXTURE_2D, textures[plane])
            // Planes are tightly packed (DecodedFrame.strides == plane widths), so no row length is needed.
            if (sizes[plane * 2] != widths[plane] || sizes[plane * 2 + 1] != heights[plane]) {
                glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, widths[plane], heights[plane], 0, GL_RED, GL_UNSIGNED_BYTE, planes[plane])
                sizes[plane * 2] = widths[plane]
                sizes[plane * 2 + 1] = heights[plane]
            } else {
                glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, widths[plane], heights[plane], GL_RED, GL_UNSIGNED_BYTE, planes[plane])
            }
        }
    }

    /** Fits the picture inside the window at its own aspect ratio; the rest stays black. */
    private fun clear(fw: Int, fh: Int) {
        glViewport(0, 0, fw, fh)
        glClearColor(0f, 0f, 0f, 1f)
        glClear(GL_COLOR_BUFFER_BIT)
    }

    private fun draw(frame: Size, fw: Int, fh: Int) {
        if (fw == 0 || fh == 0) return
        clear(fw, fh)
        val scale = minOf(fw.toDouble() / frame.width, fh.toDouble() / frame.height)
        val w = (frame.width * scale).toInt()
        val h = (frame.height * scale).toInt()
        glViewport((fw - w) / 2, (fh - h) / 2, w, h)
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
    }

    /** Black over the whole window, [alpha] deep, as the fades dim what it shows; nothing at 0. */
    private fun dim(alpha: Float, fw: Int, fh: Int) {
        if (alpha <= 0f || fw == 0 || fh == 0) return
        glViewport(0, 0, fw, fh)
        glUseProgram(dimProgram)
        glUniform1f(dimUniform, alpha)
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
        glDisable(GL_BLEND)
        glUseProgram(program)
    }

    /** The safe-area frame [showFrame] asked for, while it lasts, over the picture as the window fits it in. */
    private fun drawFrame(fw: Int, fh: Int) {
        val request = frame ?: return
        if (System.nanoTime() >= request.until) {
            frame = null
            return
        }
        if (fw == 0 || fh == 0) return
        val shape = frameShape(picture, stream, opened = Size(width, height))
        val rect = safeAreaRect(shape, fw, fh, request.top, request.bottom, request.left, request.right)
        glViewport(0, 0, fw, fh)
        glUseProgram(frameProgram)
        glUniform4f(frameUniform, rect[0], rect[1], rect[2], rect[3])
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
        glUseProgram(program)
    }

    /** Whether a fade is under way, which needs passes of the loop at the screen's pace until it ends. */
    internal val animating: Boolean get() = childFade?.moving == true || viewFade?.moving == true

    private fun buildProgram(fragmentSource: String = FRAGMENT): Int {
        val program = glCreateProgram()
        val vertex = compile(GL_VERTEX_SHADER, VERTEX)
        val fragment = compile(GL_FRAGMENT_SHADER, fragmentSource)
        glAttachShader(program, vertex)
        glAttachShader(program, fragment)
        glLinkProgram(program)
        check(glGetProgrami(program, GL_LINK_STATUS) == GL_TRUE) { "shader link failed: ${glGetProgramInfoLog(program)}" }
        glDeleteShader(vertex)
        glDeleteShader(fragment)
        return program
    }

    private fun compile(type: Int, source: String): Int {
        val shader = glCreateShader(type)
        glShaderSource(shader, source)
        glCompileShader(shader)
        check(glGetShaderi(shader, GL_COMPILE_STATUS) == GL_TRUE) { "shader compile failed: ${glGetShaderInfoLog(shader)}" }
        return shader
    }

    private companion object {
        // A strip of four vertices generated from gl_VertexID: no vertex buffer to manage.
        const val VERTEX = """#version 330 core
out vec2 uv;
void main() {
    vec2 corner = vec2(gl_VertexID & 1, gl_VertexID >> 1);
    uv = vec2(corner.x, 1.0 - corner.y);
    gl_Position = vec4(corner * 2.0 - 1.0, 0.0, 1.0);
}"""

        // BT.709, limited (studio) range: Y 16..235, Cb/Cr 16..240.
        const val FRAGMENT = """#version 330 core
in vec2 uv;
out vec4 color;
uniform sampler2D planeY;
uniform sampler2D planeU;
uniform sampler2D planeV;
// The part of the picture drawn, in texture coordinates (left, top, right, bottom); all of it by default.
uniform vec4 source;
void main() {
    vec2 at = vec2(mix(source.x, source.z, uv.x), mix(source.y, source.w, uv.y));
    float y = (texture(planeY, at).r - 16.0 / 255.0) * (255.0 / 219.0);
    float u = (texture(planeU, at).r - 128.0 / 255.0) * (255.0 / 224.0);
    float v = (texture(planeV, at).r - 128.0 / 255.0) * (255.0 / 224.0);
    color = vec4(y + 1.5748 * v, y - 0.1873 * u - 0.4681 * v, y + 1.8556 * u, 1.0);
}"""

        // The safe-area frame: 4 px thick inside the rectangle (left, bottom, right, top), in 4 px dots and gaps.
        const val FRAME_FRAGMENT = """#version 330 core
out vec4 color;
uniform vec4 rect;
void main() {
    vec2 p = gl_FragCoord.xy;
    float t = 4.0;
    bool inside = p.x >= rect.x && p.x <= rect.z && p.y >= rect.y && p.y <= rect.w;
    bool inner = p.x >= rect.x + t && p.x <= rect.z - t && p.y >= rect.y + t && p.y <= rect.w - t;
    if (!inside || inner) discard;
    bool horizontal = p.y < rect.y + t || p.y > rect.w - t;
    float along = horizontal ? p.x - rect.x : p.y - rect.y;
    if (mod(floor(along / t), 2.0) == 1.0) discard;
    color = vec4(0.188, 0.820, 0.345, 1.0);
}"""

        // Black, blended over what is drawn: the fades' depth.
        const val DIM_FRAGMENT = """#version 330 core
out vec4 color;
uniform float alpha;
void main() {
    color = vec4(0.0, 0.0, 0.0, alpha);
}"""
    }
}

/**
 * Runs the GLFW event loop for [main] and [others] on the calling (main) thread until [main] closes. GLFW wants
 * every window and its events on one thread, so the windows take turns after each wake-up. Closing one of
 * [others] closes only that window.
 */
/** The wait between passes while a fade runs: a little over a frame at 120 Hz. */
private const val FADE_FRAME_SECONDS = 0.008

fun runWindows(main: VideoWindow, vararg others: VideoWindow) {
    GLFWErrorCallback.createPrint(System.err).set()
    check(glfwInit()) { "cannot initialise GLFW" }
    val open = mutableListOf(main)
    try {
        main.open()
        for (window in others) {
            window.open()
            open += window
        }
        while (true) {
            // A fade under way draws at about the screen's pace; otherwise the loop sleeps until something happens.
            if (open.any { it.animating }) glfwWaitEventsTimeout(FADE_FRAME_SECONDS) else glfwWaitEvents()
            if (!main.step()) break
            for (window in open.drop(1)) {
                if (!window.step()) {
                    window.dispose()
                    open -= window
                }
            }
        }
    } finally {
        open.forEach { it.dispose() }
        glfwTerminate()
        glfwSetErrorCallback(null)?.free()
    }
}
