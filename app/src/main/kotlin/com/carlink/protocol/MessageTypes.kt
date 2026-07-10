package com.carlink.protocol

// CPC200-CCPA Protocol Message Types and Constants
//
// This file defines all protocol enums, constants, and data classes for
// communicating with Carlinkit wireless CarPlay/Android Auto adapters.
//

/** Magic number for protocol message headers. */
const val PROTOCOL_MAGIC: Int = 0x55aa55aa

/** Header size in bytes */
const val HEADER_SIZE: Int = 16

/**
 * Decryption key for the SESSION_TOKEN (0xA3) "encrypted blob" the firmware ships
 * during session establishment. The blob is AES-128-CBC-encrypted Base64 holding
 * phone + box telemetry (model, OS, adapter UUID, firmware version, connection
 * statistics). This key decodes it.
 *
 * Decryption recipe:
 * - Algorithm: AES-128-CBC
 * - Key: "W2EC1X1NbZ58TXtn" (16 bytes, ASCII)
 * - IV:  first 16 bytes of the Base64-decoded payload
 * - Ciphertext: remaining bytes after the IV
 * - Output: JSON blob (example structure below)
 *
 * Decrypted JSON structure:
 * ```json
 * {
 *   "phone": {
 *     "model": "iPhone18,4",      // Device model
 *     "osVer": "23D5103d",        // OS build version
 *     "linkT": "CarPlay",         // Link type (CarPlay, AndroidAuto)
 *     "conSpd": 4,                // Connection speed indicator
 *     "conRate": 0.24,            // Historical success rate
 *     "conNum": 17,               // Total connection attempts
 *     "success": 4                // Successful connections
 *   },
 *   "box": {
 *     "uuid": "651ede98...",      // Adapter unique ID
 *     "model": "A15W",            // Adapter model
 *     "hw": "YMA0-WR2C-0003",     // Hardware revision
 *     "ver": "2025.10.15.1127",   // Firmware version
 *     "mfd": "20240119"           // Manufacturing date (YYYYMMDD)
 *   }
 * }
 * ```
 *
 * Current wire-up: [SessionTokenMessage] stores only the encrypted payload size;
 * no in-tree caller decrypts yet. When the decrypt path is added, this constant
 * is the key to use.
 *
 * Reference: documents/reference/firmware/RE_Documention/02_Protocol_Reference/usb_protocol.md
 */
const val SESSION_TOKEN_ENCRYPTION_KEY: String = "W2EC1X1NbZ58TXtn"

/**
 * Command mappings for CPC200-CCPA protocol.
 *
 * Commands are bidirectional - some are sent to adapter (H→A), some received from adapter (A→H),
 * and some are forwarded to/from the connected phone (H→A→P or P→A→H).
 *
 * Reference: documents/reference/firmware/RE_Documention/02_Protocol_Reference/command_ids.md
 */
