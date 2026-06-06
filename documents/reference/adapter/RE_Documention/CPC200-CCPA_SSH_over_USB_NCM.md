# Root SSH over USB (CDC-NCM) on the Carlinkit CPC200-CCPA / A15W

**What this gives you:** a full root shell on the adapter's Linux rootfs over the **USB cable only** — no Wi‑Fi association required. The adapter exposes a USB CDC‑NCM ("USB Ethernet") gadget; your host gets a network interface on the same link, and the adapter's `dropbear` SSH daemon (already listening on all interfaces) is reachable over it.

> Verified live on 2026‑05‑30: firmware build `2025.09.26`, kernel `3.14.52+g94d07bb` (armv7l), hostname `sk_mainboard`. Hardware: A15W (IW416 Wi‑Fi). Host: macOS (Apple Silicon). The same approach works on Linux; Windows needs a CDC‑NCM driver (see notes).

---

## TL;DR

```sh
# On the adapter (one-time per boot), then from the host:  ssh root@192.168.50.2
```
Full procedure below. Everything is volatile (RAM/sysfs/tmp) — a **reboot restores normal CarPlay/Android Auto operation**.

---

## 1. Why this isn't automatic

The adapter has **two USB device controllers** (ChipIdea) and **two Android‑gadget instances**:

| sysfs gadget class | controller | role | default state |
|---|---|---|---|
| `/sys/class/android_usb_accessory/android0` | `ci_hdrc.1` | **external** port (the plug that goes into the car / your host) | AOA "accessory" gadget, VID `1314` PID `1521` ("Auto Box") |
| `/sys/class/android_usb/android0` | `ci_hdrc.0` | phone‑side port | `iap2,ncm` composite, VID `08e4` PID `01c0` (only active with a phone) |

A userspace daemon, **`ARMadb-driver`** (started by `/script/start_main_service.sh`, which spawns `/script/phone_link_deamon.sh CarPlay start`), continuously **cycles the external gadget** looking for a CarPlay head unit or an Android Auto host. A generic computer is neither, so the port stays in accessory/AOA mode and your host never sees an Ethernet interface.

The building blocks for NCM are already present, though:
- the `f_ncm` function binds cleanly on either gadget;
- `/script/start_ncm.sh` already assigns **`ncm0 = 192.168.50.2/24`** at boot;
- `dropbear` listens on `0.0.0.0:22` **and** `:::22`, so it answers on `ncm0` too.

So the job is simply: **stop the cycler, switch the external gadget to NCM, hand the host an IP.**

---

## 2. Prerequisites

- A **control channel to the adapter** to issue the setup commands the first time. Easiest is the adapter's own Wi‑Fi AP:
  - SSH: `ssh root@192.168.43.1` (password is **blank** — just press Enter). On macOS, non‑interactive: `sshpass -p '' ssh -o StrictHostKeyChecking=no root@192.168.43.1`.
  - Or a serial/UART console if you have one.
- The adapter connected to your host via the **USB data cable** (the normal connector that plugs into the car).
- `sshpass` is optional but handy for the blank password (`brew install hudochenkov/sshpass/sshpass`).

You only need the Wi‑Fi control channel **once** to arm NCM. After that the USB link stands on its own.

---

## 3. Arm NCM on the adapter

Run these on the adapter (over the Wi‑Fi SSH session). They are safe and volatile.

```sh
# 3.1  Stop the gadget cycler (otherwise it reverts NCM within ~2 s).
#      ARMadb-driver is NOT auto-respawned, so killing it makes NCM stick.
killall ARMadb-driver ARMiPhoneIAP2 fakeiOSDevice 2>/dev/null
pkill -f phone_link_deamon 2>/dev/null

# 3.2  Release the phone-side composite that owns the ncm0 netdev.
echo 0 > /sys/class/android_usb/android0/enable

# 3.3  Switch the EXTERNAL (host-facing) gadget to a pure CDC-NCM device.
A=/sys/class/android_usb_accessory/android0
echo 0   > $A/enable
echo 239 > $A/bDeviceClass      # 0xEF Miscellaneous (IAD)
echo 2   > $A/bDeviceSubClass
echo 1   > $A/bDeviceProtocol
echo ncm > $A/functions
echo 1   > $A/enable

# 3.4  Make sure the netdev has its address.
busybox ifconfig ncm0 192.168.50.2 netmask 255.255.255.0 mtu 1500 up
```

