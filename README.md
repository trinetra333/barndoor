# Barndoor

**Control your Wi-Fi with ease.**

Barndoor is an Android app for managing a personal mobile hotspot: starting
it, applying a disclosed website filter, and getting alerted when a new
device joins.

## Features

- **Hotspot control** — start a `LocalOnlyHotspot` or deep-link into the
  system hotspot settings screen. Android does not allow apps to enable
  tethering silently (API 26+), so this app always goes through
  Android-sanctioned, user-visible paths.
- **Website filtering (guest-visible)** — an on-device VPN (`VpnService`)
  blocks DNS lookups for domains you list. It only filters *this device's*
  own traffic; it does not, and technically cannot via public Android APIs,
  intercept or redirect other people's phones just because they're using
  your hotspot.
- **Security alerts** — best-effort notification when a new MAC address
  shows up in this device's ARP table, so you know who's around.

## What this app deliberately does NOT do

Early drafts of this project included a "link spoofing" feature — silently
redirecting a connected guest's traffic to different destinations than the
ones they requested. That's a man-in-the-middle / phishing technique, and
it's excluded from this codebase on purpose, even though this is a hotspot
app.

If you need traffic control for guests on your own network, do it on
infrastructure you administer (e.g. a router running dnsmasq/pfSense/OpenWRT)
and disclose the filtering with something like a captive portal notice.
That's how legitimate guest-network filtering products work.

## Project structure

```
barndoor/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/barndoor/app/
│       │   ├── MainActivity.kt
│       │   ├── HotspotManager.kt
│       │   ├── BarndoorVpnService.kt
│       │   ├── DeviceMonitorService.kt
│       │   └── AlertNotifier.kt
│       └── res/
│           ├── layout/activity_main.xml
│           └── values/{strings.xml,themes.xml}
├── build.gradle.kts
├── settings.gradle.kts
└── gradle/wrapper/gradle-wrapper.properties
```

## Building

1. Open the `barndoor/` folder in Android Studio (Koala+ recommended), or
2. From the command line, generate the wrapper jar once (Android Studio does
   this automatically on first open):
   ```
   gradle wrapper --gradle-version 8.7
   ./gradlew assembleDebug
   ```

Minimum SDK 26, target/compile SDK 34.

## Permissions used

| Permission | Why |
|---|---|
| `ACCESS_WIFI_STATE` / `CHANGE_WIFI_STATE` | Read hotspot/Wi-Fi state |
| `ACCESS_FINE_LOCATION` | Required by Android for `LocalOnlyHotspot` |
| `POST_NOTIFICATIONS` | Show security alerts |
| `FOREGROUND_SERVICE*` | Run the device-monitor service reliably |
| `BIND_VPN_SERVICE` (service-level) | Implement the on-device filter |

## Publishing to Git

```bash
cd barndoor
git init
git add .
git commit -m "Initial Barndoor scaffold"
git remote add origin <your-repo-url>
git push -u origin main
```

## Status

This is a working scaffold: the UI, service wiring, permission flow, and
architecture are in place. The DNS packet parsing inside
`BarndoorVpnService.runFilterLoop()` is stubbed with comments marking where
real packet parsing goes — that's the one piece that needs a proper
DNS/IP-packet library (e.g. parsing raw UDP port 53 packets) before this is
production-ready.
