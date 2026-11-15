# ML 1.7.191 stage 2 — music

Authorized 2026-11-15 after stage 1 acceptance. QA-190-02/03/05/06. Base `0ee4650a8ffdad05f41865b6acf552d1dbd0fe01`.

- Music opens overview/top on ordinary entry or mode shortcut, separate list states; returning from details retains parent list. Russian track plurals.
- Recent music reuses the complete privacy-filtered collection catalogue, current source and folder/hidden rules. All dates, no count/age cap, latest nonempty day by default, unknown dates explicit. Calendar cells maintain weekday alignment; empty days disabled. Day preview and full lazy list, explicit play/shuffle, existing row menus and queue callbacks. No new scan or permanent playlist.
- Duplicate search explicit mode selector and start/resume; a different mode requires confirmation before replacing unfinished checkpoint. Status cards distinguish pause/stop/interruption/actual failure. Empty completed result only after actual completion. Existing service, acoustic matching and cache remain. Journal clears completed reports with optional partial removal and rejects stale debounced writes, separate visual/trash reports untouched.
- Link preview recognition uses five themed animated bars, concise text, cancellation unchanged; decorative only, no audio access.
- Tests and exact-head CI/lint/ARM64 required before technical acceptance. Physical Xiaomi QA and large real catalogue performance remain pending. No intermediate signed APK, no production merge.

Follow-up: history playback now uses the complete catalogue and the same fresh URI/privacy validation as collections. Current queue is only changed by explicit user action; partial index generations do not silently truncate a requested selection. Added UI interaction tests for calendar and duplicate mode, fixed stale journal generation after privacy invalidation and completed-state cleanup after process restart.
