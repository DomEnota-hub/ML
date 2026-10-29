# MediaLoader 1.7.35 (97) — Instagram profiles

This trial release adds search of public Instagram profiles with optional dates and a switch for the separate Reels tab. Results group photos and videos by post, support selection in a grid or list, and can be queued through the existing Instagram downloader. Favorites store the profile title, avatar, starting date and Reels choice; checks and a journal preserve scans and selections. A repeated download checks whether completed files are still present.

Verification: `:app:testDebugUnitTest :app:assembleRelease -Parm64Release` passed offline on 2026-10-24. No Android device was available here. Instagram profile pagination uses undocumented endpoints and needs an authenticated on-device test; if Instagram changes its response, the app keeps saved results/cursors and shows an error.

Source: branch `feature/instagram-profile` based on 1.7.34. The signing key is not included in the archive.
