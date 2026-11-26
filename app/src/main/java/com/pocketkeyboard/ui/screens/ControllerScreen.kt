package com.pocketkeyboard.ui.screens

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketkeyboard.hid.HidGamepadMap
import com.pocketkeyboard.viewmodel.ControllerStyle
import com.pocketkeyboard.viewmodel.TransportMode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

private val ShellBg = Color(0xFF14171F)
private val ControlSurface = Color(0xFF222734)
private val ControlPressed = Color(0xFF34415A)
private val ControlBorder = Color(0xFF394154)

private val DualSenseBlue = Color(0xFF2979FF)
private val XboxGreen = Color(0xFF2ECC71)

private val PsTriangleGreen = Color(0xFF40E2A0)
private val PsCircleRed = Color(0xFFFF6666)
private val PsCrossBlue = Color(0xFF7CB2E8)
private val PsSquarePink = Color(0xFFFF69F8)

private val XboxYAmber = Color(0xFFF5C542)
private val XboxBRed = Color(0xFFFF5252)
private val XboxAGreen = Color(0xFF4CAF50)
private val XboxXBlue = Color(0xFF42A5F5)

private enum class FaceSymbol {
    TRIANGLE, CIRCLE, CROSS, SQUARE
}

/**
 * Multi-touch virtual gamepad screen supporting Xbox (A/B/X/Y) and PS5 DualSense styles.
 */
@Composable
fun ControllerScreen(
    hidArmed: Boolean,
    gamepadAvailable: Boolean,
    transportMode: TransportMode,
    bluetoothBridgeHost: String? = null,
    controllerStyle: ControllerStyle,
    onToggleControllerStyle: (ControllerStyle) -> Unit,
    onButtonChange: (Int, Boolean) -> Unit,
    onDpadChange: (Int) -> Unit,
    onLeftStickChange: (Int, Int) -> Unit,
    onRightStickChange: (Int, Int) -> Unit,
    onTriggerChange: (Boolean, Int) -> Unit,
    onResetGamepad: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isPortrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
    val enabled = gamepadAvailable
    val isXbox = controllerStyle == ControllerStyle.XBOX
    val accentColor = if (isXbox) XboxGreen else DualSenseBlue

    DisposableEffect(Unit) {
        onDispose {
            onResetGamepad()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ShellBg)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (!enabled) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    text = when {
                        transportMode == TransportMode.BLUETOOTH && bluetoothBridgeHost == null ->
                            "Not connected. Connect in the Connect tab, or run PocketPadXboxBridge.exe on your Windows PC over Bluetooth."
                        !hidArmed && transportMode == TransportMode.USB_ROOT ->
                            "USB HID is DISARMED. Arm it in the Connect tab to use the Gamepad."
                        else ->
                            "Gamepad HID interface (/dev/hidg2) was not exposed by this kernel. Use Bluetooth mode for Gamepad."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }

        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (isPortrait) {
                PortraitGamepadLayout(
                    maxWidth = maxWidth,
                    maxHeight = maxHeight,
                    enabled = enabled,
                    controllerStyle = controllerStyle,
                    accentColor = accentColor,
                    onToggleControllerStyle = onToggleControllerStyle,
                    onButtonChange = onButtonChange,
                    onDpadChange = onDpadChange,
                    onLeftStickChange = onLeftStickChange,
                    onRightStickChange = onRightStickChange,
                    onTriggerChange = onTriggerChange
                )
            } else {
                LandscapeGamepadLayout(
                    maxWidth = maxWidth,
                    maxHeight = maxHeight,
                    enabled = enabled,
                    controllerStyle = controllerStyle,
                    accentColor = accentColor,
                    onToggleControllerStyle = onToggleControllerStyle,
                    onButtonChange = onButtonChange,
                    onDpadChange = onDpadChange,
                    onLeftStickChange = onLeftStickChange,
                    onRightStickChange = onRightStickChange,
                    onTriggerChange = onTriggerChange
                )
            }
        }
    }
}

