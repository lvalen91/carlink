package com.carlink.protocol

import com.carlink.audio.AudioFormats
import com.carlink.logging.Logger
import com.carlink.logging.logWarn
import org.json.JSONException
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * CPC200-CCPA Protocol Message Parser
 *
 * Parses binary messages received from the Carlinkit adapter into typed message objects.
 * Handles header validation, payload extraction, and type-specific parsing.
 */
object MessageParser {
    /**
     * Parse a 16-byte header from raw bytes.
     *
     * @param data Raw header bytes (must be exactly 16 bytes)
     * @return Parsed MessageHeader
     * @throws HeaderParseException if header is invalid
     */
    fun parseHeader(data: ByteArray): MessageHeader {
        if (data.size != HEADER_SIZE) {
            throw HeaderParseException("Invalid buffer size - Expecting $HEADER_SIZE, got ${data.size}")
        }

        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        val magic = buffer.int
        if (magic != PROTOCOL_MAGIC) {
            throw HeaderParseException("Invalid magic number, received 0x${magic.toString(16)}")
        }

        val length = buffer.int
        val typeInt = buffer.int
        val msgType = MessageType.fromId(typeInt)

        if (msgType != MessageType.UNKNOWN) {
            val typeCheck = buffer.int
            val expectedCheck = (typeInt.inv()) and 0xFFFFFFFF.toInt()
            if (typeCheck != expectedCheck) {
                throw HeaderParseException("Invalid type check, received 0x${typeCheck.toString(16)}")
            }
        }

        return MessageHeader(length, msgType, rawType = typeInt)
    }

