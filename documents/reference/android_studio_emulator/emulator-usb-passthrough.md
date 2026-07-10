 AAOS Emulator USB Passthrough (macOS)

Passes the Carlinkit CPC200-CCPA USB adapter into an Android Automotive OS emulator on macOS for development and testing without a physical head unit.

Verified: 2026-02-19, macOS Darwin 25.4.0 (Apple M4 Max), Android Emulator 36.3.10.0, AAOS API 36.

Adapter USB IDs

VID 0x1314, PID 0x1520 — primary
VID 0x1314, PID 0x1521 — alternate

The adapter can re-enumerate mid-session and switch between PIDs. Both must be passed to the emulator to prevent connection drops.

Prerequisites

- Android Studio with an AAOS AVD created (e.g. ultrawideaaos)
- CPC200-CCPA adapter connected to Mac via USB
- No special macOS configuration required (no SIP changes, no sudo, no re-signing)

Launch Command

emulator -usb-passthrough vendorid=0x1314,productid=0x1520 -usb-passthrough vendorid=0x1314,productid=0x1521 -selinux permissive -writable-system -no-snapshot -avd EMULATORNAME

Both -usb-passthrough flags are required. The adapter can switch PIDs at any time and only the matching filter will grab the device.

-selinux permissive is required. The generic AAOS emulator image runs SELinux enforcing and its policy does not include USB host device access rules for passthrough devices. Without this flag, system_server cannot open /dev/bus/usb/* and the device enters an attach/detach loop with "usb_device_open failed" in logcat. Real AAOS hardware (GM, RPi5) includes OEM SELinux rules for USB host and does not need this.

USB Host Permission Setup

The emulator guest needs an android.hardware.usb.host feature declaration. Without it, UsbManager never exposes USB devices to apps. This persists across normal emulator restarts via overlayfs but is lost on data wipe.

adb root
adb remount
adb reboot

adb root
adb remount
adb shell 'echo "<permissions><feature name=\"android.hardware.usb.host\"/></permissions>" > /system/etc/permissions/android.hardware.usb.host.xml'
adb reboot

Verify after reboot:

adb shell pm list features | grep usb
adb shell lsusb

After a data wipe, re-run the permission setup above.

Adapter Cycling Behavior

The CPC200-CCPA resets itself if no app sends a heartbeat within ~8 seconds. On emulator boot the adapter typically enumerates, AAOS USB handler probes with an unsupported control transfer, adapter disconnects, then re-enumerates ~4 seconds later. Once the app claims the device and starts the heartbeat, the adapter stabilizes. This single disconnect/reconnect cycle is normal.

Hot-Plug

Physically disconnecting and reconnecting the adapter while the emulator is running works. The adapter re-enumerates in the guest and the app re-detects it. No emulator restart needed.

Debugging

adb shell lsusb
adb shell dmesg | grep 'usb 1-1'
adb shell dumpsys usb
adb shell pm list features | grep usb
adb shell getenforce

Why This Works

The Android emulator QEMU binary on macOS includes usb-host backend (host-libusb.c), IOUSBHost.framework, com.apple.security.device-access entitlement, and the -usb-passthrough flag. The CPC200-CCPA is a vendor-specific USB device (class 0xFF, subclass 0xF0) with no macOS kernel driver, so libusb hands it to QEMU without conflict.
