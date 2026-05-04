# CPC200-CCPA Configuration Reference

**Purpose:** Complete riddleBoxCfg **[Firmware]** configuration keys reference — these are CPC200-CCPA adapter firmware settings (`/etc/riddle.conf`), not Android host app settings.
**Consolidated from:** pi-carplay firmware analysis, carlink_native research
**Last Updated:** 2026-02-19 (deduplicated heartbeat deep dive and D-Bus signal table; added direction/context labels)

---

## Configuration System Overview

| Component | Path | Description |
|-----------|------|-------------|
| **Config file** | `/etc/riddle.conf` | **JSON format** (not key=value) |
| **CLI tool** | `riddleBoxCfg -s <Key> [Value]` | Read/write to riddle.conf |
| **Backup** | `/etc/riddle_default.conf` | Factory defaults (minimal JSON) |
| **Runtime** | Global variables | Values loaded at ARMadb-driver startup |
| **Apply changes** | `riddleBoxCfg --upConfig` or reboot | Process restart or reboot needed |

**riddle.conf Format (Verified Jan 2026):**
```json
{
	"USBVID":	"1314",
	"USBPID":	"1521",
	"AndroidWorkMode":	1,
	"MediaLatency":	1000,
	"AndroidAutoWidth":	2400,
	"AndroidAutoHeight":	960,
	"BtAudio":	1,
	"DevList":	[{"id":"XX:XX:XX:XX:XX:XX","type":"CarPlay","name":"iPhone"}],
	"LastConnectedDevice":	"XX:XX:XX:XX:XX:XX"
}
```

**Note:** While the file format is JSON, the `riddleBoxCfg` CLI uses key-based access (e.g., `riddleBoxCfg -s AdvancedFeatures 1`).

---

## Video / H.264 Settings

### SpsPpsMode
**Type:** Select (0-3) | **Default:** 0

Controls H.264 SPS/PPS handling for video stream.

| Value | Behavior |
|-------|----------|
| 0 | Passthrough - SPS/PPS forwarded as-is from phone (no modification) |
| 1 | Re-inject - prepends cached SPS/PPS before each IDR frame |
| 2 | Cache - stores SPS/PPS in memory, replays on decode errors |
| 3 | Repeat - duplicates SPS/PPS in every video packet |

### NeedKeyFrame
**Type:** Toggle (0/1) | **Default:** 0

| Value | Behavior |
|-------|----------|
| 0 | Passive - waits for phone's natural IDR interval |
| 1 | Active - sends `RequestKeyFrame` to phone on decoder errors |

Protocol: Uses internal command ID 0x1c (`RefreshFrame`).

### RepeatKeyframe
**Type:** Toggle (0/1) | **Default:** 0

| Value | Behavior |
|-------|----------|
| 0 | Normal - each keyframe sent once |
| 1 | Repeat - re-sends last IDR when buffer underrun detected |

### SendEmptyFrame
**Type:** Toggle (0/1) | **Default:** 1

| Value | Behavior |
|-------|----------|
| 0 | Skip - no packets sent during video gaps |
| 1 | Send - empty timing packets maintain stream clock |

### VideoBitRate
**Type:** Number (0-20) | **Default:** 0

Hint for phone's encoder target bitrate (passed in Open message). Firmware applies a 0.25 throttle factor (value × 0.25 = effective Mbps hint).

| Value | Effect |
|-------|--------|
| 0 | Auto - phone decides based on WiFi conditions |
| 1-5 | Low bitrate (~0.25-1.25 Mbps effective) |
| 6-15 | Medium bitrate (~1.5-3.75 Mbps effective) |
| 16-20 | High bitrate (~4-5 Mbps effective) |

#### iPhone-Measured Response to VideoBitRate (Mar 2026)

iPhone syslog captures (`idevicesyslog`, Runs 4-6) reveal that the iPhone uses its **own adaptive bitrate algorithm**, independent of the firmware hint:

| Phase | iPhone Behavior |
|-------|----------------|
| Session start | Always begins at **750 Kbps floor** regardless of VideoBitRate setting |
| Ramp | Climbs to 1.5–1.8 Mbps within 1–2 seconds |
| Steady state | Settles at 1.19–1.27 Mbps |
| Burst budget | 20% of target bitrate (per-frame allowance for scene changes/IDRs) |

The `DataRateLimits` property (`[target, unknown, burst, window]`) is updated hundreds of times per session (514 updates in Run 5, 488 in Run 6). The firmware VideoBitRate hint (passed in the Open message) may influence the iPhone's initial target but does NOT override the adaptive algorithm — the iPhone always converges to its own steady state based on WiFi link quality and content complexity.

#### Android Auto Response to VideoBitRate (Mar 2026)

AA bitrate behavior is fundamentally different from CarPlay's adaptive algorithm:

- Adapter passes `maxVideoBitRate` to OpenAuto (e.g., 5000 Kbps from web UI `bitRate=5`)
- Gearhead independently configures encoder at **4.03 Mbps VBR** — the adapter hint acts as a **cap**, not a target
- Encoder uses VBR mode (`bitrate-mode=1`), `max-bitrate` = target bitrate (4,034,400 bps)
- Unlike CarPlay's DataRateLimits that update hundreds of times per session, AA bitrate is **fixed at Gearhead configuration time** and does not adapt during streaming

### CustomFrameRate
**Type:** Number (0, 20-60) | **Default:** 0

Sets `frameRate` field in Open message.

| Value | Effect |
|-------|--------|
| 0 | Auto - typically 30 FPS |
| 20-60 | Custom frame rate |

#### iPhone-Confirmed Frame Rate Behavior (Mar 2026)

iPhone syslog confirms that `CustomFrameRate` acts as a **ceiling, not a target**:

- Actual encode rate: **13–27 fps** (content-dependent, varies per 2-second interval)
- Encoder drops: **0** across every reporting interval in every run
- The FigVirtualFramebuffer submits frames only when the CarPlay screen updates — static screens produce as few as 8 frames per 2-second cycle
- The 30fps VSYNC grid (33ms PTS intervals) represents the display refresh rate, not the actual encode rate

`CustomFrameRate=60` does NOT make the iPhone encode at 60fps — it allows up to 60fps during rapid animations, but the iPhone will still only encode frames when screen content changes.

#### Android Auto Frame Rate Behavior (Mar 2026)

AA frame rate control differs fundamentally from CarPlay:

- Gearhead configures 30fps via `FrameRateLimitManagerImpl` (`PowerBasedLimiter`: 60→30fps)
- `CustomFrameRate` setting has **no confirmed effect on AA** — Gearhead controls encoder fps independently of the adapter's frame rate hint
- Actual output: ~29.2fps mean (28.8-30.0 first window), declining to 6.5-13.8fps when AA screen idle
- Unlike CarPlay's variable content-driven rate, AA encoder maintains a **fixed 30fps target** during active content — only dropping when the screen is static

---

## Performance / Fluency Settings

### ImprovedFluency
**Type:** Toggle (0/1) | **Default:** 0

> **Binary Analysis (2026-02-28): UNIMPLEMENTED / DEAD CONFIG KEY**
>
> Exhaustive Ghidra decompilation and cross-reference analysis of ALL firmware
> binaries containing this key (ARMadb-driver, AppleCarPlay, ARMiPhoneIAP2,
> bluetoothDaemon, ARMHiCar, server.cgi, riddleBoxCfg) confirms that **no
> runtime code path reads ImprovedFluency to change behavior** in firmware
> 2025.10.15.1127. ARMAndroidAuto does not link the config library at all.
>
> The config table entry exists in every binary (entry #64 of 79 in
> `riddleConfigNameValue` at `.data.rel.ro`), and server.cgi serializes it
> for the web API, but zero `GetBoxConfig("ImprovedFluency")` calls exist
> in any binary's `.text` section. All 24 GetBoxConfig callers in
> ARMadb-driver and all callers in every other binary were resolved —
> none pass this key.
>
> The `advanced.html` web UI describes the *intended* behavior as:
> "Increase USB bulk transfer buffers and adjust pcm_get_buffer_size."
> This was never implemented. Setting it to 0 or 1 has no effect.

| Value | Documented Behavior | Actual Effect (fw 2025.10.15) |
|-------|----------|-------------------------------|
| 0 | Standard buffering - lower latency, possible stutters | No effect (dead key) |
| 1 | Enhanced buffering - slightly higher latency, smoother playback | No effect (dead key) |

### FastConnect
**Type:** Toggle (0/1) | **Default:** 0

| Value | Behavior |
|-------|----------|
| 0 | Full handshake - all verification steps |
| 1 | Quick reconnect - skips BT discovery if MAC matches `LastConnectedDevice` |

Reduces connection time by ~2-5 seconds on reconnect.

### SendHeartBeat (CRITICAL) **[Firmware]**
**Type:** Toggle (0/1) | **Default:** 1 | **Access:** SSH only | **Applies to:** CarPlay and Android Auto

Controls whether the adapter firmware expects and responds to heartbeat messages (0xAA) from the host application. This is a **critical setting for connection stability**.

| Value | Behavior |
|-------|----------|
| 0 | Heartbeat disabled - host must poll for status (NOT RECOMMENDED) |
| 1 | Heartbeat enabled - firmware expects 0xAA messages every ~2 seconds |

**WARNING:** Disabling heartbeat (`SendHeartBeat=0`) can cause:
- Cold start failures after ~11.7 seconds with `projectionDisconnected`
- Unstable firmware initialization
- Session termination without warning

