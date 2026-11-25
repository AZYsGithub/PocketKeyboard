package com.pocketkeyboard.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pocketkeyboard.hid.HidKeyMap

/**
 * Full QWERTY keyboard layout with function keys, modifiers, and navigation.
 * Designed for landscape orientation on Android devices.
 */

// ── Row definitions ──

val functionRow = listOf(
    KeyDef("Esc", hidKeyCode = HidKeyMap.KEY_ESC, widthWeight = 1f),
    KeyDef("F1", hidKeyCode = HidKeyMap.KEY_F1),
    KeyDef("F2", hidKeyCode = HidKeyMap.KEY_F2),
    KeyDef("F3", hidKeyCode = HidKeyMap.KEY_F3),
    KeyDef("F4", hidKeyCode = HidKeyMap.KEY_F4),
    KeyDef("F5", hidKeyCode = HidKeyMap.KEY_F5),
    KeyDef("F6", hidKeyCode = HidKeyMap.KEY_F6),
    KeyDef("F7", hidKeyCode = HidKeyMap.KEY_F7),
    KeyDef("F8", hidKeyCode = HidKeyMap.KEY_F8),
    KeyDef("F9", hidKeyCode = HidKeyMap.KEY_F9),
    KeyDef("F10", hidKeyCode = HidKeyMap.KEY_F10),
    KeyDef("F11", hidKeyCode = HidKeyMap.KEY_F11),
    KeyDef("F12", hidKeyCode = HidKeyMap.KEY_F12),
    KeyDef("Del", hidKeyCode = HidKeyMap.KEY_DELETE)
)

val numberRow = listOf(
    KeyDef("`", "~", hidKeyCode = HidKeyMap.KEY_GRAVE),
    KeyDef("1", "!", hidKeyCode = HidKeyMap.KEY_1),
    KeyDef("2", "@", hidKeyCode = HidKeyMap.KEY_2),
    KeyDef("3", "#", hidKeyCode = HidKeyMap.KEY_3),
    KeyDef("4", "\$", hidKeyCode = HidKeyMap.KEY_4),
    KeyDef("5", "%", hidKeyCode = HidKeyMap.KEY_5),
    KeyDef("6", "^", hidKeyCode = HidKeyMap.KEY_6),
    KeyDef("7", "&", hidKeyCode = HidKeyMap.KEY_7),
    KeyDef("8", "*", hidKeyCode = HidKeyMap.KEY_8),
    KeyDef("9", "(", hidKeyCode = HidKeyMap.KEY_9),
    KeyDef("0", ")", hidKeyCode = HidKeyMap.KEY_0),
    KeyDef("-", "_", hidKeyCode = HidKeyMap.KEY_MINUS),
    KeyDef("=", "+", hidKeyCode = HidKeyMap.KEY_EQUAL),
    KeyDef("\u232B", hidKeyCode = HidKeyMap.KEY_BACKSPACE, widthWeight = 1.5f)
)

val qwertyRow = listOf(
    KeyDef("Tab", hidKeyCode = HidKeyMap.KEY_TAB, widthWeight = 1.5f),
    KeyDef("Q", hidKeyCode = HidKeyMap.KEY_Q),
    KeyDef("W", hidKeyCode = HidKeyMap.KEY_W),
    KeyDef("E", hidKeyCode = HidKeyMap.KEY_E),
    KeyDef("R", hidKeyCode = HidKeyMap.KEY_R),
    KeyDef("T", hidKeyCode = HidKeyMap.KEY_T),
    KeyDef("Y", hidKeyCode = HidKeyMap.KEY_Y),
    KeyDef("U", hidKeyCode = HidKeyMap.KEY_U),
    KeyDef("I", hidKeyCode = HidKeyMap.KEY_I),
    KeyDef("O", hidKeyCode = HidKeyMap.KEY_O),
    KeyDef("P", hidKeyCode = HidKeyMap.KEY_P),
    KeyDef("[", "{", hidKeyCode = HidKeyMap.KEY_LEFTBRACE),
    KeyDef("]", "}", hidKeyCode = HidKeyMap.KEY_RIGHTBRACE),
    KeyDef("\\", "|", hidKeyCode = HidKeyMap.KEY_BACKSLASH)
)

val homeRow = listOf(
    KeyDef("Caps", hidKeyCode = HidKeyMap.KEY_CAPSLOCK, widthWeight = 1.75f, isToggle = true),
    KeyDef("A", hidKeyCode = HidKeyMap.KEY_A),
    KeyDef("S", hidKeyCode = HidKeyMap.KEY_S),
    KeyDef("D", hidKeyCode = HidKeyMap.KEY_D),
    KeyDef("F", hidKeyCode = HidKeyMap.KEY_F),
    KeyDef("G", hidKeyCode = HidKeyMap.KEY_G),
    KeyDef("H", hidKeyCode = HidKeyMap.KEY_H),
    KeyDef("J", hidKeyCode = HidKeyMap.KEY_J),
    KeyDef("K", hidKeyCode = HidKeyMap.KEY_K),
    KeyDef("L", hidKeyCode = HidKeyMap.KEY_L),
    KeyDef(";", ":", hidKeyCode = HidKeyMap.KEY_SEMICOLON),
    KeyDef("'", "\"", hidKeyCode = HidKeyMap.KEY_APOSTROPHE),
    KeyDef("Enter", hidKeyCode = HidKeyMap.KEY_ENTER, widthWeight = 2f)
)