@Composable
private fun LandscapeGamepadLayout(
    maxWidth: Dp,
    maxHeight: Dp,
    enabled: Boolean,
    controllerStyle: ControllerStyle,
    accentColor: Color,
    onToggleControllerStyle: (ControllerStyle) -> Unit,
    onButtonChange: (Int, Boolean) -> Unit,
    onDpadChange: (Int) -> Unit,
    onLeftStickChange: (Int, Int) -> Unit,
    onRightStickChange: (Int, Int) -> Unit,
    onTriggerChange: (Boolean, Int) -> Unit
) {
    val isXbox = controllerStyle == ControllerStyle.XBOX
    val topRowHeight = (maxHeight * 0.24f).coerceIn(50.dp, 82.dp)
    val dpadSize = minOf(maxHeight * 0.44f, maxWidth * 0.17f).coerceIn(88.dp, 138.dp)
    val faceSize = minOf(maxHeight * 0.46f, maxWidth * 0.18f).coerceIn(92.dp, 144.dp)
    val stickSize = minOf(maxHeight * 0.42f, maxWidth * 0.16f).coerceIn(84.dp, 130.dp)

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Top row: LT/LB (L2/L1) | View/Touchpad/Menu (Create/Touchpad/Options) | RB/RT (R1/R2)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(topRowHeight),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GamepadAnalogTrigger(
                label = if (isXbox) "LT" else "L2",
                enabled = enabled,
                accentColor = accentColor,
                onValueChange = { onTriggerChange(false, it) },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )
            GamepadShoulderButton(
                label = if (isXbox) "LB" else "L1",
                enabled = enabled,
                accentColor = accentColor,
                onPressedChange = { onButtonChange(HidGamepadMap.BTN_L1, it) },
                modifier = Modifier
                    .weight(0.9f)
                    .fillMaxHeight()
            )

            GamepadShoulderButton(
                label = if (isXbox) "VIEW" else "CREATE",
                enabled = enabled,
                accentColor = accentColor,
                onPressedChange = { onButtonChange(HidGamepadMap.BTN_CREATE, it) },
                modifier = Modifier
                    .width(64.dp)
                    .fillMaxHeight(0.76f)
            )

            GamepadTouchpadButton(
                label = if (isXbox) "XINPUT PAD" else "TOUCHPAD",
                enabled = enabled,
                accentColor = accentColor,
                onPressedChange = { onButtonChange(HidGamepadMap.BTN_TOUCHPAD, it) },
                modifier = Modifier
                    .weight(1.7f)
                    .fillMaxHeight()
            )

            GamepadShoulderButton(
                label = if (isXbox) "MENU" else "OPTIONS",
                enabled = enabled,
                accentColor = accentColor,
                onPressedChange = { onButtonChange(HidGamepadMap.BTN_OPTIONS, it) },
                modifier = Modifier
                    .width(64.dp)
                    .fillMaxHeight(0.76f)
            )

            GamepadShoulderButton(
                label = if (isXbox) "RB" else "R1",
                enabled = enabled,
                accentColor = accentColor,
                onPressedChange = { onButtonChange(HidGamepadMap.BTN_R1, it) },
                modifier = Modifier
                    .weight(0.9f)
                    .fillMaxHeight()
            )
            GamepadAnalogTrigger(
                label = if (isXbox) "RT" else "R2",
                enabled = enabled,
                accentColor = accentColor,
                onValueChange = { onTriggerChange(true, it) },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )
        }

        // Main ergonomic layout
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left cluster: D-Pad + Left Stick / L3 (LS)
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.fillMaxHeight(),
                    contentAlignment = Alignment.TopCenter
                ) {
                    GamepadDpad(
                        size = dpadSize,
                        enabled = enabled,
                        accentColor = accentColor,
                        onDpadChange = onDpadChange,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }

                Column(
                    modifier = Modifier.fillMaxHeight(),
                    verticalArrangement = Arrangement.Bottom,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    GamepadThumbstick(
                        label = "L",
                        size = stickSize,
                        enabled = enabled,
                        accentColor = accentColor,
                        onStickMove = onLeftStickChange
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    GamepadShoulderButton(
                        label = if (isXbox) "LS" else "L3",
                        enabled = enabled,
                        accentColor = accentColor,
                        onPressedChange = { onButtonChange(HidGamepadMap.BTN_L3, it) },
                        modifier = Modifier
                            .width(58.dp)
                            .height(26.dp)
                    )
                }
            }

            // Center Home + Mute + Layout toggle column
            Column(
                modifier = Modifier
                    .padding(horizontal = 6.dp)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                GamepadHomeButton(
                    label = if (isXbox) "XBOX" else "PS",
                    size = 46.dp,
                    enabled = enabled,
                    accentColor = accentColor,
                    onPressedChange = { onButtonChange(HidGamepadMap.BTN_PS, it) }
                )
                Spacer(modifier = Modifier.height(8.dp))
                GamepadShoulderButton(
                    label = "MUTE",
                    enabled = enabled,
                    accentColor = accentColor,
                    onPressedChange = { onButtonChange(HidGamepadMap.BTN_MUTE, it) },
                    modifier = Modifier
                        .width(56.dp)
                        .height(24.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                ControllerStyleSwitchPill(
                    controllerStyle = controllerStyle,
                    accentColor = accentColor,
                    onToggle = onToggleControllerStyle
                )
            }

            // Right cluster: Right Stick / R3 (RS) + Face Buttons
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.fillMaxHeight(),
                    verticalArrangement = Arrangement.Bottom,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    GamepadThumbstick(
                        label = "R",
                        size = stickSize,
                        enabled = enabled,
                        accentColor = accentColor,
                        onStickMove = onRightStickChange
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    GamepadShoulderButton(
                        label = if (isXbox) "RS" else "R3",
                        enabled = enabled,
                        accentColor = accentColor,
                        onPressedChange = { onButtonChange(HidGamepadMap.BTN_R3, it) },
                        modifier = Modifier
                            .width(58.dp)
                            .height(26.dp)
                    )
                }

                Box(
                    modifier = Modifier.fillMaxHeight(),
                    contentAlignment = Alignment.TopCenter
                ) {
                    GamepadFaceButtons(
                        size = faceSize,
                        enabled = enabled,
                        isXbox = isXbox,
                        onButtonChange = onButtonChange,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun PortraitGamepadLayout(
    maxWidth: Dp,
    maxHeight: Dp,
    enabled: Boolean,
    controllerStyle: ControllerStyle,
    accentColor: Color,
    onToggleControllerStyle: (ControllerStyle) -> Unit,
    onButtonChange: (Int, Boolean) -> Unit,
    onDpadChange: (Int) -> Unit,
    onLeftStickChange: (Int, Int) -> Unit,
    onRightStickChange: (Int, Int) -> Unit,
    onTriggerChange: (Boolean, Int) -> Unit
) {
    val isXbox = controllerStyle == ControllerStyle.XBOX
    val dpadSize = minOf(maxWidth * 0.34f, maxHeight * 0.28f).coerceIn(96.dp, 136.dp)
    val faceSize = minOf(maxWidth * 0.35f, maxHeight * 0.29f).coerceIn(100.dp, 142.dp)
    val stickSize = minOf(maxWidth * 0.34f, maxHeight * 0.28f).coerceIn(96.dp, 134.dp)

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Row 1: Shoulder triggers + View/Menu (Create/Opt)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GamepadAnalogTrigger(
                label = if (isXbox) "LT" else "L2",
                enabled = enabled,
                accentColor = accentColor,
                onValueChange = { onTriggerChange(false, it) },
                modifier = Modifier
                    .weight(1.1f)
                    .fillMaxHeight()
            )
            GamepadShoulderButton(
                label = if (isXbox) "LB" else "L1",
                enabled = enabled,
                accentColor = accentColor,
                onPressedChange = { onButtonChange(HidGamepadMap.BTN_L1, it) },
                modifier = Modifier
                    .weight(0.9f)
                    .fillMaxHeight()
            )
            GamepadShoulderButton(
                label = if (isXbox) "VIEW" else "CREATE",
                enabled = enabled,
                accentColor = accentColor,
                onPressedChange = { onButtonChange(HidGamepadMap.BTN_CREATE, it) },
                modifier = Modifier
                    .weight(0.85f)
                    .fillMaxHeight(0.82f)
            )
            GamepadShoulderButton(
                label = if (isXbox) "MENU" else "OPT",
                enabled = enabled,
                accentColor = accentColor,
                onPressedChange = { onButtonChange(HidGamepadMap.BTN_OPTIONS, it) },
                modifier = Modifier
                    .weight(0.85f)
                    .fillMaxHeight(0.82f)
            )
            GamepadShoulderButton(
                label = if (isXbox) "RB" else "R1",
                enabled = enabled,
                accentColor = accentColor,
                onPressedChange = { onButtonChange(HidGamepadMap.BTN_R1, it) },
                modifier = Modifier
                    .weight(0.9f)
                    .fillMaxHeight()
            )
            GamepadAnalogTrigger(
                label = if (isXbox) "RT" else "R2",
                enabled = enabled,
                accentColor = accentColor,
                onValueChange = { onTriggerChange(true, it) },
                modifier = Modifier
                    .weight(1.1f)
                    .fillMaxHeight()
            )
        }

        // Row 2: D-Pad | Touchpad | Face Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            GamepadDpad(
                size = dpadSize,
                enabled = enabled,
                accentColor = accentColor,
                onDpadChange = onDpadChange
            )

            GamepadTouchpadButton(
                label = if (isXbox) "XINPUT PAD" else "TOUCHPAD",
                enabled = enabled,
                accentColor = accentColor,
                onPressedChange = { onButtonChange(HidGamepadMap.BTN_TOUCHPAD, it) },
                modifier = Modifier
                    .weight(1f)
                    .height(dpadSize * 0.72f)
                    .padding(horizontal = 8.dp)
            )

            GamepadFaceButtons(
                size = faceSize,
                enabled = enabled,
                isXbox = isXbox,
                onButtonChange = onButtonChange
            )
        }

        // Row 3: Left Stick | Center Controls | Right Stick
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                GamepadThumbstick(
                    label = "L",
                    size = stickSize,
                    enabled = enabled,
                    accentColor = accentColor,
                    onStickMove = onLeftStickChange
                )
                Spacer(modifier = Modifier.height(6.dp))
                GamepadShoulderButton(
                    label = if (isXbox) "LS" else "L3",
                    enabled = enabled,
                    accentColor = accentColor,
                    onPressedChange = { onButtonChange(HidGamepadMap.BTN_L3, it) },
                    modifier = Modifier
                        .width(64.dp)
                        .height(30.dp)
                )
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GamepadHomeButton(
                    label = if (isXbox) "XBOX" else "PS",
                    size = 48.dp,
                    enabled = enabled,
                    accentColor = accentColor,
                    onPressedChange = { onButtonChange(HidGamepadMap.BTN_PS, it) }
                )
                GamepadShoulderButton(
                    label = "MUTE",
                    enabled = enabled,
                    accentColor = accentColor,
                    onPressedChange = { onButtonChange(HidGamepadMap.BTN_MUTE, it) },
                    modifier = Modifier
                        .width(58.dp)
                        .height(24.dp)
                )
                ControllerStyleSwitchPill(
                    controllerStyle = controllerStyle,
                    accentColor = accentColor,
                    onToggle = onToggleControllerStyle
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                GamepadThumbstick(
                    label = "R",
                    size = stickSize,
                    enabled = enabled,
                    accentColor = accentColor,
                    onStickMove = onRightStickChange
                )
                Spacer(modifier = Modifier.height(6.dp))
                GamepadShoulderButton(
                    label = if (isXbox) "RS" else "R3",
                    enabled = enabled,
                    accentColor = accentColor,
                    onPressedChange = { onButtonChange(HidGamepadMap.BTN_R3, it) },
                    modifier = Modifier
                        .width(64.dp)
                        .height(30.dp)
                )
            }
        }
    }
}

