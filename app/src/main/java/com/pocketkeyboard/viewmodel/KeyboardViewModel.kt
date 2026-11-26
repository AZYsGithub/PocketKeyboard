package com.pocketkeyboard.viewmodel

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pocketkeyboard.hid.BluetoothGamepadBridgeServer
import com.pocketkeyboard.hid.BluetoothHidManager
import com.pocketkeyboard.hid.BluetoothHidSnapshot
import com.pocketkeyboard.hid.BluetoothHidState
import com.pocketkeyboard.hid.GadgetEnvironment
import com.pocketkeyboard.hid.HidCharMapper
import com.pocketkeyboard.hid.HidGamepadMap
import com.pocketkeyboard.hid.HidKeyMap
import com.pocketkeyboard.hid.HidReportSender
import com.pocketkeyboard.hid.UsbGadgetManager
import com.pocketkeyboard.root.RootShell
import com.pocketkeyboard.ui.components.KeyDef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class ConnectionStatus {
    DISCONNECTED, CONNECTING, CONNECTED, ERROR
}

enum class TransportMode {
    BLUETOOTH,
    USB_ROOT
}

enum class ControllerStyle {
    XBOX,
    PS5
}

class KeyboardViewModel(application: Application) : AndroidViewModel(application) {

    // ── Transport & Bluetooth state flows ──

