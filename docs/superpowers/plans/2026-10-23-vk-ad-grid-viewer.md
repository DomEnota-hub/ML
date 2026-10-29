# VK Ads, Grid and Swipe Viewer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Classify VK ads conservatively, show dense four-column results, and allow swiping and selection in the large viewer.

**Architecture:** Extend the existing parser and SQLite image index with ad status. Keep result paging at 60 items, while the viewer reads one indexed item at a time. Compose renders one saved list/grid preference and shares selection state with the database.

**Tech Stack:** Kotlin, VK `wall.get`, SQLiteOpenHelper, Jetpack Compose, JUnit4, Gradle.

**Spec:** `docs/superpowers/specs/2026-10-23-vk-ad-grid-viewer-design.md`

## Global Constraints

- Preserve package `com.domenota.medialoader`, existing SQLite data, ARM64 build and DomEnota signer.
- VK explicit ads start unselected; suspected ads stay selected.
- Do not store VK tokens in ad metadata or logs.
- Do not upload to GitHub. Upload signed APK and source/Git history archive to the established Drive folder.

## Review Focus

- `marked_as_ads=1` on a post with several photos deselects every photo.
- Explicit flag absent with ad-like text marks suspicion without deselecting.
- Prior SQLite rows survive schema migration and retain their selection.
- Viewer crosses the boundary between page 1 and page 2 without skipping or repeating an item.
- Favorite deletion only occurs after confirmation.

---

### Task 1: Ad classification and persistence

**Files:** `VkWallParser.kt`, `VkCommunityDatabase.kt`, `VkAdClassifier.kt`, parser/classifier tests.

- [ ] Write tests for explicit and suspected ads, including multi-photo posts and ordinary posts; run focused tests red.
- [ ] Add ad status to candidates and migrate `images` without changing existing selection. Save and read status; set selected=0 for explicit ads.
- [ ] Add database operation for selecting an entire post; run tests green and commit.

### Task 2: Viewer and result modes

**Files:** `VkCommunityResultsScreen.kt`, `VkCommunityScreen.kt`, viewer index tests.

- [ ] Test pure page/index navigation at boundaries; run red.
- [ ] Add four-column grid and list toggle; remember choice. Render ad labels, localized state and post selection without technical IDs.
- [ ] Add swipe viewer with indexed SQLite loading and top-left selection, update parent results immediately; run green/compile and commit.

### Task 3: Setup screen polish

**Files:** `VkCommunityScreen.kt`.

- [ ] Add green/red key status with exact wording and improve layout with cards/spacing.
- [ ] Replace favorite Delete text with right-side cross; require confirmation before removal. Compile and commit.

### Task 4: Release and preservation

**Files:** `app/build.gradle`, release report.

- [ ] Increment version; run unit tests, lint, ARM64 release build.
- [ ] Sign with existing DomEnota key, verify package/ABI/certificate; archive full source and Git history.
- [ ] Upload signed APK and source archive to Drive and verify metadata.
