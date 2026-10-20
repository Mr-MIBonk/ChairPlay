package android.bluetooth

import java.io.InputStream
import java.io.OutputStream

/**
 * Exists only so BluetoothRfcommDuplexStream's Android constructor compiles; the desktop opens
 * its socket through Winsock and uses the stream's socket-access constructor instead.
 */
abstract class BluetoothSocket {
    abstract val inputStream: InputStream
    abstract val outputStream: OutputStream
    abstract fun close()
}