    /**
     * Parse a complete message from header and payload.
     *
     * NOTE: this `when` has an `else` catch-all that returns [UnknownMessage] — so
     * adding a new entry to [MessageType] without a case here will compile cleanly
     * and fall through to the catch-all (showing up as [Logger.Tags.PROTO_UNKNOWN]
     * at runtime). When you add a MessageType, add its branch here too.
     *
     * @param header Parsed message header
     * @param payload Message payload bytes (can be null for some message types)
     * @return Parsed Message object
     */
    fun parseMessage(
        header: MessageHeader,
        payload: ByteArray?,
    ): Message =
        when (header.type) {
            MessageType.AUDIO_DATA -> parseAudioData(header, payload)

            MessageType.MEDIA_DATA -> parseMediaData(header, payload)

            MessageType.COMMAND -> parseCommand(header, payload)

            MessageType.PLUGGED -> parsePlugged(header, payload)

            MessageType.UNPLUGGED -> UnpluggedMessage(header)

            MessageType.BOX_SETTINGS -> parseBoxSettings(header, payload)

            MessageType.PEER_BLUETOOTH_ADDRESS -> parsePeerBluetoothAddress(header, payload)

            // Inbound diagnostic messages — parsed for logging and future use
            MessageType.PHASE -> parsePhase(header, payload)

            MessageType.SOFTWARE_VERSION -> parseStringPayload(header, payload, "SoftwareVersion")

            MessageType.BLUETOOTH_DEVICE_NAME -> parseStringPayload(header, payload, "BluetoothDeviceName")

            MessageType.WIFI_DEVICE_NAME -> parseStringPayload(header, payload, "WifiDeviceName")

            MessageType.BLUETOOTH_ADDRESS -> parseStringPayload(header, payload, "BluetoothAddress")

            MessageType.BLUETOOTH_PIN -> parseStringPayload(header, payload, "BluetoothPin")

            MessageType.BLUETOOTH_PAIRED_LIST -> parseBluetoothPairedList(header, payload)

            MessageType.MANUFACTURER_INFO -> parseStringPayload(header, payload, "ManufacturerInfo")

            MessageType.STATUS_VALUE -> parseStatusValue(header, payload)

            MessageType.SESSION_TOKEN -> SessionTokenMessage(header, header.length)

            MessageType.NAVI_FOCUS_REQUEST -> NaviFocusMessage(header, isRequest = true)

            MessageType.NAVI_FOCUS_RELEASE -> NaviFocusMessage(header, isRequest = false)

            MessageType.PEER_BLUETOOTH_ADDRESS_ALT -> parsePeerBluetoothAddress(header, payload)

            MessageType.LOGO_TYPE -> parseIntPayload(header, payload, "LogoType")

            MessageType.HI_CAR_LINK -> parseStringPayload(header, payload, "HiCarLink")

            MessageType.UI_HIDE_PEER_INFO -> InfoMessage(header, "UiHidePeerInfo")

            // Observed as an active runtime signal during CarPlay session setup:
            // emitted once with a zero-length payload shortly after the paired-list
            // broadcast. Treated as diagnostic only at this layer.
            MessageType.UI_BRING_TO_FOREGROUND -> InfoMessage(header, "UiBringToForeground")

            MessageType.REMOTE_CX_CY -> parseHexPayload(header, payload, "RemoteCxCy")

            MessageType.EXTENDED_MFG_INFO -> parseStringPayload(header, payload, "ExtendedMfgInfo")

            MessageType.REMOTE_DISPLAY -> parseHexPayload(header, payload, "RemoteDisplay")

            MessageType.OPEN -> parseOpenEcho(header, payload)

            // Additional adapter→host messages (firmware binary analysis)
            MessageType.CARPLAY_CONTROL -> parseHexPayload(header, payload, "CarPlayControl")

            MessageType.DASHBOARD_DATA -> parseHexPayload(header, payload, "DashboardData")

            MessageType.WIFI_STATUS_DATA -> parseHexPayload(header, payload, "WiFiStatusData")

            MessageType.FORGET_BLUETOOTH_ADDR -> parseStringPayload(header, payload, "ForgetBluetoothAddr")

            MessageType.DISK_INFO -> parseStringPayload(header, payload, "DiskInfo")

            MessageType.DEVICE_EXTENDED_INFO -> parseHexPayload(header, payload, "DeviceExtendedInfo")

            MessageType.FACTORY_SETTING -> InfoMessage(header, "FactorySetting")

            MessageType.ADAPTER_IDLE -> InfoMessage(header, "AdapterIdle")

            MessageType.HEARTBEAT_ECHO -> InfoMessage(header, "HeartbeatEcho")

            MessageType.ERROR_REPORT -> parseHexPayload(header, payload, "ErrorReport")

            MessageType.UPDATE_PROGRESS -> parseIntPayload(header, payload, "UpdateProgress")

            MessageType.DEBUG_TRACE -> parseHexPayload(header, payload, "DebugTrace")

            // Navigation video — handled via direct video path in UsbDeviceWrapper,
            // but if it arrives here (no videoProcessor), parse as info
            MessageType.NAVI_VIDEO_DATA -> InfoMessage(header, "NaviVideoData", "${header.length}B")

            else -> UnknownMessage(header, payload)
        }

    /**
     * Bounds-safe ByteBuffer.wrap that tolerates adapter frames whose declared
     * header.length exceeds the bytes actually delivered by the USB bulk read.
     *
     * WHY: ByteBuffer.wrap(array, offset, length) throws IOOBE when
     * offset+length > array.size. Without this helper, a short/corrupt frame
     * would throw out of the USB-ReadLoop thread and require a full reconnect to
     * recover (UsbDeviceWrapper already caps header.length at MAX_PAYLOAD_SIZE,
     * so the window was narrow — but the failure mode was fatal when it hit).
     * The clamp converts any would-be crash into a logged drop; the state
     * machine's existing reconnect logic then handles recovery if needed.
     *
     * Returns null only for negative declared length (hard protocol violation);
     * callers should treat null as "return UnknownMessage".
     */
    private fun safeWrap(
        payload: ByteArray,
        declared: Int,
        tag: String,
    ): ByteBuffer? {
        if (declared < 0) {
            logWarn(
                "[MessageParser] $tag: negative header.length=$declared; dropping",
                Logger.Tags.PROTO_UNKNOWN,
            )
            return null
        }
        val safe = declared.coerceAtMost(payload.size)
        if (safe != declared) {
            logWarn(
                "[MessageParser] $tag: truncated frame, header.length=$declared payload=${payload.size}; clamping to $safe",
                Logger.Tags.PROTO_UNKNOWN,
            )
        }
        return ByteBuffer.wrap(payload, 0, safe).order(ByteOrder.LITTLE_ENDIAN)
    }

