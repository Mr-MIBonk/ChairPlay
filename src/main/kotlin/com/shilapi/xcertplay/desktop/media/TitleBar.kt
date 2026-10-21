package com.shilapi.xcertplay.desktop.media

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference

/**
 * Paints a window's title bar black through the Desktop Window Manager (dwmapi.h). Windows 11 takes
 * the caption and text colours; Windows 10 from 20H1 knows only dark mode, which is near black.
 * An attribute the system does not know fails on its own and leaves the default bar.
 */
internal object TitleBar {
    private interface Dwm : Library {
        fun DwmSetWindowAttribute(hwnd: Pointer, attribute: Int, value: IntByReference, size: Int): Int
    }

    private val dwm: Dwm? by lazy { runCatching { Native.load("dwmapi", Dwm::class.java) }.getOrNull() }

    fun paintBlack(hwnd: Long) {
        val api = dwm ?: return
        if (hwnd == 0L) return
        val window = Pointer(hwnd)
        api.DwmSetWindowAttribute(window, DWMWA_USE_IMMERSIVE_DARK_MODE, IntByReference(1), Int.SIZE_BYTES)
        api.DwmSetWindowAttribute(window, DWMWA_CAPTION_COLOR, IntByReference(BLACK), Int.SIZE_BYTES)
        api.DwmSetWindowAttribute(window, DWMWA_TEXT_COLOR, IntByReference(WHITE), Int.SIZE_BYTES)
    }

    private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
    private const val DWMWA_CAPTION_COLOR = 35
    private const val DWMWA_TEXT_COLOR = 36

    // COLORREF is 0x00BBGGRR.
    private const val BLACK = 0x000000
    private const val WHITE = 0xFFFFFF
}
