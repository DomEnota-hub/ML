# ML 1.7.213 — agreed changes, stage 1

Baseline delivered QA 1.7.212 e033516e. Archive accepted as ML-local hiding; do not redesign it.

Video source stage: restored bottom 45% in Gallery, horizontal paging allowed when paused and not locked/zoomed, blocked when play intent is active (including buffering). Explicit skip buttons remain usable; timeline independent of playback, paused canvas double-tap no longer seeks. Gallery full-screen follows same ownership, restores ordinary photo viewer when paging to photo. Linear signed drag, maximum 60 seconds per viewport, uses current playback clock for preview and commit. Real gesture/touch/arbitration and decoder behavior need device QA. White existing play/pause/previous/next glyphs keep size/position/hitbox and receive a thin 0.6dp nominal black contour. Verify white, mixed, dark frames and both orientations. Four additional required JVM identities, one prior zone test deliberately replaced. No intermediate Android CI/APK.

Pending music: persistent per-track waveform + acoustic feature cache (identity/staleness), bounded efficient alignment, improve differences classification, compact group/list UI, comparison overview/focus + explicit controls and insets, timing diagnostics. Reuse ML theme, existing features and safe destructive confirmation. Finish all agreed work before single qualification; no version bump or APK yet.
