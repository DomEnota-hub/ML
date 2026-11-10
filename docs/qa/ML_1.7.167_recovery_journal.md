# Issue 113 — recovery journal QA, 1.7.167/code227

Base: PR #116, bd3635a26797edd68ee4dee796ad4cf7081b246f. Reviewed PRs #111, #112, #114, #115, #116 and the complete Issue #113 discussion. PR #113 is an issue, not a pull request.

## Audit evidence

The device report digest recorded in the issue is `9c73c42acc4cc3c56a38111f001912e01e9ab14fbe10d6c4668cf3c8cf5e2039`. Its aggregate review covers 1100 completed records, 655 candidates (428 JPEG, 227 MP4), 12 recent/uncertain and 433 policy exclusions. Among candidates there are 37 favorites, 68 catalog assignments, 292 groups and 9 derived-file links. The report did not verify hashes or playback. Private/hidden/audio exclusions must not be described as hundreds of lost files. No raw device URI inventory is committed here.

## Implemented QA scope

Settings → temporary media lab → stage 2. Generate one 320×240 JPEG or a two-second MP4 with video and an AAC sine tone. No picker, external URI or live download ID input exists. All files use an operation UUID under `Pictures/MediaLoader_QA_Recovery/`; history, VK index and preference documents use private operation-specific sandbox stores. Main Room uses the application's real schema and an exact-match CAS. Catalog and playlist documents use the current serialization formats. No production database or preference name is used for writes.

An atomic private plan precedes insertion. Pending rows can be rediscovered by exact UUID name, collection, owner, path and MIME. A persisted new URI precedes opening its writer. An original-byte backup and snapshots of the isolated databases and link documents are checked by SHA-256. Neither source nor replacement is deleted on success or rollback. Original-byte backups are separate from the dated staging file. Insufficient space stops the operation; it does not trigger cleanup. A fixed synthetic timestamp means queue time, not download completion or camera capture time.

Journal stages: PREPARED → COPY_PENDING → METADATA_VERIFIED → LINKED_MAIN → LINKED_VK → LINKED_CATALOG → LINKED_AUX → REOPEN_VERIFIED → AWAITING_CLEANUP → DONE. Each transition is written only after its idempotent effect. Rollback direction and compensation cursor are persisted before reverse writes. Resume never changes rollback direction. DONE explicitly means references checked and originals retained; it is not deletion approval. The lab does not run automatically on startup. “Read journal”, “Resume” and “Rollback” are explicit actions.

Only URI, display name and size can change in the existing download row. CAS checks the expected URI, old name, old size, public/completed state; a full-row comparison verifies all other fields. Download ID, timestamps, source links, group/bulk/derived IDs and catalog membership remain stable. VK image/hash references change in one local transaction, including the new container SHA. Catalog and playlist steps are independent durable boundaries. Unknown schema, foreign URI, multiple exact-name rows, lost backup or checksum mismatch stops processing.

The old QA name remains `ML_QA_<uuid>_fixture`; the replacement uses `ML_QA_<uuid>_restored`. This explicit name policy avoids collision with the retained original. Publication verifies the provider did not silently rename the file. Production exact-name quarantine is not implemented or enabled.

JPEG validation: original/backup/new SHA-256, SHA-256 over decoded pixels, and all exposed EXIF fields except the intended date fields. MP4 validation: all mapped encoded stream payload hashes and a complete video/audio decode with FFmpeg error exit enabled. The replacement is reopened through ContentResolver and decoded bitmap/MediaMetadataRetriever, matching the platform APIs used by viewers. Android date readback uses bounded retry. No claim is made that these synthetic tests cover every metadata/codec combination of real files.

One process lock plus an OS file lock excludes sandbox operations. No live player references a sandbox history ID, and cache/playlist mutations in the live application cannot target its private test stores. This isolation is the safety boundary; it must not be mistaken for a process-wide lock over all production download/editor/rename/private/trash/indexing paths.

## Phone QA, artificial files only

1. Install as an ordinary signed upgrade; keep app data.
2. Create JPEG trial, read status and compare both generated QA entries in system Gallery. The original entry remains current; the dated replacement should be historical. Verify exact displayed date after force-stop/reopen and device reboot, without clearing Gallery data.
3. Open replacement, then explicitly rollback. Check the same sandbox history ID points back to its unchanged fixture, and that favorite/album/aliases still match.
4. After rollback, create the synthetic MP4; listen for the generated tone and inspect playback. Repeat resume/rollback after interruption. A new test requires rollback of the previous operation.
5. Keep both originals and replacements for investigation. There is no cleanup command in this stage.

## Real-file readiness decision

**NO-GO for a real file in this build.** This is deliberately enforced by the absence of a live-file entry point. Synthetic execution does not demonstrate safe production migration.

Before separately authorizing a one-file real JPEG trial, implement/review the shared production mutation lock across all consumers, live store snapshot adapters and cache/player invalidation, exact-name quarantine/reversal, and on-device durable journal/process-kill tests. Confirm QA MediaStore date stability after scanner/reboot on Xiaomi. For a real MP4 trial add duration/time-base/track-order, audio/subtitle, rotation/HDR and side-data validation; packet hashes alone do not establish all of these properties. Multi-volume, large-file (>100 MiB) and foreground/Doze recovery remain blocked. Existing real files, old mass migration and mass recovery are not enabled by this PR.

## Verification results

Recorded after checks complete in the PR and delivery report. Device/emulator testing is unavailable in this workspace: adb reports no attached devices and the SDK has no emulator binary. This is an actual environment observation, not a passed device test.
