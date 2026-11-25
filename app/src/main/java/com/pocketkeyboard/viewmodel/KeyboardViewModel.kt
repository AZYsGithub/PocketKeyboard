package com.pocketkeyboard.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketkeyboard.hid.GadgetEnvironment
import com.pocketkeyboard.hid.HidCharMapper
import com.pocketkeyboard.hid.HidKeyMap
import com.pocketkeyboard.hid.HidReportSender
import com.pocketkeyboard.hid.UsbGadgetManager
import com.pocketkeyboard.root.RootShell
import com.pocketkeyboard.ui.components.KeyDef
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class ConnectionStatus {
    DISCONNECTED, CONNECTING, CONNECTED, ERROR
}

class KeyboardViewModel : ViewModel() {

    // ── State flows ──

    private val _rootAvailable = MutableStateFlow(false)
    val rootAvailable: StateFlow<Boolean> = _rootAvailable.asStateFlow()

    private val _gadgetEnvironment = MutableStateFlow<GadgetEnvironment?>(null)
    val gadgetEnvironment: StateFlow<GadgetEnvironment?> = _gadgetEnvironment.asStateFlow()

    private val _hidArmed = MutableStateFlow(false)
    val hidArmed: StateFlow<Boolean> = _hidArmed.asStateFlow()

    private val _mouseAvailable = MutableStateFlow(false)
    val mouseAvailable: StateFlow<Boolean> = _mouseAvailable.asStateFlow()

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

    private val _quickTypeText = MutableStateFlow("")
    val quickTypeText: StateFlow<String> = _quickTypeText.asStateFlow()

    // Current modifier bitmask for HID reports
    private var currentModifiers: Int = 0
    // Current mouse button bitmask (bit0=Left, bit1=Right, bit2=Middle)
    private var currentMouseButtons: Int = 0

    init {
        refreshEnvironment()
    }

    // ── Root & USB Gadget probe ──

    fun refreshEnvironment() {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            val hasRoot = RootShell.isRootAvailable()
            _rootAvailable.value = hasRoot
            if (hasRoot) {
                _gadgetEnvironment.value = UsbGadgetManager.detectEnvironment()
            } else {
                _gadgetEnvironment.value = null
                _errorMessage.value = "Root access is required. Please grant su permission."
            }
            _isLoading.value = false
        }
    }

    // ── HID toggle ──

    fun toggleHid(enable: Boolean) {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null

            if (enable) {
                armHid()
            } else {
                disarmHid()
            }

            _isLoading.value = false
        }
    }

    private suspend fun armHid() {
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
        val senderResult = HidReportSender.open(devices.keyboardPath, devices.mousePath)
        if (senderResult.isFailure) {
            _errorMessage.value =
                "Failed to open HID device (${devices.keyboardPath}): ${senderResult.exceptionOrNull()?.message}"
            UsbGadgetManager.disable()
            _connectionStatus.value = ConnectionStatus.ERROR
            return
        }

        _gadgetEnvironment.value = UsbGadgetManager.detectEnvironment()
        _mouseAvailable.value = devices.mousePath != null
        _hidArmed.value = true
        _connectionStatus.value = ConnectionStatus.CONNECTED
    }

    private suspend fun disarmHid() {
        HidReportSender.close()
        UsbGadgetManager.disable()

        _hidArmed.value = false
        _mouseAvailable.value = false
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
        _gadgetEnvironment.value = if (_rootAvailable.value) {
            UsbGadgetManager.detectEnvironment()
        } else null

        // Reset modifier and mouse button state
        currentModifiers = 0
        currentMouseButtons = 0
        _shiftActive.value = false
        _capsLockActive.value = false
        _activeModifiers.value = emptySet()
    }

    // ── Full Keyboard key handling ──

    fun setVerticalKeyboardTrackpad(enabled: Boolean) {
        _verticalKeyboardTrackpad.value = enabled
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
     * Backspace + character keystrokes over USB HID in real time.
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

    // ── Cleanup ──

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch {
            if (_hidArmed.value) {
                disarmHid()
            }
        }
    }
}
