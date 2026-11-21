<!-- HISTORICAL. Superseded by /AGENTS.md and /docs/ci/README.md. Do not run the old workflow or runner. -->

# QA196 continuation checkpoint — implementation, NOT a release

Prepared 16 Nov 2026 following the owner's instruction to preserve remaining Codex quota. Six QA195 implementations are present; hardware acceptance and final release gates remain open.

## Source and approved scope

Base released195:32fcef644c0c31268b8acfa166b6600dada1b4f8, Draft144, feature/ml-17195-projection-stability. Current work branch feature/ml-17196-safe-folder-recovery; version196/code256 reserved, NOT delivered. Read ML-17196-plan.md and ML-17196-changes.md for scope, root causes and safety boundaries.

Current authoritative Drive documents: TZ1xlhUA07kkV1PmZkwi7m8Owno_L3BPEDY; Summary1blEMbZmmAujpm5C9pZB0iqZ5bfhtHHRA; Rules1-2O6i-WThyAM9yj9KQpNTHHi6pe8t5F2; device195log14Xy8JFmZchWzKpnPMTtoM0akyRbRRLsi. Folder1e7lk-lxTQIliKzU3rz_FQ9JA5txqwCAc. Never modify Rules or upload original logs to Git. No production merge, no GitHub Release, no Dropbox.

## Completed implementation

05: canonical physical MediaStore discovery, Files-wide hidden index verification; conservative exact-source legacy journal enrichment with batch-first CAS; known planning/pending counts, safe stage/code errors; remove redundant forced scans on failed preflight. No deletion during journal upgrade. Existing46-file physical state/exception remains unknown.
04: off-main alias maps, exact-generation raw reference reuse and warm publication coalescing; off-main controller binding with main Looper and UI leases; bounded thumbnail synchronization/negative cache; direct encoded payload transport and background-priority scanning without hash changes.
03: persisted automatic cover anchor across card/editor/overview; prefer exact anchor in detail cache then overview cache, no later-member substitution; manual cover semantics preserved.
02: explicit neutral stopped/paused state, partial journal persistence, retained read-only report, compact themed buttons. Last review fix also passes fresh ready/phone permission/currentGeneration into DuplicateTrashHost so already-open deletion pauses when catalog becomes stale.
01: collection outlined double note.06: scoped rounded Find file border.

## Verification status at source checkpoint

PASS: git diff --check; palette audit295files; round-slider guard; in-app notice guard. First addressed build compiled production Debug Kotlin successfully. First addressed run stopped at test compilation: two new Robolectric tests incorrectly called non-generic RuntimeEnvironment.getApplication with a type argument. Calls corrected; addressed tests have NOT passed yet. Obtain actual results from CI or retained workspace logs, never assume PASS. Production build in that first run predates the final DuplicateTrashHost readiness argument and version regeneration, so repeat compilation/addressed tests on committed source as needed.

Added tests cover46-member durable interruption/resume/no double deletion; legacy upgrade gaps and invalid sources; MediaStore aliases/wrong-type hidden rows/Room enrichment; error privacy/cancellation; stable cover; stopped report/actions; cache/projection/controller lifecycle; exact encoded payload digest. No real user files touched. No connected adb target or /dev/kvm available at checkpoint.

## Next required actions

1. Read GitHub PR/check status on this exact branch and latest commit. Inspect errors before rerunning. Finish addressed tests and fix actual failures; final readiness argument needs compiled verification and an addressed stale-catalog trash regression.
2. Existing isolation script scripts/run-jvm-isolated-compose.py full must run ALL JVM tests (recurring Compose tests in separate workers), plus storage integration guards and Android lint. New workflow ml-17196-qa-ci.yml runs these once. Old storage job skips this branch because its tests are included in full and its integration guards copied into the same job; no tests removed. Do not duplicate full local/CI runs without cause.
3. Build ARM64 release locally on the exact candidate with protected built-in GAPI input; CI source-only build lacks those local keys. Do not use officialQa (changes package/code). Check credentials present without logging values. Use one Gradle process; source freeze during compilation. Do not call CI Debug or an old-SHA APK the release.
4. Fix verification failures, rerun only required checks until exact final SHA has full gates. No disabled tests/lint suppressions to pass.
5. Sign original DomEnota, confirm certificate SHA2564f6f0ddf4d8fd2669fd2a3a6ebab6e84bda17281a3a5badada0e1eeb73a4f1d2, package com.domenota.medialoader, version196/256, ARM64-only, v2/v3, zip alignment; metadata compatible with update over195. Actual install/data retention is owner acceptance, no target available.
6. One new Drive Загрузчик/1.7.196 folder: split APK .z01+.zip (extra volumes allowed, each<100MB), exact git archive source ZIP, comprehensive Russian README and SHA256SUMS. Protected inputs/keys/logs excluded. Download back every delivered file, size+SHA comparison, reconstruct ZIP/APK and verify; no unrelated repeated checks. Update existing Summary preserving ID.
7. Report all six actual changes, proven causes vs unknown phone cause, exact SHA/PR/Drive, verification/signature/integrity evidence, manual Xiaomi scenarios and limitations. Hardware issues remain pending real acceptance.

## Workspace recovery (local only, not portable credentials)

Worktree /workspace/ml-17196. Addressed build output /tmp/ml196-addressed.log, build dirs /tmp/ml196-app-build and /tmp/ml196-root-build. Temp files can disappear; re-create only if absent. Main old worktree /workspace/ML is NOT this feature base. JDK/SDK/Gradle8.9 reside under /workspace/medialoader-1.6.14-toolchain. Private keys are supplied by existing ignored local inputs; never print/archive/commit them.

Useful addressed Gradle filters: *Visibility*Test,*HiddenFolderFileStoreTest,*CollectionCatalogSnapshotTest,*LocalThumbnailFailuresTest,*LatestProjectionCacheTest,*AsyncPlaybackConnectionTest,*AudioControllerConnectionTest,*EncodedAudioPayloadTest,*AppReferencePublicationPolicyTest,*VisualDuplicateStopTest,*VisualDuplicateStoppedPanelTest,*VisualDuplicateStoppedScreenTest. Use -Parm64Release=true -Pkotlin.compiler.execution.strategy=in-process --max-workers=1 --no-daemon --console=plain and bounded heap2.3GiB/ActiveProcessorCount2 locally.
