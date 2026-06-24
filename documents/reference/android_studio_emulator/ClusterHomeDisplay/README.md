# ClusterHomeDisplay

Unified cluster home for the AAOS instrument cluster. Renders **navigation + media side-by-side at all times**, and optionally overlays a CarPlay AltVideo (USB MsgType **0x2C**) feed on top of the cards when the companion host app (`zeno.carlink`) pushes one over AIDL.

Replaces the toggle-via-VHAL pattern used by the sibling `ClusterNavigationDisplay` and `ClusterMediaDisplay` apps. Hooks both `config_clusterMapActivity` (VHAL=1) **and** `config_clusterMusicActivity` (VHAL=2) at priority 100, so the cluster lands here regardless of which UI type the OS asks for.

## Auto-default on boot (no manual command)

ClusterHome foregrounds itself on the cluster automatically on every boot — **no manual VHAL
command needed** — provided the app is **platform-signed** (see Requirements + `keys/`).

- `BootCompletedReceiver` launches `ClusterHomeActivity` directly onto the cluster display via
  `ActivityOptions.setLaunchDisplayId` (retrying until the cluster display, `ClusterOsDouble-VD`,
  comes up).
- The `onPause` keep-alive re-asserts that same direct launch if something preempts it (e.g.
  Templates Host's `ClusterTurnCardActivity` during an active route) — burst-capped to avoid
  ping-pong.

Both rely on the signature-level `INTERNAL_SYSTEM_WINDOW`, granted only to a platform-signed app.
The older `CarPropertyManager.setIntProperty(CLUSTER_SWITCH_UI=MAPS)` approach does **not** work
from an app (`CLUSTER_SWITCH_UI` isn't in the app-facing `carPropertyConfig` list — it throws
"property ID is not supported"); only the VHAL-inject shell command can drive it.

Manual fallback (debug-signed build, or to force a switch):

```bash
adb shell cmd car_service inject-vhal-event 0x11400F34 1
```

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
- **`test-keys` image** (`adb shell getprop ro.build.tags` → `test-keys`) — required for platform-signing (below). Standard Android Studio automotive emulators are test-keys.
- **Platform-signed APK** — the app must be signed with the AOSP platform key to hold `INTERNAL_SYSTEM_WINDOW` (for the boot/keep-alive cluster launch). `deploy.sh` does this automatically using the public test-keys in `keys/` (see `keys/README.md`). A debug-signed build still installs but loses auto-default/resilience and needs the manual VHAL command each boot.
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

## Removing the Secondary (Cluster) Display Entirely — Simplified Single-Display Emulator

If you don't want a cluster at all (a plain main-IVI-only AAOS emulator), the whole secondary
display is controlled by **one line** in `/product/etc/build.prop`:

```
hwservicemanager.external.displays=<port>,<width>,<height>,<dpi>,<flag>
# e.g. stock ultrawide = 1,528,792,160,0
```

The AVD `config.ini` defines **only** the main display — the cluster physical panel
(`EMU_display_1`) and the `ClusterOsDouble-VD` virtual display are created **solely** from this
prop. Comment it out (or delete it) and the entire cluster stack disappears: no `EMU_display_1`,
no `ClusterOsDouble-VD`, and `com.android.car.cluster.osdouble` / `ClusterHomeActivity` have no
surface to render into. `cmd display get-displays` then reports only Display 0.

**Procedure (verified Jun 2026 — works both directions):**

The emulator binary lives at `$ANDROID_HOME/emulator/emulator` (add it to `PATH`, or use the full
path). Replace `ultrawide` with your AVD name. **Use this exact launch command for every
(re)start in this procedure** — a WRITABLE system + COLD boot are both required:

```bash
"$ANDROID_HOME/emulator/emulator" -avd ultrawide -writable-system -no-snapshot &
```

```bash
# 1. Launch the AVD with the command above (writable system, cold boot), then:

# 2. Make /product writable and comment the line IN PLACE (preserves SELinux context).
adb root && adb remount                                          # "Using overlayfs for /product"
adb shell 'sed -i "s/^hwservicemanager.external.displays=/#&/" /product/etc/build.prop'
adb shell grep external.displays /product/etc/build.prop         # -> #hwservicemanager.external.displays=...

# 3. COLD boot again: kill the process and relaunch with the SAME command above. Do NOT `adb reboot`.
adb emu kill                                                     # wait for it to exit, then re-run the emulator command

# 4. Verify (single display, cluster gone, /product still healthy):
adb shell 'cmd display get-displays'                             # only "Display id 0" (2400x960)
adb shell 'dumpsys display | grep -c EMU_display_1'              # 0
adb shell 'dumpsys display | grep -c ClusterOsDouble-VD'         # 0
adb shell getprop ro.product.product.brand                       # google  <- /product props still load = clean
```

To **re-enable** the cluster: uncomment the line (or restore the value, e.g. `1,1920,620,160,0`)
the same way and cold boot. With the line present, `EMU_display_1` and `ClusterOsDouble-VD` are
back (verified: both appear; with it commented, both gone).

> ⚠️ **Gotchas (these will waste hours otherwise):**
> - **Always launch with `-writable-system`.** If you ever start the *same* AVD with `emulator -avd ultrawide` **without** `-writable-system` in between, it desyncs the overlay and **the entire `/product/etc/build.prop` stops loading at boot** — `getprop ro.product.product.brand` goes empty and the cluster vanishes for the *wrong* reason (corruption, not your edit).
> - **Cold boot only** (`adb emu kill` + relaunch). A guest `adb reboot` desyncs the external-display registration and won't cleanly apply the change.
> - **Edit in place with `sed -i`** — do **not** `adb pull`/`adb push` the file (that changes its perms to 666 and can disturb the SELinux context).
> - **Recovery if `/product` props stop loading** (`ro.product.product.brand` empty): the overlay is wedged — cold boot once with `-wipe-data` to revert to a pristine image, then redo the edit. (`-wipe-data` also reverts any prior `hwservicemanager.external.displays` customization back to the stock `1,528,792,160,0`, and wipes installed apps / this priv-app deploy.)

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
