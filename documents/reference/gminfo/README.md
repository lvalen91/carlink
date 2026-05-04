# GM Info 3.7 Infotainment System Documentation

**Device:** GM Info 3.7 (gminfo37)
**Vehicle:** 2024 Chevrolet Silverado (ICE)
**Platform:** Intel Atom x7-A3960 (Apollo Lake/Broxton)
**Android Version:** 12 (API 32), Build W231E-Y181.3.2-SIHM22B-499.3
**Hypervisor:** GHS INTEGRITY IoT 2020.18.19 MY22-026
**Research Date:** December 2025 - February 2026
**Evidence:** ADB dumps (Y175/Y181), extracted partitions, binary analysis, 241MB logcat, 45MB CPC200 logs, 25+ capture sessions

---

## Platform Context

This documentation set is scoped to the **Info 3.x** generation of GM AAOS — specifically **Info 3.7** (`gminfo37`), the Intel Atom + GHS INTEGRITY platform shipping in 2024 GM ICE vehicles like the Silverado. GM also documents a sibling "Info 3.8" variant (per the GIS-646 CalDef Database) which is closely related but not separately characterized here.

**Successor platform (not the focus of this doc set):** GM has replaced Info 3.x with the **GM Vehicle Cockpit Unit (VCU) 1.0**, type-designated **Cockpit Integration Platform ("CIP")**, Bosch hardware model **VCUNM1** (MID variant, Qualcomm 8155; HIGH variant likely VCUNH1, Qualcomm 8195). The VCU/CIP platform uses BlackBerry QNX 7.x as the hypervisor with Android (AAOS 14 currently shipping) running as a guest VM. It is the radio shipping in newer GM EVs (Lyriq, Hummer EV, Escalade IQ, etc.) and newer ICE vehicles like the Cadillac CT5. Source: Bosch "Vehicle Cockpit Unit (VCU) Technical Description and Installers Manual," v1, 09/14/23 (`/Users/zeno/Downloads/VCUNM1.pdf`), which explicitly states *"GM VCU 1.0 is a technology upgrade for the previous generation GM Cockpit ECU platform ('Info 3.x')."*

Cross-platform notes appear inline in the documents that have meaningful differences (e.g. `projection/cluster_navigation.md` for the maneuver-icon ECU substitution behavior verified on AAOS 14). Otherwise assume content is Info 3.7-specific.

> **Caveat on RPO codes:** Vehicle RPO codes (e.g. `IOK` on the Silverado, `IVE` seen in CT5 firmware naming) are build-specific and NOT reliable platform identifiers — the same hardware can ship under different RPOs across model years and trims. Use module/platform names (`gminfo37`, `VCUNM1`) instead.

---

## Hardware Summary

| Component | Specification |
|-----------|---------------|
| CPU | Intel Atom x7-A3960 (Goldmont, 4-core, 800MHz-2.4GHz boost) |
| GPU | Intel HD Graphics 505 (Gen9, 18 EUs) |
| RAM | 6 GB DDR3L (5.66 GB visible; ~604MB reserved by GHS) |
| Storage | 64GB Samsung eMMC |
| Display | Chimei Innolux DD134IA-01B, 2400x960 @ 60Hz, ~13.4" |
| Display DPI | Physical 192.9/193.5, system density 200 (xhdpi) |
| OpenGL ES | 3.2 (Mesa 21.1.5) — Vulkan exists but NOT used at runtime |
| HW Composer | iahwcomposer (Intel Automotive HWC 2.1) |
| Video HW Codecs | 10 components (H.264/H.265/VP8/VP9/VC-1/MPEG-2, decode+encode) |
| Audio HAL | Harman "Titan" (`vendor.hardware.audio@5.0`) |
| Audio Buses | 14 output buses, 48kHz stereo PCM16 |
| Audio Transport | Ethernet AVB → NXP TDF8532 → amplifier → 4 speakers |
| VIP MCU | Renesas RH850/P1M-E (power, CAN, early boot, EEPROM) |
| Kernel | Linux 4.19.305 LTS (Y181) |

