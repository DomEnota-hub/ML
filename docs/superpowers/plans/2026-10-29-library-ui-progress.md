# Execution ledger — plan: docs/superpowers/plans/2026-10-29-library-ui.md

Spec: docs/superpowers/specs/2026-10-29-library-ui-design.md
Base: 6877374. Workspace: /workspace/medialoader-instagram-profile (existing linked worktree).

## Setup

- Executing inline, tasks 1–10 in order. User authorized implementation on 2026-10-29; pause only on user interruption or a real blocker.
- Ruling: Keep this ledger tracked beside the plan instead of using cloud skill helper scripts, which are not mounted as executable files. Same task/base/test/ruling information is recorded explicitly; this also gives the user a durable continuation artifact.
- Ruling: Moved regenerable `app/build` to `/tmp/medialoader-library-ui-build`, leaving a symlink at the original location, because the workspace filesystem had only 55 MB free. Existing outputs are preserved. `/tmp` is separately mounted with sufficient free space; a replaced runtime may require a fresh build.
- Pre-flight 1→2/4/5/6: canonical LibraryMediaRef key must retain original download ID and phone aliases, so old playlists/routes still resolve. No interface conflict.
- Pre-flight 2→4/6/7: catalog_assignments has a folder FK but no download FK (the earlier plan described that imprecisely); requireCompleted still prevents external references. External links will use separate storage and an adapter as specified, without a Room schema migration.
- Pre-flight 3→4/6/7: screen-local tab navigation replaces root bottom bar only inside new modes; settings and mode changes remain routed by MediaLoaderApp.
- Pre-flight 5/7/8/9: gallery video, service audio and overlay video have different owners. DSP belongs only to AudioPlaybackService; gallery/overlay seeks must not mutate music playback.

## Progress

Baseline: LibraryAudioQueueTest and PlaylistRepositoryTest passed (`/tmp/library-ui-baseline.log`, BUILD SUCCESSFUL).

Task 1: complete. Added canonical references, transitive alias grouping, chronological ordering and privacy filtering; ViewModel resolves provider identities on IO and caches only active URI identities. New modes use unified items; historical phone route IDs remain resolvable internally. RED: missing new merge/reference API (`/tmp/library-ui-task1-red.log`); GREEN: UnifiedMediaLibraryTest, PhoneMediaLibraryTest, LibraryRefreshShieldTest and PrivatePlaybackAliasTest, BUILD SUCCESSFUL (`/tmp/library-ui-task1-green.log`, 2m53s). Base 6877374; implementation committed with this ledger.

Task 2: complete. ExternalMediaCatalogRepository persists favorite/folder links outside downloads; album-linked phone refs appear in APP only after a fresh permitted MediaStore scan. Alias-aware playlist mutation preserves order and unknown members while removing physical-file duplicates. Root callbacks route native download metadata and external links separately. Folder metadata is projected only for still-existing folders. RED `/tmp/library-ui-task2-red.log`: missing repository and alias APIs; GREEN `/tmp/library-ui-task2-green.log`: ExternalMediaCatalogRepositoryTest, PlaylistRepositoryTest, UnifiedMediaLibraryTest, BUILD SUCCESSFUL, 2m44s. Base b5968cc.

Task 3: complete. Compact native A header, source popup without redundant selection reload, search/private/settings top actions, scoped palettes and gallery bottom switcher. New root actions connect real routes and preserve Personal locking.

Task 4: complete. Gallery A four-column grid/date groups, album cards and quick categories, logical folders and device albums, long-press safe file actions, round-corner covers and independent media/album scroll states. Preserved native bucket metadata separately from download group IDs so combined files retain their true device album; same-name buckets use MediaStore bucket IDs. LibraryNameDialog/LibraryStatus extracted unchanged into LibraryDialogs.kt for reuse.

