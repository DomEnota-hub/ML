# Downloader audio open/play fix

User on published1.7.98 clarifies: track page opens, Play does not start playback.
Private input log is outside Git. It shows audio Viewer opened/closed around20:13–20:15,
no recorded Media3 error. The service did not log onPlayerError, so absence of errors
in this log does not prove decoding succeeded. CDN403 entries are thumbnails.

Confirmed code defects:
- Legacy Downloader entry prepares/captures the entire audio queue after viewer
  entry, unlike Music's service-first selection. Session callbacks can run earlier.
- Play called play() without prepare() for an existing idle/stopped/failed timeline;
  ExoPlayer cannot restart STATE_IDLE solely by changing playWhenReady.
- Separate related hole: mini-player can use a service phone ID absent from a cold
  Downloader catalog, causing a silent ignored click.

Changes: common public-audio service-first selection/modern viewer in Downloader;
prepare-on-idle/error shared play action; observe live service queue in every mode
and reconstruct addressable public rows before a full phone index exists. Preserve
raw session aliases. Viewer falls back to requested row if a session alias is not
resolved yet. Private/blocked metadata remains excluded; private entry kept separate.
Add safe service error code/cause-class logging without paths, URLs or credentials.

Verification: only AudioQueuePlaybackTest and AudioPlaybackMetadataTest plus release
build. No broad suite, screenshots or previous catalog/system-bar tests repeated.
User budget persists. No publication yet; next release1.7.99/code161 includes catalog,
transparent navigation/unified mini-player and this audio fix. Exact device-specific
failure is not proven solely from the log; verify playback on the actual phone.


Completed: /tmp/downloader-audio-open-checks.log, BUILD SUCCESSFUL in5m23s. Focused
release tests14 passed (AudioQueuePlaybackTest11 + AudioPlaybackMetadataTest3), zero
failures/errors. Exact production code assembled after tests; no post-check edits.
Tests cover real ExoPlayer idle preparation with position/current selection retained,
service-first different-track selection, serialized session metadata with no local
configuration, raw alias continuity, and private/blocked/missing-URI exclusion.
No actual handset codec/file reproduction; user must confirm the specific track.

Public Downloader audio now uses the modern service-backed player. Existing internal
viewer preference remains respected; private and trash entry policies are retained.
The new play recovery also applies to old/private play controls and the mini-player.
Unsigned build is prepared; signed/public1.7.98 remains untouched. Next publication
must bump to1.7.99/code161 and include catalog + transparent UI + this fix. Do not
rerun completed tests for a version-only change.
