# 1.7.100 — reported 1.7.99 regressions

User asks for fixes and a signed APK uploaded to the existing Drive folder, with minimal
checks. Main-mode HTML remains paused. No emulator/broad test suite planned.

## Findings and changes

- Cold phone scan previously published only after every row, and fell back to provider
  canonicalize/open for every missing/unstattable path. Without protected physical identities,
  metadata URI/path aliases suffice; no need to open all 10k+ device files. When physical
  private aliases exist the fallback remains to preserve privacy.
- First scan now publishes a first page at 64 rows, then at most once per second, and saves
  partial metadata checkpoints with unknown generation so a restart still reconciles.
  Access/privacy are checked before each publication. Only index-published revisions pass
  the privacy shield; a validated first page need not await the last row.
- App-history identity resolution was part of a mapLatest also driven by phone scan revisions.
  Extracted an independent history pipeline, resolves at most eight provider identities in
  parallel and publishes batches of 16. Phone refreshes cannot repeatedly cancel history work.
  Local/media-provider paths use stat before canonicalize/open. No unvalidated identities
  are shown as final public references. Safe stage timings/counts added for handset diagnosis.
- Downloader public grid previously explicitly disabled gallery_presentation, leaving mixed
  media in the legacy viewer where mediaQueue disabled the photo pager. Public grid snapshots
  now use the existing GalleryMediaViewer with swipe navigation, mixed video, no side arrows.
  Original route IDs resolve through validated public aliases even when device dedup uses another canonical key. Raw history never bypasses the privacy index.
- Removed gallery-local delete dialog. Root provides one generic app-file confirmation;
  phone deletion invokes system consent directly. With special MANAGE_MEDIA access, root
  phone host provides one app confirmation and MediaStore executes without extra consent.
  Group/private selection titles use Russian counts and never print file names.
- Added optional Settings → Data/maintenance → Media management special access entry and
  MANAGE_MEDIA declaration. Correct platform boundary: Android 12/API31+, not API30 as stated
  in the earlier conversation. MANAGE_MEDIA applies to MediaStore media including audio;
  arbitrary SAF/document providers still control their own permissions. No all-files access.
- Version 1.7.100 / 162, arm64. No change to deletion destination: app history uses existing
  trash behavior, device items use existing MediaStore deletion.

## Verification

One combined release command: PhoneMediaLibraryTest, LibraryValidationGateTest,
GridMediaQueueTest, DeleteConfirmationTest + assembleRelease. A final targeted
GalleryViewerStateTest run covers route alias resolution and privacy before packaging. No other suites.
New cases cover inaccessible paths without per-file opens, progressive first page,
validation gate for a published page while scan continues, and Russian count titles.
Result to be recorded after completion. Signature must match 1.7.99 before upload.
Phone behavior remains for user verification; screenshots alone do not establish the
exact handset blocking provider. Do not claim a measured handset speedup.


## Выпущено

- 18 различных целевых проверок прошли: PhoneMediaLibraryTest7,
  GridMediaQueueTest2, DeleteConfirmationTest1, LibraryValidationGateTest3,
  GalleryViewerStateTest5. Промежуточный запуск обнаружил ошибку в новом
  условии незагруженного второго режима; она исправлена, три проверки шлюза
  повторно прошли. Остальные успешные наборы не повторялись.
- Финальная сборка: BUILD SUCCESSFUL in 5m3s, 61 tasks (36 executed,25 up-to-date).
  Лог: /tmp/release-1.7.100-final.log. Начальный лог: /tmp/release-1.7.100.log.
- arm64 APK: /workspace/releases/MediaLoader_1.7.100/MediaLoader_1.7.100.apk
- Размер: 103638079 bytes.
- SHA256: bb760034f7caa2a148eece64a6b98fc15c8223835b24a540cd646611b2f3f8c6
- apksigner verify, zipalign-P16 verification passed; certificate matches 1.7.99.
- Drive: https://drive.google.com/file/d/1A52u9unK6QdHcPqjhlIVhkuOf-L60l7o/view?usp=drivesdk
