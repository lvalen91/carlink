package com.carlink.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Hd
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.SettingsInputComponent
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carlink.CarlinkManager
import com.carlink.logging.logInfo
import com.carlink.logging.logWarn
import com.carlink.ui.theme.AutomotiveDimens
import com.carlink.util.WindowMetricsCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Adapter Configuration Dialog
 *
 * Scrollable popup dialog for configuring adapter initialization settings.
 * Designed to be extensible - new configuration options can be easily added.
 *
 * Structure:
 * - Header with icon and title
 * - Subtitle ("Changes require app restart…" — see HAZARD note at that string)
 * - SecondaryTabRow with Audio / Visual / Misc tabs (ephemeral state via remember)
 * - Scrollable content area whose body switches on the selected tab
 * - Footer with Cancel / Default / Apply buttons (tiered restart strategy at Apply)
 *
 * @param adapterConfigPreference DataStore-backed flows + setters for each config field.
 * @param carlinkManager Current manager (nullable before connection); used by Tier 3 stop()
 *   and by Reboot Adapter action.
 * @param currentDisplayMode Drives usable-width/height calculation for resolution options.
 * @param onDismiss Invoked on Cancel / Default / Apply completion / outside tap.
 * @param onReinitAdapter Tier-2 callback. NAME IS MISLEADING: MainActivity implements this
 *   as `reinitializeForDisplayMode`, i.e. a full CarlinkManager rebuild — not just an
 *   adapter re-init. See MainActivity for actual behavior.
 */
