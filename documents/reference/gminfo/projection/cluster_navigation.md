# Cluster Navigation Pipeline

**Device:** GM Info 3.7 (gminfo37)
**Platform:** Intel Apollo Lake (Broxton)
**Android Version:** 12 (API 32)
**Research Date:** January 2026
**Updated:** 2026-05-03 — added cross-platform analysis vs CT5 / AAOS 14 + corrected understanding of maneuver-icon path
**Updated:** 2026-05-04 — corrected platform naming to GM VCU 1.0 / CIP (Bosch VCUNM1) per Bosch installer manual; clarified that the icon-substitution behavior is verified on AAOS 14 only and may not apply to early VCU vehicles that shipped on AAOS 12/13

---

## ⚠ Platform naming note (2026-05-04)

GM's successor to the Info 3.x platform is officially named **GM Vehicle Cockpit Unit (VCU) 1.0**, type designation **Cockpit Integration Platform ("CIP")**, Bosch hardware model **VCUNM1** (MID variant, Qualcomm 8155; HIGH variant **VCUNH1**, Qualcomm 8195). Source: Bosch "Vehicle Cockpit Unit (VCU) Technical Description and Installers Manual," v1, 09/14/23. The manual states verbatim: *"GM VCU 1.0 is a technology upgrade for the previous generation GM Cockpit ECU platform ('Info 3.x')."* The 2026 Cadillac CT5 used as the test specimen below is a VCU vehicle running AAOS 14. References to "CT5" in this addendum should be read as "the VCU/CIP platform as observed on AAOS 14 firmware." The icon-substitution behavior described below is verified against AAOS 14 firmware extracts only — early VCU vehicles that shipped on AAOS 12 or 13 (before OTA upgrade) are not yet characterized and may behave differently.

---

## ⚠ 2026-05-03 ADDENDUM — Cross-platform finding (VCU/CIP on AAOS 14 vs Silverado on AAOS 12)

**Originally static decompilation analysis. NOW FIRMWARE-VERIFIED 2026-06-06 (see verification block immediately below). Runtime evidence reported by user: CT5 cluster (VCU/CIP, AAOS 14) shows GM-internal-style maneuver glyphs regardless of which projection source provides the icon (carlink-internal CarPlay design OR AA passthrough bitmap). Silverado (Info 3.7, AAOS 12) renders the actual app-provided bitmap correctly.**

### ✅ 2026-06-06 FIRMWARE-VERIFIED UPDATE (4-agent extraction of the actual OS images)

Verified directly against the shipped firmware (CT5 `Radio-IVE-86384258-AAOS14-UQBM`: `system.img`=86384268.XP, `product.img`=86384269.XP; Silverado Y181: module 86331654=system, 86331636=product). The original static analysis below is **substantively correct** on the host-side delta; two findings are corrected/clarified:

