# PocketKeyboard

![preview](./images/preview.png)
<p align="center">
  <b>Dual-Mode HID Virtual Keyboard &amp; Trackpad for Android</b><br>
  <b>No-Root Bluetooth HID</b> + <b>Root USB Gadget HID</b> • Built with Jetpack Compose &amp; Material You (Material 3)
</p>

---

## Overview

**PocketKeyboard** turns your Android smartphone into a standard **Human Interface Device (HID) Keyboard + 3-Button Mouse/Trackpad + Gamepad** with two transport modes and **zero PC companion software required**:

1. **Bluetooth HID (No Root, Default on Android 9+):** Uses Android's built-in `BluetoothHidDevice` profile to connect wirelessly to Windows, macOS, Linux, ChromeOS, Android TVs, tablets, and Steam Deck—no root and no USB cable required.
2. **USB HID (Root):** Uses the Linux kernel's USB gadget driver (`ConfigFS`, `/dev/hidg*`, or legacy `android_usb`) over a USB data cable. Recognized natively by **UEFI/BIOS menus, bootloaders, recovery consoles, Linux, Windows, macOS, and ChromeOS**.

---

## Features

- **Material You Design**: Dynamic color theming that adapts to your wallpaper on Android 12+ (Material 3), with dark/light mode support.
- **5-Page Interface**:
  1. **Connect**: Switch between **Bluetooth (No Root)** and **USB HID (Root)**, manage Bluetooth discoverability and paired hosts, check root/USB gadget diagnostics (`ConfigFS` path, `UDC`, `/dev/hidg*` nodes), and configure input & trackpad sensitivity settings.
  2. **Keyboard**: Complete 6-row physical keyboard (`Esc`, `F1`–`F12`, `Del`, numbers/symbols, QWERTY, `Caps`, `Enter`, `Shift`, `Ctrl`, `Win`, `Alt`, `Space`, Arrow keys).
     - In **landscape**, the keyboard fills the entire screen.
     - In **portrait**, keys keep a natural compact height with an optional **Trackpad** below (controlled by *"Enable trackpad on vertical full keyboard"*).
  3. **Quick**: Uses your Android system virtual keyboard (e.g., Gboard) for fast typing, swipe, and autocorrect—live-streaming keystrokes and deletions over HID, with quick chips (`Enter`, `Tab`, `Esc`, `Backspace`, `Ctrl+C`, `Ctrl+V`).
  4. **Trackpad**: Full-screen touch trackpad with adjustable sensitivity (`0.50x`–`2.50x`), adaptive acceleration, **Left / Middle / Right** physical mouse buttons (supports press-and-hold), 2-finger scroll wheel, 2-finger tap for right-click, 1-finger tap for left-click, and **double-tap & drag** (second tap holds Left Mouse Button until finger release).
  5. **Gamepad (`Xbox` / `PS5` styles)**: Multi-touch virtual controller supporting both **Xbox (`A B X Y`, `LT`/`LB`/`RB`/`RT`, `View`/`Menu`/`Xbox`, `LS`/`RS`)** and **PS5 DualSense (`✕ ○ □ △`, `L2`/`L1`/`R1`/`R2`, `Create`/`Options`/`PS`, `L3`/`R3`)** layouts, analog slide triggers, 8-way split D-Pad, dual thumbsticks, and Touchpad.
     - Works directly in DirectInput testers, Steam Input, Linux, and Android.
     - For **Windows PC games (XInput / Xbox controller)**, run `pc-xbox-bridge/PocketPadXboxBridge.exe` on your Windows PC (requires ViGEmBus) to mirror PocketKeyboard into a real Xbox 360 controller automatically.
- **Modifier Key Mode (Toggle vs Hold)**: Switch between **TOGGLE** (sticky tap-to-lock/unlock) and **HOLD** (momentary press-and-release with multi-touch support) for `Shift`, `Ctrl`, `Alt`, and `Win`.
- **Low-Latency HID Streaming**:
  - Bluetooth registers with low-latency HID QoS (`11.25ms` target latency, falling back cleanly if an OEM stack rejects QoS) and uses cached permission checks to avoid per-frame binder IPC.
  - Trackpad and Gamepad use dedicated `Thread.MAX_PRIORITY`, zero-allocation, monotonic-deadline coalescing workers (`11ms` over Bluetooth, `4ms` direct / `14ms` `su` over USB) so high-frequency touch events never build up lag.

