package io.github.numberonebot.chairplay.bluetooth

/** Bluetooth major device class "Phone"; AirPods and iPads report other classes. */
private const val MAJOR_CLASS_PHONE = 0x02

fun selectIphone(paired: List<PairedDevice>, configuredAddress: String?): PairedDevice {
    if (configuredAddress != null) {
        return paired.firstOrNull { it.address.equals(configuredAddress, ignoreCase = true) }
            ?: error("Bluetooth device $configuredAddress is not paired with this PC")
    }
    val phones = paired.filter { (it.classOfDevice shr 8) and 0x1F == MAJOR_CLASS_PHONE }
    return phones.singleOrNull() ?: error(
        if (phones.isEmpty()) "No paired phone; pair the iPhone in Windows Bluetooth settings"
        else "Several paired phones, pass one address: " + phones.joinToString { "${it.name} ${it.address}" },
    )
}
