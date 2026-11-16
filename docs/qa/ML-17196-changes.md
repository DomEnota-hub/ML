# QA 1.7.196 / 256 — changes and acceptance boundaries

Base: 1.7.195 /255, 32fcef644c0c31268b8acfa166b6600dada1b4f8 (Draft PR144). No production merge or GitHub Release. Original device logs/private journals are not source artifacts.

## QA-195-05 — ordinary folder visibility and pending recovery

The adapter discovered file locators by lexical DATA path only. Canonical aliases can refer to the same physical file; candidate discovery now includes canonical paths, but physical stat identity and full SHA remain required. Hidden-index verification now queries MediaStore.Files, including pending/trash rows, rather than only the original MIME collection.

Populated legacy HIDE records can acquire missing original metadata only from an exact still-present source with matching content, name, timestamp and catalog binding. All members are proved before journal replacement. Batch-first CAS preserves evidence across interruption before the move-journal update. IDs, tokens, cursor, phases and logical catalog URI remain stable. A missing/changed source or ambiguous binding stops recovery; no reconstructed plan, guessing, or deletion during upgrade. Modern records retain the existing verified copy / catalog CAS / delete-consent engine.

Planning shows known member counts; pending failures preserve confirmed counts. Port-specific fixed stage/error codes and stack locations replace bare Check failed, without exporting private locators, names or raw exception messages. Permission denial/preflight failure no longer forces both media scans; real recovery invalidations still refresh both modes.

The original phone failure exception and physical state of its 46 files were not provided. The adapter defects are independently reproducible; they do not establish which one caused that device event. Owner recovery of that exact journal remains a mandatory manual acceptance step, without clearing its data.

## QA-195-04 — remaining main-thread/allocation work

Both alias lookup maps now build and publish together on the projection worker; no mapValues on the Compose thread. Exact-generation raw phone references are reused before applying each current privacy/access filter. Cold app reference publication remains progressive; subsequent complete publications coalesce intermediate warm batches. Privacy/recovery invalidations still fail closed.

Media3 controller construction, including synchronous service binding, runs on IO with explicit main application Looper. Player/listener/release operations remain on main. Mini/full surfaces share bounded-lifetime leases; a cancelled connection releases its future. Thumbnail lock count is bounded (64 stripes); a bounded (256) short negative audio-cover cache avoids repeated misses and respects revision/explicit-cover keys.

Encoded audio payload fingerprinting uses a bounded direct buffer, retaining identical prefix and encoded sample bytes/order. AOSP extractor JNI's heap-array transport can copy/pin the backing buffer per sample; the direct path avoids that array transport. Duplicate worker priority is temporarily background and restored. Fingerprint/cache/deletion policies are unchanged. This is not a measured attribution of the phone's entire GC/jank load; actual Android16/Xiaomi timing must be measured after installation.

## QA-195-03 — stable collection covers

Automatic cover anchors to persisted member order (sortOrder/addedAt/id), not the first currently resolved member. Partial catalogs cannot substitute a later audio/video member. Cached covers are accepted only for that anchor. Assigned-cover semantics and privacy invalidation remain intact. All card, editor and overview paths use the same rule.

## QA-195-02 — stopped visual duplicate search

Stop/pause is explicit neutral resumable state instead of an error string. Final partial batches are journaled. A safe retained report remains viewable during ordinary catalog refresh; new scan/resume and deletion still require current fresh generation, and privacy/access changes discard stale results. The stopped panel uses themed heading/icon and compact equal-height single-line Continue/Results actions.

## QA-195-01 / QA-195-06 — scoped visuals

Collections use an outlined double-note glyph with hollow noteheads. Find file alone gains a rounded outline with thematic enabled and neutral disabled appearance; action/enabling logic and other buttons are unchanged.

## Verification / owner acceptance

Addressed tests include a durable 46-member interrupted plan, every existing move phase, legacy batch/move CAS gaps, alias/MediaStore adapter cases, failure-message privacy/cancellation, stable cover anchor, stopped report/actions, projection generation, connection lifecycle, bounded thumbnail misses and exact payload hash equivalence. Final exact-SHA full JVM (with existing isolated Compose workers), storage integration guards, Android lint and private-input ARM64 release are mandatory. Actual results and delivery hashes belong in the release README and current Drive Summary, not a predeclared pass here.

Manual Xiaomi scenarios: install over195 without clearing data; resume existing46-member plan without resetting journals; verify ordinary hidden folder absence outside ML and reversible visibility inside ML; compare dates/history/favorites/collections and folder assignments; denial/interruption must preserve recoverability. Check cold/warm gallery and music navigation during duplicate scan, mini/full audio playback transition, fixed collection cover across mode/restart, Stop then Continue/Results in supported themes, note glyph and Find file enabled/disabled border. Do not delete backups or originals manually to unblock a stopped journal.
