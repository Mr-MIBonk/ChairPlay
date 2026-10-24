package io.github.numberonebot.chairplay

import io.github.numberonebot.chairplay.config.DesktopConfig
import io.github.numberonebot.chairplay.config.WindowSize
import io.github.numberonebot.chairplay.config.mainWindowTitle
import io.github.numberonebot.chairplay.media.VideoWindow
import java.util.concurrent.atomic.AtomicBoolean

private const val CONNECTING = "connecting…"
private const val RECONNECTING = "reconnecting…"
private const val NOT_CONNECTED = "not connected"
private const val CLOSING_PREVIOUS = "closing previous run…"

/**
 * The main [window]'s title: the size of the picture it shows, and, while there is no session to show, where the
 * connection stands and why.
 */
internal class MainTitle(private val window: VideoWindow, private val config: DesktopConfig) {
    /** Set while Reconnect is under way: the title says it reconnects rather than connects until a session is up. */
    val reconnecting = AtomicBoolean()

    private fun stream() = window.stream ?: WindowSize(config.displayWidth, config.displayHeight)

    /** A session shows: the picture's size alone. */
    fun session() = window.setTitle(mainWindowTitle(stream()))

    /** Connecting, or reconnecting after Reconnect, to a picture of [size]. */
    fun connecting(size: WindowSize = stream()) =
        window.setTitle(mainWindowTitle(size, if (reconnecting.get()) RECONNECTING else CONNECTING))

    /** Reconnect pressed. */
    fun reconnect() {
        reconnecting.set(true)
        connecting()
    }

    fun notConnected() = window.setTitle(mainWindowTitle(stream(), NOT_CONNECTED))

    fun closingPrevious() = window.setTitle(mainWindowTitle(stream(), CLOSING_PREVIOUS))

    companion object {
        /** The title the main window opens with, before anything else is known. */
        fun opening(config: DesktopConfig) = mainWindowTitle(WindowSize(config.displayWidth, config.displayHeight), CONNECTING)
    }
}
