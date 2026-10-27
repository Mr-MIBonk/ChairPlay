package io.github.numberonebot.chairplay.input

import com.shilapi.xcertplay.airplay.AirPlayInfoPlist

/** Experimental touchpad/UTF-8 character input, enabled for a test run only. */
object HandwritingHid {
    const val UUID = "2a2a2a2e"
    val enabled: Boolean = System.getenv("CHAIRPLAY_TEXT_INPUT") == "1"
    internal val separateReports: Boolean = System.getenv("CHAIRPLAY_TEXT_REPORTS") == "separate"
    internal val clearAfterCharacter: Boolean = separateReports && System.getenv("CHAIRPLAY_TEXT_COMMIT") == "clear"

    fun advertise(info: MutableMap<String, Any?>, experimental: Boolean = enabled, keyboard: Boolean = KeyboardHid.enabled) {
        if (!experimental) return
        val devices = info["hidDevices"] as? List<*> ?: return
        info["hidDevices"] = devices + mapOf(
            "uuid" to UUID, "name" to "ChairPlay character input", "displayUUID" to AirPlayInfoPlist.MAIN_UUID,
            "hidVendorID" to 2L, "hidProductID" to 1L, "hidCountryCode" to 0L, "hidDescriptor" to descriptor(),
        )
        info["displays"] = (info["displays"] as? List<*>)?.map { item ->
            val display = (item as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value } ?: return@map item
            if (display["uuid"] != AirPlayInfoPlist.MAIN_UUID) display
            else display + ("features" to (((display["features"] as? Number)?.toLong() ?: 0L) or 0x10L))
        }
        KeyboardHid.advertise(info, experimental = keyboard)
    }

    /** One touch contact and two character candidates; each candidate has 4 UTF-8 bytes, encoding, length, quality. */
    internal fun descriptor(separate: Boolean = separateReports): ByteArray {
        val bytes = mutableListOf<Int>()
        fun emit(vararg items: Int) { bytes.addAll(items.toList()) }
        emit(0x05, 0x0d, 0x09, 0x05, 0xa1, 0x01) // Digitizer / Touch Pad
        if (separate) emit(0x85, 1) // Contact coordinates are never part of a character report.
        emit(0x09, 0x22, 0xa1, 0x02, 0x09, 0x33, 0x15, 0, 0x25, 1)
        emit(0x75, 1, 0x95, 1, 0x81, 2, 0x75, 7, 0x81, 1)
        emit(0x05, 1, 0x09, 0x30, 0x15, 0, 0x26, 0xff, 0x7f, 0x35, 0, 0x46, 70, 0)
        emit(0x55, 0x0f, 0x65, 0x11, 0x75, 16, 0x95, 1, 0x81, 2)
        emit(0x09, 0x31, 0x81, 2, 0xc0, 0x05, 0x0d, 0x55, 0, 0x65, 0, 0x35, 0, 0x45, 0)
        if (separate) emit(0x85, 2)
        bytes.addAll(candidateDescriptor().map { it.toInt() and 255 })
        emit(0xc0)
        return bytes.map(Int::toByte).toByteArray()
    }

    internal fun candidateDescriptor(): ByteArray {
        val bytes = mutableListOf<Int>()
        fun emit(vararg items: Int) { bytes.addAll(items.toList()) }
        repeat(2) {
            emit(0x09, 0x24, 0xa1, 2) // Gesture Character logical collection
            emit(0x09, 0x63, 0x15, 0, 0x27, 0xff, 0xff, 0xff, 0x7f, 0x75, 32, 0x95, 1, 0x82, 2, 1)
            emit(0x09, 0x65, 0x09, 0x62, 0x15, 0, 0x25, 4, 0x75, 8, 0x95, 2, 0x81, 2)
            emit(0x09, 0x61, 0x25, 100, 0x95, 1, 0x81, 2, 0xc0)
        }
        return bytes.map(Int::toByte).toByteArray()
    }

    internal fun report(codePoint: Int, separate: Boolean = separateReports): ByteArray? {
        if (!Character.isValidCodePoint(codePoint) || codePoint in 0xd800..0xdfff || Character.isISOControl(codePoint)) return null
        val character = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8)
        val combined = ByteArray(19).apply {
            character.copyInto(this, 5)
            this[9] = 1
            this[10] = character.size.toByte()
            this[11] = 100
            this[16] = 1
        }
        return if (separate) byteArrayOf(2) + combined.copyOfRange(5, 19) else combined
    }

    fun isCharacterCommand(command: Map<String, Any?>): Boolean =
        command["type"] == "hidSendReport" && command["uuid"] in setOf(UUID, KeyboardHid.UUID)

    /** The opt-in clear probe delimits candidates without generating a digitizer contact event. */
    internal fun sendCharacter(
        report: ByteArray,
        clear: Boolean = clearAfterCharacter,
        pause: () -> Unit = { Thread.sleep(35) },
        write: (ByteArray) -> Boolean,
    ): Boolean {
        if (clear) require(report.size == 15 && report[0] == 2.toByte())
        if (!write(report)) return false
        if (!clear) return true
        pause()
        val cleared = ByteArray(15).apply { this[0] = 2; this[5] = 1; this[12] = 1 }
        return write(cleared)
    }
}
