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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.input.pointer.PointerId
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
import kotlin.math.pow
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
    isFullscreen: Boolean = false,
    onToggleFullscreen: () -> Unit = {},
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
                        !hidArmed && transportMode == TransportMode.USB_BRIDGE ->
                            "USB Bridge is waiting for PC. Plug in USB cable (USB Tethering or USB Debugging) and run PocketPadXboxBridge.exe."
                        !hidArmed && transportMode == TransportMode.USB_ROOT ->
                            "USB HID is DISARMED. Arm it in the Connect tab to use the Gamepad."
                        else ->
                            "Gamepad HID interface (/dev/hidg2) was not exposed by this kernel. Use Bluetooth or USB Bridge mode."
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
                    isFullscreen = isFullscreen,
                    onToggleFullscreen = onToggleFullscreen,
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
                    isFullscreen = isFullscreen,
                    onToggleFullscreen = onToggleFullscreen,
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
private fun LeftStickCluster(
    stickSize: Dp,
    label: String,
    stickClickLabel: String,
    enabled: Boolean,
    accentColor: Color,
    onStickMove: (Int, Int) -> Unit,
    onStickClickChange: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxHeight(),
        verticalArrangement = Arrangement.Bottom,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        GamepadThumbstick(
            label = label,
            size = stickSize,
            enabled = enabled,
            accentColor = accentColor,
            onStickMove = onStickMove
        )
        Spacer(modifier = Modifier.height(4.dp))
        GamepadShoulderButton(
            label = stickClickLabel,
            enabled = enabled,
            accentColor = accentColor,
            onPressedChange = onStickClickChange,
            modifier = Modifier
                .width(58.dp)
                .height(26.dp)
        )
    }
}

@Composable
private fun DpadCluster(
    dpadSize: Dp,
    enabled: Boolean,
    accentColor: Color,
    onDpadChange: (Int) -> Unit
) {
    Box(
        modifier = Modifier.fillMaxHeight(),
        contentAlignment = Alignment.Center
    ) {
        GamepadDpad(
            size = dpadSize,
            enabled = enabled,
            accentColor = accentColor,
            onDpadChange = onDpadChange
        )
    }
}