Task 3/4: Ruling: implemented the shared header and gallery layout in one source/test cycle because search/navigation/viewport contracts change together. RED `/tmp/library-ui-task34-red2.log`: missing new actions and gallery model (corrected an unsupported test `.last()` accessor before this run). First GREEN attempt found an overload typo in clickable Surface; second found an obsolete test forbidding phone favorites. Corrected the overload and replaced the obsolete assertion with a real favorite callback check. GREEN `/tmp/library-ui-task34-green3.log`: LibraryHeaderActionsTest, LibraryModeHeaderTest, GalleryLibraryStateTest, LibraryModeScreensTest, LibraryModeShortViewportTest, PhoneMediaLibraryTest, BUILD SUCCESSFUL, 3m3s. Base 7ae3916.

Task 5: complete. GalleryMediaViewer uses one mixed pager, date/time header, bottom actions, sheet/info/folder/delete controls and no arrows/metadata footer. Reused ZoomablePhoto and LocalVideoPlayer; public route tracks the current gallery ID and falls back safely after deletion, while the root shields scans for all phone members of a mixed queue. Video exists only on the settled page and starts paused. RED `/tmp/library-ui-task5-red.log` (missing policy); first policy/privacy run passed. Real native Compose tests then reproduced stale-current notification after deletion (`photo-red`) and a stale key-provider index while growing the pager (`green2`). Fixed current-ID publication and guarded transient out-of-range key/content lookups. Tap test needed the genuine double-tap timeout advanced, confirmed independently in `/tmp/library-ui-task5-photo-tap-diagnostic.log`. Final GREEN `/tmp/library-ui-task5-green3.log`: GalleryViewerStateTest, GalleryViewerPhotoUiTest, GridMediaQueueTest, PrivatePlaybackAliasTest, LibraryRefreshShieldTest, BUILD SUCCESSFUL, 2m10s. Base 9ca41a7.

Task 6: complete. Music A rows/cards, nested playlist header, root bottom switcher, dedicated playlist-add route with common source selector, playable rows, separate +/check selection, preserved saveable selection and one-shot validated confirmation. A ViewModel-owned LibraryAudioPreview controls the existing service and highlights its current public track; leaving the route never stops it. Controller request jobs cancel superseded clicks. Added metadata factory (existing UI wrapper preserved), readiness wait for restored playlist routes, and canonical external-metadata updates to prevent favorite/folder resurrection.

Task 6 evidence: RED `/tmp/library-ui-task6-red.log` missing new APIs; components compilation caught an incorrect generic on Robolectric RuntimeEnvironment.getApplication (fixed). Additional actual RED `/tmp/library-ui-task6-alias-red.log` proved that a playing phone alias restarted under its app ID; preview now compares stored identity keys. GREEN `/tmp/library-ui-task6-green.log`: PlaylistTrackSelectionTest, PlaylistTracksScreenTest (including SavedState restore), AudioQueuePlaybackTest, ExternalMediaCatalogRepositoryTest, PlaylistRepositoryTest, LibraryModeScreensTest, LibraryModeShortViewportTest, LibraryHeaderActionsTest, PrivatePlaybackAliasTest; BUILD SUCCESSFUL, 3m17s. Base 8f53f41.

Task 7: complete. Full variant A music controls and compact mini-player with cover/progress. Full player observes the real session; opening an already-current file/alias preserves pause, position and queue. Root persists the current audio ID; listener callbacks and current item state remain valid when the route's item changes. Queue sheet uses session items, hides private metadata/blocked aliases, and supports selecting a session member. Legacy viewer stays supported; waveform/embedded-artwork effects do not run for the modern layout. The equalizer icon remains disabled until Task 8 wires the actual route.

Task 7 evidence: RED `/tmp/library-ui-task7-red.log` missing controls/open API; additional RED `/tmp/library-ui-task7-metadata-red.log` missing safe session metadata resolver. GREEN `/tmp/library-ui-task7-green.log`: AudioQueuePlaybackTest, LibraryAudioQueueTest, MusicPlayerControlsTest, PrivatePlaybackAliasTest, PlaylistTracksScreenTest; BUILD SUCCESSFUL, 2m59s. Base 7455131.

