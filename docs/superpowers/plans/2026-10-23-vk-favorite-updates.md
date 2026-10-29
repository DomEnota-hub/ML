# VK Favorite Updates Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a one-tap check for new images in all favorite VK communities, with per-community dates and reviewed checkpoints.

**Architecture:** Extend favorite persistence with a start date and reviewed timestamp. Use one WorkManager worker to scan favorites sequentially into existing VK jobs, persisting a batch manifest for UI recovery. Reuse the existing result screen for selection and downloading; commit each group's checkpoint only when its review is finished.

**Tech Stack:** Kotlin, Compose, WorkManager, SQLite, SharedPreferences, VK wall.get.

**Spec:** `docs/superpowers/specs/2026-10-23-vk-favorite-updates-design.md`

## Global Constraints

- Images only; no automatic download or periodic scheduling.
- A failed or interrupted scan must not advance a review checkpoint.
- New posts created during a scan must remain eligible next time.
- Existing favorites, downloads and journal entries must survive migration.

## Review Focus

- Existing favorite JSON without dates loads with a useful default.
- Updating a favorite's metadata does not reset its checkpoint.
- A failed community scan does not block the following community.
- A post created during a scan is eligible next time.
- Returning from results without finishing review does not advance a checkpoint.

---

### Task 1: Favorites and scan boundaries

**Files:** `VkFavoriteStore.kt`, `VkWallParser.kt`, focused unit tests.

**Interfaces:** `VkFavorite.sinceDate`, `VkFavorite.reviewedThrough`; `VkFavoriteStore.setSinceDate`, `markReviewed`; parser accepts an optional epoch window.

- [ ] Write tests for legacy decode, metadata preservation, date update, and precise lower/upper scan bounds.
- [ ] Run focused unit tests and observe expected failure.
- [ ] Implement favorite state and scan window.
- [ ] Run focused unit tests and commit.

### Task 2: Durable sequential batch scan

**Files:** new `VkFavoriteCheckStore.kt`, `VkFavoriteCheckWorker.kt`, `VkCommunityController.kt` and focused tests.

**Interfaces:** batch entries map favorite keys to job IDs and scan upper bounds; controller starts one unique worker and completes a reviewed group.

- [ ] Write tests for checkpoint eligibility and batch state decisions, observe failure.
- [ ] Implement persistent batch and sequential worker reusing existing job storage.
- [ ] Run tests and commit.

### Task 3: Date editing and review UI

**Files:** `VkCommunityScreen.kt`, new favorite-check summary and date picker UI.

**Interfaces:** setup launches batch; summary opens per-community existing results; explicit finish advances only successful group's checkpoint.

- [ ] Implement UI using existing app components and result selection callbacks.
- [ ] Compile and run full test suite.
- [ ] Commit.

### Task 4: Release verification

**Files:** `app/build.gradle`, release artifacts.

- [ ] Bump version and assemble ARM64 release.
- [ ] Verify tests, lint, signing certificate, package version, and archive integrity.
- [ ] Commit and deliver signed APK plus source/history archive.