enum class CommandMapping(
    val id: Int,
) {
    INVALID(0),

    // === Basic Commands (1-31) ===

    // Microphone Recording (H→A→P, adapter echoes back A→H during Siri/call — same semantics)
    START_RECORD_AUDIO(1), // StartRecordMic - Begin mic recording
    STOP_RECORD_AUDIO(2), // StopRecordMic - Stop mic recording

    // UI Control (P→A→H)
    REQUEST_HOST_UI(3), // RequestHostUI - Phone requests host show native UI
    HIDE(14), // Hide - Phone requests hide/minimize projection

    // Bluetooth Control (H→A, also extended format A→H for BT MAC notification)
    DISABLE_BLUETOOTH(4), // DisableBluetooth - Disable adapter Bluetooth

    // Siri/Voice Assistant (H→A→P)
    SIRI(5), // SiriButtonDown - Siri button pressed (initiates Siri)
    SIRI_BUTTON_UP(6), // SiriButtonUp - Siri button released

    // Microphone Source Selection (H→A, adapter echoes back A→H as confirmation)
    MIC(7), // UseCarMic - Use car's microphone
    USE_BOX_MIC(8), // UseBoxMic - Use adapter's built-in microphone
    BOX_MIC(15), // UseBoxI2SMic - Use adapter's I2S microphone
    USE_PHONE_MIC(21), // UsePhoneMic - Use phone's microphone

    // Video Control (H→A)
    FRAME(12), // RequestKeyFrame - Request video IDR frame
    REFRESH_FRAME(26), // RefreshFrame - Force video frame refresh

    // Night Mode (H→A)
    ENABLE_NIGHT_MODE(16), // StartNightMode - Enable dark theme
    DISABLE_NIGHT_MODE(17), // StopNightMode - Disable dark theme

    // GPS/GNSS Forwarding (H→A)
    START_GNSS_REPORT(18), // StartGNSSReport - Start GPS data forwarding to phone
    STOP_GNSS_REPORT(19), // StopGNSSReport - Stop GPS data forwarding

    // Audio Routing (H→A)
    AUDIO_TRANSFER_ON(22), // UseBluetoothAudio - Route audio via Bluetooth
    AUDIO_TRANSFER_OFF(23), // UseBoxTransAudio - Route audio via adapter transmitter

    // WiFi Band Selection (H→A)
    WIFI_24G(24), // Use24GWiFi - Switch to 2.4 GHz WiFi band
    WIFI_5G(25), // Use5GWiFi - Switch to 5 GHz WiFi band

    // Standby Mode (H→A)
    START_STANDBY_MODE(28), // StartStandbyMode - Enter low-power standby
    STOP_STANDBY_MODE(29), // StopStandbyMode - Exit standby mode

    // BLE Advertising (H→A)
    START_BLE_ADV(30), // StartBleAdv - Start BLE advertising for wireless pairing
    STOP_BLE_ADV(31), // StopBleAdv - Stop BLE advertising

    // === D-Pad Control Commands (100-106) - All H→A→P ===
    LEFT(100), // CtrlButtonLeft - D-Pad left
    RIGHT(101), // CtrlButtonRight - D-Pad right
    UP(102), // CtrlButtonUp - D-Pad up
    DOWN_BUTTON(103), // CtrlButtonDown - D-Pad down
    SELECT_DOWN(104), // CtrlButtonEnter - Enter/Select pressed
    SELECT_UP(105), // CtrlButtonRelease - Button release
    BACK(106), // CtrlButtonBack - Back button

    // === Rotary Knob Commands (111-114) - All H→A→P ===
    KNOB_LEFT(111), // CtrlKnobLeft - Knob counter-clockwise
    KNOB_RIGHT(112), // CtrlKnobRight - Knob clockwise
    KNOB_UP(113), // CtrlKnobUp - Knob tilt up
    // Naming collision note: DOWN here is knob-tilt-down (114); the D-pad down is
    // DOWN_BUTTON (103). The identifier DOWN was the original D-pad name and got
    // repurposed when the D-pad was renumbered 100-106; it was NOT renamed because
    // persisted logs / prior call sites still referenced DOWN.
    DOWN(114), // CtrlKnobDown - Knob tilt down (legacy name kept for compatibility)

    // === Media Control Commands (200-205) - All H→A→P ===
    HOME(200), // MusicACHome - Home button
    PLAY(201), // MusicPlay - Play
    PAUSE(202), // MusicPause - Pause
    PLAY_PAUSE(203), // MusicPlayOrPause - Toggle play/pause
    NEXT(204), // MusicNext - Next track
    PREV(205), // MusicPrev - Previous track

    // === Phone Call Commands (300-314) - All H→A→P ===
    PHONE_ANSWER(300), // PhoneAnswer - Answer incoming call
    PHONE_HANG_UP(301), // PhoneHungUp - End/reject call
    PHONE_KEY_0(302), // PhoneKey0 - DTMF tone 0
    PHONE_KEY_1(303), // PhoneKey1 - DTMF tone 1
    PHONE_KEY_2(304), // PhoneKey2 - DTMF tone 2
    PHONE_KEY_3(305), // PhoneKey3 - DTMF tone 3
    PHONE_KEY_4(306), // PhoneKey4 - DTMF tone 4
    PHONE_KEY_5(307), // PhoneKey5 - DTMF tone 5
    PHONE_KEY_6(308), // PhoneKey6 - DTMF tone 6
    PHONE_KEY_7(309), // PhoneKey7 - DTMF tone 7
    PHONE_KEY_8(310), // PhoneKey8 - DTMF tone 8
    PHONE_KEY_9(311), // PhoneKey9 - DTMF tone 9
    PHONE_KEY_STAR(312), // PhoneKeyStar - DTMF tone *
    PHONE_KEY_POUND(313), // PhoneKeyPound - DTMF tone #
    PHONE_HOOK_SWITCH(314), // CarPlay_PhoneHookSwitch - Hook switch toggle

    // === Android Auto Focus Commands (500-507) ===
    REQUEST_VIDEO_FOCUS(500), // RequestVideoFocus (A→H) - Adapter requests host show video
    RELEASE_VIDEO_FOCUS(501), // ReleaseVideoFocus (A→H) - Adapter releases video focus
    REQUEST_AUDIO_FOCUS(502), // RequestAudioFocus (A→H) - AA audio started (binary-verified Feb 2026)
    REQUEST_AUDIO_FOCUS_TRANSIENT(503), // RequestAudioFocusTransient (A→H) - Transient audio focus (binary-verified Feb 2026)
    REQUEST_AUDIO_FOCUS_DUCK(504), // RequestAudioFocusDuck (A→H) - Request audio ducking
    RELEASE_AUDIO_FOCUS(505), // ReleaseAudioFocus (A→H) - Release audio focus
    REQUEST_NAVI_FOCUS(506), // RequestNaviFocus (A→H) - Request navigation audio focus
    RELEASE_NAVI_FOCUS(507), // ReleaseNaviFocus (A→H) - Release navigation focus

    // === Navigation Video Handshake (508-509) - Asymmetric: adapter requests, host MUST echo back ===
    REQUEST_NAVI_SCREEN_FOCUS(508), // RequestNaviScreenFocus (A→H, host echoes H→A) - Triggers HU_NEEDNAVI_STREAM, enables nav video (0x2C)
    RELEASE_NAVI_SCREEN_FOCUS(509), // ReleaseNaviScreenFocus (A→H, host echoes H→A) - Stops nav video streaming

    // === Connection Status Commands (1000-1013) ===

    // Bidirectional — host sends to configure, adapter echoes back as status (A→H)
    WIFI_ENABLE(1000), // SupportWifi (A→H) - Adapter confirms WiFi mode supported
    AUTO_CONNECT_ENABLE(1001), // SupportAutoConnect (A→H) - Adapter confirms auto-connect enabled

    // Host → Adapter
    WIFI_CONNECT(1002), // StartAutoConnect - Start auto-connect scan
    WIFI_PAIR(1012), // WiFiPair - Enter WiFi pairing mode
    GET_BT_ONLINE_LIST(1013), // GetBluetoothOnlineList - Request BT device list

    // Adapter → Host (Status Notifications)
    SCANNING_DEVICE(1003), // ScaningDevices - Adapter scanning for devices
    DEVICE_FOUND(1004), // DeviceFound - Device found during scan
    DEVICE_NOT_FOUND(1005), // DeviceNotFound - No device found
    CONNECT_DEVICE_FAILED(1006), // DeviceConnectFailed - Connection attempt failed
    BT_CONNECTED(1007), // DeviceBluetoothConnected - Bluetooth connected
    BT_DISCONNECTED(1008), // DeviceBluetoothNotConnected - Bluetooth disconnected
    WIFI_CONNECTED(1009), // DeviceWifiConnected - WiFi hotspot: phone connected
    WIFI_DISCONNECTED(1010), // DeviceWifiNotConnected - WiFi hotspot: no phone (NOT session end!)
    BT_PAIR_START(1011), // DeviceBluetoothPairStart - Bluetooth pairing started
    ;

    companion object {
        private val idMap = entries.associateBy { it.id }

        // fromId returns a sentinel (INVALID) rather than throwing. This is a
        // protocol-parsing convention shared across every enum in this file: unknown
        // on-wire values surface as sentinels so the parser can log them and keep
        // running rather than crashing the USB read thread. Do not "fix" one fromId
        // to throw without updating the rest.
        fun fromId(id: Int): CommandMapping = idMap[id] ?: INVALID
    }
}

