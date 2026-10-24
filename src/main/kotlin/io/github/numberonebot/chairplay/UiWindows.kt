package io.github.numberonebot.chairplay

import io.github.numberonebot.chairplay.media.VideoWindow
import io.github.numberonebot.chairplay.ui.UiMainWindow
import io.github.numberonebot.chairplay.ui.UiWindow

/** A window as the settings screen's triggers see it; there for them while [available] says so. */
internal fun VideoWindow.asUiWindow(available: () -> Boolean = { true }) = object : UiWindow {
    override val visible get() = this@asUiWindow.visible
    override val available get() = available()
    override fun setShown(shown: Boolean) = this@asUiWindow.setShown(shown)
}

/** A window for its page's Actual size, which brings it to the picture it shows now; there while [available] says so. */
internal fun VideoWindow.asUiMainWindow(available: () -> Boolean = { true }) = object : UiMainWindow {
    override val available get() = available()
    override val width get() = framebufferSize.width
    override val height get() = framebufferSize.height
    override val minWidth get() = minimumSize.width
    override val minHeight get() = minimumSize.height
    override val picture get() = stream
    override fun resizeTo(width: Int, height: Int) = this@asUiMainWindow.resizeTo(width, height)
}
