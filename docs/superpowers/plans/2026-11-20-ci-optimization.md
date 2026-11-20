# MediaLoader CI Optimization Implementation Plan

> For agentic workers: use superpowers:executing-plans to implement task by task.

**Goal:** Shorten measured qualification time without losing any existing check.
**Architecture:** Cheap PR feedback, explicit exact-HEAD qualification with independent JVM, lint and ARM64 lanes and a single all-success handoff gate. Keep all 40 UI methods isolated. Cache only credential-free CI build/dependency data with explicit epoch/lane keys.
**Tech Stack:** GitHub Actions, Gradle8.9/AGP8.7.3/Kotlin2.0.21/Java17/SDK35, Python3.
**Spec:** Drive document Для Codex, id1yck82vLJvRuFv_mDVD_xybXVrPiSSPi2UOodxDNQvVw.

## Global Constraints
- No business logic or user-file changes; no production merge, signing credentials or installable release.
- All2246 baseline identities required, only existing explicit external opt-in skip allowed; zero missing/duplicate/failure.
- SourceSHA must match event HEAD in every lane and all qualification receipts; no handoff after failure/cancel.
- No functional-test retries, ignored tests or relaxed timeouts. No configuration cache until compatibility proven.

## Review Focus
- PR HEAD replacement/cancellation cannot combine receipts from different SHAs/runs.
- Missing/duplicate/newly skipped test or lost isolated worker report fails qualification.
- Compiler, API lint and test failure each blocks handoff.
- Network retry only before tests and only for evidenced dependency infrastructure failures.
- Forks receive no credentials; caches/artifacts contain no protected GAPI/keystore inputs.

## Measured baseline
Exact8f838940: main44753902351=1597s (focused301s, full550s, lint+ARM64704s); parallel storage44753902352=953s. Combined42m30s runner time, not42m30s wall time. Storage104/104 test identities included in2246 main identities. Forty isolated tests remain separate workers. Previous44742075785 failed NewApi after22m; no test result should be discarded by retrying such failure. 153 workflow trigger inventory saved in docs/ci/workflow-inventory.json; most historical workflows do not execute for the current PR.

## Tasks
- [x] Establish exact base, currentPR, run/step timing and XML coverage baseline.
- [x] Add tested CI helpers: stage timings/infra classification, required-case comparison, receipts/handoff verification and deterministic fault probes.
- [x] Replace current sequential workflow with quick PR checks and explicit parallel qualification; preserve all source/storage guards; remove proven duplicate storage lane only for managed branches.
- [ ] Verify helper negative cases locally; run exact-source cold/warm qualification plus compiler/lint/test negative probes. Keep actual logs/results and compare times/runner minutes.
- [ ] Document commands, cache invalidation, resume/rollback, all gate evidence, Draft PR and current Drive Summary. No installation APK delivery.
