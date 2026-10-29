# Active Diagnostics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make diagnostic states truthful during a run and add an explicit full mode that exercises real local and source functions.

**Architecture:** Keep the existing coordinator and persisted finding contract. Add full-mode probes through existing repository/runtime adapters, and a pure presentation policy for current-run progress. Preserve the light probes as the fast mode.

**Tech Stack:** Kotlin, Android, Jetpack Compose, JUnit, Gradle 8.9.

**Spec:** `docs/DIAGNOSTICS_ACTIVE_SPEC.md`

## Global Constraints

- Base is 1.7.7 (69) ARM64 source; do not update version until release is requested.
- Never cancel or alter the user's active downloads when diagnostics stop.
- Do not export links, cookies, queries or local file paths.

## Review Focus

- Running checks whose timestamps are later than the last UI clock refresh.
- Cancellation during a blocked HTTP call.
- Missing control links or search term in full mode.
- Prior saved report decoded after new check definitions are added.
- Anonymous 403 with successful real search or extraction.

---

### Task 1: Correct running presentation

**Files:** `DiagnosticPresentation.kt`, `DiagnosticsScreen.kt`, `DiagnosticPresentationTest.kt`.

**Interfaces:** Pure presentation helper consumes specs, current findings, current code and run state; produces per-row display state and progress counts.

- [ ] Write tests for future-clock findings, one active row, pending rows, and separate confirmed counts.
- [ ] Run tests and observe expected failures.
- [ ] Implement pure policy and wire the screen, including colour and progress copy.
- [ ] Run targeted tests and commit.

### Task 2: Correct evidence semantics and network bounds

**Files:** `SourceDiagnosticRules.kt`, `SourceDiagnosticProbes.kt`, `FaultJournal.kt`, tests.

**Interfaces:** Anonymous reachability is an observation, not a full-source fault; network transport has bounded cancellation.

- [ ] Write tests for 3xx/403/timeout reporting and fault journal behavior.
- [ ] Run tests and observe expected failures.
- [ ] Implement the rules and transport timeout behavior.
- [ ] Run targeted tests and commit.

### Task 3: Add real local checks in full mode

**Files:** `LocalDiagnosticProbes.kt`, a dedicated active probe adapter, mode contract, tests.

**Interfaces:** Full mode includes bounded runtime startup, storage write and service/queue observation; fast mode retains read-only checks.

- [ ] Write tests for full-mode probe registration, unavailable runtime and temporary file cleanup.
- [ ] Run tests and observe expected failures.
- [ ] Implement bounded active local probes using production runtime APIs where possible.
- [ ] Run targeted tests and commit.

### Task 4: Add source workflow checks and full-mode UI

**Files:** `SourceDiagnosticProbes.kt`, `MediaDiagnosticRegistry.kt`, `DiagnosticsScreen.kt`, `SafeDiagnosticReport.kt`, tests.

**Interfaces:** Full mode accepts user control links/query, calls real provider resolve or music search, reports missing input explicitly, and does not include input in export.

- [ ] Write tests for absent inputs, success/failure classification and safe report output.
- [ ] Run tests and observe expected failures.
- [ ] Implement adapters, inputs and explicit full-mode launch.
- [ ] Run targeted tests and commit.

### Task 5: Verify integration

**Files:** All modified files and docs.

- [ ] Run all JVM tests, lint and build.
- [ ] Inspect changed code and output against spec acceptance list.
- [ ] Commit any verified fixes and report device-only checks separately.
