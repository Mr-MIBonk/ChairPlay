package io.github.numberonebot.chairplay.input

import kotlin.math.hypot
import kotlin.math.min

/** One button of the controls window, in framebuffer pixels; a [round] one is the circle inside its box. */
data class ControlButton(
    val action: ControlAction,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val round: Boolean = false,
) {
    val centreX get() = (left + right) / 2
    val centreY get() = (top + bottom) / 2
    val width get() = right - left
    val height get() = bottom - top

    fun contains(x: Float, y: Float): Boolean =
        if (round) hypot(x - centreX, y - centreY) <= min(width, height) / 2
        else x >= left && x < right && y >= top && y < bottom
}

/**
 * The controls window's buttons for a [width] x [height] framebuffer: on the left a car's knob, select in the
 * middle, tilts around it and the turns above its corners; on the right rows of back, home and Siri, the media
 * keys, the call keys, and the AltScreen's view, cards and zoom. Everything scales with the window.
 */
fun controlsLayout(width: Float, height: Float): List<ControlButton> {
    val pad = 0.05f * min(width, height)
    val knob = min(height - 2 * pad, width * 0.45f - 2 * pad)
    val cx = pad + knob / 2
    val cy = height / 2
    fun circle(action: ControlAction, x: Float, y: Float, radius: Float) =
        ControlButton(action, x - radius, y - radius, x + radius, y + radius, round = true)
    val reach = knob * 0.36f
    val tilt = knob * 0.12f
    val turn = knob * 0.1f
    val buttons = mutableListOf(
        circle(ControlAction.SELECT, cx, cy, knob * 0.2f),
        circle(ControlAction.KNOB_UP, cx, cy - reach, tilt),
        circle(ControlAction.KNOB_DOWN, cx, cy + reach, tilt),
        circle(ControlAction.KNOB_LEFT, cx - reach, cy, tilt),
        circle(ControlAction.KNOB_RIGHT, cx + reach, cy, tilt),
        circle(ControlAction.KNOB_PREVIOUS, cx - reach, cy - reach, turn),
        circle(ControlAction.KNOB_NEXT, cx + reach, cy - reach, turn),
    )
    val rows = listOf(
        listOf(ControlAction.BACK, ControlAction.HOME, ControlAction.SIRI),
        listOf(ControlAction.MEDIA_PREVIOUS, ControlAction.MEDIA_PLAY_PAUSE, ControlAction.MEDIA_NEXT),
        listOf(ControlAction.CALL_ANSWER, ControlAction.CALL_END),
        // The AltScreen: its next view (F11), where its cards go (Shift+F11), and its map's zoom.
        listOf(ControlAction.ALTSCREEN_CONTENT, ControlAction.ALTSCREEN_CARDS, ControlAction.ALTSCREEN_ZOOM_OUT, ControlAction.ALTSCREEN_ZOOM_IN),
    )
    val gap = pad * 0.6f
    val left = pad + knob + pad
    val right = width - pad
    val rowHeight = (height - 2 * pad - gap * (rows.size - 1)) / rows.size
    rows.forEachIndexed { row, actions ->
        val top = pad + row * (rowHeight + gap)
        val cell = (right - left - gap * (actions.size - 1)) / actions.size
        actions.forEachIndexed { column, action ->
            val x = left + column * (cell + gap)
            buttons += ControlButton(action, x, top, x + cell, top + rowHeight)
        }
    }
    return buttons
}

/** The button under ([x], [y]), or null. */
fun controlAt(buttons: List<ControlButton>, x: Float, y: Float): ControlAction? =
    buttons.firstOrNull { it.contains(x, y) }?.action
