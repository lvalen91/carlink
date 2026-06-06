# Projection Systems

**Device:** GM Info 3.7 (gminfo37)
**Platform:** Intel Apollo Lake (Broxton)
**Android Version:** 12 (API 32)
**Research Date:** December 2025 - February 2026

---

## Overview

This directory documents the phone projection systems on GM Info 3.7: native CarPlay, CarPlay via CPC200-CCPA wireless adapter, and Android Auto. Each takes a fundamentally different path through the system -- different frameworks, decoders, and protocols -- despite all ultimately rendering on the same 2400x960 display.

## Documents

- **[carplay_vs_android_auto.md](carplay_vs_android_auto.md)** -- Detailed comparison of CarPlay and Android Auto implementations including video/audio pipelines, protocol differences, resolution handling, and performance characteristics. Covers native CarPlay (CINEMO/NME software decode) and CPC200-mediated CarPlay (Intel hardware decode).

- **[cluster_navigation.md](cluster_navigation.md)** -- Navigation-to-cluster data flow pipeline. Documents how turn-by-turn data from CarPlay (iAP2 RGD), Android Auto (NavigationStateProto), and built-in Google Maps reaches the instrument cluster ECU via text metadata (NOT video). Includes third-party app access via Car App Library.

- **[cpc200_integration.md](cpc200_integration.md)** -- CPC200-CCPA wireless CarPlay/Android Auto adapter integration with GM Info 3.7. USB protocol, video/audio pipeline, device enumeration, session lifecycle, and adapter-specific behavior.

- **[cluster_maneuver_mapping.md](cluster_maneuver_mapping.md)** -- End-to-end maneuver-icon mapping table: iAP2/AA cpManeuverType (+ exit angle) → carlink `Maneuver.TYPE_*` → Templates Host `NavigationState.Type` (incl. WITH_ANGLE roundabout buckets) → VMSPlugin `TurnType` → GM cluster glyph. Covers gminfo3.7 (bitmap via shim) vs VCUNH1 (enum-only, QNX Altia sprite), the iAP2→androidx angle conversion, and the directional-only roundabout ceiling.

---

## Quick Comparison

| Aspect | CarPlay (Native) | CarPlay (via CPC200) | Android Auto |
|--------|-----------------|---------------------|--------------|
| Framework | CINEMO/NME (Harman) | Standard MediaCodec | Standard AOSP |
| Video Decoder | Software (libNmeVideoSW.so) | Intel HW (OMX.Intel.hw_vd.h264) | Intel HW (OMX.Intel.hw_vd.h264) |
| Resolution | 1416x842 @ 30fps (windowed) | 2400x960 @ 30fps (fullscreen) | Negotiated |
| Protocol | AirPlay 320.17.8 | USB bulk (CPC200 protocol) | Android Auto Protocol |
| Transport | USB NCM + IPv6, WiFi | USB (VID 0x1314, PID 0x1521) | USB AOA, WiFi |
| Audio | CINEMO (libNmeAudioAAC.so) | AudioFlinger direct | Standard AOSP |
| Auth | Apple MFi (iAP2) | CPC200 handles MFi | Google certificates |

---

## Key Architectural Insight

Native CarPlay is the outlier: it bypasses the standard Android media stack entirely, using Harman's CINEMO/NME framework with a CPU-based software decoder. Both the CPC200 adapter path and Android Auto use the standard Android MediaCodec API with Intel hardware acceleration. This means the CPC200 adapter actually achieves better video performance than native CarPlay -- full 2400x960 resolution at 30fps with hardware decode versus 1416x842 at 30fps with software decode.
