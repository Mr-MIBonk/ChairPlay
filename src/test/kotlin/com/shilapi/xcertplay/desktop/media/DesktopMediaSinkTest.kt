package com.shilapi.xcertplay.desktop.media

import com.shilapi.xcertplay.airplay.VideoCodec
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A VideoWindow that never runs opens no window, so the sink is tested whole. */
class DesktopMediaSinkTest {
    private fun resource(name: String) =
        javaClass.getResourceAsStream("/h264/$name")!!.use { it.readBytes() }

    @Test
    fun `recovery handler that ends the stream does not free the decoder mid-decode`() {
        val sink = DesktopMediaSink(VideoWindow(320, 240), AudioRenderer())
        val handlerDone = CountDownLatch(1)
        val handlerThread = arrayOfNulls<Thread>(1)
        sink.setVideoRecoveryHandler(110) {
            handlerThread[0] = Thread.currentThread()
            // What a failed forceKeyFrame send does: the session closes and stops the stream.
            sink.onScreenStreamActive(110, false)
            handlerDone.countDown()
        }
        sink.onVideoCodec(110, VideoCodec.H264)
        sink.onVideoConfig(110, resource("avcC.bin"))
        // Slice NALs with a corrupt body: the decoder rejects them.
        val corrupt = byteArrayOf(0, 0, 0, 1, 0x65, 0x00, 0x00, 0x00, 0x03, 0x7f, 0x01)
        sink.onVideoFrame(110, corrupt, 0, 0)
        val caller = Thread.currentThread()
        assertTrue("recovery was never requested", handlerDone.await(5, TimeUnit.SECONDS))
        assertTrue("recovery ran on the decoding thread", handlerThread[0] !== caller)
        // The stream is gone; frames after it are dropped rather than touching freed memory.
        sink.onVideoFrame(110, resource("frame0.h264"), 0, 0)
        sink.close()
    }
}