1. **The `ClusterIconContentProvider` authority is ORPHANED on BOTH platforms — not closed on CT5.** GM ships Google's `GoogleTemplatesHost.apk` (in `product/priv-app/`) **unmodified** on both Silverado and CT5. On both, the class `com.android.car.libraries.templates.host.navigation.ClusterIconContentProvider` is compiled in and actively called (`ContentResolver.insert/query`), but **no manifest on either image registers a `<provider>` for the authority** (firmware-wide grep = 0; GTH declares only 6 providers, none matching). The authority is computed at runtime as `getPackageName()+".ClusterIconContentProvider"` and is **meant to be registered by the Templates Host APK itself** — Google's prebuilt simply omits it. So the hook is **equally claimable on CT5**; GM cannot close it via RRO (overlays can't add a `<provider>`). ⇒ Earlier wording that the hook "does not exist / does not apply" on VCUNH1 is wrong: it exists and is open there too, it is just **bypassed** (see #2).

2. **Why CT5 still ignores app icons = the enum bypass, NOT authority closure (confirmed).** `VMSPlugin/.../NavigationStateProtoUtils.getActiveRouteData()` calls `activeRouteData.setManeuverType(turnType)` (verified at **line 589**, with the TurnType conversion at **:564**; original cite of :554-562 was off-by-build) alongside the URI. The cluster wire proto `Protos$ManeuverDataUpdate_status.maneuverIcon_` is **enum-typed** (`Protos$maneuverIconType`, ~500 named glyphs) with **no bitmap/bytes field**, and `mManeuverByteArray` is **never populated** by VMSPlugin (grep-confirmed). There is **no URI→bytes path** anywhere in VMSPlugin/ClusterService/GMRHMIService → the app's URI/bitmap has no consumer on the CT5 cluster path and is masked.

**CORRECTION (supersedes an earlier claim): the `TurnType → maneuverIconType → glyph` final leg is NOT in the Java APKs.** An earlier note said `ClusterViewManager` maps the enum to `vcs*` sprite resources — that was a **jadx constant-inlining artifact**: the `vcs*` / `R.xml.vcs*` names are whole-screen **cluster view/theme layouts** (e.g. `vcs8_info_c1`, `vcs11_map_…`), not maneuver glyphs, and their integer keys merely coincide with `maneuverIconType._VALUE` constants. There is **no `TurnType → maneuverIconType` bridge anywhere in VMSPlugin / GMRHMIService / ClusterService**. `maneuverIconType` is a **read-only FSA property** (`IRouteGuidance` `@FSAFunction id=1002`), and `ClusterService` is a thin Java **NFSA/FSA RPC gateway** (`com.gm.nfsa.*`, `FSARemoteModuleHMIService`) — **zero native `.so`**; there is **no `libFSA` on Android** (the only "fsa" libs are unrelated Qualcomm FastRPC `lib/rfsa/adsp/*`). The Java side only **serializes + transports** the enum.

**Cluster render architecture — FIRMWARE-VERIFIED 2026-06-06 (4-agent sweep of CT5 system/product/vendor + QNX `ifs2_la.img`/`hmi_data_la.img`):** the CT5 cluster is **NOT an Android-rendered display** (not the emulator model). It is rendered in a **separate QNX 7.x safety-domain guest VM** on the same Qualcomm 8195 (QNX hypervisor) by `/usr/bin/hmiCluster/HMIC` (QNX Screen + EGL/GLES + **Altia** HMI toolkit; `WFD_CLIENT_TYPE_CLUSTER`; launched under an **ASIL Application Safety Monitor / signature-verified `asm_binary.bin`**). The AAOS cluster framework is **disabled** (`config_clusterHomeServiceMode=0`, empty `config_occupant_display_mapping`, occupant zones = DRIVER only); the legacy renderer `com.gm.vmsplugin/.VMSClusterService` only forwards nav DATA. Handoff = **inter-VM Automotive Ethernet** (Android `172.16.5.100`/`192.168.118.1` ↔ QNX `172.16.5.101`/`192.168.118.2`), GM **NFSA/FSA protobuf-over-TCP** (multicast SD `239.192.0.1:3000` → TCP, frame magic `0x5AA5`, `serviceId 1007 REMOTEMODULEHMI`, `IRouteGuidance id=1002`). The ~500 maneuver glyph sprites live on the **QNX side** in `hmi_data_la.img` (QNX6) as **Altia OGLES texture atlases**, per-vehicle-variant (`A2LL`, `A2LL_VBlackWing`, `B233`, …) — **absent from every Android partition**. Map/horizon data takes a separate path: `VMSPlugin` → Android Car **VMS bus** + GM **Vehicle HAL** (ADASIS/Navilink) → Ethernet gateway.

**⇒ Android is BLIND to the rendered cluster pixels.** It emits only the `maneuverIconType` enum index + nav data over Ethernet; QNX HMIC composes the glyph from its own Altia atlas. The only lever a projected app has is the **enum index** (`Maneuver.TYPE_*`/angle → VMSPlugin `TurnType` → NFSA → QNX). No pixel/URI/bitmap path exists. (Silverado is the same shape but a separate cluster MCU over Automotive Ethernet rather than a co-resident QNX VM; the AAOS emulator is the opposite — Android owns a `DISPLAY_TYPE_CLUSTER` and Templates Host draws it.)

Net: on CT5 the shim could still legally claim the authority, but it would only intercept the Templates-Host insert/query (data exfil/inject surface) — it cannot change the rendered cluster glyph, which is enum-driven. On Silverado, VMSPlugin forwards URI-only for the imminent turn (no enum), so the claimed-shim bitmap dominates and the cluster shows the app icon.

Verified extraction artifacts: `/Users/zeno/Downloads/misc/GM_research/gm_aaos/_cluster_authority_analysis/{ct5_templateshost,ct5_vmsplugin,ct5_openhooks,silverado}/`. The `/tmp/gm_extract/...` paths cited later in this doc are from the original (now-deleted) scratch extraction; the firmware-verified equivalents live under the path above.

### Both platforms have an ECU-side glyph library

Verified via `/tmp/gm_extract/decomp/ct5/ClusterService/sources/com/gm/nfsa/Protos$maneuverIconType.java`: the cluster wire proto enumerates 200+ named glyph IDs (`NoManeuver`, `ContinueStraight`, `LeftTurn`, `RightTurn`, `BearLeft`, `BearRight`, `MergeLeft`, `MergeRight`, `Arrow146..Arrow256`, `CWTrafficCircle1stExit..CCWTrafficCircle3rdExit`, `Waypoint1..Waypoint5`, `*Degree*` variants, etc.). The cluster ECU has its own sprite assets keyed by these enum values. The wire format **has no bitmap field at the maneuver layer** — only the enum.

This means: whoever sends data to the cluster ECU tells it which sprite to draw. The ECU never receives a bitmap to display.

### The host-side delta (the smoking gun)

**Silverado** at `/tmp/gm_extract/decomp/silv/VMSPlugin/sources/h/b.java:80-140` — the `b()` method that builds `ActiveRouteData` for the **imminent turn**:
- Calls `activeRouteData2.setManeuverIcon(uri)` (URI only)
- DOES NOT call `setManeuverType(...)` — `mManeuverType` stays `TurnType.UNKNOWN`

**CT5** at `/tmp/gm_extract/decomp/ct5/VMSPlugin/sources/com/gm/vmsplugin/navstate/NavigationStateProtoUtils.java:554-562`:
- Calls BOTH `setManeuverIcon(maneuverIconUri)` AND `setManeuverType(ManeuverTypeToTurnType(getType(), getTypeV2()))`

CT5 added a `setManeuverType()` call (with `TypeV2` support) that Silverado's equivalent code lacks.

### What actually crosses the wire to the ECU

Despite VMSPlugin populating `mManeuverIcon: Uri` in `ActiveRouteData`, the cluster wire proto `Protos$ManeuverDataUpdate_status.maneuverIcon_` is **enum-typed (`Protos$maneuverIconType`)**. No statically-traced code path resolves the URI to bytes and ships those bytes to the ECU. The URI appears to die at the proto-build boundary inside the GMRHMIService listener fan-out (heavily obfuscated `a2/a.java`, `b2/c2/d2` packages).

**The cluster ECU verifiably receives:**
- Maneuver type enum (`Protos$maneuverIconType` value, including `NoManeuver` for absent/unknown)
- Distance to maneuver (int + units)
- Road names (text)
- Lane guidance (enum array)

**The cluster ECU does NOT verifiably receive a bitmap for the maneuver icon.** The ECU draws its own glyph from its sprite library.

### Why each platform behaves differently

**CT5 (GM-style icons appear):**
- VMSPlugin sets BOTH URI AND TurnType enum → cluster ECU has an authoritative enum value → renders matching GM glyph from its sprite library → app-provided bitmap (whether from carlink or any other 3P including potentially Google Maps) is masked.

**Silverado (app-provided bitmap appears):**
- VMSPlugin sets ONLY the URI for the imminent turn (no enum forwarded for this specific widget)
- Without an enum to substitute, something downstream resolves the URI to PNG bytes and pushes them via a parallel channel (most plausibly an OnStar TBT side-channel that calls `ContentResolver.openFile()` on the URI host-side and ships the bytes via a separate FSA functionId) — exact mechanism not statically traceable
- The ClusterIconShimProvider in carlink_native captures the orphaned `<TemplatesHostPkg>.ClusterIconContentProvider` authority; when the parallel channel resolves the URI, it lands on the shim's openFile and gets the cached bitmap

### Why the carlink_native ClusterIconShimProvider was added (historical context)

Early `revisions.txt` entries show the troubleshooting that led to the shim:

- **[71]** Testing GM Cluster Nav Metadata integration. AOSP/Vanilla AOSP works fine, but not GM AAOS.
- **[72]** Stale Nav Icons and Name fix test
- **[81]** New Nav Icons
- **[92]** Android Auto Cluster Maneuver Icon correction tests. Trying to Match the known CP Manuver indicators to the Android Auto movements.
- **[94]** Android Auto navigation icons. NAVI_IMAGE(201) data found, only AA provides navigation icons via adapter forwarding. Used for AA active navigation instead of app's internal vector graphics (designed for CarPlay).
- **[95]** AA maneuver icon and Template Host fixes. Will not set maneuver to 'UNKNOWN', uses provided maneuver enum regardless. Uses provided IMAGE if available, falls back to vector graphic if not. Testing 'metheos' detection code to check if Template Host ContentProvider is available to claim.

The shim approach was **correct for Silverado**. Without it, the orphaned `<TemplatesHostPkg>.ClusterIconContentProvider` authority went unresolved, the parallel URI-resolution channel had nothing to fetch, and the cluster widget showed road name + distance but no icon — exactly the symptom that drove revisions [71]-[95].

The shim approach is **futile on CT5** for changing the rendered icon. The CT5 cluster ECU draws its own GM-style glyph based on `setManeuverType()` regardless of whether a URI is also present. carlink-side adjustments cannot override the ECU's sprite choice without changes to the host-side code outside the 3P scope (specifically: prevent VMSPlugin's `setManeuverType()` call, which is in a system app).

