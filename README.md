# Barndoor

A focused Android DNS-changer app with a Quick Settings tile and a full toolkit
around it:

- **DNS tile** — cycles through your saved DNS list (then off) with each tap. The
  tile's label shows the active resolver's name, not a generic "Barndoor DNS".
  Whichever server is active is automatically sorted to the top of the list in the
  app, updating live even if the tile changed it while the app was already open.
- **Per-app DNS** — every app can optionally get its **own** resolver instead of the
  device-wide default — genuinely different DNS per app, at the same time, not just
  an include/exclude list.
- **Two mechanisms, chosen automatically** — Android's native Private DNS setting
  (zero VPN icon) when available, falling back to a local DNS-only VPN proxy
  otherwise. See "Why does it need VPN?" below.
- **DNS-over-TLS** — built-in resolvers use encrypted DNS (DoT) where the provider
  supports it, not plaintext port 53.
- **14 built-in providers** (Cloudflare, Google, Quad9, AdGuard, Mullvad, OpenDNS,
  CleanBrowsing, Comodo, NextDNS, Control D, DNS.WATCH, Yandex, CIRA Canadian Shield,
  CenturyLink) plus custom servers with your own IP and/or DoT hostname.
- **Speed test** — times every configured resolver in parallel, sorted fastest-first.
- **Query log** — optional, in-memory-only (opt-in, off by default) log of recent DNS
  queries: domain, which app asked, which resolver answered, and how long it took.
- **Whois + map** — look up a domain or IP, see the WHOIS record plus its
  geolocation on an embedded OpenStreetMap view.

There is no VPN-for-IP-changing feature here on purpose — this app only ever
changes *DNS*, never your public IP or general traffic routing.

## Getting the APK (no local Android Studio needed)

1. Push this project to a GitHub repository.
2. The "Build Barndoor APK" workflow runs automatically on every push (or trigger it
   manually from the **Actions** tab with "Run workflow").
3. Once it finishes, go to your repo's **Releases** page (right sidebar on the repo
   home page, or `github.com/<you>/<repo>/releases`) → open **"latest-build"** →
   download `app-debug.apk` directly. Install it on your phone (you'll need to allow
   "install unknown apps" for whichever app you use to open the file).

