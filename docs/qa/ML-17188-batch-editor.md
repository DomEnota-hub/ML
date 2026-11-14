# QA-187-08/09/10 implementation

QA-08 source finding: `BatchPhotoEditorScreen` previously cleared `preview` on every spec update, removing the conditional 220dp Image from a vertically scrolling form. A quality tick therefore collapsed and restored content above the slider. The preview slot is now unconditional; quality changes do not decode or render a new preview. Last successful pixels remain during same-photo background updates. Material Slider owns gesture arbitration, range/steps, semantics and keyboard behavior under the shared round rendering.

QA-09 inventory: all `PhotoEffect.entries` use `PhotoColorMath.apply`; the batch exporter already used this pipeline for six choices. Batch now exposes `PhotoEffectCatalog.forCategory`, preserving identifiers/names/categories and passing `effectStrength` through `BatchPhotoSpec.colorAdjustments()` to preview/export. Categories match the photo editor's visible categories, except favorites (batch has no favorite persistence). The source preview is EXIF oriented and downsampled to at most 1280px, main result to 640px, thumbnails to 112px. No preview export/temp file is created. The screen owns a 12MiB LRU with two concurrent jobs, including photo identity/version and filter parameters in keys; disposal clears it. Original media is read only. Export formats, metadata handling, reports, cancellation/retry and destination publication remain with existing export flow.

Added checks, not yet executed through Gradle:

- `BatchPhotoPipelineTest`: pixel comparison with actual photo pipeline for every filter at 0/100%; intensity validation and source ownership.
- `BatchPhotoEditorGeometryTest`: quality tap, min/max drag, coordinate stability and subsequent vertical scrolling.
- `BatchPhotoPreviewCacheTest`: key/version invalidation, memory ownership after eviction, concurrency limit and cancelled-job exclusion.
- `python scripts/check-round-sliders.py`: CI-only check of shared round ordinary sliders and 48dp default interaction region. Passed locally.

QA-08 Smart Mix observation: familiar includes favorites even at 0 plays, as well as tracks with 2+ plays; empty buckets refill from available candidates. Bucket interleaving and artist ordering mean the first five items do not prove quota failure. No mix algorithm change is justified by the screenshot alone.

## QA-187-10: implemented after explicit user approval

Batch opens the full existing `EditorTextDialog` from Add/Change watermark. It reuses the photo/video tabs, keyboard, bundled fonts, color/eyedropper, opacity, outline, shadow, alignment and move/combined rotate-scale frame, including up to eight layers. It does not add a second gesture engine or new fonts. Single-tap editing and existing selection behavior remain the shared editor's contract; a separate double-tap gesture was not introduced.

`BatchWatermarkDraft` saves only text/style/normalized transforms in saveable state. The opt-in shared dialog also saves its live unapplied layers, selected layer, open sheet and tab, so saved-state recreation while editing does not discard the draft; bitmaps remain transient. One layer list applies across the batch. `BatchPhotoSpec` retains the old plain-string field for compatible callers; rich layers take precedence. Blank layers and the preview-only “Ваш текст” placeholder do not enter output.

`TextLayerFitting` measures actual StaticLayout ink bounds, font overhang, multiline height, outline and shadow margins; rotates the bounds, fits them uniformly and clamps the center in the EXIF-oriented output image. When long text cannot fit at the model's minimum size, a placement scale reduces its rendering without changing global TextLayer size limits. Each output aspect is fitted independently. Existing `TextLayerRenderer`, `TextLayerSelectionOverlay` and `EditorTextDialog` accept an opt-in fitting flag; false is the default, preserving ordinary photo/video behavior. Overlay frames, fitted text and gestures use actual Fit image coordinates including letterbox and existing zoom transforms. Renderer/export never draw frame handles.

The same fitting and renderer run in main preview, shared dialog and actual batch export. Pixel rounding in StaticLayout can produce subpixel normalized differences across resolutions; the shared resolution test allows a small tolerance. Fonts/OCR remain the existing local implementation.

Added QA-10 checks (pending consolidated Gradle execution):

- `BatchTextDraftRestorationTest`: real shared dialog edits text/style/placement, changes selection, recreates saved state before applying, then verifies the full restored draft.
- `BatchWatermarkTest`: long/multiline text, all alignment variants, rotation quadrants, portrait/landscape/square, outline/shadow, normalized placement at different resolutions and complete draft serialization.
- Actual EXIF-6 JPEG input exported to PNG with two layers: comparison against shared preview and direct fitted renderer, exact output pixel equality, original bytes unchanged, no frame/handle pixels.
- Empty text/placeholder exclusion.
- `BatchPhotoEditorGeometryTest.batchWatermarkEntryUsesSharedEditorAndPassesDraftToExport`: batch action opens the actual shared editor, edited text reaches export specification.

Manual acceptance pending: Xiaomi gesture/3-button navigation, small/large fonts, portrait/landscape, all themes, rapid photo/filter switches, async preview failure/recovery, private photos, large batches, format/export/metadata and cancellation/retry. Automated checks do not establish device acceptance.
