# CPC200-CCPA Video Protocol Reference

**Status:** VERIFIED via capture analysis and binary reverse engineering
**Consolidated from:** GM_research, carlink_native, binary analysis, 215K frame aggregate analysis
**Firmware Version:** 2025.10.15.1127 (binary analysis reference version)
**Last Updated:** 2026-02-02 (Fixed navigation video header documentation)

---

## Critical: Video Stream Characteristics

**CarPlay/Android Auto video is NOT traditional video playback.**

It is **live UI projection** — a real-time stream of the phone's screen state transmitted via H.264 encoding. This distinction is critical for host application design.

| Characteristic | Traditional Video | Projection Video (CarPlay/AA) |
|----------------|-------------------|-------------------------------|
| Content | Pre-recorded media | Live, interactive UI |
| Frame value | All frames matter | Only current frame matters |
| Late frames | Buffer and play later | **Discard immediately** |
| Buffering | Deep buffers improve quality | Deep buffers cause latency |
| Goal | Smooth playback | Low latency response |
| FPS | Fixed (24/30/60) | Variable (1-60 fps) |
| User expectation | Watch passively | Touch and interact |

### Host Application Requirements

**DO:**
- Decode frames immediately upon arrival
- Drop frames that arrive late (>30-40ms old) - balance needed: too long causes decoder poisoning, too short drops valid frames
- Reset decoder on corruption — don't wait for it to heal
- Keep buffers shallow (100-200ms jitter absorption only)
- Accept frame drops as normal operation

**DO NOT:**
- Buffer deeply for "smooth playback"
- Try to "catch up" by playing through a backlog
- Enforce fixed FPS targets
- Wait for missing frames
- Treat PTS as authoritative ordering

> **A late frame in projection video is worse than a dropped frame.**
> Late frames poison decoder reference state and create visual corruption.

### Why This Matters

The adapter forwards video **exactly as received** from the phone. If your host app:
- Buffers too deeply → latency grows, touch feels laggy
- Decodes late frames → decoder reference state corrupts → ghosting, smearing
- Doesn't reset on corruption → artifacts persist and amplify

**The adapter cannot fix host app policy errors.**

---

## Video Message Structure

### Main Video (Type 0x06)

**Total Header: 36 bytes** (16-byte USB header + 20-byte video header)

```
┌─────────────────────────────────────────────────────────────────────┐
│                    COMMON USB HEADER (16 bytes)                     │
├─────────┬──────┬────────────┬───────────────────────────────────────┤
│ Offset  │ Size │ Field      │ Description                           │
├─────────┼──────┼────────────┼───────────────────────────────────────┤
│ 0x00    │  4   │ Magic      │ 0x55AA55AA (little-endian)            │
│ 0x04    │  4   │ PayloadLen │ Bytes after this 16-byte header       │
│ 0x08    │  4   │ MsgType    │ 6 = VideoData                         │
│ 0x0C    │  4   │ Checksum   │ MsgType ^ 0xFFFFFFFF = 0xFFFFFFF9 (-7)│
├─────────┴──────┴────────────┴───────────────────────────────────────┤
│                  VIDEO-SPECIFIC HEADER (20 bytes)                   │
├─────────┬──────┬────────────┬───────────────────────────────────────┤
│ 0x10    │  4   │ Width      │ Video width (e.g., 2400)              │
│ 0x14    │  4   │ Height     │ Video height (e.g., 960)              │
│ 0x18    │  4   │ EncoderState│ Encoder generation/stream ID (see below)│
│ 0x1C    │  4   │ PTS        │ Presentation timestamp (1kHz clock)   │
│ 0x20    │  4   │ Flags      │ Usually 0x00000000                    │
├─────────┴──────┴────────────┴───────────────────────────────────────┤
│                     H.264 PAYLOAD (variable)                        │
├─────────────────────────────────────────────────────────────────────┤
│ 0x24+   │  N   │ H.264 Data │ NAL units with 00 00 00 01 start code │
└─────────────────────────────────────────────────────────────────────┘
```

### EncoderState Field Analysis (Firmware Verified Jan 2026)

The `EncoderState` field (offset 0x18) contains encoder generation/stream identifiers that change during video stream transitions.

**Observed Values (CarPlay):**

| Value | Hex | Count | Context |
|-------|-----|-------|---------|
| 7 | 0x00000007 | 4487 | Normal streaming (stable state) |
| 1617352863 | 0x6066d89f | 208 | Early stream phase |
| 8379311 | 0x007fdbaf | 16 | Stream initialization (with SPS) |
| 231883 | 0x000389cb | 21 | Brief reconfiguration |
| 3 | 0x00000003 | 1 | Transient state |

**Observed Values (Android Auto):**

| Value | Hex | Count | Context |
|-------|-----|-------|---------|
| 3 | 0x00000003 | 660 | All frames (stable) |

**Temporal Pattern (CarPlay):**
```
PTS 136351-136884: 0x007fdbaf (initialization, SPS present)
PTS 136918-146850: 0x6066d89f (early streaming)
PTS 146883+:       0x00000007 (normal streaming)
```

**Interpretation:**
- Changes indicate encoder state transitions or stream reconfiguration
- NOT correlated with NAL type (I-frame vs P-frame)
- Different default values for CarPlay (7) vs Android Auto (3)
- Large values (0x6066d89f, 0x007fdbaf) may be encoder-internal timestamps or counters

**Host Implementation:**
- Can be safely ignored for video decoding
- Useful for debugging stream initialization issues
- May indicate need to reset decoder if value changes unexpectedly

### Navigation Video (Type 0x2C) - iOS 13+

**Total Header: 36 bytes** (16-byte USB header + 20-byte video header)

**⚠️ Binary Verification (Feb 2026):** Type 0x2C = `AltVideoFrame` (navigation video).
Type 0x2B is `Connection_PINCODE` (BT pairing PIN), NOT a video type.
Verified from firmware switch statement at fcn.00017b74.

**IMPORTANT:** Navigation video uses the SAME header structure as main video.

```
┌─────────────────────────────────────────────────────────────────────┐
│                    COMMON USB HEADER (16 bytes)                     │
├─────────┬──────┬────────────┬───────────────────────────────────────┤
│ 0x00    │  4   │ Magic      │ 0x55AA55AA                            │
│ 0x04    │  4   │ PayloadLen │ Bytes after 16-byte header            │
│ 0x08    │  4   │ MsgType    │ 44 (0x2C) = NaviVideoData             │
│ 0x0C    │  4   │ Checksum   │ MsgType ^ 0xFFFFFFFF = 0xFFFFFFD3     │
├─────────┴──────┴────────────┴───────────────────────────────────────┤
│                  VIDEO-SPECIFIC HEADER (20 bytes)                   │
├─────────┬──────┬────────────┬───────────────────────────────────────┤
│ 0x10    │  4   │ Width      │ Navigation width (e.g., 1200)         │
│ 0x14    │  4   │ Height     │ Navigation height (e.g., 500)         │
│ 0x18    │  4   │ EncoderState│ Always 1 for nav video                │
│ 0x1C    │  4   │ PTS        │ Presentation timestamp (1kHz clock)   │
│ 0x20    │  4   │ Flags      │ Usually 0x00000000                    │
├─────────┴──────┴────────────┴───────────────────────────────────────┤
│                     H.264 PAYLOAD (variable)                        │
├─────────────────────────────────────────────────────────────────────┤
│ 0x24+   │  N   │ H.264 Data │ NAL units with 00 00 00 01 start code │
└─────────────────────────────────────────────────────────────────────┘
```

