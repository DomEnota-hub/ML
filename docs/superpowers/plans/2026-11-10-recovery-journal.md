# Historical date recovery implementation plan

**Goal:** Issue #113 stage 2, exercise restart-safe replacement and reference migration on generated media only.
**Architecture:** A durable operation record drives idempotent ports. The Android entry point owns an isolated QA namespace, database and MediaStore folder; it cannot accept a download ID or URI from the user library. Originals and verified backups remain after success and rollback. Production execution and mass execution have no entry point.
**Spec:** https://github.com/DomEnota-hub/ML/issues/113 and audited report SHA256 9c73c42acc4cc3c56a38111f001912e01e9ab14fbe10d6c4668cf3c8cf5e2039.

## Constraints and review focus

- Generated JPEG/MP4 only; historical timestamp explicitly means queue time.
- No automatic startup mutation, no original deletion, no production database mutation.
- Persist intent before side effects, discover pending rows by unique operation identity, CAS only an existing history ID.
- Separate durable steps for main database, VK index, external catalog and playlist aliases. Reverse these steps on rollback.
- Recheck ownership, exact path/name, checksum, immutable links, free space and destination identity. Unknown journal/schema/identity or mixed reference state stops execution.
- A process lock and OS file lock cover the entire sandbox operation; generated namespace isolates it from live jobs. This is not a production global mutation lock.

## Tasks

- [x] Implement state machine, resume and rollback with an injected durable store and idempotent ports.
- [x] Test interruption before/after every transaction boundary, corrupted identities/checksums, storage exhaustion, conflicts and concurrent acquisition (model tests; device process-kill tests remain a real-file gate).
- [x] Implement Android synthetic-only adapter: generated fixtures, verified backups, pending publication, dated metadata and decode/encoded stream checks, isolated Room/CAS and auxiliary indexes.
- [x] Add lab UI with explicit create/resume/rollback, visible status and mass disabled. No real URI picker.
- [ ] Run available unit/lint/build checks, inspect failures, sign ARM64 with existing DomEnota key, verify split ZIP reconstruction.
- [ ] Push separate branch, create Draft PR and upload APK volumes plus exact sources to Google Drive Загрузчик/1.7.167.

## Real-file gate

The deliverable is a synthetic-only QA build. Permission for a real-file test is outside this task. Production consumer locks, OEM chronology after reboot and real-file multi-track coverage must be reviewed before enabling any real-file entry point; report these explicitly, without describing synthetic evidence as device acceptance.
