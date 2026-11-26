package com.pocketkeyboard.hid

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.DataOutputStream
import java.io.FileOutputStream

interface HidOutputBackend {
    val mouseAvailable: Boolean
    val gamepadAvailable: Boolean
    val mousePollIntervalMs: Long
    val gamepadPollIntervalMs: Long
        get() = mousePollIntervalMs
    fun sendKeyboardReport(report: ByteArray): Result<Unit>
    fun sendMouseReport(report: ByteArray)
    fun sendGamepadReport(report: ByteArray)
    fun close()
}

private class UsbHidNodeWriter(private val path: String) {
    @Volatile
    private var directStream: FileOutputStream? = try {
        FileOutputStream(path)
    } catch (_: Exception) {
        null
    }

    private var suProcess: Process? = null
    private var suStream: DataOutputStream? = null

    init {
        if (directStream == null) {
            val proc = Runtime.getRuntime().exec("su")
            suProcess = proc
            suStream = DataOutputStream(proc.outputStream)
        }
    }

    val isDirect: Boolean
        get() = directStream != null

    fun write(report: ByteArray) {
        val direct = directStream
        if (direct != null) {
            try {
                direct.write(report)
                direct.flush()
                return
            } catch (_: Exception) {
                try { direct.close() } catch (_: Exception) { }
                directStream = null
                try {
                    val proc = Runtime.getRuntime().exec("su")
                    suProcess = proc
                    suStream = DataOutputStream(proc.outputStream)
                } catch (_: Exception) { }
            }
        }

        val suOs = suStream ?: return
        try {
            val hex = report.joinToString("") { "\\x%02x".format(it) }
            suOs.writeBytes("printf '${hex}' > $path\n")
            suOs.flush()
        } catch (_: Exception) { }
    }

    fun close() {
        try {
            directStream?.close()
        } catch (_: Exception) { }
        directStream = null

        try {
            suStream?.apply {
                writeBytes("exit\n")
                flush()
                close()
            }
            suProcess?.waitFor()
        } catch (_: Exception) { }
        suStream = null
        suProcess = null
    }
}

