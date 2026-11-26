package com.pocketkeyboard.hid

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

/**
 * Lightweight no-root Bluetooth Classic RFCOMM server that streams raw 9-byte
 * [HidDescriptors] gamepad reports directly to `PocketPadXboxBridge.exe` on Windows.
 *
 * This bypasses Windows' cached Bluetooth HID SDP descriptor (so existing pairings work
 * without unpairing) and coexists on the same Bluetooth radio alongside `BluetoothHidDevice`
 * Keyboard & Mouse.
 *
 * Frame layout (12 bytes):
 * `[0] = 0xA5, [1] = 0x5A, [2..10] = 9-byte gamepad report, [11] = XOR(bytes 2..10)`
 */
class BluetoothGamepadBridgeServer(
    context: Context,
    private val onConnectionChanged: (Boolean, String?) -> Unit
) {

    companion object {
        const val SERVICE_NAME = "PocketKeyboard Gamepad Bridge"
        val SERVICE_UUID: UUID = UUID.fromString("706b6264-5842-4f58-5041-440000000001")

        private const val FRAME_HEADER_0 = 0xA5.toByte()
        private const val FRAME_HEADER_1 = 0x5A.toByte()
        private const val FRAME_SIZE = 12
    }

    private val appContext = context.applicationContext
    private val bluetoothAdapter: BluetoothAdapter? =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val stateLock = Object()
    private val writeLock = Object()

    @Volatile
    private var running = false

    private var acceptThread: Thread? = null
    private var serverSocket: BluetoothServerSocket? = null

    @Volatile
    private var activeClientSocket: BluetoothSocket? = null

    @Volatile
    private var activeOutputStream: OutputStream? = null

    private val frameBuf = ByteArray(FRAME_SIZE)

    @SuppressLint("MissingPermission")
    fun start() {
        val adapter = bluetoothAdapter ?: return
        if (!BluetoothPermissions.hasRequiredPermissions(appContext)) return
        val enabled = try {
            adapter.isEnabled
        } catch (_: SecurityException) {
            false
        }
        if (!enabled) return

        synchronized(stateLock) {
            if (running) return
            running = true
            val thread = Thread({ runAcceptLoop(adapter) }, "PocketKeyboard-BtBridgeAccept")
            thread.isDaemon = true
            acceptThread = thread
            thread.start()
        }
    }

    fun sendGamepadReport(report: ByteArray) {
        if (report.size < 9) return
        var failedSocket: BluetoothSocket? = null
        synchronized(writeLock) {
            val out = activeOutputStream ?: return
            frameBuf[0] = FRAME_HEADER_0
            frameBuf[1] = FRAME_HEADER_1
            var xor = 0
            for (i in 0 until 9) {
                val b = report[i]
                frameBuf[2 + i] = b
                xor = xor xor (b.toInt() and 0xFF)
            }
            frameBuf[11] = xor.toByte()
            try {
                out.write(frameBuf, 0, FRAME_SIZE)
                out.flush()
            } catch (_: IOException) {
                failedSocket = activeClientSocket
                activeClientSocket = null
                activeOutputStream = null
            }
        }
        val toClose = failedSocket
        if (toClose != null) {
            try {
                toClose.close()
            } catch (_: Exception) { }
            onConnectionChanged(false, null)
        }
    }

    fun stop() {
        val ss: BluetoothServerSocket?
        val thread: Thread?
        synchronized(stateLock) {
            running = false
            ss = serverSocket
            serverSocket = null
            thread = acceptThread
            acceptThread = null
        }
        try {
            ss?.close()
        } catch (_: Exception) { }

        val client: BluetoothSocket?
        synchronized(writeLock) {
            client = activeClientSocket
            activeClientSocket = null
            activeOutputStream = null
        }
        if (client != null) {
            try {
                client.close()
            } catch (_: Exception) { }
            onConnectionChanged(false, null)
        }

        if (thread != null && thread !== Thread.currentThread()) {
            try {
                thread.join(250L)
            } catch (_: InterruptedException) { }
        }
    }

    fun release() {
        stop()
    }

    @SuppressLint("MissingPermission")
    private fun runAcceptLoop(adapter: BluetoothAdapter) {
        while (running) {
            if (!BluetoothPermissions.hasRequiredPermissions(appContext)) {
                break
            }
            val enabled = try {
                adapter.isEnabled
            } catch (_: SecurityException) {
                false
            }
            if (!enabled) {
                break
            }

            var ss: BluetoothServerSocket? = null
            try {
                ss = adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID)
                synchronized(stateLock) {
                    if (!running) {
                        try { ss.close() } catch (_: Exception) { }
                        return
                    }
                    serverSocket = ss
                }

                while (running) {
                    val client = ss.accept()
                    handleAcceptedClient(client)
                }
            } catch (_: SecurityException) {
                break
            } catch (_: IOException) {
                if (!running) break
                try {
                    Thread.sleep(500L)
                } catch (_: InterruptedException) {
                    break
                }
            } finally {
                synchronized(stateLock) {
                    if (serverSocket === ss) {
                        serverSocket = null
                    }
                }
                try {
                    ss?.close()
                } catch (_: Exception) { }
            }
        }

        synchronized(stateLock) {
            running = false
            acceptThread = null
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleAcceptedClient(socket: BluetoothSocket) {
        val output = try {
            socket.outputStream
        } catch (_: IOException) {
            try { socket.close() } catch (_: Exception) { }
            return
        }

        val previousSocket: BluetoothSocket?
        synchronized(writeLock) {
            previousSocket = activeClientSocket
            activeClientSocket = socket
            activeOutputStream = output
        }
        if (previousSocket != null && previousSocket !== socket) {
            try { previousSocket.close() } catch (_: Exception) { }
        }

        val hostName = try {
            socket.remoteDevice?.name?.takeIf { it.isNotBlank() }
                ?: socket.remoteDevice?.address
                ?: "Windows PC"
        } catch (_: SecurityException) {
            try {
                socket.remoteDevice?.address ?: "Windows PC"
            } catch (_: Exception) {
                "Windows PC"
            }
        }

        onConnectionChanged(true, hostName)

        // Immediately push an initial neutral report so the Windows bridge gets valid state
        sendGamepadReport(HidDescriptors.GAMEPAD_NEUTRAL_REPORT)

        val monitor = Thread({
            try {
                val input = socket.inputStream
                val buf = ByteArray(16)
                while (running) {
                    val read = input.read(buf)
                    if (read < 0) break
                }
            } catch (_: IOException) {
            } finally {
                detachClient(socket)
            }
        }, "PocketKeyboard-BtBridgeMonitor")
        monitor.isDaemon = true
        monitor.start()
    }

    private fun detachClient(socket: BluetoothSocket) {
        val wasActive: Boolean
        synchronized(writeLock) {
            if (activeClientSocket === socket) {
                activeClientSocket = null
                activeOutputStream = null
                wasActive = true
            } else {
                wasActive = false
            }
        }
        try {
            socket.close()
        } catch (_: Exception) { }
        if (wasActive) {
            onConnectionChanged(false, null)
        }
    }
}