/**
 * Message type identifiers for CPC200-CCPA protocol.
 *
 * Reference: documents/reference/firmware/RE_Documention/02_Protocol_Reference/usb_protocol.md
 */
enum class MessageType(
    val id: Int,
) {
    // Session Management
    OPEN(0x01),
    PLUGGED(0x02),
    PHASE(0x03),
    UNPLUGGED(0x04),
    DISCONNECT_PHONE(0x0F),
    CLOSE_DONGLE(0x15),
    HEARTBEAT(0xAA),

    // Data Streams
    TOUCH(0x05),
    VIDEO_DATA(0x06),
    AUDIO_DATA(0x07),
    MULTI_TOUCH(0x17),
    NAVI_VIDEO_DATA(0x2C), // Navigation video (iOS 13+), same structure as VIDEO_DATA

    // Control Commands
    COMMAND(0x08),
    LOGO_TYPE(0x09),

    // Device Information
    BLUETOOTH_ADDRESS(0x0A),
    BLUETOOTH_PIN(0x0C),
    BLUETOOTH_DEVICE_NAME(0x0D),
    WIFI_DEVICE_NAME(0x0E),
    BLUETOOTH_PAIRED_LIST(0x12),
    MANUFACTURER_INFO(0x14),
    SOFTWARE_VERSION(0xCC),
    STATUS_VALUE(0xBB), // A→H: Status/config value. H→A: UpdateState (macOS: updateState) — not used here. See dual-direction block below.

    // Configuration
    BOX_SETTINGS(0x19),
    SEND_FILE(0x99),

    // Peer Device / Bluetooth State (A→H)
    HI_CAR_LINK(0x18),
    PEER_BLUETOOTH_ADDRESS(0x23), // Binary: Bluetooth_ConnectStart — BT connection started, carries peer MAC
    PEER_BLUETOOTH_ADDRESS_ALT(0x24), // Binary: Bluetooth_Connected — BT connected, carries peer MAC
    FORGET_BLUETOOTH_ADDR(0x22), // BOTH: Remove/forget a BT address from adapter's paired list (MAC payload)
    UI_HIDE_PEER_INFO(0x25), // Binary: Bluetooth_DisConnect — BT disconnected / hide peer info
    UI_BRING_TO_FOREGROUND(0x26), // Binary: Bluetooth_Listen — BT listening/advertising / bring to foreground

    // GPS/GNSS
    GNSS_DATA(0x29), // NMEA GPS data to adapter (H→A)

    // Media Metadata
    MEDIA_DATA(0x2A),

    // Session Establishment
    SESSION_TOKEN(0xA3), // Encrypted session telemetry (AES-128-CBC)

    // Navigation Focus (iOS 13+)
    NAVI_FOCUS_REQUEST(0x6E), // Nav requesting focus (IN)
    NAVI_FOCUS_RELEASE(0x6F), // Nav released focus (IN)

    // Additional Adapter→Host Messages (Firmware Binary Analysis)
    CARPLAY_CONTROL(0x0B), // CMD_ACK / CarPlay control acknowledgment
    DASHBOARD_DATA(0x10), // Dashboard/instrument cluster data (DashBoard_DATA)
    WIFI_STATUS_DATA(0x11), // A→H: WiFi status info; H→A: AutoConnect_By_BluetoothAddress (MAC payload)
    DISK_INFO(0x13), // Adapter disk/storage information
    DEVICE_EXTENDED_INFO(0x1B), // Extended device information
    REMOTE_CX_CY(0x1E), // Display resolution broadcast from adapter
    EXTENDED_MFG_INFO(0xA1), // Extended OEM/manufacturer data
    FACTORY_SETTING(0x77), // Factory setting idle notification (A→H: empty) / factory reset (H→A: 4B)
    // Dual-direction wire IDs. These enum names reflect the INBOUND (adapter→host) meaning,
    // which is how the Android parser routes them. Firmware reuses the same IDs with
    // different outbound semantics; OUTBOUND senders (MessageSerializer.serializeRebootAdapter,
    // serializeUsbReset) piggy-back on the inbound enum entry for wire-type encoding —
    // the name does NOT reflect what's being sent. Cross-referenced against macOS
    // carlink_macOS/Protocol/MessageTypes.swift which models each direction separately.
    //
    //   0x88  A→H: AdapterIdle state notification           | H→A: DebugTest   (macOS: debugTest)
    //   0xBB  A→H: Status/config value                      | H→A: UpdateState (macOS: updateState) — not used by Android outbound
    //   0xCD  A→H: Heartbeat ack (every 2s)                 | H→A: Reboot adapter — USED OUTBOUND via serializeRebootAdapter (MessageSerializer.kt:291)
    //   0xCE  A→H: Error report                             | H→A: USB reset   — USED OUTBOUND via serializeUsbReset     (MessageSerializer.kt:296)
    //   0xF0  A→H: Remote display parameters                | H→A: Enable crypt (CMD_ENABLE_CRYPT)
    //   0xFD  A→H: Firmware update progress                 | H→A: Display ready (macOS: displayReady) — not used by Android outbound
    //
    // When adding an outbound-only message with one of these IDs, prefer a named helper in
    // MessageSerializer (like serializeRebootAdapter) over a direct enum reference, so the
    // call-site documents intent.
    ADAPTER_IDLE(0x88),     // A→H name; H→A = debugTest (not used here)
    HEARTBEAT_ECHO(0xCD),   // A→H name; H→A = rebootAdapter (MessageSerializer.serializeRebootAdapter)
    ERROR_REPORT(0xCE),     // A→H name; H→A = resetUSB     (MessageSerializer.serializeUsbReset)
    REMOTE_DISPLAY(0xF0),   // A→H name; H→A = enableCrypt (not used here)
    UPDATE_PROGRESS(0xFD),  // A→H name; H→A = displayReady (not used here)
    DEBUG_TRACE(0xFF), // Debug/trace data from adapter

    // Unrecognised on-wire type ID surfaces here; MessageParser routes these into
    // UnknownMessage, which CarlinkManager logs under Logger.Tags.PROTO_UNKNOWN so
    // they always reach file logs regardless of preset.
    UNKNOWN(-1),
    ;

    // Legacy aliases for backwards compatibility
    @Deprecated("Use PEER_BLUETOOTH_ADDRESS", ReplaceWith("PEER_BLUETOOTH_ADDRESS"))
    val NETWORK_MAC_ADDRESS get() = PEER_BLUETOOTH_ADDRESS

    @Deprecated("Use PEER_BLUETOOTH_ADDRESS_ALT", ReplaceWith("PEER_BLUETOOTH_ADDRESS_ALT"))
    val NETWORK_MAC_ADDRESS_ALT get() = PEER_BLUETOOTH_ADDRESS_ALT

    @Deprecated("Use CLOSE_DONGLE", ReplaceWith("CLOSE_DONGLE"))
    val CLOSE_ADAPTR get() = CLOSE_DONGLE

    companion object {
        private val idMap = entries.associateBy { it.id }

        fun fromId(id: Int): MessageType = idMap[id] ?: UNKNOWN
    }
}

