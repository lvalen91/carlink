# GM Info 3.7 Memory Pressure Analysis

**Device:** GM Info 3.7 (gminfo37)
**Platform:** Intel Apollo Lake (Broxton)
**Android Version:** 12 (API 32)
**Research Date:** February 2026
**Evidence:** 3.7GB logcat corpus (27 files, Feb 11-19, 2026), 4.7MB metrics (30 captures), 45MB CPC200 logs

---

## System Memory

- 6GB DDR3L physical
- 5.66GB visible to kernel (~604MB reserved by GHS hypervisor)
- Available at boot: ~3.1GB (debloated) vs ~2.5GB (factory)

## LMK (Low Memory Killer)

- Uses PSI (Pressure Stall Information) monitors
- threshold_ms=100 for levels 1 and 2
- **513 total kills across 27 logcat files**
- Per-file range: 2 to 119 kills per session
- Worst session: logcat_20260214_182011.log with 119 kills (factory config)
- Max thrashing ratio: 3,700% (com.gm.teenmode)
- Settles to stable state within ~30s

### LMK Kill Reasons

- **"low watermark breached"** — majority of kills
- **"min watermark breached"** — severe memory pressure
- **"device is not responding"** — 19+ occurrences; victims at oom_adj=200: gmcarmediaservice (4x), alexa (2x), vac, hvac, inputmethod

## Debloat Analysis (CarLink Streaming vs Factory)

- Factory idle: HOTTER and HIGHER CPU load than debloated during CarPlay streaming
- 36 packages removed: 34% fewer packages, 36% fewer services
- Memory: debloated 3.1GB free at boot vs factory 2.5GB
- Google Services: ~443MB acting as LMK cushion
- CarLink memory: 76.6MB idle, 127.2MB peak, 38.6MB post-trim; NEVER killed by LMK
- Factory: 8 ALSA underruns, 4 Davey! events (worst 225s!); debloated: 0 underruns, 0 Davey!

## Crash Analysis

12 FATAL EXCEPTION events found across 3.7GB logcat corpus (3 distinct bugs):

1. **com.google.android.configupdater:** PendingIntent FLAG_IMMUTABLE crash (8 occurrences, non-fatal, restarts silently). Android 12 PendingIntent mutability enforcement — Google never patched this for AAOS.

2. **zeno.carlink:** MediaBrowserService session token crash (2 occurrences). Symptom of a structural AAOS limitation, not an isolated app bug. `MediaBrowserServiceCompat.setSessionToken()` is one-shot per Service lifetime — once set, a second call throws `IllegalStateException: The session token has already been set`. Carlink's `CarlinkManager.initialize` triggered this on display-mode reinit; guarded with a static `mediaSessionToken` + `updateSessionToken()` to prevent the double-set. The deeper issue (third-party app session destruction stranding the cluster on a dead token until reinstall/reboot) is structural and cannot be fully fixed from third-party scope. See `known_issues.md` § "Third-Party Media MediaSession Token Mismatch" for the full picture.

3. **com.google.android.apps.automotive.templates.host:** ForegroundServiceStartNotAllowedException (2 occurrences). Boot race condition — `BootCompleteReceiver` calls `startForegroundService` when not yet allowed. Google AAOS platform bug.
