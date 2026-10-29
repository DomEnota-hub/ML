# Grid viewer and player controls implementation

Spec: ../specs/2026-10-28-grid-viewer-controls.md

1. Player gestures: extend VideoGesturePolicy with origin Y/full viewport height; change outer strips to 15%; remove quarter-range cap. Regression tests cover upper/lower half, strip boundaries and full level range. Wire full viewport measurements into MediaViewerScreen; preserve native timeline touch handling.
2. Grid sequence: add onOpenGrid(id, orderedIds) callbacks in DownloadsScreen/PrivateScreen. Capture snapshot in MediaLoaderApp with saved IDs, filter real entities and privacy on viewer entry. MediaViewerScreen selects photo/video by saved current ID; explicit previous/next buttons, photo paging remains unchanged outside grid queues. Tests cover ordered cross-group queue and privacy exclusions, screen grid callback.
3. Menu icons/locks: shared lock button for current-state image without background, modern rounded icons on all DownloadRow actions. UI checks for lock action and file menu callbacks.
4. Verify: full testDebugUnitTest and arm64 assembleRelease, review full diff with one fresh reviewer. No publish/version bump requested.

Review focus: public/private isolation; mixed photo/video player disposal; existing photo pager and audio queue unchanged; viewport size versus chrome overlay size; native seek bar remains usable; grid scroll survives back navigation.

## Completion

- Implemented all six approved items; no new application modes, version bump or publication.
- Decisions: previous/next buttons avoid conflict with video seeking; edge strips are 15% each; the ordered queue snapshot lives in each viewer navigation entry so editor outputs do not overwrite earlier queues.
- Fresh review found one Important issue: consecutive videos reused a native PlayerView with a stale reference/listener. Video content is now keyed by selected ID. The test drives real native show/hide events and verifies Compose control visibility on the second video. A screen-coordinate touch simulation proved unsuitable for Robolectric and was replaced; physical touch behavior still requires device verification.
- Full suite: 607 tests, 606 passed, 1 skipped, 0 failures/errors. Log: /tmp/grid-controls-verified.log; XML: app/build/test-results/testDebugUnitTest/.
- ARM64 assembleRelease and lintVitalRelease passed. Log: /tmp/grid-controls-release-resume.log. The environment restarted after compilation; assembly resumed using existing outputs. Unsigned APK: app/build/outputs/apk/release/app-release-unsigned.apk (103230890 bytes).
- Build configuration for a cold compile of both variants: --max-workers=1 -Dorg.gradle.jvmargs=-Xmx3072m -Pkotlin.compiler.execution.strategy=in-process. Running both compilers concurrently in 1.5GiB caused heap pressure; this is an execution setting, not a project change.
- No deferred review findings. No device/emulator was available.
