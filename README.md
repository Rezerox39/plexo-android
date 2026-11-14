# Plexo for Android

A native Android download manager that splits one file's byte ranges across **every
network link the phone has on at the same time** — Wi-Fi plus mobile data plus a
Bluetooth PAN plus a USB/OTG Ethernet adapter — and fetches the chunks in parallel.

It is an Android take on [anmolkapil/plexo](https://github.com/anmolkapil/plexo),
which does the same thing on desktop. Same core idea — no VPN, no virtual adapter,
no server — and the same look: the graphite panel, warm off-white type, amber
accent and the teal/amber/steel link colours are taken from the desktop app, along
with its Manrope + Roboto Mono typography and its merge-node mark. Each connection
downloads a different `Range` of the file, and the parts are written into one file
at their offsets.

The parity work was checked against the **shipped desktop v1.0.0-rc.15** app
(its `app.asar`, not only the repository): the same screens, filters, row actions,
queue, history, schedule, data/speed caps, network editor, theme palette and speed
graph are represented here where Android can support them. The exact
implemented/partial/impossible list lives in
[docs/DESKTOP-PARITY.md](docs/DESKTOP-PARITY.md).

---

## Read this first: what is and isn't possible

**Two Wi-Fi networks at the same time is not possible on stock Android.**

A phone has one Wi-Fi radio, and it can only be associated with one access point
at a time. There is no public API — and no permission — that lets an app join a
second SSID on that radio while the first is connected. Samsung's "Dual Wi-Fi"
splits *one* access point's bands; it does not give you a second network. Even a
rooted device would need vendor firmware support.

So "connect to multiple Wi-Fis at once" is not something any app can ship, and
claiming otherwise would be a lie.

**What actually works, and what this app does:**

| Link | How it helps |
| --- | --- |
| Wi-Fi | Normal traffic |
| Mobile data | Runs at the same time as Wi-Fi, on its own radio |
| Bluetooth | PAN through a paired phone that shares its connection; slow but independent |
| Wired / USB | USB/OTG Ethernet adapter or USB-C dock, usually the fastest link |

Android lets an app call `ConnectivityManager.requestNetwork()` for a transport
that is *not* the default network, and then bind its own sockets to that network
with `Network.bindSocket()`. That is the whole trick. Plexo asks for a cellular
network even while Wi-Fi is the system default, so both radios are live, then
opens separate `OkHttpClient`s pinned to each. This is real aggregation for
traffic the app sends itself — exactly the scope Plexo's desktop version has.

Aggregating *all* apps would require a VPN service plus a relay server on the
other side (that is how Speedify works). That is a different product with
ongoing server cost; it is not this build.

### Bluetooth PAN

When the phone is connected to another device's Bluetooth PAN, Android exposes it
as a network with `TRANSPORT_BLUETOOTH` (interface `bt-pan`), and Plexo can bind
downloads to it. Two honest limits:

- Android gives apps no API to *start* a PAN connection. Pair the sharing phone
  in system Bluetooth settings, tap the gear next to it and allow internet access;
  the app shows an **Open Bluetooth settings** button while the link is waiting.
- Bluetooth PAN tops out around 1–2 Mbps. It is a genuine extra pipe, but a small
  one: useful on a bad Wi-Fi link, never a big speed-up.

### USB tethering

There are two directions, and only one of them is possible:

- **Phone as host (works):** a USB-C dock or USB/OTG Ethernet adapter gives the
  phone a real Ethernet interface (`eth0`). The **Wired / USB** link requests both
  `TRANSPORT_ETHERNET` and, on Android 12+, `TRANSPORT_USB`, so docks and adapters
  are picked up whichever way the OEM labels them.
- **Phone as client (does not work):** stock Android cannot consume internet from
  a computer over a USB cable. That is "reverse tethering", which only exists as
  adb/root hacks. The phone's USB stack implements the tethering *host* side, so
  there is nothing for a normal app to request.

One more safety rule: if two links are handed networks on the same kernel
interface, Plexo counts only one of them. Two requests over one pipe would look
like bonding while adding no bandwidth, and the UI says so instead.

## Screens and features

- **Links panel** — Wi-Fi, Mobile data, Bluetooth PAN and Wired/USB with live
  state, a switch per link, a rename (40 characters, like desktop), an eight-colour
  swatch picker, per-link stream count (Auto/4/8/16/32), a per-link speed cap and a
  per-link data cap (day/week/month) counted from Plexo's own bytes. Toggles and
  names persist across launches. Android forbids apps from switching Wi-Fi or
  mobile data on themselves, so a switch only declares "Plexo may use this link":
  when the radio is off the panel says so immediately and offers a button that
  opens the right system panel.
- **Parallel range downloader** — probes the server, honours `Accept-Ranges`,
  works out the file name from `Content-Disposition`, and lets all links pull from
  one shared byte cursor. Like Via's downloader, the file is split into one large
  equal range per stream (up to 256 MB by default) instead of hundreds of tiny
  requests, which is what keeps Google Drive and similar hosts from throttling the
  job. A 429 pauses every stream for the server's `Retry-After` window and retries
  the same range; only repeated refusals fall back to one connection. Speeds are
  averaged over a rolling two-second window, so the readout does not flicker with
  the modem's burst/sleep cycle. Connections are pooled per link, so retries reuse
  warm TCP/TLS sessions instead of paying a handshake each time.
- **Queue and history** — desktop's *downloads at once* setting (1–4) is honoured;
  extra files wait in the queue. Every finished or failed transfer stays in a
  scrollable history with the file's source link, size, time and saved path.
  Filters: All / Downloading / Queued / Needs attention / Finished, with counts.
- **Row actions and detail** — cancel, retry, remove, copy link, open the saved
  file, and a detail sheet with the full per-link split, a 60-second throughput
  graph, ETA, stream count and source URL.
- **Four themes** — System, Light, Dark and Pure black. Dark uses the exact desktop
  palette (`#1B1E20` graphite, `#202325` panels, `#EAE7E2` warm text, `#D8A44C`
  amber); Light is warm paper; Pure black is OLED true black. The launcher icon and
  in-app mark are the desktop's three converging network lines and white arrow.
- **Desktop speed controls** — MB/s or Mbps everywhere, a shared total speed limit,
  Slow mode (desktop's one-switch 2 MB/s default), and a per-link cap. The app-wide
  limiter paces actual bytes across every stream and every concurrent file, not one
  request at a time.
- **Download schedule** — enable, start/end time, weekdays, and an optional end
  date. Queued files start when the window opens and wait while it is closed.
  (A file already transferring finishes rather than being cut mid-range.)
- **Live split view** — aggregate speed, ETA, a throughput strip chart, and
  bytes/speed per link, plus a stacked bar showing each link's share of each file.
- **Graceful fallback** — a server that ignores `Range` gets a single-stream
  download and a clear note in the UI.
- **Foreground service** — progress notification for the queue with *Cancel all*,
  survives leaving the app.
- **Saves into `Downloads/Plexo`** through MediaStore, so no storage permission is
  needed on Android 10+.
- **Share to Plexo** — send a link from any browser via the Android share sheet; it
  opens the new-download sheet with the URL already filled.

## Install

Grab `plexo-0.4.2-debug.apk` from the [0.4.2 pre-release](../../releases/tag/v0.4.2) and open it
on the phone. It is debug-signed, so Android asks you to allow installing from your
browser or file manager the first time.

## Build

Requirements: JDK 17 and the Android SDK (platform 35). The Gradle wrapper is
included.

```bash
# Local, with Android Studio or an installed SDK:
./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

No SDK on hand? Push the repo to GitHub. `.github/workflows/build.yml` builds the
debug APK on every push and uploads it as the `plexo-debug-apk` artifact — install
that on the phone.

`minSdk` is 29 (Android 10). It is 29 and not lower because MediaStore's Downloads
collection and scoped storage make saving files permission-free from there.

## Test that it really aggregates

Run the bundled range-capable, throttled server on a laptop:

```bash
python3 tools/testserver.py --size 400 --rate 4
```

It prints a URL. With **only Wi-Fi** on, a download should sit near 4 MB/s. Turn
on **Mobile data** in Plexo and start again: two radios at 4 MB/s each should get
close to 8 MB/s, and the split bar should show both links. That difference is the
proof.

Caveats worth knowing:

- Some carriers or OEMs tear down mobile data while Wi-Fi is connected. If the
  cell link stays "Looking for a network", enable **Developer options › Mobile
  data always active**.
- The server must support `Range` requests. Most CDNs and file hosts do; a few
  don't, and then only one link can be used.
- Wi-Fi and mobile data are metered differently. Mobile data is **off by default**
  in Plexo so it can never silently cost money.

## How it works

```
ConnectivityManager.requestNetwork(TRANSPORT_WIFI)       -> Network A
ConnectivityManager.requestNetwork(TRANSPORT_CELLULAR)   -> Network B
ConnectivityManager.requestNetwork(TRANSPORT_BLUETOOTH)  -> Network C  (bt-pan)
ConnectivityManager.requestNetwork(ETHERNET | USB)       -> Network D  (eth0)

probe:  GET Range: bytes=0-0        -> size, Accept-Ranges, filename

ranges: shared AtomicLong byte cursor; one equal range per stream
        ~1 GB file, 8 streams -> 8 requests of ~128 MB
        256 MB range cap keeps retries bounded on very large files

        worker(link A) x4  worker(link B) x4  worker(link C) x4
        each: GET Range: bytes=<start>-<end>  via client pinned to its Network
              FileChannel.write(buffer, position)   // thread-safe positional write

        429: wait for Retry-After, retry the same range, keep every stream
             paused until the shared gate opens

done:   verify length, copy into MediaStore Downloads/Plexo
```

Key files:

| Path | Role |
| --- | --- |
| `core/Links.kt` | Network requests, link state, `BoundSocketFactory`, per-network DNS and OkHttp clients |
| `core/Downloads.kt` | Probe, large equal-range planner, work-stealing workers, 429 gate, retries, progress maths, queue/history repository |
| `core/RateLimiter.kt` | Shared total and per-link byte pacing across every stream and file |
| `core/Settings.kt` | Themes, speed units, queue depth, schedule, caps and per-link preferences |
| `core/Store.kt` | MediaStore hand-off |
| `service/DownloadService.kt` | Foreground service and aggregate notification |
| `ui/HomeScreen.kt` | Downloads list, filters, new-download sheet, detail sheet |
| `ui/SettingsScreen.kt` | Appearance, engine, speed, schedule, network editor |
| `ui/Theme.kt` | Four Plexo palettes and typography |

Error handling: a range that fails is retried up to six times on another link; a
link that fails three times is taken out of rotation for that download; leftover
ranges get up to three extra passes after the main pass; the whole job only fails
when every link is unusable. Subtly, this means unplugging the Ethernet adapter
mid-download costs you nothing — its remaining ranges are simply picked up by
Wi-Fi, and a partly written range is re-fetched and counted once.

HTTP 429 is treated as backpressure, not a dead file. The probe honours
`Retry-After`, waits and tries once more. During the parallel pass, a 429 opens a
shared gate: every stream pauses for the same `Retry-After` window, then the same
range is retried. Only repeated refusals (four per range) fall back to one
connection after the cooldown. A 429 is never counted as a dead link. The request
headers are browser-shaped (referer and accept headers, no custom bot-looking
agent) because file hosts commonly put non-browser clients behind a 403/429 wall.

## Not in this build

The detailed table is in [docs/DESKTOP-PARITY.md](docs/DESKTOP-PARITY.md). The
honest summary:

1. **Pause / resume across app restarts** — the engine can retry ranges inside a
   running job, but it does not yet persist a chunk map, so closing the process
   cancels the transfer and removes its `.part`. Desktop can pause and continue.
2. **Torrents, magnet links and NZB** — desktop Plexo has a torrent engine. This
   port has no torrent stack yet; adding one is a project of its own, not a toggle.
3. **Schedule stopping an active file at the end of the window** — the schedule
   gates *new* downloads. It does not cut a transfer that is already running.
4. **Desktop's multi-select and bulk action bar** — actions are per download here.
5. **VPN relay for system-wide aggregation** — needs a server endpoint and a
   `VpnService`; see the section above.
6. **Full system data counters** — the per-link data caps count only bytes Plexo
   itself carries. Reading system-wide per-network usage needs the sensitive
   `PACKAGE_USAGE_STATS` permission, which this app deliberately does not request.

## Licence

The Android implementation here is original code. Upstream Plexo is MIT licensed;
keep its copyright notice if you reuse any of its assets or code. Manrope and
Roboto Mono are bundled under the SIL Open Font License; the licence texts ship in
`app/src/main/assets/licenses/`.
