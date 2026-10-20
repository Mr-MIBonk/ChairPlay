package com.shilapi.xcertplay.transport

import java.io.IOException

// Copied from shared/.../transport/IphoneUsbHost.kt (upstream 6d3b8a8): iAP2 identification
// reports its timeouts with this type, but the file that declares it is built on Android's USB API.
sealed class IphoneUsbException(message: String, cause: Throwable? = null) : IOException(message, cause) {
    class PermissionDenied(message: String, cause: Throwable? = null) : IphoneUsbException(message, cause)
    class DeviceUnavailable(message: String, cause: Throwable? = null) : IphoneUsbException(message, cause)
    class TimedOut(message: String, cause: Throwable? = null) : IphoneUsbException(message, cause)
    class Protocol(message: String) : IphoneUsbException(message)
}