/**
 * Audio command identifiers for stream control.
 */
enum class AudioCommand(
    val id: Int,
) {
    AUDIO_OUTPUT_START(1),
    AUDIO_OUTPUT_STOP(2),
    AUDIO_INPUT_CONFIG(3),
    AUDIO_PHONECALL_START(4),
    AUDIO_PHONECALL_STOP(5),
    AUDIO_NAVI_START(6),
    AUDIO_NAVI_STOP(7),
    AUDIO_SIRI_START(8),
    AUDIO_SIRI_STOP(9),
    AUDIO_MEDIA_START(10),
    AUDIO_MEDIA_STOP(11),
    AUDIO_ALERT_START(12),
    AUDIO_ALERT_STOP(13),
    AUDIO_INCOMING_CALL_INIT(14),
    AUDIO_TBT_START(15),
    AUDIO_NAVI_COMPLETE(16),
    UNKNOWN(-1),
    ;

    companion object {
        private val idMap = entries.associateBy { it.id }

        fun fromId(id: Int): AudioCommand = idMap[id] ?: UNKNOWN
    }
}

/**
 * Stream purpose for audio routing and AudioFocus management.
 *
 * Derived from AudioCommand packets (SIRI_START, PHONECALL_START, etc.)
 * to create per-purpose AudioTracks with correct AudioAttributes/USAGE,
 * enabling AAOS to route streams to the correct audio zones.
 */
