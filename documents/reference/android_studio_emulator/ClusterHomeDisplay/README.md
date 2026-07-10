# ClusterHomeDisplay

Unified cluster home for the AAOS instrument cluster. Renders **navigation + media side-by-side at all times**, and optionally overlays a CarPlay AltVideo (USB MsgType **0x2C**) feed on top of the cards when the companion host app (`zeno.carlink`) pushes one over AIDL.

Replaces the toggle-via-VHAL pattern used by the sibling `ClusterNavigationDisplay` and `ClusterMediaDisplay` apps. Hooks both `config_clusterMapActivity` (VHAL=1) **and** `config_clusterMusicActivity` (VHAL=2) at priority 100, so the cluster lands here regardless of which UI type the OS asks for.

## After you have run this at least once
You must — after every emulator reboot — kick the cluster to map mode once so the unified home gets foregrounded:

```bash
adb shell cmd car_service inject-vhal-event 0x11400F34 1
```

ClusterHomeSample does not auto-route on boot; the VHAL ping pulls the home activity in. Once foregrounded, the keep-alive in `ClusterHomeActivity` will re-foreground itself if Templates Host's `ClusterTurnCardActivity` preempts it.

## How It Works

```
ClusterHomeSample receives CLUSTER_SWITCH_UI={1,2}
  → launches ClusterHomeActivity (via RRO override of both map + music slots)
    ├── Navigation pane (left, 60%)
    │     ClusterHomeManager.registerClusterNavigationStateListener
    │       → NavigationStateProto byte[] from Templates Host
    │       → maneuver icon + distance + cue + ETA
    │       → 5 s idle watchdog clears stale state on session end
    │
    ├── Media pane (right, 40%)
    │     MediaSessionManager.getActiveSessions
    │       → first active controller's metadata + playback state + album art
    │
    └── AltVideo overlay (full 1920×620, GONE until producer pushes)
          bindService → zeno.carlink/.ipc.NaviVideoSourceService
          registerSink(INaviVideoSink)
            → onStreamConfigured(w,h,fps)  → SurfaceView VISIBLE
            → onFrame(Annex-B NALs, ptsUs, isKeyFrame)
            → onStreamEnded()              → SurfaceView GONE
          NaviDecoder (MediaCodec, async, drops to first IDR)
            → SPS/PPS extracted as csd-0/csd-1
            → decoded frames → Surface composited at 1920×620 1:1
```

Both panes are alive simultaneously. The overlay layer is invisible until carlink_native has 0x2C frames to push; when it appears, it covers the cards at native stream aspect with no scaling distortion.

## Requirements

- **AAOS emulator** with cluster display, API 32+ (tested on API 35)
- **adb root** + **adb remount** (userdebug build)
- **Gradle** and **Android SDK** installed
- **zeno.carlink** companion app installed and running for the AltVideo overlay (otherwise the app runs in degraded mode — cards only, no overlay)

## Project Structure

```
ClusterHomeDisplay/
├── app/                                  # Main application
│   ├── build.gradle.kts                  # buildFeatures { aidl = true }
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── aidl/com/carlink/ipc/
│       │   ├── INaviVideoSink.aidl       # consumer interface (this app implements)
│       │   └── INaviVideoSource.aidl     # producer interface (zeno.carlink implements)
│       ├── java/com/carlink/cluster/home/
│       │   ├── ClusterHomeActivity.java  # main activity, all wiring
│       │   ├── NaviDecoder.java          # MediaCodec H.264 decoder
│       │   └── BootCompletedReceiver.java
│       └── res/
│           ├── layout/activity_cluster_home.xml
│           ├── drawable/ic_navigation.xml
│           ├── drawable/ic_music_note.xml
│           └── values/strings.xml
├── overlay/                              # RRO overlay (pre-built)
│   ├── AndroidManifest.xml
│   ├── res/values/config.xml             # overrides BOTH config_clusterMapActivity + config_clusterMusicActivity
│   └── CarlinkClusterHomeOverlay.apk     # signed overlay APK (ready to push)
├── permissions/
│   └── privapp-permissions-clusterhome.xml
├── reference/
│   └── emulator_cluster_config.md        # AAOS emulator cluster display reference (AVD config,
│                                         #   ClusterOsDouble dimens, live dumpsys, topology)
├── deploy.sh                             # one-command deploy script
├── INTEGRATION_CARLINK_NATIVE.md         # producer-side spec for the AIDL + 0x2C wiring in zeno.carlink
├── build.gradle.kts
├── settings.gradle.kts
└── README.md
```

