package com.pocketkeyboard.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A single key button on the virtual keyboard.
 * Uses fillMaxHeight() so the parent row's weight determines height,
 * ensuring all 6 rows always fit on screen without clipping.
 */
@Composable
fun KeyButton(
    keyDef: KeyDef,
    isShiftActive: Boolean,
    isActive: Boolean = false,
    modifier: Modifier = Modifier,
    onKeyDown: (KeyDef) -> Unit,
    onKeyUp: (KeyDef) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val displayLabel = if (isShiftActive && keyDef.shiftLabel != keyDef.label) {
        keyDef.shiftLabel
    } else {
        keyDef.label
    }

    val bgColor = when {
        isActive -> MaterialTheme.colorScheme.primary
        keyDef.isModifier || keyDef.isToggle -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    val textColor = when {
        isActive -> MaterialTheme.colorScheme.onPrimary
        keyDef.isModifier || keyDef.isToggle -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val fontSize = when {
        displayLabel.length > 3 -> 10.sp
        displayLabel.length > 2 -> 11.sp
        else -> 13.sp
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .padding(1.5.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .pointerInput(keyDef) {
                detectTapGestures(
                    onPress = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onKeyDown(keyDef)
                        tryAwaitRelease()
                        onKeyUp(keyDef)
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = displayLabel,
            color = textColor,
            fontSize = fontSize,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp)
        )
    }
}
