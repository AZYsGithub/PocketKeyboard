package com.pocketkeyboard.hid

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

data class PairedHostDevice(
    val name: String,
    val address: String,
    val isConnected: Boolean = false
)

enum class BluetoothHidState {
    UNSUPPORTED,
    NO_ADAPTER,
    PERMISSION_REQUIRED,
    BLUETOOTH_OFF,
    IDLE,
    REGISTERING,
    READY,
    CONNECTING,
    CONNECTED,
    ERROR
}

data class BluetoothHidSnapshot(
    val state: BluetoothHidState,
    val pairedDevices: List<PairedHostDevice> = emptyList(),
    val connectedDeviceName: String? = null,
    val connectedDeviceAddress: String? = null,
    val errorMessage: String? = null
)

object BluetoothPermissions {

    fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
        } else {
            emptyArray()
        }
    }

    fun hasRequiredPermissions(context: Context): Boolean {
        return requiredPermissions().all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }
}

/**
 * Manages Android's built-in Bluetooth HID Device profile (API 28+, no root required).
 * Exposes a composite Boot Keyboard + 3-Button Wheel Mouse + Gamepad SDP descriptor.
 */
@RequiresApi(Build.VERSION_CODES.P)
class BluetoothHidManager(
    context: Context,
    private val onHostConnected: (BluetoothDevice) -> Unit,
    private val onHostDisconnected: () -> Unit,
    private val onHostCapsLockChanged: ((Boolean) -> Unit)? = null
) {

    private val appContext = context.applicationContext
    private val bluetoothAdapter: BluetoothAdapter? =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val callbackExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private val _snapshot = MutableStateFlow(
        BluetoothHidSnapshot(state = BluetoothHidState.IDLE)
    )
    val snapshot: StateFlow<BluetoothHidSnapshot> = _snapshot.asStateFlow()

    @Volatile
    private var hasPermissionsCached = false

    @Volatile
    private var wantActive = false

    @Volatile
    private var isRequestingProxy = false

    @Volatile
    private var triedRegisterWithQos = false

    @Volatile
    private var hidDevice: BluetoothHidDevice? = null

    @Volatile
    private var isAppRegistered = false

    @Volatile
    private var connectedDevice: BluetoothDevice? = null

    @Volatile
    private var connectingDevice: BluetoothDevice? = null

    @Volatile
    private var pendingConnectAddress: String? = null

    @Volatile
    private var lastKeyboardReport = ByteArray(8)

    @Volatile
    private var lastGamepadReport = HidDescriptors.GAMEPAD_NEUTRAL_REPORT.copyOf()

    @Volatile
    private var receiverRegistered = false

    private val profileListener = object : BluetoothProfile.ServiceListener {
        @SuppressLint("MissingPermission")
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            isRequestingProxy = false

            val device = proxy as? BluetoothHidDevice ?: return
            if (!wantActive || !checkAndCachePermissions()) {
                try {
                    bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, device)
                } catch (_: Exception) {
                }
                return
            }

            hidDevice = device
            registerHidApp(device, allowQos = true)
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            isRequestingProxy = false
            triedRegisterWithQos = false

            val hadHost = connectedDevice != null
            hidDevice = null
            isAppRegistered = false
            connectedDevice = null
            connectingDevice = null

            if (hadHost) {
                onHostDisconnected()
            }
            refreshState()
        }
    }

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        @SuppressLint("MissingPermission")
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            isAppRegistered = registered

            if (registered) {
                triedRegisterWithQos = false
                val targetAddress = pendingConnectAddress
                pendingConnectAddress = null
                if (targetAddress != null) {
                    connectToAddress(targetAddress)
                } else {
                    refreshState()
                }
            } else {
                val hadHost = connectedDevice != null
                connectedDevice = null
                connectingDevice = null
                if (hadHost) {
                    onHostDisconnected()
                }

                // If the OEM BT stack rejected QoS asynchronously, retry once without QoS
                val hd = hidDevice
                if (wantActive && triedRegisterWithQos && hd != null) {
                    triedRegisterWithQos = false
                    registerHidApp(hd, allowQos = false)
                    return
                }

                if (!wantActive) {
                    refreshState(explicitState = BluetoothHidState.IDLE)
                } else {
                    wantActive = false
                    refreshState(
                        explicitState = BluetoothHidState.ERROR,
                        error = "Bluetooth HID service was unregistered by the system."
                    )
                }
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTING -> {
                    connectingDevice = device
                    refreshState(explicitState = BluetoothHidState.CONNECTING)
                }

                BluetoothProfile.STATE_CONNECTED -> {
                    connectingDevice = null
                    connectedDevice = device
                    refreshState(explicitState = BluetoothHidState.CONNECTED)
                    onHostConnected(device)
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (connectingDevice?.address == device.address) {
                        connectingDevice = null
                    }
                    if (connectedDevice?.address == device.address) {
                        connectedDevice = null
                        onHostDisconnected()
                    }
                    refreshState()
                }

                BluetoothProfile.STATE_DISCONNECTING -> {
                    refreshState()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            val hd = hidDevice ?: return
            if (!checkAndCachePermissions()) return

            when {
                type == BluetoothHidDevice.REPORT_TYPE_INPUT &&
                    id.toInt() == HidDescriptors.REPORT_ID_KEYBOARD -> {
                    hd.replyReport(device, type, id, lastKeyboardReport.copyOf())
                }

                type == BluetoothHidDevice.REPORT_TYPE_INPUT &&
                    id.toInt() == HidDescriptors.REPORT_ID_MOUSE -> {
                    hd.replyReport(device, type, id, ByteArray(4))
                }

                type == BluetoothHidDevice.REPORT_TYPE_INPUT &&
                    id.toInt() == HidDescriptors.REPORT_ID_GAMEPAD -> {
                    hd.replyReport(device, type, id, lastGamepadReport.copyOf())
                }

                else -> {
                    hd.reportError(device, BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID)
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray?) {
            val hd = hidDevice ?: return
            if (!checkAndCachePermissions()) return

            hd.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
            if (type == BluetoothHidDevice.REPORT_TYPE_OUTPUT &&
                id.toInt() == HidDescriptors.REPORT_ID_KEYBOARD &&
                data != null &&
                data.isNotEmpty()
            ) {
                val capsLockOn = (data[0].toInt() and 0x02) != 0
                onHostCapsLockChanged?.invoke(capsLockOn)
            }
        }

        override fun onInterruptData(device: BluetoothDevice, reportId: Byte, data: ByteArray?) {
            if (reportId.toInt() == HidDescriptors.REPORT_ID_KEYBOARD &&
                data != null &&
                data.isNotEmpty()
            ) {
                val capsLockOn = (data[0].toInt() and 0x02) != 0
                onHostCapsLockChanged?.invoke(capsLockOn)
            }
        }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val btState = intent.getIntExtra(
                        BluetoothAdapter.EXTRA_STATE,
                        BluetoothAdapter.ERROR
                    )
                    if (btState == BluetoothAdapter.STATE_TURNING_OFF ||
                        btState == BluetoothAdapter.STATE_OFF
                    ) {
                        val hadHost = connectedDevice != null
                        wantActive = false
                        isRequestingProxy = false
                        triedRegisterWithQos = false
                        isAppRegistered = false
                        hidDevice = null
                        connectedDevice = null
                        connectingDevice = null
                        pendingConnectAddress = null
                        if (hadHost) {
                            onHostDisconnected()
                        }
                    }
                    refreshState()
                }

                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    refreshState()
                }
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(
            appContext,
            bluetoothReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
        receiverRegistered = true
        refreshState()
    }

    private fun checkAndCachePermissions(): Boolean {
        val granted = BluetoothPermissions.hasRequiredPermissions(appContext)
        hasPermissionsCached = granted
        return granted
    }

    @SuppressLint("MissingPermission")
    fun refreshState(
        explicitState: BluetoothHidState? = null,
        error: String? = null
    ) {
        val adapter = bluetoothAdapter
        if (adapter == null) {
            _snapshot.value = BluetoothHidSnapshot(
                state = BluetoothHidState.NO_ADAPTER,
                errorMessage = error ?: "No Bluetooth adapter found on this device."
            )
            return
        }

        if (!checkAndCachePermissions()) {
            _snapshot.value = BluetoothHidSnapshot(
                state = BluetoothHidState.PERMISSION_REQUIRED,
                errorMessage = error
            )
            return
        }

        if (!adapter.isEnabled) {
            _snapshot.value = BluetoothHidSnapshot(
                state = BluetoothHidState.BLUETOOTH_OFF,
                errorMessage = error
            )
            return
        }

        val paired = getPairedDevicesList(adapter)
        val derivedState = when {
            explicitState != null -> explicitState
            error != null -> BluetoothHidState.ERROR
            connectedDevice != null -> BluetoothHidState.CONNECTED
            connectingDevice != null -> BluetoothHidState.CONNECTING
            isAppRegistered -> BluetoothHidState.READY
            wantActive -> BluetoothHidState.REGISTERING
            else -> BluetoothHidState.IDLE
        }

        val connectedAddr = connectedDevice?.address
        val connectedName = connectedDevice?.let { dev ->
            try {
                dev.name?.takeIf { it.isNotBlank() } ?: dev.address
            } catch (_: SecurityException) {
                dev.address
            }
        }

        _snapshot.value = BluetoothHidSnapshot(
            state = derivedState,
            pairedDevices = paired,
            connectedDeviceName = connectedName,
            connectedDeviceAddress = connectedAddr,
            errorMessage = error
        )
    }

    @SuppressLint("MissingPermission")
    fun start(autoConnectAddress: String? = null) {
        val adapter = bluetoothAdapter
        if (adapter == null) {
            refreshState(
                explicitState = BluetoothHidState.NO_ADAPTER,
                error = "No Bluetooth adapter found on this device."
            )
            return
        }

        if (!checkAndCachePermissions()) {
            refreshState(explicitState = BluetoothHidState.PERMISSION_REQUIRED)
            return
        }

        if (!adapter.isEnabled) {
            refreshState(explicitState = BluetoothHidState.BLUETOOTH_OFF)
            return
        }

        wantActive = true
        if (autoConnectAddress != null) {
            pendingConnectAddress = autoConnectAddress
        }

        val existingHid = hidDevice
        if (existingHid != null && isAppRegistered) {
            val target = pendingConnectAddress
            pendingConnectAddress = null
            if (target != null) {
                connectToAddress(target)
            } else {
                refreshState()
            }
            return
        }

        if (existingHid != null) {
            refreshState(explicitState = BluetoothHidState.REGISTERING)
            registerHidApp(existingHid, allowQos = true)
            return
        }

        if (isRequestingProxy) {
            refreshState(explicitState = BluetoothHidState.REGISTERING)
            return
        }

        isRequestingProxy = true
        refreshState(explicitState = BluetoothHidState.REGISTERING)
        val ok = adapter.getProfileProxy(appContext, profileListener, BluetoothProfile.HID_DEVICE)
        if (!ok) {
            isRequestingProxy = false
            wantActive = false
            pendingConnectAddress = null
            refreshState(
                explicitState = BluetoothHidState.ERROR,
                error = "Failed to open Bluetooth HID Device profile. This device's Bluetooth stack may not support HID Device mode."
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerHidApp(device: BluetoothHidDevice, allowQos: Boolean = true) {
        if (!checkAndCachePermissions()) {
            refreshState(explicitState = BluetoothHidState.PERMISSION_REQUIRED)
            return
        }

        val sdp = BluetoothHidDeviceAppSdpSettings(
            "PocketKeyboard",
            "Wireless Keyboard, Trackpad & Gamepad",
            "PocketKeyboard",
            BluetoothHidDevice.SUBCLASS1_COMBO,
            HidDescriptors.BLUETOOTH_COMBO_REPORT_DESCRIPTOR
        )

        var ok = false
        var lastException: Exception? = null

        if (allowQos) {
            val outQos = BluetoothHidDeviceAppQosSettings(
                BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
                800,
                9,
                0,
                11250,
                BluetoothHidDeviceAppQosSettings.MAX
            )
            triedRegisterWithQos = true
            ok = try {
                device.registerApp(sdp, null, outQos, callbackExecutor, hidCallback)
            } catch (e: Exception) {
                lastException = e
                false
            }
        }

        if (!ok) {
            triedRegisterWithQos = false
            ok = try {
                device.registerApp(sdp, null, null, callbackExecutor, hidCallback)
            } catch (e: Exception) {
                lastException = e
                false
            }
        }

        if (!ok) {
            wantActive = false
            pendingConnectAddress = null
            isAppRegistered = false
            val msg = if (lastException != null) {
                "Bluetooth HID registration failed: ${lastException.message}"
            } else {
                "Failed to register Bluetooth HID service. Another HID app may be active or this device's Bluetooth stack may not support HID Device mode."
            }
            refreshState(
                explicitState = BluetoothHidState.ERROR,
                error = msg
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToAddress(address: String) {
        val adapter = bluetoothAdapter
        if (adapter == null) {
            refreshState(explicitState = BluetoothHidState.NO_ADAPTER)
            return
        }
        if (!checkAndCachePermissions()) {
            refreshState(explicitState = BluetoothHidState.PERMISSION_REQUIRED)
            return
        }
        if (!adapter.isEnabled) {
            refreshState(explicitState = BluetoothHidState.BLUETOOTH_OFF)
            return
        }

        val hd = hidDevice
        if (hd == null || !isAppRegistered) {
            start(autoConnectAddress = address)
            return
        }

        val target = try {
            adapter.bondedDevices?.firstOrNull { it.address == address }
                ?: adapter.getRemoteDevice(address)
        } catch (e: Exception) {
            refreshState(
                explicitState = BluetoothHidState.ERROR,
                error = "Invalid Bluetooth host address: ${e.message}"
            )
            return
        }

        val previousHost = connectedDevice
        if (previousHost != null && previousHost.address != address) {
            try {
                hd.disconnect(previousHost)
            } catch (_: Exception) {
            }
        }

        connectingDevice = target
        refreshState(explicitState = BluetoothHidState.CONNECTING)

        val initiated = try {
            hd.connect(target)
        } catch (e: Exception) {
            connectingDevice = null
            refreshState(
                explicitState = BluetoothHidState.ERROR,
                error = "Bluetooth connect failed: ${e.message}"
            )
            return
        }

        if (!initiated) {
            connectingDevice = null
            val label = try {
                target.name?.takeIf { it.isNotBlank() } ?: address
            } catch (_: SecurityException) {
                address
            }
            refreshState(
                explicitState = BluetoothHidState.ERROR,
                error = "Could not connect to $label. Make sure the host is paired and Bluetooth is enabled on it."
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnectHost() {
        if (!checkAndCachePermissions()) return
        val hd = hidDevice ?: return
        val target = connectedDevice ?: connectingDevice ?: return
        try {
            hd.disconnect(target)
        } catch (_: Exception) {
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        wantActive = false
        isRequestingProxy = false
        triedRegisterWithQos = false
        pendingConnectAddress = null
        connectingDevice = null

        val hadHost = connectedDevice != null
        val hd = hidDevice
        val host = connectedDevice

        if (hd != null && checkAndCachePermissions()) {
            try {
                if (host != null) {
                    hd.disconnect(host)
                }
            } catch (_: Exception) {
            }
            try {
                if (isAppRegistered) {
                    hd.unregisterApp()
                }
            } catch (_: Exception) {
            }
            try {
                bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hd)
            } catch (_: Exception) {
            }
        }

        hidDevice = null
        isAppRegistered = false
        connectedDevice = null
        lastKeyboardReport = ByteArray(8)
        HidDescriptors.GAMEPAD_NEUTRAL_REPORT.copyInto(lastGamepadReport)

        if (hadHost) {
            onHostDisconnected()
        }
        refreshState()
    }

    @SuppressLint("MissingPermission")
    fun sendKeyboardReport(report: ByteArray): Result<Unit> {
        val hd = hidDevice
            ?: return Result.failure(IllegalStateException("Bluetooth HID service is not active."))
        val host = connectedDevice
            ?: return Result.failure(IllegalStateException("No Bluetooth host is connected."))
        if (!hasPermissionsCached) {
            return Result.failure(SecurityException("Bluetooth permission not granted."))
        }

        lastKeyboardReport = report.copyOf()
        val ok = try {
            hd.sendReport(host, HidDescriptors.REPORT_ID_KEYBOARD, report)
        } catch (e: Exception) {
            return Result.failure(e)
        }

        return if (ok) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("Bluetooth HID keyboard report failed."))
        }
    }

    @SuppressLint("MissingPermission")
    fun sendMouseReport(report: ByteArray) {
        val hd = hidDevice ?: return
        val host = connectedDevice ?: return
        if (!hasPermissionsCached) return

        try {
            hd.sendReport(host, HidDescriptors.REPORT_ID_MOUSE, report)
        } catch (_: Exception) {
        }
    }

    @SuppressLint("MissingPermission")
    fun sendGamepadReport(report: ByteArray) {
        val hd = hidDevice ?: return
        val host = connectedDevice ?: return
        if (!hasPermissionsCached) return

        if (report.size == lastGamepadReport.size) {
            report.copyInto(lastGamepadReport)
        }
        try {
            hd.sendReport(host, HidDescriptors.REPORT_ID_GAMEPAD, report)
        } catch (_: Exception) {
        }
    }

    fun release() {
        stop()
        if (receiverRegistered) {
            receiverRegistered = false
            try {
                appContext.unregisterReceiver(bluetoothReceiver)
            } catch (_: Exception) {
            }
        }
        callbackExecutor.shutdownNow()
    }

    @SuppressLint("MissingPermission")
    private fun getPairedDevicesList(adapter: BluetoothAdapter): List<PairedHostDevice> {
        val connectedAddr = connectedDevice?.address
        val bonded = try {
            adapter.bondedDevices ?: emptySet()
        } catch (_: SecurityException) {
            emptySet()
        }

        return bonded.map { device ->
            val name = try {
                device.name?.takeIf { it.isNotBlank() } ?: device.address
            } catch (_: SecurityException) {
                device.address
            }
            PairedHostDevice(
                name = name,
                address = device.address,
                isConnected = device.address == connectedAddr
            )
        }.sortedWith(
            compareByDescending<PairedHostDevice> { it.isConnected }
                .thenBy { it.name.lowercase() }
        )
    }
}
