# Video Frame Export Implementation Plan

> **For agentic workers:** Implement in the current MediaLoader worktree using the existing editor export pipeline.

## Design

The video editor gets a “Сохранить кадр” action. Capture the current player position, clamped to the selected trim range, then extract a source resolution frame. Apply the editor's crop fractions and rotation to a JPEG. Publish through `MediaRepository.publishEditedFile` as `MediaType.PHOTO`, so the file appears in Downloads/history and can be opened in the photo viewer. The source video remains untouched. If exact frame extraction fails, try the nearest key frame; show a clear error if neither exists.

## Tasks

1. Add a pure timestamp clamping policy and failing tests for end-of-video and trim boundaries.
2. Add `VideoFrameExporter` for retriever extraction, crop, rotate and temporary JPEG output. Integrate it with `EditorExportCoordinator` and its foreground job state.
3. Add the video editor action and ViewModel/App wiring. Build, run unit tests, sign and verify the arm64 APK, then back up source plus Git history.
