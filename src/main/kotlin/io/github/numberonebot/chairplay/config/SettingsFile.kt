package io.github.numberonebot.chairplay.config

import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties

/**
 * settings.properties as the start screen and the settings screen change it: each value changes on its own line,
 * and the comments, the order and every other line stay as they are. The file is read as UTF-8, as [DesktopConfig]
 * reads it.
 */
class SettingsFile(private val file: Path) {
    /** Every setting in the file, as Properties reads it. */
    @Synchronized
    fun read(): Map<String, String> {
        val properties = Properties().apply { load(StringReader(Files.readString(file))) }
        return properties.stringPropertyNames().associateWith { properties.getProperty(it) }
    }

    /** Writes [changes] in place; a file that lacks a key gets it at its end. The file is replaced in one step. */
    @Synchronized
    fun write(changes: Map<String, String>) {
        val text = if (Files.exists(file)) Files.readString(file) else ""
        val edited = editSettingsText(text, changes)
        val temporary = file.resolveSibling("${file.fileName}.saving")
        Files.writeString(temporary, edited)
        try {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}

/**
 * [text] with each key of [changes] set to its value: on the key's own line, keeping the indentation, the key and
 * its separator as written and the line's ending, on every line of a key that is there more than once, and at the
 * end for a key that is missing. A value spread over continuation lines is replaced whole.
 */
internal fun editSettingsText(text: String, changes: Map<String, String>): String {
    for ((key, value) in changes) {
        require(value.none { it == '\n' || it == '\r' }) { "$key: a value cannot hold a line break" }
    }
    val lines = splitKeepingEnds(text)
    val out = StringBuilder()
    val found = mutableSetOf<String>()
    var index = 0
    while (index < lines.size) {
        // A logical line: physical lines joined while one ends in an odd number of backslashes.
        val start = index
        while (index < lines.size - 1 && continues(lines[index])) index++
        val first = lines[start]
        val last = lines[index]
        index++
        val parsed = parseKey(first.content)
        val change = parsed?.let { changes[it.first] }
        if (parsed == null || change == null) {
            for (i in start until index) out.append(lines[i].content).append(lines[i].ending)
            continue
        }
        found += parsed.first
        out.append(first.content, 0, parsed.second).append(escapeValue(change)).append(last.ending)
    }
    val missing = changes.filterKeys { it !in found }
    if (missing.isNotEmpty()) {
        if (out.isNotEmpty() && !out.endsWith("\n")) out.append('\n')
        for ((key, value) in missing) out.append(escapeKey(key)).append('=').append(escapeValue(value)).append('\n')
    }
    return out.toString()
}

private class Line(val content: String, val ending: String)

private fun splitKeepingEnds(text: String): List<Line> {
    val lines = mutableListOf<Line>()
    var start = 0
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == '\n' || c == '\r') {
            val end = if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i + 2 else i + 1
            lines += Line(text.substring(start, i), text.substring(i, end))
            start = end
            i = end
        } else {
            i++
        }
    }
    if (start < text.length) lines += Line(text.substring(start), "")
    return lines
}

/** A line that is not a comment and ends in an odd number of backslashes goes on in the next one. */
private fun continues(line: Line): Boolean {
    if (isCommentOrBlank(line.content)) return false
    val trailing = line.content.length - line.content.trimEnd('\\').length
    return trailing % 2 == 1
}

private fun isCommentOrBlank(content: String): Boolean {
    val trimmed = content.trimStart(' ', '\t', '\u000c')
    return trimmed.isEmpty() || trimmed[0] == '#' || trimmed[0] == '!'
}

/**
 * The key of a setting line, unescaped, and where its value starts: after the key, the whitespace around it and one
 * '=' or ':', as Properties reads it. Null for comments and blank lines.
 */
private fun parseKey(content: String): Pair<String, Int>? {
    if (isCommentOrBlank(content)) return null
    var i = 0
    while (i < content.length && content[i] in WHITESPACE) i++
    val key = StringBuilder()
    while (i < content.length) {
        val c = content[i]
        if (c == '\\' && i + 1 < content.length) {
            key.append(unescape(content[i + 1]))
            i += 2
            continue
        }
        if (c == '=' || c == ':' || c in WHITESPACE) break
        key.append(c)
        i++
    }
    while (i < content.length && content[i] in WHITESPACE) i++
    if (i < content.length && (content[i] == '=' || content[i] == ':')) {
        i++
        while (i < content.length && content[i] in WHITESPACE) i++
    }
    return key.toString() to i
}

private val WHITESPACE = setOf(' ', '\t', '\u000c')

private fun unescape(c: Char): Char = when (c) {
    't' -> '\t'
    'n' -> '\n'
    'r' -> '\r'
    'f' -> '\u000c'
    else -> c
}

/** Properties' escapes for a value: backslashes, leading spaces and tabs; other characters go as UTF-8. */
private fun escapeValue(value: String): String {
    val out = StringBuilder()
    // Properties skips the spaces that start a value unless they are escaped.
    var leading = true
    for (c in value) {
        when (c) {
            '\\' -> out.append("\\\\")
            '\t' -> out.append("\\t")
            '\u000c' -> out.append("\\f")
            ' ' -> out.append(if (leading) "\\ " else " ")
            else -> out.append(c)
        }
        if (c != ' ') leading = false
    }
    return out.toString()
}

private fun escapeKey(key: String): String = buildString {
    for (c in key) {
        if (c == '=' || c == ':' || c == ' ' || c == '#' || c == '!' || c == '\\') append('\\')
        append(c)
    }
}
