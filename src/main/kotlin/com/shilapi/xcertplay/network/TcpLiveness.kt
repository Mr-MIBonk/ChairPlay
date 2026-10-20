package com.shilapi.xcertplay.network

import java.net.Socket

/**
 * Desktop stand-in for shared/.../network/TcpLiveness.kt, which tunes keep-alive timing through
 * Android's Os API. Plain keep-alive is enough until a vanished iPhone proves otherwise.
 */
object TcpLiveness {
    fun configure(socket: Socket, diagnostic: (String) -> Unit) {
        socket.keepAlive = true
    }
}
