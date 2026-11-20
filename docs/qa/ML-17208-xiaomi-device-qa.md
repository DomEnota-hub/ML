# MediaLoader 1.7.208 / 268 — Xiaomi Android 16 QA

Status: DEVICE_QA_PENDING. CI/ARM64 compilation and a valid signed installer are not proof of operation on Xiaomi.

## Before installing
- Install **over** 1.7.207; never uninstall or clear ML data/storage. Keep the 612 preliminary duplicate groups, audio fingerprint cache, recordings, old HIDE/SHOW journals and Room history.
- Verify package com.domenota.medialoader, versionName 1.7.208, versionCode 268, arm64-v8a, same DomEnota signing certificate.
- Do not test folder hiding, music trash, or restore against irreplaceable originals. Make disposable copies.

## Duplicates: preserve six hours of work
1. Open Music > Duplicates > Journal > provisional results without launching a new scan. Verify the old **612 groups / 2553 files** appear as *provisional* (not completed), with **4 unavailable** where appropriate. Existing exact snapshot count may differ after real file validation.
2. Results should fill most of the screen, not only the lower third. Open/close **Управление поиском**; keep filters, playback, compare, manual keep choice and stable scroll state.
3. In restore mode, verify **Восстанавливаю сохранённые результаты**, count X/Y of actually verified files, no blank screen/duplicate unlabeled progress. Restoring must not rescan or decode entire library.
4. Scroll far, select groups and keepers, exit/reopen, rotate screen, and kill/restart process; verify separate UI-state writing does not serialize the full scan again.
5. Verify corrupted/removed/changed/public-to-private files become ineligible. The trash action independently verifies a current copy and leaves one verified keeper.
6. On a disposable library verify atomic chunked report storage (up to 100 groups / 2 MiB per part, SHA256 manifest), simulated failed commit recovery, generations, no false COMPLETED. Track heap 512 MiB, UI_STALL, battery/FGS, file counts and memory. Do not rerun the six-hour real library merely to prove the test.

## Folder hide / show (Issue #159)
1. With a disposable folder of 1, 8 and 46 media files each, click **Скрыть медиа**. The old extra **Скрыть медиа?** AlertDialog must not appear.
2. On first use if SAF access is not yet granted, Android must request precisely that physical directory. The initial URI is only a *hint*; **Xiaomi may ignore it**. If it opens the phone root, cancel; root cannot be authorized and ML must leave files untouched. Document the OEM behavior with screenshot/log.
3. If permission is already persistent, hide again without another picker. Test explicit SHOW, revoked permission, denied picker, wrong sibling/parent folder, and activity recreation while picker open.
4. Try a virtual ML folder containing multiple real directories; folder with a foreign media item; mismatched byte sizes; foreign .nomedia; top-level Pictures/DCIM/Music/Download, Android/data, old protected archive; all must fail without hiding unrelated files.
5. No original file may be moved, renamed, overwritten or deleted; only ML-owned zero-byte .nomedia can be removed by SHOW; another app's .nomedia must remain. Library history, favourites, collections, original timestamps, Private and playback must remain intact.
6. Verify system gallery behavior only after actual reindexing. Do not confuse 'excluded in ML' with 'invisible in Xiaomi Gallery'. Export AppLog and check NOMEDIA_SAF stage codes have **no actual physical paths, media filenames, URI or personal identifiers**.

## Regression
- Music starts at track list; three owner-approved launcher/shortcut icons unchanged; light theme purple, dark theme yellow.
- Gallery repeated open/scroll/return, thumbnails, hidden folder filtering, 11k+ items if available.
- Downloads history / Instagram / YouTube / VK / RUTUBE / Muzmo / Shazam / players / editor / notifications / Private / current track.
- Final stable status requires explicit owner hardware QA; this file is not self-certification.

## Required final delivery
- SHA-matched source archive, signed QA APK split into .z01/.zip under 100 MB per volume, README and SHA256SUMS on Drive **Загрузчик/1.7.208**. Verify exact source SHA, original DomEnota signer, Android zipalign, package/ABI and download roundtrip.
- No GitHub Releases/Prereleases or production merge.
