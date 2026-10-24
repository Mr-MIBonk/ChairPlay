package io.github.numberonebot.chairplay.drive

import org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN
import org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT
import org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT
import org.lwjgl.glfw.GLFW.GLFW_KEY_UP
import org.lwjgl.glfw.GLFW.GLFW_RELEASE
import kotlin.math.abs
import kotlin.math.sign

/** The arrows in drive mode: Up the throttle, Down the brake, Left and Right the wheel, each all the way. */
class DriveKeys {
    private val down = HashSet<Int>()

    /**
     * Takes an arrow's press, repeat or release; false for any other key, which stays the app's, and for the release of
     * an arrow held before drive mode began, which lets go of the knob.
     */
    fun key(key: Int, action: Int): Boolean {
        if (key !in ARROWS) return false
        if (action == GLFW_RELEASE) return down.remove(key)
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
 * mode ([keyboard], F10), sets the car's input and moves it on. [onStopped] hears each time the car comes to a stop.
 */
class DriveControl(
    private val drive: Drive,
    gamepad: () -> FloatArray?,
    private val onStopped: (DriveSnapshot) -> Unit = {},
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val readGamepad = gamepad
    private val keys = DriveKeys()
    private var wasMoving = false
    @Volatile private var lastActive: Long? = null

    /** Whether the car is being driven, in drive mode or moving, or was until [LINGER_SECONDS] ago: the gauges show. */
    val showing: Boolean get() = keyboard || lingering() > 0

    private fun lingering(): Double = lastActive?.let { LINGER_SECONDS - (nanoTime() - it) / 1e9 } ?: 0.0

    /** Drive mode: the arrows drive the car instead of tilting the knob. */
    @Volatile var keyboard = false
        private set

    /** Whether a gamepad answered the last pass. */
    @Volatile var gamepad = false
        private set

    /** Turns drive mode on or off, and says which; off lets go of the arrows. */
    fun toggleKeyboard(): Boolean {
        keyboard = !keyboard
        if (!keyboard) keys.release()
        return keyboard
    }

    /** Takes a key in drive mode when it is an arrow; false leaves it to the app. */
    fun key(key: Int, action: Int): Boolean = keyboard && keys.key(key, action)

    /**
     * One pass of the window loop: moves the car on while it is driven or moving. Returns how soon the next pass is
     * due: at frame pace then, as the gauges' linger ends, now and then to look at a gamepad, else null to wait for
     * the next event.
     */
    fun pass(): Double? {
        val axes = readGamepad()
        gamepad = axes != null
        val input = combined(if (keyboard) keys.input() else CarInput(), axes?.let(::gamepadInput) ?: CarInput())
        drive.input = input
        val active = keyboard || input != CarInput() || drive.moving
        if (active) {
            drive.advance()
            lastActive = nanoTime()
        }
        val moving = drive.moving
        if (wasMoving && !moving) onStopped(drive.snapshot())
        wasMoving = moving
        if (active) return FRAME_SECONDS
        val poll = if (axes != null) GAMEPAD_POLL_SECONDS else null
        return listOfNotNull(lingering().takeIf { it > 0 }, poll).minOrNull()
    }

    companion object {
        const val FRAME_SECONDS = 1.0 / 60
        /** A gamepad lying still is looked at twenty times a second, quick enough for a trigger pulled. */
        const val GAMEPAD_POLL_SECONDS = 0.05
        /** How long the gauges stay after the car stops and drive mode ends. */
        const val LINGER_SECONDS = 5.0
    }
}