@Composable
private fun ControllerStyleSwitchPill(
    controllerStyle: ControllerStyle,
    accentColor: Color,
    onToggle: (ControllerStyle) -> Unit
) {
    val isXbox = controllerStyle == ControllerStyle.XBOX
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable {
                onToggle(if (isXbox) ControllerStyle.PS5 else ControllerStyle.XBOX)
            },
        shape = RoundedCornerShape(12.dp),
        color = ControlSurface,
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.65f))
    ) {
        Text(
            text = if (isXbox) "XBOX ⇄ PS5" else "PS5 ⇄ XBOX",
            color = accentColor,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun GamepadAnalogTrigger(
    label: String,
    enabled: Boolean,
    accentColor: Color,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    var value by remember { mutableIntStateOf(0) }
    var isPressed by remember { mutableStateOf(false) }

    DisposableEffect(enabled) {
        if (!enabled && (isPressed || value != 0)) {
            isPressed = false
            value = 0
            currentOnValueChange(0)
        }
        onDispose { }
    }

    val fillRatio = value / 255f
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(ControlSurface)
            .border(
                width = 1.dp,
                color = if (isPressed) accentColor else ControlBorder,
                shape = RoundedCornerShape(10.dp)
            )
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitPointerEventScope {
                    var startY = 0f
                    var sliding = false

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isNotEmpty()) {
                            val p = pressed[0]
                            if (!isPressed) {
                                isPressed = true
                                startY = p.position.y
                                sliding = false
                                value = 255
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                currentOnValueChange(255)
                            } else {
                                if (!sliding && abs(p.position.y - startY) > 10f) {
                                    sliding = true
                                }
                                if (sliding) {
                                    val h = size.height.coerceAtLeast(1).toFloat()
                                    val ratio = (p.position.y / h).coerceIn(0.08f, 1f)
                                    val nextVal = (ratio * 255f).roundToInt().coerceIn(20, 255)
                                    if (nextVal != value) {
                                        value = nextVal
                                        currentOnValueChange(nextVal)
                                    }
                                }
                            }
                        } else if (isPressed) {
                            isPressed = false
                            sliding = false
                            value = 0
                            currentOnValueChange(0)
                        }
                        event.changes.forEach { it.consume() }
                    }
                }
            }
    ) {
        if (fillRatio > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(fillRatio)
                    .align(Alignment.BottomCenter)
                    .background(accentColor.copy(alpha = 0.55f))
            )
        }
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = label,
                color = if (isPressed) Color.White else Color(0xFFD5DCE8),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
            if (isPressed) {
                Text(
                    text = "${(fillRatio * 100f).roundToInt()}%",
                    color = Color(0xFFDDE7F9),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun rememberHoldButtonInteraction(
    enabled: Boolean,
    onPressedChange: (Boolean) -> Unit
): Pair<Boolean, Modifier> {
    val haptic = LocalHapticFeedback.current
    val currentOnPressedChange by rememberUpdatedState(onPressedChange)
    var isPressed by remember { mutableStateOf(false) }

    DisposableEffect(enabled) {
        if (!enabled && isPressed) {
            isPressed = false
            currentOnPressedChange(false)
        }
        onDispose { }
    }

    val touchModifier = Modifier.pointerInput(enabled) {
        if (!enabled) return@pointerInput
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                val anyPressed = event.changes.any { it.pressed }
                if (anyPressed && !isPressed) {
                    isPressed = true
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    currentOnPressedChange(true)
                } else if (!anyPressed && isPressed) {
                    isPressed = false
                    currentOnPressedChange(false)
                }
                event.changes.forEach { it.consume() }
            }
        }
    }

    return isPressed to touchModifier
}

