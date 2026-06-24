package com.carlink.protocol

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * CPC200-CCPA Protocol Message Serializer
 *
 * Serializes message objects into binary format for transmission to the Carlinkit adapter.
 * Handles header generation, payload encoding, and type-specific serialization.
 */
object MessageSerializer {
    /**
     * Create a protocol header for the given message type and payload length.
     */
    fun createHeader(
        type: MessageType,
        payloadLength: Int,
    ): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(PROTOCOL_MAGIC)
        buffer.putInt(payloadLength)
        buffer.putInt(type.id)
        buffer.putInt(type.id.inv())
        return buffer.array()
    }

    /**
     * Serialize a complete message with header and payload.
     */
    private fun serializeWithPayload(
        type: MessageType,
        payload: ByteArray,
    ): ByteArray {
        val header = createHeader(type, payload.size)
        return header + payload
    }

    /**
     * Serialize a header-only message (no payload).
     */
    private fun serializeHeaderOnly(type: MessageType): ByteArray = createHeader(type, 0)

    // ==================== Command Messages ====================

    /**
     * Serialize a command message.
     */
    fun serializeCommand(command: CommandMapping): ByteArray {
        val payload =
            ByteBuffer
                .allocate(4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(command.id)
                .array()
        return serializeWithPayload(MessageType.COMMAND, payload)
    }

    // ==================== Touch Messages ====================

    /**
     * Touch point data for multi-touch events.
     */
    data class TouchPoint(
        val x: Float,
        val y: Float,
        val action: MultiTouchAction,
        val id: Int,
    )

    /**
     * Serialize a multi-touch event (CarPlay).
     * Uses 0..1 float coordinates, message type 0x17.
     */
    fun serializeMultiTouch(touches: List<TouchPoint>): ByteArray {
        val payload = ByteBuffer.allocate(touches.size * 16).order(ByteOrder.LITTLE_ENDIAN)

        for (touch in touches) {
            payload.putFloat(touch.x)
            payload.putFloat(touch.y)
            payload.putInt(touch.action.id)
            payload.putInt(touch.id)
        }

        return serializeWithPayload(MessageType.MULTI_TOUCH, payload.array())
    }

    // ==================== Audio Messages ====================

    /**
     * Serialize a microphone audio message.
     *
     * @param data Raw PCM audio data
     * @param decodeType Audio format (default: 5 = 16kHz mono)
     * @param audioType Stream type (default: 3 = Siri/voice input)
     * @param volume Volume level (default: 0.0 per protocol)
     */
    fun serializeAudio(
        data: ByteArray,
        decodeType: Int = 5,
        audioType: Int = 3,
        volume: Float = 0.0f,
    ): ByteArray {
        val payload =
            ByteBuffer
                .allocate(12 + data.size)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(decodeType)
                .putFloat(volume)
                .putInt(audioType)
                .put(data)
                .array()

        return serializeWithPayload(MessageType.AUDIO_DATA, payload)
    }

    // ==================== Device Management Messages ====================

    /** Validates a BT MAC address format: "XX:XX:XX:XX:XX:XX" (17 ASCII chars, hex + colons). */
    private val BT_MAC_REGEX = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")

    /** Assert-level validation: throws IllegalArgumentException on malformed input.
     *  Intentional — callers that supply a bad MAC have a programmer-error bug, not a
     *  runtime-recoverable condition. Do not downgrade to a nullable return. */
    private fun requireValidMac(btMac: String) {
        require(BT_MAC_REGEX.matches(btMac)) {
            "Invalid BT MAC address format: \"$btMac\" (expected XX:XX:XX:XX:XX:XX)"
        }
    }

    /**
     * Serialize an AutoConnect_By_BluetoothAddress message (H→A).
     * Tells the adapter to connect to a specific paired device by MAC address.
     *
     * Uses MessageType.WIFI_STATUS_DATA (0x11) — dual-purpose type:
     * A→H = WiFi status data, H→A = AutoConnect_By_BluetoothAddress.
     *
     * @param btMac Bluetooth MAC address (format: "XX:XX:XX:XX:XX:XX")
     */
    fun serializeAutoConnectByBtAddress(btMac: String): ByteArray {
        requireValidMac(btMac)
        val payload = btMac.toByteArray(StandardCharsets.US_ASCII)
        return serializeWithPayload(MessageType.WIFI_STATUS_DATA, payload)
    }

    /**
     * Serialize a ForgetBluetoothAddr message (H→A).
     * Tells the adapter to remove a device from its paired list (DevList → DeletedDevList).
     *
     * @param btMac Bluetooth MAC address (format: "XX:XX:XX:XX:XX:XX")
     */
    fun serializeForgetBluetoothAddr(btMac: String): ByteArray {
        requireValidMac(btMac)
        val payload = btMac.toByteArray(StandardCharsets.US_ASCII)
        return serializeWithPayload(MessageType.FORGET_BLUETOOTH_ADDR, payload)
    }

    // ==================== File Messages ====================

    /**
     * Serialize a file send message.
     */
    fun serializeFile(
        fileName: String,
        content: ByteArray,
    ): ByteArray {
        val fileNameBytes = (fileName + "\u0000").toByteArray(StandardCharsets.US_ASCII)

        val payload =
            ByteBuffer
                .allocate(4 + fileNameBytes.size + 4 + content.size)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(fileNameBytes.size)
                .put(fileNameBytes)
                .putInt(content.size)
                .put(content)
                .array()

        return serializeWithPayload(MessageType.SEND_FILE, payload)
    }

    /**
     * Serialize a number to a file.
     */
    fun serializeNumber(
        number: Int,
        file: FileAddress,
    ): ByteArray {
        val content =
            ByteBuffer
                .allocate(4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(number)
                .array()
        return serializeFile(file.path, content)
    }

    /**
     * Serialize a boolean to a file.
     */
    fun serializeBoolean(
        value: Boolean,
        file: FileAddress,
    ): ByteArray = serializeNumber(if (value) 1 else 0, file)

    /**
     * Serialize a string to a file.
     */
    fun serializeString(
        value: String,
        file: FileAddress,
    ): ByteArray = serializeFile(file.path, value.toByteArray(StandardCharsets.US_ASCII))

    // ==================== Protocol Messages ====================

    /**
     * Serialize a heartbeat message.
     */
    fun serializeHeartbeat(): ByteArray = serializeHeaderOnly(MessageType.HEARTBEAT)

    /** Reboot adapter. Type 0xCD outbound = HUDComand_A_Reboot. Header-only. */
    fun serializeRebootAdapter(): ByteArray = serializeHeaderOnly(MessageType.HEARTBEAT_ECHO)

    /** Disconnect phone's CarPlay/AA session. Type 0x0F outbound. Header-only. */
    fun serializeDisconnectPhone(): ByteArray = serializeHeaderOnly(MessageType.DISCONNECT_PHONE)

    /** Close dongle — stop adapter internal processes. Type 0x15 outbound. Header-only. */
    fun serializeCloseDongle(): ByteArray = serializeHeaderOnly(MessageType.CLOSE_DONGLE)

    /**
     * Serialize an open message with adapter configuration.
     */
    fun serializeOpen(config: AdapterConfig): ByteArray {
        val payload =
            ByteBuffer
                .allocate(28)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(config.width)
                .putInt(config.height)
                .putInt(config.fps)
                .putInt(config.format)
                .putInt(config.packetMax)
                .putInt(config.iBoxVersion)
                .putInt(config.phoneWorkMode)
                .array()

        return serializeWithPayload(MessageType.OPEN, payload)
    }

    /**
     * Serialize box settings message with JSON configuration.
     *
     * Output is not deterministic: the syncTime field is System.currentTimeMillis()
     * unless an explicit [syncTime] is passed. Two consecutive calls produce different
     * bytes — by design; the firmware expects a fresh timestamp each send.
     */
    fun serializeBoxSettings(
        config: AdapterConfig,
        syncTime: Long? = null,
    ): ByteArray {
        val actualSyncTime = syncTime ?: (System.currentTimeMillis() / 1000)

        val json =
            JSONObject().apply {
                put("mediaDelay", config.mediaDelay)
                put("syncTime", actualSyncTime)
                put("mediaSound", 1) // 48kHz only
                // 5GHz channel 36. "WiFiChannel" exact spelling (capital W, capital C)
                // confirmed by adapter boxInfo echo.
                put("WiFiChannel", 36)
                // DashboardInfo bitmask: bit 0=MediaPlayer (album art / now-playing),
                // bit 1=LocationEngine, bit 2=RouteGuidance. 1 = MediaPlayer only — this is a
                // CarPlay-only build with no cluster nav, so only the media engine is requested.
                put("DashboardInfo", 1)
                put("wifiName", config.boxName)
                put("btName", config.boxName)
                put("boxName", config.boxName)
                // OemName removed — actual OEM name comes from /etc/airplay.conf (oemIconLabel).
                put("autoConn", true) // Auto-connect when device detected
            }

        val payload = json.toString().toByteArray(StandardCharsets.US_ASCII)
        com.carlink.logging.logInfo(
            "[BOX_SETTINGS_JSON] size=${payload.size}B json=$json",
            tag = "ADAPTR",
        )
        return serializeWithPayload(MessageType.BOX_SETTINGS, payload)
    }

    /**
     * Generate AirPlay configuration string.
     * oemIconLabel is always "Controls" regardless of box settings (the CarPlay host-UI button
     * opens the in-app controls dashboard).
     * Uses explicit \n (not raw multiline string)
     */
    fun generateAirplayConfig(config: AdapterConfig): String {
        val visible = if (config.oemIconVisible) "1" else "0"
        return "oemIconVisible = $visible\nname = AutoBox\n" +
            "model = Magic-Car-Link-1.00\n" +
            "oemIconPath = /etc/oem_icon.png\n" +
            "oemIconLabel = Controls\n"
    }

    // ==================== Initialization Sequence ====================

    /**
     * Init messages sent AFTER OPEN. [com.carlink.protocol.AdapterDriver] sends OPEN (0x01)
     * first on its own — OPEN is the only per-session-mandatory message and it triggers the
     * adapter's boxInfo (uuid), which the driver waits for before picking the mode below.
     *
     * Ordering contract: [CommandMapping.WIFI_ENABLE] MUST be the final message (activates
     * wireless mode after config). Do not append after it.
     *
     * - FULL (first install / version bump / new adapter): DPI, ViewArea, SafeArea, full config
     *   block, WIFI_ENABLE — all persisted by the adapter for later MINIMAL sessions. DPI is
     *   `/tmp` (firmware-repopulated from flash ScreenDPI); ViewArea/SafeArea + the full block
     *   are `/etc` flash. The wireless connect itself is triggered separately by the
     *   wifiConnect (1002) timer + the persisted NeedAutoConnect flag.
     * - MINIMAL_ONLY (recognized, already-staged adapter): WIFI_ENABLE only.
     *
     * @param config Adapter configuration (hardcoded in this build)
     * @param initMode "FULL" or "MINIMAL_ONLY"
     */
    fun generateInitRemainder(
        config: AdapterConfig,
        initMode: String,
    ): List<ByteArray> {
        val messages = mutableListOf<ByteArray>()

        if (initMode == "FULL") {
            messages.add(serializeNumber(config.dpi, FileAddress.DPI))
            config.viewAreaData?.let {
                messages.add(serializeFile(FileAddress.HU_VIEWAREA_INFO.path, it))
            }
            config.safeAreaData?.let {
                messages.add(serializeFile(FileAddress.HU_SAFEAREA_INFO.path, it))
            }
            addFullSettings(messages, config)
        }

        // WiFi Enable LAST — activates wireless mode after config.
        messages.add(serializeCommand(CommandMapping.WIFI_ENABLE))
        return messages
    }

    /**
     * Add all settings for a FULL initialization (first install / version bump).
     */
    private fun addFullSettings(
        messages: MutableList<ByteArray>,
        config: AdapterConfig,
    ) {
        // Hand drive mode: 0 = Left Hand Drive (LHD), 1 = Right Hand Drive (RHD)
        messages.add(serializeNumber(config.handDriveMode, FileAddress.HAND_DRIVE_MODE))

        // Box name
        messages.add(serializeString(config.boxName, FileAddress.BOX_NAME))

        // Charge mode: 0 = off (no quick charge)
        messages.add(serializeNumber(0, FileAddress.CHARGE_MODE))

        // WiFi band selection (5GHz)
        val wifiCommand = if (config.wifiType == "5ghz") CommandMapping.WIFI_5G else CommandMapping.WIFI_24G
        messages.add(serializeCommand(wifiCommand))

        // Box settings JSON — persisted to riddle.conf by the firmware. Position matters:
        // it lands immediately before the AirPlay config write — firmware may rewrite
        // airplay.conf during BoxSettings processing, so AIRPLAY_CONFIG must come last.
        messages.add(serializeBoxSettings(config))

        // AirPlay configuration AFTER BoxSettings (persists oemIconVisible/oemIconLabel).
        messages.add(serializeString(generateAirplayConfig(config), FileAddress.AIRPLAY_CONFIG))

        // Microphone source
        val micCommand = if (config.micType == "box") CommandMapping.BOX_MIC else CommandMapping.MIC
        messages.add(serializeCommand(micCommand))

        // Audio transfer mode
        val audioTransferCommand =
            if (config.audioTransferMode) {
                CommandMapping.AUDIO_TRANSFER_ON
            } else {
                CommandMapping.AUDIO_TRANSFER_OFF
            }
        messages.add(serializeCommand(audioTransferCommand))
    }
}
