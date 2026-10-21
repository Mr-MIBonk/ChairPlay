package com.shilapi.xcertplay.desktop.data

import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.transport.Iap2ArtworkTransfer
import java.io.Closeable

/** What the wireless bring-up hands over from its iAP2 links besides the bring-up itself. */
interface CarPlayDataTap {
    /** A frame the control loop did not handle itself, on that link's control thread. */
    fun onFrame(link: DataLink, frame: Iap2Frame) {}

    /** A completed artwork file transfer; empty bytes clear the artwork. */
    fun onArtwork(link: DataLink, transfer: Iap2ArtworkTransfer) {}

    /** The bring-up that delivered the data is over: its session ended or failed. */
    fun onSessionEnded() {}

    companion object {
        val NONE: CarPlayDataTap = object : CarPlayDataTap {}
    }
}

/** The data [bus] and, with log.carplay.data, the data [log], fed by the iAP2 links of every CarPlay session. */
class CarPlayData(private val log: CarPlayDataLog?) : CarPlayDataTap, Closeable {
    val bus = CarPlayDataBus()

    override fun onFrame(link: DataLink, frame: Iap2Frame) {
        if (!CarPlayDataMessages.isData(frame.messageId)) return
        log?.frame(link, frame)
        bus.accept(frame)
    }

    override fun onArtwork(link: DataLink, transfer: Iap2ArtworkTransfer) {
        log?.artwork(link, transfer)
        bus.acceptArtwork(transfer)
    }

    override fun onSessionEnded() = bus.clear()

    override fun close() {
        log?.close()
    }
}
