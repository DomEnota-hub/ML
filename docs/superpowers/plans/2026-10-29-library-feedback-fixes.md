# Six library feedback fixes

Approved scope: safe mini-player insets on detail screens; cached nonblocking refresh
with permission/private protection; mini-player previous/play/next/close and no stop
dot; playlist icon; reliable Settings return preserving library state; editable
service queue (drag, remove, play-next, append) independent of saved playlists.

Ruling: inline implementation, one bounded verification command for queue/navigation
and release build; no full suite, screenshot runs, or delegated review, per user's
explicit quota limit and willingness to check on phone. Existing linked worktree.

Refresh: use retained validated snapshot for unchanged full permissions; throttle
re-entry refresh, observe MediaStore for background updates. Permission changes and
partial-access revalidation retain blocking privacy gate. Never persist phone lists.
Queue: Media3 in-place timeline commands preserve current position; removing current
track naturally advances. No playlist mutation, media deletion, or private enqueue.

Progress: all six changes implemented; exact scoped tests passed (12 tests, zero
failures/errors): AudioQueuePlaybackTest 9, LibraryReturnNavigationTest 1,
LibraryValidationGateTest 2. Command log: /tmp/library-feedback-checks.log.
Release assembly passed: BUILD SUCCESSFUL in 8m, 83 tasks (31 executed).
Command completed with exit 0; HTML remains paused.

Implementation notes:
- System navigation inset belongs to the entire bottom stack, including album and
  playlist detail screens with no tab switcher. Child switcher has no duplicate inset.
- Mini-player includes cover/title/artist, previous/play/next/close, and custom thin
  progress paint with no Material stop marker.
- Pop the existing Home entry on return from Settings; no restoreState navigation
  that can revive the saved Settings stack. Saveable library state remains intact.
- ViewModel caches successful scans for 60 seconds; MediaStore observers debounce
  updates by 500ms and request background scans. Permissions are checked before
  switching to a cached phone-backed screen. Revocation/partial selected access uses
  the blocking gate; unchanged full access keeps the validated snapshot usable.
- Root refreshPhoneLibraries(force) requests a permission/cache check; only the VM
  force flag bypasses scan freshness (observer/private move). UI updates no longer
  clear a retained list while refreshing it.
- Queue moves/removals use in-place Media3 commands. Next/end moves an existing
  noncurrent entry instead of duplicating it; selecting the current track for enqueue
  is a no-op. No file/playlist mutation. Queue drag has adjacent-row autoscroll and
  explicit Up/Down menu alternatives.
- No UI screenshot/emulator run or broad test suite per user request. Gesture feel,
  system insets, and device-specific permission dialogs remain phone checks.
- Source app version is still 1.7.97; this is a prepared implementation/build, not a
  new published release. Do not overwrite the signed/public 1.7.97 artifact. A new
  release needs the usual version increment and signing when requested.


Final: six approved points implemented. No broad or visual verification added.
No new version uploaded. Existing signed/public 1.7.97 remains unchanged. The new
unsigned prepared APK is app/build/outputs/apk/release/app-release-unsigned.apk.
Before publishing: increment version (next 1.7.98/code160), assemble, sign with the
existing certificate and upload on release instruction; no repeat of scoped tests
is necessary for the version-only change. No device claims or raw logs committed.


## Release 1.7.98 published on user instruction

Version 1.7.98/code160. Version-only change after the verified six-point fix checkpoint
4fcf897. No repeated tests. Arm64 release build passed in 4m11s; signature matches
1.7.97. APK size103621695; checksum and URL in docs/RELEASE_1.7.98.md.
Drive: https://drive.google.com/file/d/1T4YyIuIC3A1LVIRaAGEqhbJc9oDkQOxx/view?usp=drivesdk
This supersedes the earlier awaiting-release state. Signed APK is under
/workspace/releases/MediaLoader_1.7.98/. HTML work remains paused.
