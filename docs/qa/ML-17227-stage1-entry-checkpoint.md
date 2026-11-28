# ML 1.7.227 / QA-226 — Stage 1 entry checkpoint

24.11.2026 MSK. CYCLE_1_STARTED / STAGE_1_ANALYSIS / VISUAL_REVISION_RECORDED / NOT_STABLE. 0/6 stages complete, all 14 QA items OPEN.

Base: delivered 1.7.226/286, c27fef7034d49a938194b6497a0b0ca5262bfdc0, PR #193/#194 both open, unmerged, previous qualify #45448036679 successful. New feature/ml-17227-qa226 branched from exact SHA; intended next version 1.7.227/287, build files not changed yet. Main untouched. No CI launched.

## Verified code
- SimpleArchiveStore: existing logical membership v1 JSON in noBackupFilesDir, AtomicFile + mutex, persists before publishing; rejects private/hidden/incomplete items; no changes to original media.
- SimpleArchivePolicy: URI/type/size match, refuses catalog ID conflict, no group identity/metadata, no reconciliation for changed URI.
- MediaLoaderApp: archiveSelection resolves IDs/aliases from current public refs/downloads, checks guard before store, removes archived audio from playback queue. Existing public projections filter the simple archive; legacy folder visibility still invokes .nomedia route. Need revalidate exact selection, dedup aliases, group metadata and all public entry paths; retain legacy recovery.
- SimpleArchiveScreen: list/filter, selection, restore, authorized preview and legacy restoration already exist.

## Proposed UI (not approved)
- Downloads single/group/virtual folder action: “В архив”; on multi-select, use the existing action surface. Owner revision: no additional confirmation dialog for logical archiving. Explicit “В архив” action executes directly on the exact selected scope. Preserve validation, atomic write and result “В архиве: 6 файлов” only after success. No change to destructive/trash/private confirmations.
- Archive: group card retains name and members, expand/open, restore exact members (restore UX remains separate); partial restore retains remaining members. Existing legacy restoration separated; no new .nomedia actions. Gallery top selection panel layout is QA226-11 Stage 2, not silently approved here.
- Theme tokens dark #FFBD19 / light existing purple, 48dp targets; no unnecessary captions or duplicated count.

## Engineering gates
Reuse logical store, decide migration schema only after examining catalog assignments/group identity; transactional group membership and exact identity, no content/file operations or whole-folder SAF. Revalidate access/privacy at execution; no private data exposure. Test 6/866, with unrelated Markdown, mixed physical folders, duplicates/aliases, stale selection, canceled operation, repeat, restart/atomic write failure, external removal, restore group/links, previous v1 and legacy .nomedia restoration. Verify ordinary Downloads, Gallery, Music, Favorites, search, Collections, playback guards. Existing 2544 mandatory JVM IDs preserved; final exact-source qualify only after Stage 5; focused local checks per stage, no emulator.

Stage 1 is not complete: owner requested removal of the additional confirmation; remaining implementation, visual details and tests pending. Stage 2 and 3 not started. No production source changes, no APK.
