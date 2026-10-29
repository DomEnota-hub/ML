# VK Results, Favorites and History Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give VK image results a full screen, add circular multi-selection and large image viewing, save favorite communities, and expose a lightweight searchable job history.

**Architecture:** Keep the existing VK SQLite job/image index and WorkManager pipeline. Split the Compose view into setup, results, and history states. Store a small favorite list locally; repeat creates a fresh job, while history is a read-only snapshot.

**Tech Stack:** Kotlin, Jetpack Compose, SQLiteOpenHelper, WorkManager, JUnit4, Gradle.

**Spec:** `docs/superpowers/specs/2026-10-23-vk-results-favorites-history-design.md`

## Global Constraints

- Preserve package `com.domenota.medialoader`, DomEnota signing identity, ARM64 packaging and all existing app data.
- Do not delete downloaded media or ordinary download history when deleting VK job records.
- Never log or persist the VK service token with favorites or journal entries.
- Do not publish to GitHub. Keep a local source plus Git history backup and upload artifacts to the established Drive folder.

## Review Focus

- Expired CDN thumbnails show an unavailable state without crashing or silently selecting a file.
- Switching between setup, results and history preserves the active job and period.
- Deleting a historical job cannot race a running scan/download.
- Repeating a historical search creates a new job; it does not reuse old image URLs.
- Favorites deduplicate `vk.ru` and `vk.com` links to the same short name.

---

### Task 1: Favorite community persistence

**Files:** `data/vkcommunity/VkFavoriteStore.kt`, `data/vkcommunity/VkCommunityLink.kt`, `VkFavoriteStoreTest.kt`.

- [ ] Add failing pure tests for canonical community keys, favorites encoding/decoding and duplicate addresses.
- [ ] Implement bounded local favorite storage with label and canonical key; keep service tokens separate.
- [ ] Run focused tests and commit.

### Task 2: Job history operations

**Files:** `data/vkcommunity/VkCommunityDatabase.kt`, `VkCommunityController.kt`, `VkHistoryPolicyTest.kt`.

- [ ] Add tests for pure deletion eligibility and repeat parameters.
- [ ] Add paged job summary query and transactional removal of job plus associated `images`; preserve `hashes` and ordinary history.
- [ ] Expose guarded delete/clear and repeat actions in the controller. Run focused tests and commit.

### Task 3: Results and large preview

**Files:** `ui/screens/VkCommunityResultsScreen.kt`, `ui/screens/VkCommunityImageViewer.kt`, `ui/components/RemotePreview.kt`, `ui/screens/VkCommunityScreen.kt`.

- [ ] Build full-height results view with 60-item paging, circular independent selectors and fixed download action.
- [ ] Open a fitted image view on thumbnail tap; show loading, unavailable and close states.
- [ ] Keep active task state and selection when returning to setup. Verify Compose compilation and commit.

### Task 4: Setup, favorites and journal UI

**Files:** `ui/screens/VkCommunityScreen.kt`, `ui/screens/VkCommunityHistoryScreen.kt`.

- [ ] Add favorite save/pick/remove controls and a history entry point on setup.
- [ ] Show historical jobs with dates, counts, saved-result view, repeat action and per-entry/clear confirmation.
- [ ] Remove the visible 1.7.16 archive control but leave its database untouched. Verify Compose compilation and commit.

### Task 5: Candidate validation and preservation

**Files:** `app/build.gradle`, `docs/stages/VK_1.7.20_REPORT.md`.

- [ ] Raise version to 1.7.20 (82); run full unit tests, lint and ARM64 release build once after implementation.
- [ ] Check signer against 1.7.19, APK metadata and SHA-256. Create complete source plus Git history archive.
- [ ] Upload clearly labelled test APK and archive to Drive and verify metadata; report device-only checks honestly.
