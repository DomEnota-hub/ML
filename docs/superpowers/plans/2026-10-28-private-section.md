# Private section Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans; independent auth/storage/light styling are delegated under dispatching-parallel-agents.

**Goal:** Deliver Personal storage and access modes, plus stronger light-theme outlines.
**Architecture:** Internal verified file store, auth state manager, durable DB private marker/pending cleanup, guarded app navigation and session playback.
**Tech Stack:** Kotlin, Compose, Room, platform BiometricPrompt/KeyguardManager, PBKDF2.
**Spec:** docs/superpowers/specs/2026-10-28-private-section-design.md

## Global Constraints
- Release authorized after implementation and verification; retain existing public data.
- No plaintext PIN storage or auth saved through rememberSaveable/process death.
- Failed import/delete/DB writes must not lose the sole valid file copy.
- Public collections and media session must not expose locked private items.

## Review Focus
- Crash or deletion failure during move: pending public copy remains discoverable and retryable.
- Lifecycle stop/process death: private UI/player closed, no stale authorization.
- Mode change and biometric cancellation cannot bypass existing protection.
- Editing/trash/history clearing cannot publish or remove private files accidentally.
- Source rename/recognition concurrency must not overwrite private URI/flags.

### Task 1: Independent components
- [x] Auth tests, manager and real platform gate; verified file store tests and implementation.
- [x] Improve filename-dialog fields and shared outlines in light theme.
### Task 2: Data integration
- [x] Version12 migration and private/public visibility, guarded move/restore/delete/derived edits.
- [x] Verify copy/public-cleanup failure and clear-history protections.
### Task 3: UI and playback
- [x] Personal entry and menu action, setup/unlock/settings, pending cleanup and return/delete.
- [x] Secure lifecycle and routes, private player filtering/stopping, no external grants.
### Task 4: Delivery
- [x] Full tests/build, fresh independent review, fix important findings with regression tests.
- [x] Sign and verify next APK, upload, release notes with data lifetime/security scope.
