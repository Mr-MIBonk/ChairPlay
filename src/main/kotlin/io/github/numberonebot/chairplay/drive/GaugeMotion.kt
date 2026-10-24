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

    /** Whether the gauges are still on their way in or out, so the window keeps drawing at its pace. */
    val animating: Boolean get() = last != null && progress < 1.0

    /** The frame for the gauges' [now] view, null when they are gone. */
    fun frame(now: GaugeView?): Frame? {
        val time = nanoTime()
        val wantsShown = now != null
        if (wantsShown != coming && (last != null || wantsShown)) {
            // Midway the other way, start from the same place: as far in as it was out.
            val from = if (last == null) 0.0 else 1 - progress
            coming = wantsShown
            startNanos = time - (from * seconds() * 1e9).toLong()
        }
        if (now != null) last = now
        val view = last ?: return null
        progress = ((time - startNanos) / 1e9 / seconds()).coerceIn(0.0, 1.0)
        if (!coming && progress >= 1.0) {
            last = null
            return null
        }
        return Frame(view, coming, progress)
    }

    private fun seconds() = if (coming) IN_SECONDS else OUT_SECONDS

    companion object {
        const val IN_SECONDS = 0.45
        const val OUT_SECONDS = 0.4

        /**
         * How far in the wells look at [there] (0 gone, 1 in place, linear in time): quick from the start and settling,
         * cubic. The HUD's blocks follow the same curve as they make room for the wells.
         */
        fun eased(there: Float): Float = 1 - (1 - there) * (1 - there) * (1 - there)
    }
}
