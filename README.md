# Carlink — Personal (CarPlay-only)

A personal, stripped-down build of [Carlink](https://github.com/lvalen91/carlink_flutter) for **my own use** on a GM **gminfo3.7** head unit. CarPlay only. Shared with no expectation of support — fork it and make it yours.

This is the **`cp-stripped`** branch/variant of the main Carlink Android app. It is a **drop-in** of the same package (`zeno.carlink`): it installs over the main app and reuses the same protocol/media engine. The difference is everything *around* the engine — the UI is rebuilt and a lot of features the main app carries for everyone else are removed here.

> [!IMPORTANT]
> Target platform is my **2024 Silverado gminfo3.7** (Intel, AAOS 12L / API 32) with a **Carlinkit CPC200-CCPA** on firmware 2025.10. That is the only hardware this is built and tested against. Anything else is **untested** — treat it as such.

## How this differs from the main Carlink

**Removed** (CarPlay-only focus):
- Android Auto **projection** (CarPlay only; AA-paired devices still *list* in device management)
- AAOS **Instrument Cluster** nav + **HUD** support, and the Car App Library / Templates host
- The secondary **0x2C** cluster/nav video stream
- **GPS / GNSS** forwarding to the phone
- OEM **cluster icon** upload + the `play` store flavor (sideload-only now)
- The in-app **adapter-config** and **display-mode** screens (immersive is hardcoded)
- **Log-to-file** export (release logs are WARN/ERROR-only to logcat)

`minSdk` is raised to **32** (gminfo is Android 12L); the build is **sideload-only** (`sideloadDebug` / `sideloadRelease`).

**Kept / focused on:**
- CarPlay projection (HWC overlay SurfaceView, low-latency)
- Now-playing **metadata + album art** to the AAOS media surface
- Device management (connect / disconnect / forget paired devices)

## What's new here

- **Liquid Glass UI.** The idle/host-UI dashboard is a frosted-glass design (specular rim, sheen, translucent panels, large radii) that follows the **AAOS day/night theme** automatically.
- **"Controls" host-UI button.** The CarPlay OEM/exit icon is labeled **Controls** and opens the dashboard *as a frosted overlay over the live session* — with a frozen, blurred snapshot of the CarPlay feed behind the cards (iOS-style), and a **Return to CarPlay** button to dismiss.
- **CarPlay follows day/night.** When AAOS switches light/dark, the app sends the CarPlay night-mode command (16/17) mid-session, so CarPlay's own UI follows the head unit — without dropping the session.

## Screenshots

The **Controls** dashboard — the host-UI overlay — as Liquid Glass over the live CarPlay feed, following the head unit's day/night theme:

| Night | Day |
| --- | --- |
| ![Controls dashboard — night](/screenshots/dark_ctrl.png) | ![Controls dashboard — day](/screenshots/light_ctrl.png) |

CarPlay itself follows the AAOS day/night switch — the night-mode command is forwarded mid-session, session uninterrupted:

| Night | Day |
| --- | --- |
| ![CarPlay home — night](/screenshots/dark_hs.png) | ![CarPlay home — day](/screenshots/light_hs.png) |

## Adapter firmware

Unlike the main app, this variant does **not** ship or push the adapter-side patches. The iAP2 route-data restore existed for cluster navigation and the NMEA correction for Android Auto GPS forwarding — both removed here — so neither `ARMiPhoneIAP2.patched` nor `aa_gps_fix.sh` is included. It runs against **stock adapter firmware**. (If your CPC200-CCPA happens to be running custom firmware from the main project, that lives on the adapter and is independent of this app.)

## Build & install

```sh
./gradlew :app:assembleSideloadDebug
adb install -r -d app/build/outputs/apk/sideload/debug/app-sideload-debug.apk
```

It installs over the main app (same `applicationId`); the version is bumped so a fresh init runs on first drop-in.

## Status

Personal side project, changing whenever I feel like it. Optimized only for video/audio on gminfo3.7 — **that is the only focus.** Compatibility elsewhere is unverified. Issues/PRs welcome but unsupported.

## Credits

Same lineage as the main app — these made it possible:
- [Carplay by Abuharsky](https://github.com/abuharsky/carplay)
- [Node-Carplay by Rhysmorgan134](https://github.com/rhysmorgan134/node-CarPlay)
- [LIVI by f-io](https://github.com/f-io/LIVI)
- [PyCarplay by Electric-Monk](https://github.com/electric-monk/pycarplay)