## User pause: 2026-10-29, resume requested 12:00 Europe/Moscow

User explicitly stopped implementation to wait for quota refresh. Tasks 1–7 are implemented and their targeted gates passed. Resume at Task 8; verify this ledger against Git before doing anything. No Gradle process remained active at pause.

Additional request: ONLY AFTER all agreed UI/equalizer/video changes and verification, investigate unreliable automatic downloading using `.local-inputs/MediaLoader_1.7.95_logs.md` (original attachment path recorded in HANDOFF). Do not open or analyze the log before finishing the agreed changes. The log has not been read during this session. Diagnose and report evidence; do not claim it fixed without proof.

Task 9 integration follow-up: gallery inline video currently bypasses its old all-direction drag handler so horizontal swipes can page. Restore vertical edge volume/brightness via a vertical-only detector in this mode; fullscreen keeps the existing gesture handler. Do not ship with missing vertical edge adjustment in gallery inline playback.

Resolved integration follow-up: ExternalMediaCatalogRepositoryTest.removalThroughDownloadAliasCannotResurrectOldExternalFavoriteOrFolder verifies clearing external metadata through an app alias; native mutation callbacks update both stores. Stored links remain validated through live permitted MediaStore results.

## Continuation

Final review: one fresh read-only reviewer examined 6877374..38e9b23; report and complete declined-to-judge rulings in `2026-10-29-library-ui-review.md`. Full pre-fix gate `/tmp/library-ui-task10-full.log` BUILD SUCCESSFUL, 5m18s, 686 tests / 0 failures / 1 disabled live Shazam test, unsigned arm64 release assembled. Accepted two Important findings and promoted the ID-based video resume loss from Minor to Important because it violates shared-file progress. No deferred minor findings.

Final: Ruling: use the composition-preserving validation shield for the entire library content through publication of a matching scan revision, and hide its outside mini-player meanwhile, instead of deleting retained editor rows. This covers APP-linked media, playlists and the picker uniformly without losing drafts/selections. Cost: a phone refresh can briefly hide app-owned content too.

Final: Ruling: retain alternate identities inside playlist membership and durable video preference links, independently of download history. No files are copied or fake download records created. Existing video bookmarks prefer the latest recorded identity; legacy bookmarks without timestamps prefer the greatest existing progress. Cost: ambiguous old bookmarks can resume farther ahead, since historical ordering was never stored.

Final fix pass evidence so far: `/tmp/library-ui-review-red.log` missing validation/link APIs; `/tmp/library-ui-review-red2.log` additionally reproduced the actual playlist history-clear assertion failure while video/validation/shield checks passed. GREEN `/tmp/library-ui-review-green.log`: PlaylistRepositoryTest, VideoWatchProgressTest, LibraryValidationGateTest, LibraryRefreshShieldTest, PlaylistTracksScreenTest, GalleryViewerPhotoUiTest, PrivatePlaybackAliasTest; BUILD SUCCESSFUL, 3m17s. Legacy-schema regression added for the final full gate. No second reviewer; final suite/release rerun pending. Diagnostic log remains unread.

Resumed by user and scheduled task on 2026-10-29; checkpoint 5834e69 clean. Tasks 1–7 retained. Runtime connected and filesystem/toolchain verified. `adb devices` has no targets. Diagnostic attachment still unread.

Task 8: Ruling: music always uses the PCM sink, including disabled/flat EQ, and advertises neither compressed passthrough nor hardware offload. This is necessary for live enabling/disabling without rebuilding the renderer, interrupting playback or losing queue/position. Video has separate renderers. Cost: music may consume more battery than a hardware-offloaded path; unchanged PCM has a direct byte-copy bypass.

