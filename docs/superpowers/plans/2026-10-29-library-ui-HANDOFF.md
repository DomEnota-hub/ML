# MediaLoader — current continuation state (2026-10-29)

Repository `/workspace/medialoader-instagram-profile`, linked worktree, branch `fix/instagram-session-1.7.37`. Check Git and the tracked ledger first; do not redo finished stages.

## Agreed feature implementation finished

Tasks 1–10 of `2026-10-29-library-ui.md` are complete. Spec remains `../specs/2026-10-29-library-ui-design.md`; approved HTML A and native dark/light captures are under `../references/2026-10-29-library-ui/`.

Commits before final fixes: b5968cc, 7ae3916, 9ca41a7, 8f53f41, 7455131, 5834e69, f6f3bbd, 15f2ede, 38e9b23. Latest commit contains final reviewer fixes; inspect Git for hash. One fresh read-only whole-branch review completed; three accepted findings fixed, no deferred minors. Report: `2026-10-29-library-ui-review.md`; details/rulings: `2026-10-29-library-ui-progress.md`.

Final exact gate `/tmp/library-ui-final-full.log`: BUILD SUCCESSFUL in 8m13s; 691 tests, 0 failures/errors, 1 intentionally disabled live Shazam fixture (690 passing), arm64 unsigned release assembled. No adb target: actual sound quality, Bluetooth/wired changes, OEM overlays/system insets and complete device/large-font coverage remain device QA. Native software renders do not establish handset/hardware behavior.

Version stays 1.7.95 (157). No release published, APK uploaded, branch pushed/merged. Keep this worktree. User must separately request publication.

## Additional task completed — Automatic mode diagnostic

User explicitly requested investigating unreliable automatic downloading only AFTER all agreed UI/EQ/video changes and checks. That ordering has been respected: log was never read during feature work. Feature gates now complete, so analysis may start.

Private ignored input: `.local-inputs/MediaLoader_1.7.95_logs.md`.
Original: `/tmp/codex-remote-attachments/01a13eca-64c7-74fb-aa65-a3742e46604d/e1e5636e-7b2a-4726-a489-1ccfdb3967fb/1-MediaLoader_1.7.95_logs.md`.

Report complete: `2026-10-29-automatic-share-diagnosis.md`. Log proves one successful automatic worker and two later MainActivity/manual UI paths; all three queued downloads completed. Confirmed code hole: only ShareActivity checks Automatic setting; a direct MainActivity ACTION_SEND bypasses it. Exact origin of the two MainActivity intents (direct cached target vs receiver fallback) cannot be established because routing/preference fields are absent. CDN403 belongs to a thumbnail. No automatic-download fix claimed or implemented: user asked for diagnosis; concrete minimal fix/test plan is in report. Raw logs/session data stay ignored and uncommitted.

Implementation and diagnostic tasks are done. UI/fix checkpoint ef329a4. Keep current worktree and do not rerun finished work without a new change/risk. Await user's next task; no release publication/upload authorized.

## Runtime

JDK `/workspace/medialoader-1.6.14-toolchain/jdk-17`, SDK `/workspace/medialoader-1.6.14-toolchain/android-sdk`. One Gradle at a time, offline, workers1, Xmx3072m, Kotlin in-process. Command in plan/ledger. `app/build` symlinks to `/tmp/medialoader-library-ui-build` due workspace space; about 1GB workspace and several GB /tmp free. If runtime replaced, recreate missing regenerable build storage. Do not remove signed prior releases or signing files. Current unsigned rebuilt APK is `app/build/outputs/apk/release/app-release-unsigned.apk`, NOT the previously published 1.7.95 binary.


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
