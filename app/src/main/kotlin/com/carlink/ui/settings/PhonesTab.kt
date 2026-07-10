package com.carlink.ui.settings

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.UsbOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.carlink.CarlinkManager
import com.carlink.R
import com.carlink.protocol.PhoneType
import com.carlink.ui.theme.AutomotiveDimens
import kotlinx.coroutines.delay

/**
 * Fixed card width for horizontal layout.
 *
 * 360.dp sized for gminfo37 (2400x960 @ 200dpi ≈ 456px ≈ 19% of width). Fits 2 cards
 * with 24dp gutters in the 75%-coerced content area (max 1200dp). Accommodates device
 * name + status line + Remove button without wrapping. No design token; retune only if
 * a device with non-200dpi ships.
 */
private val CARD_WIDTH = 360.dp

/**
 * Phones tab — shows adapter's paired device list as horizontal scrolling cards.
 *
 * - USB device card (leftmost): active when a USB phone is connected, greyed out otherwise.
 * - Wireless device cards: queried from adapter's DevList with Connect/Disconnect/Remove actions.
 */
@Composable
fun PhonesTabContent(carlinkManager: CarlinkManager) {
    val view = LocalView.current

    // Observe device list and connection state
    var pairedDevices by remember { mutableStateOf(carlinkManager.pairedDevices) }
    var activeBtMac by remember { mutableStateOf(carlinkManager.connectedBtMac) }
    var activeWifi by remember { mutableIntStateOf(carlinkManager.currentWifi ?: -1) }
    var phoneType by remember { mutableStateOf(carlinkManager.currentPhoneType) }
    var managerState by remember { mutableStateOf(carlinkManager.state) }

    // Register device listener for DevList updates (supports multiple listeners)
    DisposableEffect(carlinkManager) {
        val listener =
            CarlinkManager.DeviceListener { devices ->
                pairedDevices = devices
            }
        carlinkManager.addDeviceListener(listener)
        // Request fresh device list when tab opens
        carlinkManager.refreshDeviceList()
        onDispose { carlinkManager.removeDeviceListener(listener) }
    }

    // Poll connection state periodically while tab is visible.
    // Rationale: CarlinkManager.callback is single-slot and already consumed by MainScreen,
    // and DeviceListener only fires on DevList changes — neither surfaces state/phoneType/
    // wifi/btMac transitions to secondary observers. 1 Hz polling is a pragmatic workaround.
    // TODO: multi-observer ConnectionStateListener / StateFlow on CarlinkManager (would also
    // replace the SettingsScreen.kt:356 stale-remember pattern — same root cause). 1 Hz poll
    // is pragmatic for now; no contention observed in 2026-04-20 POTATO captures.
    LaunchedEffect(carlinkManager) {
        while (true) {
            managerState = carlinkManager.state
            activeBtMac = carlinkManager.connectedBtMac
            activeWifi = carlinkManager.currentWifi ?: -1
            phoneType = carlinkManager.currentPhoneType
            delay(1000)
        }
    }

    // Guard against rapid button taps launching conflicting operations.
    // HAZARD: no timeout fallback — if the adapter swallows 0x0F or stays stuck in
    // CONNECTING/DEVICE_CONNECTED, the LaunchedEffect(managerState) below never fires
    // the reset and every wireless card's onTap stays disabled indefinitely.
    // Suggested fix: LaunchedEffect(isProcessing) { if (isProcessing) { delay(10_000); isProcessing = false } }.
    var isProcessing by remember { mutableStateOf(false) }

    // Hoisted remove dialog state to prevent stale device references across recompositions.
    var deviceToRemove by remember { mutableStateOf<CarlinkManager.DeviceInfo?>(null) }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Max)
                    .horizontalScroll(rememberScrollState())
                    .padding(24.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            // === USB Device Card (always present) ===
            // wifi=0 means explicit USB; wifi=-1 (null) with active phoneType means
            // the adapter didn't send the wifi field — treat as USB since wireless
            // always sends wifi=1 explicitly. (connectedBtMac is private backing with a
            // public read-only accessor on CarlinkManager; activeWifi mirrors currentWifi.)
            val isUsbConnected = phoneType != null && activeWifi != 1
            UsbDeviceCard(
                isConnected = isUsbConnected,
                phoneType = if (isUsbConnected) phoneType else null,
                modifier = Modifier.width(CARD_WIDTH).fillMaxHeight(),
            )

            // === Wireless Device Cards ===
            if (pairedDevices.isEmpty()) {
                EmptyDeviceCard(modifier = Modifier.width(CARD_WIDTH).fillMaxHeight())
            } else {
                pairedDevices.forEach { device ->
                    // Stable keying by btMac preserves per-card state across list reorderings.
                    key(device.btMac) {
                        val isDeviceActive =
                            activeWifi == 1 &&
                                activeBtMac != null &&
                                device.btMac == activeBtMac &&
                                (
                                    managerState == CarlinkManager.State.STREAMING ||
                                        managerState == CarlinkManager.State.DEVICE_CONNECTED
                                )

                        WirelessDeviceCard(
                            device = device,
                            isConnected = isDeviceActive,
                            onTap = {
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                isProcessing = true
                                if (isDeviceActive) {
                                    carlinkManager.disconnectPhone()
                                } else {
                                    carlinkManager.connectToDevice(device.btMac)
                                }
                            },
                            onRemove = {
                                deviceToRemove = device
                            },
                            modifier = Modifier.width(CARD_WIDTH).fillMaxHeight(),
                            enabled = !isProcessing,
                        )
                    }
                }
            }
        }
    }

    // Reset processing guard when state changes (connection completed or failed)
    LaunchedEffect(managerState) {
        if (managerState == CarlinkManager.State.STREAMING ||
            managerState == CarlinkManager.State.DISCONNECTED
        ) {
            isProcessing = false
        }
    }

    // Hoisted remove confirmation dialog.
    // NOTE: forgetDevice is invoked on the main thread here. CarlinkManager optimistically
    // mutates _deviceList on main while IO-dispatched callbacks may also fire — assumed safe
    // pending audit; 2026-04-20 POTATO logs show no ConcurrentModification/IndexOutOfBounds
    // across 3 sessions. Still warrants an explicit main-thread-only contract on _deviceList.
    deviceToRemove?.let { device ->
        RemoveDeviceDialog(
            deviceName = device.name,
            onConfirm = {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                carlinkManager.forgetDevice(device.btMac)
                deviceToRemove = null
            },
            onDismiss = { deviceToRemove = null },
        )
    }
}

