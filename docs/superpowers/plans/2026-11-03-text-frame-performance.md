# Text frame and responsiveness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore reference text manipulation and remove unnecessary foreground contention without changing export semantics.
**Architecture:** Reuse TextLayerSelectionOverlay and shared project-time scale; preserve existing graphics editing. Schedule whole-library waveform decoding only when idle; keep requested tracks available. Publish library index before independent alias persistence.
**Tech Stack:** Kotlin, Compose, Media3 1.9.0, WorkManager, Robolectric.
**Spec:** docs/superpowers/specs/2026-11-03-text-frame-performance.md

## Global Constraints
- Existing PR54 branch/stack, original media and privacy gates preserved.
- No official signing/release or merge; no repeated full CI suites.
- No claimed phone speedup without phone traces; compare source with1.7.110.

## Review Focus
- Short/end-of-project text ranges: clamp without crossing or losing edits.
- Duplicate must retain style/time but get independent ID.
- Gesture cancellation must close one history transaction.
- Cached audio reads must not wait for decoding; stopped work must release its codec.
- Library must remain fail-closed for privacy while alias persistence runs separately.

### Task1: Background contention and library publication
Files: MainActivity.kt, AudioWaveformPrecomputeWorker.kt, TimelineVisuals.kt, LibraryViewModel.kt; new WaveformDecodeGate.kt.
- [x] Compare baseline branch and inspect actual scheduling/index code.
- [x] Add targeted decode gate concurrency/cache tests and bulk-vs-priority work policy check.
- [x] Make bulk work idle-only; migrate legacy jobs; serialize expensive decoders with cancellation. Defer scheduling off startup main thread.
- [x] Move alias persistence off the index publication path; coalesce progressive history publication.
- [x] Run only affected waveform/library/playlist tests and compile; commit logical changes.

### Task2: Direct text frame and intervals
Files: TextLayerSelectionOverlay.kt, VideoEditorScreen.kt, VideoOverlayPanel.kt, VideoMultiClipTimeline.kt; new VideoTextControls.kt, VideoTextLanes.kt, VideoTextLaneEdit.kt.
- [x] Add text range/duplicate tests and extend existing video text control tests to reject geometry/time sliders.
- [x] Reuse frame with corner actions and one undo transaction per drag.
- [x] Add blue independent text lanes using existing project/scroll geometry and reversible range edits.
- [x] Provide compact input/tabs and edit/style/trim/copy/delete; keep graphics behavior.
- [x] Run selected text/frame/range/history tests and compile; commit.

### Task3: Scoped verification and delivery
- [ ] Run lint once after the block; fix concrete errors only.
- [ ] Build one reviewable ARM64 QA candidate. Use one focused native UI capture if needed; do not repeat passed export matrix for unchanged DSP.
- [ ] Record exact HEAD, changed mechanisms, measured evidence vs unverified phone behavior and remaining work.
