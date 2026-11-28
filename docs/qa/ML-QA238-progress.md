# QA-238 checkpoint

## Verified
- Delivered baseline 1.7.238/298: 6e6c90f8e98edc0c9f5cf2f9fa573ea20fa3f74f; DEVICE_QA_PENDING / NOT_STABLE.
- QA238-01/03/08: history gutter, journal drawer gesture, shared journal surface/state. CI300 / 46096089391 PASS.
- QA238-05/06/07/10: alternate lyric versions, explicit replacement, collapsed settings, blank musical pauses without generated notes, no provider provenance row. CI303 / 46099160554 PASS.
- QA238-02: task notifications, icons, real counters, operation-specific actions, exact task and repeated notification navigation. CI302 / 46098563667 download PASS (one approved live Shazam skip); editor export notification local test PASS per prior checkpoint. CI304 and CI305 failed the repeated-notification UI test; CI306 / 46100925700 PASS on aee5204b149dec3be59d6dea7f4c00e6c7d35c94 after test-only fixture correction, no test removed.

## Owner decisions
- QA238-04 CANCELLED_BY_OWNER: retain version-bound internal lyric cache; no Music/MediaLoader/Text folder, public copies, new SAF grant, export or migration. No public-storage implementation was committed.
- QA238-09 automatic text/audio alignment is separate research, not release scope. Manual timing/selection rejected.
- Continuous QA delivery authorized; stop only for a required owner action.

## Release preparation
1.7.239/299 is a candidate, not a delivered or stable version. Final combined CI v2 QA profiles navigation,download,editors,playback with mandatory Lint, ARM64/DEX and QA_ONLY receipt. No full stable qualify, emulator, merge or release publication. After exact-source consume-qa, original DomEnota offline signing, certificate, zipalign, APK/DEX and split checks, deliver to Google Drive. Do not mark delivered until verified. Xiaomi QA and owner decision required for stable.

## Rulings
Reuse current technical PR #200 and branch. No source mutation during active CI. No secrets in GitHub. Collapsing lyric settings preserves correction; selecting a different timing version may reset it. Separate export notification ID8491 from duplicate-music FGS ID8293.
