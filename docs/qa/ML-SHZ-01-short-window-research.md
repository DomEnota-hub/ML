# SHZ-01 — Short acoustic window research (25.11.2026)

Status: OFFLINE_CHARACTERIZATION_COMPLETE / LIVE_MATCH_VALIDATION_BLOCKED / NOT_ACCEPTED_FOR_ENGINE_PROMOTION.

## Scope and baseline

Exact navigation base e04f234ec257a6c3507feed556f1008eb21b2c6e. Recognition code is identical to the prior qualified player-only candidate 753d8c375e3c59925c360755dd6ce787b520d94f. Existing microphone path collects 4–12 seconds, returns a PCM array, then recognizes it. ShazamRecognitionProvider enforces MIN_SAMPLES=64000 and tries direct, then two phase-shifted loop variants for short accepted clips. Do not change that path or Instagram, saved video, local file processing. ShazamClient has global serialized requests, 1500 ms start spacing and 429 cooldown. It uses an unofficial endpoint; neither supported minimum clip nor server latency is guaranteed.

Generator accepts 128 samples per FFT step at 16 kHz (8 ms); 46-pass recognition delay is about 368 ms. A one-second packet can be structurally valid even with zero peaks (existing test silentOneSecondClipProducesValidShazamPacket). Count alone cannot decide match reliability.

## Executed local study

Unmodified source SHA256: generator 9627321242c8e5578749217b66dec2eeb7a8ce6d6bad1c8b11e1d893bd1cc51d, core MusicRecognition.kt 2a314410f832ec312efc33992b67dbbd9cb092e6af528815f4f720aa9364c371.
OpenJDK 21.0.12, kotlinc-jvm 1.9.0, host JVM, not Android. Deterministic 16-bit mono 16 kHz synthetic signals. For each of five signals and each 1/2/3/4 s prefix, five timed generations with deterministic packet equality, header/sample count/CRC/TLV and peak-count checks. All 20 cases passed. No user audio or raw recording retained. Reproduce using standalone Study.kt below.

| signal | peaks 1 s | 2 s | 3 s | 4 s | 1 s median host ms | 4 s median host ms |
|---|---:|---:|---:|---:|---:|---:|
| Silence | 0 | 0 | 0 | 0 | 6.81 | 24.05 |
| Constant tone | 1 | 1 | 1 | 1 | 5.89 | 24.25 |
| Random noise | 106 | 303 | 491 | 668 | 6.74 | 25.71 |
| Synthetic changing harmonic/percussive signal | 66 | 167 | 267 | 369 | 6.30 | 24.60 |
| Same signal with noise | 94 | 271 | 424 | 586 | 7.57 | 25.26 |

A one-second music-like signature was 420 bytes; noise produced 624 bytes. Noise can produce *more* peaks than the signal, so peak count or packet length alone cannot justify early acceptance. Host timing excludes microphone, network, TLS, provider and Android scheduling. It does not measure recognition speed or accuracy. Do not replace 4 s with 1 s in production or claim one-second recognition.

## Live evidence blocker

DNS lookup of amp.shazam.com and github.com in the local execution environment returned no address. Public upstream ShazamIO examples tree exposes Gloria.ogg and dora.ogg, but raw audio download failed (no accessible binary) and the existing opt-in JVM test ShazamLiveIntegrationTest requires SHAZAM_LIVE_PCM; fixture absent. Normal CI intentionally skips it. No unauthorized workflow, provider requests or synthetic match results; no alteration of this existing skip, rate limits or default behavior.

## Remaining gate

Use permitted audio fixtures with ground-truth track IDs (several distinct recordings, quiet/speech/noise/unknown track controls, multiple offsets), same PCM prefixes, same network/environment. Compare 1/2/3/4 s direct requests plus current 4–12 s reference; record correct/wrong/no-match, response latency, total wall time, request count and 429, stop on rate limit, no concurrent requests. Measure first-result timing and false positives, including noisy and silent fragments. A reliable initial window cannot be selected from offline peak counts. Complete actual live test before stage SHZ-02; keep all existing production recognition paths untouched. If live access remains unavailable, retain this as research-only branch, do not mix into qualified navigation release. Physical Android QA needed before promotion.

Reproduce offline:
```sh
root=app/src/main/java/com/domenota/medialoader
kotlinc "$root/core/recognition/MusicRecognition.kt" "$root/data/recognition/ShazamSignatureGenerator.kt" scripts/research/shazam-short-window/Study.kt -include-runtime -d /tmp/ml-shz-study.jar
java -Xmx512m -jar /tmp/ml-shz-study.jar
```
