package io.github.numberonebot.chairplay.media

/**
 * The app's own volume for everything CarPlay plays, as a car's volume knob: [level] from 0 to [MAX] in steps of
 * [STEP], and a mute that keeps the level. Windows' volume is left alone. Safe from any thread; [onChange] runs on
 * the thread that changed it.
 */
class Volume(level: Int = MAX, muted: Boolean = false) {
    @Volatile
    var level = level.coerceIn(0, MAX)
        private set

    @Volatile
    var muted = muted
        private set

    @Volatile
    var onChange: () -> Unit = {}

    /** What the samples are multiplied by: the level squared, which sounds about even from step to step; 0 muted. */
    val gain: Float
        get() = if (muted) 0f else (level.toFloat() / MAX).let { it * it }

    /** Turns the volume [steps] steps up, or down when negative; any turn unmutes, as a car's knob does. */
    fun change(steps: Int) {
        synchronized(this) {
            level = (level + steps * STEP).coerceIn(0, MAX)
            muted = false
        }
        onChange()
    }

    /** Sets the level itself (the Audio page's slider), kept within 0 and [MAX]; it unmutes, as a turn does. */
    fun set(level: Int) {
        synchronized(this) {
            this.level = level.coerceIn(0, MAX)
            muted = false
        }
        onChange()
    }

    fun toggleMute() {
        synchronized(this) { muted = !muted }
        onChange()
    }

    companion object {
        const val MAX = 100
        const val STEP = 5
    }
}