@Composable
private fun LandscapeGamepadLayout(
    maxWidth: Dp,
    maxHeight: Dp,
    enabled: Boolean,
    controllerStyle: ControllerStyle,
    accentColor: Color,
    isFullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onToggleControllerStyle: (ControllerStyle) -> Unit,
    onButtonChange: (Int, Boolean) -> Unit,
    onDpadChange: (Int) -> Unit,
    onLeftStickChange: (Int, Int) -> Unit,
    onRightStickChange: (Int, Int) -> Unit,
    onTriggerChange: (Boolean, Int) -> Unit
) {
    val isXbox = controllerStyle == ControllerStyle.XBOX
    val topRowHeight = (maxHeight * 0.21f).coerceIn(46.dp, 74.dp)
    val dpadSize = minOf(maxHeight * 0.44f, maxWidth * 0.17f).coerceIn(88.dp, 134.dp)
    val faceSize = minOf(maxHeight * 0.50f, maxWidth * 0.20f).coerceIn(100.dp, 150.dp)
    val stickSize = minOf(maxHeight * 0.56f, maxWidth * 0.22f).coerceIn(104.dp, 158.dp)

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
            // Left cluster: Xbox places Left Stick outer-left; PS5 places D-Pad outer-left
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isXbox) {
                    LeftStickCluster(
                        stickSize = stickSize,
                        label = "L",
                        stickClickLabel = "LS",
                        enabled = enabled,
                        accentColor = accentColor,
                        onStickMove = onLeftStickChange,
                        onStickClickChange = { onButtonChange(HidGamepadMap.BTN_L3, it) }
                    )
                    DpadCluster(
                        dpadSize = dpadSize,
                        enabled = enabled,
                        accentColor = accentColor,
                        onDpadChange = onDpadChange
                    )
                } else {
                    DpadCluster(
                        dpadSize = dpadSize,
                        enabled = enabled,
                        accentColor = accentColor,
                        onDpadChange = onDpadChange
                    )
                    LeftStickCluster(
                        stickSize = stickSize,
                        label = "L",
                        stickClickLabel = "L3",
                        enabled = enabled,
                        accentColor = accentColor,
                        onStickMove = onLeftStickChange,
                        onStickClickChange = { onButtonChange(HidGamepadMap.BTN_L3, it) }
                    )
                }
            }

            // Center Home + Mute + Layout + Fullscreen toggle column
            Column(
                modifier = Modifier
                    .padding(horizontal = 6.dp)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                GamepadHomeButton(
                    label = if (isXbox) "XBOX" else "PS",
                    size = 44.dp,
                    enabled = enabled,
                    accentColor = accentColor,
                    onPressedChange = { onButtonChange(HidGamepadMap.BTN_PS, it) }
                )
                Spacer(modifier = Modifier.height(6.dp))
                GamepadShoulderButton(
                    label = "MUTE",
                    enabled = enabled,
                    accentColor = accentColor,
                    onPressedChange = { onButtonChange(HidGamepadMap.BTN_MUTE, it) },
                    modifier = Modifier
                        .width(56.dp)
                        .height(24.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))
                ControllerStyleSwitchPill(
                    controllerStyle = controllerStyle,
                    accentColor = accentColor,
                    onToggle = onToggleControllerStyle
                )
                Spacer(modifier = Modifier.height(6.dp))
                FullscreenTogglePill(
                    isFullscreen = isFullscreen,
                    accentColor = accentColor,
                    onToggle = onToggleFullscreen
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
                LeftStickCluster(
                    stickSize = stickSize,
                    label = "R",
                    stickClickLabel = if (isXbox) "RS" else "R3",
                    enabled = enabled,
                    accentColor = accentColor,
                    onStickMove = onRightStickChange,
                    onStickClickChange = { onButtonChange(HidGamepadMap.BTN_R3, it) }
                )

                Box(
                    modifier = Modifier.fillMaxHeight(),
                    contentAlignment = Alignment.Center
                ) {
                    GamepadFaceButtons(
                        size = faceSize,
                        enabled = enabled,
                        isXbox = isXbox,
                        onButtonChange = onButtonChange
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
    isFullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onToggleControllerStyle: (ControllerStyle) -> Unit,
    onButtonChange: (Int, Boolean) -> Unit,
    onDpadChange: (Int) -> Unit,
    onLeftStickChange: (Int, Int) -> Unit,
    onRightStickChange: (Int, Int) -> Unit,
    onTriggerChange: (Boolean, Int) -> Unit
) {
    val isXbox = controllerStyle == ControllerStyle.XBOX
    val dpadSize = minOf(maxWidth * 0.34f, maxHeight * 0.28f).coerceIn(96.dp, 136.dp)
    val faceSize = minOf(maxWidth * 0.36f, maxHeight * 0.30f).coerceIn(104.dp, 146.dp)
    val stickSize = minOf(maxWidth * 0.38f, maxHeight * 0.32f).coerceIn(108.dp, 150.dp)

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
                FullscreenTogglePill(
                    isFullscreen = isFullscreen,
                    accentColor = accentColor,
                    onToggle = onToggleFullscreen
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
private fun FullscreenTogglePill(
    isFullscreen: Boolean,
    accentColor: Color,
    onToggle: () -> Unit
) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onToggle),
        shape = RoundedCornerShape(12.dp),
        color = if (isFullscreen) accentColor.copy(alpha = 0.22f) else ControlSurface,
        border = BorderStroke(1.dp, if (isFullscreen) accentColor else ControlBorder)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Icon(
                imageVector = if (isFullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                contentDescription = if (isFullscreen) "Show tabs" else "Fullscreen gamepad",
                tint = if (isFullscreen) accentColor else Color(0xFFD5DCE8),
                modifier = Modifier.size(13.dp)
            )
            Text(
                text = if (isFullscreen) "TABS" else "FULL",
                color = if (isFullscreen) accentColor else Color(0xFFD5DCE8),
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold
            )
        }
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
                    var activePointerId: PointerId? = null
                    var startY = 0f
                    var sliding = false

                    while (true) {
                        val event = awaitPointerEvent()
                        var tracked = activePointerId?.let { id ->
                            event.changes.firstOrNull { it.id == id && it.pressed }
                        }

                        if (tracked == null) {
                            if (activePointerId != null) {
                                activePointerId = null
                                sliding = false
                                isPressed = false
                                if (value != 0) {
                                    value = 0
                                    currentOnValueChange(0)
                                }
                            }
                            tracked = event.changes.firstOrNull { it.pressed }
                            if (tracked != null) {
                                activePointerId = tracked.id
                                startY = tracked.position.y
                                sliding = false
                                isPressed = true
                                value = 255
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                currentOnValueChange(255)
                            }
                        } else {
                            if (!sliding && abs(tracked.position.y - startY) > 28f) {
                                sliding = true
                            }
                            if (sliding) {
                                val pullUp = (startY - tracked.position.y).coerceAtLeast(0f)
                                val rangePx = (size.height.coerceAtLeast(1) * 0.75f).coerceAtLeast(32f)
                                val ratio = (1f - (pullUp / rangePx)).coerceIn(0.10f, 1f)
                                val nextVal = (ratio * 255f).roundToInt().coerceIn(25, 255)
                                if (nextVal != value) {
                                    value = nextVal
                                    currentOnValueChange(nextVal)
                                }
                            }
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
                    var activePointerId: PointerId? = null

                    while (true) {
                        val event = awaitPointerEvent()
                        var tracked = activePointerId?.let { id ->
                            event.changes.firstOrNull { it.id == id && it.pressed }
                        }
                        if (tracked == null) {
                            activePointerId = null
                            tracked = event.changes.firstOrNull { it.pressed }
                            if (tracked != null) {
                                activePointerId = tracked.id
                            }
                        }

                        val nextHat = if (tracked == null) {
                            HidGamepadMap.HAT_NEUTRAL
                        } else {
                            val pos = tracked.position
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
    val btnSize = size * 0.37f

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
    var originOffsetX by remember { mutableFloatStateOf(0f) }
    var originOffsetY by remember { mutableFloatStateOf(0f) }
    var normX by remember { mutableFloatStateOf(0f) }
    var normY by remember { mutableFloatStateOf(0f) }
    var isActive by remember { mutableStateOf(false) }

    DisposableEffect(enabled) {
        if (!enabled && isActive) {
            isActive = false
            originOffsetX = 0f
            originOffsetY = 0f
            normX = 0f
            normY = 0f
            currentOnStickMove(HidGamepadMap.AXIS_CENTER, HidGamepadMap.AXIS_CENTER)
        }
        onDispose {
            if (isActive) {
                currentOnStickMove(HidGamepadMap.AXIS_CENTER, HidGamepadMap.AXIS_CENTER)
            }
        }
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
                    val responseExponent = 1.35f
                    var activePointerId: PointerId? = null
                    var originX = 0f
                    var originY = 0f
                    var lastSentX = HidGamepadMap.AXIS_CENTER
                    var lastSentY = HidGamepadMap.AXIS_CENTER

                    while (true) {
                        val event = awaitPointerEvent()
                        var tracked = activePointerId?.let { id ->
                            event.changes.firstOrNull { it.id == id && it.pressed }
                        }

                        if (tracked == null) {
                            if (activePointerId != null) {
                                activePointerId = null
                                isActive = false
                                originOffsetX = 0f
                                originOffsetY = 0f
                                normX = 0f
                                normY = 0f
                                if (lastSentX != HidGamepadMap.AXIS_CENTER ||
                                    lastSentY != HidGamepadMap.AXIS_CENTER
                                ) {
                                    lastSentX = HidGamepadMap.AXIS_CENTER
                                    lastSentY = HidGamepadMap.AXIS_CENTER
                                    currentOnStickMove(lastSentX, lastSentY)
                                }
                            }
                            val down = event.changes.firstOrNull { it.pressed }
                            if (down != null) {
                                val w = this.size.width.toFloat().coerceAtLeast(1f)
                                val h = this.size.height.toFloat().coerceAtLeast(1f)
                                val cx = w / 2f
                                val cy = h / 2f
                                val maxOriginShift = minOf(w, h) * 0.22f

                                activePointerId = down.id
                                originX = down.position.x.coerceIn(cx - maxOriginShift, cx + maxOriginShift)
                                originY = down.position.y.coerceIn(cy - maxOriginShift, cy + maxOriginShift)
                                originOffsetX = originX - cx
                                originOffsetY = originY - cy
                                isActive = true
                                normX = 0f
                                normY = 0f
                                if (lastSentX != HidGamepadMap.AXIS_CENTER ||
                                    lastSentY != HidGamepadMap.AXIS_CENTER
                                ) {
                                    lastSentX = HidGamepadMap.AXIS_CENTER
                                    lastSentY = HidGamepadMap.AXIS_CENTER
                                    currentOnStickMove(lastSentX, lastSentY)
                                }
                            }
                        } else {
                            val w = this.size.width.toFloat().coerceAtLeast(1f)
                            val h = this.size.height.toFloat().coerceAtLeast(1f)
                            val maxRadius = (minOf(w, h) * 0.40f).coerceAtLeast(1f)

                            val rawDx = (tracked.position.x - originX) / maxRadius
                            val rawDy = (tracked.position.y - originY) / maxRadius
                            val mag = hypot(rawDx, rawDy)

                            val axisX: Int
                            val axisY: Int
                            if (mag <= deadzone) {
                                normX = 0f
                                normY = 0f
                                axisX = HidGamepadMap.AXIS_CENTER
                                axisY = HidGamepadMap.AXIS_CENTER
                            } else {
                                val clampedMag = mag.coerceAtMost(1f)
                                val ux = rawDx / mag
                                val uy = rawDy / mag
                                normX = ux * clampedMag
                                normY = uy * clampedMag

                                val scaledMag = ((clampedMag - deadzone) / (1f - deadzone)).coerceIn(0f, 1f)
                                val curvedMag = scaledMag.pow(responseExponent)
                                val outX = ux * curvedMag
                                val outY = uy * curvedMag
                                axisX = ((outX + 1f) * 0.5f * 255f).roundToInt().coerceIn(0, 255)
                                axisY = ((outY + 1f) * 0.5f * 255f).roundToInt().coerceIn(0, 255)
                            }

                            if (axisX != lastSentX || axisY != lastSentY) {
                                lastSentX = axisX
                                lastSentY = axisY
                                currentOnStickMove(axisX, axisY)
                            }
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
                radius = r * 0.92f
            )
            val gx = cx + originOffsetX
            val gy = cy + originOffsetY
            val guideRadius = r * 0.62f
            drawCircle(
                color = if (isActive) accentColor.copy(alpha = 0.28f) else ControlBorder.copy(alpha = 0.30f),
                center = Offset(gx, gy),
                radius = guideRadius,
                style = Stroke(width = 1.dp.toPx())
            )
            drawLine(
                color = ControlBorder.copy(alpha = 0.35f),
                start = Offset(gx - guideRadius * 0.7f, gy),
                end = Offset(gx + guideRadius * 0.7f, gy),
                strokeWidth = 1.dp.toPx()
            )
            drawLine(
                color = ControlBorder.copy(alpha = 0.35f),
                start = Offset(gx, gy - guideRadius * 0.7f),
                end = Offset(gx, gy + guideRadius * 0.7f),
                strokeWidth = 1.dp.toPx()
            )
        }

        // Floating thumbstick cap anchored to dynamic origin
        val travelFraction = 0.28f
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            val travelPx = with(LocalDensity.current) {
                (minOf(maxWidth, maxHeight) * travelFraction).toPx()
            }
            val capSize = minOf(maxWidth, maxHeight) * 0.44f

            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            x = (originOffsetX + normX * travelPx).roundToInt(),
                            y = (originOffsetY + normY * travelPx).roundToInt()
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