    private fun parseAudioData(
        header: MessageHeader,
        payload: ByteArray?,
    ): Message {
        if (payload == null || header.length < 12) {
            return UnknownMessage(header, payload)
        }

        val buffer = safeWrap(payload, header.length, "parseAudioData")
            ?: return UnknownMessage(header, payload)

        val decodeType = buffer.int
        val volume = buffer.float
        val audioType = buffer.int

        // Use buffer.limit() (the safeWrap-clamped bound) rather than header.length
        // to avoid reading past the actual payload on truncated frames.
        val remainingBytes = (buffer.limit() - 12).coerceAtLeast(0)

        return when {
            remainingBytes == 1 && payload.size > 12 -> {
                val commandId = payload[12].toInt() and 0xFF
                AudioDataMessage(
                    header = header,
                    decodeType = decodeType,
                    volume = volume,
                    audioType = audioType,
                    command = AudioCommand.fromId(commandId),
                    data = null,
                    volumeDuration = null,
                    rawCommandId = commandId,
                )
            }

            remainingBytes == 4 -> {
                buffer.position(12)
                val duration = buffer.float
                AudioDataMessage(
                    header = header,
                    decodeType = decodeType,
                    volume = volume,
                    audioType = audioType,
                    command = null,
                    data = null,
                    volumeDuration = duration,
                )
            }

            remainingBytes > 0 -> {
                AudioDataMessage(
                    header = header,
                    decodeType = decodeType,
                    volume = volume,
                    audioType = audioType,
                    command = null,
                    data = payload,
                    volumeDuration = null,
                    audioDataOffset = 12,
                    audioDataLength = remainingBytes,
                )
            }

            else -> {
                AudioDataMessage(
                    header = header,
                    decodeType = decodeType,
                    volume = volume,
                    audioType = audioType,
                    command = null,
                    data = null,
                    volumeDuration = null,
                )
            }
        }
    }

