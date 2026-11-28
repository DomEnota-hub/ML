# QA235-07/09 — targeted performance review and shared audio analysis

## Evidence
Xiaomi Android 16, 1.7.235/295, user export 09:43 MSK 2026-11-27: repeated library sweeps with 1197–1198 cached, 0–1 generated, 4 skipped, and 70–151 s per sweep. OCR sessions repeatedly cancelled; known checkpoints persisted. Earlier UI stalls around 1 s are not proof of causal involvement of either worker. No new device measurement on this source yet.

## Changes and acceptance
- Keep original v6 cache names, existing OCR SQLite schema and privacy purge. Versioned per-file receipts avoid parsing unchanged waveform arrays; periodic verification and direct reads still validate actual cache contents. A first upgrade may need one inventory/cache validation pass, not mandatory decoding.
- Replace per-byte Formatter allocations in SHA-256 rendering with identical lowercase hex; use keyed receipt updates rather than scanning the whole receipt set on every track.
- Preserve OCR permission signature (including selected-photo grant refresh), privacy epoch and blocked identities. Separate ordinary catalog preparation from actual power constraints; coalesce rapid updates; exclude irrelevant audio/video input from photo catalog identity. Do not assert uninterrupted OCR through process termination.
- Existing theme callback already guards same selected key. Changing system/manual mode even if effective palette is equal is not identical to selecting the same setting; no broad Activity/theme rewrite based on that log alone.
- Device acceptance: compare on same inventory/conditions, cold launch and repeated resume, one added/changed track, cache corruption, OCR resume after app update, privacy revocation/partial grants, 60/120 Hz and stall events. Preserve counts and do not report unmeasured speedups.

## QA235-09 decision
AcousticAudioDecoder uses normalized sampled spectral landmarks; AcousticScanPlan samples 2–5 windows of 15 s for long tracks, while waveform requires continuous full-track RMS energy. Shared PCM at natural full-decode miss is a possible future experiment, but forcing full decoding during sampled scan or adding FFT to automatic waveform generation would increase work and could alter acoustic semantics. Existing waveforms cannot reconstruct spectrum or PCM. Current loudness analyzer is only current/next playback, not a library-wide background sweep. No change to duplicate scanner, comparison, fingerprints, thresholds, candidate admission or deletion safety; no compulsory fingerprint scan. Retain independent caches. Shared decoder implementation deferred until paired benchmark and equivalent result evidence; this is a research decision, not a delivered speedup.
