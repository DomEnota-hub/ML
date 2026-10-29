# Final whole-branch review and fix pass

Reviewed range: `6877374..38e9b23`. One fresh reviewer (`library_final_review`),
read-only, no implementer agents or second review. Spec/plan/ledger and native
captures reviewed. Full gate at this SHA: `/tmp/library-ui-task10-full.log`,
BUILD SUCCESSFUL in 5m18s. 686 tests, 0 failures/errors, 1 disabled live Shazam
test. Arm64 unsigned release APK built; no version bump or publication.

## Findings accepted into the single fix pass

1. Important: playlist canonicalization discarded the original phone reference.
   After history-only cleanup, physical files remain but canonical download IDs
   cannot resolve. Keep durable alternative references with playlist membership.
2. Important: APP-linked media, playlist covers/detail and add-picker data can
   retain old phone metadata during permission refresh. Apply the existing
   composition-preserving visual/accessibility shield to all library surfaces,
   through publication of the matching index revision; hide the outside mini-player
   too. Preserve retained editor references and selected IDs.
3. Reviewer Minor → Important: video watch/resume preferences use route IDs.
   Canonical app/phone identity changes lose an ordinary user's saved position,
   contrary to the shared-file requirement. Link aliases to a durable preferences
   key and use it for both players and watch marks. Cover both directions.

No Critical findings. Software EQ headroom and always-PCM rulings accepted by
reviewer. Layout follows compact A and remains scrollable.

## Fix pass results

- Playlist members now persist alternate references in the same atomic JSON
  document. Missing `trackReferences` remains compatible with old saved playlists.
  Canonicalization can return to a validated phone reference after history cleanup,
  preserving order, unavailable entries and uniqueness.
- Phone scan snapshots carry revisions. The root visual/accessibility shield now
  covers the entire library content until the matching index is published; the
  separate root mini-player is hidden meanwhile. Editor and picker composition,
  retained references and selection are preserved. Tradeoff: app-owned content is
  briefly shielded during a phone refresh too.
- Video aliases link to one durable preference key, used by the main player,
  floating player and watch marks. Read caching avoids reparsing alias JSON on
  every progress update. Linking does not overwrite a playing video's position.
  Old ambiguous bookmarks without timestamps prefer the greatest progress.
- Actual RED playlist history-clear assertion: `/tmp/library-ui-review-red2.log`.
  Missing gate/link APIs RED: `/tmp/library-ui-review-red.log`.
  Targeted GREEN: `/tmp/library-ui-review-green.log`, BUILD SUCCESSFUL, 3m17s.
- Final full suite so far: 691 total tests, 0 failures/errors, 1 disabled live
  Shazam integration. Release rebuild also passed: `/tmp/library-ui-final-full.log`, BUILD SUCCESSFUL in 8m13s, unsigned arm64 APK assembled. No release published.

All accepted findings are in this one fix pass; no second reviewer or deferred
minor item. Branch/worktree retained for the user's next command, with no push,
merge, version bump or release publication.

## Declined-to-judge rulings / remaining validation boundaries

- Audible EQ transitions, perception, Bluetooth/wired output and battery: device
  QA, not a proven defect. No attached target; sound-quality claims remain withheld.
- Overlay handoff, OEM gesture routing, notification/headset behavior and hardware
  rendering: device QA; native touch-routing tests cover the supported software path.
- Full large-font/inset/landscape device coverage: existing reachability tests and
  scrollable layouts support the design; portrait software renders are narrower evidence.
- Browser/native pixel identity: native typography and declared software Canvas
  capture are intentional; preserve measured composition, not browser raster pixels.
- Service-death/reconnection reliability: no reproduced failure; error boundary
  exists. No speculative session rewrite in this pass; a runtime failure needs evidence.
- PCM frames split mid-frame: no upstream contract evidence; no speculative buffer
  workaround. Cost if that contract changes: processor framing must be revisited.
- Externally corrupted preferences/provider identity reuse: no supported reproduced
  failure. No general storage/identity redesign; investigate against concrete evidence.
- Existing trash confirmation / brief video pause on a cancelled pager drag: not
  change-blocking defects. Preserve current confirmations and conservative release
  of outgoing video; cost is a pause if the user cancels a swipe.
- Automatic-download log: remains unread until feature fixes and final checks finish.
- Release gate was pending at review time: subsequently verified successful above;
  publication remains unauthorized.

The coordinator owns these rulings. No minor findings deferred: the only reviewer
minor was promoted to Important and included in the fix pass.
