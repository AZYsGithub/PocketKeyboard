package com.pocketkeyboard.ui.components

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.UsbOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pocketkeyboard.hid.GadgetBackend
import com.pocketkeyboard.hid.GadgetEnvironment
import com.pocketkeyboard.ui.theme.ArmedGreen
import com.pocketkeyboard.ui.theme.DisarmedRed
import com.pocketkeyboard.ui.theme.WarningAmber
import com.pocketkeyboard.viewmodel.ConnectionStatus

/**
 * Dedicated USB HID status & arm/disarm control page.
 */
@Composable
fun StatusCard(
    rootAvailable: Boolean,
    gadgetEnvironment: GadgetEnvironment?,
    hidArmed: Boolean,
    holdModifiersMode: Boolean,
    verticalKeyboardTrackpad: Boolean,
    connectionStatus: ConnectionStatus,
    isLoading: Boolean,
    errorMessage: String?,
    onToggleHid: (Boolean) -> Unit,
    onToggleHoldModifiers: (Boolean) -> Unit,
    onToggleVerticalKeyboardTrackpad: (Boolean) -> Unit,
    onRefreshEnvironment: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cardColor by animateColorAsState(
        targetValue = when {
            !rootAvailable -> MaterialTheme.colorScheme.errorContainer
            hidArmed -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        label = "cardColor"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = cardColor)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Title row with ARM/DISARM toggle
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

                // Status indicators
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
                if (gadgetEnvironment?.hasGadgetFs == true) {
                    Text(
                        text = "GadgetFS (/dev/gadget): Present",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
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
    }
}
