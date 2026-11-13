# Plexo for Android

A native Android download manager that splits one file's byte ranges across **every
network link the phone has on at the same time** — Wi-Fi plus mobile data plus a
Bluetooth PAN plus a USB/OTG Ethernet adapter — and fetches the chunks in parallel.

It is an Android take on [anmolkapil/plexo](https://github.com/anmolkapil/plexo),
which does the same thing on desktop. Same core idea: no VPN, no virtual adapter,
no server. Each connection downloads a different `Range` of the file, and the
parts are written into one file at their offsets.

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
  state, and a switch per link. Toggles persist across launches. Android 10+
  forbids apps from switching Wi-Fi or mobile data on themselves, so a switch
  only declares "Plexo may use this link": when the radio is off the panel says
  so immediately and offers a button that opens the right system panel. The
  network request stays registered, so the link joins as soon as the radio
  comes up, without a restart or a retry wait.
- **Parallel chunked downloader** — probes the server, honours `Accept-Ranges`,
  works out the file name from `Content-Disposition`, splits the file into chunks
  and lets all links pull from one shared cursor. Faster links simply take more
  chunks; no manual percentage split.
- **Live split view** — total speed, ETA, and bytes/speed per link, plus a stacked
  bar showing each link's share of the file.
- **Graceful fallback** — a server that ignores `Range` gets a single-stream
  download and a clear note in the UI.
- **Foreground service** — progress notification with cancel, survives leaving
  the app.
- **Saves into `Download/Plexo`** through MediaStore, so no storage permission is
  needed on Android 10+.
- **Share to Plexo** — send a link from any browser via the Android share sheet.

## Install

Grab `plexo-0.2.2-debug.apk` from the [latest release](../../releases/latest) and open it
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

chunks: [0..8MB) [8..16MB) [16..24MB) ...   one shared cursor
        worker(link A) x3  worker(link B) x3  worker(link C) x3
        each: GET Range: bytes=<start>-<end>  via client pinned to its Network
              FileChannel.write(buffer, position)   // thread-safe positional write

done:   verify length, copy into MediaStore Downloads/Plexo
```

Key files:

| Path | Role |
| --- | --- |
| `core/Links.kt` | Network requests, link state, `BoundSocketFactory`, per-network DNS and OkHttp clients |
| `core/Downloads.kt` | Probe, chunk planner, work-stealing workers, retries, progress maths, repository |
| `core/Store.kt` | MediaStore hand-off |
| `service/DownloadService.kt` | Foreground service and notification |
| `ui/HomeScreen.kt` | Compose UI: links, new download, live split, tuning |

Error handling: a chunk that fails is retried up to six times on another link; a
link that fails three times is taken out of rotation for that download; the whole
job only fails when every link is unusable. Subtly, this means unplugging the
Ethernet adapter mid-download costs you nothing — its remaining chunks are simply
picked up by Wi-Fi.

## Not in this build

Deliberately out of scope for a first version, in order of usefulness:

1. **Resume across app restarts** — would need persisted chunk maps.
2. **Queue and multiple concurrent downloads** — the engine is one-at-a-time today.
3. **Torrents, magnet links and NZB** — Plexo desktop supports these; they are each
   a project of their own.
4. **VPN relay for system-wide aggregation** — needs a server endpoint and a
   `VpnService`; see the section above.
5. **Per-link speed limits and data caps.**

## Licence

The Android implementation here is original code. Upstream Plexo is MIT licensed;
keep its copyright notice if you reuse any of its assets or code.
