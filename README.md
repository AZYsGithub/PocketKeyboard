# PocketKeyboard

![preview](./images/preview.png)
<p align="center">
  <b>Emergency USB HID Virtual Keyboard &amp; Trackpad for Rooted Android Devices</b><br>
  Built with Jetpack Compose &amp; Material You (Material 3)
</p>

---

## Overview

**PocketKeyboard** transforms a rooted Android smartphone into a standard **USB Human Interface Device (HID) Keyboard + Mouse/Trackpad**. When connected to a computer via a USB data cable, your phone enumerates directly as a physical USB keyboard and 3-button mouse.

Because it operates at the USB protocol level through the Linux kernel's USB gadget driver, input is recognized natively by any target host—including **UEFI/BIOS menus, bootloaders, recovery consoles, Linux, Windows, macOS, and ChromeOS**—with zero host drivers required.

---

## Features

- **Material You Design**: Dynamic color theming that adapts to your wallpaper on Android 12+ (Material 3), with dark/light mode support.
- **4-Page Interface**:
  1. **USB HID**: Root check, **ARM / DISARM** toggle, USB Gadget diagnostics (`ConfigFS` path, `UDC`, `/dev/hidg*` nodes), and settings.
  2. **Full Keyboard**: Complete 6-row physical keyboard (`Esc`, `F1`–`F12`, `Del`, numbers/symbols, QWERTY, `Caps`, `Enter`, `Shift`, `Ctrl`, `Win`, `Alt`, `Space`, Arrow keys).
     - In **landscape**, the keyboard fills the entire screen.
     - In **portrait**, keys keep a natural compact height with an optional **Trackpad** below (controlled by *"Enable trackpad on vertical full keyboard"*).
  3. **Quick Type**: Uses your Android system virtual keyboard (e.g., Gboard) for fast typing, swipe, and autocorrect—live-streaming keystrokes and deletions over USB HID, with quick chips (`Enter`, `Tab`, `Esc`, `Backspace`, `Ctrl+C`, `Ctrl+V`).
  4. **Trackpad**: Full-screen touch trackpad with **Left / Middle / Right** physical mouse buttons (supports press-and-hold), 2-finger scroll wheel, 2-finger tap for right-click, 1-finger tap for left-click, and **double-tap & drag** (second tap holds Left Mouse Button until finger release).
- **Modifier Key Mode (Toggle vs Hold)**: Switch between **TOGGLE** (sticky tap-to-lock/unlock) and **HOLD** (momentary press-and-release with multi-touch support) for `Shift`, `Ctrl`, `Alt`, and `Win`.
- **Low-Latency HID Streaming**:
  - Keyboard uses a persistent root shell pipe to `/dev/hidg0`.
  - Trackpad uses a delta-coalescing worker thread (direct `FileOutputStream` when permitted, or paced `su` pipe) so high-frequency touch events never build up lag.

---

## Device & Kernel Requirements

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

1. Clone this repository and open the folder in Android Studio.
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
├── hid/
│   ├── HidKeyMap.kt             # USB HID Usage Tables 1.12 scan codes & modifier masks
│   ├── HidCharMapper.kt         # ASCII character to (modifier, keyCode) mapper for Quick Type
│   ├── HidReportSender.kt       # 8-byte keyboard & 4-byte coalesced mouse/trackpad sender
│   └── UsbGadgetManager.kt      # ConfigFS (/config/usb_gadget, /sys/kernel/config/usb_gadget, g1 fallback)
├── viewmodel/
│   └── KeyboardViewModel.kt     # State holder for ARM/DISARM, modifiers, Quick Type, and Trackpad
└── ui/
    ├── theme/                   # Material You dynamic dark/light color schemes & typography
    ├── components/
    │   ├── KeyDef.kt            # Key model (weights, labels, HID codes, modifiers)
    │   ├── KeyButton.kt         # Press/release key composable with haptic feedback
    │   ├── KeyboardLayout.kt    # Full 6-row keyboard (fills screen in landscape, compact in portrait)
    │   └── StatusCard.kt        # USB HID status, diagnostics, ARM toggle, and mode switches
    └── screens/
        ├── MainScreen.kt        # 4-tab Material 3 scaffold & portrait/landscape split layout
        ├── QuickTypeScreen.kt   # System virtual keyboard (IME) live-streaming screen
        └── TrackpadScreen.kt    # Trackpad surface + Left/Middle/Right buttons
```

---

## How It Works Under the Hood

1. **Backend Detection**: `UsbGadgetManager.detectEnvironment()` probes `/config/usb_gadget`, `/sys/kernel/config/usb_gadget` (mounting `configfs` if needed), existing `/dev/hidg*` nodes, and legacy `/sys/class/android_usb/android0`.
2. **Dual HID Gadget Setup**:
   - Attempts a standalone `pocketkeyboard` gadget first, and automatically falls back to Android's existing `g1` gadget (`configs/b.1` or `c.1`) if `ln -s` is restricted.
   - `hid.usb0` registers a 63-byte Boot Keyboard descriptor (8-byte reports: modifier byte, reserved byte, 6 key slots).
   - `hid.usb1` registers a 52-byte Boot Mouse + Wheel descriptor (4-byte reports: button bits, `dX`, `dY`, `wheel`).
3. **USB State Management**: Saves `sys.usb.config`, sets `sys.usb.config none` while arming so Android's USB daemon does not fight over the UDC, and restores `sys.usb.config` on disarm.

---

## Troubleshooting

| Problem | Cause | Solution |
|---|---|---|
| **"No Root" status** | Superuser access denied. | Grant root permission to PocketKeyboard in Magisk / KernelSU / APatch. |
| **No HID gadget interface found** | Kernel lacks `CONFIG_USB_CONFIGFS_F_HID=y` / `/dev/hidg*`. | Check diagnostics on the **USB HID** tab; a kernel with HID gadget support is required. |
| **Mouse HID node not exposed** | Kernel limits ConfigFS to 1 HID function. | Keyboard still works via `/dev/hidg0`; trackpad requires a second `/dev/hidg*` node. |
| **Keys not received by PC** | Charge-only cable or host port issue. | Use a USB data cable connected directly to a host USB port. |
| **MTP / ADB stopped while armed** | UDC is bound to HID gadget. | Toggle **DISARM** on the **USB HID** tab to restore normal USB modes. |

---

## License

This project is licensed under the MIT License.
