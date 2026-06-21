# gminfo37 Platform FAQ
**Device:** 2024 Chevrolet Silverado ICE (Info 3.7) | **Build:** `W231E-Y181.3.2-SIHM22B-499.3`
**Evidence sources:** APK decompilation (jadx from Y181 system partition 86331654), POTATO SBC logcat corpus (22 day-directories / 50 files, Feb–May 2026), ADB enumeration (Y181 Apr 2026), Carlink source code (revisions 1–144), and this repo's own `documents/reference/gminfo/` research tree.

---

## 1. What is the gminfo37?

The **GM Info 3.7** (`gminfo37`) is the 2024 Chevrolet/GMC infotainment ECU built by Harman on Intel Apollo Lake (Broxton) silicon.

| Component | Specification |
|-----------|---------------|
| CPU | Intel Atom x7-A3960, 4-core / 4-thread, 2.4 GHz boost, 14nm |
| ABI | `x86_64`, dalvik variant `silvermont` |
| RAM | 6 GB DDR3L (5.66 GB kernel-visible; ~604 MB reserved by GHS hypervisor) |
| Storage | 64 GB Samsung KLMBG2JETD-B041 eMMC |
| GPU | Intel HD 505 (Gen9, 18 EUs); Mesa 21.1.5 GLES 3.2 |
| Display | Chimei Innolux DD134IA-01B, 2400×960 @ 60 Hz, ~13.4", `lcd_density=200` |
| Touch | Atmel maXTouch, 16-pt multitouch, I²C bus 7 @ 0x4B |
| HWC | `iahwcomposer` (Intel Automotive 2.1), explicit sync; no HDR/VRR/wide-gamut |
| Audio HAL | `vendor.hardware.audio@5.0-harman-custom-service` (Harman "Titan"), AVB transport |
| OS | Android 12 (API 32) AAOS, guest VM under GHS INTEGRITY IoT 2020.18.19 hypervisor |
| Kernel | Linux 4.19.305 LTS |
| WiFi | Broadcom BCM 802.11ac, **locked to 2.4 GHz** (`persist.sys.wifi.only2g=1`) |

**Runtime detection** in `PlatformDetector.kt`:
```kotlin
fun detectGmAaos(): Boolean =
    product.contains("gminfo", ignoreCase = true) ||   // "full_gminfo37_gb" matches
    device.startsWith("gminfo", ignoreCase = true)     // "gminfo37" matches

fun isGmInfo37(): Boolean = device.equals("gminfo37", ignoreCase = true)
```

The `isBroxton` field in `PlatformInfo` is **always false** on gminfo37 — GM does not expose the SoC codename in any `Build.*` property. Do not use it for gminfo37 detection.

---

## 2. Why don't USB permissions save between sessions?

**Root cause: `android.car.usb.handler` is completely absent from this firmware.**

Standard AOSP automotive builds include a system app named `android.car.usb.handler` that shows a persistent UI prompt when a USB device is attached and, critically, saves the user's "always allow this device" decision. GM removed it and replaced it with `com.gm.usbmountreceiver` (`USBMountRecv.apk`, `/system/priv-app/USBMountRecv/`).

**Decompiled source proof** (`jadx` from Y181 `86331654`, `USBMountReceiver.java`):
```java
// com.gm.usbmountreceiver.eng.USBMountReceiver
public void onReceive(Context context, Intent intent) {
    if (TextUtils.equals(intent.getAction(), "android.os.storage.action.VOLUME_STATE_CHANGED")) {
        // Handles USB mass storage (log file copy for GM engineering)
        context.startServiceAsUser(new Intent(..., GMLoggerUsbIntentService.class), UserHandle.OWNER);
    }
    // Other handlers: actionUSBAutoCopy, actionUSBAutoCopyModify, actionUSBToast
    // NO handling of USB_DEVICE_ATTACHED
    // NO handling of USB accessory/host permissions
}
```

`USBMountRecv` handles **only USB mass storage** (USB drives plugged into the radio). It never processes `android.hardware.usb.action.USB_DEVICE_ATTACHED`, never calls `UsbManager.grantPermission()`, and has no concept of "remember this device".

**System log evidence** — these two entries appear on every boot, every session:
```
W UserManagerService: android.car.usb.handler is allowlisted but not present.
E UsbProfileGroupSettingsManager: Default USB handling package (android.car.usb.handler) not found for user UserHandle{0}
E UsbProfileGroupSettingsManager: Default USB handling package (android.car.usb.handler) not found for user UserHandle{10}
```
Source: POTATO logcat `20260412/logcat_20260412_133523.log` — `UserManagerService` warning at line 2467; `UsbProfileGroupSettingsManager` `UserHandle{0}` at line 3790, `UserHandle{10}` at line 18820. (Emitted by `system_server`, PID 1160.)

**What this means in practice:**
- Every time the CCPA adapter is plugged in, the system asks for USB permission through a one-time dialog.
- The user tapping "always allow" does nothing persistent — there is no handler to record the decision.
- The permission dialog is provided by `system_server` directly; without the handler package, the "always allow" checkbox has no effect.
- This is a GM platform constraint. No app-side workaround exists.

**App-side manifest** (`AndroidManifest.xml` lines 38–40; rationale comment at line 33):
```xml
<uses-feature android:name="android.hardware.usb.host" android:required="false" />
```
`required=false` because gminfo37 does NOT advertise `android.hardware.usb.host` in its Play device profile. Setting `required=true` caused Play Store to filter this device off the AAOS track entirely (revision [143]).

---

## 3. Why is the app in full-screen immersive by default on gminfo37?

**`requiresImmersiveDefaults()` returns `true` on gminfo37 only:**
```kotlin
// PlatformDetector.kt
fun requiresImmersiveDefaults(): Boolean = isGmInfo37()

// DisplayModePreference.kt
fun platformDefault(): DisplayMode =
    if (PlatformDetector.detect(context).requiresImmersiveDefaults()) FULLSCREEN_IMMERSIVE
    else SYSTEM_UI_VISIBLE
```

Two consequences on gminfo37:
1. First-install / cleared-data default is `FULLSCREEN_IMMERSIVE` — the app fills the full 2400×960 panel.
2. The CarPlay OEM "Exit" button is hidden by default (`oemIconVisible = !requiresImmersiveDefaults()`). GM AAOS has its own back-navigation overlay; the OEM tile overlaps it visually.

**Display area breakdown** (measured — see Section 16 for the full geometry):
- Full immersive: 2400×960 (codec matches this)
- System-UI-visible (windowed): **1417×842** rendered content (between the 190 px App Dock and the 793 px CardView, below the 118 px Status Bar); WindowManager-usable bounds are **2211×842** (`L:189` App Dock + `T:118` Status Bar insets; `R:0 B:0` — there is no navigation-bar inset)
- DPI: physical ~193, system `lcd_density=200` (xhdpi)

Users can change the display mode from the app's settings without reinstalling.

---

## 4. Why does video use OMX.Intel.hw_vd.h264 specifically?

**gminfo37 has no software H.264 decoder.** The 12 `c2.android.*` software codecs are absent from this image. Only `OMX.Intel.hw_*` hardware decoders are available (VPU-backed).