enum class StreamPurpose {
    MEDIA,       // Music, podcasts (default)
    PHONE_CALL,  // Active phone call
    SIRI,        // Voice assistant
    ALERT,       // System alerts/notifications
    RINGTONE,    // Incoming call ring
    NAVIGATION,  // Turn-by-turn (has own track, included for completeness)
    ;

    companion object {
        val DEFAULT = MEDIA
    }
}

/**
 * Audio routing state flags for two-factor PCM routing.
 *
 * Routes audio by combining active command state with format match,
 * rather than a single mutable purpose. This prevents transition artifacts
 * where media PCM briefly routes to the wrong AudioTrack.
 */
data class AudioRoutingState(
    val isSiriActive: Boolean = false,
    val isPhoneCallActive: Boolean = false,
    val isAlertActive: Boolean = false,
)

/**
 * File addresses for adapter configuration files.
 */
enum class FileAddress(
    val path: String,
) {
    DPI("/tmp/screen_dpi"),
    NIGHT_MODE("/tmp/night_mode"),
    HAND_DRIVE_MODE("/tmp/hand_drive_mode"),
    CHARGE_MODE("/tmp/charge_mode"),
    BOX_NAME("/etc/box_name"),
    AIRPLAY_CONFIG("/etc/airplay.conf"),
    ANDROID_WORK_MODE("/etc/android_work_mode"),
    OEM_ICON("/etc/oem_icon.png"),
    ICON_120("/etc/icon_120x120.png"),
    ICON_180("/etc/icon_180x180.png"),
    ICON_256("/etc/icon_256x256.png"),
    HU_VIEWAREA_INFO("/etc/RiddleBoxData/HU_VIEWAREA_INFO"),
    HU_SAFEAREA_INFO("/etc/RiddleBoxData/HU_SAFEAREA_INFO"),
    UNKNOWN(""),
    ;

    companion object {
        private val pathMap = entries.associateBy { it.path }

        fun fromPath(path: String): FileAddress = pathMap[path] ?: UNKNOWN
    }
}

