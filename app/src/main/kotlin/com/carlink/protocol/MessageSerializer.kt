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

    /**
     * Serialize a single-touch event (Android Auto).
     * Uses 0..10000 integer coordinates, message type 0x05.
     * AA adapter firmware expects this format — different from CarPlay multitouch.
     *
     * Payload (16 bytes LE):
     *   [0-3]:  action code (14=DOWN, 15=MOVE, 16=UP)
     *   [4-7]:  x coordinate (0..10000)
     *   [8-11]: y coordinate (0..10000)
     *   [12-15]: flags = encoderType | (offScreen << 16)
     *            encoderType: 1=H264, 2=H265, 4=MJPEG (current video encoder state)
     *            offScreen: 0=on-screen, 1=off-screen
     *
     * @param encoderType Current video encoder type from video header flags (default 2 = H265/initial)
     * @param offScreen Current off-screen state from video header flags (default 0 = on-screen)
     */
    fun serializeSingleTouch(
        x: Int,
        y: Int,
        action: MultiTouchAction,
        encoderType: Int = 2,
        offScreen: Int = 0,
    ): ByteArray {
        // Action codes 14/15/16 (0x0e/0x0f/0x10) for DOWN/MOVE/UP — documented in
        // documents/reference/adapter/RE_Documention/02_Protocol_Reference/usb_protocol.md:1117
        // and host_app_guide.md:405-414. Unknown actions silently drop — add PROTO_UNKNOWN
        // logging here if a new touch action ever appears in MultiTouchAction.
        val actionCode =
            when (action) {
                MultiTouchAction.DOWN -> 14
                MultiTouchAction.MOVE -> 15
                MultiTouchAction.UP -> 16
                else -> return ByteArray(0)
            }
        val payload = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        payload.putInt(actionCode)
        payload.putInt(x.coerceIn(0, 10000))
        payload.putInt(y.coerceIn(0, 10000))
        payload.putInt(encoderType or (offScreen shl 16))
        return serializeWithPayload(MessageType.TOUCH, payload.array())
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

    // ==================== GNSS Messages ====================

    /**
     * Serialize GNSS/NMEA data for forwarding to adapter.
     *
     * Payload format (Type 0x29):
     *   [0x00] nmeaLength (4B LE uint32) - length of NMEA data
     *   [0x04] nmeaData (N bytes)        - NMEA 0183 ASCII sentences
     *
     * The adapter forwards this to the iPhone via iAP2 LocationInformation.
     *
     * @param nmeaSentences NMEA 0183 sentences (CR+LF terminated)
     */
    fun serializeGnssData(nmeaSentences: String): ByteArray {
        val nmeaBytes = nmeaSentences.toByteArray(Charsets.US_ASCII)
        val payload =
            ByteBuffer
                .allocate(4 + nmeaBytes.size)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(nmeaBytes.size)
                .put(nmeaBytes)
                .array()
        return serializeWithPayload(MessageType.GNSS_DATA, payload)
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

    /** USB-level reset only (softer than reboot). Type 0xCE outbound = HUDComand_A_ResetUSB.
     *  CURRENTLY UNUSED — kept as the softer counterpart to serializeRebootAdapter in case
     *  a USB-reset recovery path is wired up. Delete if no use emerges. */
    fun serializeUsbReset(): ByteArray = serializeHeaderOnly(MessageType.ERROR_REPORT)

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
        /** Actual video surface width — use instead of config when available (Compose insets may differ from WindowMetrics). */
        surfaceWidth: Int = 0,
        /** Actual video surface height. */
        surfaceHeight: Int = 0,
    ): ByteArray {
        val actualSyncTime = syncTime ?: (System.currentTimeMillis() / 1000)

        // Android Auto H.264 stream resolution selection.
        // Google AA only supports 3 fixed H.264 resolutions: 800x480, 1280x720, 1920x1080.
        // androidAutoSizeW = tier width, androidAutoSizeH = content height within the frame.
        // The phone renders content in a centered band, with black bars filling the rest.
        // The host SurfaceView is oversized to the tier AR and clipped to remove the bars.
        //
        // Use actual surface dims for AR calculation. On AAOS, WindowMetrics (config) may subtract
        // dock/nav insets that Compose's WindowInsets.systemBars doesn't, causing a mismatch.
        // The surface dims are the ground truth for the actual view size.
        val w = if (surfaceWidth > 0) surfaceWidth else config.width
        val h = if (surfaceHeight > 0) surfaceHeight else config.height
        val displayAR = w.toFloat() / h.toFloat()

        val (tierWidth, tierHeight) =
            when {
                w >= 1920 -> Pair(1920, 1080)
                w >= 1280 -> Pair(1280, 720)
                else -> Pair(800, 480)
            }
        val aaWidth = tierWidth
        val aaHeight = ((tierWidth.toFloat() / displayAR).toInt() and 0xFFFE).coerceAtMost(tierHeight)
        com.carlink.logging.logInfo(
            "[AA_BOXSETTINGS] surface=${w}x$h config=${config.width}x${config.height} " +
                "displayAR=${"%.3f".format(displayAR)} tier=${tierWidth}x$tierHeight aaSize=${aaWidth}x$aaHeight",
            tag = "ADAPTR",
        )

        val json =
            JSONObject().apply {
                put("mediaDelay", config.mediaDelay)
                put("syncTime", actualSyncTime)
                put("androidAutoSizeW", aaWidth)
                put("androidAutoSizeH", aaHeight)
                put("mediaSound", 1) // 48kHz only
                // CallQuality / VoiceQuality intentionally NOT sent.
                // The firmware's CMD_BOX_INFO transform rejects both with
                // "apk callQuality value transf box value error" / "apk VoiceQuality value transf box value error",
                // so sending them here is logged-and-dropped — they never reach /etc/riddle.conf
                // regardless of init mode. The only path that actually persists either value is
                // `riddleBoxCfg -s CallQuality|VoiceQuality <n>` on the adapter shell.
                // The UI surface for CallQuality was removed (AdapterConfigurationDialog.kt:418)
                // because value 2 (24kHz) is documented to soft-brick mic input — adapter's
                // AirPlay AudioStream input buffer is hardcoded 640B (16kHz × 20ms × 2B), so 24kHz
                // mic frames (960B) get dropped entirely. The vestigial CallQualityConfig surface
                // in AdapterConfigPreference.kt is kept only for round-trip safety on upgrades.
                // 5GHz channel 36. "WiFiChannel" exact spelling (capital W, capital C)
                // confirmed by adapter boxInfo echo (cpc200_20260420_063747.log:539).
                // Firmware internal "BrandWifiChannel" is a different field.
                put("WiFiChannel", 36)
                // DashboardInfo bitmask: bit 0=MediaPlayer, bit 1=LocationEngine, bit 2=RouteGuidance
                // 7 = all engines enabled. Adapter forwards all data; app decides what to use.
                put("DashboardInfo", 7)
                // GNSSCapability: bitmask for iAP2 GPS — bit 0=GPGGA, bit 1=GPRMC.
                // Always 3 (both enabled). The pipeline is always open on the adapter side;
                // GPS forwarding is gated by whether the app sends GNSS_DATA (0x29) messages.
                put("GNSSCapability", 3)
                put("wifiName", config.boxName)
                put("btName", config.boxName)
                put("boxName", config.boxName)
                // OemName removed — persists to dead storage in riddle.conf; actual OEM name
                // comes from /etc/airplay.conf (oemIconLabel), which the app writes separately.
                put("autoConn", true) // Auto-connect when device detected
                put("autoPlay", false) // Don't auto-play media on connection
            }

        val payload = json.toString().toByteArray(StandardCharsets.US_ASCII)
        return serializeWithPayload(MessageType.BOX_SETTINGS, payload)
    }

    /**
     * DISABLED 2026-05-21 — not wired into any init path (the call site in addFullSettings is
     * commented out). Kept visible for future use against firmware KNOWN to be shell-vulnerable.
     * Re-enable caveat: `config.boxName` is interpolated UNESCAPED into the inner `sed` command
     * below — a boxName containing `"`, `;`, `/`, `$()` or backticks corrupts the payload and
     * can run unintended commands as root; sanitize/escape it before re-enabling.
     *
     * Serialize a one-shot BoxSettings frame that exploits the CustomWifiName shell-injection
     * vulnerability in the adapter firmware to persist safe audio quality values via riddleBoxCfg.
     *
     * The firmware passes the JSON wifiName field unsanitized as the replacement value in a
     * `sed -i` substitution against /etc/hostapd.conf, with the value placed inside double quotes
     * (the variable expands as `${CustomWifiName}`). Injecting an unescaped double-quote breaks
     * out into shell command position, where any chained command runs as root — which is the
     * only reliable path that reaches VoiceQuality / CallQuality in /etc/riddle.conf. The
     * BoxSettings JSON path is broken for these keys: CMD_BOX_INFO's transform rejects them
     * with "apk callQuality value transf box value error" — logged-and-dropped, never written
     * to disk regardless of init mode.
     *
     * Payload chain (after firmware shell variable expansion):
     *   1. orphaned sed call from the firmware's substitution — no file arg, busybox sed errors out, harmless
     *   2. `riddleBoxCfg -s VoiceQuality 1` — persists VoiceQuality=1 to /etc/riddle.conf
     *   3. `riddleBoxCfg -s CallQuality 1`  — persists CallQuality=1 to /etc/riddle.conf
     *   4. our own sed call against /etc/hostapd.conf with the real boxName — restores the correct SSID
     *   5. `#` at end — shell comment, swallows the firmware's trailing fragment
     *
     * Idempotent: safe to fire on every FULL init. If firmware is ever patched, the literal
     * wifiName string becomes a garbage SSID that the immediately-following normal BoxSettings
     * frame overwrites — no permanent damage.
     *
     * Only the fields needed to deliver the injection are included. The full config (DashboardInfo,
     * GNSSCapability, androidAutoSizeW/H, etc.) is written by the normal BoxSettings that follows.
     */
    @Suppress("unused", "detekt:UnusedPrivateMember")
    private fun serializeQualityRescueBoxSettings(config: AdapterConfig): ByteArray {
        // Closes firmware's unsanitized sed double-quote, runs riddleBoxCfg as root,
        // restores the real SSID, then comments out the firmware's trailing sed fragment.
        val injectionPayload =
            "dummy\"; riddleBoxCfg -s VoiceQuality 1; riddleBoxCfg -s CallQuality 1; " +
                "sed -i \"s/^ssid=.*/ssid=${config.boxName}/\" /etc/hostapd.conf; #"

        val json =
            JSONObject().apply {
                put("wifiName", injectionPayload)
                put("btName", config.boxName)
                put("boxName", config.boxName)
                put("syncTime", System.currentTimeMillis() / 1000)
            }
        val payload = json.toString().toByteArray(StandardCharsets.US_ASCII)
        return serializeWithPayload(MessageType.BOX_SETTINGS, payload)
    }

    /**
     * Serialize a one-shot BoxSettings frame that invokes /tmp/aa_gps_fix.sh
     * via the firmware's unsanitized wifiName shell vulnerability.
     *
     * Uses POSIX command substitution inside the wifiName value (safer variant
     * than serializeQualityRescueBoxSettings, which closes out the sed double
     * quote and corrupts hostapd.conf momentarily). The command substitution
     * runs our watcher script in the background with nohup so the substitution
     * returns an empty string. The wifiName then collapses to the user's real
     * boxName, and the firmware sed runs a no-op idempotent overwrite of the
     * existing ssid line. Net effect: hostapd.conf is never corrupted, and the
     * watcher script is launched as an init-orphan child after our spawning sh
     * exits. The script has its own PID-file guard to prevent stacking when
     * init re-fires without an adapter reboot.
     *
     * Pairs with the SendFile placement of aa_gps_fix.sh in the same init
     * preamble: placement first via USB OUT, invocation second via this
     * BoxSettings popen side effect. Script lives only in /tmp; adapter
     * power-cycle wipes it and the next init re-pushes plus re-invokes.
     *
     * If firmware is ever patched to sanitize wifiName, the command-substitution
     * chars become a garbage SSID or sed fails benignly, and the normal
     * BoxSettings emitted earlier in init has already written the correct
     * hostapd.conf. Idempotent and safe to fire on every init.
     */
    fun serializeBoxSettingsGpsFixInject(config: AdapterConfig): ByteArray {
        // Command-substitution syntax: dollar-paren runs the inner command in
        // a subshell. nohup detaches from controlling terminal; redirects null
        // out stdout+stderr so the substitution returns empty. Result: wifiName
        // after shell expansion = literally config.boxName.
        val injectionPayload =
            config.boxName + "\$(nohup sh /tmp/aa_gps_fix.sh >/dev/null 2>&1 &)"

        val json =
            JSONObject().apply {
                put("wifiName", injectionPayload)
            }
        val payload = json.toString().toByteArray(StandardCharsets.US_ASCII)
        return serializeWithPayload(MessageType.BOX_SETTINGS, payload)
    }

    /**
     * Generate AirPlay configuration string.
     * oemIconLabel is always "Exit" regardless of box settings.
     * Uses explicit \n (not raw multiline string)
     */
    fun generateAirplayConfig(config: AdapterConfig): String {
        val visible = if (config.oemIconVisible) "1" else "0"
        return "oemIconVisible = $visible\nname = AutoBox\n" +
            "model = Magic-Car-Link-1.00\n" +
            "oemIconPath = /etc/oem_icon.png\n" +
            "oemIconLabel = Exit\n"
    }

    // ==================== Initialization Sequence ====================

    /**
     * Configuration keys matching AdapterConfigPreference.ConfigKey.
     * Used to identify which settings to send for delta configuration.
     */
    object ConfigKey {
        const val AUDIO_SOURCE = "audio_source"
        const val MIC_SOURCE = "mic_source"
        const val WIFI_BAND = "wifi_band"
        const val CALL_QUALITY = "call_quality"
        const val MEDIA_DELAY = "media_delay"
        const val HAND_DRIVE = "hand_drive_mode"
        const val GPS_FORWARDING = "gps_forwarding"
    }

    /**
     * Generate initialization messages based on init mode and pending changes.
     *
     * Ordering contract: [CommandMapping.WIFI_ENABLE] MUST be the final message in the
     * returned list in ALL three branches — it activates the wireless mode after all
     * config is applied. Do not append messages after WIFI_ENABLE in any code path.
     *
     * @param config Adapter configuration with all current values
     * @param initMode The initialization mode (FULL, MINIMAL_PLUS_CHANGES, MINIMAL_ONLY)
     * @param pendingChanges Set of config keys that have changed since last init
     * @return List of serialized messages to send to the adapter
     */
    fun generateInitSequence(
        config: AdapterConfig,
        initMode: String,
        pendingChanges: Set<String> = emptySet(),
        surfaceWidth: Int = 0,
        surfaceHeight: Int = 0,
    ): List<ByteArray> {
        val messages = mutableListOf<ByteArray>()

        // === MINIMAL CONFIG: Always sent (every session) ===
        // - DPI: stored in /tmp/ which is cleared on adapter power cycle
        // - Open: display dimensions may change between sessions
        // - BoxSettings: androidAutoSizeW/H depends on display AR which changes with display mode.
        //   Skipped here on FULL — addFullSettings emits a single BoxSettings positioned right
        //   before AIRPLAY_CONFIG so airplay.conf is written last by our intended config.
        // - ViewArea/SafeArea: tied to display mode which may change between sessions
        // - Android work mode: must be re-sent on each reconnect to restart AA daemon
        // - Audio source & mic source: adapter resets both to defaults on disconnect
        //   (confirmed: firmware logs show no persistence). Must re-send every session
        //   to ensure BT/adapter audio and mic routing match host config.
        messages.add(serializeNumber(config.dpi, FileAddress.DPI))
        messages.add(serializeOpen(config))
        if (initMode != "FULL") {
            messages.add(serializeBoxSettings(config, surfaceWidth = surfaceWidth, surfaceHeight = surfaceHeight))
        }
        config.viewAreaData?.let {
            messages.add(serializeFile(FileAddress.HU_VIEWAREA_INFO.path, it))
        }
        config.safeAreaData?.let {
            messages.add(serializeFile(FileAddress.HU_SAFEAREA_INFO.path, it))
        }
        // GPS-fix watcher script: pushed to /tmp on every init (FULL + MINIMAL) because
        // adapter power-cycle between sessions wipes /tmp. Path is a hardcoded literal
        // (one-off, not added to FileAddress enum). Three-step:
        //   (a) SendFile (0x99) places the script at /tmp/aa_gps_fix.sh
        //   (b) BoxSettings (0x19) with command-substitution in wifiName triggers the
        //       firmware popen(sed) — shell expands our nohup launcher (script detaches),
        //       then wifiName collapses to boxName so hostapd.conf gets a no-op overwrite.
        //   (c) BoxSettings (0x19) with the CLEAN wifiName immediately after, to overwrite
        //       /etc/wifi_name which the firmware writes RAW (the JSON value goes to that
        //       file without shell expansion, so step b left the literal injection text
        //       persisted there). Following the same overwrite pattern documented on
        //       serializeQualityRescueBoxSettings; the second BoxSettings restores
        //       /etc/wifi_name to the user's real boxName.
        // Script's own PID-file guard (/tmp/aa_gps_fix.pid) prevents stacking watchers when
        // init re-fires without an adapter reboot.
        config.gpsFixScriptData?.let {
            messages.add(serializeFile("/tmp/aa_gps_fix.sh", it))
            messages.add(serializeBoxSettingsGpsFixInject(config))
            messages.add(serializeBoxSettings(config, surfaceWidth = surfaceWidth, surfaceHeight = surfaceHeight))
        }
        // Patched ARMiPhoneIAP2 (NaviJSON _iap2/_iap2m roundabout recovery): pushed to
        // /tmp/bin/ on every init to preempt phone_link_deamon.sh's first-spawn factory copy.
        // Single SendFile — no injection needed because the patch is on-disk (the binary IS
        // the fix; runtime memory writes are not required). Atomic rename(2) by firmware
        // means a running CarPlay session stays on its current inode; next iAP2 respawn
        // (CarPlay disconnect+reconnect or fresh session) execs the new patched binary.
        config.patchedIap2BinaryData?.let {
            messages.add(serializeFile("/tmp/bin/ARMiPhoneIAP2", it))
        }
        // Always emit an explicit write — previously only the true-branch was sent, which
        // left firmware's /etc/android_work_mode with a stale `1` when the user toggled
        // the setting OFF within the same connection (firmware only auto-resets to 0 on
        // physical disconnect). Writing the actual current value on every init path forces
        // the adapter state to match host intent, including opt-out.
        messages.add(serializeBoolean(config.androidWorkMode, FileAddress.ANDROID_WORK_MODE))

        when (initMode) {
            "MINIMAL_ONLY" -> {
                // Just minimal - adapter retains all other settings
                // WiFi Enable sent last to activate wireless mode after config
                messages.add(serializeCommand(CommandMapping.WIFI_ENABLE))
                return messages
            }

            "MINIMAL_PLUS_CHANGES" -> {
                // Add only the changed settings
                addChangedSettings(messages, config, pendingChanges, surfaceWidth, surfaceHeight)
                // WiFi Enable sent last to activate wireless mode after config
                messages.add(serializeCommand(CommandMapping.WIFI_ENABLE))
                return messages
            }

            else -> {
                // FULL - add all settings
                addFullSettings(messages, config, surfaceWidth, surfaceHeight)
                // WiFi Enable sent last to activate wireless mode after config
                messages.add(serializeCommand(CommandMapping.WIFI_ENABLE))
                return messages
            }
        }
    }

    /**
     * Add messages for only the changed settings.
     *
     * BoxSettings-triggering keys (MEDIA_DELAY, GPS_FORWARDING) are coalesced: any
     * number of them in [pendingChanges] produces exactly one BoxSettings resend at
     * the end of the change list. ConfigKey.CALL_QUALITY is intentionally NOT
     * consumed here — the UI was removed (AdapterConfigurationDialog.kt:418) and the
     * value is hardcoded 1 in BoxSettings JSON, so any pending CALL_QUALITY would be
     * a pure no-op resend. The vestigial CallQualityConfig persistence surface in
     * AdapterConfigPreference.kt is kept for round-trip safety; restore a branch
     * here only if Call Quality is ever re-enabled in the UI.
     */
    private fun addChangedSettings(
        messages: MutableList<ByteArray>,
        config: AdapterConfig,
        pendingChanges: Set<String>,
        surfaceWidth: Int = 0,
        surfaceHeight: Int = 0,
    ) {
        var needsBoxSettingsResend = false
        for (key in pendingChanges) {
            when (key) {
                ConfigKey.AUDIO_SOURCE -> {
                    val command =
                        if (config.audioTransferMode) {
                            CommandMapping.AUDIO_TRANSFER_ON
                        } else {
                            CommandMapping.AUDIO_TRANSFER_OFF
                        }
                    messages.add(serializeCommand(command))
                }

                ConfigKey.MIC_SOURCE -> {
                    val command =
                        if (config.micType == "box") {
                            CommandMapping.BOX_MIC
                        } else {
                            CommandMapping.MIC
                        }
                    messages.add(serializeCommand(command))
                }

                ConfigKey.WIFI_BAND -> {
                    val command =
                        if (config.wifiType == "5ghz") {
                            CommandMapping.WIFI_5G
                        } else {
                            CommandMapping.WIFI_24G
                        }
                    messages.add(serializeCommand(command))
                }

                ConfigKey.MEDIA_DELAY,
                ConfigKey.GPS_FORWARDING,
                -> {
                    // mediaDelay lives in BoxSettings JSON; GPS forwarding is gated by
                    // whether the app sends GNSS_DATA (0x29) but resend BoxSettings to
                    // keep adjacent config synced. Coalesced into one frame.
                    needsBoxSettingsResend = true
                }

                ConfigKey.HAND_DRIVE -> {
                    messages.add(serializeNumber(config.handDriveMode, FileAddress.HAND_DRIVE_MODE))
                }
            }
        }
        if (needsBoxSettingsResend) {
            messages.add(serializeBoxSettings(config, surfaceWidth = surfaceWidth, surfaceHeight = surfaceHeight))
        }
    }

    /**
     * Add all settings for full initialization.
     */
    private fun addFullSettings(
        messages: MutableList<ByteArray>,
        config: AdapterConfig,
        surfaceWidth: Int = 0,
        surfaceHeight: Int = 0,
    ) {
        // Hand drive mode: 0 = Left Hand Drive (LHD), 1 = Right Hand Drive (RHD)
        messages.add(serializeNumber(config.handDriveMode, FileAddress.HAND_DRIVE_MODE))

        // Box name
        messages.add(serializeString(config.boxName, FileAddress.BOX_NAME))

        // Charge mode: 0 = off (no quick charge), 1 = quick charge enabled
        messages.add(serializeNumber(0, FileAddress.CHARGE_MODE))

        // Upload icons if provided
        config.icon120Data?.let { messages.add(serializeFile(FileAddress.ICON_120.path, it)) }
        config.icon180Data?.let { messages.add(serializeFile(FileAddress.ICON_180.path, it)) }
        config.icon256Data?.let { messages.add(serializeFile(FileAddress.ICON_256.path, it)) }

        // WiFi band selection
        val wifiCommand = if (config.wifiType == "5ghz") CommandMapping.WIFI_5G else CommandMapping.WIFI_24G
        messages.add(serializeCommand(wifiCommand))

        // Quality rescue — DISABLED 2026-05-21. Was: exploit the CustomWifiName shell injection
        // to persist VoiceQuality=1 / CallQuality=1 via riddleBoxCfg, sent before the normal
        // BoxSettings so the real wifiName would overwrite the poisoned SSID afterward.
        // Why disabled: on adapters whose firmware is NOT shell-vulnerable on wifiName, the
        // payload is stored verbatim and the garbage string sticks as the live SSID — the
        // following normal BoxSettings does not reliably overwrite it (confirmed by a user's
        // adapter web-portal screenshot). VoiceQuality / CallQuality are now never sent.
        // Kept for future use — re-enable ONLY against firmware known to be shell-vulnerable.
        // messages.add(serializeQualityRescueBoxSettings(config))

        // Box settings JSON — includes DashboardInfo=7 and GNSSCapability=3 always.
        // These are persisted to riddle.conf by the firmware's ConfigFileUtils.
        // No datastore invalidation needed — values are constant and never change.
        //
        // Sole normal BoxSettings emit on the FULL path. generateInitSequence intentionally
        // SKIPS its MINIMAL-block BoxSettings when initMode == "FULL" so this is the
        // single source. Position matters: this lands immediately before the AirPlay
        // config write below — firmware may rewrite airplay.conf during BoxSettings
        // processing, and writing AIRPLAY_CONFIG last guarantees oemIconLabel persists.
        messages.add(serializeBoxSettings(config, surfaceWidth = surfaceWidth, surfaceHeight = surfaceHeight))

        // AirPlay configuration AFTER BoxSettings — firmware rewrites airplay.conf
        // during BoxSettings processing, so this must come last to persist oemIconLabel
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

        // NOTE: androidWorkMode is NOT re-sent here. It is written once — unconditionally,
        // with the current config.androidWorkMode value — in generateInitSequence (L480-486)
        // on every init path (MINIMAL_ONLY, MINIMAL_PLUS_CHANGES, FULL). Firmware persists
        // to /etc/android_work_mode, so one write per connection matches host intent
        // (including opt-out). A second write on the FULL path (historical carry-over from
        // pre-split refactor) was redundant and was removed.
    }
}
