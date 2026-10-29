# MediaLoader 1.7.36 (98) — Instagram authorization diagnostics

- Settings now distinguish a stored Instagram cookie from a server-confirmed account. Only a verified account is shown as connected. Login redirects, access restrictions and network failures remain visible as separate states.
- Profile lookup tries the saved-session route before the public page. A public-page rate limit no longer prevents a valid saved session from being tried.
- The embedded login screen reports a main-page HTTP status or network error, with retry, home-page and settings actions. Diagnostic logs record only status, host and safe failure category; no cookie, username or challenge URL is recorded.
- Profile search logs the failing stage and reason while preserving saved search data.

Instagram returned HTTP 429 for its login page during a read-only network check from this development environment. The status on the user's device was not available in the 1.7.35 logs. This release improves diagnosis and recovery, but it cannot bypass a server-side restriction. A current authenticated session is still needed to test profile pagination.

Verification: full Android unit suite and ARM64 release build, then APK signature and version checks. No adb device was available for an on-device test.
