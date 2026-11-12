# 1.7.178 — palette review and QA criteria

The approved ordinary dark accent is `#FFBD19` (existing editor/player gold). Light app colors remain unchanged; music uses the existing **light library** accent `#7036CB` (main light app primary `#762DFF` is retained). Photo/video editor chrome always uses the independent black/white/gold scheme. User-chosen paint, color correction channel swatches, media content and source brand marks are not theme chrome.

| Scope | Implementation | Device acceptance |
|---|---|---|
| Main app / system theme | All inherited primary/secondary/tertiary roles and tonal containers explicit; dark accent gold, light unchanged | Open main, downloads, bulk, gallery, settings in both/system modes |
| Library / music / EQ | Library override gold in dark; light purple; full/compact/mini/preview playback bars use music accent; EQ off neutral | Switch active track, disabled EQ, empty artwork, favorites, playlists |
| Download/history/preview | Direct progress, gradient actions, watch stripe now thematic | Active/error/success/progress and long lists |
| Shazam | Actual recognition source identifies Shazam, original root/library tint preserved by independent composition local | Compare visible recognition result before/after in each theme; loading icon and progress follow ordinary theme |
| Settings icons | Former purple foregrounds use exact gold; containers stay distinct, other semantic colors unchanged | Accounts/theme/player, dark + light |
| WebView | Only injected app-owned download controls get theme-aware CSS | Instagram/VK/YouTube/RUTUBE normal page content unaffected |
| Photo/video | Full editor scheme; video audio lanes and neon tool icon gold; white/neutral editor text controls retained | Inspect all panels/dialogs and both app themes, including AI, overlay, timeline |
| User content | Brush palette, HSL purple channel, overlays and brand marks intentionally preserved | Purple paint remains selectable; no image/video color change |

Static `scripts/audit_ui_palette.py` scans all UI Kotlin and Android XML for direct violet literals and rejects unclassified additions. The remaining literals are explicitly scoped light colors, legacy Shazam, source branding or user media/color controls. Static checks do not establish complete on-device visual acceptance. Hidden INFERNUM-specific artwork and user assets are not recolored.

OCR: bounded multi-layout pass and a same-gesture retry when no validated masks; shared background from a coherent line's full exterior band only when individual symbol perimeter fails, with high OCR confidence and per-symbol real background/ink verification. Fail closed on textured/ambiguous background; selected masks remain pixel strokes, not rectangles. LaMa model and deletion of people unchanged. Test original and processed image, especially the outlined word and faint bottom watermark.
