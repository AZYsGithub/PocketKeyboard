package com.pocketkeyboard.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pocketkeyboard.hid.HidReportSender
import com.pocketkeyboard.viewmodel.TransportMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Full-screen Trackpad page.
 */
@Composable
fun TrackpadScreen(
    hidArmed: Boolean,
    mouseAvailable: Boolean,
    transportMode: TransportMode,
    sensitivity: Float = 1.25f,
    onMouseMove: (dx: Int, dy: Int) -> Unit,
    onMouseScroll: (wheel: Int) -> Unit,
    onMouseButtonDown: (Int) -> Unit,
    onMouseButtonUp: (Int) -> Unit,
    onMouseClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (!hidArmed || !mouseAvailable) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    text = when {
                        !hidArmed && transportMode == TransportMode.BLUETOOTH ->
                            "Bluetooth HID is not connected. Connect to a host in the Connect tab to use the trackpad."
                        !hidArmed ->
                            "USB HID is DISARMED. Arm it in the Connect tab to use the trackpad."
                        else ->
                            "Mouse HID interface (/dev/hidg1) was not exposed by this kernel."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }

        TrackpadPanel(
            enabled = hidArmed && mouseAvailable,
            buttonHeight = 56.dp,
            sensitivity = sensitivity,
            onMouseMove = onMouseMove,
            onMouseScroll = onMouseScroll,
            onMouseButtonDown = onMouseButtonDown,
            onMouseButtonUp = onMouseButtonUp,
            onMouseClick = onMouseClick,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * Reusable Trackpad + 3 Mouse Buttons panel (used in Trackpad tab and portrait Full Keyboard).
 */
@Composable
fun TrackpadPanel(
    enabled: Boolean,
    buttonHeight: Dp = 48.dp,
    sensitivity: Float = 1.25f,
    onMouseMove: (dx: Int, dy: Int) -> Unit,
    onMouseScroll: (wheel: Int) -> Unit,
    onMouseButtonDown: (Int) -> Unit,
    onMouseButtonUp: (Int) -> Unit,
    onMouseClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val currentSensitivity by rememberUpdatedState(sensitivity)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Trackpad Surface
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(14.dp)
                )
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput

                    val scrollSensitivity = 0.08f
                    val doubleTapWindowMs = 200L

                    coroutineScope {
                        var pendingTapJob: Job? = null

                        awaitPointerEventScope {
                            var remX = 0f
                            var remY = 0f
                            var remWheel = 0f

                            var downTime = 0L
                            var lastTapUpTime = 0L
                            var maxPointers = 0
                            var totalTravel = 0f
                            var isTapDragging = false
                            var hadMultiTouch = false

                            while (true) {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.filter { it.pressed }

                                if (pressed.isNotEmpty() && maxPointers == 0) {
                                    val now = System.currentTimeMillis()
                                    downTime = now
                                    totalTravel = 0f
                                    remX = 0f
                                    remY = 0f
                                    remWheel = 0f
                                    hadMultiTouch = false

                                    // Double-tap & hold: cancel the first tap's click and send only Left Press until release
                                    if (pressed.size == 1 && (now - lastTapUpTime) <= doubleTapWindowMs) {
                                        pendingTapJob?.cancel()
                                        pendingTapJob = null
                                        isTapDragging = true
                                        lastTapUpTime = 0L
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onMouseButtonDown(HidReportSender.MOUSE_LEFT)
                                    }
                                }

                                if (pressed.size > maxPointers) {
                                    maxPointers = pressed.size
                                }

                                if (pressed.size >= 2) {
                                    hadMultiTouch = true
                                    val avgDeltaY = pressed.map { it.positionChange().y }.average().toFloat()
                                    val avgDeltaX = pressed.map { it.positionChange().x }.average().toFloat()
                                    totalTravel += abs(avgDeltaX) + abs(avgDeltaY)

                                    remWheel += -avgDeltaY * scrollSensitivity
                                    val wheelInt = remWheel.roundToInt()
                                    remWheel -= wheelInt
                                    if (wheelInt != 0) {
                                        onMouseScroll(wheelInt)
                                    }
                                    pressed.forEach { it.consume() }
                                } else if (pressed.size == 1) {
                                    val change = pressed[0]
                                    if (!hadMultiTouch && change.previousPressed) {
                                        val delta = change.positionChange()
                                        if (delta.x != 0f || delta.y != 0f) {
                                            totalTravel += abs(delta.x) + abs(delta.y)
                                            val speed = hypot(delta.x, delta.y)
                                            val accel = (0.85f + (speed * 0.045f)).coerceIn(0.85f, 1.85f)
                                            val gain = currentSensitivity * accel
                                            remX += delta.x * gain
                                            remY += delta.y * gain

                                            val dx = remX.roundToInt()
                                            val dy = remY.roundToInt()
                                            remX -= dx
                                            remY -= dy

                                            if (dx != 0 || dy != 0) {
                                                onMouseMove(dx, dy)
                                            }
                                            change.consume()
                                        }
                                    } else {
                                        change.consume()
                                    }
                                }

                                if (pressed.isEmpty() && maxPointers > 0) {
                                    if (isTapDragging) {
                                        onMouseButtonUp(HidReportSender.MOUSE_LEFT)
                                        isTapDragging = false
                                    } else {
                                        val now = System.currentTimeMillis()
                                        val elapsed = now - downTime
                                        if (elapsed < 250L && totalTravel < 24f) {
                                            if (maxPointers >= 2) {
                                                pendingTapJob?.cancel()
                                                pendingTapJob = null
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                onMouseClick(HidReportSender.MOUSE_RIGHT)
                                            } else {
                                                lastTapUpTime = now
                                                pendingTapJob?.cancel()
                                                pendingTapJob = launch {
                                                    delay(doubleTapWindowMs)
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    onMouseClick(HidReportSender.MOUSE_LEFT)
                                                }
                                            }
                                        }
                                    }
                                    maxPointers = 0
                                    hadMultiTouch = false
                                }
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Trackpad\nDrag to move • Tap to click • 2-finger scroll / right-click",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(12.dp)
            )
        }

        // Three Physical Mouse Buttons: Left, Middle, Right
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(buttonHeight),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MouseButton(
                label = "Left",
                enabled = enabled,
                modifier = Modifier.weight(1f),
                onPressDown = { onMouseButtonDown(HidReportSender.MOUSE_LEFT) },
                onReleaseUp = { onMouseButtonUp(HidReportSender.MOUSE_LEFT) }
            )
            MouseButton(
                label = "Middle",
                enabled = enabled,
                modifier = Modifier.weight(0.7f),
                onPressDown = { onMouseButtonDown(HidReportSender.MOUSE_MIDDLE) },
                onReleaseUp = { onMouseButtonUp(HidReportSender.MOUSE_MIDDLE) }
            )
            MouseButton(
                label = "Right",
                enabled = enabled,
                modifier = Modifier.weight(1f),
                onPressDown = { onMouseButtonDown(HidReportSender.MOUSE_RIGHT) },
                onReleaseUp = { onMouseButtonUp(HidReportSender.MOUSE_RIGHT) }
            )
        }
    }
}

@Composable
private fun MouseButton(
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onPressDown: () -> Unit,
    onReleaseUp: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var isPressed by remember { mutableStateOf(false) }

    val bgColor = when {
        !enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        isPressed -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val textColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        isPressed -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onPressDown()
                        tryAwaitRelease()
                        isPressed = false
                        onReleaseUp()
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = textColor
        )
    }
}
