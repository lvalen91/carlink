# Carlink

Carlink is a **native** Kotlin code implementation from the original [Flutter-based](https://github.com/lvalen91/Carlink) app.
I did this app for me and my use, but sharing so others can use it. Don't expect or demand support but I'll help where i can.

### Requirments

- For AAOS 10 (Android 10) and higher
- For (Carlinkit CPC200-CCPA)[https://www.carlinkit.com/ccpa] on firmware 2025.10


## [XDA Developer Forums](https://xdaforums.com/t/carlink.4774308/)


## Work In progress - Something is always changing.. Not always good

> [!IMPORTANT]
>
> Limitations
> Instrument Panel Cluster and Heads Up Display (HUD) Support
>
> Song Information:
> Due to an AAOS bug. Song metadata displaying on the Cluster can go blank. The simplified explanation, the App creates a session 'token' and hands it to the OS. The Native Media Player and the Cluster both read song info from it. The boot/auto-launch, head unit reboot, and adapter config-change cases have been fixed, so those now repopulate the Cluster card correctly. The one case still left is force-stopping the app and relaunching it: the OS-side CarLauncher controller is nulled when the session is destroyed and never rebound (an AOSP limitation), so the Cluster keeps holding the dead controller and goes blank. There are no known ways for third-party apps to fix that last case. Emulator testing shows Apple Music and Spotify hit the same stale card, confirming it's a platform limitation, not app-side.
>
> Navigation Turn-By-Turn
> On STOCK adapter firmware, the firmware strips away the rich nav details iPhones provide and narrows it down to basic manuvers. The custom adapter firmware (see below) restores full iAP2 route information, so CarPlay now gets complete maneuver/route data. CarPlay itself does not send ready-made navigation icons, so the app generates them on-device from the recovered iAP2 geometry (including correct roundabouts drawn from the real junction arms). Android Auto does provide manuver images, which are forwarded to the cluster. They are more detailed and accurate.
>
> Vehicles running the GM VCU radio (Bosch VCUNH1 — most EVs and newer Fuel/ICE vehicles) on GM AAOS 14+ do not use any icon provided by the app. GM VMSPlugin still passes them, but the Cluster ECU ignores it and renders from its own internal icon set based on the manuver type (VMSPlugin's setManeuverType() enum forces the GM glyph). Earlier VCU vehicles on AAOS 12 or 13 may have behaved differently before being upgraded.
>
> Cluster Navigation Icons for forks / other developers (gminfo3.7):
> This mechanism only does anything useful on the **gminfo3.7** platform (Info 3.7, AAOS 12, e.g. my Silverado). The hook itself (the unregistered authority) is firmware-verified to be open/claimable on the newer **GM VCU** platform too (Bosch VCUNH1 — the EV and newer-ICE radios on AAOS 14, which ships the same unmodified Google Templates Host), but on VCUNH1 it is **bypassed**: GM's VMSPlugin force-renders the cluster glyph from a maneuver-type enum (setManeuverType), so the app's icon is masked regardless of who owns the authority (see the GM VCU note above). The way the app gets its own maneuver icons into the gminfo3.7 cluster is by claiming a content provider 'hook' (the authority `com.google.android.apps.automotive.templates.host.ClusterIconContentProvider`) that GM's Templates Host references but never registers — GM leaves it open/unclaimed. The app registers a provider on that authority so the cluster's icon calls land on it and get the forwarded Android Auto maneuver bitmaps. Because I was the first developer to upload a bundle claiming that authority, Google reserved it to me — Google enforces that a content provider authority is globally unique and locked to its first publisher. The catch is for everyone else: because GM exposes only that ONE open hook and I already claimed it, no other developer's bundle can claim the same authority — Google rejects it on upload. The code is left in place so another dev can still build and ship from this repo with one change: a fork changes only its `applicationId` and the `play` flavor automatically derives a unique authority (`<applicationId>.ClusterIconContentProvider`) to get past the Play Console check. But GM AAOS only ever calls the real GM authority, so that derived provider is never invoked on the head unit — it lets a fork upload, it does not actually deliver icons to the cluster. Net result: a fork can publish, but without the GM authority the cluster shows text navigation only — no maneuver icons at all.
>
> My truck doesn't have an HUD, so i cannot test this. However, some Silverado and Hummer EV users have reported that the HUD does show navigation. I can only test for the software in my Silverado. GM and others can easily change how that is controlled so if it works, great. If it doesnt.. too bad.

> [!TIP]
> Before complaining about Audio issues.
> 1. Disconnect and forget the Phone and Vehicle from each others Bluetooth. The adapter defaults to audio routing THROUGH the adapter for both microhone input and audio output. Make sure you allowed microphone access to the app.
> 2. Steering Wheel Voice/Call Control doesn't work. That is a system-level app featuer and this is not that. If you want Steering Wheel Voice Controls, then ignore #1 and set the in-app adapter audio routing to Bluetooth. THe Phone and Vehicle will stay connected for ALL audio related events.

> [!IMPORTANT]
>My 2024 Silverado gminfo3.7 Intel AAOS radio is the target Platform and my only hardware for testing. 

> [!WARNING]
> *Compatability on anything else is not verified* and should be treated as **untested**. Optimized for Video and Audio performance on the gminfo3.7. That is **my only focus** you can fork this repo and optimize it for your own needs.

> [!TIP]
Remember kids: (mostly me)
>
>Projection streams are live UI state, not video playback.
Do not buffer, pace, preserve, or “play” frames.
Late frames must be dropped. Corruption must trigger reset.
>
>CarPlay / Android Auto h264 is not media.
It is a real-time projection of UI state.
Correctness is defined by latency, not completeness.
Buffers create corruption. Queues create lies.
>
>Video is a best-effort, disposable representation of UI state.
Audio is a continuous time signal that must never stall.
Video may drop. Audio may buffer. Neither may block the other

> [!IMPORTANT]
> Adapter-SIde Binaries were patched to correct to problems and require use of custom firmware on the adapter itself.
> 1. Carlinkit Stripped away rich iAP2 navigation data rcvd from iPhone, that has been corrected in custom firmware. Full iAP2 route information is now forwarded and rcvd.
> 2. Carlinkit AndroidAuto incorrectly parsed rcvd Vehicle GPS NMEA when app forwarded to Phone. That is now patched so correct and accura NMEA is rcvd by Android Phone.
>
> Custom adapter firmware for complete/correct Carplay Route information and Correct GPS-Forwarding support on Android Auto. While not necessary to have, it provides a better and closer to stock experience if you do load.

```
Video:
- Represents live UI state
- Late == invalid
- Drop aggressively
- Reset on corruption
- Never wait

Audio:
- Represents continuous time
- Late == fill
- Buffer aggressively
- Never stall
- Never block video
```


> [!IMPORTANT]
> My Primary smartphone is an iPhone and therefor Carplay as gotten the most tuning and testing. A Google Pixel 10 is used for testing basic functionality, cannot do real-world 'Day to Day' testing.

## Screen Shots from Android Emulator with USB-PassThrough for CPC200-CCPA Use

![Screenshot of Android Auto via Adapter from Pixel 10](/screenshots/Aauto.png)
![Screenshot of Apple Carplay via Adapter from iPhone Air](/screenshots/Carplay.png)

## Main App UI/Page
![Screenshot of Main App Screen](/screenshots/MainPage.png)

## Adapter Configuaration Options

These options can be set for user preferance, but will require an adapter reboot upon tapping 'Apply & Restart'

![](/screenshots/adapter_config-Audio.png)
![](/screenshots/adapter_confid-Visual.png)
![](/screenshots/adapter_config-Misc.png)

## App specific Setting

Controls what is hidden or shown to allow more space for the Carlink app to configure and render the Projection UI Stream.

![](/screenshots/Settings-DisplayMode.png)

### App Logging to File Export

If enabled allows exporting app logs to a file. Uses the createDocument function so the native android documents app (files) must be installed. THis bypasses the need for the app and third-party file browsers needing permission to access various folders. You can save directly to an attached USB. 

> [!CAUTION]
>OS restrictions will apply.

![](/screenshots/File_Logging.png)

### Log Levels

Due to how verbose and active this app can be. Espically regarding troubleshooting (the more the information the easier to diagnose). Various log levels are available to help narrow down and focus on the needed areas.

![](/screenshots/LogLevels.png)

# Documentation

I, or mostly CLAUDE, have tried to collect and organize as much documentation as I can in regards to every aspect of this app, adapter, gminfo etc. To not only help me better understand, but others as well. If updates come across without code changes. It's likely new documentation or corrections.

> [!IMPORTANT]
> I cannot speak for all information to be accurate and free of errors, but its the most detailed and centralized source of information you will likely find anywhere else. Unless you have direct access to the source code of the Adapter itself, GM Radios etc... If you do, i know a guy and a site who will glady take it and publish it anonymously. 

Most of your questions are likly answered in [Carlink Documents](/documents/reference/), but reach out on the XDA Forum. Issues use github to report it or the forum as well.

# Other Repos that started this gravy train, provided insights/inspiration. And Helped a lot.
# Check them out

- [Carplay by Abuharsky](https://github.com/abuharsky/carplay) - Original Android implementation
- [Node-Carplay by Rhysmorgan134](https://github.com/rhysmorgan134/node-CarPlay) - Protocol reverse engineering
- [LIVI by f-io](https://github.com/f-io/LIVI) - Linux (Raspberry Pi) and macOS implementation
- [PyCarplay by Electric-Monk](https://github.com/electric-monk/pycarplay) - Python implementation