---

## Device & Requirements

### Bluetooth HID Mode (No Root)
| Requirement | Specification |
|---|---|
| **Root Access** | **Not required** (works on stock Android devices). |
| **Android Version** | Android 9.0 (Pie / API 28) or higher with Bluetooth HID Device profile support. |
| **Host Device** | Any OS with Bluetooth HID support (Windows, macOS, Linux, ChromeOS, Android TV, iOS/iPadOS, etc.). |

### USB HID Mode (Root)
| Requirement | Specification |
|---|---|
| **Root Access** | Magisk, KernelSU, or APatch (required to manage USB ConfigFS and write to `/dev/hidg*`). |
| **Android Version** | Android 8.0 (Oreo / API 26) or higher. |
| **Kernel Support** | Linux kernel with USB Gadget ConfigFS and HID function support (`CONFIG_USB_CONFIGFS_F_HID=y`), pre-exposed `/dev/hidg*`, or legacy `android_usb`. |
| **USB Cable** | USB data cable (charge-only cables will not enumerate). |

---

## Build Prerequisites

- **JDK 17** *(Required)* — Android Gradle Plugin 8.3 and Kotlin 1.9.22 require Java 17 (newer JDKs such as Java 21+ or 25 are not supported by AGP 8.3).
- **Android SDK** — `compileSdk = 34`, `minSdk = 26`, `targetSdk = 34`, Build-Tools `34.0.0`.
- **Gradle 8.5** — Included via the Gradle Wrapper (`./gradlew` / `gradlew.bat`).

---

## How to Build

### Option 1: Android Studio (Recommended)

1. Clone or open this project folder in Android Studio.
2. Android Studio will sync Gradle using its bundled JDK 17 (JBR) and generate `local.properties`.
3. Click **Build > Make Project** or run directly on your device.

### Option 2: Command Line (CLI)

1. Set `JAVA_HOME` to JDK 17:
   ```powershell
   # Windows PowerShell
   $env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.x"
   ```
   ```bash
   # Linux / macOS
   export JAVA_HOME=/path/to/jdk-17
   ```
2. Create `local.properties` in the project root pointing to your Android SDK:
   ```properties
   sdk.dir=C:/Users/<Username>/AppData/Local/Android/Sdk
   ```
3. Build the debug APK:
   ```bash
   # Windows
   .\gradlew.bat assembleDebug

   # Linux / macOS
   ./gradlew assembleDebug
   ```
4. Install onto a connected device:
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

---

## Project Architecture

```
com.pocketkeyboard/
├── MainActivity.kt              # Edge-to-edge Compose host activity
├── root/
│   └── RootShell.kt             # Superuser (su) command & batch executor
│   ├── hid/
│   │   ├── HidDescriptors.kt    # Shared USB & composite Bluetooth HID descriptors + HidGamepadMap
│   │   ├── HidKeyMap.kt         # USB HID Usage Tables 1.12 scan codes & modifier masks
│   │   ├── HidCharMapper.kt     # ASCII character to (modifier, keyCode) mapper for Quick Type
│   │   ├── BluetoothHidManager.kt # API 28+ BluetoothHidDevice registration & host connections
│   │   ├── UsbGadgetManager.kt  # ConfigFS (/config/usb_gadget, /sys/kernel/config/usb_gadget, g1 fallback)
│   │   └── HidReportSender.kt   # Pluggable USB/Bluetooth keyboard, coalesced mouse & gamepad sender
│   ├── viewmodel/
│   │   └── KeyboardViewModel.kt # Dual-mode state holder for Bluetooth/USB, modifiers, trackpad & gamepad
│   └── ui/
│       ├── theme/               # Material You dynamic dark/light color schemes & typography
│       ├── components/
│       │   ├── KeyDef.kt        # Key model (weights, labels, HID codes, modifiers)
│       │   ├── KeyButton.kt     # Press/release key composable with haptic feedback
│       │   ├── KeyboardLayout.kt # Full 6-row keyboard (fills screen in landscape, compact in portrait)
│       │   └── StatusCard.kt    # Connect tab: Bluetooth/USB mode switch, pairing/hosts, sensitivity & diagnostics
│       └── screens/
│           ├── MainScreen.kt    # 5-tab Material 3 scaffold, permissions/BT launchers & split layout
│           ├── QuickTypeScreen.kt # System virtual keyboard (IME) live-streaming screen
│           ├── TrackpadScreen.kt  # Trackpad surface + Left/Middle/Right buttons
│           └── ControllerScreen.kt # Multi-touch Xbox / PS5 virtual gamepad screen
pc-xbox-bridge/
├── PocketPadXboxBridge.cpp      # Native Windows HID -> ViGEmBus Xbox 360 (XInput) bridge
└── PocketPadXboxBridge.exe      # Prebuilt standalone Windows bridge executable
```