---

## Document Index

### Platform (NEW)

| Document | Description |
|----------|-------------|
| [platform/hardware.md](platform/hardware.md) | Hardware specifications, CPU, GPU, display, peripherals |
| [platform/boot_chain.md](platform/boot_chain.md) | 5-phase boot, hypervisor, verified boot, rollback protection |
| [platform/security.md](platform/security.md) | SELinux, dm-verity, FBE, EEPROM security, CVEs |
| [platform/networking.md](platform/networking.md) | Ethernet, WiFi, USB identifiers, VIP IPC, CAN bus |
| [platform/firmware_versions.md](platform/firmware_versions.md) | Y175/Y177/Y181 comparison, DPS/CalDef systems |

### Video

| Document | Description |
|----------|-------------|
| [video/README.md](video/README.md) | Video subsystem overview and quick reference |
| [video/hardware_rendering.md](video/hardware_rendering.md) | GPU, OpenGL, Vulkan, HWC, gralloc (iahwcomposer) |
| [video/carplay_video_pipeline.md](video/carplay_video_pipeline.md) | CarPlay/AirPlay video processing (CINEMO framework) |
| [video/cinemo_nme_framework.md](video/cinemo_nme_framework.md) | CINEMO/NME framework architecture (~17.5 MB) |
| [video/h264_nal_processing.md](video/h264_nal_processing.md) | H.264 NAL unit processing and frame handling |
| [video/pts_timing_strategies.md](video/pts_timing_strategies.md) | PTS timing: source extraction vs synthetic monotonic |
| [video/video_codecs.md](video/video_codecs.md) | Video codec specifications (HW + SW) |
| [video/display_subsystem.md](video/display_subsystem.md) | Display panel, SurfaceFlinger, composition |
| [video/software_rendering.md](video/software_rendering.md) | CPU-based rendering fallbacks |

### Audio

| Document | Description |
|----------|-------------|
| [audio/README.md](audio/README.md) | Audio subsystem overview (14 buses, HAL 5.0) |
| [audio/audio_subsystem.md](audio/audio_subsystem.md) | AudioFlinger, buses, PulseAudio crossbar, AVB |
| [audio/intel_audio.md](audio/intel_audio.md) | Intel IAS SmartX + SST architecture (no public docs) |
| [audio/carplay_audio_pipeline.md](audio/carplay_audio_pipeline.md) | CarPlay/AirPlay bidirectional audio (CINEMO) |
| [audio/audio_codecs.md](audio/audio_codecs.md) | Audio codec specifications |
| [audio/audio_effects.md](audio/audio_effects.md) | Harman preprocessing, NXP effects |
| [audio/automotive_audio.md](audio/automotive_audio.md) | AAOS multi-zone architecture |

### Projection (NEW)

| Document | Description |
|----------|-------------|
| [projection/README.md](projection/README.md) | Projection overview and comparison |
| [projection/carplay_vs_android_auto.md](projection/carplay_vs_android_auto.md) | CarPlay vs Android Auto detailed comparison |
| [projection/cluster_navigation.md](projection/cluster_navigation.md) | Navigation-to-cluster data flow (text metadata, not video) |
| [projection/cpc200_integration.md](projection/cpc200_integration.md) | CPC200-CCPA wireless adapter integration reference |

### Codecs

| Document | Description |
|----------|-------------|
| [codecs/media_codecs.md](codecs/media_codecs.md) | Complete media codec manifest (from system config) |

### Intel Media SDK

| Document | Description |
|----------|-------------|
| [intel_media_sdk/README.md](intel_media_sdk/README.md) | Intel MFX SDK architecture and GM implementation |
| [intel_media_sdk/mediasdk-man.pdf](intel_media_sdk/mediasdk-man.pdf) | Official Intel API Reference (1.4 MB) |
| [intel_media_sdk/intel-media-developers-guide.pdf](intel_media_sdk/intel-media-developers-guide.pdf) | Official Intel Developer's Guide (4.4 MB) |

