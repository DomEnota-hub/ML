# CI isolation A/B acceptance evidence

Exact source 4b8897a4b96aa073ca8690b5887e15f8a0771b7d. Two independently completed successful runs: #44945855009 and #44945854128. Both diagnostics downloaded and SHA256/CRC independently verified:
- #14724167450 414214eec6ca339928eab58a6c15966924a110ad51bc204b51becaf9cb991e2c
- #14723464564 6555cb743259589c5788583a0a21734764d9ebcf76eadffb03c05f2035528587

Each run: 37 legacy and 37 batched XML identities exactly equal; all PASS, zero errors/skips/duplicates. Normal regression 6 PASS (4 moved pure tests + 2 DB cases), PERF 2 PASS. Legacy vs batch elapsed seconds 256.673 -> 226.389 (11.8%), 247.644 -> 211.733 (14.5%). DB normal vs PERF testcase seconds 8.128 vs 39.953, 9.070 vs 40.132. Two separate measurements, not total CI runtime.

Adopt the identical proven Gradle batch task construction in scripts/run-jvm-ci.py, with one separate bulk invocation. Exact worker markers and merged 2359 reviewed identities required, no retry/fallback after test execution. VERIFY full JVM + lint with no APK, no qualification receipt. Old runner/audit retained only pending end-to-end VERIFY acceptance, then retire old workflow/archive and obsolete modes as agreed. No application source edits.

