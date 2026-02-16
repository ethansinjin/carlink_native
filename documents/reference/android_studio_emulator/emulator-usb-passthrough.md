# AAOS Emulator USB Passthrough (macOS)

Passes the Carlinkit CPC200-CCPA USB adapter into an Android Automotive OS emulator on macOS. Enables development and testing of carlink_native without a physical head unit.

Verified: 2026-02-15, macOS Darwin 25.3.0 (Apple M4 Max), Android Emulator 36.3.10.0, AAOS API 34.

## Prerequisites

- Android Studio with an AAOS AVD created (e.g. `AAOS`)
- CPC200-CCPA adapter connected via USB (VID `0x1314`, PID `0x1520`)
- No special macOS configuration required (no SIP changes, no sudo, no re-signing)

## USB Host Permission Setup

The emulator guest requires an `android.hardware.usb.host` feature declaration for Android's `UsbManager` to expose USB devices to apps. Without it, the adapter is visible at the kernel level (`dmesg`) but no app can access it.

This persists across normal emulator restarts via overlayfs, but is **lost on data wipe** (AVD "Wipe Data" in Android Studio or `-wipe-data` flag).

```bash
# 1. Launch emulator with USB passthrough and writable system
emulator -avd AAOS \
    -usb-passthrough vendorid=0x1314,productid=0x1520 \
    -writable-system -no-snapshot

# 2. Wait for boot, then enable overlayfs (requires 2 reboots)
adb root
adb remount        # Enables overlayfs, says "reboot required"
adb reboot

# 3. After reboot, remount again and write the permission file
adb root
adb remount        # Now writable
adb shell 'echo "<permissions><feature name=\"android.hardware.usb.host\"/></permissions>" > /system/etc/permissions/android.hardware.usb.host.xml'
adb reboot
```

After the final reboot, verify:
```bash
adb shell pm list features | grep usb
# Expected: feature:android.hardware.usb.host

adb shell lsusb
# Expected: Bus 001 Device NNN: ID 1314:1520
```

## Daily Use

After setup, the only command needed is:

```bash
emulator -avd AAOS \
    -usb-passthrough vendorid=0x1314,productid=0x1520 \
    -writable-system -no-snapshot
```

The permission file and USB host feature persist automatically across emulator stop/start cycles. No manual configuration at boot.

**After a data wipe**: re-run the permission setup above (step 2 onwards).

## What Happens Without the Permission File

If the USB host feature is missing, the adapter still enumerates at the kernel level but Android's `UsbManager` never exposes it to apps. Symptoms:
- `dmesg` shows endless ~10s connect/disconnect cycles (no app claims the device, adapter times out without heartbeat)
- `dumpsys usb` has no `host_manager` section
- Apps with USB device filters appear to launch but get stuck (AAOS tries to show a permission prompt that can never complete)

## Adapter Cycling Behavior

The CPC200-CCPA resets itself if no app sends a heartbeat within ~8 seconds. On emulator boot, the typical sequence is:

| Time     | Event |
|----------|-------|
| ~2-3s    | Adapter first enumerates in the guest |
| ~8-10s   | AAOS USB handler probes with an unsupported control transfer (0xC0/0x33) -> adapter disconnects |
| ~4s later| Adapter re-enumerates |
| ~3s later| App claims device, sends heartbeat, adapter stabilizes |

This single disconnect/reconnect cycle is normal and does not cause issues. Once an app (Abuharsky Carplay, carlink_native, etc.) claims the device and begins the heartbeat, the adapter remains stable.

AAOS auto-grants USB access to apps with a matching USB device filter — no user permission dialog is shown.

## Hot-Plug

Physically disconnecting and reconnecting the CPC adapter while the emulator is running works. The adapter re-enumerates in the guest and the app re-detects it. No emulator restart needed.

## Why This Works

The Android emulator's QEMU binary on macOS already includes:

- `usb-host` device backend compiled from `host-libusb.c`
- `IOUSBHost.framework` linked
- `com.apple.security.device-access` entitlement
- `-usb-passthrough` flag in both the emulator wrapper and QEMU binary

The CPC200-CCPA is a vendor-specific USB device (class 0xFF, subclass 0xF0) with no macOS kernel driver. macOS does not claim it, so libusb can hand it to QEMU without conflict.

## Debugging

```bash
# Check if adapter is visible in guest
adb shell lsusb

# Kernel USB enumeration/disconnect events
adb shell dmesg | grep 'usb 1-1'

# Android USB manager state (device details, endpoints, connect history)
adb shell dumpsys usb

# Verify USB host feature is registered
adb shell pm list features | grep usb
```

## Known Adapter IDs

| VID      | PID      | Description |
|----------|----------|-------------|
| `0x1314` | `0x1520` | CPC200-CCPA (primary) |
| `0x1314` | `0x1521` | CPC200-CCPA (alternate) |
| `0x08E4` | `0x01C0` | Alternate vendor ID |

For alternate PIDs, adjust the `-usb-passthrough` arguments accordingly.
