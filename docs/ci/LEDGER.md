# CI audit ledger — 2026-11-20-ci-optimization

Baseline complete at8f838940:2246 required identities,40 isolated,104 storage subset.
Ruling: retain existing isolation and avoid test sharding in first optimization; dominant proven duplication/serialization can be removed without changing test workers.
Ruling: configuration cache remains off; no compatibility proof. CI-only local build cache with no protected inputs, lane/epoch/config/source keys.
Ruling: exact same-source successful lane receipts may survive rerun-failed-jobs in the same run, but never future attempts, other runs or other SHAs; actual GitHub needs statuses must also pass.
Plan interface review: source identity and case manifest feed the gate; no application business APIs changed.
Helpers RED(import absent) then GREEN3/3; missing/duplicate/skipped/failed cases and mismatched receipts covered.

Final review: independent fresh reviewer reported cached test-result reuse, stale/cancelled receipt consumption, quick fault bypass, ignored-label cancellation, ineffective negated shell guards, package/signature verification, and retention mismatch. One fix pass completed with failing regression evidence then12/12 GREEN and actionlint1.7.7 PASS. Real negative runs must additionally prove their specific target diagnostic; aggregate red alone is insufficient.
Ruling: publish only a tiny qualified receipt; candidate remains explicitly unsigned/unqualified until successful-run+live-HEAD+byte validation at consumption. A live branch update can occur after any upload, so an artifact name alone is never authority to sign.
Ruling: retain manual historical workflow for rollback; remove its automatic hooks and only exclude proven managed storage duplicate branches. No blanket deletion of153historical workflows.
Source implementation complete; exact-SHA cold/warm and negative CI acceptance pending. CI results are recorded externally in PR/current Drive Summary so result documentation does not change the measured sourceSHA.