## Quick Start

### 1. Build

```bash
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew assembleDebug
```

### 2. Deploy

```bash
./deploy.sh              # defaults to emulator-5554, full install + reboot
./deploy.sh fast         # push APK + pm install -r + restart activity (no reboot)
./deploy.sh emulator-5556  # different serial
```

`deploy.sh fast` is what you'll use during development. The full mode:
1. Roots and remounts the emulator
2. Pushes the APK to `/system/priv-app/ClusterHomeDisplay/`
3. Pushes privapp permissions XML to `/system/etc/permissions/`
4. Pushes the RRO overlay to `/product/overlay/`
5. Reboots and verifies installation

### 3. Test

Kick the cluster into the home UI (either VHAL value works — both are overridden):

```bash
adb shell cmd car_service inject-vhal-event 0x11400F34 1   # MAPS slot
adb shell cmd car_service inject-vhal-event 0x11400F34 2   # MUSIC slot
```

Tail logs (use a single tag — most useful):

```bash
adb logcat -s CarlinkHome,CarlinkHome.NaviDec
```

Take a cluster screenshot (HWC token re-discoverable via `dumpsys SurfaceFlinger --display-id`):

```bash
adb exec-out screencap -p -d 4619827551948147201 > /tmp/cluster.png
```

## Companion: AltVideo (USB 0x2C) from zeno.carlink

This app is the **consumer** half of the AltVideo pipeline. The producer (`zeno.carlink`) demuxes USB MsgType 0x2C from the CPC200-CCPA adapter and forwards Annex-B H.264 NAL units over AIDL. Full producer-side spec is in `INTEGRATION_CARLINK_NATIVE.md`. The contract in short:

| AIDL surface | Implemented by | Direction |
|---|---|---|
| `INaviVideoSource` | `zeno.carlink/.ipc.NaviVideoSourceService` | producer exposes |
| `INaviVideoSink`   | `com.carlink.cluster.home` (this app)        | consumer implements |

Activation flow:
1. `zeno.carlink` sends `naviScreenInfo {width, height, fps, safearea}` in BoxSettings (USB type 0x19) to the adapter.
2. Adapter unlocks 0x2C streaming for iOS 13+ CarPlay sessions (`AdvancedFeatures=1` one-time unlock required).
3. iPhone emits SPS+PPS+IDR+frames at the negotiated geometry; adapter passes through verbatim.
4. `zeno.carlink` demuxes 0x2C, strips the 36-byte USB+video header, broadcasts via `INaviVideoSink.onFrame`.
5. This app's `NaviDecoder` extracts SPS/PPS as `csd-0/csd-1`, configures MediaCodec, renders to the root-level SurfaceView.

If `zeno.carlink` is absent, restarts, or is reinstalled, this app falls into degraded mode (cards only) and exponentially backs off rebind attempts (1s → 30s cap) until the producer reappears. No user action required.

### Safe Area

The host advertises a safe-area inset so the iPhone keeps interactive CarPlay UI clear of host-rendered cluster chrome. For the AAOS emulator (gauge arcs on left/right edges of the 1920×620 VirtualDisplay, no top/bottom obstructions), the producer ships:

```json
"safearea": { "x": 100, "y": 0, "width": 1720, "height": 620, "outside": 0 }
```

Turn cards / lane guidance / speed-limit chips land inside the 1720×620 inner rect; the map background may still extend to the full 1920×620 since the iPhone treats non-interactive layers separately.

## Why Priv-App?

The app needs three platform-only permissions:

| Permission | Used for |
|---|---|
| `android.car.permission.CAR_MONITOR_CLUSTER_NAVIGATION_STATE` | subscribe to NavigationStateProto |
| `android.car.permission.CAR_INSTRUMENT_CLUSTER_CONTROL` | CLUSTER_SWITCH_UI keep-alive |
| `android.permission.MEDIA_CONTENT_CONTROL` | `MediaSessionManager.getActiveSessions()` |