    private fun parseMediaData(
        header: MessageHeader,
        payload: ByteArray?,
    ): Message {
        if (payload == null || header.length < 4) {
            return MediaDataMessage(header, MediaType.UNKNOWN, emptyMap())
        }

        val buffer = safeWrap(payload, header.length, "parseMediaData")
            ?: return MediaDataMessage(header, MediaType.UNKNOWN, emptyMap())
        val typeInt = buffer.int
        val mediaType = MediaType.fromId(typeInt)

        // Use buffer.limit() rather than header.length so we respect the clamped
        // bound from safeWrap() on truncated frames. end is always <= payload.size.
        val end = buffer.limit()
        val bodyLen = (end - 4).coerceAtLeast(0)

        val mediaPayload: Map<String, Any> =
            when (mediaType) {
                MediaType.ALBUM_COVER,
                MediaType.ALBUM_COVER_AA -> {
                    if (bodyLen > 0) {
                        val imageData = ByteArray(bodyLen)
                        System.arraycopy(payload, 4, imageData, 0, bodyLen)
                        mapOf("AlbumCover" to imageData)
                    } else {
                        emptyMap()
                    }
                }

                MediaType.NAVI_IMAGE -> {
                    if (bodyLen > 0) {
                        val imageData = ByteArray(bodyLen)
                        System.arraycopy(payload, 4, imageData, 0, bodyLen)
                        mapOf("NaviImage" to imageData)
                    } else {
                        emptyMap()
                    }
                }

                MediaType.DATA, MediaType.NAVI_JSON, MediaType.CALL_STATUS -> {
                    if (bodyLen < 1) {
                        emptyMap()
                    } else {
                        // WHY trimEnd('\u0000') instead of a hardcoded "header.length - 5":
                        // the adapter's NUL-termination is inconsistent across payload
                        // types (BT-address strings definitely vary; media JSON is
                        // defensive against the same pattern). A hardcoded "-5" offset
                        // would drop the last JSON char on any frame without the
                        // trailing NUL. trimEnd tolerates 0..N trailing NULs without
                        // losing real content; trimEnd (not trim) preserves leading
                        // NULs as a framing-bug signal rather than silently masking.
                        val jsonString = String(payload, 4, bodyLen, StandardCharsets.UTF_8)
                            .trimEnd('\u0000')
                        try {
                            val json = JSONObject(jsonString)
                            json.keys().asSequence().associateWith { json.get(it) }
                        } catch (e: JSONException) {
                            logWarn(
                                "[MessageParser] Failed to parse media JSON: ${e.message} raw=${jsonString.take(256)}",
                                Logger.Tags.PROTO_UNKNOWN,
                            )
                            emptyMap()
                        }
                    }
                }

                else -> {
                    // Unknown subtype — preserve raw bytes for diagnostic logging.
                    // Keys are underscore-prefixed so downstream consumers treat them
                    // as diagnostic metadata rather than real media fields.
                    val preview = if (bodyLen > 0) {
                        val limit = bodyLen.coerceAtMost(64)
                        val hex = StringBuilder(limit * 3)
                        for (i in 0 until limit) {
                            if (i > 0) hex.append(' ')
                            hex.append("%02X".format(payload[4 + i]))
                        }
                        if (bodyLen > 64) hex.append(" … (${bodyLen}B total)")
                        hex.toString()
                    } else ""
                    mapOf("_unknownSubtype" to typeInt, "_hexPreview" to preview)
                }
            }

        return MediaDataMessage(header, mediaType, mediaPayload)
    }

    private fun parseCommand(
        header: MessageHeader,
        payload: ByteArray?,
    ): Message {
        if (payload == null || header.length < 4) {
            return CommandMessage(header, CommandMapping.INVALID, rawId = -1)
        }
        val buffer = safeWrap(payload, header.length, "parseCommand")
            ?: return CommandMessage(header, CommandMapping.INVALID, rawId = -1)
        val commandId = buffer.int
        return CommandMessage(header, CommandMapping.fromId(commandId), rawId = commandId)
    }

    private fun parsePlugged(
        header: MessageHeader,
        payload: ByteArray?,
    ): Message {
        if (payload == null || header.length < 4) {
            return PluggedMessage(header, PhoneType.UNKNOWN, null)
        }
        val buffer = safeWrap(payload, header.length, "parsePlugged")
            ?: return PluggedMessage(header, PhoneType.UNKNOWN, null)
        val phoneTypeId = buffer.int
        val phoneType = PhoneType.fromId(phoneTypeId)

        val wifi =
            if (header.length >= 8) {
                buffer.int
            } else {
                null
            }

        return PluggedMessage(header, phoneType, wifi, rawPhoneType = phoneTypeId)
    }

    private fun parseBoxSettings(
        header: MessageHeader,
        payload: ByteArray?,
    ): Message {
        if (payload == null || header.length < 2) {
            return UnknownMessage(header, payload)
        }

        // Payload is null-terminated JSON string
        val safeLen = header.length.coerceAtMost(payload.size)
        val jsonString = String(payload, 0, safeLen, StandardCharsets.UTF_8).trim('\u0000')
        if (jsonString.isEmpty()) {
            return UnknownMessage(header, payload)
        }

        return try {
            val json = JSONObject(jsonString)
            // Discriminate: the phone-info form carries "MDModel"; the adapter-info
            // form carries "uuid" (+ boxType / hwVersion / productType) and arrives
            // as the adapter's echo of the host's BoxSettings write during FULL init.
            val isPhoneInfo = json.has("MDModel")
            BoxSettingsMessage(header, json, isPhoneInfo)
        } catch (e: JSONException) {
            logWarn("[MessageParser] Failed to parse BoxSettings JSON: ${e.message}")
            UnknownMessage(header, payload)
        }
    }