---

## How It Works Under the Hood

### Bluetooth HID (No Root)
1. **Profile Registration**: `BluetoothHidManager` binds `BluetoothProfile.HID_DEVICE` and registers a 203-byte composite SDP descriptor (`HidDescriptors.BLUETOOTH_COMBO_REPORT_DESCRIPTOR`) with `Report ID 1` for Boot Keyboard (8-byte input report + Caps Lock output LED report), `Report ID 2` for 3-button Mouse + Wheel (4-byte input report), and `Report ID 3` for Gamepad (9-byte input report: 16 buttons, 8-way Hat Switch, `LX/LY/RX/RY/L2/R2`).
2. **Pairing & Connecting**: Tap **Make Discoverable** in the **Connect** tab and pair from your PC/TV/tablet's Bluetooth settings, or tap **Connect** next to an already-paired host. *(If upgrading from an older PocketKeyboard build, unpair the phone once in Windows Bluetooth settings and re-pair so Windows caches the new Gamepad HID descriptor.)*
3. **Report Streaming**: `HidReportSender.openBluetooth()` routes keyboard reports and coalesced deadline-paced trackpad/gamepad packets directly to `BluetoothHidDevice.sendReport()`.

### USB HID (Root)
1. **Backend Detection**: `UsbGadgetManager.detectEnvironment()` probes `/config/usb_gadget`, `/sys/kernel/config/usb_gadget` (mounting `configfs` if needed), existing `/dev/hidg*` nodes, and legacy `/sys/class/android_usb/android0`.
2. **Composite HID Gadget Setup**:
   - Attempts a standalone `pocketkeyboard` gadget first, and automatically falls back to Android's existing `g1` gadget (`configs/b.1` or `c.1`) if `ln -s` is restricted.
   - `hid.usb0` registers the 63-byte Boot Keyboard descriptor (`8`-byte reports).
   - `hid.usb1` registers the 52-byte Boot Mouse + Wheel descriptor (`4`-byte reports).
   - `hid.usb2` registers the 82-byte Gamepad descriptor (`9`-byte reports, best-effort).
3. **USB State Management**: Saves `sys.usb.config`, sets `sys.usb.config none` while arming so Android's USB daemon does not fight over the UDC, and restores `sys.usb.config` on disarm.

---

## Troubleshooting

| Problem | Cause | Solution |
|---|---|---|
| **Bluetooth permission needed** | Android 12+ requires Nearby Devices (`BLUETOOTH_CONNECT` / `BLUETOOTH_ADVERTISE`). | Tap **Grant Bluetooth Permission** on the **Connect** tab. |
| **Host won't pair or connect over BT** | HID service not active during pairing, or stale host bond. | Turn on Bluetooth HID service / tap **Make Discoverable** first, pair from the host's Bluetooth settings, or unpair on both sides and re-pair. |
| **Bluetooth HID registration fails** | OEM ROM disabled the Bluetooth HID Device profile or another HID app holds it. | Force-close other virtual BT keyboard apps and retry, or switch to **USB HID (Root)** if rooted. |
| **"No Root" status (USB mode)** | Superuser access denied or unrooted device. | Switch to **Bluetooth** mode for no-root use, or grant root permission in Magisk / KernelSU / APatch. |
| **No HID gadget interface found (USB)** | Kernel lacks `CONFIG_USB_CONFIGFS_F_HID=y` / `/dev/hidg*`. | Use **Bluetooth** mode, or flash a kernel with USB HID gadget support. |
| **Mouse HID node not exposed (USB)** | Kernel limits ConfigFS to 1 HID function. | Keyboard still works via `/dev/hidg0`; trackpad requires a second `/dev/hidg*` node (or use **Bluetooth** mode). |
| **MTP / ADB stopped while USB armed** | UDC is bound to HID gadget. | Toggle **DISARM** (or switch back to **Bluetooth**) on the **Connect** tab. |

---

## License

This project is licensed under the [MIT License](./LICENSE).