The release is updated in place on every successful build, so "latest-build" always
has the newest APK — no expiry, no need to be logged into GitHub to grab it later,
unlike the older path below. It also includes `app-release-unsigned.apk`, which is
**unsigned** and won't install as-is — it's there if you want to sign it yourself
later (see [Android's signing docs](https://developer.android.com/studio/publish/app-signing)).

<details>
<summary>Alternative: download from the workflow run's Artifacts instead</summary>

Open the specific **Actions** run → under **Artifacts**, download `barndoor-debug-apk`
(or `barndoor-release-apk-unsigned`) and unzip it. This ties the download to one
specific run and expires after 90 days by default — the Releases page above is
generally more convenient, but this is here if you'd rather grab a specific historical
build.

</details>

### Optional: zero-VPN system DNS mode

By default the DNS tile/proxy uses a local VPN (see "Why does it need VPN?" below —
this is an Android platform limitation, not a design choice). If you'd rather have
**zero VPN icon** for DNS-over-TLS resolvers, grant one permission once over ADB with
your phone connected to a PC:

```
adb shell pm grant com.barndoor.app.debug android.permission.WRITE_SECURE_SETTINGS
```

(use `com.barndoor.app` instead of `com.barndoor.app.debug` if you signed and
installed the release build). Once granted, Barndoor automatically uses Android's
native Private DNS setting instead of the VPN whenever the selected server supports
DNS-over-TLS and no per-app DNS is configured — the DNS tab shows which mode is
currently active. Revoke it any time with
`adb shell pm revoke com.barndoor.app.debug android.permission.WRITE_SECURE_SETTINGS`.

**On a rooted device you can skip ADB entirely** — the DNS tab shows a "Root
detected" button that grants the same permission via a root shell (`su`) instead,
triggering your root manager's (Magisk/KernelSU/etc.) one-time approval prompt. Root
is only ever used for that single `pm grant` call, from inside the app — never from
the Quick Settings tile itself (a root prompt blocking a tile tap would risk an ANR),
and never for anything beyond granting that one permission.

## First-time setup

- **DNS tile:** swipe down twice → pencil/edit icon → drag "Barndoor DNS" into your
  active tiles. In the app's DNS tab, check the small box on each server you want
  included in the tile's cycle (all are included by default) — tapping the tile only
  cycles through checked servers, then Off.
- **Per-app DNS:** open the app → **Apps** tab → tap any app → pick a resolver (or
  "Default" to go back to the device-wide one).
- **Custom servers:** DNS tab → "Add custom DNS" → give it a name and either a plain
  IP (or two) or a DNS-over-TLS hostname, or both.

## Why does it need VPN?

Stock, non-rooted Android gives a third-party app exactly one way to influence DNS
for other apps without it: a local `VpnService`. There's no public API to just "set
the system resolver" — the closest thing is Android's own Private DNS setting, which
needs the ADB-granted permission described above and only supports DNS-over-TLS
hostnames, not plain IP resolvers or per-app targeting. Every real DNS-changer app on
the Play Store (AdGuard, NextDNS, RethinkDNS, Blokada) shows the VPN key icon for
exactly this reason — it's not something a "true" implementation would avoid, it's
the only implementation Android permits.

**Important:** this VPN is DNS-only. It never routes your general traffic anywhere,
never touches your public IP, and only ever intercepts port-53/853 DNS traffic bound
for the address it advertises — everything else passes through completely untouched.
It's a mechanism for changing DNS, not a "VPN" in the IP-masking sense.

## How it works

- **DNS changer** — two mechanisms, chosen automatically: Android's native Private DNS
  setting when the ADB (or root-granted) permission is held and the selected server
  supports DNS-over-TLS with no per-app overrides active (zero VPN interface);
  otherwise a local `VpnService` that routes the whole device to its own fake DNS
  address, then for each query looks up which app sent it (by UID) and forwards it
  to that app's assigned resolver — plain UDP/TCP, or DNS-over-TLS for providers
  that support it (same 2-byte length-prefixed framing as DNS-over-TCP, just sent
  over a TLS connection on port 853 instead of plaintext port 53). Anything that
  isn't a DNS query on that address gets an immediate TCP RST or is dropped, so it
  fails fast instead of hanging.
- **Status sync** — both the DNS tab and the tile verify the *actual* system state
  (via `Settings.Global` for system mode, `ConnectivityManager` for VPN mode) rather
  than trusting a cached flag, and self-correct if it's drifted — e.g. if you turn
  Private DNS off directly in Android's own Settings app. The tile uses Android's
  standard (non-active) tile mode specifically so this check runs automatically every
  time the Quick Settings panel opens, even if Barndoor's process was killed in
  between.
- **Speed test / Whois / geolocation** — run from the app itself (not the VPN
  service), so they use the normal network directly; no special permissions needed.
  Geolocation uses the free, keyless [ipwho.is](https://ipwho.is) API; the map is
  Leaflet + OpenStreetMap tiles loaded in a WebView, no API key required.

## Known limitations

- Per-app DNS relies on `ConnectivityManager.getConnectionOwnerUid`, which needs
  Android 10+ (this is why minSdk is 29, not lower). If the lookup ever fails on a
  given device, that query just falls back to the device-wide default rather than
  breaking — but the per-app targeting itself needs Android 10+.
- The TCP relay handles the real-world DNS-over-TCP case (one query, one response,
  connection closes) but isn't a full RFC 793 stack — no retransmission, reordering,
  or pipelining.
- The one gap in the live status sync: if the Quick Settings panel is already open
  when something changes elsewhere, the tile won't repaint again until the panel is
  closed and reopened (the DNS tab itself doesn't have this gap — it has a live
  listener while it's on screen).
- Query logging is intentionally in-memory only (nothing written to disk, capped at
  300 entries, cleared on app kill) and off by default — it's a diagnostic tool, not
  a background habit.
- WHOIS here follows exactly one referral from IANA to the authoritative registry/
  registrar server, which covers the large majority of lookups but isn't a fully
  recursive WHOIS client.
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
  logs/         query log UI
  whois/        WHOIS client, geolocation client, whois + map UI
  util/         IPv4/UDP/TCP checksum helpers used by the DNS proxy
```
