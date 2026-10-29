# Text controls implementation plan

> **For agentic workers:** Use superpowers:executing-plans to implement inline.

**Goal:** Compact font selection and manipulate text directly on the preview.
**Architecture:** Shared renderer layout supplies text bounds. A preview-only overlay handles
hit testing and gestures. A pure transform helper computes size/angle from a fixed drag origin.
**Tech Stack:** Kotlin, Compose, Android StaticLayout, JUnit.
**Spec:** docs/superpowers/specs/2026-10-27-text-controls-design.md

## Global Constraints
- Preserve all font IDs, layer format and export behavior.
- No vertical slider thumbs; remove size/rotation sliders.
- Photo and video use the same component.

## Review Focus
- Rotated/multiline text frame agrees with actual glyph layout.
- Handle at preview edge remains reachable.
- Empty/invisible layers do not intercept gestures.
- Vertical scrolling outside text remains available.
- Crossing ±180° and dragging through the pivot produce valid layers.

### Task 1: Gesture geometry
- [x] Add TextLayerTransformTest for scaling, rotation, wraparound, limits and zero vector.
- [x] Run focused test, observe missing implementation, implement TextLayerTransform.
- [x] Run focused tests, expect pass.

### Task 2: Preview selection frame
- [x] Share StaticLayout construction in TextLayerRenderer; compute padded frame from actual lines.
- [x] Add TextLayerSelectionOverlay with rotated outline, 48dp handle target, drag inside frame,
      fixed-origin scale/angle, and no interception outside selection. Clamp handle to preview.
- [x] Connect overlay to EditorTextDialog; remove size and rotation sliders.
- [x] Compile Kotlin, expect success.

### Task 3: Font families and release
- [x] Group fonts by ID prefix; outlined family rows with independent expand button, variants,
      selected state and font-face samples. Outline other text actions and palette selection.
- [x] Update docs and version 1.7.78/140, run full JVM suite and ARM64 release build.
- [x] Review diff, verify signature/version, upload signed APK and commit changes.

## Verification ledger
- Base: 5e68a97; clean feature branch used in place.
- Transform tests first failed on missing implementation; initial three tests passed after implementation.
- Edge regression tests first failed on missing fallback/placement API; implementation preserves prior state at pivot and separates handle from center.
- Independent review found two edge cases above; both fixed.
- No adb target connected; on-device touch behavior remains a manual check.
- Final: 480 JVM tests, zero failures/errors; ARM64 release build successful.
- APK signature v2/v3 verified, package version 1.7.78 / code 140.
- Final preview clips text to the image and selection controls to the preview area.
