# ML 1.7.204 / 264 — cycle 2 (stages 3 + 4)

Status: **code candidate; exact-HEAD CI pending; Xiaomi on-device proof pending**. This is NOT a signed APK, release or device acceptance. The cycle stops after stages 3 and 4, awaiting explicit user permission for stages 5 and 6.

## Immutable safety and compatibility constraints

- Base: 1.7.203/263 `f89868efb766cad0f1b971e5e0e126b8cd1fa040`, installed on Xiaomi Android 16. The user has an actual pending SHOW 0/4 in folder «Тест». A public destination for the first of four files may already be published with stale MediaStore date metadata; hidden original and noBackup journals must remain protected.
- Release installation must be **in place over 1.7.203**, package `com.domenota.medialoader`, versionCode 264, original DomEnota signing certificate. Do not clear app data, journals, private-media, folder contents, ownership markers or `.nomedia`.
- Continue to read exactly the same 1.7.203 checksum-protected move/batch journal bytes, owner and receipts. The existing five durable move phases PREPARED / ALLOCATED / VERIFIED / LINKED / CLEANED remain unchanged. There is **no destructive journal upgrade, no auto-resume at startup** and no unilateral deletion on metadata uncertainty.
- Existing stage 1 DATE_TAKEN reconciliation and stage 2 independent Private restore are preserved.

## Stage 3 — safer, Private-inspired transfer core, maintaining old journals

- `VisibilityRecoveryPlanner.inspect(batch,pending,allowLegacyUpgrade)`: read-only validation of the exact current member, fingerprint, operation ID, direction, token, source identity and journal phase before any global media recovery gate; supports crash at PREPARED, crash with owned published destination at ALLOCATED and a *precisely matching* batch-first legacy metadata upgrade for HIDE. Orphan, different-member or mismatched fingerprints fail closed; not guessed from filenames.
- `FolderVisibilityManager.runOperation`: performs that preflight before entering `HistoricalRecoverySignals.beginFolderVisibility()` and before any file mutation, so corrupt/mismatched old journals do not cause unnecessary scanner invalidation or side effects. `FolderVisibilityBatch.resumeLocked`: validates again before `prepareMembership` can change catalog links.
- `VisibilityVerifiedCopy.writeAndSync()`: one streaming, 64 KiB-buffer, bounded-copy + SHA-256 + fd.sync implementation for HIDE and SHOW, following the verified-copy-before-commit principle already used by «Личное». Existing second physical-destination fingerprint, temporary-file atomic publication for hidden targets, no overwrite of unrelated public rows, exact owner/aliases and CAS journals remain in both paths. No heap-size-dependent media buffers.
- This deliberately **does not rewrite the legacy state machine or migrate old physical storage**, because doing so before the four old files are recovered would endanger recovery. The next architectural split may replace the internal engines only after device proof of this compatibility layer.

## Stage 4 — safely reduce repeated work and independent media rescan

- `HistoricalRecoverySignals` tracks the exact affected MIME kinds of authenticated new/old folder batch; supports VISUAL, AUDIO, both for mixed, and both on uncertain/empty/legacy metadata. Its bounded 32-generation completion history unions rapid consecutive operations even when StateFlow conflates their events; if history is missing the result is always BOTH.
- `LibraryViewModel`: after a visual-only transfer, refreshes Gallery provider, retains unchanged Music provider snapshot/cache and does **not** force audio scan. After audio-only transfer, refreshes only Music. Mixed/unknown batches and external provider changes queued during the transfer refresh both as necessary. Unrelated permission or privacy changes still invalidate their own data. The live visibility/privacy guard continues filtering; none of this bypasses access.
- `postTransferIndexAligned()` now waits for complete/error-free, current revision/permission epochs of **the provider kinds actually rescanned**, not an unrelated never-opened Music screen; the shared app index and live visibility guard remain mandatory. Existing 90s finite deadline and no false success remain.
- `HistoricalReferenceLinks.rebind(..., verifyInline=false)` is used **only** from `FolderVisibilityCatalogBindings.rebind`; it skips one consecutive duplicate cross-store verification inside the same VERIFIED phase, because `FolderVisibilityMove.VERIFIED` unconditionally calls authoritative `ports.verifyBinding()` and checks the proof before writing LINKED, followed by LINKED/CLEANED and final source safety checks. Every historical or non-visibility caller retains the default immediate verification. Tests with intentionally failing binding proof keep the hidden/public source intact. This is one redundant proof removed, **not** removal of seven durable safety boundaries.
- Baseline on Xiaomi 1.7.203, 18:52 HIDE four files: `MOVE_FILE` ~3.5–4.1 seconds per item; `MOVE_BIND VERIFY_LINKS` 28 calls / 2569ms total, `REFERENCE_LINKS` four / 2101ms, `EVIDENCE` four / 1116ms. `MOVE_PORT FINAL_VERIFY` four / 4337ms. Physical copy events are far cheaper. These are overlapping nested stage measures, not additive total elapsed. There is no proof yet of end-to-end speed-up on Xiaomi in 1.7.204.
- Gallery/Music should not be blanked by mere receipt changes. Independent privacy/permission changes and missing validated generations stay fail-closed; stage 4 does not promise never scanning the system provider again.

## Targeted regressions

- `VisibilityRecoveryPlannerTest`: PREPARED/ALLOCATED/VERIFIED/LINKED/CLEANED replay, orphan, wrong member/fingerprint, exact legacy HIDE metadata enrichment only.
- `VisibilityVerifiedCopyTest`: complete content both directions, destination rehash, truncation, corruption and larger-than-expected inputs.
- `VisibilityAffectedMediaKindsTest`: visual-only/audio-only/mixed/unknown; two rapid operations and lost version fall back to both.
- `RecoveryRefreshModesTest`: provider scan selection and queued external notification.
- `LibraryValidationGateTest`: visual-only completion without forcing never-loaded music and mirrored audio case, strict error/partial/visibility guard.
- Existing `FolderVisibilityMoveTest` and `VisibilityPrivatePublicationGateTest` maintain fail-closed source preservation and pending-journal isolation.

## Automated and physical acceptance gates

1. Targeted JVM tests on exact source SHA, followed by full JVM/Compose with no removed tests, storage contracts, Android lint, ARM64 unsigned package and ABI. CI results belong to the exact branch SHA; cancelled/older runs are not PASS.
2. Physical 1.7.203 → signed 1.7.204 in-place upgrade, exact SHA/date/ownership backup first, read-only journal preflight, explicit 0/4 SHOW recovery without deleting hidden originals early, separate Private restore under pending folder, failure/restart/permission denial, no lost or duplicate files.
3. Measure four-file and 46-file HIDE/SHOW, per-stage MOVE_PORT/MOVE_BIND, counters PHONE_SCAN, BACKGROUND_PROJECTION, GALLERY_BROWSE aborted, heap/GC/UI stalls, unchanged Gallery/Music and ongoing audio player under transfer. Only real before/after on same Xiaomi validates speed and smoothness.
4. Any unresolved ambiguity blocks cleanup, new folder engine migration and stable release. No interim signed APK or GitHub release. Next stages 5 and 6 require a new explicit user approval.
