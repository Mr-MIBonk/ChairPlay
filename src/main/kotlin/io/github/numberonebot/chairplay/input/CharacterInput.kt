package io.github.numberonebot.chairplay.input

import java.util.concurrent.atomic.AtomicReference
import org.lwjgl.glfw.GLFW.*

/** Tracks the iPhone's requested mode for the experimental character-input device. */
internal class CharacterInput(private val enabled: Boolean, private val log: (String) -> Unit = {}) {
    private data class State(val session: Any, val mode: Int)
    private val state = AtomicReference<State?>()

    fun command(session: Any, type: String, params: Map<String, Any?>): Boolean {
        if (!enabled || type != "hidSetInputMode" || !device(params)) return false
        val value = (params["hidInputMode"] as? Number)?.toString()?.toLongOrNull() ?: return false
        if (value !in 0L..4L) return false
        val now = State(session, value.toInt())
        val previous = state.getAndSet(now)
        if (previous != now) log("character input: mode=${now.mode} (${listOf("default", "characters", "scrolling", "characters and scrolling", "dial pad")[now.mode]})")
        return true
    }

    fun query(session: Any, type: String, params: Map<String, Any?>): Map<String, Any?>? {
        if (!enabled || type != "hidCopyInputMode" || !device(params)) return null
        return mapOf("hidInputMode" to mode(session))
    }

    private fun device(params: Map<String, Any?>): Boolean = when (val uuid = params["uuid"]) {
        is String -> uuid.equals(HandwritingHid.UUID, ignoreCase = true)
        is Number -> uuid.toLong() == HandwritingHid.UUID.toLong(16)
        else -> false
    }

    private fun mode(session: Any?): Int = state.get()?.takeIf { it.session === session }?.mode ?: 0
    fun typing(session: Any?): Boolean = enabled && mode(session) in listOf(1, 3, 4)
    fun capturesKey(session: Any?, key: Int, mods: Int): Boolean = typing(session) &&
        key in GLFW_KEY_SPACE..GLFW_KEY_WORLD_2 && mods and (GLFW_MOD_CONTROL or GLFW_MOD_ALT or GLFW_MOD_SUPER) == 0

    fun report(session: Any?, codePoint: Int): ByteArray? {
        if (!typing(session)) return null
        if (mode(session) == 4 && codePoint !in "0123456789*#+".map(Char::code)) return null
        return HandwritingHid.report(codePoint)
    }

    fun ended(session: Any) {
        state.get()?.takeIf { it.session === session }?.let { state.compareAndSet(it, null) }
    }
}
