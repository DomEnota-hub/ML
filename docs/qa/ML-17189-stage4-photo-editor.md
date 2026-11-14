# ML 1.7.189 stage 4 — common photo editor

Scope: QA-188-10, photo part of QA-188-12 and QA-188-13. No AI in batch; single-photo AI remains available. No signing, delivery or video editor changes in this stage.

- Shared PhotoEditorContent, catalog, filters, RGB/levels, crop/geometry, rotate/mirror, text/graphic marks, drawing and local effects. Batch filmstrip and one destination-scoped draft/undo stack; no max-side or quality controls. Format and metadata remain explicit; quality 95 JPEG/WebP, PNG lossless. Separate output files; sequential full-resolution rendering, memory failure reported per item, no hidden resizing.
- Fixed crop ratio is adapted using reference crop relative zoom and center on each oriented photo. Free crop retains normalized margins. Existing minimum retained dimension applies to extreme panoramas. EXIF is applied before geometry/AI overlays; preview and export share ordered PhotoRenderPipeline, fitted text in batch. No byte passthrough because encoding and metadata sanitization must remain deterministic.
- Public sources checked again before decode and publication under private-move gate. Reject AI overlay in batch data layer. Original files untouched; retry failed items uses original submitted spec. Existing report/cancel behavior preserved.
- Filter card actual aspect fit, bounded 256–384 px edge with no upscale, off-main rendering. Video cards/slider remain stage 5.
- Drawing width doubled; continuous chronological CLEAR on isolated drawing layer. Earlier marks remain editable; masks are canvas-coordinate operations, and new marks are above earlier masks. Visible circle matches actual stroke diameter, clipped loupe 2.5x renders current composition, not exported. Outside pointer segments clipped; no bridging across letterbox. Bounds: 150 marks / 900 points per mark, longer eraser gestures chunk until model cap.

Device acceptance on final signed APK:
1. Gallery mixed selection shows photo count; only public photos enter. Single mode keeps AI; batch no AI. Switch portrait/landscape, rotate device with edits and undo stack, compare current source, verify no old preview or UI overlap.
2. Crop free and fixed ratios on different EXIF orientations, rotated/mirrored images; RGB curves, filter favorites/intensity, text and graphic watermark, drawing and mosaic. Compare exported pixels and geometry against preview; no artificial upscaling, no quality/max-side controls.
3. JPEG/PNG/WebP, alpha, GPS/EXIF options, original identity and bytes, separate copies in correct Gallery location. Large batch sequential memory, cancel, unavailable photo/permission/private move, partial errors and retry. No private thumbnails in public output.
4. Pen/marker/neon minimum/maximum, full-width slider; eraser tap, slow/fast line, edge exit/re-entry, partial line/shape, neon glow, overlap, undo/redo, subsequent strokes, selection and transform. Compare circle and loupe with saved image; no display overlays exported.
5. Single-photo editor regression, all themes/density/portrait/landscape, system bars, no forbidden slider markers. On-device performance and visual quality are not proven by JVM.
