# MediaLoader 1.7.207 / 267 — Xiaomi Android 16 QA checklist (NOT RUN)

Status: DEVICE_QA_PENDING. This document is a test plan, not proof of success.
Install one signed DomEnota ARM64 QA APK over the existing 1.7.206 (or earlier compatible signed version) without uninstalling or clearing app data. Back up any irreplaceable media. Do not delete any old v1/v2 HIDE/SHOW journal, prior owner marker, or private data.

## 1. Preserve the interrupted six-hour duplicate search
- Before installing, confirm the interrupted report contains 612 preliminary groups (or the actual currently saved count), and note its timestamp; do not discard or restart the scan.
- Install in place. Open **Music → Duplicate search → Journal → Results**.
- Preliminary groups must open in the full ordinary result interface, without scanning/decoding all 3189 tracks or incrementing the scan attempt counter.
- Verify filters All / Exact / Acoustic / Versions, comparison, preview, selection, keep-this-version, scroll retention, and reopening after Activity recreation / app restart.
- Save a non-destructive selection and reopen. It must survive as *preliminary* data. Neither the completed history nor the acoustic cache should be replaced.
- Confirm changed, unavailable, private or playing tracks are ineligible for trash. Use only disposable test copies for **trash**; every valid group must retain one independently verified copy.
- Check state after cancellation, process death, denied permission, and low storage. The report must not become falsely "Completed." No automatic rescan.

## 2. Acoustic processing and memory
- On a disposable library large enough to exercise 3200-track grouping, observe JVM heap maximum from SCAN_HEAP (regular and large class), peak memory and GC, actual phase 1/4→4/4, elapsed per phase, ETA when grounded, and foreground notification.
- A system ForegroundService dataSync time quota can still stop a long run on Android 15/16; never claim that WakeLock bypasses Android timeouts.
- Confirm cancellation/pause does not clear existing partial reports and that recovery uses valid per-track fingerprint cache without re-decoding verified tracks.
- Verify no OOM during candidate pairs, no frozen UI, no repeated progress reset, and stage labels match actual work.
- Record only non-sensitive diagnostics, no private filenames/paths/keys.

## 3. Ordinary folder hide and show
- Create **disposable** physical directories containing 1, 8 and 46 media items. For each, create a corresponding ML virtual folder pointing to the exact physical directory.
- **Hide media** should show a short confirmation; choose no directory by manually navigating the tree. If a one-time Android SAF permission is required, the document picker must open at the real folder and require deliberate system authorization.
- On subsequent hides of the same authorized physical directory, no repeated picker should be needed.
- Test one unrelated media file in the same physical directory: the operation MUST refuse directory-wide hide and must not write .nomedia.
- Test an ML virtual folder spanning multiple physical directories: refuse to claim Android-wide hide; never silently hide unrelated contents.
- Test foreign .nomedia, revoked SAF permission, changed file size, missing file, non-primary volume, root/DCIM/Pictures top-level restrictions, repeated operations and process kill. No original byte/file relocation, deletion, filename change, or timestamp rewriting.
- Verify distinct statuses: ML excluded vs Android gallery indexing, including Xiaomi Gallery delayed index update. Show media must remove only ML-owned marker, never foreign one.
- Downloads history, collections, favorites, original dates, and Private must remain intact; stale MediaStore URI must not be silently forged into a valid link.

## 4. Music, launcher and regressions
- Open Music repeatedly, from Downloads, from external shortcut, after app restart and after leaving player: track list first, functions available through the existing switcher, scroll/queue position retained.
- Inspect all three approved icon images on Xiaomi Launcher, adaptive/round mask, light/dark theme and shortcuts: main ML, Gallery, Music. No redrawn replacements.
- Test Gallery 10+ open/scroll/return cycles with 11k+ media if available: no empty flicker, thumbnail/icon stability, privacy filtering, no unrelated reindex thrashing. Test last "Frequently listened" cached view.
- Smoke test Instagram/YouTube/VK/RUTUBE, Muzmo, Shazam, MP3, video player, private section, download history, photo editor, playback foreground notifications. Existing working naming logic must not regress.

## 5. Final acceptance gate
- Record app versionName=1.7.207, versionCode=267, package=com.domenota.medialoader, arm64-v8a, signer SHA256 4f6f0ddf4d8fd2669fd2a3a6ebab6e84bda17281a3a5badada0e1eeb73a4f1d2.
- Compare GitHub exact commit with the source archive, full JVM/Compose canonical XML, Android lint and ARM64 build. Confirm downloaded Drive pieces / reassembled APK SHA256.
- Mark DEVICE_QA_PASSED **only** after real device testing with actual evidence. CI success and a delivered APK do not make this stable or production.
