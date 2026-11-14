# ML-17188 — collections and Gallery (QA-187-01/03/04/07)

Implementation uses the existing public `LibraryMediaRef` projection and identity policy. It does not create a second media index or copy selected media.

- The collection picker is a full-screen state within the existing collections destination. Photo/video cells use the existing thumbnail loader; music uses `MusicLibraryTrackRow`. Search, existing Gallery sorting, indexed-device album filtering, visible-filter select-all, long-press drag selection, and saved typed IDs retain selection across filters and configuration recreation. Existing members cannot be selected again. A mixed media/link write is a single Room transaction. Save validates current source access and leaves selection in place on error.
- Music auditions use a disposable `MediaPlayer` with transient audio focus. They never send commands to the current music queue. Leaving the screen, stopping the activity, or completing a preview releases the player and focus. Video preview is released on dismissal.
- Saved links reuse stored URLs, platform hostnames, and any matching already indexed source metadata. The existing schema contains no separate persisted link-title/preview record; the URL remains the fallback. No new provider/GAPI requests are introduced.
- Cards share a 4:5 cover viewport, accent title, and a single total count. The Gallery strip gives each card half the available width, creates a dashed neighbor for a single collection, and scrolls for three or more. The full collection list is a two-column grid. Empty type chips are omitted inside a collection.
- The editor stores EXIF-oriented crop center and zoom in normalized coordinates, with pan, pinch zoom, thirds grid, fill, reset, automatic cover, and name editing. Preview and JPEG output use the same bounded crop rectangle. Covers are 640×800 service JPEGs under `filesDir/collection-covers`, outside MediaStore. A new image is written and synced before its AtomicFile metadata becomes visible. Cancellation/error removes the uncommitted image; replacement removes the previous committed image after metadata commit. Cancelling before save creates no file. Existing saved cover bytes survive source removal. Deleting a collection in the UI clears its private cover directory after database deletion.
- Quick access and the opened route show “Все файлы”; the existing photo/video selection, identity, count, filters, icon, and privacy are preserved.
- Initial loading uses explicit `usable`; freshness still gates media mutations. A privacy change must invalidate `usable` at the producer. An empty, already usable collection list does not show background refresh progress.

## Verification handoff

Agent ran `git diff --check` successfully. Gradle execution is owned by the root integration agent, so no JVM/Compose/lint/build pass is claimed here.

Targeted classes: `CollectionGeometryTest`, `CollectionCoverStoreTest`, `MediaOrganizationRepositoryTest`, `MediaOrganizationScreenTest`, `GalleryQuickAccessTest`, `GalleryAlbumNavigationTest`.

Device acceptance still needs all themes, narrow/landscape/large-font layouts, drag gestures, source permissions/removal, activity recreation while saving, and audio focus behavior with current playback. No release or publishing was performed.