private class UsbHidOutputBackend(
    private val kbdDevicePath: String,
    mouseDevicePath: String?,
    gamepadDevicePath: String?
) : HidOutputBackend {

    private val kbdSuProcess: Process = Runtime.getRuntime().exec("su")
    private val kbdSuStream: DataOutputStream = DataOutputStream(kbdSuProcess.outputStream)

    private val mouseWriter: UsbHidNodeWriter? = try {
        mouseDevicePath?.let { UsbHidNodeWriter(it) }
    } catch (e: Exception) {
        close()
        throw e
    }

    private val gamepadWriter: UsbHidNodeWriter? = try {
        gamepadDevicePath?.let { UsbHidNodeWriter(it) }
    } catch (_: Exception) {
        null
    }

    override val mouseAvailable: Boolean
        get() = mouseWriter != null

    override val gamepadAvailable: Boolean
        get() = gamepadWriter != null

    override val mousePollIntervalMs: Long
        get() = if (mouseWriter?.isDirect == true) 4L else 14L

    override fun sendKeyboardReport(report: ByteArray): Result<Unit> {
        return try {
            val hex = report.joinToString("") { "\\x%02x".format(it) }
            kbdSuStream.writeBytes("printf '${hex}' > $kbdDevicePath\n")
            kbdSuStream.flush()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun sendMouseReport(report: ByteArray) {
        mouseWriter?.write(report)
    }

    override fun sendGamepadReport(report: ByteArray) {
        gamepadWriter?.write(report)
    }

    override fun close() {
        try {
            kbdSuStream.apply {
                writeBytes("exit\n")
                flush()
                close()
            }
            kbdSuProcess.waitFor()
        } catch (_: Exception) { }

        mouseWriter?.close()
        gamepadWriter?.close()
    }
}

private class BluetoothHidOutputBackend(
    private val sendKbd: (ByteArray) -> Result<Unit>,
    private val sendMouse: (ByteArray) -> Unit,
    private val sendGamepad: (ByteArray) -> Unit
) : HidOutputBackend {

    override val mouseAvailable: Boolean = true
    override val gamepadAvailable: Boolean = true
    override val mousePollIntervalMs: Long = 11L
    override val gamepadPollIntervalMs: Long = 11L

    override fun sendKeyboardReport(report: ByteArray): Result<Unit> = sendKbd(report)

    override fun sendMouseReport(report: ByteArray) = sendMouse(report)

    override fun sendGamepadReport(report: ByteArray) = sendGamepad(report)

    override fun close() {
        // Profile lifecycle is managed by BluetoothHidManager.
    }
}

private class UsbBridgeOutputBackend(
    private val sendKbd: (ByteArray) -> Result<Unit>,
    private val sendMouse: (ByteArray) -> Unit,
    private val sendGamepad: (ByteArray) -> Unit
) : HidOutputBackend {

    override val mouseAvailable: Boolean = true
    override val gamepadAvailable: Boolean = true
    override val mousePollIntervalMs: Long = 4L
    override val gamepadPollIntervalMs: Long = 4L

    override fun sendKeyboardReport(report: ByteArray): Result<Unit> = sendKbd(report)

    override fun sendMouseReport(report: ByteArray) = sendMouse(report)

    override fun sendGamepadReport(report: ByteArray) = sendGamepad(report)

    override fun close() {
        // Transport lifecycle is managed by UsbBridgeServer.
    }
}

/**
 * Sends HID Keyboard (8-byte), Mouse (4-byte), and Gamepad (9-byte) reports over the active
 * transport backend.
 *
 * Mouse/Trackpad and Gamepad reports use dedicated zero-allocation, monotonic-deadline worker
 * threads so 120Hz/240Hz touch events never queue up or burst out of phase with Bluetooth HID.
 */
object HidReportSender {

    const val MOUSE_LEFT = 0x01
    const val MOUSE_RIGHT = 0x02
    const val MOUSE_MIDDLE = 0x04

    @Volatile
    private var activeBackend: HidOutputBackend? = null

    // Coalescing state for lag-free trackpad updates
    private val mouseLock = Object()
    private var pendingDx = 0
    private var pendingDy = 0
    private var pendingWheel = 0
    private var pendingButtons = 0
    private var lastSentMouseButtons = 0
    private val pendingClickQueue = ArrayDeque<Int>()
    private var hasPendingMouse = false
    private var mouseImmediateWake = false
    @Volatile
    private var mouseWorkerRunning = false
    private var mouseWorkerThread: Thread? = null

    // Coalescing state for lag-free gamepad updates
    private val gamepadLock = Object()
    private var pendingGamepadButtons = 0
    private var pendingGamepadHat = HidGamepadMap.HAT_NEUTRAL
    private var pendingLx = HidGamepadMap.AXIS_CENTER
    private var pendingLy = HidGamepadMap.AXIS_CENTER
    private var pendingRx = HidGamepadMap.AXIS_CENTER
    private var pendingRy = HidGamepadMap.AXIS_CENTER
    private var pendingL2 = 0
    private var pendingR2 = 0
    private var lastSentGamepadButtons = 0
    private var lastSentGamepadHat = HidGamepadMap.HAT_NEUTRAL
    private var hasPendingGamepad = false
    private var gamepadImmediateWake = false
    @Volatile
    private var gamepadWorkerRunning = false
    private var gamepadWorkerThread: Thread? = null

    suspend fun open(
        keyboardPath: String = "/dev/hidg0",
        mousePath: String? = null,
        gamepadPath: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            close()
            val backend = UsbHidOutputBackend(keyboardPath, mousePath, gamepadPath)
            activeBackend = backend
            if (backend.mouseAvailable) {
                startMouseWorker()
            }
            if (backend.gamepadAvailable) {
                startGamepadWorker()
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun openBluetooth(
        sendKbd: (ByteArray) -> Result<Unit>,
        sendMouse: (ByteArray) -> Unit,
        sendGamepad: (ByteArray) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            close()
            val backend = BluetoothHidOutputBackend(sendKbd, sendMouse, sendGamepad)
            activeBackend = backend
            startMouseWorker()
            startGamepadWorker()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun openUsbBridge(
        sendKbd: (ByteArray) -> Result<Unit>,
        sendMouse: (ByteArray) -> Unit,
        sendGamepad: (ByteArray) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            close()
            val backend = UsbBridgeOutputBackend(sendKbd, sendMouse, sendGamepad)
            activeBackend = backend
            startMouseWorker()
            startGamepadWorker()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun close() = withContext(Dispatchers.IO) {
        stopMouseWorker()
        stopGamepadWorker()
        try {
            activeBackend?.close()
        } catch (_: Exception) { }
        activeBackend = null
    }

    private fun startMouseWorker() {
        mouseWorkerRunning = true
        synchronized(mouseLock) {
            pendingDx = 0
            pendingDy = 0
            pendingWheel = 0
            pendingButtons = 0
            lastSentMouseButtons = 0
            pendingClickQueue.clear()
            hasPendingMouse = false
            mouseImmediateWake = false
        }

        val t = Thread({
            val reportBuf = ByteArray(4)
            var nextTickNs = 0L

            while (mouseWorkerRunning) {
                val buttons: Int
                val dx: Int
                val dy: Int
                val wheel: Int
                val clickMask: Int?
                val urgent: Boolean

                synchronized(mouseLock) {
                    while (mouseWorkerRunning) {
                        val hasWork = hasPendingMouse || pendingClickQueue.isNotEmpty()
                        if (!hasWork) {
                            nextTickNs = 0L
                            try {
                                mouseLock.wait()
                            } catch (_: InterruptedException) {
                                break
                            }
                            continue
                        }

                        if (mouseImmediateWake || nextTickNs == 0L) {
                            break
                        }

                        val remainNs = nextTickNs - System.nanoTime()
                        if (remainNs <= 0L) {
                            break
                        }

                        try {
                            mouseLock.wait(remainNs / 1_000_000L, (remainNs % 1_000_000L).toInt())
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                    if (!mouseWorkerRunning) return@Thread

                    clickMask = if (pendingClickQueue.isNotEmpty()) pendingClickQueue.removeFirst() else null
                    buttons = pendingButtons
                    dx = pendingDx.coerceIn(-127, 127)
                    dy = pendingDy.coerceIn(-127, 127)
                    wheel = pendingWheel.coerceIn(-127, 127)
                    urgent = mouseImmediateWake

                    lastSentMouseButtons = buttons
                    mouseImmediateWake = false
                    pendingDx -= dx
                    pendingDy -= dy
                    pendingWheel -= wheel
                    hasPendingMouse = (pendingDx != 0 || pendingDy != 0 || pendingWheel != 0)
                }

                val backend = activeBackend ?: continue
                if (!backend.mouseAvailable) continue

                if (clickMask != null) {
                    // Send full press + short hold + release so clicks are never coalesced away
                    reportBuf[0] = (buttons or clickMask).toByte()
                    reportBuf[1] = dx.toByte()
                    reportBuf[2] = dy.toByte()
                    reportBuf[3] = wheel.toByte()
                    backend.sendMouseReport(reportBuf)
                    try {
                        Thread.sleep(20L)
                    } catch (_: InterruptedException) { }
                    reportBuf[0] = (buttons and clickMask.inv()).toByte()
                    reportBuf[1] = 0
                    reportBuf[2] = 0
                    reportBuf[3] = 0
                    backend.sendMouseReport(reportBuf)
                    nextTickNs = System.nanoTime() + backend.mousePollIntervalMs * 1_000_000L
                } else {
                    reportBuf[0] = buttons.toByte()
                    reportBuf[1] = dx.toByte()
                    reportBuf[2] = dy.toByte()
                    reportBuf[3] = wheel.toByte()
                    backend.sendMouseReport(reportBuf)

                    val intervalNs = backend.mousePollIntervalMs * 1_000_000L
                    val nowNs = System.nanoTime()
                    nextTickNs = if (urgent || nextTickNs == 0L) {
                        nowNs + intervalNs
                    } else {
                        maxOf(nextTickNs + intervalNs, nowNs)
                    }
                }
            }
        }, "PocketKeyboard-MouseWorker")
        t.isDaemon = true
        t.priority = Thread.MAX_PRIORITY
        t.start()
        mouseWorkerThread = t
    }

    private fun stopMouseWorker() {
        mouseWorkerRunning = false
        synchronized(mouseLock) {
            mouseLock.notifyAll()
        }
        try {
            mouseWorkerThread?.join(300)
        } catch (_: Exception) { }
        mouseWorkerThread = null
    }

    private fun startGamepadWorker() {
        gamepadWorkerRunning = true
        synchronized(gamepadLock) {
            pendingGamepadButtons = 0
            pendingGamepadHat = HidGamepadMap.HAT_NEUTRAL
            pendingLx = HidGamepadMap.AXIS_CENTER
            pendingLy = HidGamepadMap.AXIS_CENTER
            pendingRx = HidGamepadMap.AXIS_CENTER
            pendingRy = HidGamepadMap.AXIS_CENTER
            pendingL2 = 0
            pendingR2 = 0
            lastSentGamepadButtons = 0
            lastSentGamepadHat = HidGamepadMap.HAT_NEUTRAL
            hasPendingGamepad = false
            gamepadImmediateWake = false
        }

        val t = Thread({
            val reportBuf = ByteArray(9)
            var nextTickNs = 0L

            while (gamepadWorkerRunning) {
                val buttons: Int
                val hat: Int
                val lx: Int
                val ly: Int
                val rx: Int
                val ry: Int
                val l2: Int
                val r2: Int
                val urgent: Boolean

                synchronized(gamepadLock) {
                    while (gamepadWorkerRunning) {
                        if (!hasPendingGamepad) {
                            nextTickNs = 0L
                            try {
                                gamepadLock.wait()
                            } catch (_: InterruptedException) {
                                break
                            }
                            continue
                        }

                        if (gamepadImmediateWake || nextTickNs == 0L) {
                            break
                        }

                        val remainNs = nextTickNs - System.nanoTime()
                        if (remainNs <= 0L) {
                            break
                        }

                        try {
                            gamepadLock.wait(remainNs / 1_000_000L, (remainNs % 1_000_000L).toInt())
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                    if (!gamepadWorkerRunning) return@Thread

                    buttons = pendingGamepadButtons
                    hat = pendingGamepadHat
                    lx = pendingLx
                    ly = pendingLy
                    rx = pendingRx
                    ry = pendingRy
                    l2 = pendingL2
                    r2 = pendingR2
                    urgent = gamepadImmediateWake
                    lastSentGamepadButtons = buttons
                    lastSentGamepadHat = hat
                    hasPendingGamepad = false
                    gamepadImmediateWake = false
                }

                val backend = activeBackend ?: continue
                if (!backend.gamepadAvailable) continue

                reportBuf[0] = (buttons and 0xFF).toByte()
                reportBuf[1] = ((buttons ushr 8) and 0xFF).toByte()
                reportBuf[2] = (hat and 0x0F).toByte()
                reportBuf[3] = lx.coerceIn(0, 255).toByte()
                reportBuf[4] = ly.coerceIn(0, 255).toByte()
                reportBuf[5] = rx.coerceIn(0, 255).toByte()
                reportBuf[6] = ry.coerceIn(0, 255).toByte()
                reportBuf[7] = l2.coerceIn(0, 255).toByte()
                reportBuf[8] = r2.coerceIn(0, 255).toByte()
                backend.sendGamepadReport(reportBuf)

                val intervalNs = backend.gamepadPollIntervalMs * 1_000_000L
                val nowNs = System.nanoTime()
                nextTickNs = if (urgent || nextTickNs == 0L) {
                    nowNs + intervalNs
                } else {
                    maxOf(nextTickNs + intervalNs, nowNs)
                }
            }
        }, "PocketKeyboard-GamepadWorker")
        t.isDaemon = true
        t.priority = Thread.MAX_PRIORITY
        t.start()
        gamepadWorkerThread = t
    }

    private fun stopGamepadWorker() {
        gamepadWorkerRunning = false
        synchronized(gamepadLock) {
            gamepadLock.notifyAll()
        }
        try {
            gamepadWorkerThread?.join(300)
        } catch (_: Exception) { }
        gamepadWorkerThread = null
    }

    // ── Keyboard reports ──

    suspend fun sendKeyDown(modifiers: Int, keyCode: Int): Result<Unit> {
        val report = ByteArray(8)
        report[0] = modifiers.toByte()
        report[2] = keyCode.toByte()
        return sendReport(report)
    }

    suspend fun sendKeyUp(modifiers: Int = 0): Result<Unit> {
        val report = ByteArray(8)
        report[0] = modifiers.toByte()
        return sendReport(report)
    }

    suspend fun sendTap(modifiers: Int, keyCode: Int): Result<Unit> {
        val down = sendKeyDown(modifiers, keyCode)
        if (down.isFailure) return down
        delay(10)
        return sendKeyUp(0)
    }

    suspend fun sendModifierOnly(modifiers: Int): Result<Unit> {
        val report = ByteArray(8)
        report[0] = modifiers.toByte()
        return sendReport(report)
    }

    suspend fun sendReport(report: ByteArray): Result<Unit> = withContext(Dispatchers.IO) {
        val backend = activeBackend ?: return@withContext Result.failure(
            Exception("HID session not open. Connect or arm HID first.")
        )
        backend.sendKeyboardReport(report)
    }

    // ── Mouse / Trackpad reports (coalesced, deadline-paced) ──

    fun sendMouseReportAsync(buttons: Int, dx: Int, dy: Int, wheel: Int = 0) {
        if (activeBackend?.mouseAvailable != true || !mouseWorkerRunning) return
        synchronized(mouseLock) {
            if (buttons != lastSentMouseButtons) {
                mouseImmediateWake = true
            }
            pendingButtons = buttons
            pendingDx += dx
            pendingDy += dy
            pendingWheel += wheel
            hasPendingMouse = true
            mouseLock.notifyAll()
        }
    }

    fun sendMouseClickAsync(buttonMask: Int, baseButtons: Int = 0) {
        if (activeBackend?.mouseAvailable != true || !mouseWorkerRunning) return
        synchronized(mouseLock) {
            pendingButtons = baseButtons
            pendingClickQueue.addLast(buttonMask)
            mouseImmediateWake = true
            mouseLock.notifyAll()
        }
    }

    // ── Gamepad reports (coalesced, deadline-paced) ──

    fun sendGamepadStateAsync(
        buttonsMask: Int,
        hat: Int,
        lx: Int,
        ly: Int,
        rx: Int,
        ry: Int,
        l2: Int,
        r2: Int
    ) {
        if (activeBackend?.gamepadAvailable != true || !gamepadWorkerRunning) return
        synchronized(gamepadLock) {
            if (buttonsMask != lastSentGamepadButtons || hat != lastSentGamepadHat) {
                gamepadImmediateWake = true
            }
            pendingGamepadButtons = buttonsMask
            pendingGamepadHat = hat
            pendingLx = lx.coerceIn(0, 255)
            pendingLy = ly.coerceIn(0, 255)
            pendingRx = rx.coerceIn(0, 255)
            pendingRy = ry.coerceIn(0, 255)
            pendingL2 = l2.coerceIn(0, 255)
            pendingR2 = r2.coerceIn(0, 255)
            hasPendingGamepad = true
            gamepadLock.notifyAll()
        }
    }

    fun resetGamepadAsync() {
        sendGamepadStateAsync(
            buttonsMask = 0,
            hat = HidGamepadMap.HAT_NEUTRAL,
            lx = HidGamepadMap.AXIS_CENTER,
            ly = HidGamepadMap.AXIS_CENTER,
            rx = HidGamepadMap.AXIS_CENTER,
            ry = HidGamepadMap.AXIS_CENTER,
            l2 = 0,
            r2 = 0
        )
    }
}