/**
 * Phone type identifiers for connected devices.
 *
 * Reference: documents/reference/firmware/RE_Documention/02_Protocol_Reference/usb_protocol.md
 */
enum class PhoneType(
    val id: Int,
) {
    ANDROID_MIRROR(1),
    CARLIFE(2),
    CARPLAY(3),
    IPHONE_MIRROR(4),
    ANDROID_AUTO(5),
    HI_CAR(6),
    ICCOA(7),
    CARPLAY_WIRELESS(8),
    UNKNOWN(-1),
    ;

    companion object {
        private val idMap = entries.associateBy { it.id }

        fun fromId(id: Int): PhoneType = idMap[id] ?: UNKNOWN
    }
}

/**
 * Media type identifiers for media metadata messages.
 */
enum class MediaType(
    val id: Int,
) {
    DATA(1),
    ALBUM_COVER_AA(2), // Android Auto album art — PNG at offset +4 (subtype 2)
    ALBUM_COVER(3),    // CarPlay album art — JPEG at offset +4 (subtype 3)
    CALL_STATUS(100),  // iAP2 CallStateEngine — JSON: CallStatus/CallDirection/CallID/CallName/CallNumber
    NAVI_JSON(200),
    NAVI_IMAGE(201),
    UNKNOWN(-1),
    ;

    companion object {
        private val idMap = entries.associateBy { it.id }

        fun fromId(id: Int): MediaType = idMap[id] ?: UNKNOWN
    }
}

