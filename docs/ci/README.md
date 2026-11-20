# MediaLoader CI: quick feedback and exact-source qualification

This CI-only change starts from released1.7.209/269,8f83894082b20eacf63727257617bc1674f1ffef (Draft163). It does not modify application logic, versions, user files or signing credentials. No installation APK is released by these workflows.

## Measured baseline

Source artifacts44753902351 and44753902352 were downloaded and their canonical XML compared:2246 unique JVM identities,40 isolated Compose methods; all104 storage cases are already inside the full set. Known opt-in Shazam skip is explicit. Required identities are pinned in required-jvm-tests.json. Workflow trigger inventory covers153 files; most historical workflows are manual/branch-specific or skip this PR, not expensive duplicates.

| Baseline run | Result | Active runner duration | Evidence |
|---|---|---:|---|
|44753902351|success, exact8f838940|26m37s|focused+initial compile5m01s; full9m10s; lint+ARM6411m44s|
|44753902352|success, same SHA, parallel storage|15m53s|104 repeated cases, repeated lint and ARM64|
|44742075785|failure|22m08s|NewApi: createDeleteRequest API30 with minSDK26 found only after JVM|
|44745437669 attempt2|success|22m32s|previous source, not final8f838940|
|44752625791|cancelled|8m11s|new HEAD superseded in-flight work|

The final pair consumed42m30s runner time; wall time was about26m38s, not their sum. JSON retains queue/step timestamps. Baseline setup-java restored dependencies; a cache hit did not remove the repeated execution. Exact checkout assertion exists in final44753902351; earlier run API head_sha alone is not proof of actual checkout. Where older runs lack Gradle profiling, configuration/resolve/compile separation is unknown, not fabricated. New diagnostics preserve Gradle HTML profiles, stage and per-shard wall timings; parallel task durations must not be summed as wall time. Actual before/after results and final source SHA are recorded in this Draft PR and the current Drive Summary after CI, so reporting them does not change the qualified source.

## Commands

All commands below target an explicitly reviewed branch, never implicit main:

```sh
# Normal PR open/synchronize: guards, CI-helper tests, all test-source compilation,
# then the same18-class focused suite. No qualification receipt or installable QA.

gh workflow run ml-ci.yml --repo DomEnota-hub/ML --ref ci/ml-pipeline-optimization \
  -f mode=qualify -f fault=none -f cache_epoch=ci-audit-v1
```

Alternatively label the PR `ci:qualify` to qualify the PR's exact head; remove it before iterative commits to return to quick feedback. An unrelated label does not cancel an active qualification. Manual dispatch uses the exact selected branch SHA. A source push cancels superseded normal work under the same branch concurrency group.

Qualification runs JVM, full lint and ARM64 on independent runners. Compilation failures surface in the JVM lane first; API lint does not wait behind tests. All2246 baseline cases remain required; extra cases may pass, missing/duplicate/failing/newly skipped cases reject. Forty incompatible UI methods stay in separate invocations/workers. Test-result cache/up-to-date reuse is disabled for Test tasks; compilation cache stays enabled. The gate requires41 actual Test execution markers, not merely restored XML. Failed bulk stops with an explicitly incomplete red manifest rather than spending another40 workers after failure.

## Cache and infrastructure

Java17, Gradle8.9 with existing distribution SHA256, AGP8.7.3/Kotlin2.0.21 and SDK/build-tools35 remain pinned. A temporary wrapper copy changes only its download mirror/timeout; tracked source stays clean. No lockfiles or dependency-verification metadata existed in this baseline; dependency locking/configuration cache are not added without compatibility work. Dynamic dependency changes still require review. Cold/warm comparison means a new cache_epoch for the first run, same epoch and same source for the second; cache-hit logs are evidence, not an assumption about OS caches.

Caches are lane/epoch/Gradle-input/source keyed, with same-lane/config/epoch fallback. They contain Gradle dependencies/transforms/local build outputs plus Robolectric dependencies. They do not contain project workspaces, private GAPI assets, keystores, cookies, decoded user media or signed APKs. Protected input presence is rejected before cache access/build. No credential-bearing local build is performed here. Fork PRs do not save or restore these caches and cannot produce signing handoffs. Configuration cache remains OFF. Immutable cache keys and Gradle task fingerprints enforce source invalidation; change cache_epoch to discard suspected cache contamination.

