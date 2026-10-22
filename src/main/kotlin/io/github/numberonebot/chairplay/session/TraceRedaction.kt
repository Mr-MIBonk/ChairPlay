package io.github.numberonebot.chairplay.session

import com.shilapi.xcertplay.iap2.catalog.Iap2Endpoints
import com.shilapi.xcertplay.iap2.catalog.Iap2FieldSpec

private val FRAME_HEADER = Regex("""^IAP2 (?:TX|RX) \[[^\]\n]*] 0x([0-9a-f]{4})""")

/**
 * The iAP2 trace goes to the console, and Iap2FrameFormatter quotes every string field and appends a
 * hex preview of the body: the Wi-Fi passphrase of 0x4301, 0x5701 and 0x5703 would be printed in clear.
 * A frame whose catalog entry has a passphrase field keeps only its header line. Matching the endpoint
 * rather than the printed field also covers a body the formatter could not parse, shown as hex alone.
 */
internal fun redactTrace(trace: String): String {
    val id = FRAME_HEADER.find(trace)?.groupValues?.get(1)?.toInt(16) ?: return trace
    if (Iap2Endpoints.byId(id)?.fields?.carriesPassphrase() != true) return trace
    return trace.substringBefore('\n') + " [body redacted]"
}

private fun List<Iap2FieldSpec>.carriesPassphrase(): Boolean =
    any { it.name.equals("passphrase", ignoreCase = true) || it.children.carriesPassphrase() }