`PlatformDetector` discovers the decoder at runtime (`PlatformDetector.detectHardwareH264Decoder()`, lines 282–300):
```kotlin
val hardwareH264DecoderName: String? =
    MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos    // deliberately NOT ALL_CODECS
        .firstOrNull { info ->
            !info.isEncoder && info.isHardwareAccelerated &&
            "video/avc" in info.supportedTypes
        }
        ?.name   // returns "OMX.Intel.hw_vd.h264" on gminfo37
```
(The source comment at line 275 notes `REGULAR_CODECS` is used deliberately — `ALL_CODECS` would surface codecs that aren't actually usable for instantiation.)

The decoder name is passed directly to `H264Renderer`:
```kotlin
// CarlinkManager.kt line 726
H264Renderer(context, surface, preferredDecoderName = platformInfo.hardwareH264DecoderName)
```

`H264Renderer.initCodec()` tries `MediaCodec.createByCodecName(preferredDecoderName)` first, falling back to `createDecoderByType("video/avc")`. A **bare `MediaFormat`** is used — no `KEY_LOW_LATENCY`, `KEY_PRIORITY`, `KEY_MAX_INPUT_SIZE`, or `csd-0/csd-1` — matching the Autokit `AvcDecoder` approach that avoids decoder poisoning (revision [98]).

**Known Intel VPU quirk:** In 1 of 3 POTATO sessions, the hardware decoder produced a corrupted first frame when fed a bundled SPS+PPS+IDR block. Fixed by splitting the keyframe feed and discarding the first decoded frame (revision [75]).

**Log marker** (from Apr 12 logcat line ~2900):
```
[PLATFORM] Intel-specific fixes: true [Intel VPU workaround enabled]
```

**Periodic keyframe schedule** for CarPlay sessions (CarlinkManager.kt lines 3008–3027):
- 2.5 s initial delayed keyframe — cold-start decoder poisoning safety net
- 30 s periodic keyframe loop — mid-session self-healing

This cadence is gminfo37-specific. The Android Auto path uses a single FRAME command at session start only (AA's phone UI resets if periodic FRAME commands arrive).

---

## 5. Why is low-latency audio mode (`PERFORMANCE_MODE_LOW_LATENCY`) not used?

**GM AAOS AudioFlinger denies `AUDIO_OUTPUT_FLAG_FAST` for all third-party apps.**

From POTATO logcat (Apr 12 session, every CarPlay audio start):
```
AudioTrack createTrack_l(8): AUDIO_OUTPUT_FLAG_FAST denied by server
```
System apps get `createTrack_l(0)` and pass. Third-party apps always get code `(8)`.

`PERFORMANCE_MODE_LOW_LATENCY` depends on the FAST flag being granted; without it the mode adds jitter with no latency benefit. The GM AAOS audio profile therefore uses `PERFORMANCE_MODE_NONE`:

```kotlin
// AudioConfig.kt — selected when isIntel && isGmAaos
val GM_AAOS = AudioConfig(
    sampleRate = 48000,                          // native rate; others trigger resampling
    bufferMultiplier = 4,
    performanceMode = AudioTrack.PERFORMANCE_MODE_NONE,
    prefillThresholdMs = 80,
    mediaBufferCapacityMs = 750,                 // larger than ARM default for stall absorption
    navBufferCapacityMs = 300,
)
```

**Native sample rate is 48 kHz.** AudioFlinger mix period: 8 ms (confirmed: `HarmanHal.Routing ... initRBuffer owner(bus0_media_out): owner period ms 8`); HAL output latency is approximately 3× the period (~24 ms, inferred, not directly logged). Audio transport is Ethernet AVB → Harman HAL → PulseAudio → external amplifier (CSM).

**ECNR HAL is missing** (`vendor.gm.audio@1.0::IECNRControl`). This service fails repeatedly during boot (count varies by session — observed 9 to 25 times across the corpus) at ~100–150 ms intervals with `IECNRControl/default is not registered` / `Unable to set property "ctl.interface_start"`. `hal_list.txt` confirms it "cannot be fetched from service manager (null)" — genuinely absent from the system image. This is expected and does not affect audio output.

---

## 6. Audio bus routing table

**8 output buses** (confirmed from `car_audio_configuration.xml` via `car_service.txt`, and from POTATO logcat CarAudioService boot dump Apr 12 line 5018):

| Bus address | Android VolumeGroup | AudioContext(s) | Gain range (mdB) | Default gain | Gain at capture |
|-------------|---------------------|-----------------|-----------------|--------------|-----------------|
| `bus0_media_out` | **5** | MUSIC, EMERGENCY, SAFETY, VEHICLE_STATUS, ANNOUNCEMENT | 0–6300 | 1200 | 1200 |
| `bus1_navigation_out` | **1** | NAVIGATION | 0–6300 | 1300 | 3100 |
| `bus2_voice_command_out` | **2** | VOICE_COMMAND | 0–6300 | 1300 | 3500 |
| `bus3_call_ring_out` | **3** | CALL_RING | 0–6300 | 3500 | 3500 |
| `bus4_call_out` | **4** | CALL | 0–6300 | 900 | 2600 |
| `bus5_alarm_out` | **0** | ALARM | 0–6300 | 1600 | 1600 |
| `bus6_notification_out` | **0** | NOTIFICATION | 0–6300 | 1600 | 1600 |
| `bus7_system_sound_out` | **0** | SYSTEM_SOUND | 0–6300 | 1600 | 1600 |

All buses: 48000 Hz PCM_16_BIT stereo. Volume step: 100 mdB (63 steps from 0 to 6300 mdB). Source: `car_service.txt` lines 419–513.

Note: VolumeGroups 0–5 here are **Android CarAudio VolumeGroup IDs** (what `CarAudioManager.getVolumeGroupCount()` returns). They are NOT the same as the Harman VAC hardware vgroup numbers. The hardware layer uses a separate numbering (CALL=1, RING=2, MUSIC=3, VOICE_COMMAND=4, PROMPT=5, SYSTEM=6) seen in GMAS/VolumeSynchro boot logs — those map to the CSM amplifier channels, not to Android API values.

**Carlink audio routing confirmed from POTATO logs:**

| Stream purpose | Focus type | Bus | Android VG | Evidence |
|---------------|------------|-----|------------|----------|
| Media (CarPlay audio) | GAIN | bus0_media_out | 5 | `GMAS/VACCallback: source BUS_MEDIA, vgroup MUSIC` |
| Phone call | GAIN_TRANSIENT | bus4_call_out | 4 | `GMAS/VACCallback: source BUS_CALL, vgroup CALL` |
| Navigation TTS | GAIN_TRANSIENT_MAY_DUCK | bus1_navigation_out | 1 | Focus granted; no GMAS VACCallback fired (overlay/duck) |

`HalAudioFocus is not supported on this device` — confirmed from CAR.AUDIO log. Audio focus arbitration is entirely software-side.

**ALERT/RINGTONE workaround** (`DualStreamAudioManager.kt` lines 564–578): `USAGE_NOTIFICATION_RINGTONE` maps to GM's `BUS_NOTIFICATION` (bus6, vgroup 6 SYSTEM) which is not adjustable. The app instead uses `USAGE_VOICE_COMMUNICATION_SIGNALLING` for alert/ringtone tracks to target `bus3_call_ring_out` (vgroup 2 RING).

---

## 7. What is the built-in GM CarPlay vs. the CCPA adapter?

The radio has **two completely separate CarPlay pathways**:

### Built-in GM CarPlay (native)
- **APK:** `GMCarPlaySrc.apk` (35 MB), `/system/app/GMCarPlaySrc/GMCarPlay.apk`
- **Process:** `com.gm.domain.server.delayed:CarplayService` (PID ~2651, user `u10_system`) — running at enumeration time
- **USB gadget:** `CarPlayRR` USB gadget configured in `init.carplay.rc` at boot:
  - VID `0x0451`, PID `0xD101`, manufacturer "harman", product "gminfo37"
  - FunctionFS endpoint `ffs.iAPClient` + NCM ethernet `ncm.usb0`
  - Serial number `2E6408F027620122` (device-unique)
  - HIDL service: `vendor.gm.ffsd@1.0-service` (PID 2875, root)
- **Framework:** Cinemo/NME (`libNmeVideoSW.so`), software decoder, 1416×842 @ 30 fps in windowed area
- **GMCarMediaService interaction:** `GmCarMediaService` has `R.string.carplay_source` hardcoded — during boot reconnect (`forBootUp=true`), if native CarPlay is the active source, `GmCarMediaService.onStartCommand()` immediately returns without re-binding (confirmed at `GmCarMediaService.java` line 463).

### CCPA Adapter CarPlay (carlink app)
- **VID/PID:** `0x1314` / `0x1521`, manufacturer "Magic Communication Tec.", product "Auto Box"
- **Access path:** `UsbManager.openDevice()` → system_server opens `/dev/bus/usb/*` node → passes fd to carlink
- **Decoder:** `OMX.Intel.hw_vd.h264` (hardware), 2400×960 @ 30 fps (full screen)
- **GmCarMediaService classification:** `STREAMAPP` type, `Device name:Carlink`

The two pathways are **mutually exclusive** — the adapter and native CarPlay cannot both be active simultaneously.

---

## 8. Why does the instrument cluster music card go blank?

This is a documented structural Android framework limitation affecting all AAOS platforms. On gminfo37 it is particularly visible because the cluster music card is prominent.

**Root cause** (confirmed from `documents/reference/gminfo/runtime/known_issues.md` and the POTATO corpus). carlink uses the Media3 `MediaLibraryService` API (`CarlinkMediaBrowserService` returns a single `MediaLibrarySession` from `onGetSession()`):
1. A `MediaLibraryService` creates its `MediaLibrarySession` once and returns the same instance from `onGetSession()` for the entire Service-process lifetime — there is no API to hand out a fresh session token mid-lifetime.
2. When carlink's session is invalidated (USB disconnect, force-stop, OS sleep), the singleton `CarlinkMediaBrowserService` is left holding a stale session. It cannot issue a fresh one from the same Service instance.
3. `GMCarMediaService` → `ClusterService` stay bound to the old session. They receive no notification of a new session. The cluster card goes blank.
4. The in-dash native Media Player recovers via a separate `MediaSessionManager` path and continues working.

**Why only reinstall/reboot recovers:** The only recovery is the OS killing and restarting `CarlinkMediaBrowserService` so a new session can be issued from a fresh Service. GM system services cannot be asked to re-bind from the third-party app side.

**Mitigations applied** (`MediaSessionManager.kt`, Media3 `MediaLibrarySession`):
- Session lifecycle is `initialize()` / `release()` rather than per-event destroy+recreate, so the single `MediaLibrarySession` instance is preserved across CarPlay connect/disconnect.
- Connection state is signalled via `setInactive()` and `setProjectionActive()` (which drive the Media3 `Player.State` to a paused/idle state with placeholder metadata) instead of tearing the session down — this keeps cluster controllers attached without active content. `updateMetadata()` refreshes the now-playing data.
- MediaSession ownership moved to `CarlinkMediaBrowserService.onCreate()` (revision [130], confirmed): the session is created via `MediaSessionManager.getOrCreate(...).initialize()` **before** any binder dispatch, so `onGetSession()` (which simply returns `getMediaLibrarySession()`, non-blocking) always has a live session. This eliminates the 4-second boot-race blank card:
  ```
  // Pre-fix: onGetSession at +43ms, MediaSession init at +4515ms → timeout → blank
  // Post-fix: MediaSession init at +160ms, onGetSession at +1ms after → card populated
  ```
- Revision [129] historically added a `CountDownLatch` bind-race guard; that latch has since been removed from the current source (superseded by the onCreate-ownership fix in [130]).

**None of these fully recover from process death.** The orphaned-controller-resurrection gap is a structural Android limitation, not a carlink bug.

---

## 9. Why do cluster navigation icons work on gminfo37 but not the CT5/VCU platform?

**gminfo37 (Silverado, AAOS 12):** `VMSPlugin` on gminfo37 calls only `activeRouteData.setManeuverIcon(uri)` for the imminent turn — it does NOT call `setManeuverType()`. The cluster ECU receives only a URI. Something downstream (likely an OnStar TBT side-channel) resolves the URI to PNG bytes and pushes them to the cluster MCU over a separate channel. The app's `ClusterIconShimProvider` claims the orphaned `GoogleTemplatesHost.ClusterIconContentProvider` authority — when that URI is resolved, the app's composed bitmap is returned and appears on the cluster display.

**CT5 / VCU platform (AAOS 14):** `VMSPlugin` was updated to call **both** `setManeuverIcon(uri)` AND `setManeuverType(ManeuverTypeToTurnType(...))`. The cluster render pipeline on CT5 is a **separate QNX 7.x safety-domain VM** (`/usr/bin/hmiCluster/HMIC`, Altia HMI toolkit). The cluster wire proto (`Protos$ManeuverDataUpdate_status`) has **no bitmap field** — only a `maneuverIconType` enum (~500 named glyphs). The QNX side selects its own sprite from the Altia texture atlas based on the enum. No bitmap ever reaches the cluster; the app's URI/bitmap is bypassed entirely.

**Firmware-verified 2026-06-06:** Confirmed against CT5 `Radio-IVE-86384258-AAOS14-UQBM` and Silverado Y181 partition images. The `ClusterIconContentProvider` authority is orphaned (unclaimed) on both platforms. On CT5 the bypass is structural (enum-only proto wire); on Silverado it is not.

**Play Store conflict:** The `ClusterIconContentProvider` authority is now locked to the original publisher on the Play Console. Forks using a different `applicationId` get a derived authority (`<applicationId>.ClusterIconContentProvider`) which GM AAOS never calls — those builds get text-only cluster navigation. The `sideload` build flavor preserves the correct authority for direct APK installation.

---

## 10. What causes the severe disconnect loop?

The observed extreme-instability scenario (adapter disconnects + permission re-requests in a continuous loop) has one confirmed primary cause from logs:

**Confirmed: dual `start()` re-entrancy race** (POTATO `20260412/logcat_20260412_133523.log`, process PID 3286, worker TID 3779 vs. main TID 3286). The logcat shows two interleaved permission/claim sequences:
```
TID 3779: [USB] Requesting USB permission for /dev/bus/usb/001/011...   (line 32843)
TID 3286: [USB] Requesting USB permission for /dev/bus/usb/001/011...   (line 33034)
TID 3779: [USB] Claimed interface 0: IN=81 OUT=1                        (line 33211)
TID 3286: [USB] Claimed interface 0: IN=81 OUT=1   ← second claim       (line 33223)
```
(Logcat prints numeric thread IDs, not `USB-ReadLoop-*` names.) The TID-3779 worker then fails its init messages (`Send incomplete: -1/N bytes`) — it is writing to a handle now claimed by TID 3286, which succeeds. The adapter receives two connection attempts simultaneously and its firmware responds unpredictably to the first (failed) attempt.

**LMK does not kill carlink.** Across the POTATO logcat corpus (22 day-directories / 50 files, Feb–May 2026), the LMK daemon (PSI-based) was never observed targeting carlink. The only observed carlink kills were `ActivityManager`-initiated and development-related (install/force-stop/package-removal), not OOM events. See Section 13 for the memory detail and the foreground-vs-backgrounded OOM-adj caveat.

**High-restart days (38 restarts on May 5):** The CCPA adapter logs (POTATO `cpc200/`) show these correlate with repeated USB cable partial-disconnects, not OS resource pressure. The adapter UART logs `[USB Disconnect]` / `[USB Connect]` pairs with <2 s spacing during these events.

**Other contributing factors:**
- `Thread.sleep(200)` in the device-open/start path (`CarlinkManager.kt:940`, the firmware-settle delay right after "Adapter found — opening…") is shorter than the adapter's firmware-ready floor (~270–287 ms on this hardware). Init messages sent before the firmware is ready fail with `-1/N bytes`.
- `usbDevice = device` is assigned before `openWithPermission()` succeeds. If permission is denied or the device disappears, the stale handle is never cleaned up (`CarlinkManager.kt` line 888).

---

## 11. What SELinux restrictions apply on gminfo37?

**Y181 is enforcing; Y177 is permissive** (boot cmdline from `86331652` boot image):
```
enforcing=0 androidboot.selinux=permissive    ← Y177 (security regression)
# Y181 restores full enforcement
```

Y177's permissive mode is caused by the VIP MCU security function being replaced with a 4-byte stub (CVE-2024-53104 + CVE-2024-36971 exploitable). Y181 restored full enforcement. Rollback from Y181 to Y177 is blocked by the GHS rollback counter.

**USB device access for sideloaded apps** (from `plat_sepolicy.cil`):

Sideloaded apps (`untrusted_app`) are in `base_typeattr_196` which grants:
```
(allow base_typeattr_196 usb_device (chr_file (ioctl read write getattr)))
```
**`open` is deliberately excluded.** A sideloaded app cannot directly open `/dev/bus/usb/*` device nodes. This is intentional: `UsbManager.openDevice()` has `system_server` open the node and pass the file descriptor. The app uses the fd for ioctls/reads/writes after `UsbDeviceConnection` is established.

**No `zeno.carlink`, `carlink`, or `usbmountreceiver` rules exist** in either `plat_sepolicy.cil` or `vendor_sepolicy.cil`. The app operates under standard `untrusted_app` domain rules.

**USB port role is CAN-controlled.** A dedicated `usb_roleswitch` SELinux domain (communicates over CAN socket) writes to `sysfs_usb_role_writeable` — the USB port can switch between host (CarPlay/AA adapter) and device (ADB) mode programmatically based on vehicle CAN state.

**`ro.boot.selinux=permissive` vs. `getenforce` discrepancy:** On Y181, `all_properties.txt` reports `[ro.boot.selinux]: [permissive]` (the bootloader cmdline value) while `selinux_status.txt` reports `Enforcing` at runtime. This is **standard Android behavior for `user` builds**, not a GM-specific mechanism: a production `user` build (`ro.build.type=user`, `ro.debuggable=0` — both true here) ignores the `androidboot.selinux=permissive` cmdline and boots Enforcing regardless. No explicit `setenforce` override exists in any init.rc on the partition (`grep -i setenforce` across the vendor/system init files returns nothing). Treat Y181 as Enforcing at runtime.

---

## 12. Firmware versions

| Field | Y175 (Jun 2024) | Y177 (Mar 2025) | Y181 (Jul 2025) |
|-------|-----------------|-----------------|-----------------|
| Build | `W213E-Y175.5.2-SIHM22B-383.1` | `W231E-Y177.6.1-SIHM22B-499.2` | `W231E-Y181.3.2-SIHM22B-499.3` |
| Kernel | 4.19.283 | 4.19.305 | 4.19.305 |
| Security patch | 2024-05-05 | 2025-02-05 (build dated Mar 5 2025) | 2025-06-05 |
| SELinux | Enforcing | **Permissive** ⚠️ | Enforcing |
| VIP security fn | Full (906 B) | **4-byte stub** ⚠️ | Full (906 B) |
| Rollback | — | Y181→Y177 blocked | — |

**Y177 is a security regression.** Update to Y181. Rollback from Y181 is enforced by the GHS hypervisor rollback counter in the `misc` partition.

> Provenance: Y181 and Y177 build IDs, kernels, security-patch levels, and SELinux states are verified against the partition images (Y181 `86331654`, Y177 `86283152`/`86283154`). The **Y175 column** and the **VIP security fn / Rollback** rows come from separate firmware reverse-engineering and are **not** verifiable from the ADB dump or the Y181/Y177 images alone — treat them as RE-sourced, not dump-confirmed.

Audio/video/codec configuration files are **identical between Y177 and Y181**. App behavior differences between the two builds are limited to SELinux enforcement.

---

## 13. System resource constraints

**Memory baseline** (from 964 POTATO metric samples across Feb–May 2026):
```
MemTotal:     5,663,424 KB  (5.4 GB; minor variants 5,663,432 / 5,663,456 also occur)
MemFree:      wide — 95 MB to 3.4 GB; ~77% of samples below 1.2 GB
MemAvailable: wide — 37 MB to 3.49 GB; ~82% of samples below 2.8 GB
SwapTotal:    0             (no swap — OOM killer is the only relief valve)
Committed_AS: ~78 GB        (from ADB meminfo.txt: 78,348,176 kB; ~14× overcommit, normal for Android)
```
Note: MemFree/MemAvailable are highly variable, not a tight band — the system routinely runs with MemFree well under 1 GB and MemAvailable under 2 GB without instability (LMK is PSI-driven, not watermark-driven). Don't treat any narrow range as "the baseline." Committed_AS is from the ADB `meminfo.txt` snapshot, not the POTATO metric files (which don't capture it).

**Largest RSS consumers** (from `processes.txt`, enumeration time):
```
com.google.android.apps.automotive.templates.host:renderer_service  446,896 KB (436 MB)
system_server                                                        377,092 KB (368 MB)
com.google.android.gms.persistent (user 10)                         297,696 KB (291 MB)
com.google.android.gms.persistent (user 0)                          290,064 KB (283 MB)
com.gm.homescreen                                                    212,128 KB (207 MB)
```

**Carlink app profile** (from POTATO metric snapshots, Feb–May 2026):
- RSS (active streaming, H264 + audio): 246–268 MB; fresh-start captures ~193–230 MB, with some idle/background captures dipping to ~91–99 MB
- PSS: 24–136 MB depending on state (≈136 MB peak during active streaming)
- Threads: 38–48 while foreground/streaming; ~24 when backgrounded
- OOM score adj: **0 only while top-app/foreground.** When carlink is backgrounded (user switches to another app), adj rises sharply — values of **50, 100, 102, 103, 200, and 905** were observed, with OOM score reaching **935**. So carlink is protected *only while in the foreground*; backgrounded, it is squarely in LMK kill range.
- **Not observed killed by LMK** in the corpus (see LMK note below) — but this is because memory never got tight enough *while carlink was backgrounded*, not because it is structurally immune.

Representative meminfo (20260505_222110, active streaming session — verified verbatim):
```
** MEMINFO in pid 3744 [zeno.carlink] **
  Java Heap:    19468 PSS /  41436 RSS
  Native Heap:  24184 PSS /  26936 RSS
  Code:         71448 PSS / 178300 RSS
  TOTAL PSS:   135941    TOTAL RSS:   263084    SWAP: 0
```

**LMK configuration:** PSI-based (`ro.lmk.psi_complete_stall_ms=100`, `ro.lmk.psi_partial_stall_ms=100`). No fixed MemFree watermarks. LMK targets the highest-adj processes first; a foreground (adj 0) carlink is not targeted, but a backgrounded carlink (adj ≥50) is eligible.

**Never observed killed by LMK** across the POTATO logcat corpus (22 day-directories / 50 files). The only carlink kills seen were `ActivityManager`-initiated and all development-related (`installPackageLI`, force-stop from a pid, package removal) — never an OOM/`lmkd` kill.

**No swap.** Memory pressure has no swap escape valve. Under extreme saturation, the LMK could kill `CarlinkMediaBrowserService` (lower priority) while `MainActivity` survives, causing a disconnect without a reconnect trigger — though this was not observed in the corpus.

**Debug APK trick** (revision [85]): Setting `android:appCategory="homescreen"` replaces `GMHomeScreen` and frees the homescreen's ~207 MB RSS. For development/debug use only.

---

## 14. Key code locations for gminfo37-specific behavior

| Topic | File | What to look for |
|-------|------|-----------------|
| Platform detection | `PlatformDetector.kt` | `isGmInfo37()` (:93), `requiresImmersiveDefaults()` (:110), `requiresGmAaosAudioFixes()` (:149), `requiresIntelMediaCodecFixes()` (:142) |
| Audio profile + FAST/perf mode | `AudioConfig.kt` | `GM_AAOS` profile (:68), `PERFORMANCE_MODE_NONE` (:72), `forPlatform()` three-way branch (:82) |
| ALERT/RINGTONE bus routing | `DualStreamAudioManager.kt` | `purposeToAttributes()` (:564) — `USAGE_VOICE_COMMUNICATION_SIGNALLING` mapping (:577–578) |
| Intel VPU decoder | `H264Renderer.java` | `initCodec()` (:504) — `createByCodecName(preferredDecoderName)` (:513) |
| Periodic keyframe | `CarlinkManager.kt` | `scheduleDelayedKeyframe()` (:3009–3029) — `delay(2500)` + `delay(30000)` |
| Icon compose off-thread | `ComposedIconStore.kt` | `IconComposer` daemon thread (:117), `composeGeneration` supersede |
| Display mode default | `DisplayModePreference.kt` | `platformDefault()` (:68) → `requiresImmersiveDefaults()` |
| API 29 compat | `WindowMetricsCompat.kt` | `currentWindowMetrics` (:40) fallback for gminfo3.7 on API 29 |
| Cluster shim authority | `ClusterIconShimProvider.kt` | `AUTHORITY = BuildConfig.CLUSTER_ICON_AUTHORITY` (:49) |
| Homescreen visibility | `AndroidManifest.xml` lines 163–164 | `com.gm.homescreen.visibility=HIDDEN` on MBS and ClusterService |
| USB host feature flag | `AndroidManifest.xml` lines 38–40 (comment at 33) | `android.hardware.usb.host required=false` |
| OEM icon gate | `MainActivity.kt` | `oemIconVisibleForPlatform = !requiresImmersiveDefaults()` |

---

## 15. Confirmed non-issues on gminfo37

| Claim | Reality |
|-------|---------|
| Video freezes / black screen | Not observed in POTATO corpus on Y181. Intel VPU decoder handles CarPlay H.264 correctly with the periodic keyframe safety net. |
| Inaccessible USB device nodes (SELinux blocks) | `untrusted_app` has `ioctl read write getattr` on `usb_device` without `open`. `UsbManager` path (open via `system_server`) is unaffected. |
| LMK kills carlink | Never observed across the corpus (22 dirs / 50 files) — but only because memory never got tight while carlink was backgrounded. Foreground adj is 0; backgrounded adj rises to ≥50 (up to 905), which IS killable. Not structurally immune. |
| Y177 → Y181 audio behavior change | Audio/codec config is identical between Y177 and Y181. Only SELinux enforcement differs. |
| `isBroxton` detecting gminfo37 | Always `false`. Use `isGmInfo37()` or `isGmAaos()` instead. |
| 12 audio buses | **8 buses** confirmed (bus0–bus7 only; no bus8+ in `car_audio_configuration.xml`). VolumeGroup count is 6 (VG0–VG5), not 12. |
| Vulkan in use | Vulkan 1.0.64 driver exists but zero runtime usage observed. GLES 3.2 only. |

---

---

## 16. Display geometry: windowed mode vs. immersive

The 2024 Silverado ICE display is **2400×960 physical**. The screen is divided into four regions by GM's SystemUI (nomenclature taken directly from the decompiled GM code — see Section 19). These boundaries were measured pixel-for-pixel from a true 1:1 2400×960 device screenshot (`carlink_native/screenshots/gminfo/gminfo_homescreen.png` and `AA_small.png`).

### Measured screen layout (1:1 screenshot)

```
 x=0        x=190                              x=1607        x=2400
 ┌──────────┬─────────────────────────────────┬─────────────┐  y=0
 │          │            Status Bar (118 px tall)            │
 │          ├─────────────────────────────────┬─────────────┤  y=118
 │   App    │                                 │             │
 │   Dock   │   App / projection content      │  CardView   │
 │  190 px  │   1417 px  ×  842 px             │   793 px    │
 │          │   (windowed, CardView shown)    │   (right)   │
 │          │                                 │             │
 └──────────┴─────────────────────────────────┴─────────────┘  y=960
```

| Region | GM code term | Pixel extent | Measured size | Cross-check |
|--------|-------------|--------------|---------------|-------------|
| **Status Bar** (top) | `statusBarHeight` | y 0–118 | 118 px tall | yellow divider at y≈114–117; app inset `T:118` |
| **App Dock** (left) | App Dock / launcher rail | x 0–190 | 190 px wide | content edge at x=190; app inset `L:189` |
| **App / projection content** | (host window) | x 190–1607 | **1417 × 842** | app surface logged `1416×842` |
| **CardView** (right) | `CardView` (`com.gm.cardview.view.CardView`) | x 1607–2400 | 793 px wide | `2400 − 190 − 1417 = 793` |

`190 + 1417 + 793 = 2400` and `118 + 842 = 960` — the screenshot geometry closes exactly against the app's logged window metrics.

**The 1416 content width is confirmed from three independent sources:**
1. Screenshot measurement: content region x 190→1607 = **1417 px**
2. carlink logcat: AUTO-mode rendered surface = **1416×842** (9 occurrences)
3. GMHomeScreen layout dimen: `containerWidth = 1133dp`, applied only when `densityDpi == 200` (`HomeScreenActivity.java:420`, `dimens.xml:199`). At density 1.25 (200 dpi / 160 baseline), `1133 × 1.25 = 1416 px`.

### The carlink window log, decoded

From the app's own `[WINDOW]` log line (POTATO logcat `20260211/logcat_20260211_161504.log:46785`):
```
02-11 16:15:02.672 I CARLINK: [MAIN] [WINDOW] Bounds: 2400x960, Usable: 2211x842,
                              Insets: T:118 B:0 L:189 R:0, DisplayMode: ...
```
- `Bounds 2400×960` — full panel.
- `Usable 2211×842` — bounds minus the two **window insets** the WindowManager reports: `T:118` (Status Bar) and `L:189` (App Dock). `2400 − 189 = 2211`, `960 − 118 = 842`.
- `R:0` — **the CardView is NOT reported as a window inset.** It is composited on the right by GMSystemUI independently of the app's window. So the app's window spans 2211 wide even when the CardView occupies the rightmost 793 px.

This is the crux: the **App Dock** (190 px, left) IS a real WindowManager inset; the **CardView** (793 px, right) is NOT. In AUTO resolution mode the app measures the visually-unobstructed region and fits its `SurfaceView` to **1417 (≈1416) wide** to avoid drawing under the CardView, even though its window technically extends to 2211.

### The three real states

1. **CardView shown (normal windowed)** — rendered video surface **≈1416×842**, occupying x 190–1607. This is what GM native CarPlay and every non-whitelisted 3P app (incl. Carlink) gets. `[UI] [CARLINK_RESOLUTION] ... 1416x842 (mode=SYSTEM_UI_VISIBLE)` appears 9× in the corpus.
2. **CardView collapsed, bars still up** — usable **2211×842** (App Dock + Status Bar insets only). Surfaces of **2210×960** and **1604×960** appear as transitional/collapsed states. Reaching this state requires GMSystemUI to drop the CardView (Android Auto auto-collapse, or the Google Maps user toggle — Section 19).
3. **Full immersive** — **2400×960**, zero insets (`Cutout: T:0 B:0 L:0 R:0`). All chrome gone. Carlink reaches this with `FULLSCREEN_IMMERSIVE`.

The clearest single-session walk through these states is `20260420/logcat_20260420_063729.log` (PID 3311): windowed `1416×842` ↔ `2210×960` at 06:37, then `FULLSCREEN_IMMERSIVE 2400×960` at 06:38:58.

**GM-side corroboration** of the panel height (`CardView_InitManager`, `20260420/logcat_20260420_063729.log`):
```
04-20 06:36:55.561 D CardView_InitManager: initCardView: mDisplaySize = 2400,
                              cardViewHeight = 842, statusBarHeight = 118, heightPixels = 960
```
The CardView is full-height below the Status Bar (`cardViewHeight = 842 = 960 − 118`). Its **793 px width** is the screenshot-measured value (`2400 − 190 dock − 1417 content`); confirming the exact width constant in the decompiled `CardView_InitManager` source is in progress.

> Correction history: earlier drafts mislabeled this geometry twice — first as "1416×960 with a ~984 px right panel," then as a "189 px left nav rail." Both wrong. Verified from the 1:1 screenshot: the **189 px left inset is the App Dock**; the **CardView is 793 px on the right** and is **not** a window inset; the restricted windowed content area is **1417×842**; and `2211×842` is the CardView-collapsed (not restricted) state.

### How each app type reaches full width

The CardView is dropped — letting an app expand into the right 793 px toward 2400×960 — only for apps handled by `GMSystemUI.apk` (see Section 19 for code proof):
- **Android Auto** — auto-collapses the CardView (whitelist; `VisibilityController` never reads the user toggle for AA). No user action.
- **Google Maps (full app)** — exposes a *user toggle* (`CardViewControl` button) to collapse the CardView, gated to the `com.google.android.apps.maps` package. User's choice, persisted in `NAV_SCREEN_FULL`.
- **GM native CarPlay & all 3P apps (incl. Carlink)** — not whitelisted; CardView stays shown → restricted to the **1417×842** content region.

A 3P app like Carlink can independently hide the **OS** Status Bar and App Dock via `WindowInsetsController` (reclaiming the 118 px top and 190 px left insets), and by requesting `FULLSCREEN_IMMERSIVE` it reaches the full `2400×960` surface seen in the logs. What it *cannot* do through Android APIs is command GMSystemUI to drop the CardView — that is gated by the GMSystemUI whitelist, which 3P apps cannot join without patching the APK.

The video decoder (`OMX.Intel.hw_vd.h264`) is configured to decode at **2400×960** to match the full physical panel. Source: `CarlinkManager.kt` H264 path configuration.

---

## 17. VHAL property availability for developers

**From `vehicle_properties.txt` (ADB dump Apr 2026, 535 total properties: 49 SYSTEM + 486 VENDOR):**

**Available (SYSTEM):**
- `PERF_VEHICLE_SPEED` (0x11600207) — continuous, actual speed in m/s
- `PERF_VEHICLE_SPEED_DISPLAY` (0x11600208) — continuous, display speed (respects locale units)
- `PERF_STEERING_ANGLE` (0x11600209) — continuous
- `PERF_ODOMETER` (0x11600204) — continuous
- `CURRENT_GEAR` (0x11400400) — on_change
- `GEAR_SELECTION` (0x11400401) — on_change
- `IGNITION_STATE` (0x11400409) — on_change
- `FUEL_LEVEL` (0x11600307) — continuous
- `FUEL_LEVEL_LOW` (0x11600308) — continuous
- `NIGHT_MODE` (0x11200407) — on_change
- `INFO_VIN` (0x11100101) — static
- `INFO_MAKE` (0x11100102) — static
- `TIRE_PRESSURE` (0x17600309) — continuous, 4 wheel areas
- `VEHICLE_SPEED_DISPLAY_UNITS` (0x11400605) — READ_WRITE
- Cluster display properties: `CLUSTER_SWITCH_UI`, `CLUSTER_REPORT_STATE`, `CLUSTER_REQUEST_DISPLAY`, `CLUSTER_NAVIGATION_STATE`, `CLUSTER_HEARTBEAT` (all READ_WRITE, seat-area ID 0x15200505–0x1520050a)

**Absent (will return error or no results):**
- `RANGE_REMAINING` — not present (ICE-only platform)
- `AP_POWER_STATE_REQ` — not present
- `CLUSTER_DISPLAY_STATE` (0x15200506) — not present (cluster uses VMS, not Cluster HAL state machine)
- Standard HVAC CarHvacManager IDs (0x15400101, 0x15600104, 0x15200106, 0x15200138, etc.) — **ALL ABSENT**. GM uses non-standard VHAL IDs (0x15600502/503 for temperature, 0x15400500/501 for fan speed/direction). CarHvacManager API does not work on gminfo37.
- Standard GNSS properties — **ABSENT**. GPS uses `vendor.gm.gmlocation@1.0::IGmLocation` HAL + `com.gm.server.location.NonMaskableLocationService` binder service. Standard `android.hardware.gnss@X.X` HIDL is never registered.
- `android.hardware.sensors@2.0/2.1` — **ABSENT**. No sensor HAL served. Accelerometer, gyro, and related sensors are unavailable to Android apps via the standard SensorManager API.

**486 VENDOR properties** (0x21100100–0x25400179) are present but undocumented — these contain the bulk of GM-specific telemetry, full HVAC control, OnStar integration, diagnostics, and calibration.

---

## 18. Cluster HAL implementation

**Standard `CarClusterManager` API does not work on gminfo37.** From `car_service.txt` lines 1053–1055:
```
*Cluster HAL*
mIsCoreSupported:false
mIsNavigationStateSupported:false
```

The cluster instead uses **VMS (Vehicle Map Service)**, routed through `com.gm.vmsplugin/.VMSClusterService` which is the registered `InstrumentClusterRendererService` (verbatim from `car_service.txt` lines 553–557):
```
bound with renderer: true                                                                    # line 553
renderer service: android.car.cluster.renderer.IInstrumentCluster$Stub$Proxy@7305444         # line 554
mRenderingServiceConfig: com.gm.vmsplugin/.VMSClusterService                                  # line 556
mIInstrumentClusterNavigationFromRenderer: ...IInstrumentClusterNavigation$Stub$Proxy@9d8c92d # line 557
```

`VMSClusterService` receives turn-by-turn navigation events from the app (via `IInstrumentClusterNavigation`) and converts them into VMS layer updates. Those VMS layer updates are subscribed by `system_server` (uid 1000) across channels 10002 and 10003 and forwarded to the GHS INTEGRITY cluster partition via the hypervisor VMS bridge.

This is why the CarClusterManager API (`requestDisplay()`, `sendNavigationState()`, `reportState()`) returns no-op on gminfo37, and why third-party navigation apps that use the cluster HAL path (not the VMS/IIC path) show nothing on the cluster.

**VHAL cluster properties** (`CLUSTER_NAVIGATION_STATE` at 0x15200509) exist in the property list but are READ_WRITE on the Android side only as a pass-through bridge layer. The actual cluster rendering decision is made by the QNX/GHS partition, not by Android CarService.

---

## 19. Window modes and the GMSystemUI CardView whitelist

> Nomenclature note: this section uses GM's own code terms. The right-side panel is the **CardView** (`com.gm.cardview.view.CardView`); the collapse/expand button is **`CardViewControl`**; the persisted state is **`NAV_SCREEN_FULL`**; the dispatcher is **`VisibilityController`**. "Widget panel" is not a GM term and is avoided.

### Two separate visibility systems

On gminfo37 there are **two independent layers** of screen-space control:

| Layer | What it controls | Mechanism | Who enforces it |
|-------|-----------------|-----------|-----------------|
| Android window insets | Status Bar (118 px top) + App Dock (190 px left) | `WindowInsetsController.hide(systemBars())` per app window | Android `WindowManagerService` |
| GM **CardView** | Right-side **793 px** panel (HVAC, audio quick-controls, home shortcuts) | Top-activity class-name lookup in GMSystemUI `VisibilityController` whitelist | `GMSystemUI.apk` |

A 3P app can independently control the first layer. Hiding the Android Status Bar / App Dock does NOT dismiss the GM CardView, and being whitelisted for CardView dismissal does not by itself hide the Android insets — the two are enforced by different processes.

### GMSystemUI CardView whitelist

**Source:** `GMSystemUI.apk` (`/system/priv-app/GMSystemUI/GMSystemUI.apk`, extracted June 2026), class `com.gm.cardview.utils.WhiteList`, decompiled by jadx as `d.java` in `classes2.dex`. The whitelist is a hardcoded Java static initializer — **there is no external XML config file**. Dropping a file in `/system/etc/` does not change it.

`com.gm.cardview.controller.VisibilityController` (decompiled `x.java`) checks the foreground activity class name against eight static lists on every top-of-stack change:

| List | Method | Effect when matched |
|------|--------|---------------------|
| **a** — Dialog overlays | `b.p()` | Floats above the CardView; CardView remains visible |
| **b** — ImmersiveMode | `b.s()` | CardView is **hidden**; full-width display |
| **c** — Full-screen apps | `b.t()` | CardView is **hidden**; full-screen app mode |
| **d** — Media/audio packages | `b.F()` | Triggers audio card transitions in the CardView |
| **e** — CardView activities | `b.l()` | App is displayed **inside** the CardView (limited view) |
| **f** — CardView packages | `b.m()` | Package is eligible to participate in CardView mode |
| **g** — Do-not-hide | `b.B()` | Presence blocks any CardView-hiding action |
| **h** — Telecom overlays | `b.A()` | Treated as telecom/TTS overlay |

**List c (Full-screen, CardView hidden) — confirmed members:**
```java
"com.gm.offroad.presentation.main.MainActivity"
"com.gm.hmi.androidauto.ui.activities.AndroidAutoProjectionActivity"   // ← GM ANDROID AUTO
```

**List b (ImmersiveMode, CardView hidden) — confirmed members:**
```java
"com.gm.hmi.energy.ui.activity.CloseableFullScreenActivity"
"com.gm.teenmode.presentation.seatbelt.SeatBeltRestrictionModeActivity"
"com.gm.hmi.onstarui.ui.activities.PopUpActivity"
"com.gm.hmi.onstarui.ui.activities.EventDetectedCallActivity"
"com.gm.hmi.seatstatus.ui.activities.SeatStatusPaneActivity"
"com.gm.offroad.presentation.crabmode.CrabModeActivity"
"com.gm.gmbugreport.report.GMSelectModeActivity"
"com.google.android.apps.gsa.aae.contactupload.activity.ContactUploadNoticeActivity"
// + any activity in com.gm.drivemode package
```

**List e (CardView activities, displayed inside the CardView) — confirmed members:**
```java
"com.google.android.maps.LimitedMapsActivity"                             // Google Maps card
"com.google.android.apps.gmm.car.embedded.activity.LimitedMapsActivity"  // Maps embedded
"com.gm.hmi.radio.ui.activities.CardViewNowPlayingActivity"
"com.gm.hmi.trailer.ui.activities.TrailerCardViewConnectionActivity"
"com.gm.hmi.energy.ui.activity.CardViewActivity"
"com.gm.offroad.presentation.cardview.CardViewOffRoadActivity"
"com.gm.homescreen.app.AnalogClockActivity"
```

**Special inline package checks (direct `equals` / `contains` calls in `b.java`):**
- `com.gm.camera` → always no-CardView
- `com.google.android.apps.maps` → Google Maps package check
- `com.google.android.carassistant` → Google Car Assistant
- `com.gm.hmi.androidauto` → Android Auto package (see List c above)

### Where GM CarPlay appears (and doesn't)

**GM's native CarPlay implementation** (`com.gm.domain.server.delayed:CarplayService`, `GMCarPlaySrc.apk`) is **absent from all eight lists** and all inline package checks.

This means when the GM native CarPlay UI activity is in the foreground, `VisibilityController.q()` returns false → the fall-through arm keeps the CardView shown (`s(true)`) → CarPlay is confined to the **1417×842** content region between the App Dock (190 px left) and the CardView (793 px right), below the 118 px Status Bar. See Section 16 for the measured geometry. The CarPlay video rendered by `GMCarPlaySrc` is constrained to that region.

**Android Auto** (`com.gm.hmi.androidauto.ui.activities.AndroidAutoProjectionActivity`) is in **List c** (full-screen). When Android Auto is active, the CardView is auto-collapsed (`VisibilityController.g()` at `x.java:146`) and the AA projection expands into the right 793 px.

**Google Maps** has **two distinct modes** on gminfo37, which is why it appears in multiple whitelist entries:
1. **CardView mini-app** — `LimitedMapsActivity` (List e) renders a compact Maps view **inside** the CardView alongside the homescreen content.
2. **Full app** — the full Google Maps app (`MapsActivity`/`NavigationActivity`) can be launched as a regular activity, and when it is foreground it exposes a user control to **collapse/expand the CardView**.

Maps is the only navigation app on the platform with this dual CardView/full-app behavior and the user-facing CardView-collapse toggle. The exact code path is proven below.

### Code proof: the three CardView behaviors (decompiled `GMSystemUI.apk`)

All three behaviors live entirely in `GMSystemUI.apk`, package `com.gm.cardview`. There is **no separate Maps companion APK** — the windowed Maps card is Google Maps' own embedded `LimitedMapsActivity`. Obfuscated class names map to originals as listed; line numbers are from the jadx decompilation.

**The toggle state is a per-user `SharedPreferences` int, `NAV_SCREEN_FULL<uid>`** (store `GMInfo30_Shared_Preferences`), managed by `SPManager` (`com.gm.cardview.model.l`):
```java
// model/l.java  (SPManager)
c()  // :31  reads NAV_SCREEN_FULL<uid>, default 1
e()  // :47  returns (1 == c())  → true == "nav screen FULL" (Maps full, card collapsed)
g(int)  // :65  writes it (the toggle setter)
```

**Maps detection** in `CardViewComponentUtils` (`com.gm.cardview.utils.b`) — this is the `b.v()` inline check:
```java
// utils/b.java  (CardViewComponentUtils)
b()  // :141  = "com.google.android.apps.maps"
v(s)  // :299  = b().equals(s)                      ← the Google Maps package check
w(stack)  // :303  = v(pkg) || y(stack)             ← "is Maps foreground"
i(cn)  // :209  = "com.gm.hmi.androidauto".equals(pkg)   ← Android Auto check
q(stack)  // :265  true for AA / OffRoad / Maps     ← "full-screen-capable"
```

**Behavior 1 — Maps gets a USER toggle.** The toggle is an `ImageButton` widget `CardViewControl` (`com.gm.cardview.view.CardViewControl:42`, drawables `cardview_collapse_control_chevy.xml` / `cardview_expand_control_chevy.xml`). Its click handler (`com.gm.cardview.view.j.a(boolean)`, an inner class of `CardView`) is **gated to Maps only**:
```java
// view/j.java  (CardView$onCardControlCollapse)  :17
public void a(boolean z2) {
    if (!CardViewComponentUtils.w(model.p.F().E())) {
        Log.d("CardView_Window", "Prevent the Collapse or Expansion button clicked when the top stack is not a Map");
        return;                              // ← NO-OP unless Maps is foreground
    }
    int iC = SPManager.a().c();              // read NAV_SCREEN_FULL
    if (iC == 1) {                           // FULL → go windowed: show card, persist 0
        this.f40246a.m0(0); SPManager.a().g(0);
        this.f40246a.f40223x.s(true); this.f40246a.f40223x.r(false, true);
        return;
    }
    this.f40246a.m0(1); SPManager.a().g(1);  // windowed → FULL: hide card, persist 1
    this.f40246a.f40223x.r(true, false); this.f40246a.f40223x.s(false);
}
```
`CardView.m0(int)` (`view/CardView.java:233`) flips the immersive system-bars flag `16384` on/off. The control button is only *shown* in the two Maps branches of `VisibilityController.d()`:
```java
// controller/x.java  (VisibilityController.d)  :108
public void d(stack) {
    if (stack == null) return;
    if (!CardViewComponentUtils.w(stack))      r(false, false);   // NOT Maps → no control
    else if (SPManager.a().e())                r(true, false);    // Maps + FULL → show collapse btn
    else                                       r(false, true);    // Maps + windowed → show expand btn
}
```

**Behavior 2 — Android Auto AUTO-collapses (no toggle).** In `VisibilityController.g()` (onCardViewShowOrHide):
```java
// controller/x.java  (VisibilityController.g)  :146
if (CardViewComponentUtils.q(stack)) {                 // full-screen-capable (AA / OffRoad / Maps)
    if (!CardViewComponentUtils.w(stack) || SPManager.a().e()) {
        Log.d(..., "onCardViewShowOrHide : Top is Map or Off-Road or Android Auto");
        d(stack);     // AA: !w()==true → d() → r(false,false): NO control button
        s(false);     // hide card
        return;
    }
    ...
}
```
For AA, `b.w()` is **false** (not Maps), so `!w(...)` is true and the branch is taken **without ever reading `NAV_SCREEN_FULL`** → card hidden, no toggle button. This is the structural contrast with Maps, which reaches the same `q()` block but has `w()==true` and therefore consults the user's `NAV_SCREEN_FULL` state.

**Behavior 3 — all other 3P apps locked to windowed (fall-through).** For any package where `b.q()` is false, the `:146` block is skipped and control falls through to the default arm that **keeps the card visible**:
```java
// controller/x.java  (VisibilityController.g)  :159+
Log.d(..., "onCardViewShowOrHide : Current is not full-screen(control by CardView)");
...
if (CardViewComponentUtils.J(model.p.F().J())) {       // :177
    s(true);   // ← KEEP CARDVIEW VISIBLE (windowed)
    d(stack);
}
```
Because `b.w()` is false for these apps, the toggle handler (`view/j.java:18`) would reject any button press anyway — a 3P app can never collapse the CardView.

| App | Foreground detect | Branch | Toggle button | CardView |
|-----|-------------------|--------|---------------|----------|
| Maps (full) | `w()`=true, `e()`=true (`x.java:115`) | `r(true,false)` | collapse shown | hidden |
| Maps (windowed) | `w()`=true, `e()`=false | `r(false,true)` | expand shown | shown |
| Android Auto | `q()`=true, `w()`=false (`x.java:146`) | `r(false,false)` | **none** | hidden, auto |
| GM CarPlay / 3P | `q()`=false | fall-through `s(true)` (`x.java:180`) | none (handler no-op) | shown, locked |

### Cross-process channel: how the homescreen learns of collapse/expand

`GMSystemUI` (`VisibilityController`) writes the panel state to **`Settings.Global`** keys; `GMHomeScreen` observes them — there is no broadcast or binder for this. `HomeScreenActivity.onCreate` (`HomeScreenActivity.java:433`) registers a `ContentObserver` on:
- `is_panel_expand` → the CardView expanded-vs-collapsed flag
- `mic_panel_expand` → mic privacy panel flag

The homescreen-side card host is the **"PorchView"**: `PorchScreenView` holds the cards, each card is a `ZoneView` wrapping an AOSP `TaskView`. `ZoneView.r()` (`ZoneView.java:91`) launches the embedded app with intent extra `"CardView"="CardView"` (the handshake telling an app it is running inside a card) and sizes it to `getBoundsOnScreen()`. The embedded Maps card component is hardcoded in `PorchScreenView.java:96`: `com.google.android.apps.maps/com.google.android.maps.LimitedMapsActivity`. The full-Maps launcher entry (`.../MapsActivity`) is classified as `AppType.GOOGLE_MAPS` (`X/a.java`, ordinal 30) and routed as the nav provider via `GMNavigationManager.getCardViewIntent()`.

### Why GM coded CarPlay and Android Auto differently

**Android Auto** — GM integrated it via `com.gm.hmi.androidauto` (a GM-authored wrapper) and put `AndroidAutoProjectionActivity` in the CardView full-screen whitelist (List c / `b.q()`). **CarPlay** — GM's implementation (`GMCarPlaySrc`) is in the system but is **absent from all whitelist lists and inline checks**, so `b.q()` returns false for it and it falls through to the 3P windowed-locked arm. Whether this was intentional or an oversight is not documented in the source. The result: GM native CarPlay occupies the same **1417×842 windowed content area** as any untrusted 3P app, despite being a system app.

### Third-party apps (Carlink) and window modes

**3P apps are not in the whitelist** and cannot be added without modifying `GMSystemUI.apk`. However, they CAN use standard Android window flags to hide the OS-level insets (Status Bar + App Dock):

```kotlin
// In MainActivity.kt — fires when displayMode == FULLSCREEN_IMMERSIVE
WindowInsetsControllerCompat(window, window.decorView)
    .hide(WindowInsetsCompat.Type.systemBars())
```

This hides the Android Status Bar and App Dock (OS-enforced WindowManager insets) but does **not** dismiss the GM CardView (GMSystemUI-controlled, not a window inset). The net result for a 3P app in "immersive" mode:
- Status Bar (118 px top): hidden (height reclaimed)
- App Dock (190 px left): hidden (width reclaimed)
- GM CardView (793 px right): still drawn unless the app reaches true `FULLSCREEN_IMMERSIVE` — in which case the logs show the surface does grow to the full `2400×960` (see Section 16). Carlink cannot command GMSystemUI to drop the CardView the way Maps/AA do; it relies on its own immersive flag.

**Distraction Optimization (DO) and 3P app window restrictions:**

The `car_ux_restrictions_map.xml` (inside `CarService.apk`, not an accessible plain-text file) defines:

| Driving state | Speed | UXR flags | Key restrictions |
|---------------|-------|-----------|-----------------|
| PARKED | — | 0x000 | none |
| IDLING | — | 0x010 | NO_VIDEO |
| MOVING | 0–2.2 m/s | 0x154 | LIMIT_STRING_LENGTH, NO_VIDEO, NO_SETUP, NO_VOICE_TRANSCRIPTION |
| MOVING | ≥2.2 m/s | 0x1ff | FULLY_RESTRICTED (all 9 flags) |

`NO_VIDEO (0x10)` applies at all non-parked states. This restriction is signaled to apps via `CarUxRestrictionsManager.onUxRestrictionsChanged()`. A DO-compliant app must stop video playback when this flag is active. System apps and Play Store-distributed apps may declare DO compliance; sideloaded (untrusted) apps cannot claim the same exemption pathway.

Non-GMNA markets omit `NO_KEYBOARD (0x08)` from the moving-speed tier (`uxr=0x1f7` instead of `0x1ff`).

---

## 20. USB port hardware and ADB access

### Physical port topology

The 2024 Silverado 2500 LTZ has **four USB ports** across **two separate USB assembly modules**. Source: ALLData wiring schematics.

**Module 1 — Center Console / Center Glove Box:**
- 1× USB Type-A
- 1× USB Type-C — underlying PCB connector is **USB mini-B (5-pin, including the OTG ID pin)**. A Type-C cable + OTG adapter that pulls the ID pin low triggers the DWC3 controller's OTG detection.

**Module 2 — Radio / Climate Control Area:**
- 1× USB Type-A
- 1× USB Type-C — underlying PCB connector does **NOT** have the ID pin wired. OTG adapters have no effect on this port; ADB device-mode cannot be triggered here via hardware.

**The ADB-capable OTG port is exclusively the Type-C on Module 1 (Center Console).** The Radio/Climate area Type-C does not support hardware OTG mode-switch.

### USB controller (from init files and kernel)

**Primary controller:** `dwc3.0.auto` — Synopsys DesignWare USB 3.0 dual-role (OTG) controller, bound at `/sys/bus/platform/devices/dwc3.0.auto/`

**PCIe XHCI host controller:** `0000:00:14.0` — Intel XHCI (host-side, for CarPlay/AA adapter), PM control set to `auto` at boot

**dabridge virtual UDC:** `dabr_udc.0` — created by Intel's Device Authentication Bridge kernel driver (compiled into `kernel.bin`, not a loadable module). When `dabridge` bridges the host port back into device mode, it exposes `dabr_udc.0` as the UDC Android sees. This is why `sys.usb.controller` resolves to `dabr_udc.0` at runtime even though init.rc hardcodes `dwc3.0.auto` — dabridge intercepts.

**Role-switch sysfs node** (from `vendor/ueventd.rc` mixin comment `usb-otg-switch/true`):
```
/sys/devices/pci0000:00/0000:00:*.0/intel_xhci_usb_sw/usb_role/intel_xhci_usb_sw-role-switch role
permissions: 0664  system  system
```
Writable by any process with `system` UID without root.

### Boot default: HOST mode

From `init.full_gminfo37_gb.rc` (vendor):
```sh
on boot
    setprop vendor.sys.usb.role host
```
This fires `usb_otg_switch.sh h` via property trigger.

### Software-controllable role switch (previously unknown)

The USB port can be switched to ADB/device mode **entirely via software**. From `init.bxtp_gm.rc`:
```
on property:vendor.sys.usb.role=host
    exec - system system -- /vendor/bin/usb_otg_switch.sh h

on property:vendor.sys.usb.role=device
    exec - system system -- /vendor/bin/usb_otg_switch.sh p
```

Full `usb_otg_switch.sh` (`/vendor/bin/usb_otg_switch.sh`):
```bash
#!/vendor/bin/sh
KERNEL_VERSION=$(uname -r)

if [[ $1 = "h" ]]; then
  echo -n -e "\x01\x3c\x4e\x1" > /dev/cbc-signals       # host mode CBC signal (byte = 0x01)
  echo "host" > /sys/class/usb_role/intel_xhci_usb_sw-role-switch/role

elif [[ $1 = "p" ]]; then
  echo -n -e "\x01\x3c\x4e\x0" > /dev/cbc-signals       # device mode CBC signal (byte = 0x00)
  echo "device" > /sys/class/usb_role/intel_xhci_usb_sw-role-switch/role
fi
```
(Kernel version branches for 4.4/4.9 omitted; 4.19 takes the `*` default branch above.)

**To switch to ADB mode via software (requires `system` UID or ADB shell):**
```sh
setprop vendor.sys.usb.role device
setprop sys.usb.config adb
```

**`/dev/cbc-signals`** is the GHS INTEGRITY hypervisor's CAN-bus signal device. The 4-byte packet format:
- Byte 0: `0x01` — message type
- Bytes 1–2: `0x3C 0x4E` — likely CAN signal address / ID
- Byte 3: `0x01` = host mode, `0x00` = device mode

This is the CAN-bus signal path the user speculated about — **it exists, and it fires on every USB role-switch regardless of whether the trigger is hardware (OTG ID pin) or software (property set).**

### Hardware trigger (OTG ID pin)

The hardware OTG ID pin path is the same end-point:
1. Type-C cable + OTG adapter → ID pin pulled low → kernel OTG driver detects device request
2. Kernel OTG driver writes to `/sys/class/usb_role/intel_xhci_usb_sw-role-switch/role`
3. DWC3 controller switches from host to peripheral mode
4. init.rc property triggers fire, setting `vendor.sys.usb.role device` etc.
5. `usb_otg_switch.sh p` fires → `/dev/cbc-signals` packet sent regardless

The hardware and software paths converge at the same sysfs node and the same CBC signal.

### dabridge bridgeport configuration

When ADB mode is active, `init.full_gminfo37_gb.rc` writes to `dabridge/bridgeport`:
```
on property:sys.usb.ffs.ready=1 && property:sys.usb.config=adb
    write /sys/bus/usb/drivers/dabridge/bridgeport ${sys.dabridge.host.portnum}
    write /sys/bus/usb/drivers/dabridge/bridgeport ${sys.dabridge.dev.portnum}
    setprop sys.usb.state ${sys.usb.config}      # resolves to "adb" in this context
```
`sys.dabridge.host.portnum` = `1-6.0` and `sys.dabridge.dev.portnum` = `1-6.3` (from ADB dump `all_properties.txt`). These are USB bus port identifiers — dabridge bridges port `1-6.0` (host-connected port, i.e., the external connector) to `1-6.3` (device authentication bridge internal port).

### Recovery behavior

In recovery mode there is **no dabridge involvement** — the role switch goes directly to the sysfs node and UDC is `dwc3.0.auto` straight. Recovery init writes `"host"` to the role node on `sys.usb.config=none`, `"device"` on `sys.usb.config=fastboot`.

### ADB permission grant chain

Without root access from a locked device:
1. Only path to switch to ADB mode externally is the hardware OTG ID pin (Type-C + OTG adapter)
2. If `adb_enabled=1` (confirmed in `settings_global.txt`) and `development_settings_enabled=1` (confirmed), ADB responds once the port is in device mode
3. `verifier_verify_adb_installs=0` means sideloaded APKs via ADB do not require Google Play Protect verification
4. `ro.adb.secure=1` means ADB requires RSA key authorization — the key accept dialog must appear on the radio's screen

Software path accessibility: `setprop vendor.sys.usb.role device` requires a SELinux context that can write `vendor.sys.*` properties. `untrusted_app` cannot. A system shell (`u:r:shell:s0` via existing ADB session) can. Bootstrap requires the hardware OTG trigger once per session to get the initial ADB shell.

---

## 21. CCPA adapter behavioral profile

**Adapter firmware:** `2025.12.29.1746CAY` (current), `2025.10.15.1127CAY` (prior). Source: UART boot banner `Software Vserion: 2025.12.29.1746` (note: typo in firmware, "Vserion").

**Adapter boot sequence** (timing from UART logs, adapter RTC stuck at 2020-01-02):

| Elapsed | Event |
|---------|-------|
| t+0 s | USB phy init (mxs_phy) |
| t+2 s | SSH daemon ready, main service start, kernel module load (`f_ptp.ko`, `g_iphone.ko`, `cdc_ncm.ko`, etc.) |
| t+3.2 s | Bluetooth FW load (`uartiw416_bt_v0.bin`, 115200→3000000 baud) |
| t+3.8–5.5 s | WiFi driver/FW load — **NXP SDIW416 (IW416)**: `wlan: Loading MWLAN driver`, then `wlan: version = SDIW416---16.92.21.p119.11-MXM5X16437.p20-GPL-(FP92)`, `Register NXP 802.11 Adapter wlan0` |
| t+6.1 s | ARMadb-driver main starts, CarPlay mode selected |
| t+7.49 s | WiFi AP started (`wlan: wlan0 AP started`) — hostapd `channel=161` (5 GHz UNII-3, ~5805 MHz), passphrase `12345678` |
| t+7.59 s | `driver->driver.name = android_usb_accessory ret=1` (USB accessory gadget bound) |
| t+~8.9 s | `CarPlay(3) connected, fd=8` (HU link established; `fd` varies 8/9/11) |

**Boot-to-HU-ready: ~9 seconds** (USB accessory driver binds at ~7.6 s; HU `CarPlay connected` shortly after). The HU app (carlink) must not attempt connection before this window or init messages will fail with `-1/N bytes`.

**Normal iPhone CarPlay connection sequence** (verbatim from UART log):
```
ARMadb-driver[Accessory_fd]: Open : w(2400) h(960) frameRate(60) iBoxversion(02) iPhoneWorkMode(CarPlay) VideoType(H264)
ARMadb-driver[Accessory_fd]: Software Version: 2025.12.29.1746CAY
[CarPlayDemoApp] wireless carplay timeout: 30, wifi connected  ← T-minus 30 countdown
[CarPlayDemoApp] wireless carplay timeout: 29, wifi connected
...
[AirPlay] AirPlay session started: From=Luis D=0x6431358c2969 T=WiFi Rec=9ms: 0/0x0 noErr
```
Time from `wireless carplay timeout: 30` to `AirPlay session started`: **6–8 seconds.**

**Abnormal disconnect signature** (confirmed disconnect trigger from HU side):
```
[W] ARMadb-driver[Accessory_fd]: Host No Response, we will reset connection!!!
[AirPlay] AirPlay session ended: Dur=41s Reason=-6753/0xFFFFE59F kConnectionErr
[D] ARMadb-driver[main]: HU Link Exit, restart
[D] ARMadb-driver[main]: _hu_link_main start
```
`Host No Response` = adapter's HU-socket heartbeat watchdog fired — the carlink process stopped sending on the accessory fd. `kConnectionErr (-6753 / 0xFFFFE59F)` is Apple AirPlay protocol error. After detecting this, the adapter restarts `_hu_link_main` (~2 s restart cycle) and waits for reconnect.

**Normal clean disconnect:**
```
[AirPlay] AirPlay session ended: Dur=Xm Xs Reason=0/0x0 noErr
```
`noErr` = phone-initiated disconnect (user switches away from CarPlay on iPhone). No adapter restart required; it returns to idle and re-advertises via Bonjour/mDNS.

**Persistent timeout loops at boot** (every boot, benign — stops after HU app connects):
```
[W] Need reset every time when timeout!!!   ← fires every 12–13 s
```

**High-restart day root cause:** On unstable days (20260505: 38 POTATO capture sessions), the timeout loops continue indefinitely because the carlink app on the radio is not connecting. The 38 "restarts" in POTATO capture count reflect SSH session drops and reconnects (`exit=255`), not adapter crashes. The adapter itself never crashes or kernel-panics in any log. The instability is on the HU-app/Android side (USB permission race, process restart, Settings/factory-reset interference), not in the adapter firmware.

*Sources: Y181 system partition `86331654` (jadx decompilation, June 2026); POTATO SBC logcat corpus (22 day-directories / 50 files, Feb–May 2026); ADB enumeration Y181 April 2026; this repo's `documents/reference/gminfo/` research tree; Carlink revision history [1]–[144]; MASTER_REFERENCE.md.*
