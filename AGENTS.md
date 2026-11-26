# AGENTS.md — PocketKeyboard Developer & Agent Guide

This document provides technical context, build rules, architecture details, and invariants for AI agents and contributors working on **PocketKeyboard** (`com.pocketkeyboard`).

---

## 1. Project Identity & Build Constraints

- **Package / Application ID:** `com.pocketkeyboard`
- **Gradle Root Name:** `PocketKeyboard` (`settings.gradle.kts`)
- **Language / UI:** Kotlin `1.9.22`, Jetpack Compose (`compose-bom:2024.02.00`), Material 3 (`1.5.8` Compose compiler)
- **Android SDK:** `compileSdk = 34`, `minSdk = 26`, `targetSdk = 34`
- **Required JDK:** **JDK 17** (`sourceCompatibility` / `targetCompatibility = 17`).
  - **CRITICAL:** Android Gradle Plugin `8.3.0` and Kotlin `1.9.22` **fail** when executed with JDK 21+ or JDK 25. Always run Gradle with `JAVA_HOME` pointing to JDK 17.
- **Kotlin Compiler Strategy:** `kotlin.compiler.execution.strategy=in-process` is set in `gradle.properties` to prevent silent Kotlin daemon IPC failures on Windows.
- **Repository Hygiene:** Keep `build/`, `app/build/`, `.gradle/`, and `local.properties` out of git commits (already listed in `.gitignore`). Do not hardcode machine-specific paths in `gradle.properties` or `gradlew.bat`.

### Build Commands (Windows PowerShell)
```powershell
$env:JAVA_HOME = "<path-to-jdk-17>"
# Ensure local.properties has: sdk.dir=<path-to-android-sdk>
.\gradlew.bat assembleDebug
```
Output APK: `app/build/outputs/apk/debug/app-debug.apk`.

---

## 2. Architecture & Module Responsibilities

### HID Descriptors & Transport Layer (`com.pocketkeyboard.hid`)
- **`HidDescriptors.kt`**:
  - `USB_KEYBOARD_REPORT_DESCRIPTOR` (63 bytes), `USB_MOUSE_REPORT_DESCRIPTOR` (52 bytes), and `USB_GAMEPAD_REPORT_DESCRIPTOR` (82 bytes) for separate USB ConfigFS interfaces.
  - `BLUETOOTH_COMBO_REPORT_DESCRIPTOR` (203 bytes) combining Keyboard (`REPORT_ID_KEYBOARD = 1`), Mouse (`REPORT_ID_MOUSE = 2`), and Gamepad (`REPORT_ID_GAMEPAD = 3`) into a single Bluetooth HID SDP descriptor.
  - `HidGamepadMap`: DualSense-ordered 16-button bitmasks and 8-way Hat Switch constants.
  - **Descriptor Invariant:** Unsigned 8-bit axis maxima (`255`) MUST use 2-byte `0x26, 0xFF, 0x00` (never `0x25, 0xFF`, which is signed `-1`), and HID `Unit` / `Physical Maximum` MUST be reset after the Hat Switch.
- **`BluetoothHidManager.kt`** (`@RequiresApi(Build.VERSION_CODES.P)`):
  - Uses `(context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter` (never deprecated `BluetoothAdapter.getDefaultAdapter()`).
  - Registers with low-latency `BluetoothHidDeviceAppQosSettings` (`SERVICE_BEST_EFFORT`, `11250`us latency) and automatically falls back to `null` QoS if an OEM stack rejects it synchronously or asynchronously.
  - Caches permission status in `@Volatile private var hasPermissionsCached` so hot `sendKeyboardReport` / `sendMouseReport` / `sendGamepadReport` calls perform zero Binder IPC.
  - Registers a `BroadcastReceiver` for `ACTION_STATE_CHANGED` and `ACTION_BOND_STATE_CHANGED` using `ContextCompat.registerReceiver(..., ContextCompat.RECEIVER_EXPORTED)` — **required** for Android 14 (`targetSdk = 34`) safety.
  - Implements `BluetoothHidDevice.Callback`: handles `onAppStatusChanged`, `onConnectionStateChanged`, `onGetReport` (returning last keyboard, empty mouse, or last gamepad report), `onSetReport` / `onInterruptData` (acknowledges and syncs Caps Lock LED bit `0x02`).
  - **Report ID Invariant:** `BluetoothHidDevice.sendReport(host, id, data)` prepends the Report ID automatically in Android's Bluetooth stack. Always pass raw **8-byte** keyboard (`REPORT_ID_KEYBOARD = 1`), **4-byte** mouse (`REPORT_ID_MOUSE = 2`), and **9-byte** gamepad (`REPORT_ID_GAMEPAD = 3`) arrays; never prepend the Report ID byte manually.
  - `BluetoothPermissions` handles API 31+ runtime permissions (`BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE`); legacy `BLUETOOTH` / `BLUETOOTH_ADMIN` are capped with `maxSdkVersion="30"` in `AndroidManifest.xml`.