val shiftRow = listOf(
    KeyDef("Shift", hidKeyCode = HidKeyMap.KEY_NONE, modifier = HidKeyMap.MOD_LSHIFT,
        widthWeight = 2.25f, isModifier = true),
    KeyDef("Z", hidKeyCode = HidKeyMap.KEY_Z),
    KeyDef("X", hidKeyCode = HidKeyMap.KEY_X),
    KeyDef("C", hidKeyCode = HidKeyMap.KEY_C),
    KeyDef("V", hidKeyCode = HidKeyMap.KEY_V),
    KeyDef("B", hidKeyCode = HidKeyMap.KEY_B),
    KeyDef("N", hidKeyCode = HidKeyMap.KEY_N),
    KeyDef("M", hidKeyCode = HidKeyMap.KEY_M),
    KeyDef(",", "<", hidKeyCode = HidKeyMap.KEY_COMMA),
    KeyDef(".", ">", hidKeyCode = HidKeyMap.KEY_DOT),
    KeyDef("/", "?", hidKeyCode = HidKeyMap.KEY_SLASH),
    KeyDef("Shift", hidKeyCode = HidKeyMap.KEY_NONE, modifier = HidKeyMap.MOD_RSHIFT,
        widthWeight = 2.25f, isModifier = true)
)

val bottomRow = listOf(
    KeyDef("Ctrl", hidKeyCode = HidKeyMap.KEY_NONE, modifier = HidKeyMap.MOD_LCTRL,
        widthWeight = 1.5f, isModifier = true),
    KeyDef("Win", hidKeyCode = HidKeyMap.KEY_NONE, modifier = HidKeyMap.MOD_LGUI,
        widthWeight = 1f, isModifier = true),
    KeyDef("Alt", hidKeyCode = HidKeyMap.KEY_NONE, modifier = HidKeyMap.MOD_LALT,
        widthWeight = 1.25f, isModifier = true),
    KeyDef("Space", hidKeyCode = HidKeyMap.KEY_SPACE, widthWeight = 6f),
    KeyDef("Alt", hidKeyCode = HidKeyMap.KEY_NONE, modifier = HidKeyMap.MOD_RALT,
        widthWeight = 1.25f, isModifier = true),
    KeyDef("Ctrl", hidKeyCode = HidKeyMap.KEY_NONE, modifier = HidKeyMap.MOD_RCTRL,
        widthWeight = 1.25f, isModifier = true),
    KeyDef("\u2190", hidKeyCode = HidKeyMap.KEY_LEFT),
    KeyDef("\u2191", hidKeyCode = HidKeyMap.KEY_UP),
    KeyDef("\u2193", hidKeyCode = HidKeyMap.KEY_DOWN),
    KeyDef("\u2192", hidKeyCode = HidKeyMap.KEY_RIGHT)
)

val allRows = listOf(functionRow, numberRow, qwertyRow, homeRow, shiftRow, bottomRow)

@Composable
fun KeyboardLayout(
    isShiftActive: Boolean,
    isCapsLockActive: Boolean,
    activeModifiers: Set<Int>,
    onKeyDown: (KeyDef) -> Unit,
    onKeyUp: (KeyDef) -> Unit,
    compactRows: Boolean = false,
    modifier: Modifier = Modifier
) {
    val effectiveShift = isShiftActive || isCapsLockActive

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp)
    ) {
        for (row in allRows) {
            KeyRow(
                keys = row,
                isShiftActive = effectiveShift,
                activeModifiers = activeModifiers,
                isCapsLockActive = isCapsLockActive,
                onKeyDown = onKeyDown,
                onKeyUp = onKeyUp,
                modifier = if (compactRows) Modifier.height(42.dp) else Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun KeyRow(
    keys: List<KeyDef>,
    isShiftActive: Boolean,
    activeModifiers: Set<Int>,
    isCapsLockActive: Boolean,
    onKeyDown: (KeyDef) -> Unit,
    onKeyUp: (KeyDef) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier.fillMaxWidth()) {
        for (key in keys) {
            val isActive = when {
                key.isToggle && key.hidKeyCode == HidKeyMap.KEY_CAPSLOCK -> isCapsLockActive
                key.isModifier -> key.modifier in activeModifiers
                else -> false
            }

            KeyButton(
                keyDef = key,
                isShiftActive = isShiftActive,
                isActive = isActive,
                modifier = Modifier.weight(key.widthWeight),
                onKeyDown = onKeyDown,
                onKeyUp = onKeyUp
            )
        }
    }
}