    private val _transportMode = MutableStateFlow(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            TransportMode.BLUETOOTH
        } else {
            TransportMode.USB_ROOT
        }
    )
    val transportMode: StateFlow<TransportMode> = _transportMode.asStateFlow()

    private val _bluetoothSnapshot = MutableStateFlow(
        BluetoothHidSnapshot(
            state = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                BluetoothHidState.IDLE
            } else {
                BluetoothHidState.UNSUPPORTED
            },
            errorMessage = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                "Bluetooth HID Device mode requires Android 9 (API 28) or higher."
            } else {
                null
            }
        )
    )
    val bluetoothSnapshot: StateFlow<BluetoothHidSnapshot> = _bluetoothSnapshot.asStateFlow()

    // ── Root / USB Gadget state flows ──

    private val _rootAvailable = MutableStateFlow(false)
    val rootAvailable: StateFlow<Boolean> = _rootAvailable.asStateFlow()

    private val _gadgetEnvironment = MutableStateFlow<GadgetEnvironment?>(null)
    val gadgetEnvironment: StateFlow<GadgetEnvironment?> = _gadgetEnvironment.asStateFlow()

    // ── Shared active HID & UI state flows ──

    private val _hidArmed = MutableStateFlow(false)
    val hidArmed: StateFlow<Boolean> = _hidArmed.asStateFlow()

    private val _mouseAvailable = MutableStateFlow(false)
    val mouseAvailable: StateFlow<Boolean> = _mouseAvailable.asStateFlow()

    private val _gamepadAvailable = MutableStateFlow(false)
    val gamepadAvailable: StateFlow<Boolean> = _gamepadAvailable.asStateFlow()

    private val _bluetoothBridgeHost = MutableStateFlow<String?>(null)
    val bluetoothBridgeHost: StateFlow<String?> = _bluetoothBridgeHost.asStateFlow()

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _shiftActive = MutableStateFlow(false)
    val shiftActive: StateFlow<Boolean> = _shiftActive.asStateFlow()

    private val _capsLockActive = MutableStateFlow(false)
    val capsLockActive: StateFlow<Boolean> = _capsLockActive.asStateFlow()

    private val _activeModifiers = MutableStateFlow<Set<Int>>(emptySet())
    val activeModifiers: StateFlow<Set<Int>> = _activeModifiers.asStateFlow()

    // false = Toggle (press to lock/unlock), true = Hold (press to hold, release to release)
    private val _holdModifiersMode = MutableStateFlow(false)
    val holdModifiersMode: StateFlow<Boolean> = _holdModifiersMode.asStateFlow()

    // Shows a trackpad below Full Keyboard when in portrait orientation
    private val _verticalKeyboardTrackpad = MutableStateFlow(true)
    val verticalKeyboardTrackpad: StateFlow<Boolean> = _verticalKeyboardTrackpad.asStateFlow()

    // Trackpad pointer sensitivity multiplier (0.5x - 2.5x)
    private val _trackpadSensitivity = MutableStateFlow(1.25f)
    val trackpadSensitivity: StateFlow<Float> = _trackpadSensitivity.asStateFlow()

    // Virtual controller visual style (Xbox A/B/X/Y default for PC, or PS5 DualSense)
    private val _controllerStyle = MutableStateFlow(ControllerStyle.XBOX)
    val controllerStyle: StateFlow<ControllerStyle> = _controllerStyle.asStateFlow()

    private val _quickTypeText = MutableStateFlow("")
    val quickTypeText: StateFlow<String> = _quickTypeText.asStateFlow()

    // Current modifier bitmask for HID reports
    private var currentModifiers: Int = 0
    // Current mouse button bitmask (bit0=Left, bit1=Right, bit2=Middle)
    private var currentMouseButtons: Int = 0

    // Current gamepad state protected by gamepadLock
    private val gamepadLock = Any()
    private var gamepadButtons: Int = 0
    private var gamepadHat: Int = HidGamepadMap.HAT_NEUTRAL
    private var gamepadLx: Int = HidGamepadMap.AXIS_CENTER
    private var gamepadLy: Int = HidGamepadMap.AXIS_CENTER
    private var gamepadRx: Int = HidGamepadMap.AXIS_CENTER
    private var gamepadRy: Int = HidGamepadMap.AXIS_CENTER
    private var gamepadL2: Int = 0
    private var gamepadR2: Int = 0

    @Volatile
    private var btHostConnected: Boolean = false

    @Volatile
    private var btBridgeConnected: Boolean = false

    private var btSenderOpen: Boolean = false
    private val btSessionMutex = Mutex()

    private var bluetoothHidManager: BluetoothHidManager? = null
    private var bluetoothGamepadBridge: BluetoothGamepadBridgeServer? = null

    init {
        val bridge = BluetoothGamepadBridgeServer(
            context = application,
            onConnectionChanged = { connected, hostName ->
                btBridgeConnected = connected
                _bluetoothBridgeHost.value = if (connected) (hostName ?: "Windows PC") else null
                viewModelScope.launch {
                    syncBluetoothSession()
                }
            }
        )
        bluetoothGamepadBridge = bridge

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val manager = BluetoothHidManager(
                context = application,
                onHostConnected = { _ ->
                    btHostConnected = true
                    viewModelScope.launch {
                        syncBluetoothSession()
                    }
                },
                onHostDisconnected = {
                    btHostConnected = false
                    viewModelScope.launch {
                        syncBluetoothSession()
                    }
                },
                onHostCapsLockChanged = { capsOn ->
                    if (_transportMode.value == TransportMode.BLUETOOTH) {
                        _capsLockActive.value = capsOn
                    }
                }
            )
            bluetoothHidManager = manager
            viewModelScope.launch {
                manager.snapshot.collect { snap ->
                    _bluetoothSnapshot.value = snap
                    if (_transportMode.value == TransportMode.BLUETOOTH) {
                        if (snap.state !in setOf(
                                BluetoothHidState.NO_ADAPTER,
                                BluetoothHidState.PERMISSION_REQUIRED,
                                BluetoothHidState.BLUETOOTH_OFF,
                                BluetoothHidState.UNSUPPORTED
                            )
                        ) {
                            bluetoothGamepadBridge?.start()
                        } else if (
                            snap.state == BluetoothHidState.BLUETOOTH_OFF ||
                            snap.state == BluetoothHidState.PERMISSION_REQUIRED
                        ) {
                            bluetoothGamepadBridge?.stop()
                        }
                        _errorMessage.value = snap.errorMessage
                        _connectionStatus.value = when (snap.state) {
                            BluetoothHidState.CONNECTED -> ConnectionStatus.CONNECTED
                            BluetoothHidState.CONNECTING,
                            BluetoothHidState.REGISTERING -> ConnectionStatus.CONNECTING
                            BluetoothHidState.ERROR -> ConnectionStatus.ERROR
                            else -> ConnectionStatus.DISCONNECTED
                        }
                    }
                }
            }
        }

        if (_transportMode.value == TransportMode.BLUETOOTH) {
            refreshBluetoothState()
        } else {
            refreshUsbEnvironment()
        }
    }

    private suspend fun syncBluetoothSession() = btSessionMutex.withLock {
        if (_transportMode.value != TransportMode.BLUETOOTH) return@withLock

        val hidActive = btHostConnected
        val bridgeActive = btBridgeConnected

        if (hidActive || bridgeActive) {
            if (!btSenderOpen) {
                val openResult = HidReportSender.openBluetooth(
                    sendKbd = { report ->
                        bluetoothHidManager?.sendKeyboardReport(report)
                            ?: Result.failure(IllegalStateException("Bluetooth HID service is not connected."))
                    },
                    sendMouse = { report ->
                        bluetoothHidManager?.sendMouseReport(report)
                    },
                    sendGamepad = { report ->
                        bluetoothHidManager?.sendGamepadReport(report)
                        bluetoothGamepadBridge?.sendGamepadReport(report)
                    }
                )
                if (openResult.isSuccess) {
                    btSenderOpen = true
                } else {
                    _hidArmed.value = false
                    _mouseAvailable.value = false
                    _gamepadAvailable.value = false
                    _connectionStatus.value = ConnectionStatus.ERROR
                    _errorMessage.value = openResult.exceptionOrNull()?.message
                    return@withLock
                }
            }

            _hidArmed.value = hidActive
            _mouseAvailable.value = hidActive
            _gamepadAvailable.value = true
            if (hidActive) {
                _connectionStatus.value = ConnectionStatus.CONNECTED
                _errorMessage.value = null
            }
        } else {
            if (btSenderOpen) {
                HidReportSender.close()
                btSenderOpen = false
            }
            _hidArmed.value = false
            _mouseAvailable.value = false
            _gamepadAvailable.value = false
            _connectionStatus.value = ConnectionStatus.DISCONNECTED
            resetInputState()
        }
    }

    // ── Transport switching ──

    fun selectTransportMode(mode: TransportMode) {
        if (_transportMode.value == mode) return

        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null

            when (_transportMode.value) {
                TransportMode.USB_ROOT -> {
                    if (_hidArmed.value) {
                        disarmUsbHid()
                    }
                }

                TransportMode.BLUETOOTH -> {
                    btSessionMutex.withLock {
                        btHostConnected = false
                        btBridgeConnected = false
                        _bluetoothBridgeHost.value = null
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            bluetoothHidManager?.stop()
                        }
                        bluetoothGamepadBridge?.stop()
                        HidReportSender.close()
                        btSenderOpen = false
                        _hidArmed.value = false
                        _mouseAvailable.value = false
                        _gamepadAvailable.value = false
                        _connectionStatus.value = ConnectionStatus.DISCONNECTED
                        resetInputState()
                    }
                }
            }

            _transportMode.value = mode

            if (mode == TransportMode.BLUETOOTH) {
                refreshBluetoothState()
                val snap = _bluetoothSnapshot.value
                _errorMessage.value = snap.errorMessage
                _connectionStatus.value = ConnectionStatus.DISCONNECTED
            } else {
                refreshUsbEnvironmentInternal()
            }

            _isLoading.value = false
        }
    }

    // ── Bluetooth HID actions (No Root) ──

    fun toggleBluetoothHid(enable: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            _errorMessage.value = "Bluetooth HID Device mode requires Android 9 (API 28) or higher."
            return
        }
        if (enable) {
            bluetoothHidManager?.start()
            bluetoothGamepadBridge?.start()
        } else {
            btHostConnected = false
            bluetoothHidManager?.stop()
            viewModelScope.launch {
                syncBluetoothSession()
            }
        }
    }

    fun connectBluetoothHost(address: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        bluetoothGamepadBridge?.start()
        bluetoothHidManager?.connectToAddress(address)
    }

    fun disconnectBluetoothHost() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        bluetoothHidManager?.disconnectHost()
    }

    fun refreshBluetoothState(error: String? = null) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        bluetoothHidManager?.refreshState(error = error)
        if (_transportMode.value == TransportMode.BLUETOOTH) {
            val state = bluetoothHidManager?.snapshot?.value?.state
            if (state !in setOf(
                    BluetoothHidState.NO_ADAPTER,
                    BluetoothHidState.PERMISSION_REQUIRED,
                    BluetoothHidState.BLUETOOTH_OFF,
                    BluetoothHidState.UNSUPPORTED
                )
            ) {
                bluetoothGamepadBridge?.start()
            }
        }
    }

    fun onBluetoothPermissionsResult(allGranted: Boolean, autoStart: Boolean = false) {
        if (allGranted) {
            refreshBluetoothState()
            _errorMessage.value = null
            if (_transportMode.value == TransportMode.BLUETOOTH) {
                bluetoothGamepadBridge?.start()
            }
            if (autoStart) {
                toggleBluetoothHid(true)
            }
        } else {
            bluetoothGamepadBridge?.stop()
            refreshBluetoothState(
                error = "Bluetooth permission is required to connect as a wireless keyboard & trackpad."
            )
        }
    }

    fun ensureBluetoothHidRegisteredForDiscovery() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val state = _bluetoothSnapshot.value.state
        if (state == BluetoothHidState.IDLE || state == BluetoothHidState.ERROR) {
            bluetoothHidManager?.start()
        }
    }

    // ── Root & USB Gadget probe / toggle ──

    fun refreshEnvironment() = refreshUsbEnvironment()

    fun refreshUsbEnvironment() {
        viewModelScope.launch {
            _isLoading.value = true
            refreshUsbEnvironmentInternal()
            _isLoading.value = false
        }
    }

    private suspend fun refreshUsbEnvironmentInternal() {
        _errorMessage.value = null
        val hasRoot = RootShell.isRootAvailable()
        _rootAvailable.value = hasRoot
        if (hasRoot) {
            _gadgetEnvironment.value = UsbGadgetManager.detectEnvironment()
        } else {
            _gadgetEnvironment.value = null
            _errorMessage.value = "Root access is required for USB HID mode. Switch to Bluetooth for no-root use, or grant su permission."
        }
    }

    fun toggleHid(enable: Boolean) = toggleUsbHid(enable)

    fun toggleUsbHid(enable: Boolean) {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null

            if (enable) {
                armUsbHid()
            } else {
                disarmUsbHid()
            }

            _isLoading.value = false
        }
    }

    private suspend fun armUsbHid() {
        _connectionStatus.value = ConnectionStatus.CONNECTING

        val gadgetResult = UsbGadgetManager.enable()
        if (gadgetResult.isFailure) {
            _errorMessage.value =
                "Failed to enable USB HID: ${gadgetResult.exceptionOrNull()?.message}"
            _connectionStatus.value = ConnectionStatus.ERROR
            _gadgetEnvironment.value = UsbGadgetManager.detectEnvironment()
            return
        }

        val devices = gadgetResult.getOrThrow()
        val senderResult = HidReportSender.open(
            keyboardPath = devices.keyboardPath,
            mousePath = devices.mousePath,
            gamepadPath = devices.gamepadPath
        )
        if (senderResult.isFailure) {
            _errorMessage.value =
                "Failed to open HID device (${devices.keyboardPath}): ${senderResult.exceptionOrNull()?.message}"
            UsbGadgetManager.disable()
            _connectionStatus.value = ConnectionStatus.ERROR
            return
        }

        _gadgetEnvironment.value = UsbGadgetManager.detectEnvironment()
        _mouseAvailable.value = devices.mousePath != null
        _gamepadAvailable.value = devices.gamepadPath != null
        _hidArmed.value = true
        _connectionStatus.value = ConnectionStatus.CONNECTED
    }

    private suspend fun disarmUsbHid() {
        HidReportSender.close()
        UsbGadgetManager.disable()

        _hidArmed.value = false
        _mouseAvailable.value = false
        _gamepadAvailable.value = false
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
        _gadgetEnvironment.value = if (_rootAvailable.value) {
            UsbGadgetManager.detectEnvironment()
        } else null

        resetInputState()
    }

    private fun resetInputState() {
        currentModifiers = 0
        currentMouseButtons = 0
        _shiftActive.value = false
        _capsLockActive.value = false
        _activeModifiers.value = emptySet()
        synchronized(gamepadLock) {
            gamepadButtons = 0
            gamepadHat = HidGamepadMap.HAT_NEUTRAL
            gamepadLx = HidGamepadMap.AXIS_CENTER
            gamepadLy = HidGamepadMap.AXIS_CENTER
            gamepadRx = HidGamepadMap.AXIS_CENTER
            gamepadRy = HidGamepadMap.AXIS_CENTER
            gamepadL2 = 0
            gamepadR2 = 0
        }
    }

    // ── Full Keyboard key handling ──

    fun setVerticalKeyboardTrackpad(enabled: Boolean) {
        _verticalKeyboardTrackpad.value = enabled
    }

    fun setTrackpadSensitivity(value: Float) {
        _trackpadSensitivity.value = value.coerceIn(0.5f, 2.5f)
    }

    fun setControllerStyle(style: ControllerStyle) {
        _controllerStyle.value = style
    }

    fun setHoldModifiersMode(hold: Boolean) {
        _holdModifiersMode.value = hold
        if (currentModifiers != 0) {
            currentModifiers = 0
            _shiftActive.value = false
            _activeModifiers.value = emptySet()
            if (_hidArmed.value) {
                viewModelScope.launch {
                    HidReportSender.sendModifierOnly(0)
                }
            }
        }
    }

    fun onKeyDown(key: KeyDef) {
        if (!_hidArmed.value) return

        viewModelScope.launch {
            when {
                key.isToggle -> {
                    if (key.hidKeyCode == HidKeyMap.KEY_CAPSLOCK) {
                        _capsLockActive.value = !_capsLockActive.value
                        HidReportSender.sendKeyDown(currentModifiers, HidKeyMap.KEY_CAPSLOCK)
                    }
                }

                key.isModifier -> {
                    val mods = _activeModifiers.value.toMutableSet()
                    if (_holdModifiersMode.value) {
                        // Hold mode: press activates the modifier
                        mods.add(key.modifier)
                        currentModifiers = currentModifiers or key.modifier
                    } else {
                        // Toggle mode: press flips the modifier state
                        if (key.modifier in mods) {
                            mods.remove(key.modifier)
                            currentModifiers = currentModifiers and key.modifier.inv()
                        } else {
                            mods.add(key.modifier)
                            currentModifiers = currentModifiers or key.modifier
                        }
                    }
                    _activeModifiers.value = mods
                    _shiftActive.value = (currentModifiers and
                        (HidKeyMap.MOD_LSHIFT or HidKeyMap.MOD_RSHIFT)) != 0
                    HidReportSender.sendModifierOnly(currentModifiers)
                }

                else -> {
                    HidReportSender.sendKeyDown(currentModifiers, key.hidKeyCode)
                }
            }
        }
    }

    fun onKeyUp(key: KeyDef) {
        if (!_hidArmed.value) return

        viewModelScope.launch {
            when {
                key.isToggle -> HidReportSender.sendKeyUp(currentModifiers)
                key.isModifier -> {
                    if (_holdModifiersMode.value) {
                        // Hold mode: release deactivates the modifier
                        val mods = _activeModifiers.value.toMutableSet()
                        mods.remove(key.modifier)
                        currentModifiers = currentModifiers and key.modifier.inv()
                        _activeModifiers.value = mods
                        _shiftActive.value = (currentModifiers and
                            (HidKeyMap.MOD_LSHIFT or HidKeyMap.MOD_RSHIFT)) != 0
                        HidReportSender.sendModifierOnly(currentModifiers)
                    }
                }
                else -> HidReportSender.sendKeyUp(currentModifiers)
            }
        }
    }

    // ── Quick Type (System Virtual Keyboard) handling ──

    /**
     * Diffs old text vs new text from the IME (e.g. Gboard) and streams
     * Backspace + character keystrokes over HID in real time.
     */
    fun onQuickTypeTextChanged(newText: String) {
        val oldText = _quickTypeText.value
        _quickTypeText.value = newText

        if (!_hidArmed.value) return

        val minLen = minOf(oldText.length, newText.length)
        var commonPrefix = 0
        while (commonPrefix < minLen && oldText[commonPrefix] == newText[commonPrefix]) {
            commonPrefix++
        }

        val backspaces = oldText.length - commonPrefix
        val addedText = newText.substring(commonPrefix)

        if (backspaces == 0 && addedText.isEmpty()) return

        viewModelScope.launch {
            repeat(backspaces) {
                HidReportSender.sendTap(HidKeyMap.MOD_NONE, HidKeyMap.KEY_BACKSPACE)
            }
            for (ch in addedText) {
                val mapped = HidCharMapper.mapChar(ch) ?: continue
                HidReportSender.sendTap(mapped.first, mapped.second)
            }
        }
    }

    fun sendQuickKey(modifiers: Int, keyCode: Int) {
        if (!_hidArmed.value) return
        viewModelScope.launch {
            HidReportSender.sendTap(modifiers, keyCode)
        }
    }

    fun clearQuickTypeBox() {
        _quickTypeText.value = ""
    }

    // ── Trackpad / Mouse handling ──

    fun onMouseMove(dx: Int, dy: Int) {
        if (!_hidArmed.value || !_mouseAvailable.value) return
        HidReportSender.sendMouseReportAsync(currentMouseButtons, dx, dy, 0)
    }

    fun onMouseScroll(wheel: Int) {
        if (!_hidArmed.value || !_mouseAvailable.value) return
        HidReportSender.sendMouseReportAsync(currentMouseButtons, 0, 0, wheel)
    }

    fun onMouseButtonDown(buttonMask: Int) {
        if (!_hidArmed.value || !_mouseAvailable.value) return
        currentMouseButtons = currentMouseButtons or buttonMask
        HidReportSender.sendMouseReportAsync(currentMouseButtons, 0, 0, 0)
    }

    fun onMouseButtonUp(buttonMask: Int) {
        if (!_hidArmed.value || !_mouseAvailable.value) return
        currentMouseButtons = currentMouseButtons and buttonMask.inv()
        HidReportSender.sendMouseReportAsync(currentMouseButtons, 0, 0, 0)
    }

    fun onMouseClick(buttonMask: Int) {
        if (!_hidArmed.value || !_mouseAvailable.value) return
        HidReportSender.sendMouseClickAsync(buttonMask, currentMouseButtons)
    }

    // ── Xbox / PS5 Gamepad handling ──

    fun setGamepadButton(buttonMask: Int, pressed: Boolean) {
        if (!_gamepadAvailable.value) return
        synchronized(gamepadLock) {
            gamepadButtons = if (pressed) {
                gamepadButtons or buttonMask
            } else {
                gamepadButtons and buttonMask.inv()
            }
            dispatchGamepadStateLocked()
        }
    }

    fun setGamepadDpad(hatValue: Int) {
        if (!_gamepadAvailable.value) return
        synchronized(gamepadLock) {
            gamepadHat = hatValue
            dispatchGamepadStateLocked()
        }
    }

    fun setGamepadLeftStick(x: Int, y: Int) {
        if (!_gamepadAvailable.value) return
        synchronized(gamepadLock) {
            gamepadLx = x.coerceIn(0, 255)
            gamepadLy = y.coerceIn(0, 255)
            dispatchGamepadStateLocked()
        }
    }

    fun setGamepadRightStick(x: Int, y: Int) {
        if (!_gamepadAvailable.value) return
        synchronized(gamepadLock) {
            gamepadRx = x.coerceIn(0, 255)
            gamepadRy = y.coerceIn(0, 255)
            dispatchGamepadStateLocked()
        }
    }

    fun setGamepadTrigger(isRight: Boolean, value: Int) {
        if (!_gamepadAvailable.value) return
        val clamped = value.coerceIn(0, 255)
        synchronized(gamepadLock) {
            val digitalBit = if (isRight) HidGamepadMap.BTN_R2 else HidGamepadMap.BTN_L2
            if (isRight) {
                gamepadR2 = clamped
            } else {
                gamepadL2 = clamped
            }
            gamepadButtons = if (clamped >= 30) {
                gamepadButtons or digitalBit
            } else {
                gamepadButtons and digitalBit.inv()
            }
            dispatchGamepadStateLocked()
        }
    }

    fun resetGamepadState() {
        synchronized(gamepadLock) {
            gamepadButtons = 0
            gamepadHat = HidGamepadMap.HAT_NEUTRAL
            gamepadLx = HidGamepadMap.AXIS_CENTER
            gamepadLy = HidGamepadMap.AXIS_CENTER
            gamepadRx = HidGamepadMap.AXIS_CENTER
            gamepadRy = HidGamepadMap.AXIS_CENTER
            gamepadL2 = 0
            gamepadR2 = 0
        }
        if (_gamepadAvailable.value) {
            HidReportSender.resetGamepadAsync()
        }
    }

    private fun dispatchGamepadStateLocked() {
        HidReportSender.sendGamepadStateAsync(
            buttonsMask = gamepadButtons,
            hat = gamepadHat,
            lx = gamepadLx,
            ly = gamepadLy,
            rx = gamepadRx,
            ry = gamepadRy,
            l2 = gamepadL2,
            r2 = gamepadR2
        )
    }

    // ── Cleanup ──

    override fun onCleared() {
        super.onCleared()
        bluetoothGamepadBridge?.release()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            bluetoothHidManager?.release()
        }
        val shouldDisableUsb = _transportMode.value == TransportMode.USB_ROOT && _hidArmed.value
        CoroutineScope(Dispatchers.IO).launch {
            HidReportSender.close()
            if (shouldDisableUsb) {
                UsbGadgetManager.disable()
            }
        }
    }
}