    private fun parsePeerBluetoothAddress(
        header: MessageHeader,
        payload: ByteArray?,
    ): Message {
        if (payload == null || header.length < 17 || payload.size < 17) {
            return UnknownMessage(header, payload)
        }

        // Payload is 17 bytes ASCII MAC "XX:XX:XX:XX:XX:XX" (may be null-terminated)
        val macAddress = String(payload, 0, 17, StandardCharsets.UTF_8).trim('\u0000')
        return PeerBluetoothAddressMessage(header, macAddress)
    }

    // ==================== Diagnostic Parsers ====================

    private fun parsePhase(
        header: MessageHeader,
        payload: ByteArray?,
    ): Message {
        if (payload == null || header.length < 4) return PhaseMessage(header, -1)
        val buffer = safeWrap(payload, header.length, "parsePhase")
            ?: return PhaseMessage(header, -1)
        return PhaseMessage(header, buffer.int)
    }

    private fun parseStatusValue(
        header: MessageHeader,
        payload: ByteArray?,
    ): Message {
        if (payload == null || header.length < 4) return StatusValueMessage(header, -1)
        val buffer = safeWrap(payload, header.length, "parseStatusValue")
            ?: return StatusValueMessage(header, -1)
        return StatusValueMessage(header, buffer.int)
    }

    /**
     * Parse BluetoothPairedList (0x12).
     *
     * The adapter sends paired devices as concatenated MAC+Name strings.
     * Entries may or may not be null-separated. The MAC pattern (XX:XX:XX:XX:XX:XX)
     * is used as a reliable delimiter to split entries.
     *
     * Example payloads:
     *   "AA:BB:CC:DD:EE:FFDoe"                           — single device
     *   "AA:BB:CC:DD:EE:FFJane\011:22:33:44:55:66Pixel"  — null-separated
     *   "AA:BB:CC:DD:EE:FFJane11:22:33:44:55:66Pixel 10" — no separator
     *
     * Verified against live CarPlay sessions: the single-device "MAC + name with no
     * delimiter" form is actually emitted by real adapters (25-byte payload carrying
     * a 17-byte MAC immediately followed by an 8-byte name such as "iPhone"). The
     * regex split strategy survives this format without ambiguity.
     */
    private val BT_MAC_PATTERN = Regex("[0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5}")

    private fun parseBluetoothPairedList(
        header: MessageHeader,
        payload: ByteArray?,
    ): Message {
        if (payload == null || header.length < 17) {
            return BluetoothPairedListMessage(header, "", emptyList())
        }
        val safeLen = header.length.coerceAtMost(payload.size)
        val raw = String(payload, 0, safeLen, StandardCharsets.UTF_8)
            .replace(Regex("[\\x00-\\x1F\\x7F]+"), "") // strip all control characters
        val devices = mutableListOf<Pair<String, String>>()

        // Find all MAC addresses in the string — each starts a new device entry
        val macMatches = BT_MAC_PATTERN.findAll(raw).toList()
        for ((idx, match) in macMatches.withIndex()) {
            val mac = match.value
            val nameStart = match.range.last + 1
            // Name runs from after MAC to the start of the next MAC (or end of string)
            val nameEnd = if (idx + 1 < macMatches.size) macMatches[idx + 1].range.first else raw.length
            val name = raw.substring(nameStart, nameEnd).trim().ifEmpty { mac }
            devices.add(Pair(mac, name))
        }

        return BluetoothPairedListMessage(header, raw, devices)
    }

