# USB bridge architecture

## Why the app is split

On the GM gminfo3.7 radio (AAOS 12), the system USB handler is tied to the
package name `android.car.usb.handler`. Installing a helper with that package
allows GM's fixed USB attach path to grant USB access to the helper UID without
showing the normal USB permission prompt.

The Play-distributed Carlink UI must remain the trusted Play application so it
can use the vehicle's distraction-optimized activity behavior. A single APK
cannot reliably satisfy both requirements: changing the package name to the
GM USB handler package makes USB ownership work, but causes the app to lose the
Play/trusted driving behavior.

The repository therefore contains two applications:

| Application | Package | Responsibility |
| --- | --- | --- |
| Carlink Play app | `ey.carlink` | UI, CarPlay/Android Auto protocol, rendering, audio, vehicle integration |
| USB helper | `android.car.usb.handler` | USB permission ownership, CPC200 bulk I/O, message framing |

The applications share the `:usbbridge` library. It contains the AIDL
interfaces used by the Play app to bind to the helper and exchange complete
CPC200 messages in chunks. The helper owns all `UsbManager` calls; the Play app
does not request USB permission or open the adapter directly.

## Required helper component

The helper manifest exposes only the exported bridge service:

- `com.carlink.usbhelper.UsbBridgeService`

The helper intentionally does **not** declare
`android.car.usb.handler.UsbHostManagementActivity`. GM's fixed handler runs
for every host-mode USB attach, grants permission to the package named
`android.car.usb.handler`, then attempts to launch its configured activity. On
this firmware, a missing activity is caught by the system after the grant is
issued. Omitting the activity therefore preserves the silent permission grant
without putting a sideloaded helper activity on screen. This matters when a
phone or another USB device is connected while driving: declaring the activity
can trigger GM's distraction overlay even when the device is not the CPC200.

The bridge service is an exported bound service. It is deliberately not a
foreground service: the Play app binds to it while the Carlink session is
active, avoiding foreground-service restrictions on GM AAOS.

The Play app declares package visibility for `android.car.usb.handler` so
`PackageManager` can resolve the exported bridge service on Android 11+/AAOS.
Binding callbacks use a dedicated executor; blocking the main thread while
waiting for `onServiceConnected()` can otherwise create a false binding timeout.

## Installation and testing

Build both packages from the repository root:

```text
./gradlew :app:assembleSideloadDebug :usbhelper:assembleDebug
```

Install both APKs for the same AAOS user/profile:

1. Install the USB helper APK.
2. Install or update the Play Carlink app.
3. Unplug/replug the CPC200 adapter, or power-cycle the radio, so GM can run
   its fixed handler and apply the helper's USB permission.
4. Launch Carlink while parked.

The generated APKs are:

```text
app/build/outputs/apk/sideload/debug/app-sideload-debug.apk
usbhelper/build/outputs/apk/debug/usbhelper-debug.apk
```

If the Play app reports `helper service is not installed for this AAOS user`,
the helper is not visible in the same Android user/profile. If it reports
`CPC200 adapter not found`, the helper includes the USB devices visible to
`UsbManager` in the status detail; use that VID/PID information to update the
filter and allowlist if a new adapter variant is encountered.

## Scope and limitations

This architecture depends on the GM fixed-handler behavior documented for the
target gminfo3.7 firmware. It is not a general Android USB-permission bypass,
and it has not been verified on every GM radio or AAOS version. The helper is
an intentionally narrow bridge and should not be expanded into a second UI or
protocol implementation.

## Field validation

On the target truck, the split deployment was validated through Play internal
testing: the Play-installed `ey.carlink` app connected through the sideloaded
`android.car.usb.handler` helper, and the distraction overlay was not triggered
when the vehicle was shifted out of Park. This confirms that USB ownership and
driving-policy eligibility are evaluated independently by the head unit.
