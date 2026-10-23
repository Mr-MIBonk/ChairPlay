package io.github.numberonebot.chairplay.hud

import io.github.numberonebot.chairplay.data.Artwork
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class SongCoverTest {
    private val cover = SongCover()
    private val first = Artwork(131, byteArrayOf(1))
    private val again = Artwork(132, byteArrayOf(2))

    // As playback starts the iPhone names a new cover for the song on screen, and sends it a moment later: the cover
    // on screen stays meanwhile instead of blinking to the empty one.
    @Test
    fun `the same song keeps its cover until the new one is in`() {
        assertSame(first, cover.pick("Careless Whisper", 131, first))
        assertSame(first, cover.pick("Careless Whisper", 132, first))
        assertSame(again, cover.pick("Careless Whisper", 132, again))
    }

    // Another song never shows the last one's cover: it waits on the empty cover for its own.
    @Test
    fun `another song waits for its own cover`() {
        cover.pick("Careless Whisper", 131, first)

        assertNull(cover.pick("Last Christmas", 132, first))
        assertSame(again, cover.pick("Last Christmas", 132, again))
    }

    @Test
    fun `an empty artwork is no cover`() {
        assertNull(cover.pick("Careless Whisper", 131, Artwork(131, ByteArray(0))))
    }
}
