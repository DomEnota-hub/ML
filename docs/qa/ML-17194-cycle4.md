# ML 1.7.194 / 254 — cycle 4, QA194-07/08

Accepted base: `19224d32aeb0f5ec998520ae25651b4e56f17bc9` (cycle 3).
No model-weight, provider, resolution, export or release-signing changes.

## Stage 5 — OCR and pixel masks

The original reported photo had a white fill, dark outline and variable door background. The previous code made detailed bottom-left passes, and discarded entire alternate lines if they overlapped a stronger hypothesis. The new three overlapping, bounded, full-width bands scan top/centre/bottom; they use the stricter full-frame credible-line gate. Strong lines retain non-overlapping geometrically consistent symbols from independently credible alternate hypotheses, without replacing overlapping primary boxes. The 160-symbol selection cap remains; one overflow candidate distinguishes a truncated selection from a complete candidate set.

The existing uniform/per-column background method is unchanged as the primary path. A second, conservative white-fill / dark-outline component method operates within symbol bounds, requires repeated style evidence (>=3 strongly recognized symbols, >=half of line), enclosed light components with narrow neutral dark boundaries, a bound on connected components and stroke count, and rejects components connected to the local image edge or unrelated dark structures. Inner counters are preserved; any substantial unresolved interior bright component or overly distant dark pixel rejects the entire candidate. Existing bounded raster dilation is used; no box masks. Unresolved selections remain manual and cannot automatically inpaint a partial mask. This is deliberately not a general text segmentation model: ambiguous outlines, connected door edges, severe texture and other colours may still require manual correction.

Synthetic fixtures test complementary passes, overflow, mixed background, complete outline/fill/hole, unrelated line connection, partial fill, checkerboard/no text, and real Bitmap-to-mask rasterization. Existing tests remain unchanged. Original `1000042031.jpg` is not available as bytes in this workspace; no private photo or log was committed. Compare actual OCR coverage/false positive pixels and actual LaMa output on that original during final Xiaomi QA. No measured on-device OCR improvement claimed.

## Stage 6 — LaMa lifecycle

Previously the editor unconditionally initialized LaMa on entry, including OCR-only work, and immediate last-editor disposal evicted sessions; the asynchronous eviction could wait for a run and close after a new editor attached. These are real code paths causing avoidable initialization, not proof of every prior device log's root cause. Remove preloading, initialize only on explicit processing with nonempty coverage. The reusable native handle is serialized by exact model/config identity, including creation, inference, eviction and atomic install/uninstall mutation; cancelled waiters cannot initialize, native creation/run is not forcibly interrupted, and cancellation is checked before publication. Native session/options are disposed together. Model install/uninstall/verify also serialize across model-store instances. No bitmap or tensor is retained by the cache. UI jobs for OCR and LaMa cancel on source change; stale result is never published.

Idle LaMa grace is at most 20 seconds only when Android reports no low-memory condition, >=512 MiB available and >=2x threshold; other models keep immediate idle eviction. Memory pressure/UI hidden releases all caches via the per-handle gate; stale idle tasks recheck editor generation after acquiring the gate. No background eager initialization. The grace is a bounded policy choice, not a measured device memory guarantee.

Diagnostics distinguish model lookup, ORT environment/options/create, session reuse/release reason, tensor preparation, native run, readback, and native heap snapshot. LaMa is a raw pinned ONNX file, not a decompression stage at each use. CPU 2 threads, FP32 512x512 and checksum unchanged; no graph-cache/XNNPACK toggle without device A/B. First inference and warm inference are distinguishable from reuse and run logs. Actual cold/warm init/heap/timing and inpainting quality on Xiaomi require final device QA.

Local tests execute actual pure Kotlin OCR/outline and serialized-cache test bodies with desktop JUnit stubs (not Android CI); concurrency/reuse, cancellation, retry, eviction generation, atomic replacement and memory grace. CI adds named addressable OCR and model lifecycle groups plus full JVM, both Android lint and ARM64 compilation. No prior tests weakened or removed.

### Дополнительная проверка общей обводки

На синтетической сцене с неоднородным фоном и линией двери использованы
настоящие Tesseract 5.5 и поставляемые rus/eng traineddata. Desktop-проверка
масок выполнялась Kotlin-кодом приложения с pixel-only Bitmap stub, без
Android raster/inpainting. OCR-боксы зачастую содержат лишь белую заливку.
Обводка теперь проверяется общей связной компонентой в пределах выбранной
строки, с ограниченным допуском однопиксельного нейтрального перехода;
общая граница соседних букв допустима, связь с фоном/краем отвергает всю
зависимую группу. При сильном повторяющемся стиле непроверенные буквы не
подтверждаются резервным методом фона. Независимая буква может оставаться
проверенной, но частичный результат не разрешает автоматический inpainting.
Новые регрессии: общая обводка + антиалиасинг, связанная линия двери +
независимый символ. Контрольные центры маски синтетической верхней строки:
16150 пикселей текста, 0 фона, 5 из 16 кандидатов не подтверждены; центральной:
5491 пиксель текста, 0 фона, 6 из 11 не подтверждены. Нижняя строка старого
метода: 12 из 16 кандидатов не подтверждены, 280 из 2702 отмеченных центров
попали на фон; автоматический запуск запрещён, частичную маску следует
проверять вручную. Не является оценкой реальной фотографии 1000042031.jpg
или Android-растрирования. Исходное вложение не было доступно для проверки.