- **`UsbGadgetManager.kt`**:
  - `detectEnvironment()` probes in a single `su` shell call: `/config/usb_gadget`, `/sys/kernel/config/usb_gadget` (mounting `configfs` if needed), pre-existing `/dev/hidg*` nodes (`EXISTING_HIDG`), legacy `/sys/class/android_usb/android0` (`ANDROID_USB`), and `/dev/gadget` (`hasGadgetFs`).
  - `enable()` saves `getprop sys.usb.config`, sets `sys.usb.config none`, configures `hid.usb0` (keyboard) and best-effort `hid.usb1` (mouse) + `hid.usb2` (gamepad), and falls back from standalone `pocketkeyboard` gadget to `g1` (`configs/b.1` or `c.1`) if `mkdir`/`ln -s` returns `EPERM`.
  - **ConfigFS Symlink Invariant:** Use `ln -s <src> <dst>/hid.usb0` (no `-f`, no trailing slash).
  - `disable()` unbinds UDC, removes `hid.usb0`/`hid.usb1`/`hid.usb2` symlinks/functions (without deleting `g1`), and restores `sys.usb.config`.
- **`HidReportSender.kt`**:
  - Abstracts transport output via `HidOutputBackend`:
    - `UsbHidOutputBackend` (`open()`): persistent `su` `DataOutputStream` for `/dev/hidg0` (`printf`), plus shared `UsbHidNodeWriter` (direct `FileOutputStream` with automatic `su` pipe fallback) for `/dev/hidg1` and `/dev/hidg2` (`4ms` direct / `14ms` `su`).
    - `BluetoothHidOutputBackend` (`openBluetooth()`): delegates to `BluetoothHidManager.sendKeyboardReport` / `sendMouseReport` / `sendGamepadReport` (`11ms` monotonic deadline interval).
  - **Keyboard Invariant:** `sendKeyUp(modifiers)` MUST preserve active `modifiers` in byte `0` so releasing a normal key does not wipe held/toggled modifier keys on the host.
  - **Zero-Allocation Deadline-Paced Workers (`PocketKeyboard-MouseWorker` & `PocketKeyboard-GamepadWorker`):** Both run at `Thread.MAX_PRIORITY`, reuse pre-allocated byte buffers, and pace consecutive updates against a monotonic `System.nanoTime()` schedule without catch-up bursts.
  - **Click Queue (`pendingClickQueue`):** Instant clicks MUST use `sendMouseClickAsync()`, NOT back-to-back `sendMouseReportAsync(down)` + `sendMouseReportAsync(up)` calls.
- **`HidKeyMap.kt` & `HidCharMapper.kt`**:
  - `HidKeyMap`: USB HID Usage Tables 1.12 scan codes and modifier bitmasks.
  - `HidCharMapper`: Maps printable ASCII + `\n` + `\t` to `(modifier, keyCode)` for US QWERTY.

### Root Execution (`com.pocketkeyboard.root`)
- **`RootShell.kt`**: Executes `su` commands (`isRootAvailable()`, `execute()`, `executeBatch()`) for USB HID mode only. Never assume non-root apps can open `/dev/hidg*` directly—SELinux `untrusted_app` policies block direct `open()` on most Android ROMs even after `chmod 666`.

### State Management (`com.pocketkeyboard.viewmodel`)
- **`KeyboardViewModel.kt`**:
  - Extends `AndroidViewModel` so `BluetoothHidManager` receives the `Application` context.
  - Manages `TransportMode` (`BLUETOOTH` default on Android 9+ / API 28+, `USB_ROOT` on API 26–27 or when selected), `ControllerStyle` (`XBOX` default / `PS5`), `bluetoothSnapshot`, `rootAvailable`, `gadgetEnvironment`, `hidArmed`, `mouseAvailable`, `gamepadAvailable`, `connectionStatus`, `holdModifiersMode`, `verticalKeyboardTrackpad`, `trackpadSensitivity`, `shiftActive`, `capsLockActive`, `activeModifiers`, `quickTypeText`, and gamepad state (`gamepadLock`).
  - Switching `TransportMode` cleanly disarms/closes the active transport before initializing the target transport; do NOT run `su` probes at startup while in `BLUETOOTH` mode.
  - `onCleared()` synchronously releases `BluetoothHidManager` and dispatches `HidReportSender.close()` / `UsbGadgetManager.disable()` on `CoroutineScope(Dispatchers.IO)` (never `viewModelScope`, which is already cancelled).

