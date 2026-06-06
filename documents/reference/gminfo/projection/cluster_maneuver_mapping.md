# Cluster Maneuver Mapping — iAP2 / Android Auto → GM Cluster Glyph

**Scope:** GM Info 3.7 (gminfo37, AAOS 12) and GM VCU 1.0 / CIP (Bosch **VCUNH1**, AAOS 14).
**Status:** firmware-verified 2026-06-06 (4-agent sweep of the CT5 `Radio-IVE-86384258-AAOS14-UQBM`
and Silverado Y181 images). Marks each link as **[verified]** (traced in firmware) or **[derived]**
(inferred from verified geometry; needs on-vehicle calibration).

This is the end-to-end map a projected maneuver travels, from what the adapter delivers to the app,
to the glyph the instrument cluster draws. It exists so the enum/Type precision can be tuned to drive
the best GM cluster sprite on VCUNH1 (where app bitmaps are masked).

---

## 0. The two rendering regimes (why this table matters)

| | gminfo3.7 (Silverado, AAOS 12) | VCUNH1 (CT5, AAOS 14) |
|---|---|---|
| Cluster renderer | separate cluster MCU ECU over Automotive Ethernet | **separate QNX 7.x safety-domain VM** (`/usr/bin/hmiCluster/HMIC`, QNX Screen + EGL/GLES + **Altia**) on the same Qualcomm 8195 |
| Android owns the cluster display? | No (data-only handoff) | **No — Android is BLIND to cluster pixels** |
| Maneuver icon source used | **app bitmap** (CarPlay composed / AA `NAVI_IMAGE 201`) via the `ClusterIconContentProvider` shim | **GM Altia sprite** chosen from the `maneuverIconType` enum; app bitmap/URI is **masked** |
| The lever the app controls | the bitmap pixels | **the enum only** (`Maneuver.TYPE_*` / angle → `TurnType`) |

Handoff transport (both GM platforms): GM **NFSA/FSA protobuf-over-TCP** over inter-domain Automotive
Ethernet (VCUNH1: Android `172.16.5.100`/`192.168.118.1` ↔ QNX `172.16.5.101`/`192.168.118.2`;
multicast SD `239.192.0.1:3000`, frame magic `0x5AA5`, `serviceId 1007 REMOTEMODULEHMI`,
`IRouteGuidance @FSAFunction id=1002`). Only the `maneuverIconType` enum index + nav data cross the
wire — **never pixels**. The `TurnType → maneuverIconType → Altia sprite` final leg is native to QNX
(not in the Android Java; the prior "ClusterViewManager → vcs* sprite" claim was a jadx artifact).

---

## 1. The pipeline (per maneuver)

```
ADAPTER → APP                 APP (carlink)            TEMPLATES HOST (GM, AAOS)        VMSPlugin (GM)          CLUSTER
CarPlay: _iap2/_iap2m  ─┐    ManeuverMapper:          NavigationStateConverterImpl:    ManeuverTypeToTurnType  ─ gminfo37: app bitmap (shim)
 (cpManeuverType 0-53, ├──▶  Maneuver.TYPE_* + ─────▶ androidx Maneuver.TYPE_*    ───▶ NavigationState.Type ──▶ ─ VCUNH1: QNX HMIC Altia
  exit angle 0x000b)   │     setRoundaboutExit*       → NavigationState.Maneuver.Type  → gm TurnType            glyph from maneuverIconType
AA: NaviJSON metadata ─┘     + CarIcon (bitmap)       (+ angle-bucket; exit# fwd;
 + NAVI_IMAGE(201)                                     TypeV2 NOT populated)
```

- **CarPlay** supplies rich iAP2 (`_iap2` 0x5201 per-tick, `_iap2m` 0x5202 maneuver burst) after the
  ARMiPhoneIAP2 patch — no ready-made image (carlink composes one for gminfo37). [verified]
- **Android Auto** supplies a pre-rendered `NAVI_IMAGE(201)` PNG **plus** normalized NaviJSON metadata
  (`NaviManeuverType`, `NaviTurnSide`, `NaviTurnAngle`, `NaviRoundaboutExit`). **AA does not use iAP2**
  (Apple-only). [verified]
- Both sources funnel into the same `androidx.car.app.navigation.model.Maneuver` (+ optional `CarIcon`).

---

## 2. Master table — cpManeuverType → Maneuver.TYPE_* → NavigationState.Type → TurnType

`cpManeuverType` = iAP2 0x5202 field 0x0003 (CarPlay) / `NaviManeuverType` (AA), Apple enum 0–53.
Columns: app `ManeuverMapper.kt` [verified] → Templates Host `NavigationStateConverterImpl` [verified]
→ VMSPlugin `NavigationStateProtoUtils.ManeuverTypeToTurnType` (`:372-507`) [verified].

