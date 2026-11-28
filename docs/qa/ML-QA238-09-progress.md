# QA238-09 continuation checkpoint — 28 November 2026

Last delivered1.7.239/299, delivered SHA c2a61fce5f8d8c623696f64666e833549d2100d5. Current branch research/ml-ci-optimization-20261126, PR200 draft, DO NOT MERGE. No new version, APK or device acceptance.

## Confirmed device feedback diagnosis
MediaLoader_1.7.239_logs.md: 08:22:14 OCR10843/10844;08:22:29 private move;08:22:56 OCR0/10844. Root cause: DuplicatePrivacyGuard.revoke unconditionally deleted the whole gallery-ocr database. Updated code retains only physically proven unrelated rows, hashes lexical aliases and dev:ino anchors, removes related/unknown/corrupt records under the existing lock with secure_delete. Exact legacy catalog matches gain identity metadata without recognition. Already deleted records cannot be recovered by this patch. Unknown physical identity can still require conservative invalidation. Journal's generic-explanation statistic removed, all filters/export retained.

## Verification checkpoint
CI311 on4f2d4d1dc03b528debc311a72db3cf870ff09ebb: compile PASS; bulk984 executed,3fail in PrivateScreenUiTest (obsolete wording). GalleryOcrIndexTest10/10 PASS. Failed assertions at85/116/185 seek «Введите PIN из6цифр», while existing PrivateAccessGate has «PIN из6цифр». Prepared correction changes only four text expectations, preserving wrong/correct PIN, relock, hidden filename, fresh authorization and remembered-session checks. Lint311 terminal SUCCESS at12:28MSK; run311 terminal FAILURE because of the three stale test assertions. Rerun privacy after the confirmed fix, plus diagnostics for journal.

## Research checkpoint
Model224721181bytes SHAceb61454e2e1a2dec5872cbac1de0fe0a4271d1148f6b26b5bda53ff30a12acd independently matches HF; fixed revision322c3b29492673eb7d0b434bfa9dfb8653e34d02. Exact wheel/filter provenance and MIT notices verified. Linux ORT1.20.0 loads/executes graph; not an ARM64/device measurement.

Ready GTSinger Russian word references enabled a new independent short-vocal probe (not a rerun of candidate comparison):20clips,13song names,5techniques,1singer,149words. Diagnostic forced-path onset median100ms,p90178ms,148/149within500ms. Existing strict gate accepts2/20;20wrong texts rejected. No calibrated confidence or full-mix/genre/long-track/Android acceptance. Model remains NOT_READY_FOR_INTEGRATION. Original words/recordings absent from repository; source hashes and numeric results retained in research report, reproducible probe under scripts/research.

Next research input: exact full-mix recording plus already verified matching timed lyrics/LRC, preferably several genres and repeated/instrumental passages; no owner manual marking. The lost original64-line text is useful for rescoring the prior rock pilot but cannot provide reference times. Keep audio/text entirely local. No integration/release claim until research gates pass.
