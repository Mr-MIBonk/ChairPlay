package io.github.numberonebot.chairplay.hud

import io.github.numberonebot.chairplay.data.ManeuverType
import io.github.numberonebot.chairplay.hud.maneuver.ManeuverPlacement
import org.lwjgl.nanovg.NanoVG.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

// The HUD's route: the maneuver, by the renderer or a simple arrow, and its lines, drawn by HudPainter.

/**
 * Everything about the route in one block, centred in the [box]: the arrow, and beside it the distance to the
 * maneuver, the road, the arrival with the time and distance left, and the destination; [presence] of the way out
 * of the dark. Always in this one layout, in the column beside the song ([routeBox]): the caller moves and scales it,
 * and the renderer draws its picture [sharpHeight] tall, the largest it is scaled to, so it stays sharp and never
 * rebuilds its size.
 */
internal fun HudPainter.navigation(maneuver: HudManeuver, arrival: HudArrival?, box: HudBox, h: Float, presence: Float, sharpHeight: Float) {
    val block = routeBlock(maneuver, arrival, box, h)
    val (viewWidth, viewHeight, gap, textWidth, lines) = block
    val left = box.left
    val right = box.right
    val top = HUD_PAD * h
    val bottom = h - top
    val arrowSize = 0.30f * h
    val x = left + (right - left - block.width) / 2
    val blockHeight = lines.sumOf { (it.size * it.spacing).toDouble() }.toFloat()
    val cy = (top + bottom) / 2
    emerging(presence, x + block.width / 2, cy) {
        if (!maneuverView.draw(vg, maneuver, x, cy - viewHeight / 2, viewWidth, viewHeight, sharpHeight)) {
            arrow(maneuver.typeCode, x + (viewWidth - arrowSize) / 2, cy - arrowSize / 2, arrowSize)
        }
        // The lines centred in their column, as the song's are.
        val tx = x + viewWidth + gap + textWidth / 2
        var y = cy - blockHeight / 2
        for (line in lines) {
            y += line.size * line.spacing / 2
            text(fit(line.text, textWidth, line.size, line.font), tx, y, line.size, line.rgb, line.font, NVG_ALIGN_CENTER)
            y += line.size * line.spacing / 2
        }
    }
}

/** The route's block in a box: the picture, the gap after it, the text column, and the lines in it. */
internal data class RouteBlock(val viewWidth: Float, val viewHeight: Float, val gap: Float, val textWidth: Float, val lines: List<Line>) {
    val width: Float get() = viewWidth + gap + textWidth
    val height: Float get() = maxOf(viewHeight, lines.sumOf { (it.size * it.spacing).toDouble() }.toFloat())
}

/** How the route's block lays out in [box] of an [h] high HUD. */
internal fun HudPainter.routeBlock(maneuver: HudManeuver, arrival: HudArrival?, box: HudBox, h: Float): RouteBlock {
    val top = HUD_PAD * h
    val (viewWidth, viewHeight) = maneuverSize(box.left, box.right, top, h - top, h)
    // The picture's sides fade out, so the text can come closer.
    val gap = 0.03f * h
    val roadSize = 0.06f * h
    val smallSize = 0.045f * h
    val textWidth = min(box.right - box.left - viewWidth - gap, 0.9f * h * 1.4f)
    // The distance stands out; a long one shrinks a little before it is cut.
    val distanceSize = fitSize(maneuver.distance ?: "", textWidth, 0.14f * h, 0.10f * h, WIDE)
    val road = maneuver.road ?: maneuver.description
    val arrivalLine = arrival
        ?.let { listOfNotNull(it.time?.let { time -> "Arrival $time" }, it.remaining).joinToString("  ·  ") }
        ?.ifEmpty { null }
    val lines = buildList {
        add(Line(maneuver.distance ?: "", distanceSize, WHITE, WIDE, 1.0f))
        road?.let { add(Line(it, roadSize, GREY, REGULAR, 1.25f)) }
        arrivalLine?.let { add(Line(it, smallSize, WHITE, REGULAR, 1.9f)) }
        arrival?.destination?.let { add(Line(it, smallSize, GREY, REGULAR, 1.35f)) }
    }
    return RouteBlock(viewWidth, viewHeight, gap, textWidth, lines)
}

