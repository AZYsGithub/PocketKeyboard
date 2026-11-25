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

### Root Execution (`com.pocketkeyboard.root`)
- **`RootShell.kt`**: Executes `su` commands (`isRootAvailable()`, `execute()`, `executeBatch()`).
  - Never assume non-root apps can open `/dev/hidg*` directly—even after `chmod 666`, SELinux `untrusted_app` policies on most Android ROMs block direct `open()`. Root (`su`) is mandatory for ConfigFS setup and fallback writes.

### USB HID Layer (`com.pocketkeyboard.hid`)
- **`UsbGadgetManager.kt`**:
  - `detectEnvironment()` probes in a single `su` shell call:
    1. `/config/usb_gadget` (common on Qualcomm/MediaTek Android devices)
    2. `/sys/kernel/config/usb_gadget` (mounts `configfs` first if neither exists)
    3. Pre-existing `/dev/hidg*` nodes (`EXISTING_HIDG`)
    4. Legacy `/sys/class/android_usb/android0` (`ANDROID_USB`)
    5. `/dev/gadget` / `/proc/filesystems` (`hasGadgetFs` diagnostic flag)
  - `enable()` saves `getprop sys.usb.config`, sets `sys.usb.config none` (so Android `init`/`usbd` stops fighting over the UDC), and configures:
    - `hid.usb0`: 63-byte Boot Keyboard descriptor (`protocol=1`, `subclass=1`, `report_length=8`).
    - `hid.usb1`: 52-byte Boot Mouse + Wheel descriptor (`protocol=2`, `subclass=1`, `report_length=4`). Best-effort: if a kernel rejects a second HID function, keyboard still succeeds and `mousePath` is `null`.
  - **ConfigFS Symlink Invariant:** Use `ln -s <src> <dst>/hid.usb0` (no `-f`, no trailing slash). If creating a standalone `pocketkeyboard` gadget returns `EPERM`, `enableViaConfigFs()` automatically falls back to Android's native `g1` gadget directory (`configs/b.1` or `configs/c.1`).
  - `disable()` unbinds UDC, removes `hid.usb0`/`hid.usb1` symlinks/functions (without deleting `g1` itself if `g1` was used), and restores the saved `sys.usb.config`.
- **`HidReportSender.kt`**:
  - **Keyboard (`/dev/hidg0`):** Uses a persistent `su` `DataOutputStream` (`printf '\x...' > $kbdDevicePath`).
    - **Invariant:** `sendKeyUp(modifiers)` MUST preserve active `modifiers` in byte `0` so releasing a normal key does not wipe held/toggled modifier keys on the host.
  - **Mouse / Trackpad (`/dev/hidg1`):**
    - Tries direct `FileOutputStream` first; falls back to a dedicated persistent `su` pipe.
    - **Delta-Coalescing Worker (`PocketKeyboard-MouseWorker`):** Touch screens fire at 120Hz–240Hz, which would cause severe queue lag if every move queued a `su` `printf` command. Instead, `sendMouseReportAsync()` accumulates `pendingDx`, `pendingDy`, `pendingWheel`, and `pendingButtons` under `mouseLock`, and the worker drains clamped `[-127, 127]` deltas at a paced rate (`14ms` for `su`, `4ms` for direct stream).
    - **Click Queue (`pendingClickQueue`):** Instant clicks MUST use `sendMouseClickAsync()`, NOT back-to-back `sendMouseReportAsync(down)` + `sendMouseReportAsync(up)` calls (which would coalesce `pendingButtons` back to `0` before the worker wakes up). `pendingClickQueue` guarantees a `20ms` press followed by a release.
- **`HidKeyMap.kt` & `HidCharMapper.kt`**:
  - `HidKeyMap`: USB HID Usage Tables 1.12 scan codes and modifier bitmasks.
  - `HidCharMapper`: Maps printable ASCII + `\n` + `\t` to `(modifier, keyCode)` for US QWERTY.

### State Management (`com.pocketkeyboard.viewmodel`)
- **`KeyboardViewModel.kt`**:
  - Manages `rootAvailable`, `gadgetEnvironment`, `hidArmed`, `mouseAvailable`, `connectionStatus`, `holdModifiersMode`, `verticalKeyboardTrackpad`, `shiftActive`, `capsLockActive`, `activeModifiers`, and `quickTypeText`.
  - **Modifier Modes:**
    - `holdModifiersMode == false` (**TOGGLE**): Tapping a modifier (`Shift`, `Ctrl`, `Alt`, `Win`) toggles its bit in `currentModifiers`.
    - `holdModifiersMode == true` (**HOLD**): Pressing sets the bit; releasing clears the bit. Switching modes clears any active modifiers.
  - **Quick Type Diffing:** `onQuickTypeTextChanged()` computes the longest common prefix between `oldText` and `newText`, sends `KEY_BACKSPACE` taps for deleted characters, and sends `HidCharMapper` taps for added characters (supports Gboard swipe/autocorrect).

### UI Layer (`com.pocketkeyboard.ui`)
- **`MainScreen.kt`**: 4-tab Material 3 `TabRow` (`USB HID`, `Full Keyboard`, `Quick Type`, `Trackpad`).
  - Automatically hides the system IME (`LocalSoftwareKeyboardController`) when leaving `Quick Type`.
  - In `Full Keyboard`, checks `LocalConfiguration.current.orientation`:
    - **Landscape:** `KeyboardLayout(..., compactRows = false)` uses `Modifier.weight(1f)` per row so all 6 rows fill the screen without clipping the bottom row.
    - **Portrait:** `KeyboardLayout(..., compactRows = true)` uses fixed `42.dp` row height (no vertical stretching), and renders `TrackpadPanel` below when `verticalKeyboardTrackpad` is `true`.
- **`TrackpadScreen.kt`**:
  - Uses raw `awaitPointerEventScope` (zero drag-slop delay, sub-pixel remainder accumulation).
  - **Tap & Double-Tap-Drag State Machine:** Uses a `200ms` `pendingTapJob` on 1-finger tap-up. If a second touch arrives within `200ms`, `pendingTapJob` is cancelled (preventing a double-click) and only `onMouseButtonDown(MOUSE_LEFT)` is sent until finger lift (`onMouseButtonUp(MOUSE_LEFT)`).
  - 2-finger vertical drag sends wheel scroll; 2-finger tap sends right-click.
  - Bottom row has Left, Middle, and Right buttons supporting press-and-hold.
- **`AndroidManifest.xml`**:
  - `android:windowSoftInputMode="adjustResize|stateHidden"` (required so Gboard opens on `Quick Type` while staying hidden on other tabs).
  - `android:configChanges="orientation|screenSize|screenLayout|keyboardHidden"` (prevents Activity recreation from tearing down the active USB HID session on rotation).

---

## 3. Guidelines for Future Changes

- **Never block the UI thread** with `RootShell` or `su` I/O; keep all root operations on `Dispatchers.IO` or the dedicated mouse worker.
- **Avoid fixed heights on landscape keyboard rows**: Landscape phone viewports are ~360dp–411dp tall; any fixed height or unguarded banner above `KeyboardLayout` can push the bottom row off-screen.
- **Test both single-tap and double-tap-drag** if modifying `TrackpadPanel` pointer logic, and keep `sendMouseClickAsync` separate from delta coalescing.
