package io.github.numberonebot.chairplay.drive

/**
 * The gauges' coming and going: over [IN_SECONDS] as drive mode starts, over [OUT_SECONDS] as it ends, the leaving
 * gauges keeping the last view they had. Turned back the other way midway, they go back from where they are.
 */
class GaugeMotion(private val nanoTime: () -> Long = System::nanoTime) {
    /** What to draw: [view], coming or leaving, [progress] from 0 to 1 through it (1 when it stays). */
    data class Frame(val view: GaugeView, val coming: Boolean, val progress: Double)

    private var last: GaugeView? = null
    private var coming = false
    private var startNanos = 0L
    private var progress = 1.0

    // How small the gauges are, 0 large to 1 small, linear in time, and the size they are going to.
    private var small = 0f
    private var smallAim = 0f
    private var sizeNanos = 0L
    private var sized = false

    // Whether they moved until the last frame, which wants one frame more: the HUD reads the motion a frame late, as it
    // draws before the gauges, and would keep the step before the end until the next repaint.
    private var settling = false
    private val moving: Boolean get() = last != null && (progress < 1.0 || small != smallAim)

    /**
     * Whether the gauges are still on their way in or out, or between sizes, or have just settled, so the window keeps
     * drawing at its pace.
     */
    val animating: Boolean get() = moving || settling

    /**
     * How small the gauges were at the last [frame], linear in time: 0 large, 1 small, over [SIZE_SECONDS] as their size
     * is switched; shown anew, they start at their size. The HUD makes room by it too. Read from any thread.
     */
    @Volatile var smallness = 0f
        private set

    /**
     * How far in the gauges were at the last [frame], linear in time: 0 gone, 1 in place, the way out as the way in
     * backwards. The HUD makes room for them by it, so the two move together. Read from any thread.
     */
    @Volatile var there = 0f
        private set

    /** The frame for the gauges' [now] view, null when they are gone. */
    fun frame(now: GaugeView?): Frame? {
        val before = moving
        return step(now).also { settling = before && !moving }
    }

    private fun step(now: GaugeView?): Frame? {
        val time = nanoTime()
        val wantsShown = now != null
        if (wantsShown != coming && (last != null || wantsShown)) {
            // Midway the other way, start from the same place: as far in as it was out.
            val from = if (last == null) 0.0 else 1 - progress
            coming = wantsShown
            startNanos = time - (from * seconds() * 1e9).toLong()
        }
        if (now != null) {
            last = now
            resize(if (now.size == GaugeSize.SMALL) 1f else 0f, time)
        }
        val view = last ?: return null.also { there = 0f }
        progress = ((time - startNanos) / 1e9 / seconds()).coerceIn(0.0, 1.0)
        if (!coming && progress >= 1.0) {
            last = null
            there = 0f
            sized = false
            return null
        }
        there = (if (coming) progress else 1 - progress).toFloat()
        return Frame(view, coming, progress)
    }

    // Toward [aim] at a steady pace; time counts only from the frame that set a new aim, not the gap before it.
    private fun resize(aim: Float, time: Long) {
        if (!sized) {
            small = aim
            sized = true
        } else if (aim == smallAim) {
            val step = ((time - sizeNanos) / 1e9 / SIZE_SECONDS).toFloat()
            small = if (aim > small) minOf(aim, small + step) else maxOf(aim, small - step)
        }
        smallAim = aim
        sizeNanos = time
        smallness = small
    }

    private fun seconds() = if (coming) IN_SECONDS else OUT_SECONDS

    companion object {
        const val IN_SECONDS = 0.45
        const val OUT_SECONDS = 0.4
        const val SIZE_SECONDS = 0.35

        /** How far between large and small the wells look at [smallness] (linear in time): soft at both ends, cubic. */
        fun sized(smallness: Float): Float =
            if (smallness < 0.5f) 4 * smallness * smallness * smallness else 1 - (2 - 2 * smallness).let { it * it * it } / 2

        /**
         * How far in the wells look at [there] (0 gone, 1 in place, linear in time): quick from the start and settling,
         * cubic. The HUD's blocks follow the same curve as they make room for the wells.
         */
        fun eased(there: Float): Float = 1 - (1 - there) * (1 - there) * (1 - there)
    }
}
