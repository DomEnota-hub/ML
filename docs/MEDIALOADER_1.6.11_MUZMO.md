# MediaLoader 1.6.11 — Muzmo manual source prototype

## Scope
- Production package `com.domenota.medialoader`, versionCode 51, versionName 1.6.11, ARM64 release.
- Experiment enabled only through Settings → **Для разработки** → **Музыкальный источник**.
- Default remains HitMoz; preference persists across restarts.
- Muzmo is selected explicitly and has a separate hidden browser session.
- No global proxy, VPN manipulation, or changes to Instagram, YouTube, VK, RUTUBE.
- No automatic source fallback in this version.

## User-confirmed samples (2026-10-20)
- Search: `https://rmr.muzmo.cc/search?q=Ева`
- Detail: `https://rmr.muzmo.cc/info?id=82447323`
- Original MP3: `https://rmr.muzmo.cc/get/music/20261006/Eva_Vlasova_-_Vchera_82447323.mp3`
- A separate direct MP3 link for DJ NARODNII was confirmed in two browsers by the user.

## Network and safety
- Parse results by inspecting links to `/info?id=...` within Muzmo's hidden WebView.
- On the user's explicit download click, inspect the selected info page for a real `/get/music/...` URL.
- Never synthesize a filename/date from an info ID or auto-download another track.
- Preserve existing Cronet/platform fallback, MIME and MP3-signature checks, redirect limits and size limits.
- Muzmo redirects currently allow only the confirmed Muzmo host. If an unknown CDN appears, stop and record the host rather than trusting arbitrary redirects.
- Minimal diagnostics record counts, network error codes, and selected host, not HTML or cookies.

## Limitations / device test needed
Muzmo HTML was not accessible to development-side fetch tooling. The initial DOM selectors are synthetic/heuristic and MUST be validated on the user's Android device before promotion.
- Check search results render for `Ева` and `DJ NARODNII`.
- Select **Ева Власова — Вчера**, verify info-page link and actual MP3 download.
- Observe whether Muzmo redirects to a new CDN; do not guess its hostname.
- Switch back to HitMoz and verify its original behaviour.
- Check unrelated sources use their normal networking.
- Export extended app logs only if the DOM script fails; avoid exposing user data.

## Tests
- `MuzmoDownloadUrlsTest` and `FullSongSourceTest` cover policy and preferences.
- `node scripts/test-muzmo-native-script.cjs` checks injected JavaScript with synthetic DOM examples.
- CI: `testDebugUnitTest`, `lintDebug`, `assembleRelease -Parm64Release=true`.


## 1.6.12 (52): manual-source routing fix (2026-10-20)
On-device log showed `Experimental music source changed to Muzmo` but subsequent full-song windows ran HitMoz. The installed 1.6.11 route did not log its selected adapter. Root cause is not confirmed.

Fix: maintain one observable `FullSongSource` state in `MediaLoaderApp`, pass it to Settings and both PreviewScreen hosts, commit the preference before updating state, and emit `MusicSource Full-song window launched; adapter=...` when the actual dialog is composed. No silent fallback from Muzmo to HitMoz. This does not change either site's parser or the global Android VPN configuration.


## 1.6.13 (53): source frozen at user action
Device log from 1.6.12 proves `MusicSource Saved manual source=muzmo` while the subsequent WebView still starts HitMoz. No matching `Full-song window launched` diagnostic is present. The actual cause is not yet conclusively established.

The ViewModel now reads the persistent source immediately when `findFullSong()` is accepted, logs `Full-song request accepted; adapter=...`, and stores `PreviewState.fullSongSource` alongside `fullSongSessionId`. Both preview hosts use this launch snapshot instead of a separate read or mutable settings state. The source cannot drift during recognition or recomposition. This is still a prototype until device logs show Muzmo launches.