| cp# | Apple meaning | carlink `Maneuver.TYPE_*` | Host `NavigationState.Maneuver.Type` | VMSPlugin `TurnType` |
|----|---------------|---------------------------|--------------------------------------|----------------------|
| 0 | noTurn | STRAIGHT | STRAIGHT | CONTINUE_STRAIGHT |
| 1 | left | TURN_NORMAL_LEFT | TURN_NORMAL_LEFT | TURN_LEFT |
| 2 | right | TURN_NORMAL_RIGHT | TURN_NORMAL_RIGHT | TURN_RIGHT |
| 3 | straight | STRAIGHT | STRAIGHT | CONTINUE_STRAIGHT |
| 4 | uTurn | U_TURN_LEFT/RIGHT (drive side) | U_TURN_LEFT/RIGHT | U_TURN_LEFT/RIGHT |
| 5 | followRoad | STRAIGHT | STRAIGHT | CONTINUE_STRAIGHT |
| 6 | enterRoundabout | ROUNDABOUT_ENTER_CW/CCW | ROUNDABOUT_ENTER_AND_EXIT_CW/CCW (enter/exit flattened) | ROUNDABOUT_CW/CCW_ENTER_AND_EXIT |
| 7 | exitRoundabout | ROUNDABOUT_EXIT_CW/CCW | ROUNDABOUT_ENTER_AND_EXIT_CW/CCW | ROUNDABOUT_CW/CCW_ENTER_AND_EXIT |
| 8 | offRamp | OFF_RAMP_NORMAL_LEFT/RIGHT | OFF_RAMP_NORMAL_* | OFF_RAMP_LEFT/RIGHT |
| 9 | onRamp | ON_RAMP_NORMAL_LEFT/RIGHT | ON_RAMP_NORMAL_* | ON_RAMP_LEFT/RIGHT |
| 10 | endOfNavigation | DESTINATION | DESTINATION | DESTINATION |
| 11 | depart | DEPART | DEPART | DEPART |
| 12 | arrived | DESTINATION | DESTINATION | DESTINATION |
| 13 | keepLeft | KEEP_LEFT | KEEP_LEFT | (fork/keep family) |
| 14 | keepRight | KEEP_RIGHT | KEEP_RIGHT | (fork/keep family) |
| 15/16/17 | ferry enter/exit/change | FERRY_BOAT | FERRY_BOAT | FERRY |
| 18 | uTurnToRoute | U_TURN_RIGHT/LEFT | U_TURN_* | U_TURN_LEFT/RIGHT |
| 19 | roundaboutUTurn | ROUNDABOUT_ENTER_CW/CCW | ROUNDABOUT_ENTER_AND_EXIT_* | ROUNDABOUT_*_ENTER_AND_EXIT |
| 20 | endOfRoadLeft | TURN_NORMAL_LEFT | TURN_NORMAL_LEFT | TURN_LEFT |
| 21 | endOfRoadRight | TURN_NORMAL_RIGHT | TURN_NORMAL_RIGHT | TURN_RIGHT |
| 22 | rampOffLeft | OFF_RAMP_NORMAL_LEFT | OFF_RAMP_NORMAL_LEFT | OFF_RAMP_LEFT |
| 23 | rampOffRight | OFF_RAMP_NORMAL_RIGHT | OFF_RAMP_NORMAL_RIGHT | OFF_RAMP_RIGHT |
| 24 | arrivedLeft | DESTINATION_LEFT | DESTINATION_LEFT | DESTINATION_LEFT |
| 25 | arrivedRight | DESTINATION_RIGHT | DESTINATION_RIGHT | DESTINATION_RIGHT |
| 26 | uTurnWhenPossible | U_TURN_LEFT/RIGHT | U_TURN_* | U_TURN_LEFT/RIGHT |
| 27 | endOfDirections | DESTINATION | DESTINATION | DESTINATION |
| **28–46** | **roundaboutExit N** (N = cp−27) | **ROUNDABOUT_ENTER_AND_EXIT_CW/CCW + `setRoundaboutExitNumber(N)`** | ENTER_AND_EXIT_CW/CCW (generic) + `roundabout_exit_number=N` forwarded | **generic ROUNDABOUT_*_ENTER_AND_EXIT — exit # dropped here** |
| 47 | sharpLeft | TURN_SHARP_LEFT | TURN_SHARP_LEFT | SHARP_LEFT |
| 48 | sharpRight | TURN_SHARP_RIGHT | TURN_SHARP_RIGHT | SHARP_RIGHT |
| 49 | slightLeft | TURN_SLIGHT_LEFT | TURN_SLIGHT_LEFT | SLIGHT_LEFT |
| 50 | slightRight | TURN_SLIGHT_RIGHT | TURN_SLIGHT_RIGHT | SLIGHT_RIGHT |
| 51–53 | changeHighway / L / R | KEEP_LEFT/RIGHT | KEEP_LEFT/RIGHT | FORK_LEFT/RIGHT (via keep) |

