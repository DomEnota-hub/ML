# VK Community Images Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans inline. The user authorized all stages without repeated approval. Do not dispatch subagents.

**Goal:** Add a separate, resumable VK community static-image search and download flow without changing normal download behavior.

**Architecture:** Keep the existing history database unchanged. Store community jobs, candidate images, progress, and exact duplicate keys in a separate SQLite database. A dedicated Compose screen controls search, selection, and download; WorkManager performs bounded network work. Store the service token with an Android Keystore key, outside Android backup. Restrict all token-bearing requests to `api.vk.com`.

**Tech Stack:** Kotlin, Compose Material 3, SQLiteOpenHelper, WorkManager, Android Keystore, HttpURLConnection, JUnit.

**Spec:** `MediaLoader_1.7.17_Codex_TZ_2026-10-23.md`, stages 3–8.

## Global Constraints

- No changes to normal VK/other provider download flows, applicationId, existing Room schema, signing identity, or user paths.
- Community mode downloads only verified static JPEG/PNG/WebP. No GIF, animated WebP, video, clips, or audio.
- Never log or export token, cookies, or signed media URLs.
- API permissions must be validated by actual responses; do not assume a service token can read every wall.
- Tests with fake API data are model tests; real VK API behavior remains unverified without a user supplied token.

## Review Focus

- Reversed and cross-year date range includes the entire final day and excludes future dates.
- A post with several photos and a repost is flattened without image loss or duplicated rows.
- Animated WebP and mislabeled GIF are rejected by bytes, even under `.jpg` URLs.
- Two different VK IDs yielding identical bytes save one file and record one hash.
- App restart restores a bounded scan/download queue and does not double count completed items.

---

### Task 1: Date period model and calendar (stage 4)

**Files:** Create `data/vkcommunity/VkDatePeriod.kt`, `ui/screens/VkPeriodPicker.kt`; tests in `VkDatePeriodTest.kt`.

- [ ] RED: test reversed dates, one day, month rollover, end-exclusive DST boundary, and presets.
- [ ] Implement pure `VkDatePeriod` with local dates and zone; Compose calendar supports month/year navigation, highlighted range, presets, apply/cancel.
- [ ] GREEN: focused tests; commit.

### Task 2: Encrypted token store and VK API client (stage 5)

**Files:** Create `data/vkcommunity/VkTokenStore.kt`, `VkCommunityApi.kt`, `VkCommunityLink.kt`; tests for link normalization and API error mapping.

- [ ] RED: parse accepted community URLs/IDs, reject unrelated hosts and ambiguous paths; map VK auth/permission/rate/network errors.
- [ ] Implement Android Keystore AES-GCM store in excluded-from-backup private file; invalidate unrecoverable ciphertext and request token again.
- [ ] Implement official API request via POST body to HTTPS `api.vk.com`, bounded timeouts and responses, no token logging.
- [ ] GREEN: focused tests; commit.

### Task 3: Scan and static image validation (stage 6)

**Files:** Create `VkCommunityScanner.kt`, `StaticImageProbe.kt`, `VkCommunityModels.kt`; focused tests.

- [ ] RED: multiple photos/reposts, page >100, date edges, empty/private/API error, largest size selection, actual image magic/animated WebP rejection.
- [ ] Implement page-wise `wall.get`, max 100 per call and bounded backoff, normalize photo keys; probe bytes before final save.
- [ ] GREEN: focused tests; commit.

### Task 4: Persistent jobs and exact deduplication (stage 7)

**Files:** Create `VkCommunityDatabase.kt`, `VkCommunityDownload.kt`; focused tests.

- [ ] RED: same key, same SHA with different IDs, missing saved file, interrupted temp, parallel duplicate and restart.
- [ ] Store job/item/hash rows with indexed unique keys; stream SHA-256 into private temp; under one transaction check existing hash and atomically publish to existing storage destination.
- [ ] GREEN: focused tests; commit.

### Task 5: Queue, restart, and foreground work (stage 8)

**Files:** Create `VkCommunityWorker.kt`, `VkCommunityController.kt`; update manifest/service notification as needed.

- [ ] RED: 400/1000 page scans, bounded queue, 429/5xx backoff, cancellation/resume and idempotent counters.
- [ ] Use unique WorkManager jobs for scan and download, bounded pages and concurrency; persist checkpoints after every page/item; support cancel, resume, retry failed.
- [ ] GREEN: focused tests; commit.

### Task 6: Separate Compose screen and navigation (stage 3)

**Files:** Create `ui/screens/VkCommunityScreen.kt`; modify `ui/MediaLoaderApp.kt`, `ui/screens/SettingsScreen.kt`.

- [ ] Show `Загрузка из сообщества VK` entry, `Загрузка`/`Как получить ключ` tabs, token status, period calendar, explicit search/preview/select/download, counters and distinct error states.
- [ ] UI never offers photo/video/audio toggles or starts downloading before selection and confirmation.
- [ ] Compile, review theme and accessibility semantics; commit.

### Stage gates

- [ ] After stage 3/4/5/6/7/8, run focused checks, commit a report, and preserve source plus complete Git bundle locally and on Drive.
- [ ] At stage 9, verify 1.7.16 compatibility, run all tests/lint/build, sign only if release blockers are cleared.
