package io.github.numberonebot.chairplay.hud

import io.github.numberonebot.chairplay.data.Artwork

/**
 * The cover the HUD draws for a song: the artwork the iPhone named for it, once in. As playback starts the iPhone
 * names a new cover for the song already on screen and sends it some 100 ms later; the song keeps the cover it had
 * meanwhile, rather than blinking to the empty one. Another song never shows the last one's cover.
 */
internal class SongCover {
    private var song: Any? = null
    private var cover: Artwork? = null

    /** The cover for [song], which names [artworkId], with [artwork] the last the iPhone sent; null for the empty one. */
    fun pick(song: Any, artworkId: Int?, artwork: Artwork?): Artwork? {
        if (song != this.song) {
            this.song = song
            cover = null
        }
        artwork?.takeIf { it.transferId == artworkId && it.size > 0 }?.let { cover = it }
        return cover
    }
}
