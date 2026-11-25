package com.pocketkeyboard.ui.screens

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketkeyboard.ui.components.KeyboardLayout
import com.pocketkeyboard.ui.components.StatusCard
import com.pocketkeyboard.ui.theme.ArmedGreen
import com.pocketkeyboard.viewmodel.KeyboardViewModel

enum class AppPage(val title: String) {
    USB_HID("USB HID"),
    FULL_KEYBOARD("Full Keyboard"),
    QUICK_TYPE("Quick Type"),
    TRACKPAD("Trackpad")
}

/**
 * Main screen with separate pages for USB HID Control, Full Keyboard, and Quick Type.
 */
@Composable
fun MainScreen(
    viewModel: KeyboardViewModel,
    modifier: Modifier = Modifier
) {
    val rootAvailable by viewModel.rootAvailable.collectAsState()
    val gadgetEnvironment by viewModel.gadgetEnvironment.collectAsState()
    val hidArmed by viewModel.hidArmed.collectAsState()
    val mouseAvailable by viewModel.mouseAvailable.collectAsState()
    val connectionStatus by viewModel.connectionStatus.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val shiftActive by viewModel.shiftActive.collectAsState()
    val capsLockActive by viewModel.capsLockActive.collectAsState()
    val activeModifiers by viewModel.activeModifiers.collectAsState()
    val holdModifiersMode by viewModel.holdModifiersMode.collectAsState()
    val verticalKeyboardTrackpad by viewModel.verticalKeyboardTrackpad.collectAsState()
    val quickTypeText by viewModel.quickTypeText.collectAsState()

    val isPortrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT

    var currentPage by rememberSaveable { mutableStateOf(AppPage.USB_HID) }
    val softwareKeyboardController = LocalSoftwareKeyboardController.current

    // Hide system IME when leaving Quick Type so it never covers Full Keyboard
    LaunchedEffect(currentPage) {
        if (currentPage != AppPage.QUICK_TYPE) {
            softwareKeyboardController?.hide()
        }
    }

    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            TabRow(
                selectedTabIndex = currentPage.ordinal,
                modifier = Modifier.fillMaxWidth()
            ) {
                AppPage.entries.forEach { page ->
                    val icon = when (page) {
                        AppPage.USB_HID -> Icons.Filled.Usb
                        AppPage.FULL_KEYBOARD -> Icons.Filled.Keyboard
                        AppPage.QUICK_TYPE -> Icons.Filled.Edit
                        AppPage.TRACKPAD -> Icons.Filled.TouchApp
                    }
                    Tab(
                        selected = currentPage == page,
                        onClick = { currentPage = page },
                        text = {
                            Text(
                                text = page.title,
                                fontSize = 11.sp,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        icon = {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = if (page == AppPage.USB_HID && hidArmed) {
                                    ArmedGreen
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
                    )
                }
            }

            when (currentPage) {
                AppPage.USB_HID -> {
                    StatusCard(
                        rootAvailable = rootAvailable,
                        gadgetEnvironment = gadgetEnvironment,
                        hidArmed = hidArmed,
                        holdModifiersMode = holdModifiersMode,
                        verticalKeyboardTrackpad = verticalKeyboardTrackpad,
                        connectionStatus = connectionStatus,
                        isLoading = isLoading,
                        errorMessage = errorMessage,
                        onToggleHid = viewModel::toggleHid,
                        onToggleHoldModifiers = viewModel::setHoldModifiersMode,
                        onToggleVerticalKeyboardTrackpad = viewModel::setVerticalKeyboardTrackpad,
                        onRefreshEnvironment = viewModel::refreshEnvironment,
                        modifier = Modifier.weight(1f)
                    )
                }

                AppPage.FULL_KEYBOARD -> {
                    if (isPortrait) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 4.dp),
                            verticalArrangement = if (verticalKeyboardTrackpad) {
                                Arrangement.Top
                            } else {
                                Arrangement.Center
                            }
                        ) {
                            KeyboardLayout(
                                isShiftActive = shiftActive,
                                isCapsLockActive = capsLockActive,
                                activeModifiers = activeModifiers,
                                onKeyDown = viewModel::onKeyDown,
                                onKeyUp = viewModel::onKeyUp,
                                compactRows = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            if (verticalKeyboardTrackpad) {
                                Spacer(modifier = Modifier.height(8.dp))
                                TrackpadPanel(
                                    enabled = hidArmed && mouseAvailable,
                                    buttonHeight = 48.dp,
                                    onMouseMove = viewModel::onMouseMove,
                                    onMouseScroll = viewModel::onMouseScroll,
                                    onMouseButtonDown = viewModel::onMouseButtonDown,
                                    onMouseButtonUp = viewModel::onMouseButtonUp,
                                    onMouseClick = viewModel::onMouseClick,
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(horizontal = 4.dp, vertical = 4.dp)
                                )
                            }
                        }
                    } else {
                        KeyboardLayout(
                            isShiftActive = shiftActive,
                            isCapsLockActive = capsLockActive,
                            activeModifiers = activeModifiers,
                            onKeyDown = viewModel::onKeyDown,
                            onKeyUp = viewModel::onKeyUp,
                            compactRows = false,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                AppPage.QUICK_TYPE -> {
                    QuickTypeScreen(
                        text = quickTypeText,
                        hidArmed = hidArmed,
                        onTextChanged = viewModel::onQuickTypeTextChanged,
                        onSendQuickKey = viewModel::sendQuickKey,
                        onClearBox = viewModel::clearQuickTypeBox,
                        modifier = Modifier.weight(1f)
                    )
                }

                AppPage.TRACKPAD -> {
                    TrackpadScreen(
                        hidArmed = hidArmed,
                        mouseAvailable = mouseAvailable,
                        onMouseMove = viewModel::onMouseMove,
                        onMouseScroll = viewModel::onMouseScroll,
                        onMouseButtonDown = viewModel::onMouseButtonDown,
                        onMouseButtonUp = viewModel::onMouseButtonUp,
                        onMouseClick = viewModel::onMouseClick,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}