@Composable
fun AdapterConfigurationDialog(
    adapterConfigPreference: AdapterConfigPreference,
    carlinkManager: CarlinkManager?,
    currentDisplayMode: DisplayMode,
    onDismiss: () -> Unit,
    onReinitAdapter: () -> Unit = {},
) {
    val colorScheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()

    // Load saved values from preferences
    val savedAudioSource by adapterConfigPreference.audioSourceFlow.collectAsStateWithLifecycle(
        initialValue = AudioSourceConfig.DEFAULT,
    )
    val savedMicSource by adapterConfigPreference.micSourceFlow.collectAsStateWithLifecycle(
        initialValue = MicSourceConfig.DEFAULT,
    )
    val savedWifiBand by adapterConfigPreference.wifiBandFlow.collectAsStateWithLifecycle(
        initialValue = WiFiBandConfig.DEFAULT,
    )
    val savedMediaDelay by adapterConfigPreference.mediaDelayFlow.collectAsStateWithLifecycle(
        initialValue = MediaDelayConfig.DEFAULT,
    )
    val savedVideoResolution by adapterConfigPreference.videoResolutionFlow.collectAsStateWithLifecycle(
        initialValue = VideoResolutionConfig.AUTO,
    )
    val savedFps by adapterConfigPreference.fpsFlow.collectAsStateWithLifecycle(
        initialValue = FpsConfig.DEFAULT,
    )
    val savedHandDrive by adapterConfigPreference.handDriveFlow.collectAsStateWithLifecycle(
        initialValue = HandDriveConfig.DEFAULT,
    )
    val savedGpsForwarding by adapterConfigPreference.gpsForwardingFlow.collectAsStateWithLifecycle(
        initialValue = false,
    )
    val savedClusterNavigation by adapterConfigPreference.clusterNavigationFlow.collectAsStateWithLifecycle(
        initialValue = false,
    )

    // Get usable display dimensions based on current display mode
    // FULLSCREEN_IMMERSIVE: Use full display bounds (bars are hidden)
    // Other modes: Subtract system bar insets from bounds
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val (usableWidth, usableHeight) =
        if (activity != null) {
            // WindowMetricsCompat falls back to getRealMetrics + WindowInsetsCompat on
            // API 29 (AAOS 10); the API 30+ path is unchanged.
            val windowManager = activity.windowManager
            val bounds = WindowMetricsCompat.displayBounds(windowManager)
            val windowInsets =
                WindowMetricsCompat.stableWindowInsets(windowManager, activity.window.decorView)

            when (currentDisplayMode) {
                DisplayMode.FULLSCREEN_IMMERSIVE -> {
                    // Full display - no insets subtracted
                    Pair(bounds.width() and 1.inv(), bounds.height() and 1.inv())
                }

                DisplayMode.STATUS_BAR_HIDDEN -> {
                    // Only subtract navigation bar (bottom), not status bar
                    val insets =
                        windowInsets.getInsetsIgnoringVisibility(
                            WindowInsetsCompat.Type.navigationBars(),
                        )
                    val w = bounds.width() - insets.left - insets.right
                    val h = bounds.height() - insets.bottom
                    Pair(w and 1.inv(), h and 1.inv())
                }

                DisplayMode.NAV_BAR_HIDDEN -> {
                    // Only subtract status bar (top), not navigation bar
                    val insets =
                        windowInsets.getInsetsIgnoringVisibility(
                            WindowInsetsCompat.Type.statusBars(),
                        )
                    val w = bounds.width()
                    val h = bounds.height() - insets.top
                    Pair(w and 1.inv(), h and 1.inv())
                }

                DisplayMode.SYSTEM_UI_VISIBLE -> {
                    // Subtract all system bar insets
                    val insets =
                        windowInsets.getInsetsIgnoringVisibility(
                            WindowInsetsCompat.Type.systemBars() or
                                WindowInsetsCompat.Type.displayCutout(),
                        )
                    val w = bounds.width() - insets.left - insets.right
                    val h = bounds.height() - insets.top - insets.bottom
                    Pair(w and 1.inv(), h and 1.inv())
                }
            }
        } else {
            Pair(0, 0)
        }
    val resolutionOptions =
        if (usableWidth > 0 && usableHeight > 0) {
            VideoResolutionConfig.calculateOptions(usableWidth, usableHeight)
        } else {
            emptyList()
        }

    // Local state for editing - allows cancel without saving
    var selectedAudioSource by remember { mutableStateOf(savedAudioSource) }
    var selectedMicSource by remember { mutableStateOf(savedMicSource) }
    var selectedWifiBand by remember { mutableStateOf(savedWifiBand) }
    var selectedMediaDelay by remember { mutableStateOf(savedMediaDelay) }
    var selectedVideoResolution by remember { mutableStateOf(savedVideoResolution) }
    var selectedFps by remember { mutableStateOf(savedFps) }
    var selectedHandDrive by remember { mutableStateOf(savedHandDrive) }
    var selectedGpsForwarding by remember { mutableStateOf(savedGpsForwarding) }
    var selectedClusterNavigation by remember { mutableStateOf(savedClusterNavigation) }

    // Sync local state when saved value loads (for initial load).
    // KNOWN THEORETICAL RACE (flow-shadow): any subsequent DataStore emission for one of these
    // keys re-runs the LaunchedEffect and OVERWRITES the user's in-flight selectedXxx choice.
    // Low probability in practice — DataStore only emits when something else writes — but a
    // concurrent write from elsewhere (e.g. resetToDefaults, external setter) will clobber
    // edits the user has made in the open dialog. Not fixed here; documented only.
    LaunchedEffect(savedAudioSource) { selectedAudioSource = savedAudioSource }
    LaunchedEffect(savedMicSource) { selectedMicSource = savedMicSource }
    LaunchedEffect(savedWifiBand) { selectedWifiBand = savedWifiBand }
    LaunchedEffect(savedMediaDelay) { selectedMediaDelay = savedMediaDelay }
    LaunchedEffect(savedVideoResolution) { selectedVideoResolution = savedVideoResolution }
    LaunchedEffect(savedFps) { selectedFps = savedFps }
    LaunchedEffect(savedHandDrive) { selectedHandDrive = savedHandDrive }
    LaunchedEffect(savedGpsForwarding) { selectedGpsForwarding = savedGpsForwarding }
    LaunchedEffect(savedClusterNavigation) { selectedClusterNavigation = savedClusterNavigation }

    // Track if any changes were made.
    // NOTE (stale): the old one-liner here claimed "All adapter configuration changes require
    // app restart" — that is no longer true. Apply uses a tiered strategy (see Apply block
    // ~line 828): only Misc changes (GPS/WiFi/ClusterNav) force a Tier-3 app kill; Audio/Visual
    // changes take the Tier-2 in-place reinit path. See also the misleading subtitle at ~line 273.
    val hasChanges =
        selectedAudioSource != savedAudioSource ||
            selectedMicSource != savedMicSource ||
            selectedWifiBand != savedWifiBand ||
            selectedMediaDelay != savedMediaDelay ||
            selectedVideoResolution != savedVideoResolution ||
            selectedFps != savedFps ||
            selectedHandDrive != savedHandDrive ||
            selectedGpsForwarding != savedGpsForwarding ||
            selectedClusterNavigation != savedClusterNavigation

    // Responsive dialog width - 60% of container width, clamped between 320dp and 600dp
    val windowInfo = LocalWindowInfo.current
    val density = LocalDensity.current
    val containerWidthDp = with(density) { windowInfo.containerSize.width.toDp() }
    val dialogMaxWidth = (containerWidthDp * 0.6f).coerceIn(320.dp, 600.dp)

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            modifier = Modifier.widthIn(max = dialogMaxWidth),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
            ) {
                // Header with icon and title
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.SettingsInputComponent,
                        contentDescription = null,
                        tint = colorScheme.primary,
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = "Adapter Configuration",
                        style =
                            MaterialTheme.typography.headlineSmall.copy(
                                fontWeight = FontWeight.Bold,
                            ),
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Subtitle.
                // HAZARD (stale UX copy — misleading to end users): this string is pending a
                // product-copy update. Audio/Visual-only changes take the Tier-2 in-place
                // reinit path (no app restart). Only Misc changes (GPS/WiFi/ClusterNav)
                // actually restart the app. DO NOT edit the string here without product sign-off.
                Text(
                    text = "Changes require app restart to apply",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Tab bar for configuration categories
                var selectedTabIndex by remember { mutableIntStateOf(0) }

                SecondaryTabRow(selectedTabIndex = selectedTabIndex) {
                    Tab(
                        selected = selectedTabIndex == 0,
                        onClick = { selectedTabIndex = 0 },
                        text = { Text("Audio") },
                    )
                    Tab(
                        selected = selectedTabIndex == 1,
                        onClick = { selectedTabIndex = 1 },
                        text = { Text("Visual") },
                    )
                    Tab(
                        selected = selectedTabIndex == 2,
                        onClick = { selectedTabIndex = 2 },
                        text = { Text("Misc") },
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Scrollable content area — shows settings for selected tab
                Column(
                    modifier =
                        Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    when (selectedTabIndex) {
                        // ===== Audio Tab =====
                        0 -> {
                            // Audio Source Configuration Option
                            ConfigurationOptionCard(
                                title = "Audio Source",
                                description = "Select how audio is routed from your phone",
                                icon = Icons.AutoMirrored.Filled.VolumeUp,
                            ) {
                                // Audio source selection buttons
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    // Bluetooth button
                                    AudioSourceButton(
                                        label = "Bluetooth",
                                        icon = Icons.Default.Bluetooth,
                                        isSelected = selectedAudioSource == AudioSourceConfig.BLUETOOTH,
                                        onClick = { selectedAudioSource = AudioSourceConfig.BLUETOOTH },
                                        modifier = Modifier.weight(1f),
                                    )

                                    // Adapter button
                                    AudioSourceButton(
                                        label = "Adapter",
                                        icon = Icons.Default.Usb,
                                        isSelected = selectedAudioSource == AudioSourceConfig.ADAPTER,
                                        onClick = { selectedAudioSource = AudioSourceConfig.ADAPTER },
                                        modifier = Modifier.weight(1f),
                                    )
                                }

                                // Current selection indicator
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text =
                                        when (selectedAudioSource) {
                                            AudioSourceConfig.BLUETOOTH -> "Audio via phone Bluetooth to car stereo"
                                            AudioSourceConfig.ADAPTER -> "Audio via USB through this app"
                                        },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colorScheme.primary,
                                )
                            }

                            // Microphone Source Configuration
                            ConfigurationOptionCard(
                                title = "Microphone Source",
                                description = "Select which microphone to use for voice input",
                                icon = Icons.Default.Mic,
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    AudioSourceButton(
                                        label = "Radio",
                                        icon = Icons.Default.Radio,
                                        isSelected = selectedMicSource == MicSourceConfig.APP,
                                        onClick = { selectedMicSource = MicSourceConfig.APP },
                                        modifier = Modifier.weight(1f),
                                    )
                                    AudioSourceButton(
                                        label = "Phone",
                                        icon = Icons.Default.PhoneAndroid,
                                        isSelected = selectedMicSource == MicSourceConfig.PHONE,
                                        onClick = { selectedMicSource = MicSourceConfig.PHONE },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text =
                                        when (selectedMicSource) {
                                            MicSourceConfig.APP -> "This device captures mic input via Android/OS"
                                            MicSourceConfig.PHONE -> "Phone uses its own mic (or adapter mic if present)"
                                        },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colorScheme.primary,
                                )
                            }

                            // Call Quality — UI removed (not the whole feature).
                            // VESTIGIAL / UI-DEAD: CallQualityConfig enum, setter/getter, ConfigKey,
                            // MessageSerializer.addChangedSettings branch, and ProGuard keep rule
                            // all still exist (see AdapterConfigPreference.kt:117-118). The
                            // firmware bug (CMD_BOX_INFO callQuality transform; value 2 (24kHz)
                            // breaks adapter mic input buffer) is why it is hardcoded to 1 in
                            // BoxSettings and why the UI toggle was pulled.

                            // Media Delay Configuration
                            ConfigurationOptionCard(
                                title = "Media Delay",
                                description = "Audio buffer size on adapter",
                                icon = Icons.Default.Timer,
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    AudioSourceButton(
                                        label = "Low",
                                        icon = Icons.Default.Timer,
                                        isSelected = selectedMediaDelay == MediaDelayConfig.LOW,
                                        onClick = { selectedMediaDelay = MediaDelayConfig.LOW },
                                        modifier = Modifier.weight(1f),
                                    )
                                    AudioSourceButton(
                                        label = "Medium",
                                        icon = Icons.Default.Timer,
                                        isSelected = selectedMediaDelay == MediaDelayConfig.MEDIUM,
                                        onClick = { selectedMediaDelay = MediaDelayConfig.MEDIUM },
                                        modifier = Modifier.weight(1f),
                                    )
                                    AudioSourceButton(
                                        label = "Standard",
                                        icon = Icons.Default.Timer,
                                        isSelected = selectedMediaDelay == MediaDelayConfig.STANDARD,
                                        onClick = { selectedMediaDelay = MediaDelayConfig.STANDARD },
                                        modifier = Modifier.weight(1f),
                                    )
                                    AudioSourceButton(
                                        label = "High",
                                        icon = Icons.Default.Timer,
                                        isSelected = selectedMediaDelay == MediaDelayConfig.HIGH,
                                        onClick = { selectedMediaDelay = MediaDelayConfig.HIGH },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text =
                                        when (selectedMediaDelay) {
                                            MediaDelayConfig.LOW -> "300ms — Lowest latency, may glitch on poor USB"
                                            MediaDelayConfig.MEDIUM -> "500ms — Balanced latency and stability"
                                            MediaDelayConfig.STANDARD -> "1000ms — Firmware default (recommended)"
                                            MediaDelayConfig.HIGH -> "2000ms — Maximum buffer for problematic setups"
                                        },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colorScheme.primary,
                                )
                            }
                        }

                        // ===== Visual Tab =====
                        1 -> {
                            // Video Resolution Configuration
                            //
                            // HAZARD — SILENT DROP under Tier 2 (see Apply block):
                            // MessageSerializer.addChangedSettings has NO branches for
                            // VIDEO_RESOLUTION or FPS (see AdapterConfigPreference.kt:355-362,
                            // 375-383). If the user changes ONLY resolution and/or FPS here
                            // (Visual tab, no Misc changes), Apply takes the Tier-2
                            // MINIMAL_PLUS_CHANGES path — the new values are persisted to
                            // DataStore but are NOT propagated to the adapter until a FULL
                            // init (version bump or cold start). User sees the toggle move
                            // but no visible effect. Do NOT fix here — fix in the serializer.
                            ConfigurationOptionCard(
                                title = "Video Resolution",
                                description = "Select resolution for CarPlay. Android Auto not supported.",
                                icon = Icons.Default.AspectRatio,
                            ) {
                                // Auto option button
                                val autoLabel =
                                    if (usableWidth > 0 && usableHeight > 0) {
                                        "Auto (${usableWidth}x$usableHeight)"
                                    } else {
                                        "Auto"
                                    }

                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    // Auto button - full width
                                    ResolutionButton(
                                        label = autoLabel,
                                        isSelected = selectedVideoResolution.isAuto,
                                        isRecommended = true,
                                        onClick = { selectedVideoResolution = VideoResolutionConfig.AUTO },
                                        modifier = Modifier.fillMaxWidth(),
                                    )

                                    // Resolution options - 2x2 grid
                                    if (resolutionOptions.isNotEmpty()) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            resolutionOptions.take(2).forEach { option ->
                                                ResolutionButton(
                                                    label = "${option.width}x${option.height}",
                                                    isSelected = selectedVideoResolution == option,
                                                    isRecommended = false,
                                                    onClick = { selectedVideoResolution = option },
                                                    modifier = Modifier.weight(1f),
                                                )
                                            }
                                        }
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            resolutionOptions.drop(2).take(2).forEach { option ->
                                                ResolutionButton(
                                                    label = "${option.width}x${option.height}",
                                                    isSelected = selectedVideoResolution == option,
                                                    isRecommended = false,
                                                    onClick = { selectedVideoResolution = option },
                                                    modifier = Modifier.weight(1f),
                                                )
                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text =
                                        if (selectedVideoResolution.isAuto) {
                                            "Uses detected display resolution"
                                        } else {
                                            "Lower resolutions reduce GPU load"
                                        },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colorScheme.primary,
                                )

                                // Android Auto warning for low resolutions
                                if (!selectedVideoResolution.isAuto && selectedVideoResolution.height < 720) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Note: Android Auto will use 800x480 at this resolution",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colorScheme.error,
                                    )
                                }
                            }

                            // Frame Rate Configuration
                            // HAZARD: same silent-drop issue as Video Resolution above — FPS
                            // has no MessageSerializer.addChangedSettings branch, so a Tier-2
                            // reinit persists the new value to DataStore without pushing it
                            // to the adapter until a FULL init. See HAZARD above for details.
                            ConfigurationOptionCard(
                                title = "Frame Rate",
                                description = "FPS for CarPlay/Android Auto projection",
                                icon = Icons.Default.Speed,
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    AudioSourceButton(
                                        label = "30 FPS",
                                        icon = Icons.Default.Speed,
                                        isSelected = selectedFps == FpsConfig.FPS_30,
                                        onClick = { selectedFps = FpsConfig.FPS_30 },
                                        modifier = Modifier.weight(1f),
                                    )
                                    AudioSourceButton(
                                        label = "60 FPS",
                                        icon = Icons.Default.Speed,
                                        isSelected = selectedFps == FpsConfig.FPS_60,
                                        onClick = { selectedFps = FpsConfig.FPS_60 },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text =
                                        when (selectedFps) {
                                            FpsConfig.FPS_30 -> "Some Jitter, Lower overhead"
                                            FpsConfig.FPS_60 -> "Smoother animations, More Processing (Default)"
                                        },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colorScheme.primary,
                                )
                            }

                            // Hand Drive Mode Configuration
                            ConfigurationOptionCard(
                                title = "Drive Side",
                                description = "Sets which side the Projection dock appears on.",
                                icon = Icons.Default.DirectionsCar,
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    AudioSourceButton(
                                        label = "Left (LHD)",
                                        icon = Icons.Default.DirectionsCar,
                                        isSelected = selectedHandDrive == HandDriveConfig.LEFT,
                                        onClick = { selectedHandDrive = HandDriveConfig.LEFT },
                                        modifier = Modifier.weight(1f),
                                    )
                                    AudioSourceButton(
                                        label = "Right (RHD)",
                                        icon = Icons.Default.DirectionsCar,
                                        isSelected = selectedHandDrive == HandDriveConfig.RIGHT,
                                        onClick = { selectedHandDrive = HandDriveConfig.RIGHT },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                // HAZARD — PLACEHOLDER COPY shipping to end users.
                                // Both strings are ambiguous/incorrect:
                                //   LEFT  -> "Driving on the Right Side"  (LHD, but the copy
                                //           reads as a location hint rather than LHD label)
                                //   RIGHT -> "Driving on the wrong side"  (joke placeholder)
                                // These must be replaced with proper user-facing copy before
                                // release. Do NOT change the strings here without product
                                // sign-off; only flagged for visibility.
                                Text(
                                    text =
                                        when (selectedHandDrive) {
                                            HandDriveConfig.LEFT -> "Driving on the Right Side"
                                            HandDriveConfig.RIGHT -> "Driving on the wrong side"
                                        },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colorScheme.primary,
                                )
                            }
                        }

                        // ===== Misc Tab =====
                        2 -> {
                            // GPS Forwarding Configuration
                            ConfigurationOptionCard(
                                title = "GPS Forwarding",
                                description = "Forward vehicle GPS. iOS does not use for NAVI",
                                icon = Icons.Default.LocationOn,
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    AudioSourceButton(
                                        label = "Off",
                                        icon = Icons.Default.LocationOn,
                                        isSelected = !selectedGpsForwarding,
                                        onClick = { selectedGpsForwarding = false },
                                        modifier = Modifier.weight(1f),
                                    )
                                    AudioSourceButton(
                                        label = "On",
                                        icon = Icons.Default.LocationOn,
                                        isSelected = selectedGpsForwarding,
                                        onClick = { selectedGpsForwarding = true },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text =
                                        if (selectedGpsForwarding) {
                                            "Enabled — vehicle GPS forwarded adapter"
                                        } else {
                                            "Disabled — phone uses its own GPS"
                                        },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colorScheme.primary,
                                )
                            }

                            // Cluster Navigation Configuration
                            ConfigurationOptionCard(
                                title = "Cluster Navigation",
                                description = "Show Projection turn-by-turn on Instrument Cluster.",
                                icon = Icons.Default.Map,
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    AudioSourceButton(
                                        label = "Off",
                                        icon = Icons.Default.Map,
                                        isSelected = !selectedClusterNavigation,
                                        onClick = { selectedClusterNavigation = false },
                                        modifier = Modifier.weight(1f),
                                    )
                                    AudioSourceButton(
                                        label = "Enabled",
                                        icon = Icons.Default.Map,
                                        isSelected = selectedClusterNavigation,
                                        onClick = { selectedClusterNavigation = true },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text =
                                        if (selectedClusterNavigation) {
                                            "Enabled — Projection Navigation shown on Cluster (requires restart)"
                                        } else {
                                            "Disabled — other navigation apps keep the cluster"
                                        },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colorScheme.primary,
                                )
                            }

                            // WiFi Band Configuration
                            ConfigurationOptionCard(
                                title = "WiFi Band",
                                description = "Wireless band Adapter should use.",
                                icon = Icons.Default.Wifi,
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    AudioSourceButton(
                                        label = "5 GHz",
                                        icon = Icons.Default.Speed,
                                        isSelected = selectedWifiBand == WiFiBandConfig.BAND_5GHZ,
                                        onClick = { selectedWifiBand = WiFiBandConfig.BAND_5GHZ },
                                        modifier = Modifier.weight(1f),
                                    )
                                    AudioSourceButton(
                                        label = "2.4 GHz",
                                        icon = Icons.Default.SignalCellularAlt,
                                        isSelected = selectedWifiBand == WiFiBandConfig.BAND_24GHZ,
                                        onClick = { selectedWifiBand = WiFiBandConfig.BAND_24GHZ },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text =
                                        when (selectedWifiBand) {
                                            WiFiBandConfig.BAND_5GHZ -> "Better speed, less interference (recommended)"
                                            WiFiBandConfig.BAND_24GHZ -> "Fallback (Slow NOT recommended)"
                                        },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colorScheme.primary,
                                )
                            }

                            // Adapter Reset Actions
                            Spacer(modifier = Modifier.height(8.dp))

                            var showRebootDialog by remember { mutableStateOf(false) }

                            FilledTonalButton(
                                onClick = { showRebootDialog = true },
                                enabled = carlinkManager != null,
                                modifier = Modifier.fillMaxWidth().height(AutomotiveDimens.ButtonMinHeight),
                                colors =
                                    ButtonDefaults.filledTonalButtonColors(
                                        containerColor = colorScheme.tertiaryContainer,
                                        contentColor = colorScheme.onTertiaryContainer,
                                    ),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.RestartAlt,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Reboot Adapter",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }

                            // Reboot Confirmation Dialog
                            if (showRebootDialog) {
                                AlertDialog(
                                    onDismissRequest = { showRebootDialog = false },
                                    icon = {
                                        Icon(
                                            imageVector = Icons.Default.RestartAlt,
                                            contentDescription = null,
                                            tint = colorScheme.tertiary,
                                        )
                                    },
                                    title = { Text("Reboot Adapter?") },
                                    text = {
                                        Text("The adapter will restart. It will reconnect automatically in about 15 seconds.")
                                    },
                                    confirmButton = {
                                        TextButton(
                                            onClick = {
                                                showRebootDialog = false
                                                onDismiss()
                                                CoroutineScope(Dispatchers.IO).launch {
                                                    carlinkManager?.rebootAdapter()
                                                }
                                            },
                                        ) {
                                            Text("Reboot", color = colorScheme.tertiary)
                                        }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { showRebootDialog = false }) {
                                            Text("Cancel")
                                        }
                                    },
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Footer with action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Cancel button
                    TextButton(
                        onClick = {
                            logWarn("[UI_ACTION] Adapter Config: Cancel button clicked", tag = "UI")
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Cancel")
                    }

                    // Default button.
                    // HAZARD — INCONSISTENT with Apply: this path resets DataStore preferences
                    // but does NOT reinit the manager, kill the app, or call onReinitAdapter.
                    // The current session keeps running on the OLD in-memory AdapterConfig
                    // until some other trigger fires (version bump, cold start, later Apply).
                    // The log message below says "next session will run FULL init", which
                    // implies the user must restart manually — contrast Apply's auto-restart.
                    // Consider harmonizing the two flows (do not change here without approval).
                    TextButton(
                        onClick = {
                            logWarn(
                                "[UI_ACTION] Adapter Config: Reset to Defaults clicked" +
                                    " - next session will run FULL init",
                                tag = "UI",
                            )
                            scope.launch {
                                adapterConfigPreference.resetToDefaults()
                                onDismiss()
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Default")
                    }

                    // Apply button — tiered restart strategy:
                    // Misc settings (GPS, WiFi, Cluster Nav) → Tier 3: kill app + adapter reboot
                    // Audio/Visual settings only → Tier 2: in-place reinit (no app kill)
                    val miscChanged =
                        selectedGpsForwarding != savedGpsForwarding ||
                            selectedWifiBand != savedWifiBand ||
                            selectedClusterNavigation != savedClusterNavigation

                    Button(
                        onClick = {
                            logWarn(
                                "[UI_ACTION] Adapter Config: Apply clicked" +
                                    " - audio=$selectedAudioSource" +
                                    ", mic=$selectedMicSource" +
                                    ", wifi=$selectedWifiBand" +
                                    ", callQuality=1(hardcoded)" +
                                    ", mediaDelay=$selectedMediaDelay" +
                                    ", resolution=${selectedVideoResolution.toStorageString()}" +
                                    ", fps=${selectedFps.fps}" +
                                    ", handDrive=$selectedHandDrive" +
                                    ", gpsForwarding=$selectedGpsForwarding" +
                                    ", clusterNav=$selectedClusterNavigation" +
                                    ", tier=${if (miscChanged) "3-KILL" else "2-REINIT"}",
                                tag = "UI",
                            )
                            // NOTE: "callQuality=1(hardcoded)" in the log string above encodes
                            // the firmware-bug workaround as a literal. If the BoxSettings
                            // hardcode ever changes, this log will silently lie — keep in sync.
                            scope.launch {
                                // Save all configuration
                                adapterConfigPreference.setAudioSource(selectedAudioSource)
                                adapterConfigPreference.setMicSource(selectedMicSource)
                                adapterConfigPreference.setWifiBand(selectedWifiBand)
                                adapterConfigPreference.setMediaDelay(selectedMediaDelay)
                                adapterConfigPreference.setVideoResolution(selectedVideoResolution)
                                adapterConfigPreference.setFps(selectedFps)
                                adapterConfigPreference.setHandDrive(selectedHandDrive)
                                adapterConfigPreference.setGpsForwarding(selectedGpsForwarding)
                                adapterConfigPreference.setClusterNavigation(selectedClusterNavigation)

                                if (miscChanged) {
                                    // Tier 3: Misc settings changed — always kills the app,
                                    // but ONLY GPS changes trigger an adapter reboot (see
                                    // `needsReboot` below). WiFi-band and cluster-nav changes
                                    // take the app-kill path with stop(reboot=false) — the
                                    // adapter itself is NOT rebooted.
                                    // GPS forwarding: controls whether app sends GNSS_DATA (0x29) to adapter
                                    //   -> needsReboot = true (adapter state change)
                                    // WiFi band: firmware radio reconfiguration
                                    //   -> app kill only; adapter reboot NOT triggered here
                                    // Cluster navigation: CarAppActivity shim lifecycle
                                    //   -> app kill only; adapter reboot NOT triggered here
                                    logWarn(
                                        "[ADAPTER_REINIT] Tier 3: Misc settings changed" +
                                            " (gps=${selectedGpsForwarding != savedGpsForwarding}" +
                                            ", wifi=${selectedWifiBand != savedWifiBand}" +
                                            ", cluster=${selectedClusterNavigation != savedClusterNavigation})" +
                                            " — killing app",
                                        tag = "UI",
                                    )
                                    val needsReboot = selectedGpsForwarding != savedGpsForwarding
                                    carlinkManager?.stop(reboot = needsReboot)
                                    val launchIntent =
                                        context.packageManager
                                            .getLaunchIntentForPackage(context.packageName)
                                            ?.addFlags(
                                                android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                                    android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK,
                                            )
                                    launchIntent?.let { context.startActivity(it) }
                                    // The 500ms delay gives startActivity(launchIntent) time
                                    // to dispatch before we killProcess(myPid()) below —
                                    // without it the new task may never come up.
                                    //
                                    // FRAGILITY: this block runs inside `scope.launch`. If the
                                    // dialog leaves composition before the delay elapses, the
                                    // coroutine is cancelled and killProcess is never called,
                                    // leaving the new task racing the old PID. onDismiss() is
                                    // deliberately NOT called on the Tier-3 path for this reason.
                                    kotlinx.coroutines.delay(500)
                                    android.os.Process.killProcess(android.os.Process.myPid())
                                } else {
                                    // Tier 2: Audio/Visual settings only — in-place reinit.
                                    // Tear down adapter session, rebuild CarlinkManager with
                                    // new AdapterConfig, reconnect with MINIMAL_PLUS_CHANGES.
                                    //
                                    // HAZARD: the MINIMAL_PLUS_CHANGES name is accurate, but
                                    // adapter-side coverage is INCOMPLETE — VIDEO_RESOLUTION
                                    // and FPS have no branches in
                                    // MessageSerializer.addChangedSettings, so changes to those
                                    // two fields alone are saved to DataStore but not pushed to
                                    // the adapter until a FULL init. See HAZARD at the Video
                                    // Resolution / Frame Rate cards in the Visual tab above.
                                    logInfo(
                                        "[ADAPTER_REINIT] Tier 2: Audio/Visual settings only" +
                                            " — reinitializing in-place (no app kill)",
                                        tag = "UI",
                                    )
                                    onDismiss()
                                    onReinitAdapter()
                                }
                            }
                        },
                        modifier = Modifier.weight(1.5f),
                        enabled = hasChanges,
                    ) {
                        Icon(
                            imageVector = if (miscChanged) Icons.Default.RestartAlt else Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (miscChanged) "Apply & Restart" else "Apply")
                    }
                }
            }
        }
    }
}

/**
 * Configuration Option Card - Container for a single configuration option
 *
 * Provides consistent styling for configuration options in the dialog.
 * Designed to be reusable for future configuration options.
 */
@Composable
internal fun ConfigurationOptionCard(
    title: String,
    description: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = colorScheme.surfaceContainerHighest,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            // Header row with icon and title
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = title,
                    style =
                        MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Description
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Option content
            content()
        }
    }
}

/**
 * Audio Source Selection Button
 *
 * Toggle button with animated visual indicator for selected state.
 */
@Composable
private fun AudioSourceButton(
    label: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme

    // Animated properties for smooth selection transitions
    val backgroundColor by animateColorAsState(
        targetValue = if (isSelected) colorScheme.primaryContainer else colorScheme.surfaceContainer,
        label = "backgroundColor",
    )
    val borderWidth by animateDpAsState(
        targetValue = if (isSelected) 2.dp else 1.dp,
        label = "borderWidth",
    )
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) colorScheme.primary else colorScheme.outline,
        label = "borderColor",
    )
    val contentColor by animateColorAsState(
        targetValue = if (isSelected) colorScheme.primary else colorScheme.onSurfaceVariant,
        label = "contentColor",
    )
    val iconScale by animateFloatAsState(
        targetValue = if (isSelected) 1.1f else 1f,
        label = "iconScale",
    )

    Surface(
        onClick = onClick,
        modifier = modifier.height(AutomotiveDimens.ButtonMinHeight),
        shape = MaterialTheme.shapes.medium,
        color = backgroundColor,
        border =
            BorderStroke(
                width = borderWidth,
                color = borderColor,
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = contentColor,
                modifier =
                    Modifier
                        .size(24.dp)
                        .graphicsLayer {
                            scaleX = iconScale
                            scaleY = iconScale
                        },
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                style =
                    MaterialTheme.typography.labelLarge.copy(
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    ),
                color = contentColor,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Resolution Selection Button
 *
 * Compact button for resolution selection with optional "Recommended" badge.
 */
@Composable
private fun ResolutionButton(
    label: String,
    isSelected: Boolean,
    isRecommended: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme

    val backgroundColor by animateColorAsState(
        targetValue = if (isSelected) colorScheme.primaryContainer else colorScheme.surfaceContainer,
        label = "backgroundColor",
    )
    val borderWidth by animateDpAsState(
        targetValue = if (isSelected) 2.dp else 1.dp,
        label = "borderWidth",
    )
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) colorScheme.primary else colorScheme.outline,
        label = "borderColor",
    )
    val contentColor by animateColorAsState(
        targetValue = if (isSelected) colorScheme.primary else colorScheme.onSurfaceVariant,
        label = "contentColor",
    )

    Surface(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        shape = MaterialTheme.shapes.medium,
        color = backgroundColor,
        border =
            BorderStroke(
                width = borderWidth,
                color = borderColor,
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style =
                    MaterialTheme.typography.labelLarge.copy(
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    ),
                color = contentColor,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            if (isRecommended && !isSelected) {
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "(Rec)",
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.tertiary,
                )
            }
        }
    }
}
