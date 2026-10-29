# Personal entry and group actions Implementation Plan

> Use superpowers:dispatching-parallel-agents for isolated authentication component work and inline integration; one final independent security/UI review.

**Goal:** Append without unlock, automatic entry authentication, full-group actions and consistent grouping/grid in Personal and folders.
**Architecture:** Separate append authorization from private-read authorization; shared lazy group header/actions and sequential aggregate batch results, reuse existing catalog APIs and private file operations.
**Tech Stack:** Kotlin, Compose, Room, BiometricPrompt/KeyguardManager.
**Spec:** docs/superpowers/specs/2026-10-28-personal-groups-design.md

## Review focus
- Locked append must not authorize private browsing/return/delete/overwrite; data failure cleanup remains retryable.
- Automatic authentication never loops after cancellation/background and doesn't auto-unlock explicit manual lock.
- Group actions use all members including beyond visible limit; catalog filtering applies before grouping.
- Mixed favorite membership is set uniformly, not individually inverted; partial failures are counted, cancellation propagates.
- Private metadata never appears in public folders/favorites; grid actions retain private restrictions and pending-transfer warnings.

## Tasks
- [x] Auth component: test append while locked plus existing private protections; automatic biometric entry with resumed-only once-per-entry behavior, retry after cancel.
- [x] Batch/domain integration: deterministic set favorite, batch counts and private auth wiring; direct append navigation and first setup return.
- [x] Shared group menu/header and public folder/delete actions, including complete hidden members and confirmation.
- [x] Personal grouped list/grid, common catalog actions, safe return/delete groups, pending cleanup.
- [x] Full suite/release build, independent review/fix pass, signed next APK and release notes.

## Review and fix record
- Auth/domain and private UI delegated in isolated files; public/root/catalog integrated inline.
- Fresh review found four Important issues: first setup lost on recreation, manual auto-auth suppression lost on restoration, cancellation during final NonCancellable commit reported success, unfinished/failed group rows counted as deleted files.
- Fix pass: saved setup request + ViewModel-owned PrivateMoveSession; saved manual flag; post-action/final ensureActive; completed-only group deletion guard. Regression failures observed for restoration, final cancellation, active delete; all focused checks green before full run.
- Added locked/busy first-setup regression to ensure restored transfer does not request read access; observed RED before busy placeholder fix.
- Final verification: 598 passed / 1 skipped / 0 failures; ARM64 release + lint vital successful. Gradle final rerun initially OOM-killed by container; idle daemons stopped and rerun succeeded with --no-daemon --max-workers=2, 1536m heap. Signed APK verified and uploaded.
