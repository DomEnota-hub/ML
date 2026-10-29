# Media and VK Polish Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Improve microphone recognition and VK download review, recovery and history display.

**Architecture:** Extend the existing PCM collector with an early finish signal and amplitude callback. Group VK history by job ID in the UI. Reconcile missing completed VK items against device storage before downloading. Exclude explicit VK ads from result queries and new ingestion.

**Tech Stack:** Kotlin, Compose, Android AudioRecord, Room history, SQLite VK jobs, WorkManager.

**Spec:** `docs/superpowers/specs/2026-10-24-media-vk-polish-design.md`

## Global Constraints

- No automatic deletion or download of unselected files.
- An inaccessible URI must never be classified as deleted.
- Hide only confirmed VK ads, not possible ads.

## Review Focus

- Early recording stops at 4–11 seconds without zero padding and cancellation discards audio.
- Download grouping includes historical VK rows and leaves noncommunity downloads unchanged.
- Interrupted selected missing DONE/DUPLICATE rows return to queue while present/inaccessible rows do not.
- Result counts, pagination and viewer navigation match filtered ad rows.
- A disabled selected item can be deselected before recovery.

---

### Task 1: Short microphone capture and visualizer

**Files:** `MicrophoneCapture.kt`, `MicrophoneCaptureTest.kt`, `MusicHomeSection.kt`, `MediaRepository.kt`.

- [ ] Test early finish after minimum and callback amplitude; observe red.
- [ ] Implement partial PCM return and the live volume UI; run focused tests.
- [ ] Commit.

### Task 2: Compact VK history

**Files:** `DownloadsScreen.kt`, `DownloadRow.kt`, new grouping policy and test, `MediaRepository.kt`.

- [ ] Test community job grouping of old and new rows; observe red.
- [ ] Implement collapsed per-job card and bounded child display; run focused tests.
- [ ] Commit.

### Task 3: Restore selected missing VK files

**Files:** `VkCommunityDatabase.kt`, `VkCommunityController.kt`, `VkCommunityWorker.kt`, `VkCommunityResultsScreen.kt`, policy test.

- [ ] Test state transition rules for MISSING/PRESENT/INACCESSIBLE; observe red.
- [ ] Reconcile selected completed rows in IO before download and expose recovery action; run tests.
- [ ] Commit.

### Task 4: Hide confirmed ads and align action colors

**Files:** `VkWallParser.kt`, `VkCommunityDatabase.kt`, `VkCommunityResultsScreen.kt`, `DownloadRow.kt`, shared action buttons and focused tests.

- [ ] Test only confirmed ads are hidden; observe red.
- [ ] Filter new and historical result rows consistently, make ordinary actions theme-colored, and add dialog action outlines.
- [ ] Run full tests and release build, commit, sign APK, archive source and history.
