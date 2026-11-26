package com.pocketkeyboard.hid

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.InterfaceAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

data class UsbBridgeStatus(
    val isListening: Boolean = false,
    val isConnected: Boolean = false,
    val transportLabel: String? = null,
    val peerAddress: String? = null,
    val localUsbIps: List<String> = emptyList(),
    val errorMessage: String? = null
)

/**
 * No-root ultra-low-latency wired USB Bridge server (PocketBridge v2, TCP & UDP on port 27189).
 *
 * Supports two zero-root wired paths simultaneously to `PocketPadXboxBridge.exe`:
 * 1. USB Tethering (Raw UDP): ~0.5-1.5ms latency over Android's RNDIS/NCM USB network interface.
 * 2. USB via ADB Tunnel (TCP_NODELAY): ~2-4ms latency over `adb forward tcp:27189 tcp:27189`
 *    without touching PC Wi-Fi or online game routing.
 *
 * Frame layout (`PocketBridge v2`, max 16-byte payload):
 * `[0] = 0xA5, [1] = 0x5B, [2] = type, [3] = len, [4..4+len-1] = payload, [4+len] = XOR(bytes[2..3+len])`
 */
class UsbBridgeServer(
    private val onConnectionChanged: (connected: Boolean, transportLabel: String?) -> Unit
) {

    companion object {
        const val PORT = 27189

        const val HEADER_0: Byte = 0xA5.toByte()
        const val HEADER_1: Byte = 0x5B.toByte()

        const val TYPE_GAMEPAD: Byte = 0x01
        const val TYPE_MOUSE: Byte = 0x02
        const val TYPE_KEYBOARD: Byte = 0x03
        const val TYPE_PING: Byte = 0x04
        const val TYPE_PONG: Byte = 0x05

        private const val UDP_TIMEOUT_MS = 2500L
        private val PONG_FRAME = byteArrayOf(HEADER_0, HEADER_1, TYPE_PONG, 0x00, TYPE_PONG)
    }

    private val _status = MutableStateFlow(UsbBridgeStatus())
    val status: StateFlow<UsbBridgeStatus> = _status.asStateFlow()

    private val stateLock = Object()
    private val writeLock = Object()

    @Volatile
    private var running = false

    private var tcpAcceptThread: Thread? = null
    private var udpRecvThread: Thread? = null

    private var serverSocket: ServerSocket? = null
    @Volatile
    private var udpSocket: DatagramSocket? = null

    private var tcpSocket: Socket? = null
    private var tcpOut: OutputStream? = null
    private var tcpLabel: String? = null
    private var tcpPeer: String? = null

    private var udpPeerAddr: InetAddress? = null
    private var udpPeerPort: Int = 0
    private var udpLastPingMs: Long = 0L
    private var udpLabel: String? = null

    private var lastNotifiedConnected: Boolean = false
    private var lastNotifiedLabel: String? = null

    // Pre-allocated zero-allocation hot-path buffers guarded by writeLock
    private val frameBuf = ByteArray(24)
    private val udpSendPacket = DatagramPacket(frameBuf, frameBuf.size)

    fun start() {
        val ips = discoverLocalIpv4Addresses()
        synchronized(stateLock) {
            if (running) {
                synchronized(writeLock) {
                    _status.value = _status.value.copy(localUsbIps = ips)
                }
                return
            }

            running = true
            synchronized(writeLock) {
                _status.value = UsbBridgeStatus(
                    isListening = true,
                    isConnected = false,
                    localUsbIps = ips
                )
            }

            val tcpThread = Thread({ runTcpAcceptLoop() }, "PocketKeyboard-UsbTcpAccept")
            tcpThread.isDaemon = true
            tcpThread.priority = Thread.MAX_PRIORITY
            tcpAcceptThread = tcpThread

            val udpThread = Thread({ runUdpReceiveLoop() }, "PocketKeyboard-UsbUdpRecv")
            udpThread.isDaemon = true
            udpThread.priority = Thread.MAX_PRIORITY
            udpRecvThread = udpThread

            tcpThread.start()
            udpThread.start()
        }
    }

    fun stop() {
        val ss: ServerSocket?
        val ds: DatagramSocket?
        val tTcp: Thread?
        val tUdp: Thread?
        synchronized(stateLock) {
            running = false
            ss = serverSocket
            serverSocket = null
            ds = udpSocket
            udpSocket = null
            tTcp = tcpAcceptThread
            tcpAcceptThread = null
            tUdp = udpRecvThread
            udpRecvThread = null
        }

        try { ss?.close() } catch (_: Exception) { }
        try { ds?.close() } catch (_: Exception) { }

        val oldTcp: Socket?
        val notifyChange: Pair<Boolean, String?>?
        synchronized(writeLock) {
            oldTcp = tcpSocket
            tcpSocket = null
            tcpOut = null
            tcpLabel = null
            tcpPeer = null
            udpPeerAddr = null
            udpPeerPort = 0
            udpLastPingMs = 0L
            udpLabel = null

            val ips = _status.value.localUsbIps
            _status.value = UsbBridgeStatus(
                isListening = false,
                isConnected = false,
                localUsbIps = ips
            )
            notifyChange = computeNotificationLocked(false, null)
        }

        try { oldTcp?.close() } catch (_: Exception) { }

        val current = Thread.currentThread()
        if (tTcp != null && tTcp !== current) {
            try { tTcp.join(250L) } catch (_: InterruptedException) { }
        }
        if (tUdp != null && tUdp !== current) {
            try { tUdp.join(250L) } catch (_: InterruptedException) { }
        }

        if (notifyChange != null) {
            onConnectionChanged(notifyChange.first, notifyChange.second)
        }
    }

    fun release() {
        stop()
    }

    fun refreshLocalAddresses() {
        val ips = discoverLocalIpv4Addresses()
        synchronized(writeLock) {
            _status.value = _status.value.copy(localUsbIps = ips)
        }
    }

    fun sendKeyboardReport(report: ByteArray): Result<Unit> {
        if (report.size < 8) {
            return Result.failure(IllegalArgumentException("Invalid keyboard report size: ${report.size}"))
        }
        return if (sendFrame(TYPE_KEYBOARD, report, 8)) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("USB Bridge is not connected to a PC."))
        }
    }

    fun sendMouseReport(report: ByteArray) {
        if (report.size < 4) return
        sendFrame(TYPE_MOUSE, report, 4)
    }

    fun sendGamepadReport(report: ByteArray) {
        if (report.size < 9) return
        sendFrame(TYPE_GAMEPAD, report, 9)
    }

    private fun sendFrame(type: Byte, payload: ByteArray, length: Int): Boolean {
        var failedTcp: Socket? = null
        var notifyChange: Pair<Boolean, String?>? = null
        var sent = false

        synchronized(writeLock) {
            frameBuf[0] = HEADER_0
            frameBuf[1] = HEADER_1
            frameBuf[2] = type
            frameBuf[3] = length.toByte()

            var xor = (type.toInt() and 0xFF) xor (length and 0xFF)
            for (i in 0 until length) {
                val b = payload[i]
                frameBuf[4 + i] = b
                xor = xor xor (b.toInt() and 0xFF)
            }
            val totalLen = 5 + length
            frameBuf[4 + length] = xor.toByte()

            // Prefer raw UDP when active for lowest possible latency
            val uAddr = udpPeerAddr
            val uSock = udpSocket
            if (uAddr != null && uSock != null && udpPeerPort > 0 &&
                (SystemClock.elapsedRealtime() - udpLastPingMs) <= UDP_TIMEOUT_MS
            ) {
                try {
                    udpSendPacket.setData(frameBuf, 0, totalLen)
                    udpSendPacket.address = uAddr
                    udpSendPacket.port = udpPeerPort
                    uSock.send(udpSendPacket)
                    sent = true
                } catch (_: IOException) {
                    udpPeerAddr = null
                    udpPeerPort = 0
                    udpLastPingMs = 0L
                    udpLabel = null
                    notifyChange = refreshPublishedStatusLocked()
                }
            }

            if (!sent) {
                val out = tcpOut
                if (out != null) {
                    try {
                        out.write(frameBuf, 0, totalLen)
                        out.flush()
                        sent = true
                    } catch (_: IOException) {
                        failedTcp = tcpSocket
                        tcpSocket = null
                        tcpOut = null
                        tcpLabel = null
                        tcpPeer = null
                        notifyChange = refreshPublishedStatusLocked()
                    }
                }
            }
        }

        if (failedTcp != null) {
            try { failedTcp?.close() } catch (_: Exception) { }
        }
        if (notifyChange != null) {
            onConnectionChanged(notifyChange!!.first, notifyChange!!.second)
        }
        return sent
    }

    private fun runTcpAcceptLoop() {
        while (running) {
            var ss: ServerSocket? = null
            try {
                ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(PORT))
                synchronized(stateLock) {
                    if (!running) {
                        try { ss.close() } catch (_: Exception) { }
                        return
                    }
                    serverSocket = ss
                }

                while (running) {
                    val client = ss.accept()
                    handleTcpClient(client)
                }
            } catch (e: IOException) {
                if (!running) break
                synchronized(writeLock) {
                    _status.value = _status.value.copy(
                        errorMessage = "TCP listener error: ${e.message}"
                    )
                }
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
                try { ss?.close() } catch (_: Exception) { }
            }
        }
    }

    private fun handleTcpClient(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            socket.trafficClass = 0x10 // IPTOS_LOWDELAY
        } catch (_: Exception) { }

        val out = try {
            socket.getOutputStream()
        } catch (_: IOException) {
            try { socket.close() } catch (_: Exception) { }
            return
        }

        val remote = socket.inetAddress
        val peer = "${remote?.hostAddress ?: "127.0.0.1"}:${socket.port}"
        val label = if (remote?.isLoopbackAddress == true) {
            "USB ADB (TCP)"
        } else if (remote != null && isUsbInterfacePeer(remote)) {
            "USB Tethering (TCP)"
        } else {
            "USB / LAN (TCP)"
        }

        val oldTcp: Socket?
        val notifyChange: Pair<Boolean, String?>?
        synchronized(writeLock) {
            oldTcp = tcpSocket
            tcpSocket = socket
            tcpOut = out
            tcpLabel = label
            tcpPeer = peer
            notifyChange = refreshPublishedStatusLocked()
        }
        if (oldTcp != null && oldTcp !== socket) {
            try { oldTcp.close() } catch (_: Exception) { }
        }
        if (notifyChange != null) {
            onConnectionChanged(notifyChange.first, notifyChange.second)
        }

        val monitor = Thread({
            val buf = ByteArray(128)
            var len = 0
            try {
                val input = socket.getInputStream()
                while (running) {
                    val read = input.read(buf, len, buf.size - len)
                    if (read < 0) break
                    len += read
                    len = consumeIncomingTcpFrames(socket, out, buf, len)
                }
            } catch (_: IOException) {
            } finally {
                detachTcpClient(socket)
            }
        }, "PocketKeyboard-UsbTcpMonitor")
        monitor.isDaemon = true
        monitor.start()
    }

    private fun consumeIncomingTcpFrames(
        socket: Socket,
        out: OutputStream,
        buf: ByteArray,
        initialLen: Int
    ): Int {
        var len = initialLen
        var offset = 0
        while (len - offset >= 5) {
            if (buf[offset] != HEADER_0 || buf[offset + 1] != HEADER_1) {
                offset++
                continue
            }
            val type = buf[offset + 2]
            val payloadLen = buf[offset + 3].toInt() and 0xFF
            if (payloadLen > 16) {
                offset++
                continue
            }
            val frameSize = 5 + payloadLen
            if (len - offset < frameSize) {
                break
            }
            var xor = (type.toInt() and 0xFF) xor payloadLen
            for (i in 0 until payloadLen) {
                xor = xor xor (buf[offset + 4 + i].toInt() and 0xFF)
            }
            if ((xor and 0xFF) != (buf[offset + 4 + payloadLen].toInt() and 0xFF)) {
                offset++
                continue
            }

            if (type == TYPE_PING) {
                synchronized(writeLock) {
                    if (tcpSocket === socket) {
                        try {
                            out.write(PONG_FRAME)
                            out.flush()
                        } catch (_: IOException) { }
                    }
                }
            }
            offset += frameSize
        }

        val remaining = len - offset
        if (offset > 0 && remaining > 0) {
            System.arraycopy(buf, offset, buf, 0, remaining)
        }
        return remaining
    }

    private fun detachTcpClient(socket: Socket) {
        val notifyChange: Pair<Boolean, String?>?
        synchronized(writeLock) {
            if (tcpSocket === socket) {
                tcpSocket = null
                tcpOut = null
                tcpLabel = null
                tcpPeer = null
                notifyChange = refreshPublishedStatusLocked()
            } else {
                notifyChange = null
            }
        }
        try { socket.close() } catch (_: Exception) { }
        if (notifyChange != null) {
            onConnectionChanged(notifyChange.first, notifyChange.second)
        }
    }

    private fun runUdpReceiveLoop() {
        val recvBuf = ByteArray(128)
        val recvPacket = DatagramPacket(recvBuf, recvBuf.size)

        while (running) {
            var ds: DatagramSocket? = null
            try {
                ds = DatagramSocket(null)
                ds.reuseAddress = true
                ds.bind(InetSocketAddress(PORT))
                ds.soTimeout = 500
                ds.trafficClass = 0x10 // IPTOS_LOWDELAY

                synchronized(stateLock) {
                    if (!running) {
                        try { ds.close() } catch (_: Exception) { }
                        return
                    }
                    udpSocket = ds
                }

                while (running) {
                    recvPacket.length = recvBuf.size
                    try {
                        ds.receive(recvPacket)
                        handleUdpPacket(ds, recvBuf, recvPacket.length, recvPacket.address, recvPacket.port)
                    } catch (_: SocketTimeoutException) {
                        checkUdpTimeout()
                    }
                }
            } catch (e: IOException) {
                if (!running) break
                synchronized(writeLock) {
                    _status.value = _status.value.copy(
                        errorMessage = "UDP listener error: ${e.message}"
                    )
                }
                try {
                    Thread.sleep(500L)
                } catch (_: InterruptedException) {
                    break
                }
            } finally {
                synchronized(stateLock) {
                    if (udpSocket === ds) {
                        udpSocket = null
                    }
                }
                try { ds?.close() } catch (_: Exception) { }
            }
        }
    }

    private fun handleUdpPacket(
        ds: DatagramSocket,
        data: ByteArray,
        len: Int,
        address: InetAddress?,
        port: Int
    ) {
        if (address == null || port <= 0 || len < 5) return
        if (data[0] != HEADER_0 || data[1] != HEADER_1) return
        val type = data[2]
        val payloadLen = data[3].toInt() and 0xFF
        if (payloadLen > 16 || len < 5 + payloadLen) return

        var xor = (type.toInt() and 0xFF) xor payloadLen
        for (i in 0 until payloadLen) {
            xor = xor xor (data[4 + i].toInt() and 0xFF)
        }
        if ((xor and 0xFF) != (data[4 + payloadLen].toInt() and 0xFF)) return

        if (type == TYPE_PING) {
            try {
                ds.send(DatagramPacket(PONG_FRAME, PONG_FRAME.size, address, port))
            } catch (_: IOException) {
                return
            }

            val wasNewOrChanged = synchronized(writeLock) {
                udpPeerAddr != address || udpPeerPort != port
            }
            val label = if (wasNewOrChanged || udpLabel == null) {
                if (isUsbInterfacePeer(address)) "USB Tethering (UDP)" else "Network (UDP)"
            } else {
                udpLabel
            }

            val notifyChange: Pair<Boolean, String?>?
            synchronized(writeLock) {
                udpPeerAddr = address
                udpPeerPort = port
                udpLastPingMs = SystemClock.elapsedRealtime()
                udpLabel = label
                notifyChange = refreshPublishedStatusLocked()
            }
            if (notifyChange != null) {
                onConnectionChanged(notifyChange.first, notifyChange.second)
            }
        }
    }

    private fun checkUdpTimeout() {
        var notifyChange: Pair<Boolean, String?>? = null
        synchronized(writeLock) {
            if (udpPeerAddr != null &&
                (SystemClock.elapsedRealtime() - udpLastPingMs) > UDP_TIMEOUT_MS
            ) {
                udpPeerAddr = null
                udpPeerPort = 0
                udpLastPingMs = 0L
                udpLabel = null
                notifyChange = refreshPublishedStatusLocked()
            }
        }
        if (notifyChange != null) {
            onConnectionChanged(notifyChange!!.first, notifyChange!!.second)
        }
    }

    private fun refreshPublishedStatusLocked(): Pair<Boolean, String?>? {
        val now = SystemClock.elapsedRealtime()
        val udpLive = udpPeerAddr != null && (now - udpLastPingMs) <= UDP_TIMEOUT_MS
        val tcpLive = tcpOut != null

        val connected = udpLive || tcpLive
        val label = when {
            udpLive -> udpLabel
            tcpLive -> tcpLabel
            else -> null
        }
        val peer = when {
            udpLive -> "${udpPeerAddr?.hostAddress}:$udpPeerPort"
            tcpLive -> tcpPeer
            else -> null
        }

        _status.value = UsbBridgeStatus(
            isListening = running,
            isConnected = connected,
            transportLabel = label,
            peerAddress = peer,
            localUsbIps = _status.value.localUsbIps,
            errorMessage = null
        )

        return computeNotificationLocked(connected, label)
    }

    private fun computeNotificationLocked(
        connected: Boolean,
        label: String?
    ): Pair<Boolean, String?>? {
        if (connected == lastNotifiedConnected && label == lastNotifiedLabel) {
            return null
        }
        lastNotifiedConnected = connected
        lastNotifiedLabel = label
        return connected to label
    }

    private fun isUsbInterfacePeer(peer: InetAddress): Boolean {
        if (peer !is Inet4Address) return false
        val peerBytes = peer.address
        return try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return false
            while (ifaces.hasMoreElements()) {
                val iface = ifaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val name = iface.name.lowercase()
                val looksUsb = name.startsWith("rndis") ||
                    name.startsWith("ncm") ||
                    name.startsWith("usb") ||
                    name.startsWith("eth")
                if (!looksUsb) continue
                for (ifAddr in iface.interfaceAddresses) {
                    val addr = ifAddr.address as? Inet4Address ?: continue
                    if (sameSubnet(addr.address, peerBytes, ifAddr.networkPrefixLength)) {
                        return true
                    }
                }
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun sameSubnet(a: ByteArray, b: ByteArray, prefixLenRaw: Short): Boolean {
        if (a.size != 4 || b.size != 4) return false
        val prefixLen = prefixLenRaw.toInt().coerceIn(0, 32)
        val mask = if (prefixLen == 0) 0 else (-1 shl (32 - prefixLen))
        val intA = ((a[0].toInt() and 0xFF) shl 24) or
            ((a[1].toInt() and 0xFF) shl 16) or
            ((a[2].toInt() and 0xFF) shl 8) or
            (a[3].toInt() and 0xFF)
        val intB = ((b[0].toInt() and 0xFF) shl 24) or
            ((b[1].toInt() and 0xFF) shl 16) or
            ((b[2].toInt() and 0xFF) shl 8) or
            (b[3].toInt() and 0xFF)
        return (intA and mask) == (intB and mask)
    }

    private fun discoverLocalIpv4Addresses(): List<String> {
        val usbList = mutableListOf<String>()
        val otherList = mutableListOf<String>()
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            while (ifaces.hasMoreElements()) {
                val iface = ifaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val nameLower = iface.name.lowercase()
                val isUsb = nameLower.startsWith("rndis") ||
                    nameLower.startsWith("ncm") ||
                    nameLower.startsWith("usb") ||
                    nameLower.startsWith("eth")
                for (ifAddr: InterfaceAddress in iface.interfaceAddresses) {
                    val ip = (ifAddr.address as? Inet4Address)?.hostAddress ?: continue
                    val entry = "$ip (${iface.name})"
                    if (isUsb) usbList.add(entry) else otherList.add(entry)
                }
            }
        } catch (_: Exception) { }
        return usbList + otherList
    }
}
