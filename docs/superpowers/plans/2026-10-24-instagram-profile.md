# Instagram Profile Search Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add VK-style Instagram profile search for selectable photos and videos, with optional Reels, dates, favorites, journal and recovery.

**Architecture:** A profile API enumerates post summaries with pagination. A separate SQLite store checkpoints searches and selections. WorkManager scans and downloads while Compose shows results and controls. The existing Instagram provider resolves each chosen post again before download.

**Tech Stack:** Android/Kotlin, org.json, SQLiteOpenHelper, WorkManager, Jetpack Compose, existing Instagram session and media repository.

**Spec:** `docs/superpowers/specs/2026-10-24-instagram-profile.md`

## Global Constraints

- Keep existing VK storage and UI behavior intact.
- Use only saved Instagram session; no credential collection or new API key.
- Search max 300 posts by default when dates are absent; preserve cursor for Continue.
- Do not include Stories, Highlights, music assets, or ad logic.

## Review Focus

- Invalid or non-profile URL must be rejected without starting a job.
- Duplicate post in feed and Reels must appear once.
- Rate limit or changed response schema must preserve partial results and allow retry.
- Already-downloaded file absent on disk must return to download queue.
- Expired media URL must be refreshed before transfer.

---

### Task 1: Profile addresses and paginated API

**Files:** Create `data/instagramprofile/InstagramProfileApi.kt`, `InstagramProfileModels.kt`; test under `app/src/test/.../data/instagramprofile/`.

**Interfaces:** `InstagramProfileLink.parse(String): InstagramProfileLink?`; `InstagramProfileApi.profile(username,cookies): InstagramProfileInfo`; `page(userId,cursor,reels,cookies): InstagramProfilePage`. Summaries have stable shortcode, timestamp, type, thumbnail, and media count.

- [ ] Write tests for accepted/rejected profile URLs; first profile page, feed cursor, Reels cursor, duplicates, missing or changed JSON.
- [ ] Run focused tests and observe expected failures.
- [ ] Implement parsing, bounded HTTP, response classification and page decoding.
- [ ] Run focused tests and commit.

### Task 2: Durable jobs, selection, favorites and journal

**Files:** Create `InstagramProfileStore.kt`, `InstagramProfileController.kt`, `InstagramFavoriteStore.kt`; test state and resume policies.

**Interfaces:** Search jobs store both cursors, date period, Reels switch, scanned count, completion state and item rows. Item identity is `(shortcode, position)`. Controller starts/continues/cancels scans, downloads and favorite checks.

- [ ] Write tests for date/cap stop, duplicate merging, selected state, rejected favorite results and missing-file retry.
- [ ] Run focused tests and observe expected failures.
- [ ] Implement separate SQLite state, favorites and WorkManager scheduling.
- [ ] Run focused tests and commit.

### Task 3: Scan and download workers

**Files:** Create `InstagramProfileWorker.kt`, `InstagramProfileDownloader.kt`; modify `MediaRepository.kt` only if needed for history records.

**Interfaces:** Worker scans one page per checkpoint with rate pauses and handles both cursors. Download resolves chosen post anew, filters PHOTO/VIDEO, rechecks actual presence, and records each published file in normal history.

- [ ] Write tests for post media mapping, retry and interrupted/missing file decisions.
- [ ] Run focused tests and observe expected failures.
- [ ] Implement foreground worker, resolved media transfer and reconciliation.
- [ ] Run focused tests and commit.

### Task 4: Compose screen and navigation

**Files:** Create `ui/screens/InstagramProfileScreen.kt`; modify `SettingsScreen.kt`, `MediaLoaderApp.kt`, app version.

**Interfaces:** Search form with `VkPeriodPicker`, Reels switch, favorites and journal, selectable grid/list results with photo/video badges, publication selection, download/continue/retry controls.

- [ ] Verify relevant state logic with focused tests where possible; inspect screen semantics.
- [ ] Implement screen and navigation; preserve light/dark theme colors.
- [ ] Run full unit suite, ARM64 release build and `git diff --check`.
- [ ] Sign and verify APK; archive source and Git history; commit and upload release artifacts.
