# QA-226 / 1.7.227 — Stage 1 implementation checkpoint

24.11.2026 MSK. STAGE_1_IMPLEMENTATION_IN_PROGRESS / TARGETED_ANDROID_TESTS_PENDING / NOT_STABLE. Cycle 1 not completed; stages 2/3 not started. All QA items remain open for acceptance.

Base c27fef7034d49a938194b6497a0b0ca5262bfdc0 (1.7.226/286), new code HEAD d7c324dd061d90890ff3d878459cff0a750b8782, feature/ml-17227-qa226. Working version 1.7.227/287. Main and prior PRs unchanged; no CI or APK.

## Implemented
- Extend SimpleArchiveRecord and existing AtomicFile store with optional group kind/key/name, compatible with v1 records without group. Strict transaction-wide identity/size/type conflicts, including unknown-size aliases; retain existing restore/legacy paths.
- Direct “В архив” in publication menu and virtual folder menus (Downloads/Gallery); root resolves exact public selection, fresh availability/visibility checks, checks selected identities and folder membership again before storing, deduplicated count. No confirmation for logical archive per owner decision. Archive renders expandable named groups with exact member selection and partial restore.
- New physical hiding blocked at root; legacy restoration retained. Archive preview/name display guarded by privacy readiness and live identity; unresolved metadata redacted. Existing public archive filters reused.
- Existing 2544 test IDs and statuses unchanged, eight new required IDs, total 2552, one known skip. No old tests removed. New tests cover group membership, alias conflicts, six of 866, real file integrity/restart, old v1, failed batch, direct menu and private group exclusion.

## Evidence and limits
- Exact policy Git blob 4156918c2e53b1441cb7bb17d235463b079c480a copied locally and verified with git hash-object. Standalone Kotlin check (MediaType stub, independent assertions) 10/10 PASS. Includes 6/866 logical membership, 860 unaffected, partial restoration, repetition, same URI conflict, unknown-size conflict. Not Android/Robolectric, not a physical-media integrity result.
- Static connector checks passed: current callback wiring, root physical gate, archive group membership, live preview guard, v1 read/AtomicFile write/rollback calls, version; manifest compared against exact base: 0 removed/changed old IDs. Cumulative diff reviewed at file level.
- Actual Android/Compose/Robolectric execution not available in local environment (no Android SDK/Gradle/source checkout). Existing workflow disallows intermediate Android CI until agreed scope complete; no change to workflow. Newly written store and UI tests are not executed. Need validate integrated source, public route exclusions/aliases, group/folder UI and legacy recovery, permission changes/cancellation/external file removal, exact selection with filtered/partial groups, and ensure no stale public snapshot. New notice currently displays count, not an “Открыть” action; decide/validate with existing notice component. No claims of real Xiaomi performance or data integrity.
- Do not advance stages 2/3 or mark Stage 1 complete until remaining targeted validation is resolved. Stage 2 visual mockups still require prior agreement. No merge, CI labels, release or background execution.

Next: finish Stage 1 focused validation and review remaining integration concerns; record terminal stage status in original Drive summary before moving on.

## VERIFY recovery (24.11.2026)
- Source 376ed205eb883d3180afeada4969fb5ea061694a; refactored SimpleArchiveSession/root delegation fixed MethodTooLargeException. Run 45466830628: Quick/compile PASS; full JVM 2552 IDs, 2551 passed, 1 approved Shazam skip, 37 isolated methods, 38 worker markers. Lint attempt 1 job 138703984511 and attempt 2 job 138713018066 both failed before source analysis with Maven Central HTTP 429 on Gradle classpath, including internal transport retry. No APK. Not a lint code failure, no lint PASS. No further identical retries.
- JVM saved a successful Gradle cache for the same inputs hash; lint had cache misses on both attempts. Proposed controlled cross-lane fallback and profile quick verification. Stage 1 remains open until the new exact-source checks and risk review. Stages 2/3 not started. No merge or stable.
