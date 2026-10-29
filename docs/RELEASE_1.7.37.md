# MediaLoader 1.7.37 (99) — Instagram session and profile search

- The Instagram account check retries the plain `current_user` route when the prior `?edit=true` route responds with HTTP 400. Known login and account-challenge responses are identified without exporting response content.
- After an authenticated Instagram page opens in the built-in browser, its full session cookie header is saved for background requests. The Accounts view refreshes its state after returning from the browser or login screen and offers an explicit repeat check.
- Profile scanning tries the authorized feed route if its web GraphQL route reports access required. A refusal from both routes still stops the scan and keeps previous results.
- Diagnostic results that were checked but could not be confirmed use an orange triangle; unchecked results stay gray.

The prior logs showed HTTP 400 from the account probe and `ACCESS_REQUIRED` at the feed stage, while the embedded browser displayed the user's Instagram feed. These requests use different routes and cookie stores. This build fixes their integration and adds clearer diagnostic outcomes. Instagram responses are not under the app's control, so a real session and device test is still needed.

Verification: full Android unit suite and ARM64 release build; signed APK verification and Git bundle backup. The development environment has no connected Android device.
