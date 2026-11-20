#!/usr/bin/env bash
set -euo pipefail
grep -Eq 'versionCode 269$' app/build.gradle
grep -Fq "versionName '1.7.209'" app/build.gradle
grep -Fq 'showingPartial' app/src/main/java/com/domenota/medialoader/ui/screens/music/MusicDuplicatesDialog.kt
grep -Fq 'android:largeHeap="true"' app/src/main/AndroidManifest.xml
grep -Fq 'mutableIntStateOf(0)' app/src/main/java/com/domenota/medialoader/ui/MediaLoaderApp.kt
test -s app/src/main/res/drawable-nodpi/ml_launcher_artwork.jpg
test -s app/src/main/res/drawable-nodpi/ml_shortcut_gallery.jpg
test -s app/src/main/res/drawable-nodpi/ml_shortcut_music.jpg
grep -Fq 'MusicDuplicateSessionStore.saveInteraction(context,saved' app/src/main/java/com/domenota/medialoader/ui/screens/music/MusicDuplicatesDialog.kt
grep -Fq 'MusicDuplicateShardStore.commit(context,session)' app/src/main/java/com/domenota/medialoader/data/library/MusicDuplicateSession.kt
grep -Fq 'duplicate-restore-progress' app/src/main/java/com/domenota/medialoader/ui/screens/music/DuplicateResultPresentation.kt
grep -Fq 'MusicDuplicateScanner.verifyMember' app/src/main/java/com/domenota/medialoader/ui/screens/music/MusicDuplicatesDialog.kt
grep -Fq 'NomediaSafPickerPolicy.suggestedInitialDocument' app/src/main/java/com/domenota/medialoader/ui/MediaLoaderApp.kt
grep -Fq 'NomediaSafPickerPolicy.isExactlyRequestedTree' app/src/main/java/com/domenota/medialoader/ui/MediaLoaderApp.kt
if grep -Fq 'nomediaRequest' app/src/main/java/com/domenota/medialoader/ui/MediaLoaderApp.kt; then echo "Forbidden source pattern" >&2; exit 1; fi
grep -Fq 'VaultScreen' app/src/main/java/com/domenota/medialoader/ui/MediaLoaderApp.kt
grep -Fq 'VaultImportPhase.FULLY_HIDDEN' app/src/main/java/com/domenota/medialoader/data/privatevault/VaultOperations.kt
grep -Fq 'selectedIds' app/src/main/java/com/domenota/medialoader/ui/screens/PrivateScreen.kt
grep -Fq 'persistDuration(task.id, result.savedUri)' app/src/main/java/com/domenota/medialoader/data/MediaRepository.kt
grep -Fq 'fillMissingDurationWhenCurrent' app/src/main/java/com/domenota/medialoader/core/database/HistoryDatabase.kt
python3 scripts/audit_ui_palette.py
python3 scripts/check-round-sliders.py
python3 scripts/check-in-app-notices.py

grep -q 'MediaStore.Images.Media.EXTERNAL_CONTENT_URI' app/src/main/java/com/domenota/medialoader/data/storage/StorageManager.kt
grep -q 'MediaStore.Video.Media.EXTERNAL_CONTENT_URI' app/src/main/java/com/domenota/medialoader/data/storage/StorageManager.kt
grep -q 'moveSavedUriWhenCurrent' app/src/main/java/com/domenota/medialoader/core/database/HistoryDatabase.kt
if grep -q 'moveSavedUriWhenCurrent' app/src/main/java/com/domenota/medialoader/data/storage/LegacyMediaMigration.kt; then echo "Forbidden source pattern" >&2; exit 1; fi
grep -q 'storageMigrationRunning' app/src/main/java/com/domenota/medialoader/ui/screens/SettingsScreen.kt
