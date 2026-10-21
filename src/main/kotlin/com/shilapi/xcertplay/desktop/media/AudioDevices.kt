package com.shilapi.xcertplay.desktop.media

import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Line
import javax.sound.sampled.Mixer
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine

/**
 * The device name [setting] picks among [names] for [key] (audio.output or audio.input): null for the Windows
 * default (blank or "default"), else the name it equals or the single one containing it, ignoring case.
 * Java Sound cuts Windows device names to 31 characters, so a part of the name is the practical form.
 */
fun matchDevice(key: String, setting: String?, names: List<String>): String? {
    val wanted = setting?.trim()?.ifEmpty { null } ?: return null
    if (wanted.equals("default", ignoreCase = true)) return null
    names.firstOrNull { it.equals(wanted, ignoreCase = true) }?.let { return it }
    val matches = names.filter { it.contains(wanted, ignoreCase = true) }
    require(matches.isNotEmpty()) { "$key: no device matches \"$wanted\"; devices: ${names.joinToString(" | ")}" }
    require(matches.size == 1) { "$key: \"$wanted\" matches several devices: ${matches.joinToString(" | ")}" }
    return matches.single()
}

/** Java Sound's devices that play ([SourceDataLine]) or record ([TargetDataLine]). */
object AudioDevices {
    fun outputs(): List<Mixer.Info> = supporting(SourceDataLine::class.java)

    fun inputs(): List<Mixer.Info> = supporting(TargetDataLine::class.java)

    /** The device for [setting], or null for the Windows default; a setting matching nothing stops the start. */
    fun output(setting: String?): Mixer.Info? = pick("audio.output", setting, outputs())

    fun input(setting: String?): Mixer.Info? = pick("audio.input", setting, inputs())

    private fun pick(key: String, setting: String?, devices: List<Mixer.Info>): Mixer.Info? {
        val name = matchDevice(key, setting, devices.map { it.name }) ?: return null
        return devices.first { it.name == name }
    }

    private fun supporting(type: Class<out Line>): List<Mixer.Info> = AudioSystem.getMixerInfo().filter { info ->
        AudioSystem.getMixer(info).sourceLineInfo.plus(AudioSystem.getMixer(info).targetLineInfo)
            .any { type.isAssignableFrom(it.lineClass) }
    }
}