    private fun parseStringPayload(
        header: MessageHeader,
        payload: ByteArray?,
        label: String,
    ): Message {
        if (payload == null || header.length < 1) return InfoMessage(header, label, "")
        val safeLen = header.length.coerceAtMost(payload.size)
        val str = String(payload, 0, safeLen, StandardCharsets.UTF_8).trim('\u0000')
        return InfoMessage(header, label, str)
    }

    private fun parseIntPayload(
        header: MessageHeader,
        payload: ByteArray?,
        label: String,
    ): Message {
        if (payload == null || header.length < 4) return InfoMessage(header, label, "${header.length}B")
        val buffer = safeWrap(payload, header.length, "parseIntPayload[$label]")
            ?: return InfoMessage(header, label, "${header.length}B")
        return InfoMessage(header, label, "${buffer.int}")
    }

    private fun parseHexPayload(
        header: MessageHeader,
        payload: ByteArray?,
        label: String,
    ): Message {
        if (payload == null || header.length < 1) return InfoMessage(header, label, "0B")
        val safeLen = header.length.coerceAtMost(payload.size)
        val hex =
            payload
                .take(safeLen.coerceAtMost(32))
                .joinToString(" ") { "%02X".format(it) }
        val suffix = if (safeLen > 32) "... (${safeLen}B)" else ""
        return InfoMessage(header, label, "$hex$suffix")
    }

    private fun parseOpenEcho(
        header: MessageHeader,
        payload: ByteArray?,
    ): Message {
        if (payload == null || header.length < 8) return InfoMessage(header, "OpenEcho", "${header.length}B")
        val buffer = safeWrap(payload, header.length, "parseOpenEcho")
            ?: return InfoMessage(header, "OpenEcho", "${header.length}B")
        val w = buffer.int
        val h = buffer.int
        return InfoMessage(header, "OpenEcho", "${w}x$h")
    }
}

// ==================== Message Classes ====================

/**
 * Base class for all protocol messages.
 *
 * Sealed: every concrete subclass must live in THIS file (Kotlin sealed-class
 * constraint). Do not extract subclasses without understanding that moving them
 * will break the sealing contract. [InfoMessage] is the catch-all for known-type
 * diagnostic-only frames; [UnknownMessage] is the catch-all for unrecognised
 * MessageType codes (preserves raw payload for forensics).
 */
sealed class Message(
    val header: MessageHeader,
) {
    override fun toString(): String = "Message(type=${header.type})"
}

/**
 * Unknown or unhandled message type.
 * Preserves raw payload bytes for diagnostic logging.
 */
class UnknownMessage(
    header: MessageHeader,
    val rawPayload: ByteArray? = null,
) : Message(header) {
    /** First 64 bytes as hex for log output. */
    fun hexPreview(): String {
        if (rawPayload == null || rawPayload.isEmpty()) return "(no payload)"
        val limit = header.length.coerceAtMost(64)
        val hex = rawPayload.take(limit).joinToString(" ") { "%02X".format(it) }
        return if (header.length > 64) "$hex … (${header.length}B total)" else hex
    }

    override fun toString(): String =
        "UnknownMessage(type=0x${header.rawType.toString(16)}, ${header.length}B, ${hexPreview()})"
}

/**
 * Command message from adapter.
 */
class CommandMessage(
    header: MessageHeader,
    val command: CommandMapping,
    /** Raw command ID from the wire — preserved even when command maps to INVALID */
    val rawId: Int = command.id,
) : Message(header) {
    override fun toString(): String =
        if (command == CommandMapping.INVALID) {
            "Command(unknown id=$rawId / 0x${rawId.toString(16)})"
        } else {
            "Command(${command.name})"
        }
}

/**
 * Device plugged notification.
 */
