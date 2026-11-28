# QA-238 checkpoint

Owner authorized continuous work through one QA delivery on 2026-11-28, with a stop only for a required owner decision. Eight issues are in scope; do not repeat QA-237.

## Proven
- Base/delivered: 1.7.238/298 at 6e6c90f8e98edc0c9f5cf2f9fa573ea20fa3f74f.
- Cycle I, stages 1–2: e12dc720c21810c9a3456a43bae3004ab91fa805, history gutter and shared same-window journal. CI300 / 46096089391 success; navigation727/727, no failures/skips. Label removed; Drive current section updated/read back.

## In progress, not released
- Lyrics variants, explicit replacement priority, collapsed settings, blank musical pauses retained without generated notes.
- Notification renderer, unique icons, actual counters, task deep links, current capability checks; service-specific ownership, result details and operation-bound export cancellation.
- Fresh static review found export notification identity/privacy defects, lost task-journal memory state, lack of deep-link scrolling, stale live notifications after process death, incorrect paused transfer terminal state and lyrics AtomicFile metadata race. Fix pass in progress; final tests pending.
- Version remains1.7.238/298. No new signing or delivery and no stable claim.

## Required owner decision
Issue04 explicitly requires agreement on storage/migration before implementation. Propose retaining the version-bound internal cache, no automatic migration, public LRC/TXT copies in Music/MediaLoader/Text via one-time SAF folder grant, unique track mapping and no private-source export. Decide automatic copies versus explicit export, including ownership/deletion on later private moves or revoked folder access, before implementing public persistence. Optional manual lyric editing remains a proposal, not a required implementation.

## Verification scope
Use CI v2 only, relevant local checks and sufficient QA profile + mandatory Lint. No emulator/full qualify/merge. Local Java21 compiler attempt hit incomplete JRE (no jlink);6GiB daemon attempt died without a source diagnostic. Official JDK17 downloaded with matching vendor SHA256,3GiB in-process compilation in progress. These environment failures are not app test failures. Do not claim a passing local suite until actual test XML is read.

## Rulings
- Work on next stages in a detached local worktree while CI validates an unchanged remote HEAD; keep the current remote branch/PR.
- A new explicit lyric-version selection may reset correction for the new timing track; collapsing settings does not reset correction. The owner's requirement concerns collapsing.
- Export and duplicate-music FGS shared notification ID8293; allocate export8491 to keep separate owners from overwriting each other.