See [SendHeartBeat — Deep Analysis](#sendheartbeat--deep-analysis) below for full protocol details and [`heartbeat_analysis.md`](../01_Firmware_Architecture/heartbeat_analysis.md) for comprehensive binary evidence.

### BackgroundMode
**Type:** Toggle (0/1) | **Default:** 0

| Value | Behavior |
|-------|----------|
| 0 | Show connection UI (logo, progress) |
| 1 | Hide UI - fixes blur/overlay issues on some head units |

### BoxConfig_DelayStart
**Type:** Number (0-30) | **Default:** 0

Firmware calls `usleep(value * 1000000)` before USB init.

| Value | Behavior |
|-------|----------|
| 0 | Immediate start |
| 1-30 | Wait N seconds before USB init |

### MediaLatency
**Type:** Number (300-2000) | **Default:** 1000

Audio/video buffer size in milliseconds. AppleCarPlay binary enforces a 500ms floor (values <500 are clamped to 500).

| Value | Behavior |
|-------|----------|
| 300-499 | Clamped to 500 by AppleCarPlay binary |
| 500-1000 | Balanced (default is 1000) |
| 1000-2000 | High latency - very stable, noticeable A/V desync |

---

## USB / Connection Settings

### AutoResetUSB
**Type:** Toggle (0/1) | **Default:** 1

| Value | Behavior |
|-------|----------|
| 0 | Normal disconnect - USB interface stays initialized |
| 1 | Full reset - USB controller power-cycled via sysfs |

D-Bus: `HUDComand_A_ResetUSB` signal, logged as `"$$$ ResetUSB from HU"`.

**NOT a factory reset** - only resets USB peripheral controller.

### USBConnectedMode
**Type:** Select (0-2) | **Default:** 0

USB gadget function selection — controls what the adapter exposes to the host via USB.
Consumer: `start_mtp.sh` → writes to `/sys/class/android_usb_accessory/android0/functions`.

| Value | Functions | Behavior |
|-------|-----------|----------|
| 0 | `mtp,adb` | Both MTP (file transfer) and ADB (debug) exposed to host |
| 1 | `mtp` | MTP only — no ADB debug interface |
| 2 | `adb` | ADB only — no MTP file transfer |

### USBTransMode
**Type:** Select (0/1) | **Default:** 0

USB Zero-Length Packet mode for AOA (Android Auto) bulk transfers.
Consumer: `start_aoa.sh` → writes to `/sys/module/g_android_accessory/parameters/accZLP`.

| Value | Behavior |
|-------|----------|
| 0 | Standard bulk transfers — no ZLP termination |
| 1 | Enable ZLP — fixes stalling on host USB controllers that require zero-length packet termination |

### iAP2TransMode
**Type:** Select (0/1) | **Default:** 0

| Value | Behavior |
|-------|----------|
| 0 | Normal iAP2 framing |
| 1 | Compatible mode - longer ACK timeouts, smaller messages |

### WiredConnect
**Type:** Toggle (0/1) | **Default:** 1

| Value | Behavior |
|-------|----------|
| 0 | Wireless only |
| 1 | Allow wired mode fallback |

### NeedAutoConnect
**Type:** Toggle (0/1) | **Default:** 1

| Value | Behavior |
|-------|----------|
| 0 | Manual - wait for phone to initiate |
| 1 | Auto - reconnect to `LastConnectedDevice` on boot |

---

## Audio Settings

### MediaQuality
**Type:** Select (0/1) | **Default:** 1

| Value | Rate | Description |
|-------|------|-------------|
| 0 | 44.1kHz | CD quality - compatible with all cars |
| 1 | 48kHz | DVD quality - better fidelity |

### MicType
**Type:** Select (0-2) | **Default:** 0

| Value | Source | Implementation |
|-------|--------|----------------|
| 0 | Car mic | Routes `/dev/snd/pcmC0D0c` capture to phone |
| 1 | Box mic | Uses adapter's 3.5mm mic input |
| 2 | Phone mic | Phone's built-in mic |

**Hardware Note:** The CPC200-CCPA (A15W) does **not** have a built-in microphone. Option 1 (Box mic) is not applicable for this model. Use 0 (Car mic) or 2 (Phone mic) only.

### MicMode
**Type:** Select (0-4) | **Default:** 0

| Value | Algorithm |
|-------|-----------|
| 0 | Auto - firmware selects based on detected noise |
| 1-4 | Different WebRTC NS configurations |

### EchoLatency
**Type:** Number (20-2000) | **Default:** 320

Echo cancellation delay parameter in milliseconds. Used by WebRTC AECM module.

| Value | Effect |
|-------|--------|
| 20-100 | Low delay - minimal audio path latency |
| 100-500 | Typical car systems |
| 500-2000 | High delay - significant audio buffering |
| **320** | **Sentinel value** — triggers firmware to read `echoDelay` from BoxSettings config instead of using the literal value |

**Architectural threshold:** Values above 200ms are clamped internally by the WebRTC AECM at `0x2dfa2`. Effective AECM delay is min(EchoLatency, 200).

### MediaPacketLen / TtsPacketLen / VrPacketLen
**Type:** Number (200-40000) | **Default:** 200

USB bulk transfer sizes for different audio streams:
- **MediaPacketLen:** Music/media audio
- **TtsPacketLen:** Navigation voice (TTS)
- **VrPacketLen:** Voice recognition/Siri microphone

---

## Display Settings

### ScreenDPI
**Type:** Number (0-480) | **Default:** 0

| Value | Effect |
|-------|--------|
| 0 | Auto - phone uses default |
| 160 | Low density (MDPI) |
| 240 | Medium density (HDPI) |
| 320 | High density (XHDPI) |
| 480 | Extra high density (XXHDPI) |

### MouseMode
**Type:** Toggle (0/1) | **Default:** 0

| Value | Behavior |
|-------|----------|
| 0 | Direct touch - absolute coordinates passed to phone |
| 1 | Cursor mode - relative movements, tap to click |

**Binary Evidence:** `MouseMode` string found in ARMadb-driver config key table.

---

## GPS/GNSS Settings (Binary Verified Jan 2026)

### HudGPSSwitch
**Type:** Toggle (0/1) | **Default:** 0

Controls whether GPS data from the head unit is forwarded to the phone.

| Value | Behavior |
|-------|----------|
| 0 | GPS from HU disabled - phone uses own GPS |
| 1 | GPS from HU enabled - adapter forwards NMEA to phone |

**Note:** Factory default is `0`. The `--info` output may show `1` if previously changed via `riddleBoxCfg -s HudGPSSwitch 1`. Must be set to `1` for GPS forwarding to work.

**Binary Evidence:** `BOX_CFG_HudGPSSwitch Closed, not use GPS from HUD`

### GNSSCapability (Live-Tested Feb 2026) **[CarPlay only]**
**Type:** Bitmask (0-65535) | **Default:** 0

Detailed bitmask specifying which GNSS features are advertised to the phone during iAP2 identification.

| Value | Behavior |
|-------|----------|
| 0 | No GNSS capability advertised; **LocationEngine disabled** |
| 1+ | GNSS capability bitmask; **enables LocationEngine for GPS forwarding to phone** |

**CRITICAL:** This setting is **REQUIRED** for `iAP2LocationEngine` to be enabled and for the adapter to advertise `locationInformationComponent` during iAP2 identification. Without `GNSSCapability ≥ 1`, the phone never learns the adapter can provide location data and never sends `StartLocationInformation`.

**DashboardInfo bit 1 is NOT required** for GPS forwarding to the phone. `GNSSCapability` alone gates the adapter→phone GPS path. DashboardInfo bit 1 controls the phone→HUD location data direction, which is a separate engine. Live-tested Feb 2026: `DashboardInfo=5` (bits 0+2, no bit 1) + `GNSSCapability=3` → GPS forwarding to iPhone fully operational.

**Binary Evidence:** `GNSSCapability=%d` format string in ARMiPhoneIAP2

**GNSSCapability Bitmask:**

| Bit | Value | NMEA Sentence | Purpose |
|-----|-------|---------------|---------|
| 0 | 1 | `$GPGGA` | Global Positioning System Fix Data |
| 1 | 2 | `$GPRMC` | Recommended Minimum Specific GPS Transit Data |
| 3 | 8 | `$PASCD` | Proprietary (dead-reckoning/compass) |

Recommended: `GNSSCapability=3` (GPGGA + GPRMC).

### DashboardInfo (Live-Tested & Verified Feb 2026) **[CarPlay only]**
**Type:** Bitmask (0-7) | **Default:** 1

Controls which iAP2 data engines are enabled during identification. This is a **3-bit bitmask** where each bit enables a different data stream to the head unit:

| Bit | Value | iAP2 Engine | Data Type | Requirements |
|-----|-------|-------------|-----------|--------------|
| 0 | 1 | **iAP2MediaPlayerEngine** | NowPlaying info (track, artist, album) | None |
| 1 | 2 | **iAP2LocationEngine** | Location FROM phone TO HUD | GNSSCapability ≥ 1 (for phone→HUD direction; GPS forwarding adapter→phone uses GNSSCapability alone) |
| 2 | 4 | **iAP2RouteGuidanceEngine** | Navigation TBT (turn-by-turn directions) | None |

**IMPORTANT:** `iAP2CallStateEngine` is always enabled regardless of DashboardInfo value.

**Common Values:**
| Value | Bits Set | Engines Enabled |
|-------|----------|-----------------|
| 0 | None | CallState only |
| 1 | Bit 0 | MediaPlayer + CallState (default) |
| 2 | Bit 1 | LocationEngine + CallState (requires GNSSCapability ≥ 1) |
| 3 | Bits 0+1 | MediaPlayer + Location + CallState |
| 4 | Bit 2 | RouteGuidance + CallState |
| 5 | Bits 0+2 | MediaPlayer + RouteGuidance + CallState |
| 7 | All | All engines |

**Live Test Results (Feb 2026 via SSH):**
```
DashboardInfo=1: iAP2MediaPlayerEngine ✓
DashboardInfo=2: (nothing extra - GNSSCapability was 0)
DashboardInfo=2 + GNSSCapability=1: iAP2LocationEngine ✓
DashboardInfo=3 + GNSSCapability=1: MediaPlayer + Location ✓
DashboardInfo=4: iAP2RouteGuidanceEngine ✓
DashboardInfo=7 + GNSSCapability=1: All three engines ✓
```

**Engine Datastore:** `/etc/RiddleBoxData/AIEIPIEREngines.datastore`
- Binary plist caching enabled engines
- Created on first iAP2 connection after boot
- Must be deleted (`rm -f`) for DashboardInfo changes to take effect

**Binary Evidence (ARMiPhoneIAP2_unpacked):**
```asm
; At 0x15f50: Load DashboardInfo config value
ldr r0, str.DashboardInfo      ; Load "DashboardInfo" key
blx fcn.0006a43c               ; Read config value
mov r7, r0                     ; r7 = DashboardInfo value

; At 0x15f78-0x15f98: Test each bit and call corresponding engine init
tst r7, #1                     ; Test bit 0 (MediaPlayer)
bne -> bl 0x282b8              ; If set, initialize MediaPlayerEngine
tst r7, #2                     ; Test bit 1 (Location)
bne -> bl 0x2aa6c              ; If set, initialize LocationEngine
tst r7, #4                     ; Test bit 2 (RouteGuidance)
bne -> bl 0x2ebc4              ; If set, initialize RouteGuidanceEngine
```

**Protocol Integration:**
- Used during iAP2 identification (`CiAP2IdentifyEngine`) to configure which data streams the adapter supports
- Controls data flow TO the HUD (cluster display), not from it
- Related D-Bus signal: `HU_GPS_DATA` for GPS data transmission
- Related message type: `0x2A (DashBoard_DATA)` for dashboard data packets

**Related Settings:**
- `GNSSCapability`: **REQUIRED** for LocationEngine (bit 1). Must be ≥ 1.
- `HudGPSSwitch`: Controls GPS data flow to HUD after engine is enabled
- These settings work together during iAP2 capability negotiation

**Recommended Configurations:**
```bash
# NowPlaying metadata only (most common use case)
riddleBoxCfg -s DashboardInfo 1

# NowPlaying + GPS location
riddleBoxCfg -s DashboardInfo 3
riddleBoxCfg -s GNSSCapability 1

# All features (NowPlaying + GPS + Navigation)
riddleBoxCfg -s DashboardInfo 7
riddleBoxCfg -s GNSSCapability 1

# After changing, delete cached engines and reboot:
rm -f /etc/RiddleBoxData/AIEIPIEREngines.datastore
busybox reboot
```

### DashBoard_DATA Message Format (0x2A) - Capture Verified Feb 2026

When DashboardInfo bit 2 is set, the adapter sends navigation data to the host via message type 0x2A (DashBoard_DATA) containing JSON payloads.

**Message Structure:**
```
Offset  Size  Field
0x00    4     Magic (0x55AA55AA)
0x04    4     Payload length (little-endian)
0x08    4     Message type (0x0000002A)
0x0C    4     Type inverse (0xFFFFFFD5)
0x10    4     Subtype (0x000000C8 = 200 for NaviJSON)
0x14    N     JSON payload (null-terminated)
```

**NaviJSON Payload Fields (Live-Capture Verified Feb 2026):**

| Field | Type | Observed Values | Description |
|-------|------|-----------------|-------------|
| `NaviStatus` | int | 0, 1 | Navigation state: 0=inactive/flush, 1=active |
| `NaviTimeToDestination` | int | 1200, 1260 | ETA in seconds |
| `NaviDestinationName` | string | `"Lyn Ary Park"` | Destination name |
| `NaviDistanceToDestination` | int | 18952, 19097 | Total distance in meters |
| `NaviAPPName` | string | `"Apple Maps"` | Navigation app name |
| `NaviRemainDistance` | int | 0–484 | Distance to next maneuver (meters) |
| `NaviRoadName` | string | `"Elmore Rd"` | Extracted from `ManeuverDescription` (see note) |
| `NaviOrderType` | int | 6, 16 | Turn order type (enum, NOT 0–6 as previously assumed) |
| `NaviManeuverType` | int | 2, 11, 28, 29 | CPManeuverType 0–53 (see Table 15-16) |
| `NaviTurnAngle` | int | 0, 2 | Enum/type value, NOT degrees (=0 for roundabouts, =2 for right turns) |
| `NaviTurnSide` | int | 0, 2 | Driving side (0=RHD, 1=LHD, 2=observed but undocumented). =0 for all roundabouts on US route. |
| `NaviRoundaboutExit` | int | 1, 2 | Roundabout exit number 1–19 (only sent for roundabout maneuvers) |

**IMPORTANT — Fields NOT forwarded by adapter (live-capture confirmed):**
- `NaviJunctionType` — NEVER sent in any observed message. Always absent from JSON.
- `NaviTurnAngle` — NOT sent for roundabout maneuvers (only for regular turns).
- `JunctionElementAngle` / `JunctionElementExitAngle` — stripped by adapter (see Data Loss section).
- `AfterManeuverRoadName` — stripped.
- Lane guidance data — stripped.

**NaviRoadName Duplication Bug:** The adapter firmware duplicates `NaviRoadName` in the JSON output:
```json
{"NaviRoadName":"Elmore Rd","NaviRoadName":"Elmore Rd","NaviOrderType":16,"NaviRoundaboutExit":1,"NaviManeuverType":28}
```
This is a firmware bug — the key appears twice with the same value.

**Example Payloads (Live Capture Feb 2026 — Lyn Ary Park Route):**

Route Status Update (from 0x5201):
```json
{"NaviStatus":1,"NaviRemainDistance":245}
```

Roundabout Maneuver (exit 1, from ManeuverIdx advance):
```json
{"NaviRoadName":"Elmore Rd","NaviRoadName":"Elmore Rd","NaviOrderType":16,"NaviRoundaboutExit":1,"NaviManeuverType":28}
```

Right Turn Maneuver (from ManeuverIdx advance):
```json
{"NaviRoadName":"De Armoun Rd","NaviRoadName":"De Armoun Rd","NaviOrderType":6,"NaviTurnAngle":2,"NaviTurnSide":2,"NaviManeuverType":2}
```

Distance Update (~1/sec):
```json
{"NaviRemainDistance":239}
```

**Data Flow (Live-Capture Verified Feb 2026):**
```
iPhone (Apple Maps)
    │
    ├─► iAP2 StartRouteGuidanceUpdate (0x5200) ◄── Adapter requests TBT data
    │
    ├─► iAP2 RouteGuidanceUpdate (0x5201) ──────► Route status updates (~1/sec)
    │        Contains: distance, ETA, route state
    │        Triggers: ManeuverIdx advance when current maneuver passed
    │
    └─► iAP2 RouteGuidanceManeuverUpdate (0x5202) ► Full maneuver list (burst on route start)
             Contains: ManeuverDescription, AfterManeuverRoadName, DrivingSide,
                       JunctionType, JunctionElementAngle, JunctionElementExitAngle
             ⚠ Adapter extracts ONLY: ManeuverDescription → NaviRoadName
             ⚠ JunctionElement* fields trigger iAP2UpdateEntity.cpp:314 ASSERT and are DROPPED
            │
            ▼
    Adapter (iAP2RouteGuidanceEngine)
            │
            ├─► Receives 0x5202 burst: stores ManeuverDescription[] indexed by ManeuverIdx
            │       Log: "use ManeuverDescription as roadName: Elmore Rd"
            │
            ├─► On 0x5201 with ManeuverIdx change:
            │       Log: "update ManeuverIdx: 1"
            │       Emits TWO _SendNaviJSON calls:
            │         1. {"NaviRemainDistance": N}  (distance to new maneuver)
            │         2. {"NaviRoadName":..., "NaviManeuverType":..., ...}  (maneuver fields)
            │
            ├─► On 0x5201 distance-only updates:
            │       Emits: {"NaviRemainDistance": N}  (countdown ~1/sec)
            │
            └─► _SendNaviJSON() ─── Writes JSON to USB MEDIA_DATA (0x2A, subtype 200)
            │
            ▼
    USB Message Type 0x2A (MEDIA_DATA, subtype NAVI_JSON=200)
            │
            ▼
    Host Application
```

**Data Loss Analysis (Live-Capture Evidence Feb 2026):**

The adapter's `iAP2RouteGuidanceEngine` processes 0x5202 messages through `iAP2UpdateEntity.cpp`.
When it encounters iAP2 dictionary/group field types it doesn't understand, it triggers:
```
### [ASSERT] iAP2UpdateEntity.cpp:314 "", "dict"
```
and silently drops the data. These asserts were observed on multiple 0x5202 maneuver messages
during route setup. The dropped fields likely include `JunctionElementAngle` and
`JunctionElementExitAngle` — structured iAP2 group types containing per-spoke angular data.

**Impact on roundabout rendering (confirmed with multi-roundabout capture Feb 2026):**
The adapter forwards only the exit ordinal (NaviRoundaboutExit + NaviManeuverType) with NO
junction geometry. In a capture of 12 consecutive roundabouts on W Main St, ALL had
turnAngle=0, turnSide=0, junction=0. Only CPTypes 28 (exit 1) and 29 (exit 2) were observed,
both mapping to the same AAOS `Maneuver.TYPE=34` (ROUNDABOUT_ENTER_AND_EXIT_CCW).
The AAOS cluster displayed wrong icons for every roundabout because:
1. No exit angle data → AAOS picks a generic glyph per exit number
2. "Exit 2" on a 4-spoke roundabout (~180° straight through) renders identically to
   "exit 2" on a 6-spoke roundabout (~120° partial turn)
3. The iPhone sends `paramCount=21` per 0x5201 and `LaneGuidance` data — all stripped

**Adapter Log Evidence (Live Feb 2026):**
```
[iAP2Engine] Enable iAP2 iAP2RouteGuidanceEngine Capability
[iAP2Engine] Send_changes:StartRouteGuidanceUpdate(0x5200), msgLen: 18
[CiAP2Session_CarPlay] Message from iPhone: 0x5201 RouteGuidanceUpdate
[CiAP2Session_CarPlay] Message from iPhone: 0x5202 RouteGuidanceManeuverUpdate
### [ASSERT] iAP2UpdateEntity.cpp:314 "", "dict"
### [ASSERT] iAP2UpdateEntity.cpp:314 "", "dict"
[iAP2RouteGuidanceEngine] use ManeuverDescription as roadName: Elmore Rd
[iAP2RouteGuidanceEngine] _SendNaviJSON:
{"NaviRoadName":"Elmore Rd","NaviRoadName":"Elmore Rd","NaviOrderType":16,"NaviRoundaboutExit":1,"NaviManeuverType":28}
[iAP2RouteGuidanceEngine] update ManeuverIdx: 1
```

**Route Maneuver List (Live Capture — Lyn Ary Park Route, 20 maneuvers):**
The iPhone sent 20 0x5202 messages in a ~200ms burst at route start:
```
Idx  ManeuverDescription              CPType
0    Proceed to the route             11 (proceedToRoute)
1    Elmore Rd                        28 (roundaboutExit1)
2    De Armoun Rd                      2 (right)
3    Brayton Dr                        ? (not observed at runtime)
4    Seward Hwy North                  ?
5    Seward Hwy North                  ?
6    Merge onto 1 New Seward Hwy       ?
7    E Northern Lights Blvd            ?
8    Turnagain Pkwy                    ?
9    Illiamna Ave                      ?
10   Foraker Dr                        ?
11   Foraker Dr                        ?
12   Prepare to park your car          ?
13   Prepare to park your car          ?
14   Take a slight right turn          ?
15   Take a left                       ?
16   Take a left                       ?
17   On your right: Lyn Ary Park       ?
18   On your right: Lyn Ary Park       ?
19   (empty)                           ?
```

**Route Maneuver List (Live Capture — W Main St Roundabout Route, 24 maneuvers):**
12 of 24 maneuvers captured. ALL roundabouts, every one on W Main St:
```
Adapter  App Time   CPType  Exit  Road           Start Dist
Idx 1    14:57:11   29      2     W 131st St     467m
Idx 2    14:57:42   29      2     W Main St      1555m
Idx 3    14:59:09   28      1     W Main St      1582m
Idx 4    15:00:22   29      2     W Main St      310m
Idx 5    15:00:39   29      2     W Main St      1198m
Idx 6    15:01:36   29      2     W Main St      570m
Idx 7    15:01:45   29      2     W Main St      272m
Idx 8    15:02:18   29      2     W Main St      463m
Idx 9    15:02:41   29      2     W Main St      347m
Idx 10   15:03:31   29      2     W Main St      757m
Idx 11   15:04:06   29      2     W Main St      741m
Idx 12   15:05:19   29      2     E Main St      —
```
All had: turnAngle=0, turnSide=0, junction=0, NaviOrderType=16.
iPhone sent paramCount=21 per 0x5201 (adapter forwards ~5 fields).

**Requirements for Navigation Data:**
1. Set `DashboardInfo` with bit 2 enabled (value 4, 5, 6, or 7)
2. iPhone must have active navigation in Apple Maps (or compatible app)
3. Adapter must successfully complete iAP2 identification with RouteGuidance capability
4. Host receives 0x2A messages with JSON navigation payloads

### GPS Data Format

The adapter accepts standard **NMEA 0183** sentences via `/tmp/gnss_info` file:

| Sentence | iAP2 Component | Description |
|----------|----------------|-------------|
| `$GPGGA` | `globalPositionSystemFixData` | Position fix data |
| `$GPRMC` | `recommendedMinimumSpecificGPSTransitData` | Minimum GPS data |
| `$GPGSV` | `gpsSatellitesInView` | Satellite information |
| `$PASCD` | (proprietary) | Vehicle data |

**Additional Vehicle Data:**
- `VehicleSpeedData` - Speed from vehicle CAN
- `VehicleHeadingData` - Compass heading
- `VehicleGyroData` - Gyroscope readings
- `VehicleAccelerometerData` - Accelerometer readings

**Commands (Type 0x08):**
- Command 18 (0x12): `StartGNSSReport` - Begin GPS forwarding
- Command 19 (0x13): `StopGNSSReport` - Stop GPS forwarding

---

## Charge Mode (Binary Verified Jan 2026)

USB charging speed is controlled via GPIO pins, not a riddle.conf key.

### Charge Mode File
**Path:** `/tmp/charge_mode` or `/etc/charge_mode`

| Value | GPIO6 | GPIO7 | Mode | Log Message |
|-------|-------|-------|------|-------------|
| 0 | 1 | 1 | SLOW | `CHARGE_MODE_SLOW!!!!!!!!!!!!!!!!!` |
| 1 | 1 | 0 | FAST | `CHARGE_MODE_FAST!!!!!!!!!!!!!!!!!` |

**GPIO Initialization (from init_gpio.sh):**
```bash
echo 6 > /sys/class/gpio/export
echo out > /sys/class/gpio/gpio6/direction
echo 7 > /sys/class/gpio/export
echo out > /sys/class/gpio/gpio7/direction
echo 1 >/sys/class/gpio/gpio6/value   # Enable charging
echo 1 >/sys/class/gpio/gpio7/value   # Slow mode (default)
```

### OnlyCharge Work Mode

"OnlyCharge" is an iPhone work mode (not a config key) indicating the phone is connected for charging only without projection:

| Work Mode | Description |
|-----------|-------------|
| AirPlay | Audio/video mirroring |
| CarPlay | CarPlay projection |
| iOSMirror | Screen mirroring |
| OnlyCharge | Charging only, no projection |

---

## Navigation Video Parameters (iOS 13+)

### AdvancedFeatures **[CarPlay only]**
**Type:** Boolean (0-1) | **Default:** 0 | **Range:** 0-1 (enforced by riddleBoxCfg)

**Purpose:** Controls instrument cluster / navigation video (CarPlay Dashboard) and NaviScreen view area negotiation.

| Value | Effect |
|-------|--------|
| 0 (default) | Used at runtime, navigation video flows via `naviScreenInfo` (Path A) — but **only if** the adapter has previously had this flag set to 1 (see "Persistent Unlock" below). On a virgin adapter, no 0x2C frames are emitted. |
| 1 | Activates the legacy navigation-video path (Path B) and, on first use, creates the persistent unlock file. Sets `g_bSupportNaviScreen` and enables NaviScreen ViewArea/SafeArea negotiation. |

**Activation requires a one-time persistent unlock.** Setting `AdvancedFeatures=1` at least once in the device's lifetime causes ARMadb to write `/etc/RiddleBoxData/HU_NAVISCREEN_INFO`, which AppleCarPlay reads at session-init to set `g_bSupportNaviScreen`. The file persists across reboots and across reverting `AdvancedFeatures=0`. Without it, `_AltScreenSetup` is never called and no Type 0x2C frames flow — even when the host sends `naviScreenInfo` (the JSON parser bypass at `0x16e64` only clears the parser-side gate, not the session-init gate).

The persistent-unlock behavior was originally observed and documented during the Jan 2026 discovery of navigation video. See "Navigation Video Activation" / "Persistent Unlock" sections below for the full activation matrix and binary trace.

**CORRECTION (2026-02-18, r2 + live verified):** Earlier documentation incorrectly described this as a bitmask (0-3) with bit 1 controlling `g_bSupportViewarea`. This is **wrong**:
- `riddleBoxCfg` enforces max=1 (`AdvancedFeatures:0 ~ 1`). Values 2+ are rejected.
- Only `tst.w sb, 1` (bit 0) exists in the firmware decode path. No `tst.w sb, 2` exists anywhere.
- **`g_bSupportViewarea`** is set from `HU_VIEWAREA_INFO` file content, NOT from AdvancedFeatures. See [ViewArea/SafeArea section](#viewarea--safearea-configuration-r2--live-verified-feb-2026) below.

**WARNING:** Enabling navigation video (value 1) causes the adapter to send an **additional H.264 video stream** (Type 0x2C) alongside the primary stream (Type 0x06). This doubles video processing load. Only enable on systems that are prepared to handle and decode a second video stream. On systems not expecting it, the additional stream may cause USB bandwidth pressure or wasted processing. Host apps that do not handle Type 0x2C should leave this at 0 and use `naviScreenInfo` in BoxSettings only when ready to consume the stream.

**r2 Evidence (AppleCarPlay at 0x25958):**
```
HU features, g_bSupportNaviScreen: %d, g_bSupportViewarea: %d
Phone features, featureAltScreen: %d, featureViewAreas: %d
```
The log format shows both globals, but they are set by **different mechanisms**: `g_bSupportNaviScreen` from AdvancedFeatures, `g_bSupportViewarea` from HU_VIEWAREA_INFO file content. When both HU and phone support alt screen (`g_bSupportNaviScreen && phone.featureAltScreen`), `_AltScreenSetup` is called. If neither supports it, logs `"Not support new fratues"` and skips.

**How to Set:**
```bash
/usr/sbin/riddleBoxCfg -s AdvancedFeatures 1    # enable naviScreen
/usr/sbin/riddleBoxCfg --upConfig
```

**When Set to 1:**
1. Adapter advertises `"supportFeatures":"naviScreen"` in boxInfo JSON
2. Adapter processes `naviScreenInfo` from incoming BoxSettings
3. Navigation video (Type 0x2C) becomes available
4. NaviScreen view area / safe area negotiation via `HU_NAVISCREEN_VIEWAREA_INFO` and `HU_NAVISCREEN_SAFEAREA_INFO`

---

### ViewArea / SafeArea Configuration (r2 + Live Verified Feb 2026)

CarPlay's ViewArea/SafeArea system tells the iPhone how to position UI elements relative to the physical display. This is critical for **non-rectangular screens** (curved edges, rounded corners, status bar overlays) where full-resolution video should fill the screen but interactive elements must stay within a visible/touchable region.

**Concept:**
- **ViewArea** = the full rectangle where CarPlay renders video (typically = screen resolution)
- **SafeArea** = a smaller inset rectangle where interactive UI must be placed
- **drawUIOutsideSafeArea** = controls what renders outside the SafeArea boundary (see below)
- The iPhone receives both and composites accordingly

**drawUIOutsideSafeArea behavior (live tested 2026-02-18 on iOS 18):**

| Value | Outside SafeArea | Inside SafeArea |
|-------|-----------------|-----------------|
| 0 | **Black/empty** — hard crop, nothing renders beyond SafeArea | All UI + content |
| 1 | **Home screen wallpaper only** — wallpaper extends to full ViewArea | All UI + content |

**Important:** With `drawUIOutsideSafeArea=1`, only the home screen wallpaper extends beyond the SafeArea. All other content — including Maps tiles, Now Playing artwork, app backgrounds, and all interactive controls (icons, buttons, lists, search bars) — remains constrained within the SafeArea. This is iOS-side behavior; individual CarPlay apps do not render content outside the SafeArea regardless of this flag.

For **curved/non-rectangular screens**, value 1 is recommended: wallpaper fills the curved edges aesthetically while all interactive elements stay in the touchable region.

**Example: GM 2400×960 display with curved corners:**
```
ViewArea:  {width: 2400, height: 960, originX: 0, originY: 0}    ← full screen
SafeArea:  {width: 2200, height: 760, originX: 100, originY: 100} ← 100px inset all around
drawUIOutsideSafeArea: 1   ← wallpaper fills to edges, everything else stays inside SafeArea
```

#### Config Keys and Data Structures

**Main Screen (gated by `g_bSupportViewarea` — set from `HU_VIEWAREA_INFO` file having valid dimensions, NOT from AdvancedFeatures):**

| Config Key | Size | Struct (uint32 LE) | Purpose |
|---|---|---|---|
| `HU_VIEWAREA_INFO` | 24B (0x18) | `[width, height, width_dup, height_dup, 0, 0]` | Main screen viewable area rectangle |
| `HU_SAFEAREA_INFO` | 20B (0x14) | `[width, height, originX, originY, drawUIOutside]` | Main screen safe area insets + outside flag |

**Navigation/Cluster Screen (gated by `g_bSupportNaviScreen` / AdvancedFeatures bit 0):**

| Config Key | Size | Struct (uint32 LE) | Purpose |
|---|---|---|---|
| `HU_NAVISCREEN_VIEWAREA_INFO` | 24B (0x18) | `[width, height, originX, originY, 0, 0]` | NaviScreen viewable area |
| `HU_NAVISCREEN_SAFEAREA_INFO` | 20B (0x14) | `[width, height, originX, originY, outside]` | NaviScreen safe area |
| `HU_NAVISCREEN_INFO` | 24B (0x18) | `[width, height, fps, ?, ?, ?]` | NaviScreen resolution |

All stored as flat files at `/etc/RiddleBoxData/[key_name]`.

#### AppleCarPlay: _CopyDisplayDescriptions (fcn.0001c0b4, 2112B)

This function builds the CarPlay display info dictionary during AirPlay session negotiation. It runs when the iPhone connects and is called from 4 sites.

**Gate logic (r2 verified):**
```
1. Check phone.featureViewAreas (struct offset 0x2ED9)  ← iPhone reports this
   If 0 → skip to NaviScreen section
2. Check g_bSupportViewarea (global at 0xabbe0)          ← set at init from HU_VIEWAREA_INFO file (NOT AdvancedFeatures)
   If 0 → skip to NaviScreen section
3. Read HU_VIEWAREA_INFO (24 bytes) via fcn.00073d14
   Validate width > 0 AND height > 0
4. Build viewArea CFLDictionary:
   - "widthPixels"       = viewarea width
   - "heightPixels"      = viewarea height
   - "originXPixels"     = viewarea X origin
   - "originYPixels"     = viewarea Y origin
   - "DuckPosition"      = dock position (int64) — **NOTE:** Despite the dict key name, this value maps to `viewAreaStatusBarEdge` (firmware naming inconsistency)
5. Read HU_SAFEAREA_INFO (20 bytes)
   If valid (width > 0 AND height > 0):
     Build safeArea sub-dictionary:
     - "widthPixels"           = safe area width
     - "heightPixels"          = safe area height
     - "originXPixels"         = safe area X origin
     - "originYPixels"         = safe area Y origin
     - "drawUIOutsideSafeArea" = kCFLBooleanTrue if outside flag != 0
   If no valid SafeArea:
     Fallback: use ViewArea dims as SafeArea, drawUIOutsideSafeArea = false
6. Set "viewAreaStatusBarEdge" in viewArea dict
7. Set "viewAreaTransitionControl" = kCFLBooleanFalse (hardcoded)
8. Add viewArea to viewAreas CFLArray
   Log: "add viewAreasArray: %@"

--- NaviScreen section (separate gate) ---
9. Check phone.featureAltScreen (struct offset 0x2ED8)
   If 0 → skip entirely
10. Check g_bSupportNaviScreen (global at 0xabbe1)
    If 0 → skip entirely
11. Read HU_NAVISCREEN_VIEWAREA_INFO → build same dict structure
12. Read HU_NAVISCREEN_SAFEAREA_INFO → build same sub-dict
    Log: "add AltScreen viewAreasArray: %@"
```

#### ARMadb-driver: Auto-Update on Connection (ProceessCmdOpen)

When a phone connects, `fcn.00021cb0` (ProceessCmdOpen) compares stored `HU_VIEWAREA_INFO` dimensions with the current screen dimensions from the Open message. If they differ, it auto-updates `HU_VIEWAREA_INFO` to match the new screen size (full-screen dimensions, origin 0,0). This ensures ViewArea always reflects the resolution declared in the Open message.

**Key limitation:** This auto-update sets ViewArea = full screen dimensions. It does NOT update `HU_SAFEAREA_INFO`. SafeArea must be configured separately (see below).

#### ARMadb-driver: BoxSettings SafeArea Path (NaviScreen Only)

The BoxSettings parser (`fcn.00016c20`) processes `naviScreenInfo.safearea` JSON:
```json
{
  "naviScreenInfo": {
    "width": 480,
    "height": 272,
    "fps": 30,
    "safearea": {
      "width": 460,
      "height": 252,
      "x": 10,
      "y": 10,
      "outside": 1
    }
  }
}
```
Logged as: `"safearea %dx%d, %d,%d, %d"` (W, H, X, Y, outside)
Writes: `HU_NAVISCREEN_SAFEAREA_INFO` (20B) and `HU_NAVISCREEN_VIEWAREA_INFO` (24B)

**No BoxSettings path exists for main screen SafeArea.** `HU_SAFEAREA_INFO` can only be configured via direct file write (SSH or SendFile).

#### How to Configure Main Screen SafeArea

**Option 1: SSH (one-time setup)**
```bash
ssh root@192.168.43.1
# Write HU_SAFEAREA_INFO: 20 bytes = 5 × uint32 LE
# Fields: width, height, originX, originY, drawUIOutside
# Example for 2400×960 screen with 50px inset on left/right, 30px top/bottom:
python3 -c "
import struct
data = struct.pack('<5I', 2300, 900, 50, 30, 1)
open('/etc/RiddleBoxData/HU_SAFEAREA_INFO', 'wb').write(data)
"
# AdvancedFeatures is boolean 0-1 (NOT a bitmask). Values >1 are rejected by riddleBoxCfg.
# ViewArea is set via HU_VIEWAREA_INFO file content, not AdvancedFeatures.
# See AdvancedFeatures section above for details.
busybox reboot
```

**Option 2: Host App SendFile (Type 0x99)**
```
SendFile path="/etc/RiddleBoxData/HU_SAFEAREA_INFO"
Payload: 20 bytes (5 × uint32 LE): [width, height, originX, originY, drawUIOutside]
```
This requires adding SendFile support for the config path in the host app.

**Option 3: Host App BoxSettings (requires firmware support)**
The firmware does NOT parse a main-screen `safearea` field from BoxSettings. Only the naviScreenInfo sub-object safearea path exists.

#### What g_bSupportViewarea Actually Controls (Correction — Feb 2026, r2 + live verified)

Previous documentation incorrectly stated `g_bSupportViewarea` is set by AdvancedFeatures bit 1. **This is wrong.**

**Actual mechanism (r2 disassembly of AppleCarPlay init at ~0x16ca2, live-verified 2026-02-18):**
- `g_bSupportViewarea` (global at 0xabbe0) is set to 1 during AppleCarPlay init **if and only if** `/etc/RiddleBoxData/HU_VIEWAREA_INFO` exists with valid dimensions (width > 0, height > 0 at offsets 0x08 and 0x0C)
- Fallback: also checks `HU_NAVISCREEN_VIEWAREA_INFO` — if that has valid dims, g_bSupportViewarea = 1
- `AdvancedFeatures` only has **bit 0** tested in the entire firmware (`tst.w sb, 1` — no `tst.w sb, 2` exists). Max value is 1 (range-checked by riddleBoxCfg)
- `g_bSupportNaviScreen` (AdvancedFeatures bit 0) → gates **NaviScreen** ViewArea/SafeArea (checks at 0x1c3a0)

**Live test result:** Writing `HU_VIEWAREA_INFO` (24B) + `HU_SAFEAREA_INFO` (20B) to `/etc/RiddleBoxData/` and rebooting enabled main screen SafeArea on CarPlay with `AdvancedFeatures=1`. iPhone CarPlay correctly inset all interactive UI elements while rendering wallpaper full-screen (`drawUIOutsideSafeArea=true`).

Both screens have their own independent gate. The iPhone also reports its own capabilities (`featureViewAreas` for main, `featureAltScreen` for navi) which are ANDed with the HU flags.

#### Absent Features

These CarPlay ViewArea properties exist as strings in AppleCarPlay but are not functional:
- `viewAreaTransitionControl`: present but hardcoded to `kCFLBooleanFalse`
- `adjacentViewAreas`: string present but never populated with data
- `initialViewArea`: string present but not wired to config
- Corner clipping masks: zero strings in all binaries — **not supported**

---

### Navigation Video Activation (Binary Verified Feb 2026)

The firmware has **two independent paths** to activate navigation video. Either path can work independently:

**Path A: naviScreenInfo in BoxSettings (BYPASSES AdvancedFeatures check)**

If the host sends `naviScreenInfo` in BoxSettings JSON:
```json
{
  "naviScreenInfo": {
    "width": 480,
    "height": 240,
    "fps": 30
  }
}
```

The firmware:
1. Parses naviScreenInfo at 0x16e5c
2. **Immediately branches** to HU_SCREEN_INFO path (0x170d6)
3. Sends D-Bus signal `HU_SCREEN_INFO` with resolution data
4. Navigation video becomes available via Type 0x2C (AltVideoFrame)

**Binary Evidence (ARMadb-driver_2025.10_unpacked):**
```asm
0x16e5c  blx fcn.00015228              ; Parse JSON for "naviScreenInfo"
0x16e62  cmp r0, 0                     ; Check if key found
0x16e64  bne.w 0x170d6                 ; If FOUND → HU_SCREEN_INFO path (BYPASS)
0x16e68  ldr r0, "AdvancedFeatures"    ; Only reached if naviScreenInfo NOT found
0x16e6c  bl fcn.00066d3c               ; Read config value
0x16e70  cmp r0, 0                     ; Check AdvancedFeatures
0x16e72  bne 0x16f20                   ; If ≠ 0 → HU_NAVISCREEN_INFO path
0x16e7c  add r2, pc, "Not support NaviScreenInfo, return\n"
```

**Path B: AdvancedFeatures=1 (Fallback for legacy config)**

If `naviScreenInfo` is NOT provided in BoxSettings:
1. Firmware checks `AdvancedFeatures` config at 0x16e70
2. If AdvancedFeatures=1 → sends HU_NAVISCREEN_INFO D-Bus signal
3. Uses legacy naviScreenWidth/naviScreenHeight/naviScreenFPS from riddle.conf
4. Navigation video becomes available

**Activation Matrix:**

| `naviScreenInfo` in BoxSettings | `AdvancedFeatures` (current) | Persistent unlock present (`/etc/RiddleBoxData/HU_NAVISCREEN_INFO` exists) | Result |
|---|---|---|---|
| Yes | Either | Yes | ✅ Works (Path A — HU_SCREEN_INFO) |
| Yes | Either | **No** (virgin adapter) | ❌ No 0x2C — JSON parser bypasses, but `g_bSupportNaviScreen=0` blocks `_AltScreenSetup` |
| No | 1 | Yes | ✅ Works (Path B — HU_NAVISCREEN_INFO legacy) |
| No | 0 | Either | ❌ Rejected (`"Not support NaviScreenInfo"`) |

**Key Insight:** The `bne.w 0x170d6` branch at `0x16e64` only bypasses the **parser-side** `AdvancedFeatures` check. A **second, persistent gate** lives at the AppleCarPlay session-init layer: it reads `g_bSupportNaviScreen` from the file `/etc/RiddleBoxData/HU_NAVISCREEN_INFO`. ARMadb writes that file the first time it processes `AdvancedFeatures=1`, and the file survives reboots and reverting the flag to 0. Path A works regardless of the current `AdvancedFeatures` value, but **only after** `AdvancedFeatures=1` has been set at least once in the device's lifetime to create the unlock file.

---

### Persistent Unlock (Originally Documented Jan 2026)

Coverage of this behavior was lost during a doc-folder reorganization and a subsequent Feb 2026 binary-RE pass that introduced (and over-generalized) the parser-bypass finding. Restored here.

| Scenario | Navigation Video | Mechanism |
|----------|------------------|-----------|
| Virgin adapter (`AdvancedFeatures=1` never run), no `naviScreenInfo` | ❌ Not working | Both gates fail |
| Virgin adapter, `naviScreenInfo` sent | ❌ Not working | Parser bypasses, but `g_bSupportNaviScreen=0` |
| Run `riddleBoxCfg -s AdvancedFeatures 1 && --upConfig` once | ✅ Activated | ARMadb writes `/etc/RiddleBoxData/HU_NAVISCREEN_INFO` |
| Then set `AdvancedFeatures=0`, send `naviScreenInfo` (Path A) | ✅ Still working | Unlock file persists |
| Then set `AdvancedFeatures=0`, omit `naviScreenInfo` (Path B) | ❌ Not working | Legacy path requires runtime `=1` |
| Manually delete `AdvancedFeatures` line from `riddle.conf` | (re-emitted on next config sync) | `riddleBoxCfg` re-injects from default schema; unlock state lives in `/etc/RiddleBoxData/`, not `riddle.conf` |

**Practical guidance:** A host app cannot rely on `naviScreenInfo` alone if it might run against a virgin adapter. Options: (a) require a one-time SSH unlock by the user, or (b) ship the unlock as a first-run setup step if the host has shell access to the adapter.

### naviScreenWidth
**Type:** Number (0-4096) | **Default:** 480

Navigation screen width in pixels.

### naviScreenHeight
**Type:** Number (0-4096) | **Default:** 272

Navigation screen height in pixels.

### naviScreenFPS
**Type:** Number (10-60) | **Default:** 30

Navigation video frame rate.

**Tested Range:**
- **Maximum:** 60 FPS (hardware limit)
- **Minimum usable:** 10 FPS (below this, UI refresh is noticeably degraded)
- **Recommended:** 24-60 FPS for acceptable user experience

**Note:** While the adapter accepts values down to 10 FPS, testing showed UI responsiveness becomes unacceptable below this threshold. Target 24-60 FPS for production use.

### naviScreenInfo BoxSettings Configuration

Host applications configure navigation video via BoxSettings JSON:
```json
{
  "naviScreenInfo": {
    "width": 480,
    "height": 272,
    "fps": 30  // Range: 10-60, Recommended: 24-60
  }
}
```

**Requirements (when using naviScreenInfo Path A):**
1. The adapter must have had `AdvancedFeatures=1` set at least once in its lifetime — the file `/etc/RiddleBoxData/HU_NAVISCREEN_INFO` must exist (persistent unlock — see above). On a virgin adapter, run `riddleBoxCfg -s AdvancedFeatures 1 && riddleBoxCfg --upConfig` once via SSH. After that, `AdvancedFeatures` can be left at 0 and Path A continues to work.
2. Host must send `naviScreenInfo` in BoxSettings (0x19) JSON.
3. Host must handle NaviVideoData (Type 0x2C) messages.
4. Command 508 handshake: recommended to echo 508 back if received, but testing was inconclusive on whether this is strictly required.

**Note:** `naviScreenInfo` bypasses the `AdvancedFeatures` check **only in the JSON parser** (firmware branch at `0x16e64` → HU_SCREEN_INFO path). It does NOT bypass the AppleCarPlay session-init check on `g_bSupportNaviScreen`, which reads the persistent `/etc/RiddleBoxData/HU_NAVISCREEN_INFO` file. See "Persistent Unlock" above.

---

## BoxSettings JSON Mapping (Binary Verified Jan 2026)

**⚠️ SECURITY WARNING:** The `wifiName`, `btName`, and `oemIconLabel` fields are vulnerable to **command injection**. See `03_Security_Analysis/vulnerabilities.md`.

### Host to Adapter Fields **[Host→Adapter]** - Complete List

**Core Configuration:**

| JSON Field | riddle.conf | Type | Description |
|------------|-------------|------|-------------|
| `mediaDelay` | `MediaLatency` | int | Audio buffer (ms) |
| `syncTime` | - | int | Unix timestamp |
| `autoConn` | `NeedAutoConnect` | bool | Auto-reconnect flag |
| `autoPlay` | `AutoPlay` | bool | Auto-start playback |
| `autoDisplay` | - | bool | Auto display mode |
| `bgMode` | `BackgroundMode` | int | Background mode |
| `startDelay` | `BoxConfig_DelayStart` | int | Startup delay (sec) |
| `syncMode` | - | int | Sync mode |
| `lang` | - | string | Language code |

**Display / Video:**

| JSON Field | riddle.conf | Type | Description |
|------------|-------------|------|-------------|
| `androidAutoSizeW` | `AndroidAutoWidth` | int | Android Auto width |
| `androidAutoSizeH` | `AndroidAutoHeight` | int | Android Auto height |
| `screenPhysicalW` | - | int | Physical screen width (mm) |
| `screenPhysicalH` | - | int | Physical screen height (mm) |
| `drivePosition` | `CarDrivePosition` | int | 0=LHD, 1=RHD |

**Audio:**

| JSON Field | riddle.conf | Type | Description |
|------------|-------------|------|-------------|
| `mediaSound` | `MediaQuality` | int | 0=44.1kHz, 1=48kHz |
| `mediaVol` | - | float | Media volume (0.0-1.0) |
| `navVol` | - | float | Navigation volume |
| `callVol` | - | float | Call volume |
| `ringVol` | - | float | Ring volume |
| `speechVol` | - | float | Speech/Siri volume |
| `otherVol` | - | float | Other audio volume |
| `echoDelay` | `EchoLatency` | int | Echo cancellation (ms) |
| `callQuality` | `CallQuality` | int | Voice call quality |

**Network / Connectivity:**

| JSON Field | riddle.conf | Type | Description |
|------------|-------------|------|-------------|
| `wifiName` | `CustomWifiName` | string | WiFi SSID ⚠️ **CMD INJECTION** |
| `wifiFormat` | - | int | WiFi format |
| `WiFiChannel` | `WiFiChannel` | int | WiFi channel (1-11, 36-165) |
| `btName` | `CustomBluetoothName` | string | Bluetooth name ⚠️ **CMD INJECTION** |
| `btFormat` | - | int | Bluetooth format |
| `boxName` | `CustomBoxName` | string | Device display name |
| `iAP2TransMode` | `iAP2TransMode` | int | iAP2 transport mode |

**Branding / OEM:**

| JSON Field | riddle.conf | Type | Description |
|------------|-------------|------|-------------|
| `oemName` | - | string | OEM name |
| `productType` | - | string | Product type (e.g., "A15W") |
| `lightType` | - | int | LED indicator type |

**Navigation Video (sent via `naviScreenInfo` — requires one-time `AdvancedFeatures=1` unlock; see "Persistent Unlock"):**

| JSON Field | Type | Description |
|------------|------|-------------|
| `naviScreenInfo` | object | Nested object for nav video |
| `naviScreenInfo.width` | int | Nav screen width (default: 480) |
| `naviScreenInfo.height` | int | Nav screen height (default: 272) |
| `naviScreenInfo.fps` | int | Nav screen FPS (default: 30) |

**Android Auto Mode:**

| JSON Field | Type | Description |
|------------|------|-------------|
| `androidWorkMode` | int | Phone link daemon mode: 0=Idle, 1=AndroidAuto, 2=CarLife, 3=AndroidMirror, 4=HiCar, 5=ICCOA |

### Adapter to Host Fields **[Adapter→Host]**

| JSON Field | Type | Description |
|------------|------|-------------|
| `uuid` | string | Device UUID |
| `MFD` | string | Manufacturing date |
| `boxType` | string | Model code (e.g., "YA") — see Box Code Taxonomy below |
| `productType` | string | Product ID (e.g., "A15W") |
| `OemName` | string | OEM name |
| `hwVersion` | string | Hardware version |
| `HiCar` | int | HiCar support flag (0/1) |
| `WiFiChannel` | int | Current WiFi channel |
| `CusCode` | string | Customer code |
| `DevList` | array | Paired device list |
| `ChannelList` | string | Available WiFi channels |

### Phone Info Fields (Adapter → Host)

| JSON Field | Type | Description |
|------------|------|-------------|
| `MDLinkType` | string | "CarPlay", "AndroidAuto", "HiCar" |
| `MDModel` | string | Phone model |
| `MDOSVersion` | string | OS version (empty for Android Auto) |
| `MDLinkVersion` | string | Protocol version |
| `btMacAddr` | string | Bluetooth MAC address |
| `btName` | string | Phone Bluetooth name |
| `cpuTemp` | int | Adapter CPU temperature |

---

## Box Code Taxonomy

The box code is a single-letter customer identifier sent in the Open message (`iBoxversion` field at offset 20) and stored at `/etc/box_version` on the adapter. It appears as the last character of the 18-byte SoftwareVersion string (0xCC).

| Letter | Numeric | Customer / Product |
|--------|---------|-------------------|
| I | 0 | Invalid (unauthorized) |
| T | 1 | TengShi (ShiTeng) |
| Y | 2 | YunLian (AutoKit/LoadKit/FlyPlay) |
| B | 3 | Basic/AutoCast |
| H | 4 | HiCar dedicated (Riddle/SmartLink) |
| D | 13 | DongRong (DrongPlay) |
| K | 14 | SinKet |
| J | 15 | JoyeCar |
| S | 16 | Hello_Link |
| G | 17 | AutoPlay_H |
| L | 18 | Geely/Lynk&Co |
| M | 19 | (unknown customer) |
| P | 0xFF | Public (default/generic) |

Values 5–12 are unassigned. When the adapter echoes `iBoxversion=0` in the Open response, the host marks it as unauthorized.

> **Source:** PhoneMirrorBox `Config.java:239-270` (BOX_CODE_MAP), tool flavor `FirstPageExImpl.java:137-148`, `build.gradle` BOX_CODE assignments. Firmware references `/etc/box_version`, `/etc/box_version2`, `/etc/box_version3` (confirmed in binary strings).

---

## SoftwareVersion String Format (0xCC)

The 18-byte version string format is: `YYYY.MM.DD.HHMM[V][P][C]`

| Position | Meaning | Known Values |
|----------|---------|-------------|
| 0–14 | Date-time from `/etc/software_version` | e.g., `2025.10.15.1127` |
| 15 (V) | Firmware variant | C=CarPlay-capable, O=Non-CarPlay (AA-only OEM) |
| 16 (P) | Protocol support | A=Android Auto, H=HiCar |
| 17 (C) | Customer/box code | See Box Code Taxonomy above |

Example: `2025.02.25.1521CAY` → 2025-02-25 15:21 build, CarPlay+AA firmware, YunLian customer.

**Important:** Some host apps (e.g., PhoneMirrorBox) append `W` when `CarPlay_SupportWifi` is received from the adapter. This `W` is **host-appended, not part of the firmware's 18-byte string**. Firmware version validation checks like `endsWith("CHPW")` test the host-extended string.

> **Source:** PhoneMirrorBox `BoxProtocol.java:2172-2187`, `BoxInfo.java:124-129`, `Config.java:196` (isAuthorizedBox). Firmware `/etc/software_version` contains only the 15-char date-time; suffix chars come from adapter state.

---

## AndroidWorkMode Deep Dive — 6-Mode Phone Link Daemon Selector

### Overview

`AndroidWorkMode` is a **multi-mode phone link daemon selector**, not a simple on/off toggle. Writing a value 0-5 to `/etc/android_work_mode` triggers `OnAndroidWorkModeChanged` in ARMadb-driver, which stops the current mode's daemon and starts the new one via `/script/phone_link_deamon.sh <ModeName> start/stop &`.

### Mode Table

| Value | Mode | Daemon Process | Start Method |
|-------|------|---------------|-------------|
| 0 | Idle / Disconnect | (kills running mode) | Direct |
| 1 | AndroidAuto | `ARMAndroidAuto` + `hfpd` | `phone_link_deamon.sh AndroidAuto start &` |
| 2 | CarLife | `CarLife` | `phone_link_deamon.sh CarLife start &` |
| 3 | AndroidMirror | `ARMandroid_Mirror` | Direct fork/exec (not via script) |
| 4 | HiCar | `ARMHiCar` + 9 shared libs | `phone_link_deamon.sh HiCar start &` |
| 5 | ICCOA | `iccoa` | `phone_link_deamon.sh ICCOA start &` |

### Behavior

| Event | AndroidWorkMode Value | Effect |
|-------|----------------------|--------|
| Host sends `android_work_mode=1` | `0 → 1` | `Start Link Deamon: AndroidAuto` |
| Host sends `android_work_mode=4` | `1 → 4` | Stops AA, `Start Link Deamon: HiCar` |
| Phone disconnects | `N → 0` (firmware auto-reset) | Running daemon stops |
| Host reconnects | Must re-send mode value | Daemon restarts |

### How to Set via Host App

**File Path:** `/etc/android_work_mode` (4-byte binary int, persistent)
**Protocol:** `SendFile` (type 0x99) with 4-byte payload

```typescript
// CarLink sends mode 1 (AndroidAuto) — the only mode needed for this app
new SendBoolean(true, '/etc/android_work_mode')  // maps to integer 1
```

**Firmware Log Evidence:**
```
UPLOAD FILE: /etc/android_work_mode, 4 byte
OnAndroidWorkModeChanged: 0 → 1
Start Link Deamon: AndroidAuto
```

### Impact on Android Auto Pairing

| AndroidWorkMode | Android Auto Daemon | Fresh Pairing |
|-----------------|---------------------|---------------|
| 0 (default) | NOT started | Fails |
| 1 | Started | Works |

**Key Finding:** Open-source projects that don't send `android_work_mode=1` during initialization cannot perform Android Auto pairing, even if Bluetooth pairing succeeds.

### riddle.conf vs Runtime State

- `AndroidWorkMode` in riddle.conf: May show `1` if previously set
- **Runtime state:** Always resets to `0` on phone disconnect
- Host app must send on **every connection**, not just first time

**CORRECTION (2026-02-28):** Previous version described AndroidWorkMode as a binary 0/1 toggle for Android Auto only. Binary analysis of ARMadb-driver confirms it is a 6-mode selector — format strings `/script/phone_link_deamon.sh %s start &` with mode names `ICCOA`, `CarLife`, `AndroidMirror` as standalone `.rodata` strings, plus `AndroidAuto` and `HiCar` resolved via DAT pointer table. Modes 2-5 are for other Carlinkit products sharing the same firmware codebase.

---

## SendHeartBeat — Deep Analysis

For complete heartbeat protocol analysis including binary evidence, timing requirements, D-Bus signal flow, and implementation patterns, see [`heartbeat_analysis.md`](../01_Firmware_Architecture/heartbeat_analysis.md).

Key facts: 2-second interval, 15,000ms firmware timeout (~10s practical budget), 0xAA message type (16-byte header-only), exempt from encryption.

**Diagnostic commands [Firmware]:**
```bash
riddleBoxCfg SendHeartBeat        # Read current value (0 or 1)
riddleBoxCfg SendHeartBeat 1      # Enable (recommended)
```

**Note:** Changes require reboot to take effect. The running ARMadb-driver process caches the value at startup.

---

## Configuration Precedence

| Priority | Source | Persistence | Notes |
|----------|--------|-------------|-------|
| 1 (Highest) | Host App BoxSettings | Until next init | Sent via USB protocol at connection |
| 2 | riddle.conf | Persistent | Written by riddleBoxCfg or Web API |
| 3 | Web API (/server.cgi) | Persistent | Manual configuration changes |
| 4 (Lowest) | riddle_default.conf | Factory | Restored on factory reset |

**Note:** When the host app sends BoxSettings during initialization, it can override values in riddle.conf. This is why the same adapter may behave differently with different host applications.

---

## D-Bus Interface

The firmware uses D-Bus (`org.riddle`) as its inter-process communication mechanism between ARMadb-driver, bluetoothDaemon, and other adapter processes.

See [`05_Reference/firmware_internals.md`](../05_Reference/firmware_internals.md) for the complete D-Bus signal reference table (`org.riddle` interface, HUDComand_* signals, kRiddleAudioSignal_* signals, etc.).

---

## Scripts Called by Firmware

| Script | Trigger | Purpose |
|--------|---------|---------|
| `/script/start_bluetooth_wifi.sh` | Boot, reconnect | Initialize BT/WiFi |
| `/script/close_bluetooth_wifi.sh` | Disconnect | Stop BT/WiFi services |
| `/script/phone_link_deamon.sh` | Connection events | Manage phone link |
| `/script/start_accessory.sh` | USB connect | Start USB accessory mode |
| `/script/update_box_ota.sh` | OTA update | Apply firmware update |
| `/script/open_log.sh` | Debug mode | Enable logging |

---

## LED Configuration Parameters (8)

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| rgbStartUp | hex (24-bit) | 0x800000 | Startup LED color (red) |
| rgbWifiConnected | hex (24-bit) | 0x008000 | WiFi connected color (green) |
| rgbBtConnecting | hex (24-bit) | 0x800000 | Bluetooth connecting color (red) |
| rgbLinkSuccess | hex (24-bit) | 0x008000 | Link success color (green) |
| rgbUpgrading | hex (24-bit) | 0x800000/0x008000 | Firmware upgrade (alternating) |
| rgbUpdateSuccess | hex (24-bit) | 0x800080 | Update success color (purple) |
| rgbFault | hex (24-bit) | 0x000080 | Fault/error color (blue) |
| ledTimingMode | enum (0-2) | 1 | 0=Static, 1=Blink, 2=Gradient |

---

## Configuration Flow

```
1. Boot: ARMadb-driver starts
         |
         v
2. Read /etc/riddle.conf -> global variables (cached)
         |
         v
3. Initialize hardware (USB, WiFi, BT) using cached values
         |
         v
4. Runtime: Most settings already applied
   |
   +-> Changing config via riddleBoxCfg writes to file
       but does NOT update running process
         |
         v
5. Reboot/restart required to apply new values
```

---

---

## riddleBoxCfg CLI Reference

### Access Notes

**Important**: `riddleBoxCfg` is located at `/usr/sbin/riddleBoxCfg` which may not be in PATH for all shells.

| Access Method | PATH includes /usr/sbin | Usage |
|---------------|------------------------|-------|
| Telnet (port 23) | Yes | `riddleBoxCfg --info` |
| SSH (dropbear) | Often No | `/usr/sbin/riddleBoxCfg --info` |

**Fix for SSH**: Add to profile: `echo 'export PATH=$PATH:/usr/sbin' >> /etc/profile`

### Command Reference

```
riddleBoxCfg --help                                    : print usage
riddleBoxCfg --info                                    : get all config parameters with defaults/ranges
riddleBoxCfg --uuid                                    : print box uuid
riddleBoxCfg --readOld                                 : update riddle.conf include oldCfgFileData
riddleBoxCfg --removeOld                               : remove old cfgFileData
riddleBoxCfg --restoreOld                              : restore old cfgFileData (recovery)
riddleBoxCfg --upConfig                                : update riddle.conf on the box
riddleBoxCfg --specialConfig                           : sync riddle_special.conf to riddle_default.conf
riddleBoxCfg -s key value  [--default]                 : set a key's value
riddleBoxCfg -s list listKeyID listKey listvalue       : set a list key's value
riddleBoxCfg -g key  [--default]                       : get a value from key
riddleBoxCfg -g list listKeyID listKey                 : get a list value from key
riddleBoxCfg -d list listkey listvalue                 : delete a list key's value
```

---

## Configuration Setting Mechanisms

Each riddle.conf parameter can be set through different mechanisms. Understanding these helps determine how to modify settings:

### Setting Mechanisms

| Mechanism | Description | When Used |
|-----------|-------------|-----------|
| **Host App (0x19)** | BoxSettings JSON sent via USB message type 0x19 | Host app (pi-carplay, AutoKit) sends config during session |
| **riddleBoxCfg CLI** | Command-line tool on adapter: `riddleBoxCfg -s Key Value` | SSH access, scripts, OEM provisioning |
| **Auto (Connect)** | Firmware sets automatically when device connects | DevList, LastConnectedDevice, LastPhoneSpsPps |
| **Auto (Pair)** | Firmware sets automatically during Bluetooth pairing | DevList entries, PHONE_INFO |
| **Auto (Runtime)** | Firmware manages internally during operation | LastBoxUIType, CarDate |
| **OEM Default** | Set in riddle_default.conf during manufacturing | Brand*, USB*, oemName |

### BoxSettings JSON → riddle.conf Mapping

When host app sends BoxSettings (0x19), these JSON fields map to config keys:

| BoxSettings JSON | riddle.conf Key | Notes |
|------------------|-----------------|-------|
| `mediaDelay` | MediaLatency | Audio buffer (ms) |
| `autoConn` | NeedAutoConnect | Auto-reconnect toggle |
| `autoPlay` | AutoPlauMusic | ⚠️ **MAPPING MISSING** — `autoPlay` string absent from ARMadb-driver; config key exists but not reachable via BoxSettings JSON. Set via web UI only. |
| `autoDisplay` | autoDisplay | Auto display mode |
| `bgMode` | BackgroundMode | Background mode |
| `startDelay` | BoxConfig_DelayStart | Startup delay |
| `lang` | BoxConfig_UI_Lang | UI language |
| `androidAutoSizeW` | AndroidAutoWidth | AA resolution |
| `androidAutoSizeH` | AndroidAutoHeight | AA resolution |
| `screenPhysicalW` | ScreenPhysicalW | Physical size (mm) |
| `screenPhysicalH` | ScreenPhysicalH | Physical size (mm) |
| `drivePosition` | CarDrivePosition | 0=LHD, 1=RHD |
| `mediaSound` | MediaQuality | 0=44.1kHz, 1=48kHz |
| `echoDelay` | EchoLatency | Echo cancellation |
| `callQuality` | CallQuality | **BUGGY** - translation fails |
| `wifiName` | CustomWifiName | WiFi SSID |
| `WiFiChannel` | WiFiChannel | WiFi channel |
| `btName` | CustomBluetoothName | Bluetooth name |
| `boxName` | CustomBoxName | Display name |
| `iAP2TransMode` | iAP2TransMode | Transport mode |
| `androidWorkMode` | AndroidWorkMode | AA daemon mode |

### Auto-Set Parameters (Firmware Managed)

These are set automatically by firmware - do not set manually:

| Key | Set When | Description |
|-----|----------|-------------|
| DevList | Device pairs via Bluetooth | Adds device entry |
| DeletedDevList | User removes device | Prevents auto-reconnect |
| LastConnectedDevice | Session starts | MAC of current device |
| LastPhoneSpsPps | Video stream starts | Cached H.264 SPS/PPS |
| LastBoxUIType | UI mode changes | CarPlay/AA/HiCar state |
| CarDate | Unknown | Date code |

### Internal Parameters (Not User-Settable via Host App)

These require `riddleBoxCfg` CLI or are firmware-only:

| Key | How to Set | Notes |
|-----|------------|-------|
| DashboardInfo | `riddleBoxCfg -s DashboardInfo 7` | iAP2 engine bitmask |
| GNSSCapability | `riddleBoxCfg -s GNSSCapability 1` | Required for LocationEngine |
| AdvancedFeatures | `riddleBoxCfg -s AdvancedFeatures 1` | Boolean 0-1 (NOT a bitmask). Enables naviScreen (extra video stream). Only set when host handles 0x2C |
| SpsPpsMode | `riddleBoxCfg -s SpsPpsMode 1` | H.264 handling mode |
| SendHeartBeat | `riddleBoxCfg -s SendHeartBeat 1` | Heartbeat toggle |
| LogMode | `riddleBoxCfg -s LogMode 1` | Logging toggle |

---

## Authoritative Parameter List (from --info)

Output from `/usr/sbin/riddleBoxCfg --info` on CPC200-CCPA firmware. This is the **definitive source** for all configuration parameters.

**Source Column Legend:**
- **Web UI** = Set via Host App BoxSettings (0x19) JSON
- **Protocol Init** = Set via Host App during session initialization
- **Internal** = Firmware-managed or riddleBoxCfg CLI only

### Integer Parameters

| Key | Default | Min | Max | Source |
|-----|---------|-----|-----|--------|
| iAP2TransMode | 0 | 0 | 1 | Web UI |
| MediaQuality | 1 | 0 | 1 | Web UI |
| MediaLatency | 1000 | 300 | 2000 | Web UI |
| UdiskMode | 1 | 0 | 1 | Web UI |
| LogMode | 1 | 0 | 1 | Internal |
| BoxConfig_UI_Lang | 0 | 0 | 65535 | Web UI |
| BoxConfig_DelayStart | 0 | 0 | 120 | Web UI |
| BoxConfig_preferSPSPPSType | 0 | 0 | 1 | Protocol Init | **DEAD KEY** — zero runtime xrefs |
| NotCarPlayH264DecreaseMode | 0 | 0 | 2 | Internal | **DEAD KEY** — zero runtime xrefs |
| NeedKeyFrame | 0 | 0 | 1 | Protocol Init |
| EchoLatency | 320 | 20 | 2000 | Web UI |
| DisplaySize | 0 | 0 | 3 | Web UI | **PASS-THROUGH** — written by host, never read via GetBoxConfig |
| UseBTPhone | 0 | 0 | 1 | Internal |
| MicGainSwitch | 0 | 0 | 1 | Web UI | Controls WebRTC AGC (Automatic Gain Control) |
| CustomFrameRate | 0 | 0 | 60 | Protocol Init |
| NeedAutoConnect | 1 | 0 | 1 | Web UI |
| BackgroundMode | 0 | 0 | 1 | Web UI |
| HudGPSSwitch | 0 | 0 | 1 | Web UI | **Note:** `--info` may show 1 if previously set; factory default is 0 |
| CarDate | 0 | 0 | 65535 | Internal |
| WiFiChannel | 36 | 1 | 165 | Web UI |
| AutoPlauMusic | 0 | 0 | 1 | Web UI |
| MouseMode | 1 | 0 | 1 | Web UI | **PASS-THROUGH** — written by host, never read via GetBoxConfig |
| CustomCarLogo | 0 | 0 | 1 | Web UI |
| VideoBitRate | 0 | 0 | 20 | Protocol Init |
| VideoResolutionHeight | 0 | 0 | 4096 | Protocol Init |
| VideoResolutionWidth | 0 | 0 | 4096 | Protocol Init |
| UDiskPassThrough | 1 | 0 | 1 | Web UI | **DEAD KEY** — zero runtime xrefs |
| AndroidWorkMode | 1 | 1 | 5 | Protocol Init |
| CarDrivePosition | 0 | 0 | 1 | Web UI |
| AndroidAutoWidth | 0 | 0 | 4096 | Protocol Init | **PASS-THROUGH** — written by host, never read via GetBoxConfig |
| AndroidAutoHeight | 0 | 0 | 4096 | Protocol Init | **PASS-THROUGH** — written by host, never read via GetBoxConfig |
| ScreenDPI | 0 | 0 | 480 | Protocol Init |
| KnobMode | 0 | 0 | 1 | Web UI | **PASS-THROUGH** — written by host, never read via GetBoxConfig |
| NaviAudio | 0 | 0 | 2 | Web UI |
| ScreenPhysicalW | 0 | 0 | 1000 | Protocol Init |
| ScreenPhysicalH | 0 | 0 | 1000 | Protocol Init |
| CallQuality | 1 | 0 | 2 | Web UI | **BUGGY** - see below |
| VoiceQuality | 1 | 0 | 2 | Internal | **BUGGY** - see below |
| AutoUpdate | 1 | 0 | 1 | Web UI |
| LastBoxUIType | 1 | 0 | 2 | Internal | **DEAD KEY** — zero runtime xrefs |
| BoxSupportArea | 0 | 0 | 1 | Internal | Region flag: 0=Global, 1=China. Affects iAP2 identification (zh hint). See deep analysis |
| HNPInterval | 10 | 0 | 1000 | Internal | **DEAD KEY** — zero runtime xrefs |
| lightType | 3 | 1 | 3 | Web UI | **DEAD KEY** — zero runtime xrefs |
| MicType | 0 | 0 | 2 | Web UI |
| RepeatKeyframe | 0 | 0 | 1 | Protocol Init |
| BtAudio | 0 | 0 | 1 | Web UI |
| MicMode | 0 | 0 | 4 | Internal |
| SpsPpsMode | 0 | 0 | 3 | Protocol Init |
| MediaPacketLen | 200 | 200 | 20000 | Internal |
| TtsPacketLen | 200 | 200 | 40000 | Internal |
| VrPacketLen | 200 | 200 | 40000 | Internal |
| TtsVolumGain | 0 | 0 | 1 | Internal |
| VrVolumGain | 0 | 0 | 1 | Internal |
| CarLinkType | 30 | 1 | 30 | Internal |
| SendHeartBeat | 1 | 0 | 1 | Internal |
| SendEmptyFrame | 1 | 0 | 1 | Internal |
| autoDisplay | 1 | 0 | 2 | Web UI | **PASS-THROUGH** — written by host, never read via GetBoxConfig |
| USBConnectedMode | 0 | 0 | 2 | Web UI | USB gadget functions: 0=mtp+adb, 1=mtp, 2=adb. See start_mtp.sh |
| USBTransMode | 0 | 0 | 1 | Web UI | USB ZLP for AOA: 0=off, 1=on. Fixes bulk stalls. See start_aoa.sh |
| ReturnMode | 0 | 0 | 1 | Web UI |
| LogoType | 0 | 0 | 3 | Web UI |
| BackRecording | 0 | 0 | 1 | Internal |
| FastConnect | 0 | 0 | 1 | Protocol Init |
| WiredConnect | 1 | 0 | 1 | Internal |
| ImprovedFluency | 0 | 0 | 1 | Web UI | **DEAD KEY** — not read by any binary (see analysis notes above) |
| NaviVolume | 0 | 0 | 100 | Web UI |
| OriginalResolution | 0 | 0 | 1 | Protocol Init |
| AutoConnectInterval | 0 | 0 | 60 | Internal |
| AutoResetUSB | 1 | 0 | 1 | Internal |
| HiCarConnectMode | 0 | 0 | 1 | Internal |
| GNSSCapability | 0 | 0 | 65535 | Internal |
| DashboardInfo | 1 | 0 | 7 | Internal |
| AudioMultiBusMode | 1 | 0 | 1 | Internal | **DEAD KEY** — zero runtime xrefs |
| DayNightMode | 2 | 0 | 2 | Web UI | 0=Auto (host cmds 16/17), 1=Force Day, 2=Force Night |
| InternetHotspots | 0 | 0 | 1 | Internal | **DEAD KEY** — zero runtime xrefs |
| UseUartBLE | 0 | 0 | 1 | Internal | 0=USB HCI BLE, 1=UART HCI BLE (hardware-dependent) |
| WiFiP2PMode | 0 | 0 | 1 | Internal | 0=SoftAP (hostapd), 1=WiFi Direct P2P GO (wpa_cli, SSID=DIRECT-*). Requires WiFi restart. See deep analysis |
| DuckPosition | 0 | 0 | 2 | Internal | 0=Left (LHD), 1=Right (RHD), 2=Bottom. Maps to `viewAreaStatusBarEdge` |
| AdvancedFeatures | 0 | 0 | 1 | Internal | Boolean. Enables naviScreen (0x2C stream). See detailed section above |

### String Parameters

| Key | Default | Min Len | Max Len | Source |
|-----|---------|---------|---------|--------|
| CarBrand | "" | 0 | 31 | Internal |
| CarModel | "" | 0 | 31 | Internal |
| BluetoothName | "" | 0 | 15 | Web UI |
| WifiName | "" | 0 | 15 | Web UI |
| CustomBluetoothName | "" | 0 | 15 | Web UI |
| CustomWifiName | "" | 0 | 15 | Web UI |
| HU_BT_PIN_CODE | "" | 0 | 6 | Internal - BT pairing PIN (see note) |
| LastPhoneSpsPps | "" | 0 | 511 | Internal | **DEAD KEY** — zero runtime xrefs (SpsPpsMode 0 does NOT read this) |
| CustomId | "" | 0 | 31 | Internal | **PASS-THROUGH** — written by host, never read via GetBoxConfig |
| LastConnectedDevice | "" | 0 | 17 | Internal |
| IgnoreUpdateVersion | "" | 0 | 15 | Internal |
| CustomBoxName | "" | 0 | 15 | Web UI |
| WifiPassword | "12345678" | 0 | 15 | Web UI |
| BrandName | "" | 0 | 15 | Internal |
| BrandBluetoothName | "" | 0 | 15 | Internal |
| BrandWifiName | "" | 0 | 15 | Internal |
| BrandServiceURL | "" | 0 | 31 | Internal | **DEAD KEY** — zero runtime xrefs |
| BoxIp | "" | 0 | 15 | Web UI | **DEAD KEY** — zero runtime xrefs |
| USBProduct | "" | 0 | 63 | Web UI |
| USBManufacturer | "" | 0 | 63 | Web UI |
| USBPID | "" | 0 | 4 | Web UI |
| USBVID | "" | 0 | 4 | Web UI |
| USBSerial | "" | 0 | 63 | Internal |
| oemName | "" | 0 | 63 | Internal | **DEAD KEY** — zero runtime xrefs |
| BrandWifiChannel | "" | 0 | 31 | Internal | WiFi channel branding override (read by start_bluetooth_wifi.sh) |

### Array/Object Parameters

| Key | Type | Description |
|-----|------|-------------|
| DevList | Array | Paired devices list |
| DeletedDevList | Array | Removed/unpaired devices list |
| LangList | Array | Supported UI languages (PASS-THROUGH — server.cgi only, populates web UI dropdown) |

#### DevList Structure

Stores all paired Bluetooth devices. Each entry is an object with the following fields:

```json
{
  "DevList": [
    {
      "id": "64:31:35:8C:29:69",
      "type": "CarPlay",
      "name": "Luis",
      "index": "1",
      "time": "2026-02-03 17:51:25",
      "rfcomm": "1"
    }
  ]
}
```

| Field | Type | Description |
|-------|------|-------------|
| `id` | string | Bluetooth MAC address (XX:XX:XX:XX:XX:XX format) |
| `type` | string | Connection type: `"CarPlay"`, `"AndroidAuto"`, `"HiCar"` |
| `name` | string | Device friendly name (from phone) |
| `index` | string | Device index in list (starts at "1") |
| `time` | string | Last connection timestamp (YYYY-MM-DD HH:MM:SS) |
| `rfcomm` | string | RFCOMM channel number |

**Behavior:**
- Populated automatically when devices pair via Bluetooth
- Used for auto-reconnect feature (`NeedAutoConnect=1`)
- `LastConnectedDevice` references an `id` from this list
- Maximum entries: ~10 devices (older entries may be pruned)
- Survives factory reset if Bluetooth pairing data persists

#### DeletedDevList Structure

Stores devices that were explicitly unpaired/removed by user:

```json
{
  "DeletedDevList": [
    {
      "id": "AA:BB:CC:DD:EE:FF",
      "type": "CarPlay"
    }
  ]
}
```

| Field | Type | Description |
|-------|------|-------------|
| `id` | string | Bluetooth MAC address of removed device |
| `type` | string | Connection type that was removed |

**Behavior:**
- Prevents auto-reconnect to intentionally removed devices
- Device must be re-paired manually to reconnect
- Cleared on factory reset

---

## Known Configuration Bugs

### CallQuality → VoiceQuality Translation Bug (Firmware 2025.10.XX)

**Status:** CONFIRMED (Jan 2026)
**Severity:** Medium - Setting has no effect

The `CallQuality` Web UI setting (0=Normal, 1=Clear, 2=HD) fails to translate to the internal `VoiceQuality` parameter.

**Error observed in TTY logs:**
```
[D] CMD_BOX_INFO: {...,"callQuality":1,...}
[E] apk callQuality value transf box value error , please check!
```

**Technical Details:**
- Host app sends `callQuality` in BoxSettings JSON
- Firmware's `ConfigFileUtils` attempts to map it to `VoiceQuality`
- Translation fails with error, VoiceQuality remains unchanged
- Error occurs regardless of CallQuality value (0, 1, or 2)

**Impact:**
- CallQuality setting via Web UI has no effect
- Voice/telephony audio sample rate is NOT controllable via this setting
- CarPlay independently negotiates sample rate (always 16kHz on modern iPhones)

**Testing Performed:**
- Cycled CallQuality 0→1→2 via Web UI
- Captured USB audio packets during phone calls
- All CarPlay captures showed decode_type=5 (16kHz), never decode_type=3 (8kHz). Note: Android Auto phone calls DO use decode_type=3 (8kHz via HFP/SCO) — see `03_Audio_Processing/microphone_processing.md` § AA Phone Call Microphone.
- TTY logs confirmed error on every CallQuality change

**Workaround:** None. Sample rate is determined by CarPlay's `audioFormat` during stream setup.

---

## Parameter Source Classification

Parameters come from different sources and have different behaviors:

### Web UI Parameters
Set via web interface at `http://192.168.43.1` (or `http://192.168.50.2`). User-facing settings that persist in riddle.conf.

**Examples**: WiFiChannel, MediaLatency, MediaQuality, MicType, MouseMode, BackgroundMode, CustomWifiName

### Protocol Init Parameters
Set by host applications (carlink_native, pi-carplay, AutoKit) during USB protocol initialization. These are sent as BoxSettings and may override riddle.conf values.

**Examples**: SpsPpsMode, RepeatKeyframe, VideoBitRate, VideoResolutionWidth/Height, FastConnect, NeedKeyFrame, AndroidWorkMode, CustomFrameRate, ScreenDPI

**Note**: These parameters are dynamically set each time a host app connects. The adapter may behave differently with different host applications.

### Internal Parameters
System-level settings not exposed in standard UI. Used for protocol internals, debugging, or OEM configuration.

**Examples**: SendHeartBeat, SendEmptyFrame, MediaPacketLen, TtsPacketLen, HNPInterval, LogMode, CarLinkType, LastPhoneSpsPps, AutoResetUSB

---

## Web API Configuration

### Endpoint
```
POST /server.cgi
FormData: cmd=set&item=parameter&val=value&ts=timestamp&sign=md5_hash
Salt: HweL*@M@JEYUnvPw9G36MVB9X6u@2qxK
```

### Method Comparison

| Method | Endpoint/Binary | Format | Latency | Restart Required |
|--------|----------------|--------|---------|------------------|
| USB | CPC200-CCPA protocol | Binary packets | <100ms | Video/USB changes only |
| Web API | /server.cgi | POST FormData+MD5 | Real-time | Resolution/USB identity |
| CLI | /usr/sbin/riddleBoxCfg | -s param value | Immediate | System settings |

---

## Validation Rules

| Type | Rule | Error Message |
|------|------|---------------|
| Range 0-60 | micGain | "Please Enter A Number From 0-60" |
| Range 300-2000 | mediaDelay | "Please Enter A Number From 300-2000" |
| Range 0-4096 | resolution | "Please Enter A Number From 0-4096" |
| Range 0-20 | bitRate | "Please Enter A Number From 0-20" |
| Range 0-480 | ScreenDPI | "Please Enter A Number From 0-480" |
| Text | alphanumeric | "No Special Symbols Are Allowed" |
| Text | no emoji | "Emoticons Cannot Be Used" |

---

## Model Detection & Parameter Filtering

### Product Identification

| File | Purpose |
|------|---------|
| `/etc/box_product_type` | Model identifier (A15W, A15H, etc.) |
| `/etc/box_version` | Custom version affecting module loading |
| `/script/getFuncModule.sh` | Module loading logic based on product type |
| `/script/init_audio_codec.sh` | Audio codec detection (WM8960/AC6966) |

### Module Loading by Model

```
A15W: CarPlay,AndroidAuto,AndroidMirror,iOSMirror,HiCar
A15H: CarPlay,HiCar (limited subset)
```

### Model-Specific Parameter Visibility

**IMPORTANT**: Parameters are NOT "hidden" by firmware - they are **contextually filtered** based on hardware capabilities.

- **Backend Reality**: All 106 parameters (79 int + 24 string + 3 array) are accessible via API/CLI regardless of model
- **Frontend Filtering**: Web interface conditionally displays parameters based on hardware capabilities

**A15W Contextual Limitations**:

| Parameter | Status | Reason |
|-----------|--------|--------|
| micGain, MicMode, micType | Limited | Hardware-dependent microphone |
| KnobMode | Disabled | No physical knob hardware |
| btCall | Limited | Bluetooth calling hardware constraints |
| audioCodec | Hardware-detected | WM8960(0) or AC6966(1) via I2C |

---

## Configuration Recovery

If configuration changes cause issues (e.g., CarPlay stops working):

```bash
# Restore previous configuration
/usr/sbin/riddleBoxCfg --restoreOld

# Or restore factory defaults
cp /etc/riddle_default.conf /etc/riddle.conf
/usr/sbin/riddleBoxCfg --upConfig
```

The `--restoreOld` command restores the previous configuration state, useful when experimental changes break functionality.

---

## Host App vs Direct Access Configuration (Binary Verified Feb 2026)

This section documents which configuration keys can be set via USB protocol messages from a host application versus those requiring direct adapter access (terminal/SSH, web interface, or riddleCfg CLI).

**Analysis Source:** Binary analysis of `riddleBoxCfg_unpacked` and `ARMadb-driver_2025.10_unpacked`

### Summary

| Access Method | Key Count | Percentage |
|---------------|-----------|------------|
| Host App (USB Protocol) | ~19 keys | 18% |
| Direct Access Only | ~68 keys | 64% |
| Read-Only | ~19 keys | 18% |

---

### Host App Configurable Keys (USB Protocol)

#### Via Open Message (0x01) - Session Parameters

| Key | Protocol Field | Effect |
|-----|----------------|--------|
| VideoResolutionWidth | width (bytes 0-3) | Video encoder width |
| VideoResolutionHeight | height (bytes 4-7) | Video encoder height |
| CustomFrameRate | fps (bytes 8-11) | Video framerate |
| ScreenDPI | Sent via SendFile (0x99) | UI scaling |
| DisplaySize | Derived from resolution | UI density |

#### Via BoxSettings Message (0x19) - JSON Configuration

| Key | JSON Field | Effect |
|-----|------------|--------|
| mediaDelay | "mediaDelay" | Audio delay compensation |
| MediaQuality | "mediaSound" | 0=44.1kHz, 1=48kHz |
| CallQuality | "callQuality" | 0=normal, 1=clear, 2=HD |
| WiFiChannel | "WiFiChannel" / "wifiChannel" | WiFi AP channel |
| CustomWifiName | "wifiName" | WiFi SSID |
| CustomBluetoothName | "btName" | Bluetooth name |
| CustomBoxName | "boxName" / "OemName" | Device name |
| NeedAutoConnect | "autoConn" | Auto-connect enable |
| AutoPlauMusic | ~~"autoPlay"~~ | ⚠️ **NOT mapped via BoxSettings** — `autoPlay` key absent from ARMadb-driver string table. Settable via web UI (boa CGI → riddle.conf) only. |
| AndroidAutoWidth | "androidAutoSizeW" | AA video width |
| AndroidAutoHeight | "androidAutoSizeH" | AA video height |

#### Via Command Message (0x08) - Runtime Control

| Key Affected | Command ID | Effect |
|--------------|------------|--------|
| MicType | 7 (UseCarMic), 8 (UseBoxMic), 15 (I2S), 21 (Phone) | Mic source |
| DayNightMode | 16 (StartNightMode), 17 (StopNightMode) | Theme |
| BtAudio | 22 (UseBluetoothAudio), 23 (UseBoxTransAudio) | Audio routing |
| WiFiChannel | 24 (Use24GWiFi), 25 (Use5GWiFi) | WiFi band (not specific channel) |
| GNSSCapability | 18 (StartGNSSReport), 19 (StopGNSSReport) | GPS forwarding |
| NeedKeyFrame | 12 (RequestKeyFrame) | Video IDR request |
| NeedAutoConnect | 1001 (SupportAutoConnect), 1002 (StartAutoConnect) | Auto-connect |

#### Via SendFile Message (0x99) - File Writes

| Key | File Path | Effect |
|-----|-----------|--------|
| ScreenDPI | /tmp/screen_dpi | DPI value |
| DayNightMode | /tmp/night_mode | Theme state (0/1) |
| CarDrivePosition | /tmp/hand_drive_mode | LHD/RHD |
| ChargeMode | /tmp/charge_mode | Charging behavior |
| CustomBoxName | /etc/box_name | Device name |
| AirPlay config | /etc/airplay.conf | AirPlay settings |
| AndroidWorkMode | /etc/android_work_mode | Phone link daemon mode (0-5: Idle/AA/CarLife/Mirror/HiCar/ICCOA) |
| CustomCarLogo | /etc/icon_*.png | Logo images |

---

### Direct Access Only Keys (Terminal/Web/riddleCfg)

#### USB Hardware Configuration

| Key | Why Direct Access Required |
|-----|---------------------------|
| USBVID | Changes USB device identity - requires gadget driver reconfigure |
| USBPID | Changes USB device identity - requires gadget driver reconfigure |
| USBProduct | USB descriptor - set at driver init |
| USBManufacturer | USB descriptor - set at driver init |
| USBSerial | USB descriptor - set at driver init |
| USBConnectedMode | USB gadget functions (mtp/adb selection) |
| USBTransMode | USB ZLP for AOA (Android Auto bulk transfers) |
| AutoResetUSB | USB error recovery behavior |

#### Video Processing (Internal Encoder Settings)

| Key | Why Direct Access Required |
|-----|---------------------------|
| VideoBitRate | Encoder parameter - not in protocol |
| BoxConfig_preferSPSPPSType | Codec config |
| SpsPpsMode | Codec config |
| LastPhoneSpsPps | Cached value |
| RepeatKeyframe | Encoder behavior |
| SendEmptyFrame | Encoder behavior |
| NotCarPlayH264DecreaseMode | Quality reduction policy |
| ImprovedFluency | Buffering behavior | **UNIMPLEMENTED** — no binary reads this value at runtime (fw 2025.10.15) |

#### Audio Processing (Internal Mixer Settings)

| Key | Why Direct Access Required |
|-----|---------------------------|
| MediaLatency | Internal buffer size |
| MediaPacketLen | Packet configuration |
| VoiceQuality | Audio processing |
| MicMode | Processing mode |
| MicGainSwitch | Gain control |
| NaviAudio | Channel routing |
| NaviVolume | Volume level |
| TtsPacketLen | TTS config |
| TtsVolumGain | TTS gain |
| VrPacketLen | Voice recognition config |
| VrVolumGain | Voice recognition gain |
| EchoLatency | Echo cancellation latency |
| DuckPosition | Dock position (viewAreaStatusBarEdge: left/right/bottom of CarPlay status bar) |
| AudioMultiBusMode | **DEAD KEY** — zero runtime xrefs in any binary |

#### WiFi/Bluetooth Hardware

| Key | Why Direct Access Required |
|-----|---------------------------|
| WifiPassword | Security - not sent over USB |
| WiFiP2PMode | Network mode change |
| InternetHotspots | Network routing |
| BrandWifiName | OEM config |
| BrandWifiChannel | OEM config |
| BrandBluetoothName | OEM config |
| UseBTPhone | Audio routing config |
| UseUartBLE | Hardware interface |
| HU_BT_PIN_CODE | BT pairing PIN config |

**HU_BT_PIN_CODE Note:**
This key stores the Bluetooth pairing PIN code used by the adapter. Related to two USB message types:
- **Type 0x0C (CMD_SET_BLUETOOTH_PIN_CODE):** Sets this configuration value
- **Type 0x2B (Connection_PINCODE):** Real-time PIN notification during active pairing
See `usb_protocol.md` → "Bluetooth PIN Message Types" for detailed flow.

#### System/Behavior (Feature Gating)

| Key | Why Direct Access Required |
|-----|---------------------------|
| **AdvancedFeatures** | **Boolean 0-1 (NOT bitmask). Enables naviScreen (extra video stream 0x2C). ViewArea set via HU_VIEWAREA_INFO, not this key** |
| AutoUpdate | Firmware update policy |
| IgnoreUpdateVersion | Update skip |
| BackgroundMode | Background operation |
| BackRecording | DVR recording |
| BoxConfig_DelayStart | Boot timing |
| BoxConfig_UI_Lang | UI language |
| FastConnect | Handshake behavior |
| SendHeartBeat | Heartbeat enable/disable |
| KnobMode | Input mapping |
| MouseMode | Input mode |
| ReturnMode | Button behavior |
| LogMode | Debug level |

#### OEM/Branding (Factory Settings)

| Key | Why Direct Access Required |
|-----|---------------------------|
| BrandName | OEM identity |
| BrandServiceURL | OEM service |
| CustomId | OEM tracking |
| LogoType | Logo selection |
| BoxSupportArea | Regional config |
| ResetBoxCarLogo | Factory reset trigger |
| ResetBoxConfig | Factory reset trigger |

---

### Read-Only Keys (Set by Adapter or Phone)

#### Set by Adapter (Hardware/Firmware Info)

| Key | Source |
|-----|--------|
| RiddlePlatform | Hardware detection |
| uuid | Generated/stored in /etc/uuid |
| hwVersion | /etc/box_version |
| software_version | /etc/software_version |

#### Set by Connected Phone (Session Info)

| Key | Source | When Set |
|-----|--------|----------|
| PHONE_INFO | Phone during iAP2/AA handshake | Session start |
| PHONE_DEVICE_TYPE | Phone identification | Session start |
| PHONE_OS_VERSION | Phone OS version | Session start |
| PHONE_LINK_TYPE | Active link type | Session start |
| BT_CONNECTING_ADDR | Bluetooth MAC | During BT connect |
| LastPhoneSpsPps | Phone's H.264 params | Video negotiation |
| conNum | Connection statistics | Session end |
| conRate | Connection success rate | Session end |
| conSpd | Connection speed | Session end |
| linkT | Link type string | Session end |
| MD_LINK_TIME | Link timestamp | Session end |

#### Set by Host App (Reported to Adapter)

| Key | Source | Protocol |
|-----|--------|----------|
| APP_INFO | Host app info blob | BoxSettings (0x19) |
| HU_TYPE_ID | Host identifier | Open (0x01) |
| HU_TYPE_OS | Host OS type | Open (0x01) |
| HU_OS_VERSION | Host OS version | BoxSettings (0x19) |
| HU_APP_VERSION | Host app version | BoxSettings (0x19) |
| HU_SCREEN_INFO | Host screen info | Open (0x01) |
| HU_LINK_TYPE | Host link type | Session info |

---

### Critical Note: Navigation Video Configuration

There are **two activation paths** for navigation video at runtime, but **both require a one-time `AdvancedFeatures=1` unlock** (originally documented during the Jan 2026 navigation-video discovery). See "Navigation Video Activation" / "Persistent Unlock" sections above for binary-verified details.

**Path A: naviScreenInfo in BoxSettings (Host App Controlled)**
- Host sends `naviScreenInfo: {width, height, fps}` in BoxSettings JSON
- Firmware at `0x16e64` detects `naviScreenInfo` and bypasses the **parser-side** `AdvancedFeatures` check (HU_SCREEN_INFO D-Bus path)
- ⚠️ Still requires the persistent unlock file `/etc/RiddleBoxData/HU_NAVISCREEN_INFO`, created the first time `AdvancedFeatures=1` is processed
- ✅ Works with `AdvancedFeatures=0` at runtime, **provided** the adapter has had `AdvancedFeatures=1` set at least once in its lifetime

**Path B: AdvancedFeatures=1 at runtime (Legacy, Direct Access Only)**
- Set via `riddleBoxCfg -s AdvancedFeatures 1` (SSH/terminal only)
- Uses HU_NAVISCREEN_INFO D-Bus path
- Uses `naviScreenWidth` / `naviScreenHeight` / `naviScreenFPS` from `riddle.conf`
- Required only if host does NOT send `naviScreenInfo` in BoxSettings

**AdvancedFeatures Effects (when set to 1):**
- Sets `g_bSupportNaviScreen=1` in AppleCarPlay
- Adapter advertises `"supportFeatures": "naviScreen"` in boxInfo
- Enables HU_NAVISCREEN_INFO D-Bus signal (fallback path)
- Enables HU_NEEDNAVI_STREAM requests
- Enables RequestNaviScreenFoucs/ReleaseNaviScreenFoucs commands
- Enables NaviVideoData (0x2C) message handling
- NaviScreen ViewArea/SafeArea (`HU_NAVISCREEN_VIEWAREA_INFO` / `HU_NAVISCREEN_SAFEAREA_INFO`) becomes active

**Main Screen ViewArea/SafeArea (independent of AdvancedFeatures — live verified 2026-02-18):**
- `g_bSupportViewarea` is set from `HU_VIEWAREA_INFO` file content, NOT from AdvancedFeatures
- Write `HU_VIEWAREA_INFO` (24B) + `HU_SAFEAREA_INFO` (20B) to `/etc/RiddleBoxData/`, reboot
- AppleCarPlay init reads `HU_VIEWAREA_INFO` → if width > 0 AND height > 0 → sets `g_bSupportViewarea=1`
- During stream setup, `_CopyDisplayDescriptions` reads both files and sends to iPhone
- `drawUIOutsideSafeArea` flag in `HU_SAFEAREA_INFO` offset 0x10: if 1, wallpaper/maps render full-screen, interactive UI inset to SafeArea
- This is **essential for non-rectangular screens** — allows declaring a SafeArea inset so CarPlay keeps interactive UI elements away from curved/clipped edges

**Recommended Flow (Host App - Path A):**
1. Host sends BoxSettings with `naviScreenInfo: {width, height, fps}`
2. Firmware branches to HU_SCREEN_INFO path (bypasses AdvancedFeatures)
3. iPhone and adapter negotiate navigation support
4. NaviVideoData (0x2C) flows from iPhone → Adapter → Host

**Legacy Flow (Path B - requires SSH access):**
1. Set `AdvancedFeatures=1` via SSH/terminal (one-time, direct access)
2. Reboot or restart ARMadb-driver
3. Host does NOT send naviScreenInfo
4. Firmware uses legacy naviScreen* settings from riddle.conf

---

### Host App Configuration Workflow

```
Session Initialization (Host → Adapter):

1. Send Open (0x01)
   - width, height, fps, format, iBoxVersion, phoneWorkMode

2. Send BoxSettings (0x19) JSON
   - mediaDelay, mediaSound, callQuality, WiFiChannel
   - wifiName, btName, boxName, autoConn, autoPlay
   - androidAutoSizeW, androidAutoSizeH, syncTime

3. Send Commands (0x08) as needed
   - MicType: 7/8/15/21
   - NightMode: 16/17
   - WiFiBand: 24/25
   - GNSS: 18/19

4. Send Files (0x99) as needed
   - /tmp/screen_dpi
   - /etc/airplay.conf
   - /etc/android_work_mode
   - /etc/icon_*.png

Runtime Configuration Changes:
- Theme change: Send Command 16 or 17
- Mic change: Send Command 7, 8, 15, or 21
- WiFi band: Send Command 24 or 25
- Sample rate/Call quality: Resend full BoxSettings (0x19)
```

---

## References

- Source: `pi-carplay-4.1.3/firmware_binaries/CONFIG_KEYS_REFERENCE.md`
- Source: `carlink_native/documents/reference/Firmware/firmware_configurables.md`
- Source: Binary analysis of `riddleBoxCfg_unpacked` (49KB, 2025.10 firmware)
- Source: Binary analysis of `ARMadb-driver_2025.10_unpacked` (478KB)
- Firmware strings analyzed using: `strings -t x`, `objdump -d`, `radare2`
