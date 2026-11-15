# ML 1.7.190 / stage 1 of 3

Base: shipped 1.7.189/249 a02597e2805b27d47fd5d45298964f1862a32680. Scope QA-189-01/02/04/08/09 only.

- Collection order: Room schema 13→14 adds nullable sortOrder; legacy members retain original addedAt/id ordering until reordered. Transaction verifies the ordered visible subset against the live DB, rewrites ordinal slots without touching timestamps, IDs, other collections, underlying media, links or invisible slots. New entries append. Separate reorder mode, long-press drag, edge scrolling and accessibility actions. Filtered order preserves other slots.
- File and link counts use RussianCounts. Links remain separate; no new collapsible link block. Existing picker and removal semantics preserved.
- Music starts collapsed in mixed collection; audio-only entry starts expanded. Visual segmented filters independent of music. Standard music row, current state and collection queue. Explicit play selection from chosen index retains original queue order.
- Existing AudioMiniPlayer in collection detail only; interactive round seek with current ID/generation/command validation and real duration; no new service, no global gallery chrome. Full player uses CurrentPlayerNavigation and current_audio_session_only. Existing system previous policy preserved.
- Resume session cap 10, no changes to stored session format.
- Addressed migration, filtered reorder, stale snapshot, resume limit and persistence, selected queue, Russian forms, collection collapse tests. Full CI, storage CI, lint and ARM64 required on exact HEAD.

## Physical acceptance after final stage
Install over 1.7.189 without removing application data. Mixed photo/video collection, drag within All and filtered views, both grid densities, edge scroll, cancel, orientation, restart, add/remove after reordering, links/music unaffected. Verify accessible actions and ordinary preview/selection. Check separate counts including 0, 1, 2, 11–14, 21; fresh/legacy collections and private/unavailable files. Music collapsed/expanded and independent filters; start selected track and whole collection, play/pause/previous/next/seek/close; open full player and return without resetting queue/position; system player remains synchronized; no private metadata. Verify retained queues at 8/9/10/11, old session data preserved. Check light/dark/INFERNUM, small screen, landscape and safe insets. Do not assume physical acceptance from JVM tests.
