# Download Catalog Implementation Plan

> **For agentic workers:** Implement inline with `superpowers:executing-plans`; the user approved the folder sheet mockup and asked to begin.

**Goal:** Add app-only folders and favorites for completed downloads, opened from the Downloads header.

**Architecture:** Keep physical media files in their existing storage location. Store folders and per-download catalog assignments in two new Room tables, separate from mutable download history rows. Expose observed catalog state through the existing repository and ViewModel. Filter history within Downloads; leave active downloads visible under every catalog selection.

**Tech Stack:** Kotlin, Room 2.6, Compose Material 3, coroutines.

**Spec:** Approved Downloads folder-sheet mockup from the 2026-10-26 conversation; one folder per file, favorites independent of folders.

## Global Constraints

- Database upgrade must preserve all existing download rows and saved media.
- Folder deletion only removes organization; it must never delete physical files.
- Existing source, type, and text filters continue to work inside a selected folder.
- Catalog actions apply to completed, visible downloads only.

## Review Focus

- Database migration from version 10 retains old history.
- Duplicate or blank folder names are rejected; rename does not lose assignments.
- Clearing/deleting history cleans catalog assignments without touching folder definitions.
- Grouped download cards expose actions for individual child files.
- Counts and filtered history update immediately after edits.

---

### Task 1: Persistent catalog

**Files:** `core/database/HistoryDatabase.kt`, `data/catalog/DownloadCatalogRepository.kt`, `data/MediaRepository.kt`; tests in `app/src/test`.

- [x] Add folder and assignment entities, DAOs, migration 10→11.
- [x] Add validated create, rename, delete, assign, and favorite repository operations.
- [x] Verify no physical file operation occurs and existing rows remain available.

### Task 2: ViewModel and catalog filtering

**Files:** `ui/MediaLoaderViewModel.kt`, `ui/MediaLoaderApp.kt`, `ui/model/DownloadCatalogFilter.kt`.

- [x] Observe folders and assignments and expose user actions.
- [x] Filter only completed history by All, Favorites, or selected folder.
- [x] Keep source/type/search filtering and active list behavior.

### Task 3: Folder sheet and item actions

**Files:** `ui/screens/DownloadsScreen.kt`, `ui/screens/DownloadFolderSheet.kt`, `ui/components/DownloadRow.kt`.

- [x] Add header folder icon and sheet matching the approved mockup.
- [x] Add create and rename dialogs, folder deletion confirmation, counts, and empty states.
- [x] Add favorite and folder actions to each completed file menu.

### Task 4: Release verification

- [x] Run relevant tests and release build (449 tests, no failures, one skipped).
- [x] Sign and verify APK; provide build for device validation.