Task 8: Ruling: reserve headroom equal to the sum of positive band gains, with a final finite/clamp guard, and smooth gain changes over approximately 20 ms. This prioritizes no integer overflow/clipping over unchanged loudness. Cost: boosted presets can sound quieter overall; no volume above 100% or extra effects are introduced.

Resume the first task without a `complete` line. Read plan, spec, ledger and `git status`; do not redo committed tasks. No release/version changes or Drive uploads are authorized by this implementation step.

Task 8: complete. Shared persisted EQ, five peaking filters with stereo/PCM16/float correctness, sample-rate-aware bands and native A screen; real AudioPlaybackService chain, no queue/session replacement. UI writes are immediate and conflated on IO; errors remain visible. RED /tmp/library-ui-task8-red.log: missing processor/settings APIs. GREEN /tmp/library-ui-task8-final.log: Equalizer*Test, MusicPlayerControlsTest, AudioQueuePlaybackTest, PrivatePlaybackAliasTest, BUILD SUCCESSFUL, 3m21s. Additional lifecycle gate /tmp/library-ui-task8-reset.log passed (reset before any configured track). Presets/bypass/reset/unavailable-band controls tested natively. No attached adb device: audible transitions, Bluetooth/wired output and battery behavior remain device QA items. Base 5834e69.

Task 9: complete. Overlay bottom time bar supports tap/drag seek, preserves play intent, blocks window movement during scrub, clamps coordinates and disables seeking without duration/seekable timeline. Native View test verifies parent receives no scrub touch, timer cannot overwrite dragged target and detach stops player polling. Close/expand controls remain outside the 32dp seek target. Gallery-inline vertical-only volume/brightness gestures restored while horizontal paging remains independent; fullscreen retains original detector. Native Media3 timeline lowered via resource because PlayerControlViewLayoutManager reapplies its resource margin on orientation/size changes (verified cached bytecode). RED /tmp/library-ui-task9-red.log: missing seek APIs. GREEN /tmp/library-ui-task9-final.log: FloatingVideoSeek*Test, FloatingVideoGesturePolicyTest, VideoGesturePolicyTest, GalleryViewerPhotoUiTest; BUILD SUCCESSFUL, 3m23s. Base f6f3bbd. Overlay-to-fullscreen real-device behavior remains device QA (no adb target).

Task 10: native visual pass completed in Robolectric at 390dp width / 740dp content height, both themes. Twenty real Compose/View tree captures (gallery, albums, album, viewer, tracks, playlists, playlist detail, add, player, EQ) plus contact sheets are retained in references/2026-10-29-library-ui/native-a. Fixture data and cached covers are offline test-only, not product content. Captures use software Canvas because PixelCopy's redraw callback is unavailable in Robolectric (/tmp/library-ui-task10-capture2.log); no emulator/device rendering claim. Compared native contact sheets to HTML A; fixed album header/dock and matched EQ title hierarchy. Active service mini-player and audible/system flows remain device QA.

Task 10 integration: gallery routes now resolve only through current validated refs and reapply fresh private-key masking; raw history cannot reintroduce an alias while private movement is propagating. Index has an explicit readiness state so cold-restored gallery routes wait for IO validation. Alias persistence errors no longer kill the media index; audio connection failure is handled without crashing or navigating to a dead player. Legacy photo queue dedupes alias copies by saved URI. RED /tmp/library-ui-task10-red.log: missing candidate API/detail callback (also corrected a test import and IMAGE enum typo). GREEN /tmp/library-ui-task10-green.log: native capture, GalleryAlbumNavigationTest (including saved-state restore), GalleryViewerStateTest, LibraryModeScreensTest, EqualizerScreenTest, GalleryViewerPhotoUiTest and PrivatePlaybackAliasTest; BUILD SUCCESSFUL, 3m28s. Full suite/release gate and final review still pending.

