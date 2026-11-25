package com.pocketkeyboard.hid

/**
 * USB HID Keyboard usage codes (from USB HID Usage Tables 1.12).
 * These are the scan codes sent in the 8-byte HID keyboard report.
 */
object HidKeyMap {

    // ── Modifier bit flags (byte 0 of the report) ──
    const val MOD_NONE   = 0x00
    const val MOD_LCTRL  = 0x01
    const val MOD_LSHIFT = 0x02
    const val MOD_LALT   = 0x04
    const val MOD_LGUI   = 0x08
    const val MOD_RCTRL  = 0x10
    const val MOD_RSHIFT = 0x20
    const val MOD_RALT   = 0x40
    const val MOD_RGUI   = 0x80

    // ── Letter keys (0x04 - 0x1D) ──
    const val KEY_A = 0x04
    const val KEY_B = 0x05
    const val KEY_C = 0x06
    const val KEY_D = 0x07
    const val KEY_E = 0x08
    const val KEY_F = 0x09
    const val KEY_G = 0x0A
    const val KEY_H = 0x0B
    const val KEY_I = 0x0C
    const val KEY_J = 0x0D
    const val KEY_K = 0x0E
    const val KEY_L = 0x0F
    const val KEY_M = 0x10
    const val KEY_N = 0x11
    const val KEY_O = 0x12
    const val KEY_P = 0x13
    const val KEY_Q = 0x14
    const val KEY_R = 0x15
    const val KEY_S = 0x16
    const val KEY_T = 0x17
    const val KEY_U = 0x18
    const val KEY_V = 0x19
    const val KEY_W = 0x1A
    const val KEY_X = 0x1B
    const val KEY_Y = 0x1C
    const val KEY_Z = 0x1D

    // ── Number keys (0x1E - 0x27) ──
    const val KEY_1 = 0x1E
    const val KEY_2 = 0x1F
    const val KEY_3 = 0x20
    const val KEY_4 = 0x21
    const val KEY_5 = 0x22
    const val KEY_6 = 0x23
    const val KEY_7 = 0x24
    const val KEY_8 = 0x25
    const val KEY_9 = 0x26
    const val KEY_0 = 0x27

    // ── Control keys ──
    const val KEY_ENTER      = 0x28
    const val KEY_ESC        = 0x29
    const val KEY_BACKSPACE  = 0x2A
    const val KEY_TAB        = 0x2B
    const val KEY_SPACE      = 0x2C

    // ── Symbol keys ──
    const val KEY_MINUS      = 0x2D   // - and _
    const val KEY_EQUAL      = 0x2E   // = and +
    const val KEY_LEFTBRACE  = 0x2F   // [ and {
    const val KEY_RIGHTBRACE = 0x30   // ] and }
    const val KEY_BACKSLASH  = 0x31   // \ and |
    const val KEY_SEMICOLON  = 0x33   // ; and :
    const val KEY_APOSTROPHE = 0x34   // ' and "
    const val KEY_GRAVE      = 0x35   // ` and ~
    const val KEY_COMMA      = 0x36   // , and <
    const val KEY_DOT        = 0x37   // . and >
    const val KEY_SLASH      = 0x38   // / and ?

    // ── Lock keys ──
    const val KEY_CAPSLOCK   = 0x39
    const val KEY_NUMLOCK    = 0x53
    const val KEY_SCROLLLOCK = 0x47

    // ── Function keys (0x3A - 0x45) ──
    const val KEY_F1  = 0x3A
    const val KEY_F2  = 0x3B
    const val KEY_F3  = 0x3C
    const val KEY_F4  = 0x3D
    const val KEY_F5  = 0x3E
    const val KEY_F6  = 0x3F
    const val KEY_F7  = 0x40
    const val KEY_F8  = 0x41
    const val KEY_F9  = 0x42
    const val KEY_F10 = 0x43
    const val KEY_F11 = 0x44
    const val KEY_F12 = 0x45

    // ── Navigation keys ──
    const val KEY_PRINTSCREEN = 0x46
    const val KEY_PAUSE       = 0x48
    const val KEY_INSERT      = 0x49
    const val KEY_HOME        = 0x4A
    const val KEY_PAGEUP      = 0x4B
    const val KEY_DELETE      = 0x4C
    const val KEY_END         = 0x4D
    const val KEY_PAGEDOWN    = 0x4E
    const val KEY_RIGHT       = 0x4F
    const val KEY_LEFT        = 0x50
    const val KEY_DOWN        = 0x51
    const val KEY_UP          = 0x52

    const val KEY_NONE = 0x00
}
