# Barndoor

A DNS-changer + rotating-IP Android app with two Quick Settings tiles:

- **DNS tile** — tap to cycle through your saved DNS servers (and off), no root needed.
- **IP tile** — toggles automatic exit-server rotation through Mullvad (WireGuard), every N minutes, either fully random or random-within-a-country.

Includes a third tab to scope the DNS proxy to specific apps instead of the whole device.

## Getting the APK (no local Android Studio needed)

This repo builds itself on GitHub's servers:

1. Push this project to a GitHub repository.
2. Go to the **Actions** tab → the "Build Barndoor APK" workflow runs automatically on every push (or trigger it manually with "Run workflow").
3. When it finishes, open the run → under **Artifacts**, download `barndoor-debug-apk`.
4. Unzip it — inside is `app-debug.apk`. Transfer it to your phone and install it (you'll need to allow "install unknown apps" for whichever app you use to open the file).

The debug APK is signed with Android's default debug key, so it installs and runs like any normal app. A `barndoor-release-apk-unsigned` artifact is also produced, but it is **unsigned** and won't install as-is — it's there if you want to sign it yourself for a "real" release later (see [Android's signing docs](https://developer.android.com/studio/publish/app-signing)).

## First-time setup

**DNS tile:** Settings → Notifications → Quick Settings (or swipe down twice → pencil/edit icon) → drag the "Barndoor DNS" tile into your active tiles. Tapping it cycles: DNS #1 → DNS #2 → … → Off → DNS #1…

**Rotation tile:** same process for "Barndoor IP". Before it will do anything, open the app → **Rotation** tab → enter your Mullvad account number → **Register device**. This calls Mullvad's API once to register a WireGuard key against your account and get you a permanent in-tunnel address; after that, rotation just swaps which relay (exit server) that same identity connects to.

**Per-app DNS:** open the app → **Apps** tab. Leave everything unchecked for device-wide DNS, or check specific apps to limit the proxy to just those.

## How it works

- **DNS changer** — a local `VpnService` that only intercepts UDP/port 53 packets addressed to your chosen resolver and relays them over a protected socket. Nothing else is routed through it, so it's lightweight and only affects DNS.
- **IP rotation** — uses the official `com.wireguard.android:tunnel` library. One WireGuard identity (key pair + Mullvad-assigned address) is registered once; a foreground service then reconnects that same identity to a different Mullvad relay on a timer, which is what actually changes your visible exit IP/country.

## Known limitations

- DNS proxy is UDP-only. Apps that hardcode DNS-over-HTTPS/TLS to their own provider, or that fall back to TCP for oversized DNS responses, will bypass it.
- Android only allows **one active VPN interface at a time**. Turning on the DNS tile while rotation is running (or vice versa) will replace whichever was active — they're not meant to run simultaneously.
- The Mullvad account number and WireGuard private key are stored in this app's normal (app-private, not additionally encrypted) SharedPreferences. Fine for personal use; if you ever ship this more broadly, swap in `androidx.security.crypto`'s `EncryptedSharedPreferences`.
- Listing installed apps for the per-app screen uses the `QUERY_ALL_PACKAGES` permission. That's unrestricted for a sideloaded build; a Play Store listing would need to declare the "Core app functionality" use-case for that permission in Play Console.
- Mullvad API endpoints used here (`api.mullvad.net`) are the same ones their own apps/CLI use, reverse-engineered from public write-ups rather than official third-party docs — if Mullvad changes them, registration will start failing and the endpoint constants in `MullvadApi.kt` are the place to update.

## Project structure

```
app/src/main/java/com/barndoor/app/
  MainActivity.kt, BarndoorApp.kt
  dns/          DNS list, VpnService-based proxy, DNS quick tile
  apps/         installed-app listing for the per-app screen
  rotation/     Mullvad API client, WireGuard manager, rotation service + tile
  util/         IPv4/UDP checksum helpers used by the DNS proxy
```
