package io.github.numberonebot.chairplay.hud

import io.github.numberonebot.chairplay.data.Artwork
import org.lwjgl.nanovg.NanoVG.*
import kotlin.math.min
import kotlin.math.roundToInt

// The HUD's song: its cover and lines, drawn by HudPainter.

/**
 * The song: the cover, and at its right the title, artist and album on lines of their own, a progress bar and the
 * app, all centred, as the iPhone's Now Playing sets them; centred in the [box] with its cover's size, [presence] of
 * the way out of the dark.
 */
internal fun HudPainter.media(media: HudMedia, now: HudView, box: HudBox, h: Float, presence: Float) {
    val gap = MEDIA_GAP * h
    val coverSize = box.cover
    val blockWidth = mediaBlockWidth(box, h)
    val textWidth = blockWidth - coverSize - gap
    val x = box.left + (box.right - box.left - blockWidth) / 2
    val y = h / 2 - coverSize / 2
    emerging(presence, x + blockWidth / 2, h / 2) { mediaBlock(media, now, x, y, coverSize, gap, textWidth, h, box.text) }
}

/**
 * How wide the song's block is in [box] of an [h] high HUD: alone it keeps to 80 % of the width its lines could take
 * beside the cover; beside the route its column bounds it. It is as tall as its cover.
 */
internal fun mediaBlockWidth(box: HudBox, h: Float): Float = min(box.right - box.left, (0.9f * h * 1.4f + MEDIA_GAP * h + box.cover) * 0.8f)

// 40 px on the 540 px AltScreen.
private const val MEDIA_GAP = 0.074f

/** One line of the song's text block: its [height], the [space] above it, and how it draws at its middle. */
private class Row(val height: Float, val space: Float, val draw: (Float) -> Unit)

private fun HudPainter.mediaBlock(
    media: HudMedia, now: HudView, x: Float, y: Float, coverSize: Float, gap: Float, textWidth: Float, h: Float, scale: Float,
) {
    drawCover(songCover.pick(listOf(media.title, media.artist, media.album), media.artworkId, now.artwork), x, y, coverSize)
    val tx = x + coverSize + gap
    val cx = tx + textWidth / 2
    // In whole pixels: while the song moves between the middle and its column, every size between would be
    // another set of glyphs for NanoVG to render.
    val fullSize = (0.10f * h * scale).roundToInt().toFloat()
    val lineSize = (fullSize * 0.6f).roundToInt().toFloat()
    val sourceSize = 0.04f * h
    val barHeight = 0.012f * h
    val space = lineSize * 0.35f
    // A long title shrinks a little before it is cut; the lines under it keep their size and place.
    val title = media.title.orEmpty()
    val titleSize = fitSize(title, textWidth, fullSize, fullSize * 0.8f, BOLD).roundToInt().toFloat()
    val rows = buildList<Row> {
        add(Row(fullSize, 0f) { cy -> centred(fit(title, textWidth, titleSize, BOLD), cx, cy, titleSize, WHITE, BOLD) })
        // The artist in white, as the title; the album, as the iPhone's dims it, in grey.
        for ((line, rgb) in listOfNotNull(media.artist?.to(WHITE), media.album?.to(GREY))) {
            add(Row(lineSize, space) { cy -> centred(fit(line, textWidth, lineSize, REGULAR), cx, cy, lineSize, rgb, REGULAR) })
        }
        media.progress?.let { progress ->
            add(Row(barHeight, space * 2.5f) { cy ->
                nvgBeginPath(vg)
                nvgRoundedRect(vg, tx, cy - barHeight / 2, textWidth, barHeight, barHeight / 2)
                fill(DIM)
                nvgBeginPath(vg)
                nvgRoundedRect(vg, tx, cy - barHeight / 2, textWidth * progress, barHeight, barHeight / 2)
                fill(accentColor)
            })
        }
        media.source?.let { source ->
            add(Row(sourceSize, space * 2f) { cy ->
                // The play state and the app as one centred line.
                val icon = sourceSize * 0.7f * (if (media.playing) 0.85f else 0.8f) + sourceSize * 0.5f
                val name = fit(source, textWidth - icon, sourceSize, REGULAR)
                val left = cx - (icon + measure(name, sourceSize, REGULAR)) / 2
                playState(media.playing, left, cy, sourceSize)
                text(name, left + icon, cy, sourceSize, GREY, REGULAR, NVG_ALIGN_LEFT)
            })
        }
    }
    // The lines stand together, centred on the cover; a line the song lacks keeps no room.
    var top = y + coverSize / 2 - rows.sumOf { (it.space + it.height).toDouble() }.toFloat() / 2
    for (row in rows) {
        top += row.space
        row.draw(top + row.height / 2)
        top += row.height
    }
}

private fun HudPainter.drawCover(artwork: Artwork?, x: Float, y: Float, size: Float) {
    val id = artwork?.transferId
    if (id != coverId) {
        if (cover >= 0) nvgDeleteImage(vg, cover)
        cover = -1
        coverId = id
        // Decoded in the JVM with limits (coverPixels), not by NanoVG's native decoder.
        artwork?.let { coverPixels(it.bytes) }?.let { pixels ->
            cover = nvgCreateImageRGBA(vg, pixels.width, pixels.height, 0, pixels.rgba)
        }
    }
    // Barely rounded: softer than the iPhone draws its covers.
    val radius = size * 0.02f
    nvgBeginPath(vg)
    nvgRoundedRect(vg, x, y, size, size, radius)
    if (cover >= 0) {
        nvgImagePattern(vg, x, y, size, size, 0f, cover, 1f, paint)
        nvgFillPaint(vg, paint)
        nvgFill(vg)
    } else {
        fill(DIM)
        note(x, y, size)
    }
}

/** The soft, filled note from packaging/hud, centred on the empty cover and uploaded once per GL context. */
private fun HudPainter.note(x: Float, y: Float, size: Float) {
    if (emptyCoverNote < 0) return
    val s = size * 0.70f
    val left = x + (size - s) / 2
    val top = y + (size - s) / 2
    nvgBeginPath(vg)
    nvgRect(vg, left, top, s, s)
    nvgImagePattern(vg, left, top, s, s, 0f, emptyCoverNote, 1f, paint)
    nvgFillPaint(vg, paint)
    nvgFill(vg)
}

/** A play triangle or two pause bars at [x], centred on [y]; returns the width used. */
private fun HudPainter.playState(playing: Boolean, x: Float, y: Float, size: Float): Float {
    val h = size * 0.7f
    if (playing) {
        nvgBeginPath(vg)
        nvgMoveTo(vg, x, y - h / 2)
        nvgLineTo(vg, x + h * 0.85f, y)
        nvgLineTo(vg, x, y + h / 2)
        nvgClosePath(vg)
        fill(GREY)
        return h * 0.85f
    }
    for (bar in 0..1) {
        nvgBeginPath(vg)
        nvgRect(vg, x + bar * h * 0.5f, y - h / 2, h * 0.3f, h)
        fill(GREY)
    }
    return h * 0.8f
}