A transport failure may retry once with explicit10s backoff only when dependency-resolution/HTTP429/5xx/timeout evidence exists and no test execution, compiler diagnostic, lint error or checksum failure appears. Functional failures never retry. XML/report cleanup and coverage validation remain strict. Every attempt and its exit appears in ci-output timings. Controlled transport-then-success and transport+compiler-failure tests exercise this classifier/runner. An actual provider outage cannot be guaranteed on demand; distinguish simulation from real observations.

## Required gate, resume and signing handoff

- quick, jvm, lint and arm64 must all succeed on the same exact source. Each lane emits a SHA/run/attempt receipt only after its checks pass. Retained successful receipts from an earlier attempt of the SAME run/SHA may be reused by `gh run rerun RUN_ID --failed`; future attempts, other runs/SHAs or missing lanes fail. Candidate retention5days exceeds receipt3days; resume within48h, otherwise run a new qualification.
- An ARM64 artifact named UNQUALIFIED contains unsigned bytes, package/version/ABI metadata, exact `git archive` source ZIP and hashes. It is internal build evidence, not approval to sign or install. Diagnostics never duplicate the APK.
- The final job verifies all receipts, original-source ZIP comment, every file hash, and the current branch/PR head after expensive verification. Only then uploads a tiny QUALIFIED-receipt artifact; it does not duplicate the large APK. Cancellation inhibits publication. Because HEAD can change immediately after any check, a receipt MUST be consumed only after the whole workflow completed SUCCESS and after checking live HEAD again. A cancelled/stale run is never sufficient even if a tiny artifact exists.

After downloading the receipt and its referenced UNQUALIFIED candidate into separate local directories, verify immediately before any separately authorized signing:

```sh
python3 scripts/ci/gate.py consume /path/receipt/QUALIFIED.json /path/candidate
```

This reads GitHub metadata (read-only `gh` auth or CI's read-only token), compares live source and successful run, and checks candidate/source hashes. Signing remains outside this task: original DomEnota certificate SHA256 `4f6f0ddf4d8fd2669fd2a3a6ebab6e84bda17281a3a5badada0e1eeb73a4f1d2`, v2/v3, package `com.domenota.medialoader`, correct version/ARM64, protected local service inputs and final signed hash remain required. No original private signing key or service credential is accessed here. Google Drive delivery requires a separately authorized QA release; no GitHub Releases/prereleases or production merges.

## Negative validation and measurement

```sh
gh workflow run ml-ci.yml --repo DomEnota-hub/ML --ref ci/ml-pipeline-optimization \
  -f mode=qualify -f fault=compile -f cache_epoch=ci-audit-v1
# Repeat with fault=lint and fault=test. quick+fault is invalid and fails preflight.
```

Probe files exist only in disposable runner workspaces and are never committed. ARM64/signing handoff is disabled for probe runs. A red aggregate alone DOES NOT prove the probe: acceptance must inspect the intended compiler/lint/test step and expected diagnostic (`CiCompileFault`, `CiLintFault` NewApi, or `CiTestFault.qualificationMustRejectFailure`). Failed probe results and absence of qualified artifact are recorded separately. Never retry a functional failure into green.

Run two successful qualifications on an unchanged final SHA with the same new cache epoch; compare actual queue/critical path, runner-minutes, setup/cache, compile, full bulk/40isolated, lint, ARM64 and artifact stages. Inspect XML zero missing/duplicate, known skip,41 workers and APK/source metadata. JVM worker startup and unchanged test bodies are retained, not merged for a speed number. Device QA, full signing/delivery timing and real network outages are distinct evidence and must not be claimed from these CI measurements.

## Rollback

Keep this Draft unmerged until evidence is accepted. The old sequential workflow is retained as manual-dispatch-only for historical comparison; the storage workflow skips managed branches because its test and guard coverage is retained. Revert this CI-only commit range to restore the original automatic hooks, without changing app data or release branches. Do not edit the required test inventory to hide a regression. Existing historical manual workflows and their unique checks remain intact.
