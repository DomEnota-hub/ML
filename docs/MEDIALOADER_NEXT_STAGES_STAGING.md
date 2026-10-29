# MediaLoader — implementation progress (staging)

Source baseline: `release/1.6.17-source` at `7bc39e054f8e0db12960da36943eb80fb099477a` (version 1.6.17 / code 57). Original Codex route/CDN/music artwork fixes must be preserved; changes are integrated in `feature/medialoader-1.6.18-integrated-from-1.6.17`.

## Stage 1: separate history and trash (implemented in staging)
- Room query `clearHistory()` is limited to `hidden=0` and terminal download states.
- Repository no longer deletes trash bytes when clearing history.
- Trash cleanup operates on explicit row IDs and deletes metadata only after successful physical removal.
- Failed physical deletion preserves the row and records an error; non-completed metadata-only trash entries are removable.
- Confirmation text explicitly says that physical files and trash remain unchanged.
- `removeHidden(ids)` supports future selected deletion in full-screen trash UI.

## Stage 2: full-screen trash UI (implemented in staging)
- New `TrashScreen.kt`: full-screen scrollable list, circular multiselect, select all, restore, selected/permanent removal and always-accessible clear-all with confirmations.
- Existing `MediaViewerScreen` gains `trashReadOnly` mode: hides editor, share, external-open actions; adds restore/remove controls with confirmation.
- Viewer is fed app-private trash URIs; it never restores the physical file just to view it.
- History and trash routes are distinct, trash is excluded from bottom navigation.
- On-device tests of 80+ files and preview formats remain outstanding.

## Stage 3: settings colors / source captions (implemented in staging)
- Music source picker is now in the Music group (as installed v1.6.17 screenshot), with only «Основной источник»/«Резервный источник» visible, leaving stored IDs untouched.
- Distinct agreed icon colors applied; mass download is coral, music teal; About remains gray.
- Only icon tones/subtitle/placement changed. Card geometry and purple toggles are unchanged.
- UI snapshot review remains needed for light theme and unavailable actions.

## Stage 4: targeted observability (implemented in staging)
- File open/close, successful rename, trash move, restore and delete events added (IDs, not raw URLs).
- Source browsers now report navigation, page lifecycle, HTTP/network errors and safe JavaScript console diagnostics.
- HitMoz adds JavaScript console diagnostics; Muzmo console now omits arbitrary third-party messages to avoid leaking secrets.
- Full Chrome DevTools Protocol network-response bodies are not available via standard WebView APIs; no claim of complete response capture.
- Device verification and logging performance tests remain pending.

## Stage 5: music source and search audit
Open: confirm timeout handling and isolation against exact 1.6.17 source; do not change successful music implementations on outdated staging base.

## Stage 6: remaining multimedia bugs
Open: reproduce metadata issue #23, thumbnail HTTP 403 only if still present, and regression test extraction to MP3.

## Stage 7: release
Original 1.6.17 sources recovered and staged integration begun. New CI and on-device tests are required before signed release.

## Source reconciliation / 1.6.18 build
- `release/1.6.17-source` versionCode 57 is immutable integration base; Codex's 3 source commits are retained.
- Integrated branch sets versionCode 58 / versionName 1.6.18; ARM64 CI triggered only on this branch.
- Tests and Android device acceptance still required before release. No photo inpainting work is included.