### Runtime Analysis (NEW)

| Document | Description |
|----------|-------------|
| [runtime/README.md](runtime/README.md) | Runtime observations overview (3.8GB logs, Feb 2026) |
| [runtime/boot_timing.md](runtime/boot_timing.md) | Boot sequence and timing (~24.5s to screen) |
| [runtime/memory_pressure.md](runtime/memory_pressure.md) | LMK, memory pressure, debloat comparison |
| [runtime/known_issues.md](runtime/known_issues.md) | SELinux denials, audio errors, adapter quirks |

### Other

| Document | Description |
|----------|-------------|
| [third_party_access.md](third_party_access.md) | Third-party app access to Intel Video/Audio APIs |
| [debloat_vs_factory_analysis.md](debloat_vs_factory_analysis.md) | Debloated vs factory performance comparison |

---

## Projection Support

### CarPlay (Native)

| Aspect | Implementation |
|--------|----------------|
| Framework | CINEMO/NME (Harman/Samsung) |
| Video Decoder | Software (libNmeVideoSW.so) at 1416x842 @ **30fps** |
| Protocol | AirPlay 320.17.8 |
| Authentication | Apple MFi (iAP2) |
| Transport | USB NCM + IPv6, WiFi |

### CarPlay (via CPC200 Adapter)

| Aspect | Implementation |
|--------|----------------|
| Framework | Standard Android MediaCodec |
| Video Decoder | **Hardware** (OMX.Intel.hw_vd.h264) at 2400x960 @ **30fps** |
| Protocol | CPC200 USB protocol (adapter bridges AirPlay) |
| Bitrate | 1.2-5.3 Mbps adaptive |
| Transport | USB (VID 0x1314, PID 0x1521) |

### Android Auto

| Aspect | Implementation |
|--------|----------------|
| Framework | Standard Android AOSP |
| Video Decoder | Hardware (OMX.Intel.hw_vd.h264) |
| Protocol | Android Auto Protocol (AAP) |
| Authentication | Google certificates |
| Transport | USB AOA, WiFi |

---

## Key Corrections (Feb 2026 Verification)

Values corrected from initial research based on logcat verification:

| Item | Previously | Corrected |
|------|-----------|-----------|
| Audio buses | 12 | **14** (bus7_system_sound_out, bus12_audio_cue_out added) |
| Audio HAL | Version 3.0 | **Version 5.0** (`vendor.hardware.audio@5.0`) |
| HW Composer | hwcomposer.broxton | **iahwcomposer** (Intel Automotive HWC 2.1) |
| CarPlay FPS | 60fps | **30fps** ("HU fps=60 but we are using 30") |
| Vulkan | "1.0.64 in use" | **Exists but NOT used at runtime** (GLES only) |
| HW codecs | 9 | **10** (MPEG-2 OMX.Intel.hw_vd.mp2 was missing) |
| Gralloc | gralloc.broxton | **minigbm CrosGralloc4** |

---

## Data Sources

**ADB Enumeration (Y175, Y181):**
- dumpsys (SurfaceFlinger, audio, media.player, gpu, display)
- System properties, service list, process list

**Extracted Partitions:**
- `/vendor/etc/` — Configuration files (media_codecs.xml, audio_policy, etc.)
- `/system/lib64/` — Native libraries (libNme*.so, libias-*.so)
- `/system/app/` — APKs (GMCarPlay, AndroidAuto, ClusterService)

**Binary Analysis:**
- `strings`, `readelf`, `nm` on NME and Intel libraries

**Logcat (Feb 2026):**
- 3.7GB across 29 files, 241MB primary analysis file
- `/Volumes/POTATO/logcat/20260219/`

**CPC200 Logs:**
- 45MB across 20 firmware log files
- `/Volumes/POTATO/cpc200/`

**Source:** `~/Downloads/misc/GM_research/gm_aaos/2024_Silverado_ICE/`
