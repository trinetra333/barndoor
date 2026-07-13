# Barndoor

An Android app with two Quick Settings tiles and a full toolkit around them:

- **DNS tile** — cycles through your saved DNS list (then off) with each tap. Every
  app can optionally get its **own** resolver instead of the device-wide default —
  genuinely different DNS per app, at the same time, not just an include/exclude list.
- **IP tile** — toggles automatic exit-server rotation through Mullvad (WireGuard),
  every 10 seconds to 30 minutes, random-any or random-in-country.
- **DNS-over-TLS** — built-in resolvers use encrypted DNS (DoT) where the provider
  supports it, not plaintext port 53.
- **Speed test** — times every configured resolver in parallel, sorted fastest-first.
- **Query log** — optional, in-memory-only (opt-in, off by default) log of recent DNS
  queries: domain, which app asked, which resolver answered, and how long it took.
- **Whois + map** — look up a domain or IP, see the WHOIS record plus its
  geolocation on an embedded OpenStreetMap view.

## Getting the APK (no local Android Studio needed)

1. Push this project to a GitHub repository.
2. Go to the **Actions** tab → the "Build Barndoor APK" workflow runs on every push
   (or trigger it manually with "Run workflow").
3. When it finishes, open the run → under **Artifacts**, download `barndoor-debug-apk`.
4. Unzip it — inside is `app-debug.apk`. Install it on your phone (you'll need to
   allow "install unknown apps" for whichever app you use to open the file).

A `barndoor-release-apk-unsigned` artifact is also produced but is **unsigned** and
won't install as-is — it's there if you want to sign it yourself later (see
[Android's signing docs](https://developer.android.com/studio/publish/app-signing)).

### Optional: zero-touch Mullvad setup

By default, the Rotation tab needs you to paste in a Mullvad account number and tap
"Register device" **once** — after that it's saved and the tile just works, no
recurring login. If you'd rather not type it in at all, add a repository secret named
`MULLVAD_ACCOUNT` (Settings → Secrets and variables → Actions → New repository
secret) with your account number. The workflow bakes it into the build, and the app
registers itself automatically the first time you open the Rotation tab.

## First-time setup

- **DNS tile:** swipe down twice → pencil/edit icon → drag "Barndoor DNS" into your
  active tiles. Tapping it cycles: DNS #1 → DNS #2 → … → Off → DNS #1…
- **Rotation tile:** same process for "Barndoor IP". Needs the one-time Mullvad setup
  above (in-app or via the build secret) before it does anything.
- **Per-app DNS:** open the app → **Apps** tab → tap any app → pick a resolver (or
  "Default" to go back to the device-wide one).

## How it works

- **DNS changer** — a local `VpnService` that routes the whole device to its own fake
  DNS address, then for each query looks up which app sent it (by UID) and forwards
  it to that app's assigned resolver — plain UDP/TCP, or DNS-over-TLS for providers
  that support it (same 2-byte length-prefixed framing as DNS-over-TCP, just sent
  over a TLS connection on port 853 instead of plaintext port 53). Anything that
  isn't a DNS query on that address gets an immediate TCP RST or is dropped, so it
  fails fast instead of hanging.
- **IP rotation** — uses the official `com.wireguard.android:tunnel` library. One
  WireGuard identity (key pair + Mullvad-assigned address) is registered once; a
  foreground service then reconnects that same identity to a different Mullvad relay
  on a timer, which is what actually changes your visible exit IP/country.
- **Speed test / Whois / geolocation** — run from the app itself (not the VPN
  service), so they use the normal network directly; no special permissions needed.
  Geolocation uses the free, keyless [ipwho.is](https://ipwho.is) API; the map is
  Leaflet + OpenStreetMap tiles loaded in a WebView, no API key required.

## Known limitations

- Per-app DNS relies on `ConnectivityManager.getConnectionOwnerUid`, which needs
  Android 10+ (this is why minSdk is 29, not lower). If the lookup ever fails on a
  given device, that query just falls back to the device-wide default rather than
  breaking — but the per-app targeting itself needs Android 10+.
- Android only allows **one active VPN interface at a time**. The DNS tile and the
  IP-rotation tile aren't meant to run simultaneously — turning one on replaces
  whichever was active.
- The TCP relay handles the real-world DNS-over-TCP case (one query, one response,
  connection closes) but isn't a full RFC 793 stack — no retransmission, reordering,
  or pipelining.
- Query logging is intentionally in-memory only (nothing written to disk, capped at
  300 entries, cleared on app kill) and off by default — it's a diagnostic tool, not
  a background habit.
- WHOIS here follows exactly one referral from IANA to the authoritative registry/
  registrar server, which covers the large majority of lookups but isn't a fully
  recursive WHOIS client.
- The Mullvad account number and WireGuard private key are stored in this app's
  normal (app-private, not additionally encrypted) SharedPreferences. Fine for
  personal use; swap in `androidx.security.crypto`'s `EncryptedSharedPreferences` if
  you ever distribute this more broadly.
- Listing installed apps for the per-app screen uses `QUERY_ALL_PACKAGES`.
  Unrestricted for a sideloaded build; a Play Store listing would need to declare the
  "Core app functionality" use-case for that permission in Play Console.
- The built-in DNS list covers well-known, verifiable providers (with IPs and DoT
  hostnames double-checked, not guessed) rather than literally every public resolver
  that exists — use "Add custom DNS" for anything not listed.

## Project structure

```
app/src/main/java/com/barndoor/app/
  MainActivity.kt, BarndoorApp.kt
  dns/          DNS list, DoT client, VpnService proxy + TCP relay, quick tile,
                per-app assignment storage, speed test, query log store
  apps/         installed-app listing for the per-app assignment screen
  rotation/     Mullvad API client, WireGuard manager, rotation service + tile
  logs/         query log UI
  whois/        WHOIS client, geolocation client, whois + map UI
  util/         IPv4/UDP/TCP checksum helpers used by the DNS proxy
```
