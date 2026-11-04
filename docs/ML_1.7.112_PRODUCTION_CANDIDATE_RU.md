# Кандидат итоговой 1.7.112

Пользователь после ручной проверки Release QA200124 (df000cdc6cac9a3f8d52e865aee8f16013ec297a) запросил итоговую обычную версию для обновления установленной 1.7.110. Это разрешение на выпуск после исправления двух замечаний и прохождения релизных проверок. Main и stacked PR автоматически не сливаются; релиз может быть привязан к точному проверенному SHA ветки PR54. Предыдущие релизы сохраняются.

## Последние исправления

1. VideoEditor: снимок panelSnapshot/history для отмены создаётся синхронно до вставки новой надписи. Раньше LaunchedEffect захватывал состояние уже с новой надписью, поэтому Cancel её оставлял.
2. Новый слой имеет text="". VideoTextDraft.preview добавляет «Ваш текст» только в копию выбранного слоя для предпросмотра. Поле ввода получает исходный пустой текст. Преобразование рамкой сохраняет исходное содержимое; подсказка не копируется в модель. Пустые слои удаляются при подтверждении и исключаются из экспорта.
3. PhotoGeometryDialog использует существующий PhotoToolCard основной ленты фоторедактора для режимов и вспомогательных действий. Иконки, цвета выбора, размер карточек/подписей и область касания согласованы; геометрические операции и Undo/Redo сохранены.

Локально: :app:testDebugUnitTest по VideoTextDraftTest и PhotoGeometry*Test — 18 PASS, 0 ошибок, 4м22с с компиляцией (2+4+4+2+6). Дополнительный полный локальный прогон не выполнялся; полный набор запускается в CI один раз как релизный gate.

## Обязательная идентичность

com.domenota.medialoader, versionName1.7.112, versionCode173, Release без debuggable, ABI arm64-v8a. У 1.7.110 ожидается code172. Никаких суффиксов QA в production APK.
Официальный сертификат DomEnota SHA256: 4f6f0ddf4d8fd2669fd2a3a6ebab6e84bda17281a3a5badada0e1eeb73a4f1d2.
Ключ/пароли остаются локально и не передаются в Git, CI, логи, PR, Dropbox. CI сохраняет unsigned production artifact; подпись и финальная проверка выполняются локально.

## Gates и приёмка

Полный workflow ml-video-v8-reference-validation: full_validation=true, run_device_tests=true, official_release_review=false. Unit/Compose, lint, WebView JS, ARM64, unsigned production preflight, настоящие Media3 Transformer MP4/эффекты, shared photo catalog, trim/restore, текст/стикеры, отмена и FFmpeg MP3→AAC смешивание выполняются существующими проверками. Ошибки gate необходимо устранить до публикации.

Окончательные SHA, CI, результаты тестов, хэш/размер/сертификат APK, публикация, обратное скачивание GitHub asset и Dropbox фиксируются в /workspace/ML_1.7.112_PRODUCTION_HANDOFF_20261103.md . До появления успешных результатов пункты не считаются PASSED.

Физический Xiaomi в среде недоступен. Его производительность/фоновая работа/память и полное визуальное соответствие HTML v8 не выдаются за автоматически проверенные. Пользователь дал ручную приёмку и запрос выпуска; это не заменяет проверку фактического MP4 и обязательные gates. Новые замечания о тексте/Геометрии исправлены по коду и адресным тестам, новая ручная проверка этих двух изменений пока не выполнена.

Первый полный gate (aea63e0, CI41685810441): 1082/1084 unit/Compose PASS; два GridViewerUiTest обращались к заменённым стрелкам навигации. Тесты переведены на свайпы без ослабления проверок границ, действий, video-controller callbacks и восстановления выбранного файла. Адресно все4 GridViewerUiTest PASS4м19с.
Реальные Android проверки: 11/12 PASS, включая все MP4/фильтры/интенсивности/trim/text/JPEG/cancel/MP3-AAC и13регуляторов. Единственный timeout — UI smoke ожидал SurfaceView при фактическом SDR TextureView. Захват кадра адаптирован к TextureView с сохранением SurfaceView fallback и проверки пикселей; runtime pipeline не менялся. Эти исправления тестового интерфейса требуют нового полного gate на новом SHA перед публикацией.

Gate91a5fbd: Android12/12PASS (CI126), unit/Compose1084/1084PASS (CI127). Перед этим unit-job126 получила32ошибки setup из-за MavenHTTP429 — повтор127 устранил инфраструктурный сбой без изменения приложения. CI127 lint сообщил2UnsafeOptInUsageError у HDR-констант в readVideoInfo и1ложный ProduceStateDoesNotAssignValue у фоновой проекции. Добавлен proper AndroidX opt-in непосредственно readVideoInfo (математика не менялась). Локально две ошибки Media3 устранены; EditorItemsProjectionTest1PASS. ProduceState lint не распознал даже явное value=computed, поэтому helper заменён эквивалентными remember MutableState + LaunchedEffect; присваивание result.value явное, прежняя проверка source===rows сохранена. Никаких подавлений ошибок lint и отключения проверок. Финальные unit/lint/JS/ARM64 gates запускаются в CI на новом SHA; реальные12codec уже прошли для неизменённого processing/export implementation91a5fbd, повторять эти экспорты для одних opt-in/state-helper не требуется.