### UI Layer (`com.pocketkeyboard.ui`)
- **`MainScreen.kt`**: 5-tab Material 3 `TabRow` (`Connect`, `Keyboard`, `Quick`, `Trackpad`, `Gamepad`).
  - Hosts `ActivityResultContracts` launchers for Bluetooth runtime permissions, `ACTION_REQUEST_ENABLE`, and `ACTION_REQUEST_DISCOVERABLE`, plus an `ON_RESUME` lifecycle observer to refresh paired devices after returning from system Bluetooth settings.
  - Automatically hides the system IME (`LocalSoftwareKeyboardController`) when leaving `Quick`.
  - In `Keyboard`, checks `LocalConfiguration.current.orientation`:
    - **Landscape:** `KeyboardLayout(..., compactRows = false)` uses `Modifier.weight(1f)` per row so all 6 rows fill the screen without clipping the bottom row.
    - **Portrait:** `KeyboardLayout(..., compactRows = true)` uses fixed `42.dp` row height, and renders `TrackpadPanel` below when `verticalKeyboardTrackpad` is `true`.
- **`StatusCard.kt`**: Dual-mode **Connect** panel with stable Material 3 components (avoid experimental M3 APIs requiring `@OptIn`), Trackpad Sensitivity slider, and Controller Layout selector (`Xbox` / `PS5`).
- **`TrackpadScreen.kt`**: Raw `awaitPointerEventScope` with `200ms` tap vs double-tap-drag state machine, 2-finger-to-1-finger transition jump guard (`hadMultiTouch`), adaptive acceleration + `roundToInt()`, 2-finger scroll/right-click, and Left/Middle/Right hold buttons.
- **`ControllerScreen.kt`**: Multi-touch Xbox / PS5 DualSense virtual controller (`BoxWithConstraints` landscape/portrait responsive layout, `DisposableEffect` neutral reset on dispose).

### Windows PC Xbox Bridge (`pc-xbox-bridge`)
- **`PocketPadXboxBridge.cpp`**: Standalone native Win32 C++17 bridge using `hid.dll` (`HidP_GetUsages` / `HidP_GetUsageValue`), `setupapi.dll`, and direct `ViGEmBus` IOCTLs (`GUID_DEVINTERFACE_BUSENUM_VIGEM`) to expose PocketKeyboard's Bluetooth/USB HID Gamepad as an Xbox 360 (XInput) controller (`VendorId=0x045E`, `ProductId=0x028E`). Filters out ViGEm virtual devices (`vigem`/`xusb` and `045E:028E` / `054C:05C4`) to avoid loopback, inverts `Y`/`Rz` axes for XInput, and treats `ERROR_NO_MORE_ITEMS` (`259`) as non-fatal. Supports `--test` and `--list`.
- **`AndroidManifest.xml`**:
  - Declares `ACCESS_SUPERUSER`, legacy `BLUETOOTH` / `BLUETOOTH_ADMIN` (`maxSdkVersion="30"`), runtime `BLUETOOTH_CONNECT` / `BLUETOOTH_ADVERTISE`, and optional `android.hardware.bluetooth`.
  - `android:windowSoftInputMode="adjustResize|stateHidden"` and `android:configChanges="orientation|screenSize|screenLayout|keyboardHidden"`.

---

## 3. Guidelines for Future Changes

- **Never block the UI thread** with `RootShell`, `su` I/O, or Bluetooth binder calls on non-worker paths.
- **Avoid fixed heights on landscape keyboard rows**: Landscape phone viewports are ~360dp–411dp tall; any fixed height or unguarded banner above `KeyboardLayout` can push the bottom row off-screen.
- **Guard API 28+ / API 31+ Bluetooth calls** properly because `minSdk = 26` and `targetSdk = 34`.
- **Test both single-tap and double-tap-drag** if modifying `TrackpadPanel` pointer logic, and keep `sendMouseClickAsync` separate from delta coalescing.