Task 10: complete. Final post-review full gate `/tmp/library-ui-final-full.log`: BUILD SUCCESSFUL in 8m13s, 83 tasks (19 executed, 64 up-to-date). 691 tests, 0 failures/errors, 1 intentionally disabled live Shazam fixture (690 passing). Unsigned arm64 release assembled successfully. `git diff --check` clean. Version remains 1.7.95 (157); no publish/upload/push/merge. No device available; the documented hardware/audio validation limits remain.

Final: fixed playlist loss after history cleanup — PlaylistRepositoryTest.historyOnlyCleanupKeepsMergedPhoneTrackPlayableAfterRestart actual assertion RED→GREEN; legacy-schema compatibility also passes.
Final: fixed stale phone metadata during refresh — LibraryValidationGateTest plus native LibraryRefreshShieldTest RED→GREEN; all library routes and outside mini-player use the generation-aware gate, keeping editor composition.
Final: fixed alias video resume loss — VideoWatchProgressTest covers phone→app→phone and app→phone-only, shared watch marks and completion; missing APIs RED→GREEN.
Final review pass complete, all accepted findings addressed in one pass, full suite green; no minor items deferred. Existing worktree/branch retained per user instruction not to publish a version.

Next task: diagnostic investigation of Automatic mode. The attachment has remained unread through all feature work and gates; it may now be opened as explicitly requested.

Additional diagnostic: complete AFTER all feature gates. See `2026-10-29-automatic-share-diagnosis.md`. Supplied 60-line log has one successful automatic share (08:58–08:59) and two later MainActivity onNewIntent/manual-UI paths (09:05, 09:08); all three queued files completed. Found entry-point-dependent automatic-mode check: ShareActivity checks the preference, MainActivity never does. Direct explicit sends can bypass automation. Log omits per-attempt mode/receiver/fallback reason, so pinned Android targets versus receiver manual fallback cannot be proven uniquely. Thumbnail HTTP403 is unrelated to download transfer. Diagnosis/report only as requested; no speculative automatic-download patch or release. Raw log remains ignored and uncommitted.


## Release 1.7.96 — automatic share fix completed

User authorized the automatic-download fix and release, requesting minimal checks.
This supersedes the earlier diagnosis-only/no-publication state. Shared routing now
covers cold MainActivity ACTION_SEND and warm onNewIntent; existing receiver and
worker retain persistence-before-return and deduplication. Manual routes unchanged.
Nine targeted tests passed; arm64 release build passed (7m32s). Signed APK certificate
matches 1.7.95. Full suite was not repeated; earlier feature gates remain complete.
Release notes and artifact checksum: `docs/RELEASE_1.7.96.md`.
Google Drive upload succeeded on retry and metadata confirms 103597119 bytes:
https://drive.google.com/file/d/14ekw1UMkCm84B4IUrkw_4Pb66N5dcQPM/view?usp=drivesdk
No raw diagnostic data or signing secrets committed. Device/OEM runtime testing
remains for installation; no claim of physical-device verification.


## Gallery hotfix 1.7.97 (159)

Main-mode HTML discussion is paused for the reported Gallery loading trap and
bottom-switcher overlap with Android navigation. Changes and bounded evidence:
`2026-10-29-gallery-loading-hotfix.md`. All 13 targeted checks passed; release arm64
build passed in 4m8s. Signed APK matches 1.7.96 certificate. Physical-device timing
and insets remain installation checks; do not claim an observed device reproduction.
Release notes: `docs/RELEASE_1.7.97.md`. Drive APK: https://drive.google.com/file/d/1e4PylW03H4KPw60Mbkr8nT0wotYwiXaj/view?usp=drivesdk
Do not repeat completed UI stages/full suite for this hotfix. No raw logs or secrets
are part of the commit. HTML work remains paused until user resumes it.


## Six feedback fixes complete, awaiting release instruction

