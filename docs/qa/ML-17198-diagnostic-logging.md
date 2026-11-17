# ML 1.7.198 / 258 — P0 diagnostics (device QA follow-up)

Base signed QA 1.7.197 exact source `6569d888ee32792b6c0fa416d3a736e329f33faa`.

Xiaomi Android 16 manual log 17 November 2026 17:52:39: `FV_DESTINATION_STATE`, 0/46 for pending folder `Мелкие`, `HiddenFolderFileStore.root:40`. Cause of the failed `.nomedia` predicate is not evidenced by this log. No code here changes the transfer algorithm, owner checks, marker creation, original deletion, pending journal or copy bytes.

Logging split:
- Base: `ROOT_GUARD` fixed reason code `NOMEDIA_{MISSING|SYMBOLIC_LINK|DIRECTORY|NON_REGULAR|NONEMPTY|ACCESS_DENIED|UNKNOWN}` and `OWNER_UNVERIFIED`; existing `STOP` records attempt id, stage, count and stable exception class.
- Extended: on `.nomedia` failure only, bounded and path-free marker type and marker size, fixed ownerVerified boolean. No user filenames, hashes, file contents, URI, folder identifiers or tokens.
- Detailed Performance: fixed `RECOVERY_GATE` enter/exit and invalidation reasons, `RECOVERY_REFRESH_REQUEST`, `RECOVERY_SCAN_DECISION` before scheduled forced scans, additional `PHONE_SCAN_RECOVERY[n,totalMs,maxMs]` without modifying existing counter semantics. Emitted only when detailed perf diagnostics are enabled.

Read-only marker probe is called only after failure of the existing safety gate. Deliberately no auto-repair of an ambiguous or nonempty `.nomedia`. Tests require nonempty marker and symlink to remain untouched; no permissions or destructive actions are broadened.

QA acceptance: targeted `HiddenFolderFileStoreTest` + existing FolderVisibility/Storage tests; full JVM with existing Compose isolation, lint, exact ARM64, DomEnota original signing, private GAPI preservation, split ZIP and Google Drive round-trip. Do not declare 46 files recovered until owner checks them on Xiaomi. Do not log private artifact content or ship user logs in source archive.
