# MediaLoader 1.7.39 (101) — browser account corrections

- RUTUBE no longer treats a removed HTTP 404 probe route as proof of sign-out. The account browser records login after its page visibly offers Sign out and saves the same session for downloads. The in-app source browser can capture that session too.
- The Settings row says browser sign-in was completed; this does not assert that a download was verified.
- Instagram's empty modal backdrop recovery also applies to profile pages. The extra account request injected into the browser was removed because it generated HTTP 400 without a usable result.
- Instagram profile scan logs which feed route failed and its safe reason, so the next on-device log can identify the failing request.

Android unit tests and ARM64 release build are run before distribution. The remaining browser behavior requires the user's phone to confirm.
