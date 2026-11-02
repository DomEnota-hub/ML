# Video v8 adjustment controls implementation plan

> Inline continuation of the already approved HTML v8 contract and autonomous execution request.

**Goal:** Implement the thirteen real adjustment controls from the approved HTML, retaining existing project settings.
**Architecture:** Additional per-clip immutable settings feed one SDR GPU pass. A small 33³ LUT uses the existing photo matrix/tone algorithms; spatial sharpening stays in the fragment shader. Existing video RGB/HSL and photo preset paths remain functional. Grain/vignette reuse the real shared overlay painter.
**Tech stack:** Kotlin, Compose, Media3 1.9.0, GLSL ES2.
**Spec:** docs/ML_VIDEO_EDITOR_V8_APPROVED_REFERENCE.md; approved HTML v8 modifiers array and paths object.

## Constraints and review focus

- Existing PR54 branch and stack; no production publication or signing.
- Neutral settings preserve pixels; values finite, signed controls −100..100, detail0..100.
- Keep existing project legacy manual values, Undo/Redo and shared PhotoEffectCatalog.
- No per-frame CPU video decode/filter; GPU creation and LUT work run on the GL worker.
- Very short clips, zero intensity, saturated colours, small-screen captions and rapid reselection require regression coverage.
- SDR implemented; HDR colour handling remains explicitly unverified.

## Tasks

### 1. Immutable settings and genuine GPU processing

Files: data/editor/VideoReferenceAdjustments.kt, VideoReferenceAdjustmentEffect.kt;
assets/shaders/ml_video_adjustment.glsl; modify VideoColorGrade.kt and VideoEditor.kt.
Interface: VideoReferenceAdjustments.toPhoto(): PhotoColorAdjustments;
VideoReferenceAdjustmentEffect(settings): GlEffect; VideoColorGrade.reference default neutral.

- [ ] Add model contract/range and wiring regression tests; confirm failure before implementation.
- [x] Map matrix/tone fields through PhotoColorMath/PhotoToneMath, build a bounded33³ LUT on the GL worker.
- [x] Apply real sharpening and retain grain/vignette through the existing overlay painter.
- [x] Verify zero state, old manual project compatibility, and compile instrumented tests.

### 2. Reference adjustment panel

Files: ui/screens/VideoReferenceAdjustmentsPanel.kt, VideoColorPanel.kt,
VideoV8Icons.kt; preserve legacy controls through the More menu.
Interface: panel(settings, enabled, onChange); thirteen named enum controls and groups.

- [x] Use exact SVG paths from HTML, one selected slider, tone/colour/detail tabs and scrolling tiles.
- [x] Full two-line labels with enough vertical space and yellow active state.
- [x] Keep old RGB/HSL controls accessible as additional tools, preserving their actual values.
- [ ] Compose navigation/label tests and native screenshots.

### 3. Actual Android acceptance evidence

File: androidTest/.../Media3ActualVideoFxInstrumentedTest.kt.

- [ ] For all13 controls export synthetic coloured MP4 at0/50/100 and compare encoded pixels.
- [ ] Compare representative matrix/tone outputs with the existing photo CPU renderer.
- [ ] Re-run P0 preview/export tests, shared photo catalogue and exact-SHA CI.
- [ ] Record remaining device/visual acceptance gaps; do not call the release ready until accepted.