Verify (on the adapter):

```sh
cat /sys/class/android_usb_accessory/android0/functions   # -> ncm
cat /sys/class/android_usb_accessory/android0/state        # -> CONFIGURED
cat /sys/class/udc/ci_hdrc.1/state                         # -> configured
```
`dmesg` should show: `android_enable_function: ncm`, `ncm_function_bind_config MAC: …`, `ncm0: HOST MAC <xx>`. That `HOST MAC` is the MAC your host will use for its new interface.

### 3.5  (Recommended) Serve DHCP on ncm0 so the host auto-gets an IP

By default `/etc/udhcpd.conf` serves **only `wlan0`** (the `interface ncm0` line is commented out). Start a **second, independent** udhcpd bound to `ncm0` so you don't disturb the Wi‑Fi one:

```sh
cat > /tmp/udhcpd_ncm.conf <<'CFG'
start       192.168.50.100
end         192.168.50.150
interface   ncm0
opt router  192.168.50.2
opt subnet  255.255.255.0
opt dns     192.168.50.2
opt lease   86400
lease_file  /tmp/udhcpd_ncm.leases
pidfile     /tmp/udhcpd_ncm.pid
max_leases  20
CFG
touch /tmp/udhcpd_ncm.leases
setsid busybox udhcpd -f /tmp/udhcpd_ncm.conf >/tmp/udhcpd_ncm.log 2>&1 </dev/null &
```
(Without this you can still connect via IPv6 link‑local — see §4b.)

> **Tip:** bouncing the gadget once (`echo 0 > $A/enable; sleep 2; echo 1 > $A/enable`) makes the host tear down and re‑create the interface, which triggers a fresh DHCP request and picks up the lease immediately.

---

## 4. Connect from the host

### 4a. macOS / Linux — DHCP (simplest)

When the NCM gadget enumerates, the OS creates a new Ethernet interface automatically (no driver install on macOS or modern Linux — both have a built‑in CDC‑NCM driver).

```sh
# Find the new interface: its MAC equals the adapter's "HOST MAC" from dmesg.
#   macOS: ifconfig -l ; ifconfig enX        Linux: ip -br link
# With §3.5 DHCP running it gets 192.168.50.100-150 automatically:
ssh root@192.168.50.2          # password: blank
```
Confirm it really went over USB, not Wi‑Fi:
```sh
route -n get 192.168.50.2 | grep interface   # macOS -> the USB enX
# inside the session:  echo $SSH_CONNECTION   -> 192.168.50.100 … 192.168.50.2 22
```

### 4b. macOS / Linux — IPv6 link‑local (no DHCP, no admin rights)

`dropbear` listens on IPv6 too, and link‑local needs no address config:

```sh
# adapter ncm0 IPv6-LL is derived from its MAC, e.g. fe80::c08e:30ff:fe52:e9a3
# host interface scope is the new enX (e.g. en7 on macOS, enpXsY on Linux)
ssh -6 root@fe80::c08e:30ff:fe52:e9a3%en7
```
Get the adapter's value with `busybox ifconfig ncm0 | grep inet6` over your control channel.

### 4c. Static IPv4 (if you don't run DHCP and don't want IPv6)

```sh
sudo ifconfig en7 192.168.50.1 netmask 255.255.255.0 up   # macOS
sudo ip addr add 192.168.50.1/24 dev enpXsY && sudo ip link set enpXsY up  # Linux
ssh root@192.168.50.2
```

### 4d. Windows

Windows doesn't ship a generic CDC‑NCM class driver. Either install a CDC‑NCM/usb_ncm INF, or have the adapter present **RNDIS** instead (`echo rndis > $A/functions`) which Windows binds natively. Then the adapter appears as a "USB Ethernet/RNDIS Gadget" NIC; use DHCP (§3.5) or static `192.168.50.1`. Note: macOS does **not** support RNDIS — keep `ncm` for Apple hosts.

---

## 5. Restore normal operation

Everything above is in RAM/sysfs/tmp. To return the adapter to a normal CarPlay/Android‑Auto dongle:

```sh
reboot          # on the adapter — boot re-runs start_main_service / start_iap2_ncm / start_ncm
```
(There is no clean "undo" short of reboot, because killing `ARMadb-driver` tears down the projection state machine.)

