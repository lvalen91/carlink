# GM Info 3.7 Known Issues

**Device:** GM Info 3.7 (gminfo37)
**Platform:** Intel Apollo Lake (Broxton)
**Android Version:** 12 (API 32)
**Research Date:** February 2026
**Evidence:** 3.7GB logcat corpus (27 files, Feb 11-19, 2026), 4.7MB metrics (30 captures), 45MB CPC200 logs

---

## SELinux Denials (7,811 total across 3.7GB corpus, 25 files)

Per-file range: 111-662. The previously reported "462" was from a single file (logcat_20260219).

- **CBC (Connected Body Control):** Most denials
- **RVC (Rear Vision Camera):** Camera-related denials
- **SXM (SiriusXM):** Radio service denials
- No USB or CarPlay-related denials observed

## Audio Issues

- **ECNR HAL:** `vendor.gm.audio@1.0::IECNRControl` fails 8 times per boot in rapid succession (~100ms intervals). Each attempt cycle: VAC -> hwservicemanager -> "not registered" -> "unable to set property" -> "not installed". The service is genuinely missing from the system image — not a timing issue.
- **Volume table:** XML load failure (volume control handled externally by amp/DSP)
- **Audio underruns:** 1,710 CARLINK_AUDIO [AUDIO_UNDERRUN] events across 16 files + 20 PulseAudio ALSA buffer underruns across 8 files. Per-session range: 2-456 underruns. Mostly during Siri transitions.
- Factory: 8 ALSA underruns, 4 Davey! events; debloated: 0 of each

## AVB Streamhandler Issues (404 events across 19 files)

- TX worker oversleep (scheduling jitter) — worst: 11.5ms (limit 2ms)
- ALSA engine oversleep — worst: 12.2ms (limit 4ms)
- Both are intermittent timing issues, not fatal
- Directly correlates with PulseAudio underruns (AVB scheduling miss -> downstream ALSA buffer starvation)

## Thermal Subsystem

4 zones from Thermal HAL v2.0:

| Zone | Boot Range | Streaming Range | Throttle | Shutdown |
|------|-----------|-----------------|----------|----------|
| TCPU | 15-39C | 28-33C | 108C | 109C |
| TGPU | tracks TCPU within 0-5C | tracks TCPU within 0-5C | 108C | 109C |
| TBATTERY | constant 25C (virtual stub) | constant 25C | N/A | N/A |
| TSKN | constant 25C (no real sensor) | constant 25C | N/A | N/A |

Only TCPU and TGPU are real thermal zones. TBATTERY and TSKN are stubs returning fixed values.

## Display/Graphics

- **iahwcomposer init:** EDID 640 bytes, CRTC id 35, DRM connector 37
- 3 physical displays configured but only display 0 exists
- EXPLICIT SYNC enabled, High Quality Scaling
- No DCIP3/wide gamut, no HDR, no VRR support
- Gralloc format conversion errors (intermittent)
- Wide color gamut init failures (display doesn't support wide gamut)

## Google Templates Host Crash

`com.google.android.apps.automotive.templates.host` throws ForegroundServiceStartNotAllowedException during boot. `BootCompleteReceiver` calls `startForegroundService` when the app is not yet in a state that allows foreground service starts. This is a Google AAOS platform bug (2 occurrences observed).

## Third-Party Media MediaSession Token Mismatch (AAOS structural bug)

User-visible symptom: any third-party media app — including carlink_native — that crashes, is force-closed, or has its session destroyed (OS sleep, USB disconnect, etc.) will keep producing metadata, but the cluster will go blank or stale while the in-dash native Media Player continues to display correct song info. The only fixes are app reinstall or a head unit reboot.

Root cause is a structural Android framework limitation, not a GM-specific bug:

- `MediaBrowserServiceCompat.setSessionToken()` is **one-shot per Service instance lifetime**. A second call on the same Service throws `IllegalStateException: The session token has already been set`.
- When the app's session is destroyed (`onSessionDestroyed`), the underlying MediaSession dies but the singleton `MediaBrowserService` keeps a stale reference to the dead token. The app cannot hand out a fresh token from the same Service.
- Privileged AAOS consumers (Media Center / GMCarMediaService / ClusterService) keep their bindings to the old token instead of rediscovering the new session.
- Google's official guidance ([Building a media browser client](https://developer.android.com/media/legacy/audio/mediabrowser)) confirms: *"the session cannot become functional again within the lifetime of the MediaBrowserService"* — clients must `disconnect()` so the OS can destroy the Service. On AAOS the clients are GM system services the third-party app cannot control.
- Why the in-dash player still works while the cluster doesn't: they read down different paths. The native Media Player goes via `MediaSessionManager` directly and recovers; the cluster path goes through `GMCarMediaService` → `ClusterService` and stays bound to the dead token.

Workaround applied in carlink_native: deactivate-and-reactivate the existing `MediaSession` rather than recreate it (`MediaSessionManager.refresh()`); on `DISCONNECTED` use `STATE_PAUSED` instead of `STATE_STOPPED` + cleared metadata so cluster controllers stay attached. Static `mediaSessionToken` with guarded `updateSessionToken()` prevents the double-set crash on display-mode reinit. None of this fully recovers from a process-death scenario — only the OS killing the Service does.

Crash references: see `runtime/memory_pressure.md` for the two `setSessionToken()`-double-call FATAL EXCEPTION events observed in the 3.7GB logcat corpus, and the surrounding section on broader AAOS media-token state.

Affects: all GM AAOS platforms (Info 3.x and VCU/CIP) — this is an Android-framework-level limitation, not platform-specific.

## VIP MCU Health Telemetry

PLSS_PMPAL reports at 1Hz via IPC: `health:48 10 0 0 0 0` (consistent across all sessions). Format: 6 integer fields, values stable — no anomalies detected.

## CPC200 Adapter Issues

- Unrecovered AirPlay packets during Siri to media transitions
- MFi IC: clone/compatible, fallback address works (not a real Apple MFi chip)
- WiFi channel 161 (5.8GHz) — may have interference issues in some environments

## Y177 Specific Issues

- SELinux is PERMISSIVE (significant security regression)
- VIP security function is a 4-byte stub (disabled)
- CVE-2024-53104 and CVE-2024-36971 exploitable on Y177

---

**Data sources:** `/Volumes/POTATO/logcat/20260219/`, 3.7GB logcat corpus (27 files, Feb 11-19, 2026)
