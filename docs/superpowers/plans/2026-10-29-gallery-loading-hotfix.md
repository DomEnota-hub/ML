# Gallery loading hotfix after 1.7.96

## Report and scope

User reports app-library content not appearing, then phone gallery remaining behind a
full-screen spinner with no way to open diagnostics. Screenshot also shows the
Media/Albums switcher under the Android three-button navigation bar. Main-mode HTML
redesign is paused. User requests minimal targeted checks.

## Evidence from code (device log unavailable)

- `LibraryViewModel.index` used PublicMediaIdentity.capture for every phone row;
  each capture canonicalizes a content URI and opens a descriptor for fstat.
  With the previously observed ~12k-image library this is tens of thousands of
  provider round trips before validation can release the opaque root shield.
- PhoneMediaLibrary performed a permission query per output row after scanning.
- Active download progress emissions could cancel/restart mapLatest indexing.
- The shield intercepted the full navigation content without an exit action.
- LibraryBottomSwitcher used a fixed 10dp bottom gap without navigation-bar insets.

This identifies concrete excessive work and a navigation trap. It does not establish
an exact on-device ANR/exception cause without the handset log.

## Changes

- Batch-read MediaStore `_data` with the existing metadata cursor. Derive physical
  inode/size/mtime aliases using stat, preserving matching with private/SAF sources.
  Only missing/inaccessible path metadata falls back to the original descriptor path.
- Carry the resulting identities in the same scan snapshot/revision as its rows;
  the unified index reuses them. No stale identities are published after revocation.
- Recheck permissions by collection, not per row. Exclude active download progress
  from index input; keep completed entries, alias filtering, privacy validation.
- Make the shared cache thread safe across cancellation of IO indexing work.
- Keep the shield and editor state, add an explicit exit and Back action on library
  home. Downloader home and Settings remain usable while validation continues.
- Apply navigationBarsPadding to the shared Gallery/Music bottom switcher.

## Verification

Targeted command: PhoneMediaLibraryTest, UnifiedMediaLibraryTest,
LibraryValidationGateTest, LibraryRefreshShieldTest. Final run: BUILD SUCCESSFUL in
1m20s, 13 tests, zero failures/errors/skips (`/tmp/gallery-hotfix-tests.log`).
A new test checks physical alias continuity without per-file provider opens; another
checks that the loading exit is usable while stale metadata remains hidden.
First attempt caught the new API missing; first combined run exposed a JUnit fixture
return type (Boolean instead of Unit), corrected before rerun. The next run exposed
Robolectric Linux.stat/fstat always returning inode zero; the new physical-identity
fixture now supplies realistic OS stat metadata while testing real scanner/provider
interaction. No full-suite rerun.
No handset or emulator is available; on-device gallery timing and actual system-bar
placement still require installation verification. No diagnostic data committed.

Release build: BUILD SUCCESSFUL in 4m8s; version 1.7.97/code159, arm64. Signed APK
certificate matches 1.7.96. See docs/RELEASE_1.7.97.md for hash and delivery URL.
