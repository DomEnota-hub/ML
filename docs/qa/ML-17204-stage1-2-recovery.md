# ML 1.7.204 / 264 — cycle 1 (stages 1 and 2)

Status: stages implemented in Draft QA branch, targeted tests PASS; full CI and physical-device recovery pending. This file is a checkpoint, not device approval.

## Base and installation constraint

- Based exclusively on signed installed QA 1.7.203/263 source `f89868efb766cad0f1b971e5e0e126b8cd1fa040` (Draft PR #152), not default main.
- New package ID remains `com.domenota.medialoader`; versionName `1.7.204`, versionCode `264`. Future QA must use original DomEnota signing certificate to update **over** 1.7.203, retaining app DB, Private files, access configuration and 1.7.203 durable visibility journals.
- User's Xiaomi Android 16: synthetic result HIDE 4/4 confirmed; SHOW fails on first file `FV_DESTINATION_STATE`, checkpoint `ALLOCATED`, confirmed 0/4, journal retained. The public restored row may already be published; original hidden copy must remain until fully verified. No real user media may be modified remotely.
- No migration or move is automatically started by application startup. The recorded journal remains untouched until explicit recovery under user control; any ambiguous state fails closed.

## Stage 1: recover legacy visibility SHOW safely

Root cause evidence: `VisibilityMediaStore.publishRestore` required exact millisecond equality for MediaStore DATE_TAKEN and exact DATE_MODIFIED and SIZE. Android MediaStore can report rounded DATE_TAKEN or delay its refresh. The same release already used <=1000ms tolerance in `StorageManager.confirmPrivateDate`; this is provider rounding tolerance, NOT a byte-integrity tolerance.

Changes:
1. Added `VisibilityRestoreMetadataPolicy` to report independent SIZE / DATE_MODIFIED / DATE_TAKEN verification with bounded <=1000ms DATE_TAKEN tolerance; size and DATE_MODIFIED still exact.
2. Exact destination `VisibilityMediaStore.publishRestore` retries updating stale metadata only after verifying the owned journal destination, physical path/bytes/mtime and SHA-256. It never overwrites a published candidate, and never edits the hidden original. Remains journal-driven, with 12 bounded asynchronous MediaStore reads; no guess from filename.
3. If metadata still differs, log only `RESTORE_METADATA_PENDING` with mismatch categories (no names, URIs or private metadata); fail closed preserving source and `ALLOCATED` journal. Source cleanup still requires destination fingerprint, binding proof, original re-verification and file ownership.
4. Added `VisibilityRestoreMetadataPolicyTest` (rounding, missing/incorrect metadata, strict bytes/time) and `FolderVisibilityMoveTest.publishedButUnconfirmedRestoreKeepsHiddenBytesAndJournalForResume`.

This is a candidate for Xiaomi 1.7.203 `0/4` recovery, **not** proof the four files can be restored on that device. Do not reset journal or delete files to force a pass.

## Stage 2: independent Private restore during unrelated pending folder journal

Root cause evidence: `PrivateScreen` -> `MediaRepository.restorePrivate` -> `PrivateMediaOperations.restore` -> `StorageManager.publishPrivateWithDate` -> `StorageManager.publish` -> `HistoricalRecoverySignals.requireIdle` blocked whenever `folderVisibilityPending=true`, even when no actual folder transfer was running.

Changes:
1. `HistoricalRecoverySignals` now has an exclusive independent-publication scope for vault restore. It allows a pending visibility journal, but **not** another running operation, concurrent vault publication, pending historical/old-downloads recovery, or other normal media mutation. The scope encloses publish, date/byte verification and is released in a `finally` block.
2. To prevent stealing an unpublished original name reserved by pending SHOW, `FolderVisibilityManager.reservedPendingPublicNames` reads the EXACT bounded authenticated binary batch journal, with no side effects; planned original names in the same MediaStore relative folder are reserved during `StorageManager.publish`. Normal name collision logic selects a distinct free name for the Private export.
3. Corrupt journal, move-only orphan journal, missing public-origin metadata, unresolved scope or a custom SAF target while visibility is pending **fail closed**. This restriction is deliberate; later architecture migration can provide a separate approved fallback destination.
4. The private original remains in app storage until the existing PrivateMediaOperations DB update and public validation succeed. Ordinary public mutations remain blocked during the private publication.
5. Added `VisibilityPrivatePublicationGateTest`: version-2 pending SHOW exact names across different directories, missing/corrupt legacy plan, simultaneous visibility/vault operations. Existing `PrivateMediaOperationsTest` remains mandatory.

Security constraints: no key/secret/cookie contents in diagnostics, no manual deletion of noBackup journals or `private-media`, no app reinstall/clear data, no automatic migration on first run. The public and hidden bytes, dates and original Room history must be checked on device.

## Validation status

- Stage 1 exact checkpoint `ab9ae8c8f2c04249a311b40f21b6418eab8ed903`: targeted CI #44351233448 SUCCESS.
- Combined stages targeted CI #44353357028 SUCCESS on `69edc4217516790d567ed6cd336fd84ad68972cc`.
- Full JVM/Compose, lint and ARM64 compile should run on the final combined commit and are not implied by targeted PASS.
- Original in-place signing, delivery and device QA are reserved for a later release stage, not these two stages.
- Draft PR base is the exact 1.7.203 feature branch, never production main.

## Device acceptance (not automated)

1. Back up the four hidden originals from `Documents/MediaLoaderHidden` without moving/deleting source files; also record any already-created public candidate and its SHA-256. Preserve journals.
2. Install signed QA 1.7.204/264 **over** installed 1.7.203/263. Verify old Private entries, journal and permissions are present.
3. Run explicit read-only recovery preflight. Only after proven same-identity source/candidate may user try the recovery. Confirm 4/4, original filename/date/bytes/sha, binding receipts and no lost/duplicate media. Check app interrupted during ALLOCATED and resumed.
4. Test unrelated Private restore with visibility journal pending; test name collision and a custom SAF target separately. Verify Private original retained on any failure.
5. Do not interpret CI success as passing these physical-device checks.

## Paused work

After this cycle, wait for explicit user approval before stage 3 (replacing the entire ordinary hidden-folder architecture) or stage 4 (speed/index optimizations). Do not start either in this chat cycle.
