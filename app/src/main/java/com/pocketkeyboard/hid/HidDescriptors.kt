package com.pocketkeyboard.hid

/**
 * Shared USB and Bluetooth HID Report Descriptors, Report IDs, and Gamepad mapping constants.
 *
 * USB ConfigFS registers separate HID interfaces (/dev/hidg0, /dev/hidg1, /dev/hidg2) without
 * Report IDs, whereas Bluetooth HID registers a single composite SDP record using Report ID 1
 * (Keyboard), Report ID 2 (Mouse), and Report ID 3 (Gamepad).
 */
object HidDescriptors {

    const val REPORT_ID_KEYBOARD = 1
    const val REPORT_ID_MOUSE = 2
    const val REPORT_ID_GAMEPAD = 3

    // Standard USB HID Boot Keyboard Report Descriptor (63 bytes, 8-byte report)
    val USB_KEYBOARD_REPORT_DESCRIPTOR = byteArrayOf(
        0x05, 0x01,                      // Usage Page (Generic Desktop)
        0x09, 0x06,                      // Usage (Keyboard)
        0xA1.toByte(), 0x01,             // Collection (Application)
        0x05, 0x07,                      //   Usage Page (Key Codes)
        0x19, 0xE0.toByte(),             //   Usage Minimum (224) - Left Control
        0x29, 0xE7.toByte(),             //   Usage Maximum (231) - Right GUI
        0x15, 0x00,                      //   Logical Minimum (0)
        0x25, 0x01,                      //   Logical Maximum (1)
        0x75, 0x01,                      //   Report Size (1)
        0x95.toByte(), 0x08,             //   Report Count (8)
        0x81.toByte(), 0x02,             //   Input (Data, Variable, Absolute) - Modifier byte
        0x95.toByte(), 0x01,             //   Report Count (1)
        0x75, 0x08,                      //   Report Size (8)
        0x81.toByte(), 0x01,             //   Input (Constant) - Reserved byte
        0x95.toByte(), 0x05,             //   Report Count (5)
        0x75, 0x01,                      //   Report Size (1)
        0x05, 0x08,                      //   Usage Page (LEDs)
        0x19, 0x01,                      //   Usage Minimum (1) - Num Lock
        0x29, 0x05,                      //   Usage Maximum (5) - Kana
        0x91.toByte(), 0x02,             //   Output (Data, Variable, Absolute) - LED report
        0x95.toByte(), 0x01,             //   Report Count (1)
        0x75, 0x03,                      //   Report Size (3)
        0x91.toByte(), 0x01,             //   Output (Constant) - LED padding
        0x95.toByte(), 0x06,             //   Report Count (6)
        0x75, 0x08,                      //   Report Size (8)
        0x15, 0x00,                      //   Logical Minimum (0)
        0x25, 0x65,                      //   Logical Maximum (101)
        0x05, 0x07,                      //   Usage Page (Key Codes)
        0x19, 0x00,                      //   Usage Minimum (0)
        0x29, 0x65,                      //   Usage Maximum (101)
        0x81.toByte(), 0x00,             //   Input (Data, Array) - Key array (6 keys)
        0xC0.toByte()                    // End Collection
    )

    // Standard USB HID 3-Button Mouse + Wheel Report Descriptor (52 bytes, 4-byte report)
    // [0] Buttons (bit 0=Left, bit 1=Right, bit 2=Middle), [1] dX, [2] dY, [3] Wheel
    val USB_MOUSE_REPORT_DESCRIPTOR = byteArrayOf(
        0x05, 0x01,                      // Usage Page (Generic Desktop)
        0x09, 0x02,                      // Usage (Mouse)
        0xA1.toByte(), 0x01,             // Collection (Application)
        0x09, 0x01,                      //   Usage (Pointer)
        0xA1.toByte(), 0x00,             //   Collection (Physical)
        0x05, 0x09,                      //     Usage Page (Buttons)
        0x19, 0x01,                      //     Usage Minimum (1)
        0x29, 0x03,                      //     Usage Maximum (3)
        0x15, 0x00,                      //     Logical Minimum (0)
        0x25, 0x01,                      //     Logical Maximum (1)
        0x95.toByte(), 0x03,             //     Report Count (3)
        0x75, 0x01,                      //     Report Size (1)
        0x81.toByte(), 0x02,             //     Input (Data, Variable, Absolute)
        0x95.toByte(), 0x01,             //     Report Count (1)
        0x75, 0x05,                      //     Report Size (5)
        0x81.toByte(), 0x01,             //     Input (Constant) - 5-bit padding
        0x05, 0x01,                      //     Usage Page (Generic Desktop)
        0x09, 0x30,                      //     Usage (X)
        0x09, 0x31,                      //     Usage (Y)
        0x09, 0x38,                      //     Usage (Wheel)
        0x15, 0x81.toByte(),             //     Logical Minimum (-127)
        0x25, 0x7F,                      //     Logical Maximum (127)
        0x75, 0x08,                      //     Report Size (8)
        0x95.toByte(), 0x03,             //     Report Count (3)
        0x81.toByte(), 0x06,             //     Input (Data, Variable, Relative)
        0xC0.toByte(),                   //   End Collection (Physical)
        0xC0.toByte()                    // End Collection (Application)
    )