**Capture Verification (Jan 2026):**
- 429 navigation video packets analyzed
- Resolution: 1200x500 (matches host's naviScreenInfo configuration)
- NAL distribution: 4 SPS (I-frames), 425 P-frames
- EncoderState field: Always 1 for nav video (does NOT indicate frame type)
- Frame type determined by NAL unit type in H.264 payload

**Requirements (Corrected Feb 2026):**
- iOS 13+
- Host must send `naviScreenInfo` in BoxSettings JSON — this is the **primary activation mechanism**. The firmware parses `naviScreenInfo` at `0x16e5c` and branches directly to the `HU_SCREEN_INFO` path, **bypassing** the `AdvancedFeatures` config check.
- `AdvancedFeatures=1` is **NOT required** when `naviScreenInfo` is provided (disproven by testing)
- Command 508 handshake: **inconclusive** — the `pi-carplay` reference echoes 508 back, but testing could not isolate this as a requirement. Recommended as precaution.

### Wireless CarPlay Navigation Video (TCP Stream Type 111)

**IMPORTANT:** For **wireless** CarPlay sessions, navigation video is NOT delivered via USB message type 0x2C. Instead, it uses a separate WiFi TCP stream.

| Transport | Nav Video Mechanism | USB Type |
|-----------|---------------------|----------|
| Wired CarPlay | USB message 0x2C | Yes |
| Wireless CarPlay | WiFi TCP (type 111) | No |

**Stream Types (AirPlay Protocol):**

| Type | Purpose | Example Resolution |
|------|---------|-------------------|
| 110 | Main screen video | 1200×480 @ 60 FPS |
| 111 | Alt screen (cluster/navigation) | 1000×400 @ 24 FPS (configurable 10-60, recommended 24-60) |

**TTY Log Evidence (Jan 2026 Wireless CarPlay Capture):**
```
[AirPlay] ### Setup stream type: 111
[AirPlayReceiverSessionScreen] Video Latency 75 ms
[AirPlay] Alt screen receiver set up on port 49281
[D] _SendPhoneCommandToCar: RequestNaviScreenFoucs(508)
[ScreenStream] ### Screen set widthPixels: 1000
[ScreenStream] ### Screen set heightPixels: 400
[ScreenStream] ### Screen set avcc data: 40, 1e006401
[ScreenStream] ### Send h264 I frame data 150 byte!

[AirPlay] ### Setup stream type: 110
[ScreenStream] ### Screen set widthPixels: 1200
[ScreenStream] ### Screen set heightPixels: 480
[ScreenStream] ### Send h264 I frame data 3607 byte!
```

**Alt Screen URLs (Navigation):**
```
maps:/car/instrumentcluster
maps:/car/instrumentcluster/map
maps:/car/instrumentcluster/instructioncard
```

**H.264 Profile Comparison:**

| Stream | Profile | Level | SPS Header |
|--------|---------|-------|------------|
| Main (1200×480) | High (100) | 3.2 | `27 64 00 20` |
| Cluster (1000×400) | High (100) | 3.0 | `27 64 00 1e` |

**Why USB Capture Shows No 0x2C:**

When capturing USB packets from a wireless CarPlay session:
- USB capture only shows Type 6 (main video) going to the car's head unit
- Navigation video goes directly via WiFi TCP to the adapter's AirPlay receiver
- The adapter handles the two streams internally and may merge or route separately

---

## Video Processing Architecture (Binary Verified)

**CRITICAL FINDING:** Video from CarPlay/Android Auto is **forwarded/passthrough** - the adapter does NOT transcode or re-encode the H.264 stream.

### Architecture Overview

```
┌────────────────┐     ┌─────────────────────────────────────────┐     ┌──────────────┐
│   iPhone/AA    │     │           CPC200-CCPA Adapter           │     │    Host      │
│                │     │                                         │     │    App       │
│  CarPlay UI    │────►│  AppleCarPlay  ──────►  ARMadb-driver   │────►│              │
│  (H.264)       │iAP2 │   (Unix Sock)   IPC    (_SendDataToCar) │ USB │  (H.264      │
│                │     │                                         │     │   Decode)    │
└────────────────┘     └─────────────────────────────────────────┘     └──────────────┘
```

### What the Adapter Does to Video

| Step | Operation | Modifies H.264 Payload? |
|------|-----------|-------------------------|
| 1 | Receive H.264 from AirPlay/iAP2 | NO |
| 2 | Parse NAL units (keyframe detection only) | NO |
| 3 | Extract/log timestamp | NO |
| 4 | Prepend USB header (16 bytes) | Header only |
| 5 | Prepend video metadata (20 bytes) | Metadata only |
| 6 | Forward to USB endpoint | NO |

### Binary Evidence (Jan 2026 Analysis)

**AppleCarPlay Binary:**
- `AirPlayReceiverSessionScreen_ProcessFrames` - receives H.264 stream
- `_AirPlayReceiverSessionScreen_ProcessFrame` - processes individual frames
- `### Send screen h264 frame data failed!` - sends raw H.264 data
- `### Send h264 I frame data %d byte!` - sends I-frames unchanged
- `### H264 data buffer overrun!` - buffer management, not decoding
- Only codec imports: `aacDecoder_*`, `aacEncoder_*` (AAC audio only)

**ARMadb-driver Binary:**
- `recv CarPlay videoTimestamp:%llu` (at 0x6d139) - logs timestamp
- `_SendDataToCar iSize: %d, may need send ZLP` (at 0x6b823) - USB transmission
- USB magic header `0x55AA55AA` at 0x62e18

**No Video Codec Libraries Found:**
- No FFmpeg, x264, x265, libvpx, OpenH264
- Video encoder functions in `libdmsdpdvcamera.so` are for **reverse camera** (sending TO phone), not CarPlay video

### Inter-Process Communication

| Component | Mechanism | Purpose |
|-----------|-----------|---------|
| AppleCarPlay → ARMadb-driver | Unix Socket (`CRiddleUnixSocketServer`) | Video frame transfer |
| AppleCarPlay → ARMadb-driver | D-Bus (`org.riddle`) | Control signals |
| ARMadb-driver → Host | USB Bulk Transfer | Video data forwarding |

### Implications for Host Apps

1. **Host must decode H.264** - the adapter does not provide decoded frames
2. **H.264 is Annex B format** - NAL units with 00 00 00 01 start codes
3. **Keyframe requests** - Host can request IDR via Frame command (0x0C)
4. **No format conversion** - Resolution/framerate set during CarPlay negotiation

---

## H.264 Configuration

| Parameter | Value |
|-----------|-------|
| **Profile** | High Profile (100) |
| **Level** | 5.0 (Level IDC = 50) |
| **NAL Format** | Annex B (00 00 00 01 start codes) |
| **SPS/PPS** | In-band with IDR frames |

**Note:** Level 5.0 supports up to 4096×2304 @ 30fps, providing headroom for 2400×960 @ 60fps.

### iPhone AVE Encoder Identity (iPhone Syslog, Mar 2026)

Runs 4-6 captured iPhone-side `idevicesyslog` during CarPlay sessions, revealing the encoder hardware:

- **Hardware**: Apple Video Encoder (AVE) H9 variant (Apple Silicon A-series)
- **Firmware**: AVE 905.29.1, compiled Feb 4, 2026 in prod
- **Daemon**: `airplayd` (PID 591) — encoding runs in the AirPlay daemon, not the CarPlay app
- **Profile/Level**: High Profile, Level 4.0 at 2400×788@30fps (vs Level 5.0 at 1920×1080@60fps in Jan 2026 captures)

**AVCC (41 bytes) — identical across all sessions:**
```
01 64 00 28 ff e1 00 16 27 64 00 28 ac 13 14 50
09 60 65 f9 e6 e0 21 a0 c0 da 08 84 65 80 01 00
04 28 ee 3c b0 02 00 00 00
```

Decoded SPS fields: profile_idc=100 (High), level_idc=40 (4.0), chroma 4:2:0, bit depth 8/8, pic_order_cnt_type=0, no B-frames (I+P only stream confirmed).

**800×480 thumbnail encoder phase**: Fresh AirPlay sessions start with a low-resolution 800×480 encoder (thumbnail/preview for adapter negotiation screen), switching to full resolution within 344ms. Warm reconnects skip this phase.

### Pixel Software Encoder Identity (Logcat, Mar 2026)

Android Auto uses `c2.google.avc.encoder` — Google **software** AVC encoder (CPU-based), not hardware. The Pixel has `c2.exynos.avc.encoder` (HW) but Gearhead selects the software codec for cross-device compatibility.

| Parameter | Value |
|-----------|-------|
| Encoder | `c2.google.avc.encoder` |
| Profile | Baseline (66), Constrained Baseline |
| Level | 3.1 |
| Entropy | CAVLC (Baseline mandates this) |
| CSD size | **29 bytes** (vs CarPlay 33B — simpler SPS, no scaling lists/transform_8x8) |

**CSD hex dump (identical across all 6 runs, all 10 sync events):**
```
00 00 00 01 67 42 40 1f e9 00 a0 0b 74 d4 04 04
04 1e 10 08 54 00 00 00 01 68 ca 8f 20
```

**Two-phase configuration** (from Pixel `CCodecConfig`):
1. **Phase 1 — Codec2 defaults**: 64 kbps placeholder, 1s sync-frame-interval
2. **Phase 2 — Gearhead reconfigures**: 4.03 Mbps VBR, Baseline L3.1, `sync-frame-interval=60000000` μs (60s), 30fps, `prepend-sps-pps-to-idr-frames=0` (SPS/PPS sent separately)

### Typical Resolutions

| Resolution | FPS | Use Case |
|------------|-----|----------|
| 800x480 | 30 | Basic head units |
| 1280x720 | 60 | Standard HD |
| 1920x720 | 60 | Widescreen |
| 1920x1080 | 60 | Full HD |
| 2400x960 | 60 | Ultra-wide (GM Info 3.7) |

---

## Keyframe (IDR) Management

### Frame Sync Command (0x0C)

The host can request a keyframe by sending:
```
Magic: 55 AA 55 AA
Length: 00 00 00 00
Type: 0C 00 00 00
Check: F3 FF FF FF
```

**Response:** Adapter sends SPS + PPS + IDR frame within 100-200ms

**Measured Response Times (Jan 2026):**
| Frame Sync Sent | IDR Received | Delta |
|-----------------|--------------|-------|
| 41,297ms | 41,395ms | **98ms** |
| 440,859ms | 441,040ms | **181ms** |

### IDR Sent Notification (0x3F1)

**Direction:** Device → Host

After sending an IDR, adapter may send type 0x3F1 to notify host.

**Note:** Only sent when host uses format=5 in Open command.

### Comparison (Jan 2026 Capture)

| Metric | format=5 (carlink_native) | format=1 (pi-carplay) |
|--------|--------------------------|------------------------|
| IDR frames | 107 | 27 |
| SPS repetitions | 118 | 33 |
| Receives 0x3F1 | Yes | No |
| Frame sync sent | Yes | No |

### iPhone-Side ForceKeyFrame Behavior (iPhone Syslog, Mar 2026)

The iPhone confirms every host `Command FRAME` via `airplayd(CoreUtils)`:
```
[0x148A] Force Key Frame, params: NULL
```

| Run | ForceKeyFrame Events | Host Command FRAME Count | Match |
|-----|---------------------|--------------------------|-------|
| 5 | 27 | 27 | Exact |
| 6 | 26 | 26 | Exact |

**CRITICAL**: ForceKeyFrame triggers a **complete AVE encoder restart** (Stop → Destroy → Create → Start), NOT a simple IDR insertion. The full cycle:
1. FigVirtualFramebuffer source suspended
2. `AVE_Plugin_AVC_Invalidate` → `AVE_Session_AVC_Stop`
3. `AVE_Session_AVC_Destroy`
4. `AVE_Plugin_AVC_CreateInstance` → `AVE_Session_AVC_Create`
5. `AVE_Plugin_AVC_StartSession`
6. FigVirtualFramebuffer source resumed

This cycle takes ~3ms, visible as a brief PTS gap around each keyframe.

**AirPlay RTSP control channel**: All keyframe requests flow via `POST /command RTSP/1.0`. Run 5: 55/55 requests at `200 OK`. Run 6: 53/53. Zero RTSP errors or timeouts across all runs.

### Android Auto: NO Forced Keyframes (Mar 2026)

**CRITICAL**: `Command FRAME` sent to an AA adapter causes the Pixel to **reset its entire projection UI**, NOT just the video encoder. This is fundamentally different from CarPlay where `Command FRAME` triggers a clean IDR.

| Run | FRAME Commands | Trigger | Impact |
|-----|---------------|---------|--------|
| 1 | **1** (bug) | Watchdog zombie reset | Pixel UI reset, 540ms recovery |
| 2 | **1** (bug) | Watchdog zombie reset | Pixel UI reset, 324ms recovery |
| 3-6 | 0 | — | Correctly suppressed |

- Runs 1-2 confirmed the UI reset — the watchdog reset code path incorrectly sent `Command FRAME` after zombie codec detection
- Runs 3-6 correctly suppressed FRAME (0 commands sent)
- **Natural IDR interval: 62-68s** (encoder `sync-frame-interval=60000000` μs)
- No external keyframe control available — decoder must wait for next natural IDR
- **Contrast with CarPlay**: Periodic forced keyframe cycle via `Command FRAME` → AVE encoder restart (2.5s initial, 30s periodic). The periodic interval is a mitigation for the GM Info 3.7 (Intel Atom x7-A3960) platform, where the Intel VPU can silently corrupt decoder state mid-session due to USB stalls, hypervisor interrupts, or VPU firmware bugs outside the app's control. CarPlay encoder teardown is invisible to the user at any reasonable interval. The 30s value can be adjusted per-platform — 2s was used historically with no user-visible impact, shorter intervals trade iPhone encoder overhead for faster corruption recovery

---

## Video Timing

### Variable Frame Rate Streaming (IMPORTANT)

CarPlay uses **variable frame rate** streaming. The configured FPS (e.g., 60) is the **maximum** rate, not guaranteed. When screen content is static, frame rate drops to conserve bandwidth.

**PTS Delta Distribution (8,605 frame intervals from Jan 2026 capture):**

| PTS Delta | Count | Percentage | Implied FPS |
|-----------|-------|------------|-------------|
| 16-17 | 6,532 | 75.9% | ~60 fps |
| 30-36 | 327 | 3.8% | ~30 fps |
| Other | 1,746 | 20.3% | Adaptive |

**Frame Rate Over Time (sample windows):**
```
Time(s)  | FPS   | Bitrate   | Activity
─────────┼───────┼───────────┼──────────────────────
8        | 11.2  | 1.6 Mbps  | Session start
35       | 25.3  | 4.3 Mbps  | Active navigation
57       | 50.0  | 0.3 Mbps  | Static screen
227      | 1.7   | 0.3 Mbps  | Nearly static

Actual FPS range: 1.7-50.0, Avg: 27.1 fps
```

### iPhone-Confirmed Frame Rate (Mar 2026)

iPhone syslog (Runs 5-6) confirms frame rate is **content-dependent**, not fixed:

| Metric | Value |
|--------|-------|
| Actual encode rate | 13–27 fps (varies per 2s interval) |
| Encoder drops | **0** (all intervals, all runs) |
| PTS grid | 33.33ms VSYNC intervals |
| Frame submission | Variable — FigVirtualFramebuffer submits only on screen updates |

The iPhone encoder processes every submitted frame without exception (`Drop: 0` across every 2-second reporting interval). All frame drops in the pipeline originate downstream — in the adapter USB transport, host staging buffer, or codec input buffer contention.

### Android Auto Frame Rate (Pixel Logcat, Mar 2026)

AA maintains a **fixed ~29.2fps mean** (range 28.8-30.0 in first 30-second windows), unlike CarPlay's variable content-driven rate:

| Metric | Value |
|--------|-------|
| Mean FPS (first window) | **29.2 fps** (6 runs: 29.4, 29.1, 28.8, 30.0, 28.8, 29.0) |
| Frame rate limiter | `FrameRateLimitManagerImpl` enforces 30fps ceiling |
| Rate reduction | `PowerBasedLimiter`: 60→30fps at projection start |
| PTS interval | **89% at 33ms**, 8% at 34ms — tighter than CarPlay (60%/30%) |
| Late-window decline | 6.5-13.8fps when AA screen idle (fewer encoder frames) |
| Frame drops | **Zero** during uninterrupted streaming across all 6 runs |

The Pixel software encoder targets a fixed 30fps output rate, unlike the iPhone's VirtualFramebuffer which only submits frames on screen updates.

### Bitrate Statistics

| Metric | Value |
|--------|-------|
| Overall Average | 1.18 Mbps |
| 1-second Window Avg | 1.52 Mbps |
| Minimum (1s window) | 70 kbps |
| Maximum (1s window) | 15.3 Mbps |
| Peak (active content) | 4.3 Mbps |

**Note:** Low average reflects variable frame rate - static screens consume minimal bandwidth.

### iPhone-Confirmed Bitrate Control (DataRateLimits, Mar 2026)

iPhone `airplayd(VideoProcessing)` reports real-time encoder bitrate via `DataRateLimits` property:

| Phase | Target Bitrate | Duration |
|-------|---------------|----------|
| Session start floor | 750 Kbps | Initial |
| Ramp | 1.0 → 1.5–1.8 Mbps | 1–2 seconds |
| Steady state | 1.19–1.27 Mbps | Remainder |
| Burst budget | 20% of target | Per-frame allowance |

DataRateLimits format: `[targetBitrate, unknown, burstBitrate, burstWindow]` where `burstBitrate` = 20% of target.

The steady-state 1.2 Mbps matches the 1.18 Mbps aggregate measured from host-side captures (section above), confirming the iPhone's bitrate controller is the authoritative rate limiter.

### Android Auto Bitrate Configuration (Pixel Logcat, Mar 2026)

AA bitrate is **fixed at Gearhead configuration time**, unlike CarPlay's adaptive algorithm:

| Parameter | Value |
|-----------|-------|
| Gearhead target | **4.03 Mbps VBR** (`bitrate-mode=1`) |
| Max bitrate | 4,034,400 bps (matches target) |
| Adapter cap | `maxVideoBitRate = 5000 Kbps` (from web UI `bitRate=5`) |
| Audio streams | MEDIA 48kHz stereo, TTS 16kHz mono, SYSTEM 16kHz mono |

The adapter passes `maxVideoBitRate` to OpenAuto as a cap; the Pixel's Gearhead independently configures the encoder at 4.03 Mbps within that cap. Unlike CarPlay's DataRateLimits that update hundreds of times per session, AA bitrate is set once at projection start and does not adapt.

### vs Audio Independence

| Metric | Video | Audio |
|--------|-------|-------|
| Frame Rate | Variable (1.7-50 fps) | Fixed (16-17 fps) |
| Packet Timing | 10-20ms typical | 60ms fixed |
| Bitrate | 0.07-15.3 Mbps | 1.54 Mbps fixed |

---

## SPS/PPS Handling

### SpsPpsMode Configuration

| Value | Behavior |
|-------|----------|
| 0 | Auto - firmware decides |
| 1 | Re-inject - prepend cached SPS/PPS before each IDR |
| 2 | Cache - store in memory, replay on decode errors |
| 3 | Repeat - duplicate SPS/PPS in every video packet |

### Typical SPS/PPS NAL Units

```
SPS: 00 00 00 01 67 ...  (NAL type 7)
PPS: 00 00 00 01 68 ...  (NAL type 8)
IDR: 00 00 00 01 65 ...  (NAL type 5)
P-frame: 00 00 00 01 41 ... (NAL type 1)
```

### Boot-Screen IDR Poisoning (Observed Mar 2026)

Run 6 captured an abnormally small first IDR of **1,206 bytes** (vs typical 9–52KB). This is consistent with the iPhone encoder's 800×480 thumbnail/preview phase — the first IDR after session establishment may encode a boot-screen or low-resolution placeholder rather than the full CarPlay UI.

**Impact**: All P-frames following a boot-screen IDR reference this low-quality frame, causing ~2 seconds of degraded output until the next ForceKeyFrame triggers a clean IDR at full resolution.

**Mitigation**: `AA_RENDER_SKIP_COUNT=4` — skip first 4 decoded frames in Android Auto mode, preventing boot-screen display entirely. The decoder warms up cleanly before rendering starts. This is decoder-side only; no adapter commands are involved. (Earlier `flushOnNextIdr` approach was removed as inferior.)

### Android Auto Boot-Screen IDR Poisoning (Mar 2026)

AA boot-screen IDR poisoning is **deterministic and severe** — far worse than CarPlay's variable-size first IDR:

| Metric | CarPlay | Android Auto |
|--------|---------|-------------|
| First IDR size | 1,206–90,549B (variable) | **2,735B (identical all 6 runs, all 10 sync events)** |
| Bits/pixel | Variable | 0.024 bits/pixel — near-empty boot-screen |
| Poisoning window | ~30s (next ForceKeyFrame clears, configurable) | **60-70s** (no forced keyframe mitigation) |
| Natural IDR size | N/A (forced every 30s, configurable) | 49,792-58,654B (18-21× larger than boot-screen) |

The 2,735B IDR encodes the AA startup animation — a nearly uniform dark background that compresses to <3KB for 921,600 pixels (1280×720). All subsequent P-frames reference this degenerate IDR until the next natural IDR at ~65 seconds.

**Current mitigation (`AA_RENDER_SKIP_COUNT=4`)**: Skips the first 4 decoded frames without rendering them (releases with `shouldRender=false`). This prevents the boot-screen from ever being displayed and allows the codec to warm up cleanly. Matches AutoKit's behavior. The earlier `flushOnNextIdr` approach (flush codec on second IDR) was removed because it dropped the IDR and caused a brief glitch requiring watchdog recovery.

---

## Video Buffer Management

### Open Message Format Field

| Value | Name | IDR Behavior |
|-------|------|--------------|
| 1 | Basic | Minimal IDR insertion (stream start only) |
| 5 | Full H.264 | Responsive to Frame sync, aggressive IDR |

### Keyframe Recovery Flow

```
1. Video decode error detected
2. Host sends Frame (0x0C) command
3. Adapter responds within 100-200ms with:
   - SPS (sequence parameter set)
   - PPS (picture parameter set)
   - IDR (instantaneous decoder refresh)
4. Adapter sends IdrSent (0x3F1) notification
5. Host resets decoder, resumes playback
```

---

## Navigation Video Protocol (iOS 13+)

### Activation

Navigation video activation requires **two conditions**:

1. **One-time persistent unlock.** `AdvancedFeatures=1` must have been processed by the adapter at least once in its lifetime, which causes ARMadb to write `/etc/RiddleBoxData/HU_NAVISCREEN_INFO`. This file persists across reboots and across reverting `AdvancedFeatures=0`. AppleCarPlay session-init reads it to set `g_bSupportNaviScreen=1`; without that, `_AltScreenSetup` is never called regardless of host signaling. (Originally documented during the Jan 2026 discovery of navigation video.)
2. **Per-session activation.** Host sends `naviScreenInfo` in BoxSettings (Path A — recommended), OR `AdvancedFeatures=1` is currently set (Path B — legacy).

The firmware parses `naviScreenInfo` at `0x16e5c` and branches directly to `HU_SCREEN_INFO` (`0x170d6`), bypassing the **parser-side** `AdvancedFeatures` check. That bypass alone is insufficient on a virgin adapter, since `g_bSupportNaviScreen` won't be set — both gates must clear.

### Handshake Sequence (INCONCLUSIVE)

The firmware binary shows a 508 handshake path, and the `pi-carplay` reference implementation echoes 508 back. However, **live testing with CarLink Native could not conclusively determine whether the 508 echo is required**.

**Observed sequence:**
```
1. Adapter sends Command 508 to host (RequestNaviScreenFocus)
2. Host echoes Command 508 back to adapter (recommended but inconclusive if required)
3. Adapter emits HU_NEEDNAVI_STREAM D-Bus signal
4. Navigation video (Type 0x2C) streaming begins
```

**pi-carplay implementation** (`src/main/carplay/services/CarplayService.ts:270-277`):
```typescript
if ((msg.value as number) === 508 && this.config.naviScreen?.enabled) {
  this.driver.send(new SendCommand('requestNaviScreenFocus'))
}
```

**Recommendation:** Echo 508 back if received (low cost, may be needed in some firmware paths), but the primary activation mechanism is `naviScreenInfo` in BoxSettings.

**Prerequisites for navigation video:**
- Adapter must have had `AdvancedFeatures=1` processed at least once (one-time persistent unlock — see Activation section). On virgin adapters, run `riddleBoxCfg -s AdvancedFeatures 1 && riddleBoxCfg --upConfig` once via SSH.
- `naviScreenInfo` must be sent in BoxSettings JSON for Path A activation
- After the one-time unlock, `AdvancedFeatures` can be left at 0 — Path A continues to work
- CarPlay navigation app becomes active on phone
- Host's `naviScreen.enabled` config must be true

### naviScreenInfo BoxSettings Field

```json
{
  "naviScreenInfo": {
    "width": 1200,
    "height": 500,
    "fps": 24
  }
}
```

**Resolution is configurable** - the adapter streams at whatever resolution the host specifies in `naviScreenInfo`. Examples:
- 480x272 @ 30fps - compact display
- 1200x500 @ 24fps - widescreen cluster

**Optional safeArea field:**
```json
{
  "naviScreenInfo": {
    "width": 1200,
    "height": 500,
    "fps": 24,
    "safeArea": { "x": 0, "y": 0, "width": 1200, "height": 500 }
  }
}
```

### Focus Control Commands

| Type | Dec | Name | Direction |
|------|-----|------|-----------|
| 0x1FA | 506 | NaviFocus | H→D |
| 0x1FB | 507 | NaviRelease | H→D |
| 0x1FC | 508 | RequestNaviScreenFocus | Both |
| 0x1FD | 509 | ReleaseNaviScreenFocus | H→D |
| 0x6E | 110 | NaviFocusRequest | D→H |
| 0x6F | 111 | NaviFocusRelease | D→H |

---

## Video Limitations (Binary Verified Jan 2026)

**CRITICAL FINDING:** The adapter has **no hardcoded resolution or FPS validation** in the video forwarding path. Limits are practical (memory, bandwidth), not programmatic.

### Resolution Limits

**Binary Evidence - No Validation Found:**
- `recv CarPlay size info:%dx%d` - logs resolution, no rejection logic
- `set frame format: %s %dx%d %dfps` - sets format without bounds checking
- Width/Height stored as 32-bit integers (supports values up to 4 billion)
- No "resolution too large" or "unsupported resolution" error strings found

**Practical Constraints:**

| Constraint | Binary Evidence | Limit |
|------------|-----------------|-------|
| Memory Allocation | `### Failed to allocate memory for video frame with timestamp!` | ~128MB RAM total |
| Buffer Overrun | `### H264 data buffer overrun!` | Fixed buffer size |
| USB 2.0 Bandwidth | libusb bulk transfers | ~35-40 MB/s practical (~280 Mbps) |

### FPS Limits

**Binary Evidence - No Hardcoded Maximum:**
- `kScreenProperty_MaxFPS :%d` - property exists but value is **dynamic**, not hardcoded
- `--fps %d` command line parameter - accepts any integer
- `/tmp/screen_fps` - runtime configuration file
- `minFps: %d maxFps: %d` - tracks range, no rejection logic found
- `format[%d]: %s size: %dx%d minFps: %d maxFps: %d` - format logging only

### Bandwidth Constraints

**Binary Evidence:**
```
### tcpSock recv bufSize: %d, maxBitrate: %d Mbps
Not Enough Bandwidth
Bandwidth Limit Exceeded
```

Bandwidth checking exists but is **configured at runtime**, not hardcoded.

### What Would Happen (Binary-Based Analysis)

| Scenario | Resolution | FPS | Expected Result | Failure Mode |
|----------|------------|-----|-----------------|--------------|
| Standard | 1920x1080 | 60 | ✅ Works | - |
| Ultra-wide | 2400x960 | 60 | ✅ Works | - |
| 4K | 3840x2160 | 30 | ⚠️ Marginal | Memory pressure, USB bandwidth limit |
| 4K | 3840x2160 | 60 | ❌ Likely fails | USB bandwidth exceeded (~50+ Mbps needed) |
| 8K | 7680x4320 | Any | ❌ Fails | Memory allocation failure |
| High FPS | 1920x1080 | 120 | ⚠️ Marginal | 2x bandwidth vs 60fps |
| Extreme | 4K | 120 | ❌ Fails | Bandwidth + memory exceeded |

### Configuration Files (Runtime Limits)

| File | Purpose | Set By |
|------|---------|--------|
| `/tmp/screen_fps` | Current FPS setting | Host Open message |
| `/tmp/screen_size` | Current resolution | Host Open message |
| `/tmp/screen_dpi` | Display DPI | Host SendFile |

### Key Insight: "Unaware" Forwarding

The adapter is essentially **unaware** of absolute resolution/FPS limits:
1. CarPlay/Android Auto negotiates format during session setup
2. Phone determines format based on host's `Open` message
3. Adapter forwards whatever stream the phone sends
4. Failures occur from practical limits, not validation:
   - Memory exhaustion (dynamic allocation)
   - Buffer overrun (fixed buffers)
   - USB 2.0 bandwidth saturation

**Host applications should:**
- Request only resolutions they can decode/display
- Stay within USB 2.0 practical bandwidth (~25-35 Mbps for video)
- Monitor for `CarPlay recv data size error!` indicating data issues

---

## Captured Video Analysis (Jan 2026)

Real-world video characteristics from USB capture analysis of CarPlay and Android Auto sessions.

### CarPlay Main Video Session

| Property | Value |
|----------|-------|
| Device | iPhone 18,4 (iOS 23D5103d) |
| Resolution | 1280×720 |
| Duration | 216.2 seconds |
| Total frames | 4,733 |
| Total data | 50.96 MB |
| Average bitrate | 1.98 Mbps |
| Effective FPS | 21.9 fps |
| Average frame size | 11.02 KB |
| Min frame | 313 bytes |
| Max frame | 164,824 bytes |

**H.264 Codec Parameters:**
```
Profile: High (100)
Level: 3.1
SPS: 2764001fac13145014016e9b8086830368221196 (20 bytes)
PPS: 28ee3cb0 (4 bytes)
```

**NAL Unit Distribution:**
| NAL Type | Count | Description |
|----------|-------|-------------|
| 1 | 4,732 | Non-IDR (P-frames) |
| 5 | 1 | IDR (keyframe) |
| 7 | 1 | SPS |
| 8 | 1 | PPS |

**Frame Timing:**
- Expected interval: 33.3ms (30 fps target)
- Actual average: 45.7ms
- 70.7% of frames in 20-40ms range
- Some bursting: 8.6% delivered <10ms apart
- Occasional stalls: 3.5% intervals >100ms

### CarPlay Navigation Video

| Property | Value |
|----------|-------|
| Resolution | 1200×500 |
| Total frames | 2,841 |
| Total data | 10.31 MB |
| Average frame size | 3,804 bytes |
| Effective FPS | 12.5 fps |
| First frame | 7524ms into session |

**Navigation Video Header (20 bytes - same structure as main video):**
```
Offset  Size  Field         Example
------  ----  -----         -------
0x10    4     Width         1200 (0x04B0)
0x14    4     Height        500 (0x01F4)
0x18    4     EncoderState  Encoder generation/stream ID (typically 1)
0x1C    4     PTS           Presentation timestamp (1kHz clock)
0x20    4     Flags         Usually 0x00000000
```
Note: Offsets are relative to USB message start (16-byte USB header + 20-byte video header = 36 bytes total).

**H.264 Codec Parameters:**
```
Profile: High (100)
Level: 3.1
SPS: 2764001fac13145012c107e79b80868303682211 (21 bytes)
```

### Android Auto Video Session

| Property | Value |
|----------|-------|
| Device | Google Pixel 10 |
| Resolution | 1280×720 |
| Duration | 30.3 seconds |
| Total frames | 660 |
| Total data | 4.19 MB |
| Average bitrate | 1.16 Mbps |
| Effective FPS | 21.8 fps |
| Average frame size | 6.51 KB |
| Min frame | 49 bytes |
| Max frame | 56,291 bytes |

**H.264 Codec Parameters:**
```
Profile: Baseline (66)
Level: 3.1
Constraint flags: 0x40
SPS: 6742401fe900a00b74d40404041e100854 (17 bytes)
PPS: 68ca8f20 (4 bytes)
```

**NAL Unit Distribution:**
| NAL Type | Count | Description |
|----------|-------|-------------|
| 1 | 659 | Non-IDR (P-frames) |
| 5 | 1 | IDR (keyframe) |
| 7 | 1 | SPS |
| 8 | 1 | PPS |

**Frame Timing:**
- Expected interval: 33.3ms (30 fps target)
- Actual average: 46.0ms
- 86.2% of frames in 20-40ms range (more consistent than CarPlay)
- Less bursting: 0.6% delivered <10ms apart
- Fewer stalls: 1.7% intervals >100ms

### CarPlay vs Android Auto Comparison

| Aspect | CarPlay | Android Auto |
|--------|---------|--------------|
| H.264 Profile | High (100) | Baseline (66) |
| H.264 Level | 3.1 | 3.1 |
| Average bitrate | 1.98 Mbps | 1.16 Mbps |
| Frame size avg | 11.02 KB | 6.51 KB |
| IDR frame size | 9.5 KB | 2.7 KB |
| P-frame size avg | 5.9 KB | 6.7 KB |
| Frame timing consistency | 70.7% normal | 86.2% normal |
| Navigation video | Yes (Type 44) | Not observed |

**Key Observations:**

1. **CarPlay uses High profile** - Better compression efficiency, more complex decode
2. **Android Auto uses Baseline** - Simpler decode, broader compatibility
3. **Single IDR per session** - Both protocols send SPS/PPS/IDR only at start
4. **Variable frame rate** - Neither protocol maintains strict 30fps
5. **Android Auto more consistent** - Less frame timing variance
6. **CarPlay higher bitrate** - 70% higher average bitrate for same resolution

### Android Auto Quantitative Stream Data (Mar 2026, 6 Sessions)

| Metric | Value |
|--------|-------|
| CSD size | 29B (Baseline Profile L3.1, CAVLC) |
| First IDR | **2,735B deterministic** (all 6 runs, all 10 sync events) |
| Steady-state FPS | 29.2 fps mean (28.8-30.0 first window) |
| PTS stability | 89% at 33ms, 8% at 34ms |
| Boot-to-streaming | 37-47s (mean 41s) |
| BT→WiFi handoff | 3.0-3.2s typical (Run 2 outlier: 8.0s) |
| Connection path | BT HFP → RFCOMM → WiFi Direct → Projection |
| RFCOMM failures | 20-22 per run (structural, non-blocking, 5.5s retry cycle) |
| RFCOMM UUID | `4de17a00-52cb-11e6-bdf4-0800200c9a66` |
| WiFi link | 5GHz ch36, 144Mbps, RSSI -38 to -42, WPA2 |
| Natural IDR interval | 62-68s (`sync-frame-interval=60s`) |
| Sync→first-decode | 35.6ms average |
| Frame drops (steady) | **Zero** during uninterrupted streaming |
| Codec resets (pre-stream) | 2-4 per run (surface churn + connection retries) |
| Watchdog triggers | Runs 1-2 only (boot-screen IDR poisoning) |

**Connection timing breakdown (mean 41s):**
- Adapter boot: ~12s (USB device enumeration)
- USB connection + init: ~2-3s
- BT discovery + RFCOMM: ~20-25s (dominated by RFCOMM retry failures)
- BT→WiFi handoff: ~3s
- WiFi→first video: <1s

### Video Header Comparison

**Main Video Header (20 bytes):**
```
Offset  Field    CarPlay        Android Auto
------  -----    -------        ------------
0x00    Width    1280           1280
0x04    Height   720            720
0x08    Flags    0x007fdbaf     0x00000003
0x0C    PTS      Variable       Variable
0x10    Reserved 0              0
```

The Flags field differs significantly between protocols.

---

## TTY Log Correlation: Video Stream Setup

### CarPlay Video Setup Sequence

The adapter TTY log shows the internal AirPlay video stream negotiation:

| Timestamp | Event | Details |
|-----------|-------|---------|
| +5.2s | Setup stream type 111 | Navigation screen stream |
| +5.3s | Video Latency 75 ms | AirPlay latency negotiation |
| +5.5s | Screen set widthPixels: 1200 | Navigation video config |
| +5.5s | Screen set heightPixels: 500 | Navigation video config |
| +5.5s | Send h264 I frame data 187 byte | First nav I-frame |
| +5.6s | Setup stream type 110 | Main screen stream |
| +5.7s | Screen set widthPixels: 1280 | Main video config |
| +5.7s | Screen set heightPixels: 720 | Main video config |
| +5.7s | Send h264 I frame data 9687 byte | First main I-frame |
| +5.9s | RequestVideoFocus(500) | Adapter requests video focus |

**TTY Log Excerpt:**
```
[AirPlay] ### Setup stream type: 111
[AirPlayReceiverSessionScreen] Video Latency 75 ms
[ScreenStream] ### Screen set widthPixels: 1200
[ScreenStream] ### Screen set heightPixels: 500
[ScreenStream] ### Screen set avcc data: 40, 1f006401
[ScreenStream] ### Send h264 I frame data 187 byte!
[AirPlay] ### Setup stream type: 110
[ScreenStream] ### Screen set widthPixels: 1280
[ScreenStream] ### Screen set heightPixels: 720
[ScreenStream] ### Send h264 I frame data 9687 byte!
[D] _SendPhoneCommandToCar: RequestVideoFocus(500)
```

### Android Auto Video Setup Sequence

| Timestamp | Event | Details |
|-----------|-------|---------|
| +36.2s | Begin handshake | SSL/TLS handshake start |
| +36.3s | Handshake, size: 2348 | Certificate exchange |
| +36.3s | Handshake, size: 51 | Handshake completion |
| +36.5s | VideoService start | OpenAuto video service |
| +39.3s | First video frame | USB capture |
| +103.6s | H264 I frame | I-frame logged |

**SSL Handshake Error Codes (Jan 2026 Capture):**

The AaSdk SSL wrapper logs detailed error codes during handshake:

| Error Code | Meaning | Action |
|------------|---------|--------|
| `res=-1, errorCode=2` | SSL_ERROR_WANT_READ | Needs more data, retry |
| `res=-1, errorCode=3` | SSL_ERROR_WANT_WRITE | Buffer full, retry |
| `res=1, errorCode=0` | Success | Handshake complete |

```
[AaSdk] [SSLWrapper] SSL_do_handshake res = -1, errorCode = 2   ← needs more data
[AaSdk] [SSLWrapper] SSL_do_handshake res = -1, errorCode = 2   ← retry
[AaSdk] [SSLWrapper] SSL_do_handshake res = 1, errorCode = 0    ← success
Connected2 with (NONE) encryption
No certificates.
```

**Video Margin Settings:**

Android Auto configures video output margins that may vary between sessions:

| Setting | Default | Alt | Purpose |
|---------|---------|-----|---------|
| `margin w` | 0 | 80 | Horizontal margin (pixels) |
| `margin h` | 0 | 240 | Vertical margin (pixels) |

```
[OpenAuto] [Configuration] set margin w = 80
[OpenAuto] [Configuration] set margin h = 240
```

**TTY Log Excerpt:**
```
[OpenAuto] [Configuration] setVideoResolution = 2
[OpenAuto] [Configuration] set margin w = 80
[OpenAuto] [Configuration] set margin h = 240
[OpenAuto] [VideoService] start.
[D] [BoxVideoOutput] maxVideoBitRate = 5000 Kbps, bEnableTimestamp_ = 1
[I] Begin handshake.
[AaSdk] [SSLWrapper] SSL_do_handshake res = -1, errorCode = 2
[I] Handshake, size: 2348
[I] continue handshake.
[AaSdk] [SSLWrapper] SSL_do_handshake res = 1, errorCode = 0
[I] Handshake, size: 51
[D] [BoxVideoOutput] [BoxVideoOutput] H264 I frame
```

**Note:** `maxVideoBitRate` depends on `bitRate` setting in web UI (0-20 Mbps). Default 0 = auto, 5 = 5000 Kbps.

### Video Frame Rate Logging

The adapter logs video/audio frame rates every 10 seconds:

```
[D] box video frame rate: 26, 562.19 KB/s, audio frame rate: 0, 0.00 KB/s
[D] box video frame rate: 22, 73.35 KB/s, audio frame rate: 17, 191.45 KB/s
```

---

## Quantitative Stream Analysis (Aggregate Data)

**Source:** Analysis of 215,191 video frames across 10 recording sessions (133.7 minutes total).

This section provides definitive, measured stream behavior based on comprehensive capture analysis. For complete frame-by-frame data, see `/documents/video_code_reasoning/17_USB_CAPTURE_STREAM_ANALYSIS.md`.

### Session Start Behavior (VERIFIED)

```
FINDING: 100% of sessions begin with SPS+PPS+IDR bundle
STATUS:  Verified across all 10 captures (10/10)
```

**First packet structure (every session):**
```
[USB Header: 16B] + [Video Header: 20B] + [SPS: 22B] + [PPS: 4B] + [IDR: ~43KB]
```

**Implication:** No need to request keyframe at session start. Decoder can initialize immediately on first packet.

**Correction (Mar 2026):** While the first packet is always SPS+PPS+IDR, the first IDR may be a **boot-screen frame** (1,206B in Run 6 vs typical 9–52KB). This is the iPhone encoder's thumbnail/preview phase — not a full CarPlay UI render. The decoder initializes correctly but may display a degraded image for up to 2 seconds until the next ForceKeyFrame cycle.

**AA-specific (Mar 2026):** AA first IDR is **always** 2,735B boot-screen (deterministic across all 6 runs, unlike CarPlay's variable 1,206–90,549B). The poisoning window extends to ~65 seconds (next natural IDR) because `Command FRAME` cannot be used for AA — it causes a full Pixel UI reset instead of an encoder-only keyframe.

### SPS/PPS Bundling Rule (CRITICAL)

```
FINDING: SPS and PPS are NEVER sent as standalone packets
         They are ALWAYS bundled with IDR in a single USB packet
STATUS:  Verified across 538+ IDR frames
```

| Pattern | Occurrence | Description |
|---------|------------|-------------|
| `[P-slice]` | ~97% | Standard P-frame packet |
| `[SPS → PPS → IDR]` | ~3% | Complete keyframe bundle |
| `[SPS]` standalone | 0% | **Never occurs** |
| `[PPS]` standalone | 0% | **Never occurs** |

**Implication:** Every IDR is self-contained. No need to cache SPS/PPS separately for normal operation.

### IDR (Keyframe) Periodicity

| Metric | Value |
|--------|-------|
| Minimum interval | 83ms (burst recovery from keyframe request) |
| Maximum interval | 2,117ms |
| Average interval | 2,031ms |
| **Median interval** | **2,000ms** |

**Distribution (538 intervals analyzed):**

| Interval Range | Percentage | Meaning |
|----------------|------------|---------|
| < 500ms | 7.3% | Keyframe requests honored |
| 1.5 - 2s | 21.7% | Typical GOP |
| **2 - 2.5s** | **66.4%** | **Standard ~2s GOP** |
| > 2.5s | 0.4% | Rare long gap |

### Frame Timing and Jitter

**PTS Delta Distribution (frame-to-frame intervals):**

| Interval | Equiv FPS | Occurrence | UI Activity |
|----------|-----------|------------|-------------|
| 16-17ms | 58-62 fps | 55.7% | Active animations |
| 33-34ms | 29-30 fps | 11.9% | Moderate activity |
| 50ms | 20 fps | 30.6% | Light updates |
| 100ms+ | ≤10 fps | ~2% | Static/idle screen |

**Jitter Statistics:**

| Metric | Value |
|--------|-------|
| Standard deviation | **25.6ms** |
| Within ±20ms | 55% |
| **Within ±40ms** | **85%** |
| Maximum late | +276ms |

**Design Rule:** A 30-40ms staleness threshold is appropriate. ~85% of frames arrive within this window. Frames older than 40ms are safe to drop (except IDR).

### Frame Size Statistics

| Frame Type | Average | Range |
|------------|---------|-------|
| All frames | 24 KB | 0.4 - 138 KB |
| **IDR frames** | **49 KB** | 29 - 138 KB |
| **P-frames** | **23 KB** | 0.4 - 134 KB |

**iPhone-side confirmation (Mar 2026):** DataRateLimits steady-state of 1.19–1.27 Mbps = the 1.18 Mbps aggregate from host captures. At 30fps VSYNC, this yields ~5KB average P-frames, consistent with the observed distribution.

### Buffer Sizing Recommendations

```
192KB jitter buffer calculation:
  - Holds 4-8 typical frames (24KB average)
  - Provides ~200ms jitter absorption at 30fps
  - Can hold 1-2 maximum-size IDR frames

Rule: Deep buffering (seconds) is counterproductive for projection video.
      Drop frames rather than buffer for "smooth playback."
```

> **Manufacturer reference (PhoneMirrorBox r5889):** When decode buffer backlog exceeds 2× framerate (e.g., >120 frames at 60fps), the reference app requests a keyframe via `CarPlay_RequestKeyFrame` (sub-cmd 12), then clears all buffered frames, then sets a flag to discard P-frames until the next IDR arrives. AutoKit (2025.03) uses a different strategy: blocking `dequeueInputBuffer` with a long timeout, plus output-side frame dropping based on presentation timing.

### GOP Structure

| Metric | Value |
|--------|-------|
| P-frames per GOP (min) | 0 (back-to-back IDRs during recovery) |
| P-frames per GOP (max) | 116 (sustained high FPS) |
| P-frames per GOP (avg) | 54 |

**No B-frames observed.** Stream is strictly I-P-P-P... with no bidirectional prediction. No frame reordering needed.

### Stream Structure Pattern

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ CARPLAY VIDEO STREAM STRUCTURE                                               │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  [SPS+PPS+IDR] → [P] → [P] → ... → [P] → [SPS+PPS+IDR] → [P] → [P] → ...   │
│       │              16-53 frames            │                               │
│       │              (~550ms-2s)             │                               │
│   Session                               Periodic                             │
│    Start                               keyframe                              │
│   (100%)                              (~2s typical)                          │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Key Design Rules (Derived from Data)

| Rule | Justification |
|------|---------------|
| First packet is always SPS+PPS+IDR | 10/10 sessions verified |
| Never discard IDR packets | Only recovery points; always bundled with SPS+PPS |
| Use 30-40ms staleness threshold | 85% of frames within ±40ms |
| 192KB buffer is sufficient | 4-8 frames, ~200ms at 30fps |
| Don't expect constant frame rate | 2-60+ fps is normal based on UI activity |
| No B-frames, no reordering needed | Strictly I-P-P stream |

---

## Android Auto Resolution Tier Algorithm (AutoKit Decompilation, Mar 2026)

**Source:** Carlinkit AutoKit app v2025.03.19.1126 — manufacturer's reference implementation, decompiled from jiagu360-protected APK.

The firmware itself accepts any resolution 0-4096 (no tier enforcement in ARMadb-driver). However, the Android Auto protocol (inside the packed ARMAndroidAuto/OpenAuto SDK) enforces specific resolution tiers. The manufacturer's app computes the AA resolution to send in BoxSettings (`androidAutoSizeW`/`androidAutoSizeH`) using the following algorithm:

### Tier Calculation

Given the host display dimensions (`srcWidth` × `srcHeight`), four tiers are generated. All dimensions are forced even via `& 0xFFFE`:

**Landscape (width > height):**
| Tier | Base Height | Width Calculation | Max Width |
|------|-------------|-------------------|-----------|
| 480p | 480 | `(480 * srcWidth / srcHeight) & 0xFFFE` | 800 |
| 720p | 720 | `(720 * srcWidth / srcHeight) & 0xFFFE` | 1280 |
| 1080p | 1080 | `(1080 * srcWidth / srcHeight) & 0xFFFE` | 1920 |
| 1440p | 1440 | `(1440 * srcWidth / srcHeight) & 0xFFFE` | 2560 |

**Portrait (height >= width):**
| Tier | Base Width | Height Calculation | Max Height |
|------|------------|-------------------|------------|
| 480p | 800 | `(800 * srcHeight / srcWidth) & 0xFFFE` | 480 |
| 720p | 1280 | `(1280 * srcHeight / srcWidth) & 0xFFFE` | 720 |
| 1080p | 1920 | `(1920 * srcHeight / srcWidth) & 0xFFFE` | 1080 |
| 1440p | 2560 | `(2560 * srcHeight / srcWidth) & 0xFFFE` | 1440 |

Default tier selection: **720p** (index 1). User-configurable via preference `vandroidautoh` (480/720/1080/1440).

### 1440p / 2160p: Not Supported by Android Auto (Verified 2026-03-12)

**Device:** Pixel 10 (frankel), Android 16 (API 36), security patch 2026-02-05
**AA Version:** v16.3.660834-release (com.google.android.projection.gearhead)

Although the AutoKit manufacturer app defines 4 tiers (including 1440p) and the Android Auto APK contains enum values for `VIDEO_2560x1440`, `VIDEO_1440x2560`, `VIDEO_3840x2160`, and `VIDEO_2160x3840` (class `wvc`, ordinals 3/4/7/8), **the actual resolution mapping function (`gvu.p()`) throws `iuz("Unsupported resolution")` for any resolution above 1080p.**

Verified by JADX decompilation of `base.apk` pulled from Pixel 10:

```java
// gvu.p() — the only function that maps wvc enum → Size for video negotiation
static final Size p(wvc wvcVar) throws iuz {
    int iOrdinal = wvcVar.ordinal();
    if (iOrdinal == 0) return new Size(800, 480);    // VIDEO_800x480
    if (iOrdinal == 1) return new Size(1280, 720);   // VIDEO_1280x720
    if (iOrdinal == 2) return new Size(1920, 1080);  // VIDEO_1920x1080
    if (iOrdinal == 5) return new Size(720, 1280);   // VIDEO_720x1280 (portrait)
    if (iOrdinal == 6) return new Size(1080, 1920);  // VIDEO_1080x1920 (portrait)
    throw new iuz("Unsupported resolution: " + wvcVar.name());
}
```

This function is called by `gvu.n()` → `wcs.b()` during video size negotiation. Selecting "1440p" or "2160p" in AA Developer Settings (`car_video_resolution`) will cause a runtime exception.

**Additional constraints from AA APK analysis:**
- **Framerate:** 480p and 720p get **60fps**; 1080p gets **30fps** (determined by `vbu.aj()` — ordinals 0,1 → 60fps, else → 30fps)
- **Codecs supported:** H.264 Baseline Profile, H.265, AV1 (class `idx`, field `h`)
- **Wireless restriction:** Resolution may be further limited by Wi-Fi frequency band (5GHz required for wireless AA; log: `"VideoCodecResolutionType %s (%d) is not allowed due wireless frequency"`)
- **Manufacturer blocklist:** Subaru and HARMAN head units have a separate resolution restriction path (class `idx`, field `b`)

**Conclusion:** The effective maximum AA resolution is **1920×1080 @ 30fps** (landscape) or **1080×1920 @ 30fps** (portrait). The 1440p/2160p enum values and dev settings entries are forward-looking placeholders with no functional implementation as of AA v16.3.

**Example for GM gminfo37 (2400×960):**
- 720p tier: width = `(720 * 2400/960) & 0xFFFE` = `1800 & 0xFFFE` = **1800**, height = **720**
- Capped at max: width = min(1800, 1280) = **1280**, height = **720**
- Sent to adapter: `{"androidAutoSizeW": 1280, "androidAutoSizeH": 720}`
- Actual AA content area: 1280 × `(720 * 960/2400)` = 1280 × **512** (aspect-ratio preserved)

### DPI Calculation

The DPI sent via `/tmp/screen_dpi` is computed from the AA resolution:

```
if (width * height < 384000) → DPI = 100
else:
    dpi = (int)((((w*h - 384000) * 23 / 998400) + 120) * 1.25)
    dpi = dpi - (10 - (dpi % 10))   // round down to nearest 10
    if (landscape) dpi *= 1.2
```

Anchor point: 384000 pixels = 800×480 (480p tier). The formula scales linearly from DPI 150 (480p) to DPI ~195 (1080p).

### AA Oversizing (Letterbox Cropping)

When the phone renders at a different aspect ratio than the display (e.g., 1280×720 video for a 2400×960 display), black bars appear. The AutoKit app uses a **negative-margin oversizing technique** to crop these bars:

```
maxVideoSize = tier size (e.g., 1280×720)
androidAutoSize = actual AA content area (e.g., 1280×512)

// Calculate black bar margins
xDiff = (videoWidth - aaWidth) / 2
yDiff = (videoHeight - aaHeight) / 2

// Scale margins to display coordinates
scaledLeft = (xDiff * maxW) / aaW
scaledTop  = (yDiff * maxH) / aaH

// Oversize the view beyond display bounds to hide black bars
view.setTop(-scaledTop)
view.setLeft(-scaledLeft)
layoutParams.width  = maxW + (scaledLeft * 2)
layoutParams.height = maxH + (scaledTop * 2)
```

The SurfaceView is physically larger than the display area, with negative offsets pushing the black-bar edges off-screen. Only the center content portion remains visible.

### Resolution Scale Options

The app also generates a list of down-scaled and up-scaled resolution variants:
- **Down-scale:** 10 steps at 5% decrements (100%, 95%, 90%...55%), stopping when landscape width < 730 or height < 480
- **Up-scale:** 3 steps at 5% increments (105%, 110%, 115%), dimensions rounded up to even
- **Special oversize entries:** If 1920×(1080-1151), adds 1920×1152. If 1280×(720-767), adds 1088×768.

---

## References

- Source: `carlink_native/documents/reference/Firmware/firmware_video.md`
- **Quantitative analysis:** `carlink_native/documents/video_code_reasoning/17_USB_CAPTURE_STREAM_ANALYSIS.md`
- Source: `pi-carplay-4.1.3/firmware_binaries/2025.02/NAVIGATION_PROTOCOL_ANALYSIS.md`
- **508 handshake verified:** `pi-carplay-main/src/main/carplay/services/CarplayService.ts` (Jan 2026)
- Binary analysis: `ARMadb-driver_unpacked`, `AppleCarPlay_unpacked` (Jan 2026)
- Capture analysis: Jan 2026 (iPhone 18,4, Pixel 10)
- Session examples: `../04_Implementation/session_examples.md`
- **Jan 2026 Wireless CarPlay capture:** `picarplay-capture_26JAN22_02-45-40` - TCP type 111 alt screen verified
- **Jan 2026 Android Auto capture:** SSL handshake error codes verified (AaSdk logs)
- **Mar 2026 CarPlay capture (Runs 1-6):** iPhone AVE encoder characterization, ForceKeyFrame lifecycle, DataRateLimits bitrate control, boot-screen IDR poisoning — see `video_pipeline_analysis/VIDEO_PIPELINE_DEEP_ANALYSIS.md`
- **Mar 2026 iPhone syslog (Runs 4-6):** `idevicesyslog` capture confirming encoder identity, zero-drop behavior, RTSP 100% success rate
- **Mar 2026 AA capture (Runs 1-6):** Pixel `c2.google.avc.encoder` characterization, boot-screen IDR poisoning, FRAME prohibition, RFCOMM instability, connection lifecycle — see `video_pipeline_analysis/AA_VIDEO_PIPELINE_DEEP_ANALYSIS.md`
