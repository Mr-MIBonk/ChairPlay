package io.github.numberonebot.chairplay.input

import io.github.numberonebot.chairplay.config.SettingsFile
import io.github.numberonebot.chairplay.config.editSettingsText
import java.nio.file.Files
import java.nio.file.Path

/** What a save of the Keyboard page's changes came to. */
sealed interface ControlsSave {
    /** Written: the bindings as they now are, and every setting written, the actions keys were taken from among them. */
    class Saved(val config: ControlsConfig, val changes: Map<String, String>) : ControlsSave

    /** Not written: the start would refuse the file for [message], about the change to [key]. */
    class Refused(val key: String, val message: String) : ControlsSave
}

/**
 * controls.properties as the Keyboard page changes it: each setting on its own line, comments and order kept, a
 * missing one added at the end ([SettingsFile]). A key that another action has moves at once: that action loses it in
 * the same write. Every change is checked with the start's own parser first, so its reasons read as the file's do.
 */
class ControlsFile(private val dir: Path) {
    private val path = dir.resolve(ControlsConfig.FILE)
    private val file = SettingsFile(path)

    /** The bindings as the file has them now. */
    fun read(): ControlsConfig = ControlsConfig.load(dir)

    @Synchronized
    fun save(changes: Map<String, String>): ControlsSave {
        require(changes.isNotEmpty()) { "nothing to save" }
        val text = if (Files.exists(path)) Files.readString(path) else ControlsConfig.defaultText()
        val current = runCatching { ControlsConfig.parse(text) }.getOrElse { return refused(changes, it) }
        val all = LinkedHashMap(changes)
        for ((key, value) in changes) {
            val action = ControlAction.entries.firstOrNull { it.key == key } ?: continue
            val taken = names(value).mapNotNull(ControlsConfig::parseBinding).toSet()
            for (other in ControlAction.entries) {
                if (other == action || other.key in changes) continue
                val keys = all[other.key]?.let(::names) ?: current.keys(other)
                val kept = keys.filter { ControlsConfig.parseBinding(it) !in taken }
                if (kept.size != keys.size) all[other.key] = kept.joinToString(", ")
            }
        }
        val config = runCatching { ControlsConfig.parse(editSettingsText(text, all)) }.getOrElse { return refused(changes, it) }
        file.write(all)
        return ControlsSave.Saved(config, all)
    }

    private fun names(value: String) = value.split(',').map(String::trim).filter(String::isNotEmpty)

    /** The parser names the setting it refuses; the change it names, or else the first, carries the reason. */
    private fun refused(changes: Map<String, String>, error: Throwable): ControlsSave.Refused {
        val message = error.message ?: error.javaClass.simpleName
        val key = changes.keys.firstOrNull { Regex("""\b${Regex.escape(it)}\b""").containsMatchIn(message) } ?: changes.keys.first()
        return ControlsSave.Refused(key, message)
    }
}
