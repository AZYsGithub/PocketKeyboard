package com.pocketkeyboard.ui.components

import com.pocketkeyboard.hid.HidKeyMap

/**
 * Defines a single key on the virtual keyboard.
 *
 * @param label         Primary label shown on the key
 * @param shiftLabel    Label shown when Shift is active (defaults to label)
 * @param hidKeyCode    USB HID key code to send
 * @param modifier      Modifier bitmask (for modifier keys like Shift, Ctrl)
 * @param widthWeight   Relative width weight (1.0 = standard key width)
 * @param isModifier    Whether this key is a modifier (toggle behavior)
 * @param isToggle      Whether this key toggles on/off (Caps Lock)
 */
data class KeyDef(
    val label: String,
    val shiftLabel: String = label,
    val hidKeyCode: Int = HidKeyMap.KEY_NONE,
    val modifier: Int = HidKeyMap.MOD_NONE,
    val widthWeight: Float = 1f,
    val isModifier: Boolean = false,
    val isToggle: Boolean = false
)
