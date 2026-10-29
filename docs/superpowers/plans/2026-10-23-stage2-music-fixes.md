# MediaLoader 1.7.17 Stage 2 Music Fixes Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans inline. Work in the local `work/1.7.17-stage1` branch; never contact GitHub.

**Goal:** Keep the HitMoz action position stable, refresh confirmed audio artwork in history, and prevent repeated failed Instagram thumbnail requests.

**Architecture:** Keep existing music and download flows. Add a focused cache invalidation path from successful MP3 replacement to Compose thumbnails, and a bounded per-URL retry policy around existing remote image loading.

**Tech Stack:** Kotlin, Jetpack Compose, Room, Android MediaMetadataRetriever, JUnit, Gradle 8.9.

**Spec:** `MediaLoader_1.7.17_Codex_TZ_2026-10-23.md`, stage 2.

## Global Constraints

- Preserve `applicationId` and signing identity; do not migrate or clear user data.
- Do not change other downloaders or UI outside the specified music/thumb areas.
- Keep secrets and full signed image URLs out of logs and backups.
- Commit locally, run focused tests, and back up source plus full Git history after the stage.

## Review Focus

- A search result moves from idle to running while its title is long: action center must stay fixed.
- Shazam confirms a different song but saved URI stays the same: history must show the new embedded art.
- Artwork download fails while tags succeed: history must use confirmed artwork URL before publication art.
- Multiple visible rows share a failing Instagram URL: one concurrent network attempt should serve them.
- URL or cooldown changes: a valid image must be fetchable again.

---

### Task 1: HitMoz action layout

**Files:** Modify `ui/components/HitMozSearchDialog.kt` (`NativeMusicTrackRow`).

- [ ] Confirm width changes from 24 dp / intrinsic column to 48 dp across states.
- [ ] Wrap every action state in one 48 × 48 dp Box; center 24 dp progress/icon and move preparation text outside the action width or omit it.
- [ ] Compile debug Kotlin and review all state branches.
- [ ] Commit the focused layout change.

### Task 2: Confirmed recognition artwork

**Files:** Modify `ui/model/UiMappers.kt`, `ui/components/DownloadThumbnail.kt`, `data/recognition/MusicPostProcessor.kt`, `data/storage/StorageManager.kt`; create `core/storage/AudioThumbnailInvalidation.kt` and focused tests.

- [ ] Write failing tests: confirmed audio chooses recognition URL as fallback; invalidating unchanged URI deletes stale cache and increments revision.
- [ ] Run focused tests and observe failures.
- [ ] Implement cache invalidation after successful MP3 replacement and DB save, including unchanged URI and rename cases.
- [ ] Make Compose wait for local decode before remote fallback and reload on invalidation.
- [ ] Run focused tests, compile and commit.

### Task 3: Bounded remote thumbnail failures

**Files:** Modify `data/preview/RemoteImageLoader.kt`; create `data/preview/ThumbnailRetryPolicy.kt` and focused tests.

- [ ] Write failing tests for 403 cooldown, temporary 429/5xx, network errors, and a changed URL.
- [ ] Run focused tests and observe failures.
- [ ] Add per-URL singleflight, longer 403 negative caching, bounded temporary retry delays, and safe host-only diagnostics.
- [ ] Run focused tests and commit.

### Stage gate

- [ ] Run all unit tests, Android lint, native JS parser scripts, and ARM64 diagnostic build once.
- [ ] Write stage report and create/verify local + Drive source/Git backup.
- [ ] Continue to stage 3 under the user's standing authorization.
