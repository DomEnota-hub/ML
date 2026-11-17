# ML 1.7.199 / versionCode 259 — exact-guard recovery fix and scanning optimization

Source: QA 1.7.198 exact commit dd7132e3a2ce8e9fe8d37711e207b2c40fdf4358; Xiaomi Android 16 / 24116RACCG, 17 November 2026 19:12.
Two retries of folder “Мелкие”, 0/46, fail at ROOT_GUARD NOMEDIA_NONEMPTY, markerBytes=47, ownerVerified=true.

AOSP MediaProvider FileUtils.setDirectoryDirty(dir,false) writes dir.getPath() into .nomedia.
Fix: accept only the zero-byte sentinel or exact UTF-8 bytes of canonical owned root path.
No symlink following, writing or rewriting .nomedia; fail closed on unrecognized, unreadable, replaced or oversized content. Both root() and read-only matchesReceipt() use a single validator. Journal and original media unchanged.

Optimization: pending recovery now performs a read-only, ownership+marker gate before global HistoricalRecoverySignals.beginFolderVisibility, so a definitively invalid root refuses without spuriously cancelling MediaStore scans and invalidating Gallery/Music cache generations. Once valid, the existing mutation gate, conservative invalidation, privacy checks, cryptographic identity and source deletion barriers remain unchanged. Real moves still refresh the catalog; no weakening of invalidation after potential writes.

Regression coverage: Android MediaProvider clean marker, exact bytes preserved, receipt checks, same-length forged marker, newline, missing root and marker, symlink, unrelated owner and non-destructive failure. Run focused JVM, all JVM with existing Compose isolation, storage integration, Android lint and exact ARM64 release before signing with original DomEnota certificate, original protected GAPI and Drive roundtrip.
Do not claim 46 files recovered until Xiaomi manual QA. No production merge, prerelease, new signing identity or clearing recovery journals.