// ==================== USB Device Card ====================

// Active card background tints
private val CarPlayActiveColor = Color(0xFF1B3A1F) // Dark green tint
private val AndroidAutoActiveColor = Color(0xFF1A2A3D) // Dark blue tint

/**
 * Card representing the wired USB slot.
 *
 * Always rendered as the leftmost card; shows a branded CarPlay / Android Auto icon when a
 * USB phone is connected, greyed-out UsbOff icon otherwise. Non-interactive — the adapter
 * owns USB session lifecycle.
 */
@Composable
private fun UsbDeviceCard(
    isConnected: Boolean,
    phoneType: PhoneType?,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val alpha = if (isConnected) 1f else 0.38f

    val containerColor =
        if (isConnected && phoneType != null) {
            activeCardColor(phoneType)
        } else {
            colorScheme.surfaceContainerLow
        }

    val textColor = if (isConnected) Color.White else colorScheme.onSurface.copy(alpha = alpha)

    Card(
        modifier = modifier,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(
            modifier = Modifier.padding(24.dp).fillMaxWidth().fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = if (isConnected) Icons.Default.Usb else Icons.Default.UsbOff,
                contentDescription = null,
                tint = textColor,
                modifier = Modifier.size(32.dp),
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "USB",
                style =
                    MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                color = textColor,
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (isConnected && phoneType != null) {
                Text(
                    text = "Connected",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.85f),
                )

                // Push icon to bottom
                Spacer(modifier = Modifier.weight(1f))

                // Show CarPlay or Android Auto branded icon
                Image(
                    painter =
                        painterResource(
                            id =
                                when (phoneType) {
                                    PhoneType.CARPLAY, PhoneType.CARPLAY_WIRELESS -> R.drawable.ic_carplay
                                    else -> R.drawable.ic_android_auto
                                },
                        ),
                    contentDescription = phoneType.name,
                    modifier = Modifier.size(48.dp),
                )
            } else {
                Text(
                    text = "No device",
                    style = MaterialTheme.typography.bodyLarge,
                    color = colorScheme.onSurface.copy(alpha = 0.38f),
                )
            }
        }
    }
}

// ==================== Wireless Device Card ====================

/**
 * Card representing a single paired wireless device from the adapter's DevList.
 *
 * Tap toggles connect/disconnect (guarded by [enabled] / isProcessing); Remove confirms
 * forget. Active card tint is chosen by [device.type] string match.
 *
 * FRAGILITY: [CarlinkManager.DeviceInfo.type] is a raw String from
 * CarlinkManager.parseDevList. Any adapter spelling drift ("Carplay", "Android Auto",
 * "CarPlay-W") and alternative types like "HiCar" (listed as valid in the DeviceInfo
 * KDoc) silently fall through to the generic icon/surfaceContainerHighest tint.
 * Suggested fix: map to a DeviceType enum at parseDevList boundary and log unknown
 * values; keep the String on DeviceInfo for debugging.
 */
