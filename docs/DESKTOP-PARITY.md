# Desktop Plexo parity

Reference build: **Plexo 1.0.0-rc.15**. The inventory was taken from the shipped
Linux ARM64 `.deb` (`plexo` package version `1.0.0~rc.15`; its packaged
`app.asar` reports `"version": "1.0.0-rc.15"`) plus the matching source tag. The
renderer bundle is the one whose labels, palette and behaviour were inventoried.

Status key: **Done** = works on Android; **Partial** = works with a documented
limit; **Impossible** = the Android platform forbids it; **Missing** = not built yet.

## Screens and actions

| Desktop feature | Android 0.4.0 |
| --- | --- |
| Downloads screen with All / Downloading / Queued / Needs attention / Finished filters and counts | Done |
| Row actions: pause, resume, retry, fix link, download again, open, reveal, copy link, cancel, remove, trash | Partial — cancel, retry, remove from list, copy link, open file and details are present. Pause/resume, reveal folder, fix-link dialog and delete-file are missing |
| Multi-select and bulk action bar | Missing |
| Detail view with per-link split, stream count, ETA, throughput graph (60 s at 1 Hz) | Done — graph is a 60-sample strip chart |
| New download dialog: URL, file name, destination, network chips, streams Auto/4/8/16/32, torrent file picker, probe summary | Partial — URL, name, network chips and stream choice are present. Destination is fixed to Downloads/Plexo; probe details appear once the job starts; no torrent picker |
| Network controls: enable, rename (40 chars), colour, streams, speed limit, data limit + period | Done |
| Schedule: enable, start/end, weekday mask, optional end date | Done for starting queued files; an active file is not stopped at the window's end |
| Downloads at once (1–4) | Done |
| Total speed limit, Slow mode (2 MB/s default) | Done, enforced across every stream and file |
| MB/s vs Mbps switch | Done |
| Theme: light/dark | Done, plus System and Pure black |
| History of finished/error downloads | Done (last 100, persisted) |
| Share-sheet / browser hand-off | Done for Android's share sheet; no desktop browser extension |
| Update indicator | Missing (APK is installed manually) |
| Free-space readout | Done in Settings |
| Torrent engine, magnet links, piece map, file selection, peer count | Missing |
| NZB/newsgroup support | Missing (also absent from desktop 1.0.0-rc.15) |

## Platform limits, not missing work

| Desktop behaviour | Android reality |
| --- | --- |
| Use several Wi-Fi interfaces at once | One Wi-Fi radio associates with one network. Dual-Wi-Fi vendor features split one AP's bands; they are not a second network. Impossible for a normal app. |
| Toggle a radio from the app | Android 10+ blocks third-party apps from enabling/disabling Wi-Fi and mobile data. Plexo selects links and opens the system panel. Impossible without a system/privileged app. |
| Route all system traffic through the bond | Requires a VpnService plus a relay server. Plexo aggregates its own sockets only. |
| Consume USB tethering from a laptop | Stock Android exposes only the host/tethering side. USB-C Ethernet docks do work as a wired link. |
| Per-network system data usage | Reading it requires `PACKAGE_USAGE_STATS` (sensitive). Plexo counts its own bytes instead. |

## Engine invariants carried over

- Contiguous, non-overlapping ranges from one atomic cursor; the last range is
  clamped to `totalBytes - 1`.
- Three retry rounds for leftover ranges, six attempts per range, and a link is
  dropped from rotation after three consecutive failures.
- Two-second rolling speed window; per-link measured speed determines request
  size; 256 KB floor; configured chunk size is the ceiling.
- HTTP/1.1 per stream so OkHttp cannot multiplex all ranges over one TCP
  connection; per-network DNS and socket binding; per-network cookie jar.
- A dropped link is waited for (30 s when *Keep waiting for a link* is on), so a
  mobile handover does not fail the file.
