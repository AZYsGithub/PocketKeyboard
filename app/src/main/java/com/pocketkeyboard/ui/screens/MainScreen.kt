package com.pocketkeyboard.ui.screens

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.res.Configuration
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.pocketkeyboard.hid.BluetoothPermissions
import com.pocketkeyboard.ui.components.KeyboardLayout
import com.pocketkeyboard.ui.components.StatusCard
import com.pocketkeyboard.ui.theme.ArmedGreen
import com.pocketkeyboard.viewmodel.KeyboardViewModel
import com.pocketkeyboard.viewmodel.TransportMode

enum class AppPage(val title: String) {
    CONNECT("Connect"),
    FULL_KEYBOARD("Keyboard"),
    QUICK_TYPE("Quick"),
    TRACKPAD("Trackpad"),
    CONTROLLER("Gamepad")
}

private enum class PendingBtAction {
    NONE,
    START,
    ENABLE_BT,
    DISCOVERABLE
}

/**
 * Main screen with separate pages for Connect (Bluetooth / USB HID), Full Keyboard, Quick Type, and Trackpad.
 */
@Composable
fun MainScreen(
    viewModel: KeyboardViewModel,
    modifier: Modifier = Modifier
) {
    val transportMode by viewModel.transportMode.collectAsState()
    val bluetoothSnapshot by viewModel.bluetoothSnapshot.collectAsState()
    val rootAvailable by viewModel.rootAvailable.collectAsState()
    val gadgetEnvironment by viewModel.gadgetEnvironment.collectAsState()
    val hidArmed by viewModel.hidArmed.collectAsState()
    val mouseAvailable by viewModel.mouseAvailable.collectAsState()
    val gamepadAvailable by viewModel.gamepadAvailable.collectAsState()
    val connectionStatus by viewModel.connectionStatus.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val shiftActive by viewModel.shiftActive.collectAsState()
    val capsLockActive by viewModel.capsLockActive.collectAsState()
    val activeModifiers by viewModel.activeModifiers.collectAsState()
    val holdModifiersMode by viewModel.holdModifiersMode.collectAsState()
    val verticalKeyboardTrackpad by viewModel.verticalKeyboardTrackpad.collectAsState()
    val trackpadSensitivity by viewModel.trackpadSensitivity.collectAsState()
    val controllerStyle by viewModel.controllerStyle.collectAsState()
    val quickTypeText by viewModel.quickTypeText.collectAsState()

    val isPortrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var currentPage by rememberSaveable { mutableStateOf(AppPage.CONNECT) }
    var pendingBtAction by remember { mutableStateOf(PendingBtAction.NONE) }
    val softwareKeyboardController = LocalSoftwareKeyboardController.current

    val btEnableLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.refreshBluetoothState()
    }

    val btDiscoverableLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.refreshBluetoothState()
    }

    fun launchEnableBluetooth() {
        try {
            btEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } catch (_: Exception) {
            try {
                context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            } catch (_: Exception) {
            }
        }
    }

    fun launchMakeDiscoverable() {
        viewModel.ensureBluetoothHidRegisteredForDiscovery()
        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
            putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)
        }
        try {
            btDiscoverableLauncher.launch(intent)
        } catch (_: Exception) {
            try {
                context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            } catch (_: Exception) {
            }
        }
    }

    val btPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        val action = pendingBtAction
        pendingBtAction = PendingBtAction.NONE

        viewModel.onBluetoothPermissionsResult(
            allGranted = allGranted,
            autoStart = (action == PendingBtAction.START || action == PendingBtAction.DISCOVERABLE)
        )

        if (allGranted) {
            when (action) {
                PendingBtAction.ENABLE_BT -> launchEnableBluetooth()
                PendingBtAction.DISCOVERABLE -> launchMakeDiscoverable()
                else -> Unit
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshBluetoothState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

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
                        AppPage.CONNECT -> when {
                            transportMode == TransportMode.BLUETOOTH && hidArmed ->
                                Icons.Filled.BluetoothConnected
                            transportMode == TransportMode.BLUETOOTH ->
                                Icons.Filled.Bluetooth
                            else -> Icons.Filled.Usb
                        }
                        AppPage.FULL_KEYBOARD -> Icons.Filled.Keyboard
                        AppPage.QUICK_TYPE -> Icons.Filled.Edit
                        AppPage.TRACKPAD -> Icons.Filled.TouchApp
                        AppPage.CONTROLLER -> Icons.Filled.SportsEsports
                    }
                    Tab(
                        selected = currentPage == page,
                        onClick = { currentPage = page },
                        text = {
                            Text(
                                text = page.title,
                                fontSize = 10.sp,
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
                                tint = if (page == AppPage.CONNECT && hidArmed) {
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
                AppPage.CONNECT -> {
                    StatusCard(
                        transportMode = transportMode,
                        bluetoothSnapshot = bluetoothSnapshot,
                        rootAvailable = rootAvailable,
                        gadgetEnvironment = gadgetEnvironment,
                        hidArmed = hidArmed,
                        holdModifiersMode = holdModifiersMode,
                        verticalKeyboardTrackpad = verticalKeyboardTrackpad,
                        trackpadSensitivity = trackpadSensitivity,
                        controllerStyle = controllerStyle,
                        connectionStatus = connectionStatus,
                        isLoading = isLoading,
                        errorMessage = errorMessage,
                        onSelectTransportMode = viewModel::selectTransportMode,
                        onToggleBluetoothHid = viewModel::toggleBluetoothHid,
                        onConnectBluetoothHost = viewModel::connectBluetoothHost,
                        onDisconnectBluetoothHost = viewModel::disconnectBluetoothHost,
                        onRefreshBluetooth = { viewModel.refreshBluetoothState() },
                        onRequestBluetoothPermissions = {
                            val perms = BluetoothPermissions.requiredPermissions()
                            if (perms.isEmpty()) {
                                viewModel.onBluetoothPermissionsResult(
                                    allGranted = true,
                                    autoStart = true
                                )
                            } else {
                                pendingBtAction = PendingBtAction.START
                                btPermissionLauncher.launch(perms)
                            }
                        },
                        onRequestEnableBluetooth = {
                            if (!BluetoothPermissions.hasRequiredPermissions(context)) {
                                pendingBtAction = PendingBtAction.ENABLE_BT
                                btPermissionLauncher.launch(BluetoothPermissions.requiredPermissions())
                            } else {
                                launchEnableBluetooth()
                            }
                        },
                        onMakeDiscoverable = {
                            if (!BluetoothPermissions.hasRequiredPermissions(context)) {
                                pendingBtAction = PendingBtAction.DISCOVERABLE
                                btPermissionLauncher.launch(BluetoothPermissions.requiredPermissions())
                            } else {
                                launchMakeDiscoverable()
                            }
                        },
                        onOpenBluetoothSettings = {
                            try {
                                context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                            } catch (_: Exception) {
                            }
                        },
                        onToggleHid = viewModel::toggleHid,
                        onToggleHoldModifiers = viewModel::setHoldModifiersMode,
                        onToggleVerticalKeyboardTrackpad = viewModel::setVerticalKeyboardTrackpad,
                        onTrackpadSensitivityChange = viewModel::setTrackpadSensitivity,
                        onSelectControllerStyle = viewModel::setControllerStyle,
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
                                    sensitivity = trackpadSensitivity,
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
                        transportMode = transportMode,
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
                        transportMode = transportMode,
                        sensitivity = trackpadSensitivity,
                        onMouseMove = viewModel::onMouseMove,
                        onMouseScroll = viewModel::onMouseScroll,
                        onMouseButtonDown = viewModel::onMouseButtonDown,
                        onMouseButtonUp = viewModel::onMouseButtonUp,
                        onMouseClick = viewModel::onMouseClick,
                        modifier = Modifier.weight(1f)
                    )
                }

                AppPage.CONTROLLER -> {
                    ControllerScreen(
                        hidArmed = hidArmed,
                        gamepadAvailable = gamepadAvailable,
                        transportMode = transportMode,
                        controllerStyle = controllerStyle,
                        onToggleControllerStyle = viewModel::setControllerStyle,
                        onButtonChange = viewModel::setGamepadButton,
                        onDpadChange = viewModel::setGamepadDpad,
                        onLeftStickChange = viewModel::setGamepadLeftStick,
                        onRightStickChange = viewModel::setGamepadRightStick,
                        onTriggerChange = viewModel::setGamepadTrigger,
                        onResetGamepad = viewModel::resetGamepadState,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}
