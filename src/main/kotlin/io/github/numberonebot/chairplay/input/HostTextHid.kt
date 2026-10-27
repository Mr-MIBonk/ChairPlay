package io.github.numberonebot.chairplay.input

import org.lwjgl.glfw.GLFW.*

/** Probe Unicode events from the keyboard's sender instead of the touchpad's sender. */
internal object HostTextHid {
    val enabled: Boolean = KeyboardHid.enabled && System.getenv("CHAIRPLAY_TEXT_LAYOUT") == "windows"
    fun descriptor(keyboard: ByteArray): ByteArray {
        require(keyboard.size > 6 && keyboard.last() == 0xc0.toByte())
        return keyboard.copyOfRange(0, 6) + byteArrayOf(0x85.toByte(), 1) +
            keyboard.copyOfRange(6, keyboard.lastIndex) + byteArrayOf(5, 0x0d, 0x85.toByte(), 2) +
            HandwritingHid.candidateDescriptor() + byteArrayOf(0xc0.toByte())
    }

    fun report(codePoint: Int): ByteArray? = HandwritingHid.report(codePoint, separate = true)

    fun usesCharacterCallback(key: Int, mods: Int): Boolean = key in GLFW_KEY_SPACE..GLFW_KEY_WORLD_2 &&
        (mods and (GLFW_MOD_CONTROL or GLFW_MOD_ALT or GLFW_MOD_SUPER) == 0 ||
            mods and (GLFW_MOD_CONTROL or GLFW_MOD_ALT or GLFW_MOD_SUPER) == (GLFW_MOD_CONTROL or GLFW_MOD_ALT))
}