/**
 * Multi-touch action types.
 *
 * The `.id` values (0/1/2) are the CarPlay multi-touch (0x17) codes. Android Auto
 * single-touch (0x05) uses a DIFFERENT code space (14=DOWN, 15=MOVE, 16=UP);
 * [MessageSerializer.serializeSingleTouch] maps this enum into those codes at
 * serialization time. Don't assume `.id` is the universal wire value.
 */
enum class MultiTouchAction(
    val id: Int,
) {
    DOWN(1),
    MOVE(2),
    UP(0),
    UNKNOWN(-1),
    ;

    companion object {
        private val idMap = entries.associateBy { it.id }

        fun fromId(id: Int): MultiTouchAction = idMap[id] ?: UNKNOWN
    }
}

/**
 * Known Carlinkit USB device identifiers (vendorId, productId).
 *
 * Sourced from USB captures and observed CPC200-CCPA variants. Consumed
 * by UsbDeviceWrapper.isKnownDevice at attach time; only these VID/PID pairs are
 * accepted. Add new pairs here if a newer adapter SKU ships with a different ID.
 */
object KnownDevices {
    val DEVICES =
        listOf(
            Pair(0x1314, 0x1520),        // CPC200-CCPA primary
            Pair(0x1314, 0x1521),        // CPC200-CCPA variant
            Pair(0x08E4, 0x01C0),        // Alternate SKU observed in captures
        )

    fun isKnownDevice(
        vendorId: Int,
        productId: Int,
    ): Boolean = DEVICES.any { it.first == vendorId && it.second == productId }
}

/**
 * Adapter configuration for protocol initialization.
 *
 * Note on nullable configuration options:
 * - null = not configured by user, command will NOT be sent to adapter
 * - non-null = user explicitly configured, command WILL be sent to adapter
 *
 * This allows the adapter to retain its current settings for unconfigured options,
 * since the adapter firmware persists most settings through power cycles.
 */