### What about Google Maps on the cluster?

`MapsCarPrebuilt.apk` (`com.google.android.apps.maps`) holds `CAR_DISPLAY_IN_CLUSTER` + `CAR_NAVIGATION_MANAGER` + `VMS_PUBLISHER`/`SUBSCRIBER` permissions and integrates with the cluster on both platforms. Static analysis suggests Google Maps maneuver icons go through the same VMSPlugin path:

- **On CT5**: Google Maps icons would also be substituted by GM-internal glyphs (because VMSPlugin's enum-forwarding applies to all callers of NavigationManager.updateTrip)
- **On Silverado**: Google Maps icons would land on the same orphaned `ClusterIconContentProvider` authority — meaning if carlink_native is NOT installed, Google Maps maneuver icons would also have the URI-resolution problem on the imminent-turn widget (UNKNOWN whether Google Maps has an alternate in-process resolution path that bypasses ContentResolver)

This is consistent with: GM's design intent is uniform cluster appearance regardless of nav source. CT5's `setManeuverType()` addition enforces this uniformity at the host-side. Silverado's older path still allows the bitmap channel to dominate.

### Implications for carlink_native

1. **The shim was correct work for Silverado** — without it, the cluster widget would show no icon regardless of what carlink computed.
2. **The shim is dead-weight on CT5** — even with a perfect bitmap, the ECU substitutes its own glyph. Cannot be fixed from 3P scope.
3. **The most carlink can do on CT5** to control the rendered glyph is ensure `Maneuver.TYPE_*` mapping in `ManeuverMapper.kt` produces the most precise matching enum so the GM glyph chosen is at least correct for the maneuver. Mapping accuracy translates 1:1 to the cluster sprite chosen by the ECU.
4. **Workaround experiment** (UNVERIFIED): set `Maneuver.TYPE_UNKNOWN` unconditionally on CT5 to observe whether the ECU falls back to URI rendering or draws a generic "unknown" sprite. Risk-free runtime test.

### Cross-platform summary table

| Property | Info 3.7 / gminfo37 (Silverado, AAOS 12) | VCU / CIP / VCUNM1 (CT5, AAOS 14) |
|---|---|---|
| GM platform name | Info 3.x | GM VCU 1.0 / Cockpit Integration Platform ("CIP") |
| Hardware vendor / model | Intel Atom + Renesas RH850 (gminfo37 module) | Bosch VCUNM1 (Qualcomm 8155 + RH850F1KM/KH); HIGH variant **VCUNH1** (Qualcomm 8195) |
| Hypervisor | GHS INTEGRITY | BlackBerry QNX 7.x |
| Cluster ECU has glyph library | YES (per Protos$maneuverIconType wire format) | YES (same enum schema) |
| `ClusterIconContentProvider` authority orphaned/claimable | YES (firmware-verified) | YES (firmware-verified — same unmodified GoogleTemplatesHost; open but bypassed) |
| VMSPlugin forwards URI on imminent turn | YES | YES |
| VMSPlugin forwards TurnType enum on imminent turn | NO | YES (`setManeuverType()` at NavigationStateProtoUtils:562) |
| ECU renders app-provided bitmap | YES (parallel URI-resolution channel, mechanism unverified) | NO (enum-driven sprite preferred) — verified on AAOS 14 only |
| ECU renders own glyph from enum | Only for upcoming-step preview list (`gm.navigation.models.Maneuver` list path) | YES, for imminent turn |
| Was carlink's ClusterIconShimProvider needed | YES (without it, no icon at all) | NO (futile; bitmap is masked) |
| Early VCU on AAOS 12/13 (pre-upgrade) | n/a | UNCHARACTERIZED — divergence is at the VMSPlugin layer (`setManeuverType()` call) which may have been introduced in the AAOS 14 build; pre-AAOS-14 VCU behavior is not yet verified |

### Unknowns (require hardware verification)

1. **The CT5 cluster ECU's behavior when `TurnType=UNKNOWN`** — does it fall back to URI rendering, or render a generic "unknown" glyph? Critical for evaluating the workaround.
2. **The exact Silverado URI-resolution mechanism** — there must be a parallel channel (OnStar TBT? a separate FSA functionId carrying inline PNG bytes?) but no statically-traceable code path was found writing the URI's bytes to a wire proto.
3. **Whether `mManeuverByteArray` (defined on `ActiveRouteData` on both platforms but unpopulated by VMSPlugin in static trace) gets filled by `gm.navigation.GMNavigationManager` downstream** — this would be the simplest plausible mechanism if it exists.
4. **`tcpdump` on the SoC's Automotive Ethernet interface (192.168.118.0/24) during a maneuver event** — would definitively show what bytes leave the SoC for the cluster ECU on each platform. Easiest hardware experiment.

### Key file references

CT5 host-side delta:
- `/tmp/gm_extract/decomp/ct5/VMSPlugin/sources/com/gm/vmsplugin/navstate/NavigationStateProtoUtils.java:345,554,561,562,622-626`
- `/tmp/gm_extract/decomp/ct5/VMSPlugin/sources/gm/navigation/models/ActiveRouteData.java:29,250`

Silverado host-side:
- `/tmp/gm_extract/decomp/silv/VMSPlugin/sources/h/b.java:80-140` (imminent turn — URI only, no enum)
- `/tmp/gm_extract/decomp/silv/VMSPlugin/sources/h/b.java:166-330` (preview-list path — enum only, no URI)
- `/tmp/gm_extract/decomp/silv/GMSXM/sources/gm/navigation/models/ActiveRouteData.java:13,200`

Cluster wire format (CT5; Silv likely similar but lower file count):
- `/tmp/gm_extract/decomp/ct5/ClusterService/sources/com/gm/nfsa/Protos$ManeuverDataUpdate_status.java:151,308-310,568-569`
- `/tmp/gm_extract/decomp/ct5/ClusterService/sources/com/gm/nfsa/Protos$maneuverIconType.java` (200+ glyph enum)

Templates Host (identical Silv + CT5):
- `/tmp/gm_extract/decomp/{silv,ct5}/GoogleTemplatesHost/sources/com/android/car/libraries/templates/host/navigation/NavigationStateConverterImpl.java:298-309`
- `/tmp/gm_extract/decomp/{silv,ct5}/GoogleTemplatesHost/sources/com/android/car/libraries/templates/host/navigation/ClusterIconContentProvider.java:179-181`

mempalace cross-references:
- `gminfo37/projection/drawer_gminfo37_projection_6fcb0bc8369266b42fc97dee` — initial finding (CT5 vs Silv divergence)
- `carlink_native/architecture/drawer_carlink_native_architecture_92ce1e0f483ffbe8312a7821` — IClusterHmi wire-format spec for cluster
- `gminfo37/platform/drawer_gminfo37_platform_fe0c8a960a9d2ce90fcabefd` — GM AAOS platform naming (Info 3.x → VCU 1.0 / CIP / Bosch VCUNM1) sourced from Bosch installer manual

---

## Overview

This document describes how navigation turn-by-turn data flows from projection sources (CarPlay, Android Auto) and built-in navigation (Google Maps) to the vehicle instrument cluster display. The cluster shows basic directions including turn arrows, street names, and distance to the next turn.

> **2026-05-03 NOTE on the original Silverado-only architecture below:** The pipeline diagrams and component map below describe Silverado AAOS 12 (Info 3.7) specifically. The VCU/CIP platform on AAOS 14 (CT5 and equivalent EVs/newer ICE vehicles) uses the same upstream (carlink → Templates Host → AAOS framework) but diverges inside `VMSPlugin` (system app) where AAOS 14 added enum forwarding alongside the URI. See addendum above for the corrected understanding. The original Silverado architecture below remains accurate for AAOS 12 / Info 3.7.

---

## Key Finding: Text Metadata, NOT Video Stream

**GM AAOS does NOT use CarPlay's Alternate Video Stream for the instrument cluster.** Instead, it uses text-based metadata protocols:

| Source | Protocol | Data Type |
|--------|----------|-----------|
| CarPlay | iAP2 Route Guidance Display (RGD) | Text metadata |
| Android Auto | NavigationStateProto | Protobuf metadata |
| Google Maps | Android Car Navigation API | Structured data |

### Why Not Alternate Video?

1. **Cluster is a Separate ECU** - Connected via CAN bus (~500kbps), insufficient for video
2. **Local Rendering** - ClusterService.apk contains pre-rendered PNG icon assets
3. **Protocol Evidence** - libNmeIAP.so implements RGD (text), not video streaming

### What the Cluster Receives

```
Structured Data (NOT video frames):
├─ Maneuver Type (enum: turn_left, turn_right, roundabout, etc.)
├─ Distance to Next Maneuver (number + unit)
├─ Current Road Name (string)
├─ Next Road Name (string)
├─ Lane Guidance (array of lane states)
└─ Exit Information (optional string)
```

### How the Cluster Renders

ClusterService.apk renders navigation using **local PNG assets**:

```
/assets/Extended/C1/
├─ quick_turn_primary_maneuver.png
├─ quick_turn_secondary_maneuver.png
└─ lane_guidance_bg.png

/assets/Extended/C2/
├─ arrow_bg.png, arrow_bg_enhanced.png
├─ guidance_arrow_enhanced_small.png
├─ nav_distance_window.png
├─ nav_compass_window_small.png
├─ directions_sign.png
└─ carplay_icon.png
```

---

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                           NAVIGATION DATA SOURCES                                │
├──────────────────┬──────────────────┬────────────────────────────────────────────┤
│   Apple CarPlay  │   Android Auto   │         Built-In Google Maps               │
│   (iPhone)       │   (Phone)        │         (MapsCarPrebuilt.apk)              │
│                  │                  │                                            │
│   iAP2 Protocol  │   AAP Protocol   │   Android Car Navigation API               │
│   AirPlay Video  │   USB AOA        │   VMS Publisher/Subscriber                 │
└────────┬─────────┴────────┬─────────┴─────────────────┬──────────────────────────┘
         │                  │                           │
         ▼                  ▼                           ▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│                      GM AAOS NAVIGATION SERVICES LAYER                           │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│  ┌─────────────────────┐   ┌─────────────────────────┐   ┌───────────────────┐  │
│  │ com.gm.carplay      │   │ CarService              │   │ Google Maps       │  │
│  │  .service.BINDER    │   │ (AAOS Framework)        │   │ (MapsCarPrebuilt) │  │
│  │ [ICarPlayService]   │   │ [car_navigation_service]│   │                   │  │
│  └──────────┬──────────┘   └───────────┬─────────────┘   └─────────┬─────────┘  │
│             │                          │                           │            │
│             ▼                          ▼                           │            │
│  ┌──────────────────────────────────────────────────────────────────────────┐   │
│  │           com.gm.server.NavigationService                                │   │
│  │           [gm.navigation.INavigationService]                             │   │
│  │           SELinux Context: gm_domain_service_nav                         │   │
│  └───────────────────────────────────┬──────────────────────────────────────┘   │
│                                      │                                          │
│                                      ▼                                          │
│  ┌──────────────────────────────────────────────────────────────────────────┐   │
│  │           gm.onstar.OnStarTurnByTurnManager                              │   │
│  │           [GMOnStarTBT.apk]                                              │   │
│  │           Turn-by-Turn Maneuver Processing                               │   │
│  └───────────────────────────────────┬──────────────────────────────────────┘   │
│                                      │                                          │
└──────────────────────────────────────┼──────────────────────────────────────────┘
                                       │
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│                         CLUSTER DISPLAY LAYER                                    │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│  ┌──────────────────────────────────────────────────────────────────────────┐   │
│  │           clusterService                                                  │   │
│  │           [gm.cluster.IClusterHmi]                                       │   │
│  │           Package: com.gm.cluster (PID 2094)                             │   │
│  │           Framework: info3_cluster.jar                                    │   │
│  │           APK: ClusterService.apk (2.9 MB)                               │   │
│  └───────────────────────────────────┬──────────────────────────────────────┘   │
│                                      │                                          │
│                                      ▼                                          │
│  ┌──────────────────────────────────────────────────────────────────────────┐   │
│  │           Vehicle Panel Service (HAL)                                     │   │
│  │           vendor.gm.panel@1.0::IPanel                                    │   │
│  │           Binary: /vendor/bin/hw/vehiclepanel                            │   │
│  │           Interface: IPanel/default, IPanelDiagnostics/default           │   │
│  └───────────────────────────────────┬──────────────────────────────────────┘   │
│                                      │                                          │
└──────────────────────────────────────┼──────────────────────────────────────────┘
                                       │
                                       ▼ (CAN Bus / Internal Bus)
                          ┌────────────────────────────┐
                          │   INSTRUMENT CLUSTER       │
                          │   (Separate ECU)           │
                          │   Turn Arrow + Distance    │
                          └────────────────────────────┘
```

---

## Data Flow by Source

### 1. CarPlay Navigation Data Flow

**Protocol:** iAP2 Route Guidance Display (RGD) - **Text Metadata, NOT Video**

> **Important:** CarPlay's Alternate Video Stream is NOT used for the cluster. GM uses the iAP2 Route Guidance Display (RGD) protocol which transmits text metadata only.

```
iPhone Navigation App (Apple Maps, Google Maps, Waze)
    ↓ iAP2 RGD Protocol (metadata only)
libNmeIAP.so (2.9 MB)
    ├─ RouteGuidanceDisplayComponent
    ├─ OnRouteGuidanceUpdate()
    ├─ OnRouteGuidanceManeuverUpdate()
    └─ OnLaneGuidanceInformation()
    ↓
com.gm.carplay.service.BINDER [ICarPlayService]
    ↓
com.gm.server.NavigationService [INavigationService]
    ↓
OnStarTurnByTurnManager (maneuver extraction)
    ↓
clusterService [IClusterHmi] → renders with local PNG assets
    ↓
vehiclepanel (vendor.gm.panel@1.0)
    ↓
Instrument Cluster ECU
```

**Key Files:**
| File | Path | Purpose |
|------|------|---------|
| libNmeIAP.so | /system/lib64/ | iAP2 protocol + RGD handling |
| libNmeNav.so | /system/lib64/ | Navigation command handling |
| libNmeNavCopier.so | /system/lib64/ | Navigation data buffering |
| libNmeCarPlay.so | /system/lib64/ | AirPlay video (main screen only) |

**iAP2 RGD Metadata Fields (from libNmeIAP.so):**
```
NMEMETANAME_IAP_RGD_MAX_CURRENT_ROAD_NAME_LENGTH
NMEMETANAME_IAP_RGD_MAX_AFTER_MANEUVER_ROAD_NAME_LENGTH
NMEMETANAME_IAP_RGD_MAX_MANEUVER_DESCRIPTION_LENGTH
NMEMETANAME_IAP_RGD_MAX_LANE_GUIDANCE_DESCRIPTION_LENGTH
NMEMETANAME_IAP_RGD_MAX_LANE_GUIDANCE_STORAGE_CAPACITY
NMEMETANAME_IAP_RGD_MAX_GUIDANCE_MANEUVER_CAPACITY
NMEMETANAME_IAP_RGD_MAX_DESTINATION_NAME_LENGTH
```

**RGD Callback Functions:**
- `OnRouteGuidanceUpdate()` - Route state changes
- `OnRouteGuidanceManeuverUpdate()` - Maneuver list updates
- `OnLaneGuidanceInformation()` - Lane guidance data
- `StartRouteGuidanceUpdates()` / `StopRouteGuidanceUpdates()`

**CarPlay Mode Tracking (from libNmeCarPlay.so):**
```
screen=%s(%s), main_audio=%s(%s), speech=%s, phone=%s, turn_by_turn=%s
```
Note: `turn_by_turn` is tracked separately from `screen` (main video), confirming they are different data channels.

### CINEMO Secondary Video Capability (Not Used for Cluster)

While libNmeNav.so contains secondary video APIs, these are **NOT used** for the cluster:

```cpp
// Present in binary but NOT used for cluster display:
NmeNavCommands::SetSecondaryVideo()
NmeNavCommands::GetSecondaryVideoAttributes()
```

The cluster receives text metadata via RGD, not video frames.

---

### 2. Android Auto Navigation Data Flow

**Protocol:** Android Auto Protocol (AAP) over USB AOA

```
Phone Navigation App (Google Maps/Waze)
    ↓ AAP USB/WiFi
CarService (AAOS Framework)
    ├─ car_navigation_service (enabled)
    ├─ CAR_NAVIGATION_MANAGER permission
    └─ NavigationStateProto (protobuf format)
    ↓
com.gm.server.NavigationService [INavigationService]
    ↓
OnStarTurnByTurnManager
    ↓
clusterService [IClusterHmi]
    ↓
vehiclepanel
    ↓
Instrument Cluster ECU
```

**Key Permissions (from privapp-permissions-car.xml):**
```xml
<permission name="android.car.permission.CAR_DISPLAY_IN_CLUSTER"/>
<permission name="android.car.permission.CAR_NAVIGATION_MANAGER"/>
<permission name="android.car.permission.CAR_INSTRUMENT_CLUSTER_CONTROL"/>
```

---

### 3. Built-In Google Maps Navigation

**Package:** `com.google.android.apps.maps` (MapsCarPrebuilt.apk)

```
Google Maps (MapsCarPrebuilt)
    ├─ CAR_NAVIGATION_MANAGER permission
    ├─ VMS_PUBLISHER / VMS_SUBSCRIBER
    └─ CAR_DISPLAY_IN_CLUSTER permission
    ↓
CarService → car_navigation_service
    ↓
com.gm.server.NavigationService
    ↓
clusterService
    ↓
vehiclepanel
    ↓
Instrument Cluster ECU
```

**Google Maps Permissions (from privapp-permissions-car.xml):**
```xml
<privapp-permissions package="com.google.android.apps.maps">
    <permission name="android.car.permission.CAR_DISPLAY_IN_CLUSTER"/>
    <permission name="android.car.permission.CAR_NAVIGATION_MANAGER"/>
    <permission name="android.car.permission.VMS_PUBLISHER"/>
    <permission name="android.car.permission.VMS_SUBSCRIBER"/>
</privapp-permissions>
```

---

## Key Components

### Navigation Service

**Service Registration:**
```
com.gm.server.NavigationService: [gm.navigation.INavigationService]
SELinux Context: u:object_r:gm_domain_service_nav:s0
```

The dedicated SELinux context (`gm_domain_service_nav`) separate from other GM services indicates it's a privileged navigation component with specific access policies for interfacing with both projection services and the cluster subsystem.

---

### OnStar Turn-by-Turn Manager

**Location:** `/system/priv-app/GMOnStarTBT/GMOnStarTBT.apk`

**Service Registration:**
```
gm.onstar.OnStarTurnByTurnManager
SELinux Context: u:object_r:gm_domain_service:s0
```

This APK is the **central hub** for all turn-by-turn navigation data, regardless of source:
1. Receives navigation data from all sources (CarPlay, Android Auto, Google Maps)
2. Processes maneuver information (turn direction, distance, street names)
3. Sends formatted turn-by-turn data to the cluster via clusterService

---

### Cluster Service

**Service Registration:**
```
clusterService: [gm.cluster.IClusterHmi]
Process: com.gm.cluster (PID 2094, system user)
```

**Framework Components:**

| File | Path | Purpose |
|------|------|---------|
| info3_cluster.jar | /system/framework/ | Cluster framework library |
| ClusterService.apk | /system/priv-app/ClusterService/ | Cluster display service (2.9 MB) |
| info3_cluster_library.xml | /system/etc/permissions/ | Framework declaration |

**Power Save Configuration:**
```xml
<!-- /system/etc/sysconfig/com.gm.cluster.powersave.xml -->
<config>
    <allow-in-power-save package="com.gm.cluster" />
</config>
```

---

### Vehicle Panel HAL

**Service:** `vendor.gm.panel@1.0-service`
**Binary:** `/vendor/bin/hw/vehiclepanel`

**Interfaces:**
- `IPanel/default` - Main panel control
- `IPanelDiagnostics/default` - Diagnostic interface

---

## Navigation Data Formats

### Android Auto NavigationState (Protobuf)

| Field | Description |
|-------|-------------|
| road_name | Current road name |
| next_instruction | Upcoming maneuver |
| distance_to_next | Distance to next turn |
| turn_icon | Maneuver icon type (left, right, roundabout, etc.) |
| lane_guidance | Lane guidance data |

### CarPlay Navigation (iAP2 Route Guidance Display)

CarPlay uses the **iAP2 Route Guidance Display (RGD)** protocol - a text-based metadata protocol, NOT the Alternate Video Stream.

**RGD Data Structure:**

| Field | Max Length | Description |
|-------|------------|-------------|
| Current Road Name | Configurable | Street currently on |
| After Maneuver Road Name | Configurable | Street after next turn |
| Maneuver Description | Configurable | Turn instruction text |
| Lane Guidance | Array | Lane recommendation states |
| Destination Name | Configurable | Final destination |
| Guidance Maneuver List | Capacity limit | Upcoming maneuvers |

**Protocol Flow:**
```
iPhone App → iAP2 RGD Message → libNmeIAP.so
    → OnRouteGuidanceManeuverUpdate()
    → Extract: maneuver_type, distance, road_names
    → NavigationService → ClusterService
    → Render using local PNG assets
```

**Why RGD Instead of Alternate Video:**
1. Lower bandwidth - CAN bus to cluster ECU is ~500kbps
2. Local rendering - Consistent look with vehicle cluster design
3. Reliability - Text survives connection hiccups better than video
4. Battery - Less processing on iPhone

---

## NavSens Integration (GPS/Location)

The Harman NavSens daemon provides location data that feeds into navigation:

**Service:** `vendor.harman.navsens@1.0-service`
**Config:** `/vendor/etc/navsens_config.json`

**Data Pipelines:**
```
ublox_uart → ubx_to_genivi → genivi_to_carplay → gm_gnss_hal
                    ↓
            genivi_to_sensors → gm_misalignment_correction → sensors_hal
```

**IPC Channel:** `/dev/ipc/ipc4` for real-time sensor data

**Pipeline Configuration (from navsens_config.json):**
```json
{
    "producer": "com.harman.navsensd.genivi_to_carplay",
    "consumer": "com.harman.navsensd.gm_gnss_hal",
    "variant": ["all"]
}
```

---

## VIP MCU Integration

The VIP MCU (Renesas RH850) coordinates cluster communication:

**Calibrations affecting cluster animation:**

| Calibration | Default | Purpose |
|-------------|---------|---------|
| cal_cluster_animation_ignore | 0 | Skip cluster animation sequence |
| cal_cluster_hmiready_ignore | 0 | Skip cluster HMI ready signal |

**VIP-to-SoC IPC Channels:**
- Channels 13-16: System state / Animation control

---

## Vehicle HAL Integration

**Android Automotive Vehicle HAL:**
```
android.hardware.automotive.vehicle@2.0-service-gm
User: vehicle_network
Interface: IVehicle/default
```

**GM Vehicle Extensions:**
- `vendor.gm.vehicle@1.0.so`
- `vendor.gm.panel@1.0.so` (Panel/Cluster display)

---

## Complete Service Map

| Service | Interface | Purpose |
|---------|-----------|---------|
| clusterService | gm.cluster.IClusterHmi | Cluster HMI display |
| com.gm.server.NavigationService | gm.navigation.INavigationService | Navigation data routing |
| gm.onstar.OnStarTurnByTurnManager | OnStar TBT interface | Turn-by-turn processing |
| com.gm.carplay.service.BINDER | ICarPlayService | CarPlay integration |
| vehiclepanel | vendor.gm.panel@1.0::IPanel | Panel HAL bridge |
| navsens-hal-1_0 | INavsens@1.0 | Navigation sensors |

---

## APKs Consuming NavigationService

| APK | Purpose |
|-----|---------|
| info3.jar | Core GM Info3 framework - central navigation integration |
| VTTProxyServer.apk | Vehicle telematics proxy |
| GMOnStar.apk | OnStar services (includes TBT) |
| GMAlexa.apk | Alexa voice assistant integration |
| GMTCPS.apk | Telematics services |
| GMTrustedDevice.apk | Trusted device management |
| DelayedWKSApp.apk | Delayed workstation services |
| GMVAC.apk | Vehicle Audio Control |
| GMNotifications.vdex | Notification system |
| diagnostics_pci_impl.jar | PCI diagnostics |

---

## Component Hierarchy

```
┌─────────────────────────────────────────────────────────────────┐
│                    NAVIGATION INTERFACE LAYER                    │
├─────────────────────────────────────────────────────────────────┤
│  info3.jar                                                       │
│  └─ Defines: gm.navigation.INavigationService (AIDL interface)  │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                    NAVIGATION PROCESSOR                          │
├─────────────────────────────────────────────────────────────────┤
│  GMOnStarTBT.apk                                                 │
│  └─ Implements: gm.onstar.OnStarTurnByTurnManager               │
│  └─ Consumes: INavigationService                                │
│  └─ Produces: Cluster-formatted maneuver data                   │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                    CONSUMERS                                     │
├─────────────────────────────────────────────────────────────────┤
│  - GMAlexa.apk - Voice-based navigation queries                 │
│  - GMTCPS.apk - Telematics integration                          │
│  - GMAnalytics - Navigation usage tracking                      │
│  - ClusterService - Display on instrument cluster               │
└─────────────────────────────────────────────────────────────────┘
```

---

## Summary

The navigation-to-cluster pipeline in GM AAOS follows this chain:

1. **Source Layer:** CarPlay (iAP2 RGD), Android Auto (NavigationStateProto), or Google Maps
2. **Navigation Service:** `com.gm.server.NavigationService` receives navigation metadata
3. **TBT Manager:** `OnStarTurnByTurnManager` processes maneuvers
4. **Cluster Service:** `clusterService` renders using local PNG assets
5. **HAL Layer:** `vehiclepanel` (vendor.gm.panel@1.0) bridges to hardware
6. **Physical Layer:** CAN bus or internal bus to instrument cluster ECU

### Important Distinction

**Text Metadata, NOT Video:** The cluster does NOT receive video frames from CarPlay or Android Auto. It receives structured text data (maneuver type, distance, road names) and renders the display locally using pre-built icon assets in ClusterService.apk.

| What Cluster Receives | What Cluster Does NOT Receive |
|----------------------|------------------------------|
| Maneuver type enum | H.264 video frames |
| Distance as number | Map imagery |
| Road names as strings | CarPlay Alternate Video Stream |
| Lane guidance array | Real-time rendered graphics |

This architecture ensures consistent cluster appearance regardless of navigation source and works within the CAN bus bandwidth limitations (~500kbps) between the infotainment SoC and the cluster ECU.

---

## Binary Analysis Evidence

### libNmeIAP.so - Route Guidance Display Implementation

```bash
$ strings libNmeIAP.so | grep -i "RouteGuidance\|RGD"
```

**Key Findings:**
```
RouteGuidanceDisplayComponent
CinemoIAPRouteGuidance
INmeIAPRouteGuidance::OnRouteGuidanceUpdate
INmeIAPRouteGuidance::OnRouteGuidanceManeuverUpdate
INmeIAPRouteGuidance::OnLaneGuidanceInformation
StartRouteGuidanceUpdates
StopRouteGuidanceUpdates
NMEMETANAME_IAP_RGD_COMPONENT_NAME
NMEMETANAME_IAP_RGD_MAX_CURRENT_ROAD_NAME_LENGTH
NMEMETANAME_IAP_RGD_MAX_AFTER_MANEUVER_ROAD_NAME_LENGTH
NMEMETANAME_IAP_RGD_MAX_MANEUVER_DESCRIPTION_LENGTH
NMEMETANAME_IAP_RGD_MAX_LANE_GUIDANCE_DESCRIPTION_LENGTH
```

These are text field length constants and metadata callbacks - confirming RGD (text protocol), not video.

### libNmeCarPlay.so - Mode Tracking

```bash
$ strings libNmeCarPlay.so | grep "turn"
```

**Key Finding:**
```
screen=%s(%s), main_audio=%s(%s), speech=%s, phone=%s, turn_by_turn=%s
```

`turn_by_turn` is tracked as a separate boolean state from `screen`, proving they are independent data channels.

### libNmeNav.so - Secondary Video (Unused)

```bash
$ strings libNmeNav.so | grep -i "Secondary"
```

**Functions Present (but NOT used for cluster):**
```
NmeNavCommands::SetSecondaryVideo()
NmeNavCommands::GetSecondaryVideoAttributes()
NmeNavCommands::SetSecondaryAudio()
NmeNavCommands::GetSecondaryAudioAttributes()
```

While CINEMO framework supports secondary video streams, there is no evidence these are connected to the cluster display path. The cluster ECU is connected via CAN bus which cannot support video bandwidth.

### ClusterService.apk - Local Asset Rendering

```bash
$ unzip -l ClusterService.apk | grep -E "arrow|maneuver|nav"
```

**PNG Assets for Local Rendering:**
```
assets/Extended/C1/quick_turn_primary_maneuver.png
assets/Extended/C1/quick_turn_secondary_maneuver.png
assets/Extended/C2/arrow_bg.png
assets/Extended/C2/arrow_bg_enhanced.png
assets/Extended/C2/guidance_arrow_enhanced_small.png
assets/Extended/C2/nav_distance_window.png
assets/Extended/C2/nav_compass_window_small.png
assets/Extended/C2/directions_sign.png
assets/Extended/C2/carplay_icon.png
assets/Extended/HUD/navigation_icon.png
```

The cluster renders navigation using these pre-built icons based on the maneuver type received via metadata, not by displaying video frames.

---

## Data Sources

**ADB Enumeration (`/analysis/adb_Y181/`):**
- `dumpsys SurfaceFlinger`
- `dumpsys display`
- `service list`
- Process enumeration

**Extracted Partitions (`/extracted_partitions/`):**
- `/system/priv-app/GMOnStarTBT/` - Turn-by-turn processor
- `/system/priv-app/ClusterService/` - Cluster display service
- `/system/framework/info3.jar` - Navigation interface definitions
- `/system/framework/info3_cluster.jar` - Cluster framework
- `/product/etc/permissions/` - Permission declarations
- `/vendor/etc/navsens_config.json` - NavSens configuration

**Binary Analysis:**
- `strings` on libNmeIAP.so - Route Guidance Display protocol
- `strings` on libNmeNav.so - Secondary video APIs (unused for cluster)
- `strings` on libNmeCarPlay.so - Mode tracking (screen vs turn_by_turn)
- `unzip -l` on ClusterService.apk - PNG asset inventory
- SELinux context analysis from product_service_contexts

**Source:** `/Users/zeno/Downloads/misc/GM_research/gm_aaos/`

---

## Third-Party App Access to Cluster Navigation

### Direct Access: NOT Possible

Third-party apps **cannot** directly access the cluster navigation system due to permission restrictions:

```xml
<!-- Required permissions (signature|privileged protection level) -->
<permission name="android.car.permission.CAR_NAVIGATION_MANAGER" />
<permission name="android.car.permission.CAR_DISPLAY_IN_CLUSTER" />
```

These permissions require either:
- System signature (signed with platform key)
- Privileged app status (installed in `/system/priv-app/`)

### Indirect Access: Via Templates Host (Car App Library)

Third-party navigation apps **CAN** send navigation data to the cluster by using the **Android Car App Library** (`androidx.car.app`). The **Templates Host** (`com.google.android.apps.automotive.templates.host`) acts as the privileged intermediary.

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                    THIRD-PARTY NAVIGATION VIA CAR APP LIBRARY                    │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│  ┌────────────────────────────┐                                                 │
│  │ Third-Party Navigation App │                                                 │
│  │ (e.g., Sygic, TomTom)      │                                                 │
│  │                            │                                                 │
│  │ Uses: androidx.car.app    │                                                 │
│  │ Template: NavigationTemplate                                                 │
│  │ Category: FEATURE_CLUSTER  │                                                 │
│  └─────────────┬──────────────┘                                                 │
│                │ Car App Library IPC                                            │
│                ▼                                                                 │
│  ┌──────────────────────────────────────────────────────────────────────────┐   │
│  │  Templates Host (com.google.android.apps.automotive.templates.host)      │   │
│  │                                                                          │   │
│  │  Privileged Permissions:                                                 │   │
│  │  ├─ android.car.permission.CAR_NAVIGATION_MANAGER                       │   │
│  │  ├─ android.car.permission.CAR_DISPLAY_IN_CLUSTER                       │   │
│  │  └─ android.car.permission.TEMPLATE_RENDERER                            │   │
│  └───────────────────────────────────┬──────────────────────────────────────┘   │
│                                      │                                          │
│                                      ▼                                          │
│  ┌──────────────────────────────────────────────────────────────────────────┐   │
│  │  CarService → NavigationService → ClusterService → vehiclepanel HAL      │   │
│  └──────────────────────────────────────────────────────────────────────────┘   │
│                                                                                  │
└─────────────────────────────────────────────────────────────────────────────────┘
```

### Templates Host Permissions

From `/product/etc/permissions/com.google.android.car.templateshost.xml`:

```xml
<privapp-permissions package="com.google.android.apps.automotive.templates.host">
    <!-- To be able to display activities in the cluster -->
    <permission name="android.car.permission.CAR_DISPLAY_IN_CLUSTER" />

    <!-- To be able to show navigation state (turn by turn directions) in the cluster -->
    <permission name="android.car.permission.CAR_NAVIGATION_MANAGER" />

    <!-- To be considered a system-approved host -->
    <permission name="android.car.permission.TEMPLATE_RENDERER" />
</privapp-permissions>

<!-- Declare support for templated applications -->
<feature name="android.software.car.templates_host" />
```

### Car App Library API for Third-Party Apps

**1. Declare Cluster Support in Manifest:**

```xml
<service android:name=".MyNavigationCarAppService" android:exported="true">
    <intent-filter>
        <action android:name="androidx.car.app.CarAppService" />
        <category android:name="androidx.car.app.category.NAVIGATION"/>
        <category android:name="androidx.car.app.category.FEATURE_CLUSTER"/>
    </intent-filter>
</service>
```

**2. Use NavigationManager API:**

```kotlin
val navigationManager = carContext.getCarService(NavigationManager::class.java)

// Start navigation (gains cluster control)
navigationManager.navigationStarted()

// Send turn-by-turn data
val trip = Trip.Builder()
    .setCurrentRoad(roadName)
    .setDestination(destination)
    .addStep(step)  // Maneuver instructions
    .build()
navigationManager.updateTrip(trip)

// End navigation
navigationManager.navigationEnded()
```

**3. Handle Cluster Session Separately:**

```kotlin
override fun onCreateSession(sessionInfo: SessionInfo): Session {
    return if (sessionInfo.displayType == SessionInfo.DISPLAY_TYPE_CLUSTER) {
        ClusterSession()  // Simplified UI for cluster
    } else {
        MainDisplaySession()  // Full UI for main screen
    }
}
```

### Trip Data Structure

The `Trip` object contains structured navigation data:

| Component | Description |
|-----------|-------------|
| `Step` | Turn-by-turn instructions (maneuver type, distance) |
| `Destination` | Final destination information |
| `TravelEstimate` | ETA, remaining distance |
| `currentRoad` | Current road name |

### Cluster Display Limitations

Per Android Car App quality guidelines (NF-9):

- **Map tiles only:** Cluster should primarily show map tiles
- **Not all data displayed:** Turn-by-turn instructions, ETA cards may not show on all OEMs
- **Non-interactive:** No buttons or touch interaction on cluster
- **Dark theme required:** Must support dark-themed rendering

### Data Flow Summary

```
Third-Party App (Car App Library)
    ↓ NavigationManager.updateTrip(trip)
Templates Host (privileged)
    ↓ CAR_NAVIGATION_MANAGER permission
CarService → NavigationService
    ↓
OnStarTurnByTurnManager
    ↓
ClusterService (renders with local PNG assets)
    ↓
vehiclepanel HAL
    ↓
Instrument Cluster ECU
```

---

## Implications for Third-Party Development

### For CarPlay/Android Auto Implementation

1. **For Cluster Display:** Must implement iAP2 RGD protocol or Android NavigationStateProto to send maneuver metadata to the cluster - video streaming to cluster is not supported

2. **For Main Screen:** Video streaming (H.264 via AirPlay/AAP) is only for the main infotainment display, not the cluster

3. **Maneuver Icons:** The cluster will render its own icons from ClusterService.apk assets based on the maneuver type enum received - custom icons cannot be displayed

4. **Text Limits:** Road names and descriptions have maximum length limits defined by RGD metadata constants

### For Native Android Navigation Apps

1. **Use Car App Library:** Third-party navigation apps must use `androidx.car.app` with `NavigationTemplate`

2. **Declare FEATURE_CLUSTER:** Add `androidx.car.app.category.FEATURE_CLUSTER` to manifest

3. **Call navigationStarted():** Cluster display only available after calling `NavigationManager.navigationStarted()`

4. **Handle Separate Sessions:** Implement `onCreateSession(SessionInfo)` to handle cluster display separately

5. **Limited Control:** Cannot display custom icons or arbitrary graphics on cluster - only standard template elements

---

## References

### GM AAOS Research Data

**Source:** `/Users/zeno/Downloads/misc/GM_research/gm_aaos/`

- ADB enumeration data
- Extracted partitions (system, vendor, product)
- Binary analysis of NME libraries

### Android Car App Library Documentation

- [Build a navigation app | Android Developers](https://developer.android.com/training/cars/apps/navigation)
- [Instrument Cluster API | AOSP](https://source.android.com/docs/automotive/displays/cluster_api)
- [Navigation template | Cars](https://developer.android.com/design/ui/cars/guides/templates/navigation-template)
- [Car App Library fundamentals](https://developer.android.com/codelabs/car-app-library-fundamentals)
- [Car App Jetpack releases](https://developer.android.com/jetpack/androidx/releases/car-app)
