# ML 1.7.189 / 249 — Stage 2: duplicate search (QA-188-05–06)

Baseline: stage 1 commit 97dbb49146dad23d2eaaa4ea3e7d2deee9873743.
Observed by user: 29 of 561 music files after ~18 min; gallery ~5–10/min, not a controlled benchmark.

Implementation:
- Separate metadata/exact and acoustic passes; partial reports, current-phase count/ETA, independent music/gallery foreground services and journals. Read-only partials; verify content before explicit trash, never select every group member, active music track excluded.
- Audio: pinned full fingerprint algorithm retained; cached FFT tables and primitive buffers, skip FFT at frames that cannot contribute; compact landmark/index representation. Quick scan <=45 s full, then 2/3/5 distributed 15 s windows; expanded full scan, fallback for unsupported seeking. SHA-256 and encoded payload matching stay independent from acoustic or title candidates.
- Gallery: per-file visual fingerprint checkpoints (URI, size, modified time, privacy generation), physical alias deduplication. Cache only complete decodes. Full verification still required before trash.
- Journals: latest finished snapshot plus eight previous snapshots, one partial snapshot per domain. Pause or interruption reuses verified per-file checkpoints. No shared music/gallery history; privacy changes clear both public search caches and reports.
- Comparison reuses the existing player/editor PCM RMS v6 waveform cache and cancellation gate. It does not use the legacy compressed packet-size fallback. Missing duration/PCM is shown as unavailable. Acoustic spectral fingerprints cannot be derived from the stored RMS envelope; the sampled spectral scan does not start a full waveform decode.
- Diagnostics: AppLog tag DuplicateScanMetrics contains only aggregate elapsed time, stage counts and cache hits. It does not include filenames or URIs.

Automated tests: pinned synthetic full-fingerprint equivalence, expanded trim match, indexed candidates, acoustic sampling, progress, selection, persistence/privacy, original collections, general JVM and Android lint. No release or distribution during this stage.

Device acceptance (deferred to final signed 1.7.189):
1. Compare cold/warm baseline vs updated scan on same 561 files and large gallery; same phone/storage, record exact counts and aggregate logs; compare metadata, SHA, payload, PCM/FFT, visual frames, grouping and cache. Do not infer physical throughput from JVM timings.
2. Test short/long and uncommon codecs, cross-codec versions and trimmed recordings; compare quick and expanded recall, validate matching-fragment seeks and waveform; ensure main queue, position and history stay intact.
3. Test pause, interruption, reopen, foreground notification, Android background limits, changed/deleted/private files, report dates and separate journals, scroll and selection. No deletion during scanning; trash must preserve an unselected member and verify current content.
4. Compare photo/video exact copies, similar pictures and videos; check previews, controls, portrait/landscape, large font and all themes. Validate memory use and battery on physical device.