**Two lossy joints to know about:**
- Roundabout **enter/exit** distinction is flattened to generic CW/CCW at Templates Host. [verified]
- The roundabout **exit NUMBER** is forwarded into the proto (`setRoundaboutExitNumber`) but **VMSPlugin
  reads only `Type`/`TypeV2`** — it never emits a numbered `TurnType`, so the number is dropped. [verified]

---

## 3. Roundabout WITH_ANGLE → directional Type (the real lever)

If carlink uses `TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW/CCW_WITH_ANGLE` + `setRoundaboutExitAngle(a)`,
Templates Host bucket-maps the **androidx swept angle `a`** (degrees, range [1,361)) into a **directional**
`NavigationState.Maneuver.Type`, which VMSPlugin then maps to a directional `TurnType`. [verified — smali
thresholds `1/6/46/136/171/190/225/315/355/361`]

| androidx exit angle `a` | CW → Type / TurnType | CCW → Type / TurnType |
|---|---|---|
| [1,6) ∪ [355,361) | …CW_U_TURN | …CCW_U_TURN |
| [6,46) | …CW_SHARP_LEFT | …CCW_SHARP_RIGHT |
| [46,136) | …CW_NORMAL_LEFT | …CCW_NORMAL_RIGHT |
| [136,171) | …CW_SLIGHT_LEFT | …CCW_SLIGHT_RIGHT |
| [171,190) | …CW_STRAIGHT | …CCW_STRAIGHT |
| [190,225) | …CW_SLIGHT_RIGHT | …CCW_SLIGHT_LEFT |
| [225,315) | …CW_NORMAL_RIGHT | …CCW_NORMAL_LEFT |
| [315,355) | …CW_SHARP_RIGHT | …CCW_SHARP_LEFT |
| 0 / ≥361 / unmatched | base ENTER_AND_EXIT_CW | base ENTER_AND_EXIT_CCW |

### iAP2 → androidx angle conversion [derived — validate on-vehicle]
iAP2 `0x000b JunctionElementExitAngle` is **driver-relative signed degrees** (0=straight, +90=right,
−90=left, ±180=back). Templates Host expects the **swept angle** (≈180=straight, ≈0/360=u-turn). From the
bucket geometry the conversion is:

- **CCW** (right-side driving, e.g. US): `androidxAngle = (180 − iap2ExitAngle) mod 360`
- **CW** (left-side driving, e.g. UK): `androidxAngle = (180 + iap2ExitAngle) mod 360`
- Map a result of `0` to `360` (range is [1,361)).

Spot-check (CCW/US): driver 0→180 STRAIGHT; +90→90 NORMAL_RIGHT; −90→270 NORMAL_LEFT; +135→45 SHARP_RIGHT;
±180→0/360 U_TURN. ✔ Pick CW vs CCW from drive side (`isLeftDrive`), same as `ManeuverMapper`.

---

## 4. Final leg: TurnType → cluster glyph

- **gminfo3.7:** the `CarIcon` bitmap (CarPlay composed / AA `NAVI_IMAGE`) is cached via the
  `ClusterIconContentProvider` shim and the **app bitmap renders**. `TurnType` is secondary. [verified]
- **VCUNH1:** `TurnType` → (NFSA over Ethernet) → QNX `/usr/bin/hmiCluster/HMIC` → **Altia sprite**. [verified]
  HMIC is a QNX AArch64 PIE ELF (`/usr/lib/ldqnx-64.so.2`) that dlopens the Altia runtime
  (`libaltia.so` + `libAltiaGMRHMI*.so`, Altia model `GMModel`, DeepScreen) and renders via **QNX Screen +
  GLES/EGL**. It maps the incoming `maneuverIconType` integer by a **static atlas lookup**: glyph =
  `onstar_TBT/icn_<N>.png` where **N = the `maneuverIconType` value** (identity map). The bitmap/URI is masked.

### Reachability ceiling on VCUNH1 — FIRMWARE-VERIFIED against the HMIC/Altia atlas (2026-06-06)
The CT5 `GMModel` atlas realizes **only ~57 maneuver icons**: `N ∈ {0–23, 30–47, 99–101, 200–211}`.
That hard-limits what any projected `TurnType` can actually draw:
- **Renders correctly:** turns (slight/normal/sharp L/R), ramps, forks, u-turns, destinations — these
  `icn_N` exist, and carlink's existing `Maneuver.TYPE_*` mapping already hits them.