/** The renderer's picture in a block: as tall as the block allows but no more than half its width. */
private fun HudPainter.maneuverSize(left: Float, right: Float, top: Float, bottom: Float, h: Float): Pair<Float, Float> {
    var viewHeight = min(MANEUVER_HEIGHT * h, bottom - top)
    var viewWidth = viewHeight * ManeuverPlacement.ASPECT
    if (viewWidth > (right - left) / 2) {
        viewWidth = (right - left) / 2
        viewHeight = viewWidth / ManeuverPlacement.ASPECT
    }
    return viewWidth to viewHeight
}

/** One line of a text block: [spacing] is the height it takes, in multiples of its [size]. */
internal class Line(val text: String, val size: Float, val rgb: Int, val font: String, val spacing: Float)

/**
 * A simple arrow for when the MHI2Q project's renderer cannot run or draws nothing for the maneuver: a stem from the bottom,
 * bending toward the turn's angle; a ring with the exit for roundabouts, a dot for arriving.
 */
private fun HudPainter.arrow(typeCode: Int?, x: Float, y: Float, size: Float) {
    val type = ManeuverType.entries.firstOrNull { it.code == typeCode }
    val cx = x + size / 2
    val stroke = size * 0.11f
    nvgStrokeWidth(vg, stroke)
    nvgLineCap(vg, NVG_ROUND)
    nvgLineJoin(vg, NVG_ROUND)
    val exit = type?.roundaboutExit
    if (exit != null || type == ManeuverType.ENTER_ROUNDABOUT || type == ManeuverType.EXIT_ROUNDABOUT) {
        nvgBeginPath(vg)
        nvgCircle(vg, cx, y + size * 0.45f, size * 0.26f)
        stroke(accentColor)
        exit?.let { centred(it.toString(), cx, y + size * 0.45f, size * 0.3f, WHITE, BOLD) }
        return
    }
    if (type in ARRIVALS) {
        nvgBeginPath(vg)
        nvgCircle(vg, cx, y + size * 0.42f, size * 0.2f)
        fill(accentColor)
        return
    }
    val degrees = when (type) {
        ManeuverType.LEFT_TURN, ManeuverType.LEFT_TURN_AT_END -> -90f
        ManeuverType.RIGHT_TURN, ManeuverType.RIGHT_TURN_AT_END -> 90f
        ManeuverType.SLIGHT_LEFT_TURN, ManeuverType.KEEP_LEFT, ManeuverType.HIGHWAY_OFF_RAMP_LEFT, ManeuverType.CHANGE_HIGHWAY_LEFT -> -40f
        ManeuverType.SLIGHT_RIGHT_TURN, ManeuverType.KEEP_RIGHT, ManeuverType.HIGHWAY_OFF_RAMP_RIGHT, ManeuverType.CHANGE_HIGHWAY_RIGHT,
        ManeuverType.OFF_RAMP -> 40f
        ManeuverType.SHARP_LEFT_TURN -> -135f
        ManeuverType.SHARP_RIGHT_TURN -> 135f
        ManeuverType.U_TURN, ManeuverType.U_TURN_WHEN_POSSIBLE, ManeuverType.START_ROUTE_WITH_U_TURN -> 180f
        else -> 0f
    }
    val bendX = cx
    val bendY = y + size * 0.48f
    val angle = degrees * PI.toFloat() / 180f
    val length = size * if (degrees == 180f) 0.0f else 0.36f
    nvgBeginPath(vg)
    nvgMoveTo(vg, cx, y + size * 0.95f)
    nvgLineTo(vg, bendX, bendY)
    val (tipX, tipY) = if (degrees == 180f) {
        // A U-turn: up, over and back down on the left.
        nvgArc(vg, cx - size * 0.16f, bendY, size * 0.16f, 0f, PI.toFloat(), NVG_CCW)
        nvgLineTo(vg, cx - size * 0.32f, bendY + size * 0.22f)
        (cx - size * 0.32f) to (bendY + size * 0.22f)
    } else {
        val tx = bendX + sin(angle) * length
        val ty = bendY - cos(angle) * length
        nvgLineTo(vg, tx, ty)
        tx to ty
    }
    stroke(accentColor)
    // The head points along the last segment.
    val headAngle = if (degrees == 180f) PI.toFloat() else angle
    val head = size * 0.2f
    nvgBeginPath(vg)
    nvgMoveTo(vg, tipX + sin(headAngle) * head * 0.6f, tipY - cos(headAngle) * head * 0.6f)
    nvgLineTo(vg, tipX + sin(headAngle - 2.4f) * head, tipY - cos(headAngle - 2.4f) * head)
    nvgLineTo(vg, tipX + sin(headAngle + 2.4f) * head, tipY - cos(headAngle + 2.4f) * head)
    nvgClosePath(vg)
    fill(accentColor)
}