class PluggedMessage(
    header: MessageHeader,
    val phoneType: PhoneType,
    val wifi: Int?,
    /** Raw phone type integer from wire — preserved even when phoneType maps to UNKNOWN */
    val rawPhoneType: Int = phoneType.id,
) : Message(header) {
    override fun toString(): String =
        if (phoneType == PhoneType.UNKNOWN) {
            "Plugged(phoneType=UNKNOWN rawId=$rawPhoneType / 0x${rawPhoneType.toString(16)}, wifi=$wifi)"
        } else {
            "Plugged(phoneType=${phoneType.name}, wifi=$wifi)"
        }
}

/**
 * Device unplugged notification.
 */
class UnpluggedMessage(
    header: MessageHeader,
) : Message(header) {
    override fun toString(): String = "Unplugged"
}

/**
 * Audio data message with PCM samples or command.
 */
class AudioDataMessage(
    header: MessageHeader,
    val decodeType: Int,
    val volume: Float,
    val audioType: Int,
    val command: AudioCommand?,
    val data: ByteArray?,
    val volumeDuration: Float?,
    val audioDataOffset: Int = 0,
    val audioDataLength: Int = data?.size ?: 0,
    /** Raw command byte from wire — preserved even when command maps to UNKNOWN */
    val rawCommandId: Int = command?.id ?: -1,
) : Message(header) {
    override fun toString(): String {
        val format = AudioFormats.fromDecodeType(decodeType)
        val formatInfo = "${format.sampleRate}Hz ${format.channelCount}ch"
        return when {
            command != null -> "AudioData(command=${command.name})"
            volumeDuration != null -> "AudioData(volumeDuration=$volumeDuration)"
            else -> "AudioData(format=$formatInfo, audioType=$audioType, bytes=$audioDataLength)"
        }
    }
}

/**
 * Media metadata message.
 */
class MediaDataMessage(
    header: MessageHeader,
    val type: MediaType,
    val payload: Map<String, Any>,
) : Message(header) {
    override fun toString(): String = "MediaData(type=${type.name})"
}

/**
 * Video streaming signal (synthetic, not from the protocol wire).
 * Emitted by [AdapterDriver]'s read loop when VIDEO_DATA arrives with
 * data==null || dataLength==0 — i.e., the frame was consumed directly by the
 * VideoDataProcessor for zero-copy rendering. The header.length is always 0
 * as a clue that this is synthetic, not a real on-wire frame.
 * Consumers ([CarlinkManager.handleMessage]) use it as a streaming-liveness
 * pulse (keyframe request, STREAMING-state transition) without touching pixels.
 */
object VideoStreamingSignal : Message(MessageHeader(0, MessageType.VIDEO_DATA)) {
    override fun toString(): String = "VideoStreamingSignal"
}

/**
 * BoxSettings message from adapter (0x19).
 * Two forms: adapter info (has DevList) and phone info (has MDModel/btMacAddr).
 */
class BoxSettingsMessage(
    header: MessageHeader,
    val json: JSONObject,
    val isPhoneInfo: Boolean,
) : Message(header) {
    override fun toString(): String =
        if (isPhoneInfo) {
            "BoxSettings(phone: ${json.optString("MDModel", "?")})"
        } else {
            "BoxSettings(adapter: ${json.optString("boxType", "?")})"
        }
}

/**
 * Peer Bluetooth address from adapter (0x23).
 * Contains the connected phone's BT MAC address.
 */
class PeerBluetoothAddressMessage(
    header: MessageHeader,
    val macAddress: String,
) : Message(header) {
    override fun toString(): String = "PeerBluetoothAddress($macAddress)"
}

// ==================== Diagnostic Message Classes ====================
// These are parsed for logging/correlation and potential future use.
// The existing [RECV] log in AdapterDriver prints toString() for each.

