package io.github.numberonebot.chairplay.session

import io.github.numberonebot.chairplay.media.AudioDuckSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlayCommandsTest {
    @Test
    fun `a command's line names its type and its simple params in key order`() {
        val line = describeCommand("duckAudio", mapOf("volume" to 0.2, "durationMs" to 500L, "fade" to true))

        assertEquals("airplay command duckAudio: durationMs=500, fade=true, volume=0.2", line)
    }

    @Test
    fun `a command without params says so`() {
        assertEquals("airplay command unduckAudio (no params)", describeCommand("unduckAudio", emptyMap()))
    }

    @Test
    fun `blobs, long texts and nested values are only described`() {
        val line = describeCommand("requestUI", linkedMapOf(
            "data" to ByteArray(300),
            "url" to "maps:",
            "note" to "x".repeat(200),
            "info" to mapOf("a" to 1, "b" to 2),
            "items" to listOf(1, 2, 3),
            "none" to null,
        ))

        assertEquals(
            "airplay command requestUI: data=<300 bytes>, info=<2 entries>, items=<3 items>, none=null, " +
                "note=<200 chars>, url=\"maps:\"",
            line,
        )
    }

    @Test
    fun `values under secret-sounding keys are hidden`() {
        val line = describeCommand("x", mapOf("pairingKey" to "abcd", "token" to 12345L, "password" to "hunter2"))

        assertEquals("airplay command x: pairingKey=<hidden>, password=<hidden>, token=<hidden>", line)
    }

    private class RecordingSink : AudioDuckSink {
        val ducks = mutableListOf<Pair<Boolean, Map<String, Any?>>>()
        override fun onAudioDuck(ducked: Boolean, params: Map<String, Any?>) {
            ducks += ducked to params
        }
    }

    private val sink = RecordingSink()
    private val logs = mutableListOf<String>()
    private val commands = AudioDuckCommands(sink) { logs += it }
    private val session = Any()
    private val other = Any()

    @Test
    fun `duckAudio and unduckAudio reach the sink with their params and other commands do not`() {
        commands.onCommand(session, "duckAudio", mapOf("durationMs" to 500L))
        commands.onCommand(session, "modesChanged", mapOf("x" to 1))
        commands.onCommand(session, "unduckAudio", emptyMap())

        assertEquals(listOf(true to mapOf("durationMs" to 500L), false to emptyMap<String, Any?>()), sink.ducks)
    }

    // A phone that disconnects during a prompt never sends unduckAudio.
    @Test
    fun `the ducking session's end unducks once`() {
        commands.onCommand(session, "duckAudio", emptyMap())

        commands.onSessionEnded(other)
        assertEquals(1, sink.ducks.size)
        commands.onSessionEnded(session)
        commands.onSessionEnded(session)

        assertEquals(listOf(true, false), sink.ducks.map { it.first })
        assertTrue(logs.toString(), logs.single().contains("without unduckAudio"))
    }

    @Test
    fun `a session that unducked ends without another unduck`() {
        commands.onCommand(session, "duckAudio", emptyMap())
        commands.onCommand(session, "unduckAudio", emptyMap())

        commands.onSessionEnded(session)

        assertEquals(listOf(true, false), sink.ducks.map { it.first })
    }

    @Test
    fun `without a sink that ducks the commands go nowhere`() {
        val none = AudioDuckCommands(null) {}
        none.onCommand(session, "duckAudio", emptyMap())
        none.onSessionEnded(session)
    }
}
