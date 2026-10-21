package com.shilapi.xcertplay.desktop.input

import com.shilapi.xcertplay.airplay.AirPlayKnobState
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import org.lwjgl.glfw.GLFW.*

/** What a key or the mouse wheel asks of the iPhone, sent through AirPlaySession's HID and command calls. */
sealed interface Control {
    /** AirPlaySession.sendKnob: [momentary] releases at once, as a wheel step does. */
    data class Knob(val state: AirPlayKnobState, val momentary: Boolean) : Control
    data class Media(val index: Int) : Control
    data class Telephony(val index: Int) : Control
    data object Siri : Control
}

/**
 * The keyboard and mouse wheel as a car's knob, media keys and phone buttons, bound as [config] says
 * (controls.properties). Knob tilts (its X/Y axes nudge focus in a direction) and the select, back and home
 * buttons hold until the key is released, so CarPlay can tell a long press; knob turns step at once, a held
 * key stepping on every [ControlsConfig.repeatEvery]-th repeat as upstream's CarPlayRemoteKeys does.
 * A key is released by its code alone, so letting go of a modifier first cannot leave a button held.
 * Called on the GL thread only.
 */
class KeyControls(private val config: ControlsConfig = ControlsConfig.DEFAULT) {
    /** The action each held key started, and how many repeats it has had. */
    private val held = HashMap<Int, ControlAction>()
    private val repeats = HashMap<Int, Int>()
    private var scrolled = 0.0

    /** [action] is GLFW_PRESS, GLFW_REPEAT or GLFW_RELEASE; [mods] GLFW's modifier bits. */
    fun key(key: Int, action: Int, mods: Int): Control? {
        val bound = when (action) {
            GLFW_PRESS -> config.bindings[KeyBinding(key, mods and ControlsConfig.MOD_MASK)]?.also {
                held[key] = it
                repeats[key] = 0
            }
            else -> held[key]
        } ?: return null
        if (action == GLFW_RELEASE) held.remove(key)
        return when (bound) {
            ControlAction.KNOB_NEXT -> turn(key, action, 1)
            ControlAction.KNOB_PREVIOUS -> turn(key, action, -1)
            else -> hold(bound)?.let { state ->
                when (action) {
                    GLFW_PRESS -> Control.Knob(state, false)
                    GLFW_RELEASE -> Control.Knob(AirPlayKnobState(), false)
                    else -> null
                }
            } ?: if (action == GLFW_PRESS) press(bound) else null
        }
    }

    /** [dy] as GLFW reports it: 1.0 per mouse notch up, fractions from a touchpad. Down moves focus forward. */
    fun scroll(dy: Double): Control? {
        if (config.wheel == WheelAction.NONE) return null
        scrolled += if (config.wheelInvert) dy else -dy
        val steps = (scrolled / config.notchesPerStep).toInt()
        if (steps == 0) return null
        scrolled -= steps * config.notchesPerStep
        return Control.Knob(AirPlayKnobState(wheel = steps), true)
    }

    private fun turn(key: Int, action: Int, step: Int): Control? {
        val send = when (action) {
            GLFW_PRESS -> true
            GLFW_REPEAT -> {
                val count = (repeats[key] ?: 0) + 1
                repeats[key] = count
                count % config.repeatEvery == 0
            }
            else -> {
                repeats.remove(key)
                false
            }
        }
        return if (send) Control.Knob(AirPlayKnobState(wheel = step), true) else null
    }

    private fun hold(action: ControlAction): AirPlayKnobState? = when (action) {
        ControlAction.KNOB_LEFT -> AirPlayKnobState(x = -NUDGE)
        ControlAction.KNOB_RIGHT -> AirPlayKnobState(x = NUDGE)
        ControlAction.KNOB_UP -> AirPlayKnobState(y = -NUDGE)
        ControlAction.KNOB_DOWN -> AirPlayKnobState(y = NUDGE)
        ControlAction.SELECT -> AirPlayKnobState(select = true)
        ControlAction.BACK -> AirPlayKnobState(back = true)
        ControlAction.HOME -> AirPlayKnobState(home = true)
        else -> null
    }

    private fun press(action: ControlAction): Control? = when (action) {
        ControlAction.MEDIA_PREVIOUS -> Control.Media(CarPlayMediaButton.PREVIOUS)
        ControlAction.MEDIA_PLAY_PAUSE -> Control.Media(CarPlayMediaButton.PLAY_PAUSE)
        ControlAction.MEDIA_NEXT -> Control.Media(CarPlayMediaButton.NEXT)
        ControlAction.CALL_ANSWER -> Control.Telephony(TELEPHONY_ANSWER)
        ControlAction.CALL_END -> Control.Telephony(TELEPHONY_HANG_UP)
        ControlAction.SIRI -> Control.Siri
        else -> null
    }

    companion object {
        /** Upstream CarPlayController's TELEPHONY_HOOK_SWITCH and TELEPHONY_DROP. */
        const val TELEPHONY_ANSWER = 1
        const val TELEPHONY_HANG_UP = 3
        /** Full deflection of the knob descriptor's X/Y axes (logical -127..127). */
        private const val NUDGE = 127
    }
}
