# ML 1.7.192 — stage 2: functional corrections

Authorized after stage 1 acceptance, base `3ecf6f702c2c1dfaeb32bea08b9b1662abf1a8e3`.
Scope QA-191-04, QA-191-05 and QA-191-08; no new version, production merge, or intermediate release.

## Recognition and playback

- Local recognition stores a separate atomic per-URI+SHA retry journal, with the actual decoded PCM duration and requested start offset for each checked window. Old success-only cache has no provenance and is not used for this interactive route. Rejected identities/title+artist remain excluded across reopen, including provider exceptions. A new file version needs new analysis. No additional request after an ordinary first match. Retry prefers nonoverlapping unused windows, at most five plan entries; a new candidate is independently confirmed if an unused independent window exists. Conflicts are explicit; an unconfirmed result is not advertised as certain. Only explicit user selection can change tags; the existing URI/ID/SHA/privacy/audio-payload checks are retained and the journal is rechecked before staging.
- Local result offers “Это не тот трек” and refresh icon, existing animated equalizer, actual progress. Rejected match disappears before retry; no automatic tag write or download. Cancel/network failure cannot resurrect the rejected match. Legacy cache may require a fresh recognition.
- Playback remembers a positive duration only for the same Media3 ID/URI/content revision; verified replacement gets a new revision. Unknown duration is shown as --:--, seek requires actual seek command. Position comes from current controller; recognition dismissal does not rebuild the queue. Verified file replacement uses existing restoration path. No duration copied from a different track.
- Existing conditional permission row now includes Android 12+ MANAGE_MEDIA, version-appropriate audio read, ACCESS_MEDIA_LOCATION. Only explicit action requests missing grants; no GPS/broad all-files access. The existing MediaStore createWriteRequest/RESULT_OK remains authoritative, with app confirmations. Multiple initial system steps may be required. No claim HyperOS will suppress all dialogs until device verification.

## Instagram publication music

- Explicit full-song action only: use existing trustworthy source metadata first, then exact publication native public page/embed or already-authorized API. Known publication-level `music_asset_info` wrappers only; no invented GAPI schema, no extraction from caption/author or unrelated recommendation. Generic original audio is not song identification; only verified HTTPS CDN assets become temporary acoustic candidates. Metadata-only music does not become a downloadable fake file. Existing normal visual download routing unchanged. No-match, missing input, extraction and provider errors distinguished, cancellation/stale gate preserved; status-only diagnostics.
- GAPI recorded fixtures lack confirmed attached-music fields. Actual target publication and live Instagram/authorized endpoint availability remain manual acceptance; no guarantee for inaccessible licensed audio. Optional metadata lookup can fail without breaking existing acoustic/manual route.

## AI eraser

- Preserve existing OCR confidence and safe per-symbol pixel extraction; no bounding-box LaMa fallback. For credible multi-glyph lines, a bounded column background profile verifies compatible upper/lower exterior bands, smooth gradient or at most one coherent vertical seam. Reject texture and ambiguous backgrounds. Keep verified ink when another glyph is unresolved, show unresolved outlines in manual mode, do not silently remove a partial phrase; explicit mask review before manual inpaint. No model/weights changes. Log counts and rejection categories, no image/text/URI.
- Synthetic outlined text crossing a vertical seam, exact center mask pixels 1248 with no background centers; separate Android raster test checks coverage and holes/far background. Blank/textured/ambiguous inputs still fail closed. The actual user photograph is not available as original; no use of screenshots as training/test data; actual OCR+LaMa visual result remains device acceptance.

## Verification

15 new regression tests: retry 5, journal 1, duration 2, permission 1, publication music 4, glyph mask 2. Focused Main music checks include new duration, permission and publication tests; Storage includes GlyphContentMaskTest. Local Kotlin smoke of production core (Bitmap stub only for synthetic pixel input) passed; not a complete Android test. UI palette/slider/notice guards and git whitespace checks must pass. Main/Storage CI, full JVM, both lint, ARM64, exact diagnostics required for stage closure. Stage 3 cosmetic work separate.
