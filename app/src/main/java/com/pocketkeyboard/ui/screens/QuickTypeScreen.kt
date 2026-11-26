package com.pocketkeyboard.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pocketkeyboard.hid.HidKeyMap
import com.pocketkeyboard.viewmodel.TransportMode

/**
 * Quick Type page using the device's system virtual keyboard (e.g. Gboard)
 * to stream keystrokes over HID in real time.
 */
@Composable
fun QuickTypeScreen(
    text: String,
    hidArmed: Boolean,
    transportMode: TransportMode,
    onTextChanged: (String) -> Unit,
    onSendQuickKey: (modifiers: Int, keyCode: Int) -> Unit,
    onClearBox: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (!hidArmed) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    text = if (transportMode == TransportMode.BLUETOOTH) {
                        "Bluetooth HID is not connected. Connect to a host in the Connect tab to send keystrokes."
                    } else {
                        "USB HID is currently DISARMED. Arm it in the Connect tab to send keystrokes."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        // Quick shortcut chips for keys not easily typed via mobile IME
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AssistChip(
                onClick = { onSendQuickKey(HidKeyMap.MOD_NONE, HidKeyMap.KEY_ENTER) },
                label = { Text("Enter") },
                enabled = hidArmed
            )
            AssistChip(
                onClick = { onSendQuickKey(HidKeyMap.MOD_NONE, HidKeyMap.KEY_TAB) },
                label = { Text("Tab") },
                enabled = hidArmed
            )
            AssistChip(
                onClick = { onSendQuickKey(HidKeyMap.MOD_NONE, HidKeyMap.KEY_ESC) },
                label = { Text("Esc") },
                enabled = hidArmed
            )
            AssistChip(
                onClick = { onSendQuickKey(HidKeyMap.MOD_NONE, HidKeyMap.KEY_BACKSPACE) },
                label = { Text("Backspace") },
                enabled = hidArmed
            )
            AssistChip(
                onClick = { onSendQuickKey(HidKeyMap.MOD_LCTRL, HidKeyMap.KEY_C) },
                label = { Text("Ctrl+C") },
                enabled = hidArmed
            )
            AssistChip(
                onClick = { onSendQuickKey(HidKeyMap.MOD_LCTRL, HidKeyMap.KEY_V) },
                label = { Text("Ctrl+V") },
                enabled = hidArmed
            )
            AssistChip(
                onClick = onClearBox,
                label = { Text("Clear Box") }
            )
        }

        OutlinedTextField(
            value = text,
            onValueChange = onTextChanged,
            label = { Text("Quick Type (System Keyboard)") },
            placeholder = {
                Text("Tap here to open Gboard / system keyboard and type directly to the host...")
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )

        Spacer(modifier = Modifier.height(2.dp))
    }
}