- **Roundabouts are capped at the GENERIC glyph, regardless of iAP2 richness:**
  - The **fine directional** roundabout sprites (`CW/CCWTrafficCircle{22..337}Degrees`, enum 106–123) and the
    `…NthExit30Degree` set (57–80) have **NO Altia asset** in the GMModel atlas — a directional `TurnType`
    falls back to the generic roundabout glyph. So the **WITH_ANGLE refinement does NOT change the rendered
    CT5 roundabout sprite.**
  - The **numbered**-exit glyphs that DO exist (icn_17–23 / 30–34) are unreachable anyway, because VMSPlugin
    never emits a numbered `TurnType` (it drops `roundabout_exit_number`).
  - ⇒ On CT5, the cluster roundabout glyph is **fixed at generic** through the projection path; the rich iAP2
    angle/exit data cannot improve it. (The one unexplored hook is libaltia's generic `altia_warp_set_angle`
    geometry-warp primitive, but there is no evidence the `ManeuverIcon` path uses it — it's a static
    `icn_N` lookup.)

**Bottom line:** non-roundabout maneuvers already render the correct GM glyph; **roundabouts cannot be
improved on CT5 via projection** (no directional art + number dropped). The improvement opportunity from the
rich iAP2 data is therefore **gminfo3.7-only** (where the app composes/forwards the actual bitmap).

---

## 5. Current gap vs recommended change

- **Today:** `ManeuverMapper.kt` maps roundabouts to plain `TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW/CCW` +
  `setRoundaboutExitNumber(cp−27)`. On VCUNH1 that yields the **generic** roundabout glyph (number dropped).
- **WITH_ANGLE roundabout change (implemented 2026-06-06):** `ManeuverMapper` now switches to
  `…_WITH_ANGLE` + `setRoundaboutExitAngle()` (§3 conversion from iAP2 `0x000b` / AA `NaviTurnAngle`) when an
  angle is available. **NOTE — no CT5 visible effect:** the HMIC/Altia atlas has no directional roundabout art
  (see §4), so CT5 still draws the generic roundabout glyph. The change is kept only because it is (a)
  semantically more correct, (b) gminfo3.7-neutral, and (c) future-proof for any GMModel/AAOS that ships the
  directional art. It does **not** deliver the originally hoped-for CT5 roundabout improvement.
- **Platform-gate (implemented 2026-06-06, the real win):** on VCUNH1, skip the dead bitmap compose
  (`ComposedIconStore`/`IconBitmapRenderer`) + the AA-bitmap shim — they're masked there. VCU detection:
  `Build` device `burmese` / product `burmese_orange` / `ro.build.id` prefix `VCU` (from CT5 build.prop;
  best-effort, untested — no VCUNH1 hardware). gminfo3.7 unaffected.

---

## 6. Provenance

- iAP2 wire/field catalog + cpManeuverType 0–53: `../adapter/RE_Documention/02_Protocol_Reference/iap2_message_catalog.md`,
  `iap2_sdk_delta_r14g17_to_ios26.md` (RE from iOS 26.5 IPSW `AccessoryNavigation.framework` `+keyForType:` tables);
  real captures `carlink_navi_044653.log`. Parsers: `app/.../navigation/Iap2StateParser.kt`, `Iap2RouteParser.kt`.
- App mapping: `app/.../navigation/ManeuverMapper.kt`.
- Templates Host conversion + angle buckets: CT5 `GoogleTemplatesHost.apk`
  `com.android.car.libraries.templates.host.navigation.NavigationStateConverterImpl` (smali, `getNavStateType`).
- VMSPlugin: CT5 `VMSPlugin.apk` `com.gm.vmsplugin.navstate.NavigationStateProtoUtils.ManeuverTypeToTurnType` (`:372-507`).
- TurnType enum + native-render finding: CT5 `gm.navigation.enums.TurnType`, `com.gm.nfsa.Protos$maneuverIconType`.
- QNX cluster renderer + atlas (final leg): `/usr/bin/hmiCluster/HMIC` (QNX AArch64 ELF, dlopens
  `libaltia.so`/`libAltiaGMRHMI*.so`, Altia `GMModel`, QNX Screen + GLES) from `system_la.img`; glyph atlas
  `onstar_TBT/icn_<N>.png` (N = `maneuverIconType`) in `libAltiaGMRHMIData*.so` / `hmi_data_la.img`. Realized
  set ~57 icons `{0–23,30–47,99–101,200–211}`; directional roundabout 106–123 absent. Artifacts:
  `_cluster_authority_analysis/ct5_hmic/`.
- Cluster architecture (QNX HMIC / Altia / blind Ethernet handoff): see `cluster_navigation.md` (2026-06-06 block).
- Extraction artifacts: `/Users/zeno/Downloads/misc/GM_research/gm_aaos/_cluster_authority_analysis/`.
