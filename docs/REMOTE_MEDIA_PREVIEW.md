# Media preview before download

`PreviewScreen` owns a saveable open item ID. Tapping the photo grid, media row or hero opens a
separate measured `EditorWindow`; the check control alone changes download selection. Closing
returns to the original picker with its selection. Photos have swipe/button navigation, pinch
zoom/pan, double-tap zoom and reset. Zoom disables swipe-to-next until reset; next/previous buttons
remain available. Full photo URLs are carried separately from the thumbnail in PreviewMediaUi.
The existing bounded remote-image loader decodes at most 2048px and handles its disk/memory cache.

Video/audio use a short-lived ExoPlayer, streaming directly with in-memory buffering. This path
does not enqueue downloads or write history/files. HLS and DASH modules match Media3 1.9.0. Each
source has its own HTTP headers. Separate video/audio are merged with FilteringMediaSource so a
muxed fallback can supply audio without replacing the chosen video. Shared manifest URLs are
loaded once; the selected height limits adaptive video tracks. Audio-only playback disables video.
Loading, retry, playback/seek controls, end-to-start replay and error messages are visible.

`YtDlpJson` retains transient URL/protocol/manifest/header/DRM metadata. `PreviewStreams` accepts
HTTP(S) tracks, excludes DRM and storyboards, chooses the requested available height and audio.
No webpage URL from YouTube/VK/RUTUBE is passed directly to the player. Provider extractor wrappers
retain only the last analyzed StreamInfo for five minutes; only preview reuses it, explicit
Analyze always calls the original extractor. Signed URLs/headers are not intentionally logged or
stored in history. Instagram/direct audio use media URLs with the appropriate site referer.

Opening explicitly requests playback. It pauses on ON_STOP; a resolver completing in background
only prepares the player and cannot start audio. Closing cancels resolution and releases player;
position polling also ends with composition. Reopening/retrying uses a new playback request.

Tests cover stream metadata/header parsing, separate and muxed fallback audio, shared manifests,
height limits, unsupported protocols/DRM, missing streams and metadata reuse/expiry. Robolectric
UI tests cover independent selection/preview, photo navigation/close, source failure and retry for
audio/video, and no download request while viewing. Source-account playback with hardware decoding
still requires a connected device; none is available in the execution environment.