All three are `signature|privileged` — only granted to apps installed in `/system/priv-app/` (or signed with the platform key). A normal `adb install` cannot obtain them. The deploy script handles the priv-app placement.

A custom permission `com.carlink.permission.NAVI_VIDEO_STREAM` (declared by zeno.carlink at `signature|privileged`) gates the AIDL service binding. Both APKs must be signed with the same key.

## Why RRO Overlay?

AOSP `ClusterHomeSample` reads `config_clusterMapActivity` and `config_clusterMusicActivity` from its resources to decide which Activity to launch on the cluster. The RRO overrides **both** strings to point to `ClusterHomeActivity`, so the unified home wins regardless of which slot the OS asks for.

The overlay sits on `/product/overlay/` (not `/data/`) because the target resource's overlayable policy requires `product|system|vendor` partition placement. Priority is 100, above per-slot sibling overlays at 99.

## Cluster Display Details (AAOS Emulator)

| Property | Value |
|---|---|
| Display ID | 3 (virtual, type=VIRTUAL, owner=`com.android.car.cluster.osdouble`) |
| Resolution | 1920 × 620 @ 160dpi |
| Hardware panel | EMU_display_1: 1920 × 720 (bottom 100 px is ClusterOsDouble's gauge strip, outside our sandbox) |
| Usable bounds | full 1920 × 620, no decor insets (`nonDecorInsets=[0,0][0,0]`) |
| Refresh | 60 Hz |

See `reference/emulator_cluster_config.md` for the full topology: AVD `config.ini` / `hardware-qemu.ini`, `ClusterOsDouble.apk` dimens decode, and live `dumpsys` capture.

## Cluster UI Types (CLUSTER_SWITCH_UI / VHAL 0x11400F34)

| Value | UI Type | Activity (after this overlay) |
|---|---|---|
| 0 | HOME | ClusterHomeSample's stock home |
| 1 | MAPS | **ClusterHomeActivity** (this app) |
| 2 | MUSIC | **ClusterHomeActivity** (this app) |
| 3 | PHONE | ClusterPhoneActivity |

Values 1 and 2 both land here because we override both resource slots at priority 100.

## Rebuilding the RRO Overlay

If you need to change the target component or add another slot:

```bash
# Edit overlay/res/values/config.xml, then:
export ANDROID_HOME="$HOME/Library/Android/sdk"
AAPT2="$ANDROID_HOME/build-tools/35.0.0/aapt2"
APKSIGNER="$ANDROID_HOME/build-tools/35.0.0/apksigner"

cd overlay
$AAPT2 compile --dir res -o compiled.zip
$AAPT2 link -o CarlinkClusterHomeOverlay.apk \
  -I $ANDROID_HOME/platforms/android-35/android.jar \
  --manifest AndroidManifest.xml compiled.zip

# Sign (create keystore first if needed — see ClusterMediaDisplay/README.md):
$APKSIGNER sign --ks debug.keystore --ks-pass pass:android \
  --key-pass pass:android --v2-signing-enabled true \
  CarlinkClusterHomeOverlay.apk

rm -f compiled.zip
```

## See Also

- `INTEGRATION_CARLINK_NATIVE.md` — producer-side spec for the AIDL + 0x2C wiring in `zeno.carlink`
- `reference/emulator_cluster_config.md` — AAOS emulator cluster display topology (AVD, ClusterOsDouble, dumpsys snapshots)
- `../ClusterMediaDisplay/README.md` — single-pane media-only sibling app (legacy, replaced by this)
- `../ClusterNavigationDisplay/README.md` — single-pane nav-only sibling app (legacy, replaced by this)
- Adapter RE docs:
  - `../../adapter/RE_Documention/02_Protocol_Reference/video_protocol.md` — USB 0x2C frame format, naviScreenInfo, SafeArea
  - `../../adapter/RE_Documention/04_Implementation/host_app_guide.md` — host app responsibilities, AltVideo→Cluster integration
