# Persistent Phone Catalog Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans inline in the existing linked worktree.

**Goal:** Restore previously validated phone media after process death without a blocking rescan; reconcile changes in the background.
**Architecture:** Atomic per-mode metadata snapshots in noBackupFilesDir; gate restoration on permission signature, MediaStore database versions and private-identity digest. Use MediaStore generation stamps to skip unchanged scans and row fingerprints to reuse physical identities during a metadata reconciliation, including deletions.
**Tech Stack:** Kotlin/coroutines, AtomicFile/JSON, existing MediaStore queries and unified identity index. No new dependencies or Room migration.
**Spec:** User-approved proposal in this conversation: one full check, persisted known list on later launches, background additions/updates/removals; privacy checked before publication.

## Global Constraints

- Preserve private identity and permission gates before displaying disk snapshots.
- Partial selected-photo access is never restored without validating current selection.
- No media copying, thumbnails embedded in catalog, background service, or periodic worker.
- User explicitly requests minimal checks; bounded cache/scanner/privacy tests and release build only. No full suite or delegated review.
- No release upload until instructed. Existing signed 1.7.98 remains unchanged.

## Review Focus

- Process restart restores typed metadata/physical aliases: cache round-trip test.
- Revoked permission/changed private identities/MediaStore reset: reject-cache test.
- Corrupt snapshot: cache miss, not crash; test.
- Unchanged/changed/deleted rows: incremental scan test.
- Cache disk I/O / private-key startup: all disk work on IO, await private keys and recheck access before publication; manual source check.

### Task 1: Snapshot store and scan reuse

Files: new data/library/PhoneLibraryCache.kt; modify PhoneMediaLibrary.kt; tests PhoneLibraryCacheTest.kt and PhoneMediaLibraryTest.kt.
Interfaces: PhoneStoreStamp(versions,generation); PhoneMediaSnapshot(items,identities,fingerprints); cache read/write with mode/access/private tags; scanSnapshot(mode,previous).
- [x] Write focused restart/rejection/corruption and row-reconciliation tests.
- [x] Implement atomic metadata store, database/generation stamps, row identity reuse.
- [x] Verify via one bounded command after integration (user's explicit check budget).

### Task 2: Restore then reconcile

Files: ui/LibraryViewModel.kt, existing validation-gate tests.
- [x] Await current private identities; read disk snapshot before deciding a full scan is required.
- [x] Restore only validated matching snapshots, keep UI interactive during disk read/background work.
- [x] Skip scanning if generations match; otherwise metadata-reconcile and persist atomically.
- [x] Invalidate on private-key changes; retain last valid list after transient background failure.
- [x] Preserve MediaStore observer debounce, coalesce events arriving during scans.

### Task 3: Bounded verification and checkpoint

- [x] Run PhoneLibraryCacheTest, PhoneMediaLibraryTest, LibraryValidationGateTest plus assembleRelease, offline/one worker.
- [x] Record actual counts/build result and remaining phone checks; commit source/docs without diagnostics/secrets.

Ruling: execute directly after the user's explicit approval, without another design/plan confirmation. Single combined verification instead of repeated per-task full suites. On Android where database versions cannot be established, do not trust a disk snapshot; fall back to validation rather than risk reused IDs exposing private metadata.


## Completed evidence

Initial bounded run `/tmp/persistent-phone-catalog-checks.log`: 14 targeted tests
(PhoneLibraryCacheTest6 + PhoneMediaLibraryTest6 + LibraryValidationGateTest2) passed;
release arm64 assembly: BUILD SUCCESSFUL in 7m50s. The cold-start integration case was
added after that test task had taken its source snapshot, so only that single method
was run subsequently: `/tmp/persistent-phone-catalog-cold-start.log`, BUILD SUCCESSFUL
in 3m33s, 1 test, zero failures/errors. Total15 distinct passing cases. No broad suite,
visual run, or additional release assembly after test-only changes.

Cold ViewModel test uses a fresh model, disk snapshot, two coalesced entry/resume
refresh calls, and a deliberately blocked provider query. Cached refs publish and
the privacy gate remains open before releasing the provider; afterward ID42 is
removed and ID43 appears. The OS version/generation is controlled by a test shadow;
production cache/scanner/ViewModel/Room/private-key/alias paths run normally.

Implementation includes safe fallback for corrupt/unavailable cache, matching
permission/private/db-version tags before publication, private-key invalidation,
post-IO access checks, unchanged-generation skip, unchanged-row alias reuse, deleted
row removal, and retained display on transient background errors. Events arriving
during a scan wait for it before reconciling again.

Actual phone diagnostic header says Android16/API36. No private log data is committed.
User confirmed full access to all photos/videos. Their mode uses persistent
restoration. Selected access still takes the conservative validation path. Older platforms/providers without a reliable database version also validate
instead of trusting persisted IDs. A first full scan is needed to create the catalog.

Current source version stays1.7.98/code160; signed/public1.7.98 remains unchanged.
New code is prepared, not published. Next release should be1.7.99/code161; no repeat
of these completed tests is needed for a version-only change. HTML remains paused.
