# ML 1.7.188: music statistics and saved queues

Implemented scope: QA-187-02/11/12/13 and the QA-187-08 smart-mix slider integration. QA-187-02 implementation was separately authorized by the user after the initial diagnosis.

## Statistics evidence and behavior

`ListeningHistoryPolicy.threshold` credits one listening event after actual media-position progress reaches `min(30 seconds, duration / 2)` for known durations, or 30 seconds for unknown durations. Opening a file does not credit a listening event. The current history stores accumulated wall time and a session's `last_at`, without timestamped time slices. Therefore wall time remains attributed to that session's last recorded date; hourly detail would invent unavailable data and is deliberately absent.

Selected calendar intervals use `[start, end)` in the app/system calendar zone. Overview buttons retain their existing rolling semantics (7 days, 30 days, 12 calendar months); All time includes the current year even with no activity. Tapping a year selects all its calendar months, and tapping a month selects its actual calendar days, including zero-value buckets. Back traverses the selection stack. Cards, artists, tracks, chart and image export use the same selection. Time-only artists previously appeared in the top while being excluded from the artist card count; cards and tops now consistently include public rows with a credited event or positive real wall time. This does not change the credit threshold.

The chart positions at its latest bucket after its matching interval is loaded and measured. Metric changes and subsequent background snapshots retain manual horizontal scrolling. Earliest All-time dates are derived only from resolved public candidates, preventing unavailable/private history from influencing the displayed date range.

## Saved queues

Saved sessions retain their eight-session limit. Queue entries have persisted occurrence IDs; old v1 JSON without IDs remains readable. Repeated tracks resolve and resume as separate occurrences. Removing occurrences preserves the selected occurrence and position when retained; deleting the selected occurrence chooses the next available one, then the previous available one, and resets position. Empty edited queues are removed. Order, playlist provenance, speed, shuffle and repeat are retained.

Atomic store edits, session deletion and clearing all sessions invalidate the recorder's old revision lease. Existing active playback continues unchanged; its old checkpoints and completion events cannot overwrite, recreate or delete an edited saved snapshot. Explicitly resuming a changed queue starts recording its new composition. No file deletion or player mutation is used by saved editing.

## QA-187-02 diagnosis and authorized implementation

Source evidence:

- `AudioPlaybackService.kt` creates `MediaSession.Builder(this, player).build()` without `setSessionActivity`.
- No playback notification `PendingIntent` is supplied by this service.
- `MainActivity.kt` handles mode shortcuts and shared text during cold start / `onNewIntent`, but has no current-player intent action.
- The manifest already uses `singleTask`; that alone cannot select the current full-screen player.
- `MediaLoaderApp.kt` has the existing `MEDIA_VIEWER_ROUTE` and observes `LibraryAudioPreview`, which connects to the existing service controller.

Implemented an explicit immutable/update-current activity `PendingIntent` via `MediaSession.Builder.setSessionActivity`. The request contains only its action, with no title, URI, track ID or queue extras. `MainActivity` accepts both cold-start and `onNewIntent` requests, clears the incoming action immediately, and retains pending generation state across recreation. Generation-aware consumption prevents an older navigation result from clearing a newer notification tap.

`LibraryAudioPreview.observeCurrent` only attaches to the existing service controller. Its state distinguishes controller readiness and exposes the actual current `MediaItem` (rather than finding the first matching media ID in a queue that may contain repeated occurrences). Private state exposes neither current item nor queue. `CurrentPlayerNavigation` waits for controller/catalog/privacy readiness, resolves only permitted public rows by real physical URI identity, and rejects missing/private/blocked/mismatched entries. Root-owned app integration navigates directly to the existing viewer and consumes the matching request without calling queue-opening or resume actions.

Notification viewer entries carry `current_audio_session_only`; `MediaViewerScreen` forwards it to `BackgroundAudioPlayer`. This player entry only synchronizes the service state and returns before all queue initialization paths, including a race where the queue becomes empty before the viewer controller attaches. It does not replace, seek, prepare or start playback on entry. Index, position, shuffle/repeat, speed and stop timer remain owned by the service. Existing public/privacy presentation remains in force.

Required validation: request/navigation regressions; background/other-screen/cold-UI entry; playback continuity and index/position/shuffle/repeat/timer preservation. HyperOS system card, normal notification and lockscreen behavior require actual device acceptance and cannot be guaranteed by JVM tests.

## Regression coverage added (not executed by this agent)

Calendar year/month/day boundaries, leap year, DST, empty current year; selected-month half-open SQL bounds, chart/card agreement and unavailable-history isolation; zero-bucket click and chart scroll retention; partial-listening artist/card consistency; duplicate occurrences, current-index fallback, position retention, legacy JSON read, edit/restart, stale-checkpoint/finish rejection, active player independence and duplicate resume playback.


QA-187-02 regression additions (not executed by this agent): explicit immutable pending intent without media extras; cold-start pending state and recreation; warm intent generations and stale-consumption rejection; delayed controller/catalog/privacy readiness; actual current-item selection; unknown/private/blocked rows; exact-ID physical-URI mismatch rejection. End-to-end HyperOS notification/system-card/lockscreen acceptance remains manual.
