# Downloads grid and video controls Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans for inline implementation.

**Goal:** Four-column download gallery, watch progress, immediate aspect feedback and right-center lock.
**Architecture:** Reuse DownloadRow menu for compact tiles and existing LazyColumn for lazy grid rows. Record progress in existing video position preferences. Overlay feedback uses cancellable Compose timer.
**Tech Stack:** Kotlin / Compose / SharedPreferences / ExoPlayer.
**Spec:** docs/superpowers/specs/2026-10-28-download-grid-player-design.md

## Constraints / review focus
- Filters, search, favorites, folders, private-transfer and file actions must work in both views.
- No eager video decoding or whole-history thumbnail preload for progress.
- Unknown/zero duration and out-of-range positions must not render invalid progress.
- Rapid aspect taps replace feedback without delayed Toast queue.
- Four cells and incomplete final row keep equal sizes; headers must remain usable on narrow screens.

## Tasks
- [x] Add progress store tests for saved resume position, unknown duration and completed video; implement record/read and thumbnail stripe; wire viewer and floating saves.
- [x] Add Compose regression test for four-column tiles, no visible titles, long-press menu and saved mode; implement compact DownloadRow and grid/list switch, update prefetch keys.
- [x] Add overlay test for immediate replacement and cancelled old timeout; implement aspect overlay and centered edge lock/unlock.
- [x] Run full unit suite and ARM64 release build, inspect changes; fix concrete failures, document and deliver signed APK.

## Verification record
- Grid regression failed before the switch existed, passed after implementation. Progress tests pass for old resume values and completion.
- Notice timing test initially advanced only Compose time; Robolectric Android main-looper time must also advance for coroutine delays. Corrected the test scheduler, without changing production timing. Both replacement and repeated-label cases pass.
- Fresh read-only review: no Important/Critical findings.
- Delivery: full suite 568 passed / 1 skipped / 0 failed; ARM64 release and lint vital passed; signed APK uploaded.
