package io.github.numberonebot.chairplay.session

import io.github.numberonebot.chairplay.media.AudioDuckSink
import java.util.concurrent.atomic.AtomicReference

/**
 * A command's line for the log: its type and its params in key order. Numbers, booleans and short texts are shown;
 * data, long texts, dictionaries and lists only by their size, and anything under a key that sounds secret not at all.
 */
internal fun describeCommand(type: String, params: Map<String, Any?>): String {
    if (params.isEmpty()) return "airplay command $type (no params)"
    val shown = params.entries.sortedBy { it.key }.joinToString(", ") { (key, value) -> "$key=${describeValue(key, value)}" }
    return "airplay command $type: $shown"
}

private fun describeValue(key: String, value: Any?): String = when {
    value == null -> "null"
    SECRET_KEY.containsMatchIn(key) -> "<hidden>"
    value is Number || value is Boolean -> value.toString()
    value is String -> if (value.length <= MAX_SHOWN_TEXT) "\"$value\"" else "<${value.length} chars>"
    value is ByteArray -> "<${value.size} bytes>"
    value is Map<*, *> -> "<${value.size} entries>"
    value is Collection<*> -> "<${value.size} items>"
    else -> "<${value.javaClass.simpleName}>"
}

private val SECRET_KEY = Regex("key|secret|token|passw|passphrase|signature|certificate", RegexOption.IGNORE_CASE)
private const val MAX_SHOWN_TEXT = 64

/**
 * Unlike DiPlay 0.2.22's Boolean-only callback, this preserves volume and ramp parameters. Commands go to
 * [sink], and the end of the session that ducked, which then never sends its unduckAudio, unducks. Commands come on
 * each session's RTSP thread.
 */
internal class AudioDuckCommands(private val sink: AudioDuckSink?, private val log: (String) -> Unit) {
    private val duckingSession = AtomicReference<Any?>()

    fun onCommand(session: Any, type: String, params: Map<String, Any?>) {
        when (type) {
            DUCK -> {
                duckingSession.set(session)
                sink?.onAudioDuck(true, params)
            }
            UNDUCK -> {
                duckingSession.compareAndSet(session, null)
                sink?.onAudioDuck(false, params)
            }
        }
    }

    fun onSessionEnded(session: Any) {
        if (!duckingSession.compareAndSet(session, null)) return
        log("airplay session that sent duckAudio ended without unduckAudio; unducking")
        sink?.onAudioDuck(false)
    }

    private companion object {
        const val DUCK = "duckAudio"
        const val UNDUCK = "unduckAudio"
    }
}
