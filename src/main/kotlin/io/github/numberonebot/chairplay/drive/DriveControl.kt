package io.github.numberonebot.chairplay.drive

import org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN
import org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT
import org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT
import org.lwjgl.glfw.GLFW.GLFW_KEY_UP
import org.lwjgl.glfw.GLFW.GLFW_MOD_ALT
import org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL
import org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT
import org.lwjgl.glfw.GLFW.GLFW_RELEASE
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.sign

/** The arrows in drive mode: Up the throttle, Down the brake, Left and Right the wheel, each all the way. */
class DriveKeys {
    private val down = HashSet<Int>()

    /**
     * Takes a plain arrow's press, repeat or release; false for any other key, which stays the app's, for an arrow with
     * Shift, Ctrl or Alt (Ctrl+Up stays the volume), and for the release of an arrow held before drive mode began,
     * which lets go of the knob.
     */
    fun key(key: Int, action: Int, mods: Int = 0): Boolean {
        if (key !in ARROWS) return false
        if (action == GLFW_RELEASE) return down.remove(key)
        if (mods and MODIFIERS != 0) return false
        down.add(key)
        return true
    }

    fun input() = CarInput(
        throttle = if (GLFW_KEY_UP in down) 1.0 else 0.0,
        brake = if (GLFW_KEY_DOWN in down) 1.0 else 0.0,
        steer = (if (GLFW_KEY_RIGHT in down) 1.0 else 0.0) - (if (GLFW_KEY_LEFT in down) 1.0 else 0.0),
    )

    /** Lets go of every arrow, as drive mode ends. */
    fun release() = down.clear()

    private companion object {
        val ARROWS = setOf(GLFW_KEY_UP, GLFW_KEY_DOWN, GLFW_KEY_LEFT, GLFW_KEY_RIGHT)
        const val MODIFIERS = GLFW_MOD_SHIFT or GLFW_MOD_CONTROL or GLFW_MOD_ALT
    }
}

/**
 * A gamepad's [axes] as GLFW gives them (GLFW_GAMEPAD_AXIS_*: the left stick's X first, the triggers last, from -1
 * released to 1 pressed): the right trigger the throttle, the left the brake, the left stick the wheel. A stick near
 * the middle steers nothing, and the rest of its travel steers all the way.
 */
fun gamepadInput(axes: FloatArray): CarInput {
    fun trigger(value: Float) = ((value + 1.0) / 2).takeIf { it > TRIGGER_DEAD_ZONE }?.coerceAtMost(1.0) ?: 0.0
    val x = axes[AXIS_LEFT_X].toDouble()
    val steer = if (abs(x) <= STICK_DEAD_ZONE) 0.0 else sign(x) * ((abs(x) - STICK_DEAD_ZONE) / (1 - STICK_DEAD_ZONE)).coerceAtMost(1.0)
    return CarInput(throttle = trigger(axes[AXIS_RIGHT_TRIGGER]), brake = trigger(axes[AXIS_LEFT_TRIGGER]), steer = steer)
}

/** The keys and the gamepad together: each pedal as far as either presses it, the wheel turned by both. */
fun combined(a: CarInput, b: CarInput) = CarInput(
    throttle = maxOf(a.throttle, b.throttle),
    brake = maxOf(a.brake, b.brake),
    steer = (a.steer + b.steer).coerceIn(-1.0, 1.0),
)

private const val AXIS_LEFT_X = 0
private const val AXIS_LEFT_TRIGGER = 4
private const val AXIS_RIGHT_TRIGGER = 5
private const val STICK_DEAD_ZONE = 0.15
private const val TRIGGER_DEAD_ZONE = 0.02

/**
 * Drives [drive] from the window loop, on its (the main) thread: each [pass] reads the gamepad, and the arrows in drive
 * mode ([keyboard], F10 or the GPS page), sets the car's input and moves it on. [onStopped] hears each time the car comes
 * to a stop.
 */
class DriveControl(
    private val drive: Drive,
    gamepad: () -> FloatArray?,
    private val onStopped: (DriveSnapshot) -> Unit = {},
) {
    private val readGamepad = gamepad
    private val keys = DriveKeys()
    private var wasMoving = false
    // Drive mode as another thread (the GPS page's) asks for it, put in force on the next pass.
    private val keyboardRequest = AtomicReference<Boolean?>()

    /** Drive mode: the arrows drive the car instead of tilting the knob, and the gauges show. */
    @Volatile var keyboard = false
        private set

    /** An experiment's script on the pedals and the wheel, on top of the keys and a gamepad; at rest lets go. */
    @Volatile var scripted = CarInput()

    /** Whether a gamepad answered the last pass. */
    @Volatile var gamepad = false
        private set

    /** Turns drive mode on or off, and says which; off lets go of the arrows. On the main thread, as F10 does. */
    fun toggleKeyboard(): Boolean {
        keyboard = !keyboard
        if (!keyboard) keys.release()
        return keyboard
    }

    /** Asks for drive mode [on] or off from any thread; the next [pass] puts it in force. */
    fun requestKeyboard(on: Boolean) = keyboardRequest.set(on)

    /** Takes a key in drive mode when it is a plain arrow; false leaves it to the app. */
    fun key(key: Int, action: Int, mods: Int = 0): Boolean = keyboard && keys.key(key, action, mods)

    /**
     * One pass of the window loop: moves the car on while it is driven or moving. Returns how soon the next pass is
     * due: at frame pace then, now and then to look at a gamepad, else null to wait for the next event.
     */
    fun pass(): Double? {
        keyboardRequest.getAndSet(null)?.let { on -> if (on != keyboard) toggleKeyboard() }
        val axes = readGamepad()
        gamepad = axes != null
        val input = combined(combined(if (keyboard) keys.input() else CarInput(), axes?.let(::gamepadInput) ?: CarInput()), scripted)
        drive.input = input
        val active = keyboard || input != CarInput() || drive.moving
        if (active) drive.advance()
        val moving = drive.moving
        if (wasMoving && !moving) onStopped(drive.snapshot())
        wasMoving = moving
        if (active) return FRAME_SECONDS
        return if (axes != null) GAMEPAD_POLL_SECONDS else null
    }

    companion object {
        const val FRAME_SECONDS = 1.0 / 60
        /** A gamepad lying still is looked at twenty times a second, quick enough for a trigger pulled. */
        const val GAMEPAD_POLL_SECONDS = 0.05
    }
}