---

## 6. Making it survive reboots (optional, advanced)

`start_main_service.sh` runs `test -e /script/custom_init.sh && /script/custom_init.sh` early in boot. A `custom_init.sh` could arm a management NCM link automatically. **Caveats:**
- The external port `ci_hdrc.1` is single‑gadget: a dedicated management‑NCM **conflicts** with the CarPlay/AOA gadget the box wants on that same port. You can't have both simultaneously unless NCM is **composited into** the projection gadget (e.g. an `iap2,ncm` or `rndis,ncm` multifunction) or you only arm NCM when not actively projecting.
- `/script` may live on a read‑only rootfs; persisting a file there can require remounting rw or repacking firmware (the A15W image is AES‑encrypted — see your firmware tooling).

**Important corollary:** during a **normal wired‑CarPlay session against a real head unit**, the box already brings up `iap2,ncm` and `ncm0` carries the CarPlay IPv6 session. Because `dropbear` is listening on `ncm0`, SSH over USB is reachable **from the head‑unit side with no modification and no Wi‑Fi at all** — the head unit is simply the NCM peer instead of your laptop.

---

## 7. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| NCM flips back to "Auto Box" after ~2 s | `ARMadb-driver` still running — `killall ARMadb-driver` (and `pkill -f phone_link_deamon`). It is **not** auto‑respawned. |
| `echo 1 > .../enable` reads back `0` | The other gadget still holds `ncm0`. Run `echo 0 > /sys/class/android_usb/android0/enable` first (§3.2). |
| Host interface appears but stays on `169.254.x` / no IP | No DHCP on `ncm0`. Start udhcpd (§3.5) and bounce the gadget, or use IPv6‑LL (§4b) / static IPv4 (§4c). |
| No new interface on the host at all | Check `cat .../state` is `CONFIGURED` and `cat /sys/class/udc/ci_hdrc.1/state` is `configured`; replug the USB cable; on Windows use RNDIS (§4d). |
| `busybox udhcpd: not found` | It's a busybox applet — call it as `busybox udhcpd …`, not `udhcpd`. |
| Can reach `192.168.43.1` but want to prove it's USB | `ssh root@192.168.50.2` (the `.50.x` subnet only exists on the USB‑NCM link) and check `echo $SSH_CONNECTION`. |

---

## 8. One‑shot helper script (run on the adapter)

Save as `/tmp/ncm_ssh.sh`, then `sh /tmp/ncm_ssh.sh`:

```sh
#!/bin/sh
set +e
A=/sys/class/android_usb_accessory/android0
killall ARMadb-driver ARMiPhoneIAP2 fakeiOSDevice 2>/dev/null
pkill -f phone_link_deamon 2>/dev/null
sleep 1
echo 0 > /sys/class/android_usb/android0/enable
echo 0 > $A/enable
echo 239 > $A/bDeviceClass; echo 2 > $A/bDeviceSubClass; echo 1 > $A/bDeviceProtocol
echo ncm > $A/functions; echo 1 > $A/enable
busybox ifconfig ncm0 192.168.50.2 netmask 255.255.255.0 mtu 1500 up
# DHCP for the host (optional)
cat > /tmp/udhcpd_ncm.conf <<'CFG'
start 192.168.50.100
end 192.168.50.150
interface ncm0
opt router 192.168.50.2
opt subnet 255.255.255.0
opt dns 192.168.50.2
opt lease 86400
lease_file /tmp/udhcpd_ncm.leases
pidfile /tmp/udhcpd_ncm.pid
max_leases 20
CFG
touch /tmp/udhcpd_ncm.leases
[ -f /tmp/udhcpd_ncm.pid ] && kill "$(cat /tmp/udhcpd_ncm.pid)" 2>/dev/null
setsid busybox udhcpd -f /tmp/udhcpd_ncm.conf >/tmp/udhcpd_ncm.log 2>&1 </dev/null &
# nudge the host to re-DHCP
echo 0 > $A/enable; sleep 2; echo ncm > $A/functions; echo 1 > $A/enable
echo "NCM armed. From host: ssh root@192.168.50.2   (state=$(cat $A/state))"
```

Then on the host: **`ssh root@192.168.50.2`** (blank password). Done — root shell over USB, no Wi‑Fi.
