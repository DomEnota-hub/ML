# LaMa quality improvements

Approved scope: keep the installed local 512x512 LaMa model; improve input geometry,
mask margins and composition. Do not publish, upload or bump the release version.

Use a square crop around the final selection plus surrounding context, with reflected
image padding at borders. Uniform mapping prevents source/mask aspect mismatch.
Rasterize strokes at source resolution before crop/resampling; slightly dilate the
binary selection and feather only its outermost edge. Use the same coverage for
inference, preview and export. Pixels outside coverage remain exact originals.
Keep model execution isolated behind an injectable runner for deterministic tests.
Compare geometry and compositing using synthetic model outputs; real model quality
requires later before/after checks on representative photos.

Alternatives considered: replacing LaMa now (cannot isolate integration problems),
full-frame letterboxing (preserves aspect but loses local detail). Selected: local
crop with context and unchanged model. Large selections still use a large crop.

Related log investigation: Instagram returned account-specific sensitive-content
restriction. Preserve bounded complete error JSON for classification and truncate
only log output; report sensitive-content hint without automatic retries.
