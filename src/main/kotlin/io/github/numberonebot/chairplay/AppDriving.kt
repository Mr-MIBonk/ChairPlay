package io.github.numberonebot.chairplay

import io.github.numberonebot.chairplay.drive.CarInput
import io.github.numberonebot.chairplay.drive.Driving
import io.github.numberonebot.chairplay.media.VideoWindow
import io.github.numberonebot.chairplay.ui.UiDriving

/**
 * The GPS car as the GPS page and its experiments reach it: drive mode, the car driven by a script, and the CarPlay
 * screens saved. Each change goes in on the next pass of the window loop, which [window]'s repaint wakes.
 */
internal fun uiDriving(driving: Driving, window: VideoWindow, screenshots: () -> Unit, log: (String) -> Unit) = object : UiDriving {
    override fun driveMode(on: Boolean): Boolean {
        val done = driving.driveMode(on)
        if (done) log("drive mode ${if (on) "on" else "off"} from the GPS page")
        window.repaint()
        return done
    }

    override fun gpsDrive(throttle: Double, brake: Double, steer: Double): Boolean {
        driving.control.scripted = CarInput(throttle, brake, steer)
        window.repaint()
        return driving.settings.enabled
    }

    override fun screenshot(): Boolean = true.also { screenshots() }
}
