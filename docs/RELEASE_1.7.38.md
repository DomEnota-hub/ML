# MediaLoader 1.7.38 (100) — account session trial

- The Instagram browser logs a safe HTTP status for an in-browser account request. Compare it with the existing account probe status and a profile scan in the exported app log. No cookies or response bodies are logged.
- RUTUBE browser navigation permits the service's `gid.ru` identity pages. Sign-in completes only after the saved session passes the account probe; unconfirmed temporary files are removed.
- RUTUBE sign-out clears its saved downloader session and its site cookies, so the next sign-in starts fresh. The Settings row distinguishes a verified session from saved but unverified cookies.

Android unit tests and ARM64 release build passed. A real Instagram/RUTUBE account test still requires the user's device.