data class AdapterConfig(
    var width: Int = 1920,
    var height: Int = 720,
    var fps: Int = 60,
    var dpi: Int = 160,
    /** True if user explicitly selected a resolution (non-AUTO). When true, CarlinkManager
     *  will use these width/height values instead of actual surface dimensions. */
    val userSelectedResolution: Boolean = false,
    val format: Int = 5,
    val iBoxVersion: Int = 2,
    val packetMax: Int = 49152,
    val phoneWorkMode: Int = 2,
    val boxName: String = "carlink",
    val mediaDelay: Int = 1000,
    /** Audio transfer mode: true=bluetooth, false=adapter (USB audio, default) */
    val audioTransferMode: Boolean = false,
    /** Sample rate for media audio (48000 Hz). Controls mediaSound in BoxSettings. */
    val sampleRate: Int = 48000,
    val wifiType: String = "5ghz",
    val micType: String = "os",
    /** Call quality: 0=normal, 1=clear, 2=HD. Sent in BoxSettings. */
    val callQuality: Int = 2,
    val oemIconVisible: Boolean = true,
    val androidWorkMode: Boolean = true,
    /** Hand drive mode: 0 = LHD (dock left, US/EU default), 1 = RHD (dock right, UK/JP/AU) */
    val handDriveMode: Int = 0,
    /** GPS forwarding: true = app sends GNSS_DATA (0x29) to adapter for CarPlay, false = no GPS data sent. Adapter-side GNSSCapability=3 is always set in BoxSettings. */
    val gpsForwarding: Boolean = false,
    val icon120Data: ByteArray? = null,
    val icon180Data: ByteArray? = null,
    val icon256Data: ByteArray? = null,
    /** ViewArea binary data (24B) for adapter — always sent */
    val viewAreaData: ByteArray? = null,
    /** SafeArea binary data (20B) for adapter — always sent */
    val safeAreaData: ByteArray? = null,
    /** Bundled aa_gps_fix.sh content (NMEA divisor in-memory patcher). Pushed to
     *  /tmp/aa_gps_fix.sh on every init (full + minimal) because adapter power-cycle
     *  between sessions wipes /tmp. Placement only — invocation is a separate step. */
    val gpsFixScriptData: ByteArray? = null,
    /** Bundled patched ARMiPhoneIAP2 (NaviJSON _iap2 + _iap2m roundabout recovery).
     *  Pushed to /tmp/bin/ARMiPhoneIAP2 on every init to preempt phone_link_deamon's
     *  factory-copy step, so the next CarPlay session execs our patched binary.
     *  Atomic rename means a session in progress is unaffected (running iAP2 keeps
     *  the old inode via mmap; next respawn picks up the new file). */
    val patchedIap2BinaryData: ByteArray? = null,
) {
    companion object {
        val DEFAULT = AdapterConfig()
    }

    // equals/hashCode are PARTIAL by design: they cover only the fields whose
    // change triggers a display-mode-level re-init (resolution, DPI, format, box
    // name, icon assets, view/safe area). Fields that affect only BoxSettings-level
    // config (audioTransferMode, sampleRate, wifiType, micType, callQuality,
    // oemIconVisible, androidWorkMode, handDriveMode, gpsForwarding, mediaDelay,
    // iBoxVersion, packetMax, phoneWorkMode, userSelectedResolution) are tracked
    // separately via AdapterConfigPreference's pendingChanges set and MINIMAL_PLUS_CHANGES
    // init mode — they don't need to invalidate AdapterConfig equality to propagate.
    //
    // Consequence: two AdapterConfigs differing only in those config fields will be
    // equal() and produce the same hashCode(). Do NOT use AdapterConfig as a HashMap
    // key or HashSet member expecting full-field distinction.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AdapterConfig
        return width == other.width &&
            height == other.height &&
            fps == other.fps &&
            dpi == other.dpi &&
            format == other.format &&
            boxName == other.boxName &&
            icon120Data.contentEquals(other.icon120Data) &&
            icon180Data.contentEquals(other.icon180Data) &&
            icon256Data.contentEquals(other.icon256Data) &&
            viewAreaData.contentEquals(other.viewAreaData) &&
            safeAreaData.contentEquals(other.safeAreaData)
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + fps
        result = 31 * result + dpi
        result = 31 * result + boxName.hashCode()
        result = 31 * result + (icon120Data?.contentHashCode() ?: 0)
        result = 31 * result + (icon180Data?.contentHashCode() ?: 0)
        result = 31 * result + (icon256Data?.contentHashCode() ?: 0)
        result = 31 * result + (viewAreaData?.contentHashCode() ?: 0)
        result = 31 * result + (safeAreaData?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * Message header for CPC200-CCPA protocol.
 */
data class MessageHeader(
    val length: Int,
    val type: MessageType,
    /** Raw type integer from the USB header — preserved even when type maps to UNKNOWN */
    val rawType: Int = type.id,
) {
    override fun toString(): String = "MessageHeader(length=$length, type=${type.name})"
}

/**
 * Exception thrown when message header parsing fails.
 */
class HeaderParseException(
    message: String,
) : Exception(message)
