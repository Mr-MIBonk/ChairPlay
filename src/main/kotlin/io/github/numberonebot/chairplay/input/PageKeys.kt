package io.github.numberonebot.chairplay.input

import org.lwjgl.glfw.GLFW.GLFW_PRESS
import org.lwjgl.glfw.GLFW.GLFW_RELEASE
import org.lwjgl.glfw.GLFW.GLFW_REPEAT

/**
 * Keys pressed on a page of the web UI, which has the keyboard while it shows, handed on as if pressed on the window:
 * the same bindings, holds and repeats as [KeyControls]. Over the controls window's page every key of the app works;
 * over the settings only those that neither move about a page nor type ([worksOverSettings]), so the arrows, Enter,
 * Tab, Esc and Home stay the page's. Keys come by name, as controls.properties writes them ("Shift+F11", "Equal").
 */
class PageKeys(private val source: () -> ControlsConfig) {
    private val keys = KeyControls(source)

    /** [phase] is [PRESS], [REPEAT] or [RELEASE]; [everything] for the controls window's page. Null for nothing to do. */
    @Synchronized
    fun key(name: String, phase: String, everything: Boolean): Control? {
        val binding = ControlsConfig.parseBinding(name) ?: return null
        val action = when (phase) {
            PRESS -> GLFW_PRESS
            REPEAT -> GLFW_REPEAT
            RELEASE -> GLFW_RELEASE
            else -> return null
        }
        if (action == GLFW_PRESS && !everything) {
            val bound = source().bindings[KeyBinding(binding.key, binding.mods and ControlsConfig.MOD_MASK)]
            if (bound == null || !bound.worksOverSettings) return null
        }
        // A repeat or release follows its press, which the key's hold in KeyControls remembers.
        return keys.key(binding.key, action, binding.mods)
    }

    companion object {
        const val PRESS = "press"
        const val REPEAT = "repeat"
        const val RELEASE = "release"
    }
}

/** Whether the key works while the settings cover CarPlay: not the knob's, Back's or Home's, nor Siri's letter. */
val ControlAction.worksOverSettings: Boolean
    get() = this !in PAGE_KEYS

private val PAGE_KEYS = setOf(
    ControlAction.KNOB_LEFT, ControlAction.KNOB_RIGHT, ControlAction.KNOB_UP, ControlAction.KNOB_DOWN,
    ControlAction.KNOB_NEXT, ControlAction.KNOB_PREVIOUS, ControlAction.SELECT, ControlAction.BACK, ControlAction.HOME,
    ControlAction.SIRI,
)
