package com.shilapi.xcertplay.desktop.data

import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.transport.Iap2ArtworkTransfer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class CarPlayDataTest {
    private val dir = Files.createTempDirectory("diplay-data")
    private val power = Iap2Messages.buildRaw(0xae01) { u16(6, 0xffff) }

    @Test
    fun `frames and artwork reach the bus and the log`() {
        val log = CarPlayDataLog(dir)
        val data = CarPlayData(log)

        data.onFrame(DataLink.WIFI, power)
        data.onArtwork(DataLink.WIFI, Iap2ArtworkTransfer(3, byteArrayOf(9)))
        data.close()

        assertEquals(100, data.bus.snapshot().power!!.batteryPercent)
        assertEquals(Artwork(3, byteArrayOf(9)), data.bus.snapshot().artwork)
        val lines = Files.readAllLines(log.file!!)
        assertEquals(2, lines.size)
        assertTrue(lines[0], lines[0].contains("\"name\":\"PowerUpdate\""))
        assertTrue(lines[1], lines[1].contains("\"name\":\"NowPlayingArtwork\""))
    }

    @Test
    fun `without a log the bus still hears everything and the end of a session clears it`() {
        val data = CarPlayData(null)

        data.onFrame(DataLink.BLUETOOTH, power)
        assertEquals(0xffff, data.bus.snapshot().power!!.chargeLevel)

        data.onSessionEnded()
        assertEquals(CarPlayDataSnapshot(), data.bus.snapshot())
    }
}
