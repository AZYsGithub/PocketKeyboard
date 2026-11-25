package com.pocketkeyboard.hid

/**
 * Maps printable ASCII characters and common control characters
 * to USB HID (modifierMask, keyCode) pairs for US QWERTY layout.
 */
object HidCharMapper {

    fun mapChar(c: Char): Pair<Int, Int>? = when (c) {
        in 'a'..'z' -> HidKeyMap.MOD_NONE to (HidKeyMap.KEY_A + (c - 'a'))
        in 'A'..'Z' -> HidKeyMap.MOD_LSHIFT to (HidKeyMap.KEY_A + (c - 'A'))
        in '1'..'9' -> HidKeyMap.MOD_NONE to (HidKeyMap.KEY_1 + (c - '1'))
        '0' -> HidKeyMap.MOD_NONE to HidKeyMap.KEY_0

        ' ' -> HidKeyMap.MOD_NONE to HidKeyMap.KEY_SPACE
        '\n' -> HidKeyMap.MOD_NONE to HidKeyMap.KEY_ENTER
        '\t' -> HidKeyMap.MOD_NONE to HidKeyMap.KEY_TAB

        '-' -> HidKeyMap.MOD_NONE to HidKeyMap.KEY_MINUS
        '_' -> HidKeyMap.MOD_LSHIFT to HidKeyMap.KEY_MINUS
        '=' -> HidKeyMap.MOD_NONE to HidKeyMap.KEY_EQUAL
        '+' -> HidKeyMap.MOD_LSHIFT to HidKeyMap.KEY_EQUAL
        '[' -> HidKeyMap.MOD_NONE to HidKeyMap.KEY_LEFTBRACE
        '{' -> HidKeyMap.MOD_LSHIFT to HidKeyMap.KEY_LEFTBRACE
        ']' -> HidKeyMap.MOD_NONE to HidKeyMap.KEY_RIGHTBRACE
        '}' -> HidKeyMap.MOD_LSHIFT to HidKeyMap.KEY_RIGHTBRACE
        '\\' -> HidKeyMap.MOD_NONE to HidKeyMap.KEY_BACKSLASH
        '|' -> HidKeyMap.MOD_LSHIFT to HidKeyMap.KEY_BACKSLASH
        ';' -> HidKeyMap.MOD_NONE to HidKeyMap.KEY_SEMICOLON
        ':' -> HidKeyMap.MOD_LSHIFT to HidKeyMap.KEY_SEMICOLON
        '\'' -> HidKeyMap.MOD_NONE to HidKeyMap.KEY_APOSTROPHE
        '"' -> HidKeyMap.MOD_LSHIFT to HidKeyMap.KEY_APOSTROPHE
        '`' -> HidKeyMap.MOD_NONE to HidKeyMap.KEY_GRAVE
        '~' -> HidKeyMap.MOD_LSHIFT to HidKeyMap.KEY_GRAVE
        ',' -> HidKeyMap.MOD_NONE to HidKeyMap.KEY_COMMA
        '<' -> HidKeyMap.MOD_LSHIFT to HidKeyMap.KEY_COMMA
        '.' -> HidKeyMap.MOD_NONE to HidKeyMap.KEY_DOT
        '>' -> HidKeyMap.MOD_LSHIFT to HidKeyMap.KEY_DOT
        '/' -> HidKeyMap.MOD_NONE to HidKeyMap.KEY_SLASH
        '?' -> HidKeyMap.MOD_LSHIFT to HidKeyMap.KEY_SLASH

        '!' -> HidKeyMap.MOD_LSHIFT to HidKeyMap.KEY_1
        '@' -> HidKeyMap.MOD_LSHIFT to HidKeyMap.KEY_2
        '#' -> HidKeyMap.MOD_LSHIFT to HidKeyMap.KEY_3
        '$' -> HidKeyMap.MOD_LSHIFT to HidKeyMap.KEY_4
        '%' -> HidKeyMap.MOD_LSHIFT to HidKeyMap.KEY_5
        '^' -> HidKeyMap.MOD_LSHIFT to HidKeyMap.KEY_6
        '&' -> HidKeyMap.MOD_LSHIFT to HidKeyMap.KEY_7
        '*' -> HidKeyMap.MOD_LSHIFT to HidKeyMap.KEY_8
        '(' -> HidKeyMap.MOD_LSHIFT to HidKeyMap.KEY_9
        ')' -> HidKeyMap.MOD_LSHIFT to HidKeyMap.KEY_0

        else -> null
    }
}