@Composable
private fun GamepadShoulderButton(
    label: String,
    enabled: Boolean,
    accentColor: Color,
    onPressedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val (isPressed, touchModifier) = rememberHoldButtonInteraction(enabled, onPressedChange)

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (isPressed) ControlPressed else ControlSurface)
            .border(
                width = 1.dp,
                color = if (isPressed) accentColor else ControlBorder,
                shape = RoundedCornerShape(10.dp)
            )
            .then(touchModifier),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (isPressed) Color.White else Color(0xFFD5DCE8),
            fontSize = if (label.length > 3) 9.sp else 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

@Composable
private fun GamepadTouchpadButton(
    label: String,
    enabled: Boolean,
    accentColor: Color,
    onPressedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val (isPressed, touchModifier) = rememberHoldButtonInteraction(enabled, onPressedChange)
    val barColor = if (enabled) accentColor else Color(0xFF4B5568)

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (isPressed) ControlPressed else Color(0xFF1D212C))
            .border(
                width = 1.dp,
                color = if (isPressed) accentColor else ControlBorder,
                shape = RoundedCornerShape(12.dp)
            )
            .then(touchModifier)
    ) {
        // Lightbar strip along top edge
        Box(
            modifier = Modifier
                .fillMaxWidth(0.75f)
                .height(3.dp)
                .align(Alignment.TopCenter)
                .clip(RoundedCornerShape(bottomStart = 4.dp, bottomEnd = 4.dp))
                .background(barColor)
        )

        Text(
            text = label,
            color = Color(0xFF7D879B),
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

@Composable
private fun GamepadHomeButton(
    label: String,
    size: Dp,
    enabled: Boolean,
    accentColor: Color,
    onPressedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val (isPressed, touchModifier) = rememberHoldButtonInteraction(enabled, onPressedChange)

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(if (isPressed) accentColor else ControlSurface)
            .border(
                width = 1.5.dp,
                color = if (enabled) accentColor.copy(alpha = 0.7f) else ControlBorder,
                shape = CircleShape
            )
            .then(touchModifier),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (isPressed) Color.White else Color(0xFFE1E8F5),
            fontSize = if (label.length > 2) 10.sp else 13.sp,
            fontWeight = FontWeight.ExtraBold
        )
    }
}

