# ML 1.7.227 (287) — QA226 stage 3 navigation checkpoint

25.11.2026 MSK. QA226-08 technical PASS; device acceptance pending. CI-only draft PR #195 must not merge.

- Exact source: b03c43161dc21ce9ad4b152f73787951c9487f20; VERIFY #212 [run](https://github.com/DomEnota-hub/ML/actions/runs/45512003565), terminal success.
- Drawer admission scoped to actual active back-stack entry. Allow Archive, Favorites, authorized Private, Collections, music duplicates and settings in Music mode. Existing blocked routes retained. Privacy context must be unlocked; lock before departure from Private without disabling FLAG_SECURE. Left private restore gesture does not consume rightward drawer gesture. Block selection, dialogs, operations and nested detail states. Save duplicate report interaction before navigation; failed save prevents departure.
- New route admission and exit guard tests; existing private PIN test verifies admission/revocation. 2559 unique cases, 2558 pass, one preauthorized Shazam live integration skip, zero errors/failures. 37 isolated methods, 38 actual workers, manifest errors=[].
- JVM artifact 14976232372 SHA256 6f1374fb3e05d6092977191dd65801c9995f2f08956480c76c2f6e94fdb1be9a, CRC PASS.
- Lint artifact 14975383767 SHA256 5f6c7dcdedf772879a1b2bfd50d8d7346f07def717c97fb0f7fc1ca991e3ea96, CRC PASS; 0 errors / 103 warning / 33 information.
- No APK. Physical acceptance of gestures, lock/re-entry and all approved routes remains mandatory. Next: stage 4 QA226-09/13. Detailed diagnostics required before speculative fixes. No merge.
