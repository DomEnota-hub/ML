# Video preferences Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans for inline implementation and one final independent review.

**Goal:** Separate preview and selection touch areas, source/frame thumbnails, preferred video quality.
**Architecture:** Shared persisted VideoPreferences, pure quality selection applied in repository resolve, thumbnail loading reuses existing local source cache and lazy frame fallback.
**Tech Stack:** Kotlin, Compose, SharedPreferences, existing RemoteImageLoader.
**Spec:** docs/superpowers/specs/2026-10-28-video-preferences-design.md

## Constraints / review focus
- Thumbnail-only preview must not double-toggle; row text, padding and radio continue selection.
- Exact/missing/unknown heights and fps labels must select one video per source without selecting audio or changing Instagram.
- No network or frame decode on UI thread, no unbounded parallel cover fetch, cache keys never conflate frame and source.
- Preference switches affect existing visible tiles; source fetch failures fall back to frame and preserve cancellation.
- Settings controls remain scrollable in landscape and persist; manual override and explicit bulk rules are respected.

## Tasks
- [x] Add row interaction regression test, verify old behavior fails, then move preview click to thumbnail and selection to whole row.
- [x] Add quality/fallback and persisted preference tests; implement VideoPreferences and PreferredVideoQuality; wire shared resolve and expandable Settings choices.
- [x] Add source/frame/failure/cancellation loader tests; implement thumbnail preference, foreground-free bounded cover prefetch on enqueue, and update download-list prefetch.
- [x] Full tests/build, independent review, fix concrete failures, sign and upload next APK with release notes.

## Verification record
- Row test failed when text still opened preview, passed after splitting touch areas.
- Quality, preference persistence, source/frame/failure/cancellation tests passed.
- Mixed-provider ID collision test exposed selection leaking to unsupported Instagram items; changed choices to provider/page/group keys, test now passes.
- Final independent review: no Important/Critical findings. Full suite 576 passed, 1 skipped, 0 failed; release/lint vital succeeded; signed APK verified and uploaded.
