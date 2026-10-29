# Media preview before download

Approved UX: tapping a thumbnail opens the viewer; a separate check control changes download
selection. Photos support swipe and zoom; audio/video support playback with seeking and sound.
Preview must not create downloads/history. Closing restores the picker and its selections.

Architecture: retain yt-dlp stream URLs, protocol and per-track headers in transient StreamInfo.
Provider extractor wrappers retain the last analyzed result for five minutes, used only by preview
(explicit analyze still refreshes). A pure policy chooses compatible audio/muxed/separate tracks.
Repository resolves item IDs to ephemeral playback descriptors; UI never treats webpage URLs as
streams. Media3 with HLS/DASH support plays one or merged sources, pauses in background, releases
on close and reports recoverable errors. Photo URLs use existing bounded remote-image loader.
Viewer uses the measured EditorWindow and existing preview-only zoom math.

## Tasks
- [x] Tests then parse stream URLs/headers, select tracks, reuse analysis metadata.
- [x] Wire repository/viewmodel preview resolution and photo full-size URLs.
- [x] Add photo gallery and audio/video streaming viewer with lifecycle cleanup and retry.
- [x] Change picker thumbnail/card clicks to preview and give selection independent controls.
- [x] Robolectric UI tests for independent opening/selection, close and photo navigation.
- [x] Full tests, review, ARM64 1.7.83/145 build, signed APK upload.

Constraints: no vertical Material slider thumbs; ephemeral URLs/headers never logged; no playback
or HTTP media fetch until explicit open/play; no invisible audio after close/background.
Cases: separate YouTube audio/video, manifests and progressive URLs, expired/unavailable sources,
album selection preservation, no usable stream, cancellation while resolving, window rotation.

Verification: initial policy tests failed on missing API, then passed. Independent review found
muxed-audio fallback and unrestricted duplicate DASH manifest issues; regression cases were added
before fixes. Seven stream policy and three UI tests pass; full suite 509 tests, zero failures/errors.
ARM64 release build and v2/v3 signing verification pass. No physical adb target is available: actual
account stream decoding/authorization still needs device validation. Source URLs/headers remain
transient; inspection confirmed the preview resolver has no queue/history/storage publication calls.
