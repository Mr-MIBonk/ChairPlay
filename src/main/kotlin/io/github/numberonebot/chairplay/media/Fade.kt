package io.github.numberonebot.chairplay.media

/**
 * A switch between a window's two contents through black: when the wanted one changes, the content on screen dims to
 * black over [millis], the other takes its place, and comes up over [millis] again. Taken back half way, the content
 * on screen comes back up from where it was. [update] runs on each pass of the window's loop; [moving] asks for more
 * passes until it is done.
 */
class Fade(initial: Boolean, private val millis: Long = FADE_MILLIS, private val now: () -> Long = System::nanoTime) {
    /** The content on screen now, which lags the wanted one while it dims away. */
    var shown = initial
        private set

    /** How bright the content on screen is: 1 as it is, 0 black. */
    var brightness = 1f
        private set

    private var target = initial
    private var last = now()

    val moving: Boolean get() = brightness < 1f || target != shown

    fun update(target: Boolean) {
        val time = now()
        val step = ((time - last).coerceAtLeast(0) / (millis * 1_000_000.0)).toFloat()
        last = time
        this.target = target
        if (target != shown) {
            brightness -= step
            if (brightness <= 0f) {
                // The new content starts from black, whatever was left of the step.
                brightness = 0f
                shown = target
            }
        } else {
            brightness = (brightness + step).coerceAtMost(1f)
        }
    }

    companion object {
        /** Each half of a fade; the page's own fade (styles.css, .shell.away) takes as long. */
        const val FADE_MILLIS = 150L
    }
}