/**
 * Connection phase update (0x03).
 * Phase 0=terminated, 7=connecting, 8=streaming, 13=negotiation_failed.
 * NOTE: Phase 0 is a session termination signal (alternative to UNPLUGGED).
 * NOTE: Phase 13 indicates AirPlay session negotiation failed (e.g., viewArea/safeArea constraint violation).
 *
 * Observed on live CarPlay sessions: PLUGGED(phoneType=3 CARPLAY) arrives first, then
 * Phase 7 ~1 s later, then Phase 8 ~1.8 s after that. [SessionTokenMessage] (0xA3)
 * typically lands at the SAME millisecond as the Phase 8 transition — the encrypted
 * telemetry is delivered as the adapter crosses into streaming. Clean session teardown
 * does not require a Phase 0 (host-initiated stop sends DisconnectPhone+CloseDongle
 * and the adapter may end the stream without a final Phase message).
 *
 * The adapter's internal CarPlay-phase state machine uses a broader enum (visible on
 * its console as values like 0/1/2/3/100/101/102); only the subset above is
 * transmitted over USB to the host.
 */
class PhaseMessage(
    header: MessageHeader,
    val phase: Int,
) : Message(header) {
    val phaseName: String
        get() =
            when (phase) {
                0 -> "terminated"
                7 -> "connecting"
                8 -> "streaming"
                13 -> "negotiation_failed"
                else -> "unknown"
            }

    override fun toString(): String = "Phase($phase=$phaseName)"
}

/**
 * Status/config value from adapter (0xBB).
 */
class StatusValueMessage(
    header: MessageHeader,
    val value: Int,
) : Message(header) {
    override fun toString(): String = "StatusValue($value / 0x${value.toString(16)})"
}

/**
 * Parsed Bluetooth paired device list (0x12).
 * Payload format: "MAC1Name1\u0000MAC2Name2\u0000..." or "MAC1Name1" single entry.
 * MAC is 17 chars ("XX:XX:XX:XX:XX:XX"), remainder is the device name.
 */
class BluetoothPairedListMessage(
    header: MessageHeader,
    val rawPayload: String,
    val devices: List<Pair<String, String>>, // (btMac, name)
) : Message(header) {
    override fun toString(): String = "BluetoothPairedList(${devices.size} devices: ${devices.joinToString { "${it.second}(${it.first})" }})"
}

/**
 * Encrypted session telemetry (0xA3) — OPAQUE at this layer.
 *
 * Wire format is AES-128-CBC-encrypted JSON with phone/adapter info (phone model,
 * adapter UUID, firmware version). The key [SESSION_TOKEN_ENCRYPTION_KEY] is
 * declared in MessageTypes.kt for FUTURE decryption but is not currently used —
 * this class stores only the encrypted payload size, and [CarlinkManager.handleMessage]
 * logs receipt without decoding. Do not assume the decrypted fields are accessible
 * from this message.
 *
 * Observed wire characteristics: payload is ASCII Base64 (with trailing `=` padding)
 * typically 428 bytes long on CarPlay sessions; decodes to a 16-byte IV + an integer
 * number of 16-byte AES-CBC ciphertext blocks. Arrives exactly once per session, at
 * the same millisecond the adapter emits [PhaseMessage] with phase=8 (streaming).
 * If the encrypted payload also appears in an in-tree capture header slot, treat it
 * as potentially truncated; the authoritative source is always the 0xA3 body record.
 */
class SessionTokenMessage(
    header: MessageHeader,
    val payloadSize: Int,
) : Message(header) {
    override fun toString(): String = "SessionToken(${payloadSize}B encrypted)"
}

/**
 * Navigation focus request/release (0x6E/0x6F).
 * Signals when CarPlay navigation video should start/stop.
 */
class NaviFocusMessage(
    header: MessageHeader,
    val isRequest: Boolean,
) : Message(header) {
    override fun toString(): String = if (isRequest) "NaviFocusRequest" else "NaviFocusRelease"
}

/**
 * Generic informational message for adapter diagnostics.
 * Used for inbound types with string/hex/int payloads that are logged but not actively handled.
 */
class InfoMessage(
    header: MessageHeader,
    val label: String,
    val value: String = "",
) : Message(header) {
    override fun toString(): String = if (value.isNotEmpty()) "$label($value)" else label
}