See `2026-10-29-library-feedback-fixes.md`: safe entire bottom-stack inset, full
mini-player controls/progress, cached background scanning with access validation,
playlist icon, Settings return via existing Home entry, in-place editable queue.
Twelve scoped tests passed; release build passed in 8m. No full-suite, delegated
review, or screenshot run, per user's explicit check budget. Current source version
remains 1.7.97; do not distribute new unsigned output as the prior published release.
Next release should bump to 1.7.98/code160 and sign. Do not repeat finished tests for
a version-only change. HTML main-mode redesign remains paused.


## Release 1.7.98 published on user instruction

Version 1.7.98/code160. Version-only change after the verified six-point fix checkpoint
4fcf897. No repeated tests. Arm64 release build passed in 4m11s; signature matches
1.7.97. APK size103621695; checksum and URL in docs/RELEASE_1.7.98.md.
Drive: https://drive.google.com/file/d/1T4YyIuIC3A1LVIRaAGEqhbJc9oDkQOxx/view?usp=drivesdk
This supersedes the earlier awaiting-release state. Signed APK is under
/workspace/releases/MediaLoader_1.7.98/. HTML work remains paused.


## Persistent phone catalog implementation complete

See `2026-10-29-persistent-phone-catalog.md`. Added AtomicFile metadata snapshots in
noBackupFilesDir, access/private/MediaStore-version validation, generation skip,
row fingerprint reuse, and cold restore followed by background reconciliation.
Fifteen distinct targeted tests passed including actual cold ViewModel restoration
while provider scan is deliberately blocked. Release build passed in7m50s. Do not
repeat tests or original UI work. Source version1.7.98 unchanged; next release is
1.7.99/code161 after instruction. User confirmed full photo/video access, eligible for persistence;
selected-photo access remains conservatively validated. HTML remains paused.


## Transparent navigation and unified mini-player complete

See `2026-10-29-transparent-navigation.md`. Shared window contrast opt-out and
transparent navigation/divider, edge-to-edge main/auth/fullscreen exit, separate
Compose dialog/sheet windows, and app-theme icon restoration after video. Downloader
now uses the same artwork mini-player renderer/palette as Gallery/Music; old glyph
renderer removed. Focused release tests2 passed; BUILD SUCCESSFUL in 5m 24s.
Previous persistent-catalog checks remain complete (15 distinct cases). No broad
suite or screenshot run. Source version remains1.7.98/code160; signed public1.7.98
unchanged. Next release1.7.99/code161 should include BOTH persistent catalog and
transparent-navigation/unified-mini-player changes. Wait for release instruction;
version-only release does not require repeating completed tests. HTML remains paused.


## Downloader audio play fix complete

User clarified that the track page opens but Play does not start playback. Report:
`2026-10-29-downloader-audio-open.md`. Common public service-first selection/modern
player; prepare idle/failed timeline before play; live session metadata resolves raw
phone IDs in Downloader; safe playback error-code logging. Private guards retained.
Fourteen targeted release tests passed; BUILD SUCCESSFUL in5m23s. Do not repeat prior
catalog/window checks. Prepared, not published: next1.7.99/code161 should include all
three unpublished changes. Source/public version remains1.7.98. Actual phone playback
needs confirmation after update; raw attachment log stays outside Git.


## Release1.7.99/code161 completed

User authorized build/release. Version-only change after verified checkpoint64604f2.
No repeated tests. Arm64 release assembled successfully in3m57s; signed certificate
matches1.7.98. APK size103633983. Notes/checksum:docs/RELEASE_1.7.99.md.
Drive upload completed: https://drive.google.com/file/d/18se3ra7DhPPEfXqYlScSQgt_s1WHLROm/view?usp=drivesdk
This supersedes previous prepared/awaiting-release notes. Includes persistent phone
catalog, transparent system navigation/unified mini-player, and Downloader audio
preparation/session resolution. Raw diagnostic files and signing secrets not committed.
First full catalog scan is expected to seed cache. User has full photo/video access;
subsequent cold starts restore cache when its access/privacy/database tags match.
HTML main-mode work remains paused. Await phone feedback; do not rerun finished gates.