@Composable
private fun GamepadDpad(
    size: Dp,
    enabled: Boolean,
    accentColor: Color,
    onDpadChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val currentOnDpadChange by rememberUpdatedState(onDpadChange)
    var activeHat by remember { mutableIntStateOf(HidGamepadMap.HAT_NEUTRAL) }

    DisposableEffect(enabled) {
        if (!enabled && activeHat != HidGamepadMap.HAT_NEUTRAL) {
            activeHat = HidGamepadMap.HAT_NEUTRAL
            currentOnDpadChange(HidGamepadMap.HAT_NEUTRAL)
        }
        onDispose { }
    }

    val upActive = activeHat in setOf(
        HidGamepadMap.HAT_UP,
        HidGamepadMap.HAT_UP_LEFT,
        HidGamepadMap.HAT_UP_RIGHT
    )
    val rightActive = activeHat in setOf(
        HidGamepadMap.HAT_RIGHT,
        HidGamepadMap.HAT_UP_RIGHT,
        HidGamepadMap.HAT_DOWN_RIGHT
    )
    val downActive = activeHat in setOf(
        HidGamepadMap.HAT_DOWN,
        HidGamepadMap.HAT_DOWN_LEFT,
        HidGamepadMap.HAT_DOWN_RIGHT
    )
    val leftActive = activeHat in setOf(
        HidGamepadMap.HAT_LEFT,
        HidGamepadMap.HAT_UP_LEFT,
        HidGamepadMap.HAT_DOWN_LEFT
    )

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Color(0xFF1A1E28))
            .border(1.dp, ControlBorder.copy(alpha = 0.5f), CircleShape)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        val nextHat = if (pressed.isEmpty()) {
                            HidGamepadMap.HAT_NEUTRAL
                        } else {
                            val pos = pressed[0].position
                            val cx = this.size.width / 2f
                            val cy = this.size.height / 2f
                            val dx = pos.x - cx
                            val dy = pos.y - cy
                            val minDist = minOf(this.size.width, this.size.height) * 0.08f
                            if (hypot(dx, dy) < minDist) {
                                HidGamepadMap.HAT_NEUTRAL
                            } else {
                                // 0° = Up, clockwise in 45° sectors
                                val deg = ((atan2(dy, dx) * 180.0 / PI) + 90.0 + 360.0) % 360.0
                                (((deg + 22.5) / 45.0).toInt()) % 8
                            }
                        }

                        if (nextHat != activeHat) {
                            if (nextHat != HidGamepadMap.HAT_NEUTRAL) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                            activeHat = nextHat
                            currentOnDpadChange(nextHat)
                        }
                        event.changes.forEach { it.consume() }
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(6.dp)) {
            val w = this.size.width
            val h = this.size.height
            val cx = w / 2f
            val cy = h / 2f
            val armLen = w * 0.36f
            val armHalf = w * 0.145f
            val gap = w * 0.08f

            fun drawArm(path: Path, active: Boolean) {
                drawPath(
                    path = path,
                    color = if (active) accentColor else ControlSurface
                )
                drawPath(
                    path = path,
                    color = if (active) Color.White.copy(alpha = 0.8f) else ControlBorder,
                    style = Stroke(width = 1.5.dp.toPx(), join = StrokeJoin.Round)
                )
            }

            // Up arm
            drawArm(
                Path().apply {
                    moveTo(cx - armHalf, cy - gap - armLen)
                    lineTo(cx + armHalf, cy - gap - armLen)
                    lineTo(cx + armHalf, cy - gap - armHalf * 0.6f)
                    lineTo(cx, cy - gap)
                    lineTo(cx - armHalf, cy - gap - armHalf * 0.6f)
                    close()
                },
                upActive
            )

            // Down arm
            drawArm(
                Path().apply {
                    moveTo(cx - armHalf, cy + gap + armLen)
                    lineTo(cx + armHalf, cy + gap + armLen)
                    lineTo(cx + armHalf, cy + gap + armHalf * 0.6f)
                    lineTo(cx, cy + gap)
                    lineTo(cx - armHalf, cy + gap + armHalf * 0.6f)
                    close()
                },
                downActive
            )

            // Left arm
            drawArm(
                Path().apply {
                    moveTo(cx - gap - armLen, cy - armHalf)
                    lineTo(cx - gap - armLen, cy + armHalf)
                    lineTo(cx - gap - armHalf * 0.6f, cy + armHalf)
                    lineTo(cx - gap, cy)
                    lineTo(cx - gap - armHalf * 0.6f, cy - armHalf)
                    close()
                },
                leftActive
            )

            // Right arm
            drawArm(
                Path().apply {
                    moveTo(cx + gap + armLen, cy - armHalf)
                    lineTo(cx + gap + armLen, cy + armHalf)
                    lineTo(cx + gap + armHalf * 0.6f, cy + armHalf)
                    lineTo(cx + gap, cy)
                    lineTo(cx + gap + armHalf * 0.6f, cy - armHalf)
                    close()
                },
                rightActive
            )
        }
    }
}