    // Standard HID Gamepad Report Descriptor (82 bytes, 9-byte report)
    // DualSense / DirectInput / SDL2 layout:
    // [0..1] 16 buttons, [2] 4-bit Hat Switch + 4-bit padding,
    // [3] LX (X), [4] LY (Y), [5] RX (Z), [6] RY (Rz), [7] L2 (Rx), [8] R2 (Ry)
    val USB_GAMEPAD_REPORT_DESCRIPTOR = byteArrayOf(
        0x05, 0x01,                      // Usage Page (Generic Desktop)
        0x09, 0x05,                      // Usage (Game Pad)
        0xA1.toByte(), 0x01,             // Collection (Application)
        0x05, 0x09,                      //   Usage Page (Buttons)
        0x19, 0x01,                      //   Usage Minimum (1)
        0x29, 0x10,                      //   Usage Maximum (16)
        0x15, 0x00,                      //   Logical Minimum (0)
        0x25, 0x01,                      //   Logical Maximum (1)
        0x75, 0x01,                      //   Report Size (1)
        0x95.toByte(), 0x10,             //   Report Count (16)
        0x81.toByte(), 0x02,             //   Input (Data, Variable, Absolute)
        0x05, 0x01,                      //   Usage Page (Generic Desktop)
        0x09, 0x39,                      //   Usage (Hat Switch)
        0x15, 0x00,                      //   Logical Minimum (0)
        0x25, 0x07,                      //   Logical Maximum (7)
        0x35, 0x00,                      //   Physical Minimum (0)
        0x46, 0x3B, 0x01,                //   Physical Maximum (315)
        0x65, 0x14,                      //   Unit (Degrees)
        0x75, 0x04,                      //   Report Size (4)
        0x95.toByte(), 0x01,             //   Report Count (1)
        0x81.toByte(), 0x42,             //   Input (Data, Variable, Absolute, Null State)
        0x65, 0x00,                      //   Unit (None)
        0x75, 0x04,                      //   Report Size (4)
        0x95.toByte(), 0x01,             //   Report Count (1)
        0x81.toByte(), 0x01,             //   Input (Constant) - 4-bit padding
        0x05, 0x01,                      //   Usage Page (Generic Desktop)
        0x09, 0x30,                      //   Usage (X)  - Left Stick X
        0x09, 0x31,                      //   Usage (Y)  - Left Stick Y
        0x09, 0x32,                      //   Usage (Z)  - Right Stick X
        0x09, 0x35,                      //   Usage (Rz) - Right Stick Y
        0x09, 0x33,                      //   Usage (Rx) - L2 Trigger
        0x09, 0x34,                      //   Usage (Ry) - R2 Trigger
        0x15, 0x00,                      //   Logical Minimum (0)
        0x26, 0xFF.toByte(), 0x00,       //   Logical Maximum (255)
        0x35, 0x00,                      //   Physical Minimum (0)
        0x46, 0xFF.toByte(), 0x00,       //   Physical Maximum (255)
        0x75, 0x08,                      //   Report Size (8)
        0x95.toByte(), 0x06,             //   Report Count (6)
        0x81.toByte(), 0x02,             //   Input (Data, Variable, Absolute)
        0xC0.toByte()                    // End Collection
    )

    val GAMEPAD_NEUTRAL_REPORT = byteArrayOf(
        0x00, 0x00, 0x08,
        0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
        0x00, 0x00
    )

    // Composite Bluetooth HID descriptor (203 bytes: 65 Keyboard + 54 Mouse + 84 Gamepad)
    val BLUETOOTH_COMBO_REPORT_DESCRIPTOR: ByteArray =
        insertReportId(USB_KEYBOARD_REPORT_DESCRIPTOR, REPORT_ID_KEYBOARD) +
            insertReportId(USB_MOUSE_REPORT_DESCRIPTOR, REPORT_ID_MOUSE) +
            insertReportId(USB_GAMEPAD_REPORT_DESCRIPTOR, REPORT_ID_GAMEPAD)

    /**
     * Inserts a HID Report ID item (0x85, id) immediately after the top-level
     * Usage Page / Usage / Collection(Application) 6-byte header.
     */
    private fun insertReportId(descriptor: ByteArray, reportId: Int): ByteArray {
        val headerSize = 6
        val result = ByteArray(descriptor.size + 2)
        descriptor.copyInto(result, destinationOffset = 0, startIndex = 0, endIndex = headerSize)
        result[headerSize] = 0x85.toByte()
        result[headerSize + 1] = reportId.toByte()
        descriptor.copyInto(
            result,
            destinationOffset = headerSize + 2,
            startIndex = headerSize,
            endIndex = descriptor.size
        )
        return result
    }
}

/**
 * DualSense-ordered HID button bitmasks and 8-way D-pad Hat Switch constants.
 */
object HidGamepadMap {
    const val BTN_SQUARE   = 1 shl 0
    const val BTN_CROSS    = 1 shl 1
    const val BTN_CIRCLE   = 1 shl 2
    const val BTN_TRIANGLE = 1 shl 3
    const val BTN_L1       = 1 shl 4
    const val BTN_R1       = 1 shl 5
    const val BTN_L2       = 1 shl 6
    const val BTN_R2       = 1 shl 7
    const val BTN_CREATE   = 1 shl 8
    const val BTN_OPTIONS  = 1 shl 9
    const val BTN_L3       = 1 shl 10
    const val BTN_R3       = 1 shl 11
    const val BTN_PS       = 1 shl 12
    const val BTN_TOUCHPAD = 1 shl 13
    const val BTN_MUTE     = 1 shl 14

    const val HAT_UP         = 0
    const val HAT_UP_RIGHT   = 1
    const val HAT_RIGHT      = 2
    const val HAT_DOWN_RIGHT = 3
    const val HAT_DOWN       = 4
    const val HAT_DOWN_LEFT  = 5
    const val HAT_LEFT       = 6
    const val HAT_UP_LEFT    = 7
    const val HAT_NEUTRAL    = 8

    const val AXIS_CENTER = 128
}
