package com.pocketkeyboard.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.UsbOff
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale
import com.pocketkeyboard.hid.BluetoothHidSnapshot
import com.pocketkeyboard.hid.BluetoothHidState
import com.pocketkeyboard.hid.GadgetBackend
import com.pocketkeyboard.hid.GadgetEnvironment
import com.pocketkeyboard.ui.theme.ArmedGreen
import com.pocketkeyboard.ui.theme.DisarmedRed
import com.pocketkeyboard.ui.theme.WarningAmber
import com.pocketkeyboard.viewmodel.ConnectionStatus
import com.pocketkeyboard.viewmodel.ControllerStyle
import com.pocketkeyboard.viewmodel.TransportMode

/**
 * Dedicated Connect page for switching between No-Root Bluetooth HID and Root USB HID,
 * pairing/connecting hosts, and configuring keyboard/trackpad options.
 */
@Composable
fun StatusCard(
    transportMode: TransportMode,
    bluetoothSnapshot: BluetoothHidSnapshot,
    rootAvailable: Boolean,
    gadgetEnvironment: GadgetEnvironment?,
    hidArmed: Boolean,
    holdModifiersMode: Boolean,
    verticalKeyboardTrackpad: Boolean,
    trackpadSensitivity: Float,
    controllerStyle: ControllerStyle,
    connectionStatus: ConnectionStatus,
    isLoading: Boolean,
    errorMessage: String?,
    onSelectTransportMode: (TransportMode) -> Unit,
    onToggleBluetoothHid: (Boolean) -> Unit,
    onConnectBluetoothHost: (String) -> Unit,
    onDisconnectBluetoothHost: () -> Unit,
    onRefreshBluetooth: () -> Unit,
    onRequestBluetoothPermissions: () -> Unit,
    onRequestEnableBluetooth: () -> Unit,
    onMakeDiscoverable: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    onToggleHid: (Boolean) -> Unit,
    onToggleHoldModifiers: (Boolean) -> Unit,
    onToggleVerticalKeyboardTrackpad: (Boolean) -> Unit,
    onTrackpadSensitivityChange: (Float) -> Unit,
    onSelectControllerStyle: (ControllerStyle) -> Unit,
    onRefreshEnvironment: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TransportModeSelector(
            selectedMode = transportMode,
            enabled = !isLoading,
            onSelectMode = onSelectTransportMode
        )

        when (transportMode) {
            TransportMode.BLUETOOTH -> {
                BluetoothStatusSection(
                    snapshot = bluetoothSnapshot,
                    errorMessage = errorMessage,
                    isLoading = isLoading,
                    onToggleBluetoothHid = onToggleBluetoothHid,
                    onConnectBluetoothHost = onConnectBluetoothHost,
                    onDisconnectBluetoothHost = onDisconnectBluetoothHost,
                    onRefreshBluetooth = onRefreshBluetooth,
                    onRequestBluetoothPermissions = onRequestBluetoothPermissions,
                    onRequestEnableBluetooth = onRequestEnableBluetooth,
                    onMakeDiscoverable = onMakeDiscoverable,
                    onOpenBluetoothSettings = onOpenBluetoothSettings
                )
            }

            TransportMode.USB_ROOT -> {
                UsbRootStatusSection(
                    rootAvailable = rootAvailable,
                    gadgetEnvironment = gadgetEnvironment,
                    hidArmed = hidArmed,
                    connectionStatus = connectionStatus,
                    isLoading = isLoading,
                    errorMessage = errorMessage,
                    onToggleHid = onToggleHid,
                    onRefreshEnvironment = onRefreshEnvironment
                )
            }
        }

        // Modifier key behavior card (Toggle vs Hold)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Modifier Key Mode",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (holdModifiersMode) {
                            "Hold (Momentary): Shift, Ctrl, Alt, Win stay pressed only while held"
                        } else {
                            "Toggle (Sticky): Tap Shift, Ctrl, Alt, Win once to lock, again to release"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Switch(
                        checked = holdModifiersMode,
                        onCheckedChange = onToggleHoldModifiers
                    )
                    Text(
                        text = if (holdModifiersMode) "HOLD" else "TOGGLE",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // Portrait Full Keyboard trackpad toggle card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Enable trackpad on vertical full keyboard",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Shows a trackpad below Full Keyboard in portrait mode instead of stretching keys",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Switch(
                    checked = verticalKeyboardTrackpad,
                    onCheckedChange = onToggleVerticalKeyboardTrackpad
                )
            }
        }

        // Trackpad pointer sensitivity slider card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Trackpad Sensitivity",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = String.format(Locale.US, "%.2fx", trackpadSensitivity),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Slider(
                    value = trackpadSensitivity,
                    onValueChange = onTrackpadSensitivityChange,
                    valueRange = 0.5f..2.5f,
                    steps = 7
                )
            }
        }

        // Controller layout card (Xbox vs PS5)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Controller Layout",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "Choose button labels on the Gamepad tab. Use PocketPadXboxBridge.exe on Windows for full XInput game support.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (controllerStyle == ControllerStyle.XBOX) {
                        Button(
                            onClick = { onSelectControllerStyle(ControllerStyle.XBOX) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Xbox (A B X Y)")
                        }
                        OutlinedButton(
                            onClick = { onSelectControllerStyle(ControllerStyle.PS5) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("PS5 (✕ ○ □ △)")
                        }
                    } else {
                        OutlinedButton(
                            onClick = { onSelectControllerStyle(ControllerStyle.XBOX) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Xbox (A B X Y)")
                        }
                        Button(
                            onClick = { onSelectControllerStyle(ControllerStyle.PS5) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("PS5 (✕ ○ □ △)")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TransportModeSelector(
    selectedMode: TransportMode,
    enabled: Boolean,
    onSelectMode: (TransportMode) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            TransportOptionPill(
                title = "Bluetooth",
                subtitle = "No Root • Wireless",
                icon = Icons.Filled.Bluetooth,
                selected = selectedMode == TransportMode.BLUETOOTH,
                enabled = enabled,
                onClick = { onSelectMode(TransportMode.BLUETOOTH) },
                modifier = Modifier.weight(1f)
            )
            TransportOptionPill(
                title = "USB HID",
                subtitle = "Root • Cable / BIOS",
                icon = Icons.Filled.Usb,
                selected = selectedMode == TransportMode.USB_ROOT,
                enabled = enabled,
                onClick = { onSelectMode(TransportMode.USB_ROOT) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun TransportOptionPill(
    title: String,
    subtitle: String,
    icon: ImageVector,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        Color.Transparent
    }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val border = if (selected) {
        BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
    } else {
        null
    }

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = bgColor,
        border = border
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = contentColor
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun BluetoothStatusSection(
    snapshot: BluetoothHidSnapshot,
    errorMessage: String?,
    isLoading: Boolean,
    onToggleBluetoothHid: (Boolean) -> Unit,
    onConnectBluetoothHost: (String) -> Unit,
    onDisconnectBluetoothHost: () -> Unit,
    onRefreshBluetooth: () -> Unit,
    onRequestBluetoothPermissions: () -> Unit,
    onRequestEnableBluetooth: () -> Unit,
    onMakeDiscoverable: () -> Unit,
    onOpenBluetoothSettings: () -> Unit
) {
    val cardColor by animateColorAsState(
        targetValue = when (snapshot.state) {
            BluetoothHidState.CONNECTED -> MaterialTheme.colorScheme.primaryContainer
            BluetoothHidState.READY,
            BluetoothHidState.CONNECTING,
            BluetoothHidState.REGISTERING -> MaterialTheme.colorScheme.secondaryContainer
            BluetoothHidState.ERROR,
            BluetoothHidState.UNSUPPORTED,
            BluetoothHidState.NO_ADAPTER -> MaterialTheme.colorScheme.errorContainer
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        label = "btCardColor"
    )

    val btServiceActive = snapshot.state in setOf(
        BluetoothHidState.REGISTERING,
        BluetoothHidState.READY,
        BluetoothHidState.CONNECTING,
        BluetoothHidState.CONNECTED
    )
    val canToggleBtService = !isLoading && snapshot.state !in setOf(
        BluetoothHidState.UNSUPPORTED,
        BluetoothHidState.NO_ADAPTER,
        BluetoothHidState.PERMISSION_REQUIRED,
        BluetoothHidState.BLUETOOTH_OFF
    )
    val btReadyForDevices = snapshot.state !in setOf(
        BluetoothHidState.UNSUPPORTED,
        BluetoothHidState.NO_ADAPTER,
        BluetoothHidState.PERMISSION_REQUIRED,
        BluetoothHidState.BLUETOOTH_OFF
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cardColor)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = when (snapshot.state) {
                            BluetoothHidState.CONNECTED -> Icons.Filled.BluetoothConnected
                            BluetoothHidState.BLUETOOTH_OFF,
                            BluetoothHidState.NO_ADAPTER,
                            BluetoothHidState.UNSUPPORTED -> Icons.Filled.BluetoothDisabled
                            else -> Icons.Filled.Bluetooth
                        },
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = when (snapshot.state) {
                            BluetoothHidState.CONNECTED -> ArmedGreen
                            BluetoothHidState.READY,
                            BluetoothHidState.CONNECTING -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "PocketKeyboard",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = when (snapshot.state) {
                                BluetoothHidState.CONNECTED ->
                                    "Connected to ${snapshot.connectedDeviceName ?: "Host"}"
                                BluetoothHidState.CONNECTING -> "Connecting to host..."
                                BluetoothHidState.READY -> "Ready — waiting for host"
                                BluetoothHidState.REGISTERING -> "Starting Bluetooth HID..."
                                BluetoothHidState.BLUETOOTH_OFF -> "Bluetooth is turned off"
                                BluetoothHidState.PERMISSION_REQUIRED -> "Bluetooth permission needed"
                                BluetoothHidState.NO_ADAPTER -> "No Bluetooth adapter found"
                                BluetoothHidState.UNSUPPORTED -> "Requires Android 9 or higher"
                                BluetoothHidState.ERROR -> "Bluetooth HID Error"
                                BluetoothHidState.IDLE -> "Bluetooth HID Inactive"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = when (snapshot.state) {
                            BluetoothHidState.CONNECTED -> "ACTIVE"
                            BluetoothHidState.READY -> "READY"
                            BluetoothHidState.REGISTERING,
                            BluetoothHidState.CONNECTING -> "WAIT"
                            else -> "OFF"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = when (snapshot.state) {
                            BluetoothHidState.CONNECTED -> ArmedGreen
                            BluetoothHidState.READY -> MaterialTheme.colorScheme.primary
                            else -> DisarmedRed
                        }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = btServiceActive,
                        onCheckedChange = onToggleBluetoothHid,
                        enabled = canToggleBtService,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = ArmedGreen,
                            checkedTrackColor = ArmedGreen.copy(alpha = 0.5f)
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = ArmedGreen
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "No Root Required",
                        style = MaterialTheme.typography.labelMedium
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = when (snapshot.state) {
                            BluetoothHidState.CONNECTED -> Icons.Filled.BluetoothConnected
                            BluetoothHidState.ERROR -> Icons.Filled.Warning
                            else -> Icons.Filled.Bluetooth
                        },
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = when (snapshot.state) {
                            BluetoothHidState.CONNECTED -> ArmedGreen
                            BluetoothHidState.ERROR -> WarningAmber
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = when (snapshot.state) {
                            BluetoothHidState.CONNECTED -> "BT Connected"
                            BluetoothHidState.READY -> "Service Registered"
                            BluetoothHidState.CONNECTING,
                            BluetoothHidState.REGISTERING -> "Connecting..."
                            BluetoothHidState.ERROR -> "Error"
                            else -> "Disconnected"
                        },
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

            if (snapshot.state == BluetoothHidState.PERMISSION_REQUIRED) {
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onRequestBluetoothPermissions,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Grant Bluetooth Permission")
                }
            } else if (snapshot.state == BluetoothHidState.BLUETOOTH_OFF) {
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onRequestEnableBluetooth,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Turn On Bluetooth")
                }
            }

            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }

    if (btReadyForDevices) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Host Devices",
                        style = MaterialTheme.typography.titleMedium
                    )
                    OutlinedButton(
                        onClick = onRefreshBluetooth,
                        enabled = !isLoading
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Refresh",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Refresh")
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "To pair a new PC, laptop, TV, or tablet: tap Make Discoverable, then select this phone from your host's Bluetooth settings.\nIf you already paired this phone before adding Gamepad, remove it in your PC's Bluetooth settings and re-pair once. For Windows XInput PC games, run PocketPadXboxBridge.exe on your PC.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onMakeDiscoverable,
                        modifier = Modifier.weight(1f),
                        enabled = !isLoading
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Visibility,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Make Discoverable", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }

                    OutlinedButton(
                        onClick = onOpenBluetoothSettings,
                        enabled = !isLoading
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("BT Settings")
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(10.dp))

                if (snapshot.pairedDevices.isEmpty()) {
                    Text(
                        text = "No paired Bluetooth devices yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        snapshot.pairedDevices.forEach { device ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Devices,
                                        contentDescription = null,
                                        modifier = Modifier.size(22.dp),
                                        tint = if (device.isConnected) {
                                            ArmedGreen
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = device.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = if (device.isConnected) {
                                                "${device.address} • Connected"
                                            } else {
                                                device.address
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (device.isConnected) {
                                                ArmedGreen
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            }
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                if (device.isConnected) {
                                    OutlinedButton(onClick = onDisconnectBluetoothHost) {
                                        Text("Disconnect")
                                    }
                                } else {
                                    Button(
                                        onClick = { onConnectBluetoothHost(device.address) },
                                        enabled = !isLoading &&
                                            snapshot.state != BluetoothHidState.CONNECTING
                                    ) {
                                        Text("Connect")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UsbRootStatusSection(
    rootAvailable: Boolean,
    gadgetEnvironment: GadgetEnvironment?,
    hidArmed: Boolean,
    connectionStatus: ConnectionStatus,
    isLoading: Boolean,
    errorMessage: String?,
    onToggleHid: (Boolean) -> Unit,
    onRefreshEnvironment: () -> Unit
) {
    val cardColor by animateColorAsState(
        targetValue = when {
            !rootAvailable -> MaterialTheme.colorScheme.errorContainer
            hidArmed -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        label = "usbCardColor"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cardColor)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Keyboard,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = if (hidArmed) ArmedGreen
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "PocketKeyboard",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = if (hidArmed) "USB Keyboard Active"
                            else "USB HID Inactive",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (hidArmed) "ARMED" else "DISARMED",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (hidArmed) ArmedGreen else DisarmedRed
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = hidArmed,
                        onCheckedChange = onToggleHid,
                        enabled = rootAvailable && !isLoading,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = ArmedGreen,
                            checkedTrackColor = ArmedGreen.copy(alpha = 0.5f)
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Shield,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = if (rootAvailable) ArmedGreen else DisarmedRed
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (rootAvailable) "Root OK" else "No Root",
                        style = MaterialTheme.typography.labelMedium
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = when (connectionStatus) {
                            ConnectionStatus.CONNECTED -> Icons.Filled.Usb
                            ConnectionStatus.ERROR -> Icons.Filled.Warning
                            else -> Icons.Filled.UsbOff
                        },
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = when (connectionStatus) {
                            ConnectionStatus.CONNECTED -> ArmedGreen
                            ConnectionStatus.ERROR -> WarningAmber
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = when (connectionStatus) {
                            ConnectionStatus.CONNECTED -> "USB Connected"
                            ConnectionStatus.CONNECTING -> "Connecting..."
                            ConnectionStatus.ERROR -> "Error"
                            ConnectionStatus.DISCONNECTED -> "Disconnected"
                        },
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }

    // Kernel / USB Gadget diagnostics card
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "USB Gadget Diagnostics",
                    style = MaterialTheme.typography.titleMedium
                )
                OutlinedButton(
                    onClick = onRefreshEnvironment,
                    enabled = !isLoading
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "Refresh",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Re-check")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            val backendLabel = when (gadgetEnvironment?.backend) {
                GadgetBackend.CONFIGFS -> "ConfigFS (${gadgetEnvironment.configFsPath})"
                GadgetBackend.EXISTING_HIDG -> "Kernel HID Node (${gadgetEnvironment.hidDevicePath})"
                GadgetBackend.ANDROID_USB -> "Legacy android_usb"
                GadgetBackend.NONE -> "Not detected"
                null -> "Checking..."
            }
            Text(
                text = "Backend: $backendLabel",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "UDC: ${gadgetEnvironment?.udcName ?: "None"}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "Keyboard Node: ${gadgetEnvironment?.hidDevicePath ?: "Created when armed"}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "Mouse Node: ${gadgetEnvironment?.mouseDevicePath ?: "Created when armed"}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "Gamepad Node: ${gadgetEnvironment?.gamepadDevicePath ?: "Created when armed"}",
                style = MaterialTheme.typography.bodySmall
            )
            if (gadgetEnvironment?.hasGadgetFs == true) {
                Text(
                    text = "GadgetFS (/dev/gadget): Present",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
