package com.pocketkeyboard.hid

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.DataOutputStream
import java.io.FileOutputStream

/**
 * Sends USB HID Keyboard (8-byte) and Mouse (4-byte) reports.
 *
 * Mouse/Trackpad reports use a delta-coalescing worker thread so high-frequency
 * touch events (120Hz/240Hz) never pile up in a queue. Any deltas arriving while
 * a report is being written are summed and sent in the very next frame (~60Hz).
 */
object HidReportSender {

    const val MOUSE_LEFT = 0x01
    const val MOUSE_RIGHT = 0x02
    const val MOUSE_MIDDLE = 0x04

    private var kbdDevicePath: String = "/dev/hidg0"
    @Volatile
    private var mouseDevicePath: String? = null

    private var kbdSuProcess: Process? = null
    private var kbdSuStream: DataOutputStream? = null

    private var mouseDirectStream: FileOutputStream? = null
    private var mouseSuProcess: Process? = null
    private var mouseSuStream: DataOutputStream? = null

    // Coalescing state for lag-free trackpad updates
    private val mouseLock = Object()
    private var pendingDx = 0
    private var pendingDy = 0
    private var pendingWheel = 0
    private var pendingButtons = 0
    private val pendingClickQueue = ArrayDeque<Int>()
    private var hasPendingMouse = false
    @Volatile
    private var mouseWorkerRunning = false
    private var mouseWorkerThread: Thread? = null

    suspend fun open(keyboardPath: String = "/dev/hidg0", mousePath: String? = null): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                close()
                kbdDevicePath = keyboardPath
                mouseDevicePath = mousePath

                val kbdProc = Runtime.getRuntime().exec("su")
                kbdSuProcess = kbdProc
                kbdSuStream = DataOutputStream(kbdProc.outputStream)

                if (mousePath != null) {
                    mouseDirectStream = try {
                        FileOutputStream(mousePath)
                    } catch (_: Exception) {
                        null
                    }

                    if (mouseDirectStream == null) {
                        val mProc = Runtime.getRuntime().exec("su")
                        mouseSuProcess = mProc
                        mouseSuStream = DataOutputStream(mProc.outputStream)
                    }
                    startMouseWorker()
                }

                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun close() = withContext(Dispatchers.IO) {
        stopMouseWorker()

        try {
            kbdSuStream?.apply {
                writeBytes("exit\n")
                flush()
                close()
            }
            kbdSuProcess?.waitFor()
        } catch (_: Exception) { }
        kbdSuStream = null
        kbdSuProcess = null

        try {
            mouseDirectStream?.close()
        } catch (_: Exception) { }
        mouseDirectStream = null

        try {
            mouseSuStream?.apply {
                writeBytes("exit\n")
                flush()
                close()
            }
            mouseSuProcess?.waitFor()
        } catch (_: Exception) { }
        mouseSuStream = null
        mouseSuProcess = null
    }

    private fun startMouseWorker() {
        mouseWorkerRunning = true
        synchronized(mouseLock) {
            pendingDx = 0
            pendingDy = 0
            pendingWheel = 0
            pendingButtons = 0
            pendingClickQueue.clear()
            hasPendingMouse = false
        }

        val t = Thread({
            while (mouseWorkerRunning) {
                val buttons: Int
                val dx: Int
                val dy: Int
                val wheel: Int
                val clickMask: Int?

                synchronized(mouseLock) {
                    while (mouseWorkerRunning && !hasPendingMouse && pendingClickQueue.isEmpty()) {
                        try {
                            mouseLock.wait()
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

                    pendingDx -= dx
                    pendingDy -= dy
                    pendingWheel -= wheel
                    hasPendingMouse = (pendingDx != 0 || pendingDy != 0 || pendingWheel != 0)
                }

                val path = mouseDevicePath ?: continue
                if (clickMask != null) {
                    // Send full press + short hold + release so clicks are never coalesced away
                    writeMouseReport(path, byteArrayOf((buttons or clickMask).toByte(), dx.toByte(), dy.toByte(), wheel.toByte()))
                    try {
                        Thread.sleep(20L)
                    } catch (_: InterruptedException) { }
                    writeMouseReport(path, byteArrayOf((buttons and clickMask.inv()).toByte(), 0, 0, 0))
                } else {
                    val report = byteArrayOf(
                        buttons.toByte(),
                        dx.toByte(),
                        dy.toByte(),
                        wheel.toByte()
                    )
                    writeMouseReport(path, report)
                }

                // Pace writes (~60-80Hz) so su pipe never backs up with queued printf commands
                try {
                    Thread.sleep(if (mouseDirectStream != null) 4L else 14L)
                } catch (_: InterruptedException) { }
            }
        }, "PocketKeyboard-MouseWorker")
        t.isDaemon = true
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

    private fun writeMouseReport(path: String, report: ByteArray) {
        val direct = mouseDirectStream
        if (direct != null) {
            try {
                direct.write(report)
                direct.flush()
                return
            } catch (_: Exception) {
                try { direct.close() } catch (_: Exception) { }
                mouseDirectStream = null
                try {
                    val mProc = Runtime.getRuntime().exec("su")
                    mouseSuProcess = mProc
                    mouseSuStream = DataOutputStream(mProc.outputStream)
                } catch (_: Exception) { }
            }
        }

        val suOs = mouseSuStream ?: return
        try {
            val hex = report.joinToString("") { "\\x%02x".format(it) }
            suOs.writeBytes("printf '${hex}' > $path\n")
            suOs.flush()
        } catch (_: Exception) { }
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
        delay(8)
        return sendKeyUp(0)
    }

    suspend fun sendModifierOnly(modifiers: Int): Result<Unit> {
        val report = ByteArray(8)
        report[0] = modifiers.toByte()
        return sendReport(report)
    }

    suspend fun sendReport(report: ByteArray): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val os = kbdSuStream ?: return@withContext Result.failure(
                Exception("HID session not open. Call open() first.")
            )
            val hex = report.joinToString("") { "\\x%02x".format(it) }
            os.writeBytes("printf '${hex}' > $kbdDevicePath\n")
            os.flush()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── Mouse / Trackpad reports (coalesced, lag-free) ──

    fun sendMouseReportAsync(buttons: Int, dx: Int, dy: Int, wheel: Int = 0) {
        if (mouseDevicePath == null || !mouseWorkerRunning) return
        synchronized(mouseLock) {
            pendingButtons = buttons
            pendingDx += dx
            pendingDy += dy
            pendingWheel += wheel
            hasPendingMouse = true
            mouseLock.notifyAll()
        }
    }

    fun sendMouseClickAsync(buttonMask: Int, baseButtons: Int = 0) {
        if (mouseDevicePath == null || !mouseWorkerRunning) return
        synchronized(mouseLock) {
            pendingButtons = baseButtons
            pendingClickQueue.addLast(buttonMask)
            mouseLock.notifyAll()
        }
    }
}
