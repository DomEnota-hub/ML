# Diagnostic QA update — 2026-11-03

PR54 existing branch; original media, rendering/export behavior and official releases preserved.

- AppLog now uses a single ordered background writer with a512-record bounded queue; overflow drops records instead of blocking callers. Export reports dropped count and flush status. Event time and caller thread are captured before queuing. Error causes/redaction retained; crash path attempts a bounded500ms flush.
- Foreground-only heartbeat samples main-thread stalls >=700ms, at most3stack samples per stall spaced>=2s, plus recovery. Background lifecycle stops watchdog/frame callback. Frame callback aggregates gaps>=50ms in10s windows (not a measurement of exactly how many frames were rendered/dropped). Java heap usage/max are included in timing/stall reports.
- Startup first-pre-draw, root navigation dispatch, screen first-Compose-frame and library-data-ready are separately labeled. These timings do not claim first content pixels or every UI input latency. Template route/mode/source only; no media filenames, URIs or text contents added by performance probes.
- Slow timeline-thumbnail batches and actual source-boundary decode are timed; trim gesture start/end recorded. Existing phone scan start/end/count/ms retained.
- Performance events work without enabling extended logging. Reproduce stutters for20–30s and export ordinary application Markdown log. This diagnoses delays; it is not a claim that freezes are resolved.

Verification: selected BoundedLogWriterTest, StallWindowTest, AppLogAsyncTest, LogDisplayTest, LogExplanationTest and VideoTrimDecoderLifetimeTest PASS; compile included (3m44s after environment recovery). No full suite/native export matrix repeated. No physical Android target available; real-device watchdog behavior/overhead and freeze cause remain NOT_DEVICE_TESTED.

Build environment recovery: full workspace disk caused incomplete Kapt files. Local build outputs/cache were moved to /tmp, stale generated Kapt output retained separately. No user source or published release removed. Temporary ZIP duplicates of the previous uploaded QA APK were removed.