@Composable
private fun GamepadFaceButtons(
    size: Dp,
    enabled: Boolean,
    isXbox: Boolean,
    onButtonChange: (Int, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val btnSize = size * 0.34f

    Box(modifier = modifier.size(size)) {
        GamepadFaceButton(
            symbol = FaceSymbol.TRIANGLE,
            xboxLabel = if (isXbox) "Y" else null,
            symbolColor = if (isXbox) XboxYAmber else PsTriangleGreen,
            size = btnSize,
            enabled = enabled,
            onPressedChange = { onButtonChange(HidGamepadMap.BTN_TRIANGLE, it) },
            modifier = Modifier.align(Alignment.TopCenter)
        )
        GamepadFaceButton(
            symbol = FaceSymbol.CIRCLE,
            xboxLabel = if (isXbox) "B" else null,
            symbolColor = if (isXbox) XboxBRed else PsCircleRed,
            size = btnSize,
            enabled = enabled,
            onPressedChange = { onButtonChange(HidGamepadMap.BTN_CIRCLE, it) },
            modifier = Modifier.align(Alignment.CenterEnd)
        )
        GamepadFaceButton(
            symbol = FaceSymbol.CROSS,
            xboxLabel = if (isXbox) "A" else null,
            symbolColor = if (isXbox) XboxAGreen else PsCrossBlue,
            size = btnSize,
            enabled = enabled,
            onPressedChange = { onButtonChange(HidGamepadMap.BTN_CROSS, it) },
            modifier = Modifier.align(Alignment.BottomCenter)
        )
        GamepadFaceButton(
            symbol = FaceSymbol.SQUARE,
            xboxLabel = if (isXbox) "X" else null,
            symbolColor = if (isXbox) XboxXBlue else PsSquarePink,
            size = btnSize,
            enabled = enabled,
            onPressedChange = { onButtonChange(HidGamepadMap.BTN_SQUARE, it) },
            modifier = Modifier.align(Alignment.CenterStart)
        )
    }
}

@Composable
private fun GamepadFaceButton(
    symbol: FaceSymbol,
    xboxLabel: String?,
    symbolColor: Color,
    size: Dp,
    enabled: Boolean,
    onPressedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val (isPressed, touchModifier) = rememberHoldButtonInteraction(enabled, onPressedChange)

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(if (isPressed) symbolColor.copy(alpha = 0.28f) else ControlSurface)
            .border(
                width = 1.5.dp,
                color = if (isPressed) symbolColor else ControlBorder,
                shape = CircleShape
            )
            .then(touchModifier),
        contentAlignment = Alignment.Center
    ) {
        if (xboxLabel != null) {
            Text(
                text = xboxLabel,
                color = symbolColor,
                fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold
            )
        } else {
            Canvas(modifier = Modifier.fillMaxSize(0.46f)) {
                val stroke = Stroke(
                    width = 2.4.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
                val w = this.size.width
                val h = this.size.height
                when (symbol) {
                    FaceSymbol.TRIANGLE -> {
                        val path = Path().apply {
                            moveTo(w / 2f, 0f)
                            lineTo(w, h)
                            lineTo(0f, h)
                            close()
                        }
                        drawPath(path, color = symbolColor, style = stroke)
                    }

                    FaceSymbol.CIRCLE -> {
                        drawCircle(
                            color = symbolColor,
                            radius = minOf(w, h) / 2f,
                            style = stroke
                        )
                    }

                    FaceSymbol.CROSS -> {
                        drawLine(
                            color = symbolColor,
                            start = Offset(0f, 0f),
                            end = Offset(w, h),
                            strokeWidth = stroke.width,
                            cap = StrokeCap.Round
                        )
                        drawLine(
                            color = symbolColor,
                            start = Offset(w, 0f),
                            end = Offset(0f, h),
                            strokeWidth = stroke.width,
                            cap = StrokeCap.Round
                        )
                    }

                    FaceSymbol.SQUARE -> {
                        drawRoundRect(
                            color = symbolColor,
                            topLeft = Offset.Zero,
                            size = Size(w, h),
                            cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
                            style = stroke
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GamepadThumbstick(
    label: String,
    size: Dp,
    enabled: Boolean,
    accentColor: Color,
    onStickMove: (x: Int, y: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentOnStickMove by rememberUpdatedState(onStickMove)
    var normX by remember { mutableFloatStateOf(0f) }
    var normY by remember { mutableFloatStateOf(0f) }
    var isActive by remember { mutableStateOf(false) }

    DisposableEffect(enabled) {
        if (!enabled && isActive) {
            isActive = false
            normX = 0f
            normY = 0f
            currentOnStickMove(HidGamepadMap.AXIS_CENTER, HidGamepadMap.AXIS_CENTER)
        }
        onDispose { }
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Color(0xFF1A1E29))
            .border(
                width = 1.5.dp,
                color = if (isActive) accentColor else ControlBorder,
                shape = CircleShape
            )
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitPointerEventScope {
                    val deadzone = 0.06f
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isNotEmpty()) {
                            isActive = true
                            val pos = pressed[0].position
                            val cx = this.size.width / 2f
                            val cy = this.size.height / 2f
                            val maxRadius = (minOf(this.size.width, this.size.height) * 0.36f)
                                .coerceAtLeast(1f)

                            var dx = (pos.x - cx) / maxRadius
                            var dy = (pos.y - cy) / maxRadius
                            val mag = hypot(dx, dy)
                            if (mag > 1f) {
                                dx /= mag
                                dy /= mag
                            }

                            val outX = if (mag < deadzone) 0f else dx
                            val outY = if (mag < deadzone) 0f else dy
                            normX = outX
                            normY = outY

                            val axisX = ((outX + 1f) * 0.5f * 255f).roundToInt().coerceIn(0, 255)
                            val axisY = ((outY + 1f) * 0.5f * 255f).roundToInt().coerceIn(0, 255)
                            currentOnStickMove(axisX, axisY)
                        } else if (isActive) {
                            isActive = false
                            normX = 0f
                            normY = 0f
                            currentOnStickMove(HidGamepadMap.AXIS_CENTER, HidGamepadMap.AXIS_CENTER)
                        }
                        event.changes.forEach { it.consume() }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = this.size.width / 2f
            val cy = this.size.height / 2f
            val r = minOf(this.size.width, this.size.height) / 2f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF10131A), Color(0xFF1D2230)),
                    center = Offset(cx, cy),
                    radius = r
                ),
                radius = r * 0.9f
            )
            drawLine(
                color = ControlBorder.copy(alpha = 0.35f),
                start = Offset(cx - r * 0.55f, cy),
                end = Offset(cx + r * 0.55f, cy),
                strokeWidth = 1.dp.toPx()
            )
            drawLine(
                color = ControlBorder.copy(alpha = 0.35f),
                start = Offset(cx, cy - r * 0.55f),
                end = Offset(cx, cy + r * 0.55f),
                strokeWidth = 1.dp.toPx()
            )
        }

        // Floating thumbstick cap
        val travelFraction = 0.30f
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            val travelPx = with(LocalDensity.current) {
                (minOf(maxWidth, maxHeight) * travelFraction).toPx()
            }
            val capSize = minOf(maxWidth, maxHeight) * 0.46f

            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            x = (normX * travelPx).roundToInt(),
                            y = (normY * travelPx).roundToInt()
                        )
                    }
                    .size(capSize)
                    .clip(CircleShape)
                    .background(if (isActive) ControlPressed else Color(0xFF2B3142))
                    .border(
                        width = 1.5.dp,
                        color = if (isActive) accentColor else Color(0xFF4A536A),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    color = if (isActive) Color.White else Color(0xFFB4BED1),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