@Composable
private fun WirelessDeviceCard(
    device: CarlinkManager.DeviceInfo,
    isConnected: Boolean,
    onTap: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colorScheme = MaterialTheme.colorScheme

    val containerColor =
        if (isConnected) {
            // See class KDoc: String match is fragile — unknown types fall through here.
            when (device.type) {
                "CarPlay" -> CarPlayActiveColor
                "AndroidAuto" -> AndroidAutoActiveColor
                else -> colorScheme.surfaceContainerHighest
            }
        } else {
            colorScheme.surfaceContainer
        }

    // UX INCONSISTENCY: Card(onClick=...) has no `enabled` parameter, so the ripple still
    // animates on tap when `enabled == false`; only the inner `if (enabled) onTap()` barrier
    // suppresses the action. The Button below uses the proper `enabled = enabled` and is
    // visually/interaction-disabled correctly. Consider wrapping the Card click in an
    // interactionSource-gated modifier, or migrating to a clickable modifier with enabled.
    Card(
        onClick = { if (enabled) onTap() },
        modifier = modifier,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(
            modifier = Modifier.padding(24.dp).fillMaxWidth().fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Device type icon (CarPlay / Android Auto branded) — same String-match fragility
            // as the container tint above; unknown types render the generic phone-projection icon.
            Image(
                painter =
                    painterResource(
                        id =
                            when (device.type) {
                                "CarPlay" -> R.drawable.ic_carplay
                                "AndroidAuto" -> R.drawable.ic_android_auto
                                else -> R.drawable.ic_phone_projection
                            },
                    ),
                contentDescription = device.type,
                modifier = Modifier.size(48.dp),
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Device name — white on active colored cards, theme-adaptive otherwise
            val cardTextColor = if (isConnected) Color.White else colorScheme.onSurface

            Text(
                text = device.name,
                style =
                    MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                color = cardTextColor,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Status line — single field: "Connected" or "Last seen: ..."
            Text(
                text =
                    if (isConnected) {
                        "Connected"
                    } else {
                        device.lastConnected?.let { "Last seen: $it" } ?: "Disconnected"
                    },
                style = MaterialTheme.typography.bodySmall,
                color = if (isConnected) Color.White.copy(alpha = 0.85f) else colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Push remove button to bottom
            Spacer(modifier = Modifier.weight(1f))

            // Remove button — matches "Disconnect Adapter" style (filled error)
            Button(
                onClick = onRemove,
                enabled = enabled,
                modifier = Modifier.height(AutomotiveDimens.ButtonMinHeight),
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = colorScheme.error,
                        contentColor = colorScheme.onError,
                    ),
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Remove",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

// ==================== Empty State ====================

/**
 * Placeholder card shown when the adapter's DevList is empty (no paired wireless devices).
 * Prompts the user to pair a phone with the adapter; no interactive actions.
 */
@Composable
private fun EmptyDeviceCard(modifier: Modifier = Modifier) {
    val colorScheme = MaterialTheme.colorScheme

    Card(
        modifier = modifier,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = colorScheme.surfaceContainerLow,
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .padding(24.dp)
                    .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Default.Wifi,
                contentDescription = null,
                tint = colorScheme.onSurface.copy(alpha = 0.38f),
                modifier = Modifier.size(48.dp),
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "No paired wireless devices",
                style = MaterialTheme.typography.titleMedium,
                color = colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Connect a phone to the adapter to get started",
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
    }
}

/** Returns the active card background color based on phone type. */
@Composable
private fun activeCardColor(phoneType: PhoneType): Color =
    when (phoneType) {
        PhoneType.CARPLAY, PhoneType.CARPLAY_WIRELESS -> CarPlayActiveColor
        PhoneType.ANDROID_AUTO -> AndroidAutoActiveColor
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }

// ==================== Dialogs ====================

/**
 * Confirmation dialog for forgetting a paired wireless device.
 *
 * Dismissal paths (onDismissRequest + Cancel button) both route to [onDismiss], which the
 * caller wires to clear the hoisted `deviceToRemove` state — verified consistent.
 */
@Composable
private fun RemoveDeviceDialog(
    deviceName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        // Back press / scrim tap both clear deviceToRemove via onDismiss.
        onDismissRequest = onDismiss,
        title = { Text("Remove Device") },
        text = {
            Text(
                "Remove \"$deviceName\" from the adapter's paired device list? " +
                    "The adapter will no longer auto-connect to this device.",
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors =
                    ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
            ) {
                Text("Remove")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
