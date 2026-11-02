# PR54 — проверка продолжения (2026-11-02)

## Точная исходная точка

- Ветка: `feature/ml-video-editor-v8-reference-rebuild-20261102`.
- PR54 OPEN/DRAFT, base `feature/ml-17111-group3-video-8-15-20261102`.
- Исходный SHA: `8e7331ae00b95055e2d4ee9a19fdcf1d9fadc4bf`.
- `main` и последовательность PR51–54 сохраняются, merge/release не выполняются.
- CI run41478136719 действительно относится к этому SHA: qa FAILURE (1027tests,
  3failed/1skipped); device FAILURE (Asset loader error). Старый зелёный run не принят.

## Источники

Контракт и аудит в docs прочитаны. Drive HTML v8 доступен:307518bytes,
ID1mysfXrzXaD5B-Nqxk76aWyEpIPaeXztQ; локальный одобренный исходник сохранён
в /workspace/medialoader-video-editor-v8.14скриншотов и записи ранее скачаны
в /workspace/video-editor-reference; иерархия подтверждена в предыдущей сессии.
Три журнала `/Загрузчик/1.7.111/…` из ChatGPT Library недоступны через текущие
инструменты; локальная `/workspace/library-files` пуста. Содержимое не выдумывается.
Официальный signing key также не читается/не используется до приёмки.

## Матрица исходного состояния — код отдельно от доказательств

| Область | Статус | Фактическое основание / следующий шаг |
|---|---|---|
| Корневой док v8 | PARTIAL / NOT_DEVICE_TESTED | Порядок/вложенные инструменты есть; визуальный Android parity не доказан |
| Undo/Redo | IMPLEMENTED / NOT_DEVICE_TESTED | VideoEditHistory,60состояний; реальные серии жестов ещё проверить |
| Спецэффекты10 | PARTIAL / NOT_DEVICE_TESTED | Painter→thumbnail/OverlayEffect, реальные pixel-тесты ранее падали |
| Общий PhotoEffectCatalog | PARTIAL | Подключён к VideoColorGrade; точность алгоритма/MP4 не доказана |
| Pre-prepare effects + REDRAW | IMPLEMENTED / NOT_DEVICE_TESTED | Проверено по upstream Media3 tag1.9.0: REDRAW специальный маркер, не сброс списка; устройство ещё нужно |
| Инструментальный MP4-тест | BROKEN | Входной base64 декодируется в повреждённый H264: FFmpeg Invalid NAL unit size на всех12кадрах |
| Pixel unit tests | BROKEN | Robolectric использует legacy Canvas; требуется native graphics для доказательства реального рисования |
| Trim source timeline | PARTIAL / NOT_DEVICE_TESTED | Внесённые ранее source-relative/short-clip исправления сохраняются; проверить20→15→20, скорости/undo/экспорт |
| Текст/стикеры/фото54.1 | PARTIAL / NOT_DEVICE_TESTED | Модели и общие компоненты есть; регрессии/полнота настроек не проверены |
| Отделённый/внешний звук | PARTIAL / NOT_DEVICE_TESTED | Данные и FFmpeg mix подключены; реальный AAC/громкость/fade/speed ещё не доказаны |
| Галерея без миниплеера53.1 | MISSING | В MediaLoaderApp всё ещё передаётся AudioMiniPlayer в GalleryModeScreen; исправить отдельным коммитом |
| JPEG/M4A/MP4, прогресс/фон/отмена | PARTIAL / NOT_DEVICE_TESTED | Код/обработчики есть; пока нет полного end-to-end доказательства |
| Декодирование/кэш8MiB | PARTIAL | Scaled retriever и ограничения есть; память/длинный ролик не измерены |
| Официальный релиз | BLOCKED | До реальных проверок и ручной приёмки, ключ/сертификат не подменять |

## Первый блок: восстановление доказательного тестирования P0

- Заменён повреждённый синтетический белый H264 на валидный160×90,15fps,0.8s,
  baseline/yuv420p. Полное декодирование через FFmpeg `-xerror` прошло.
- В instrumented test добавлена проверка декодируемости до Transformer.
  Исправление main Looper из8e7331a сохранено, не переделано.
- Два класса тестов Canvas/миниатюр переведены на Robolectric NATIVE graphics.
  Это не обходит проверки пикселей: assertions сохранены.
- CI сохраняет unit/lint/device reports и logcat даже при падении. Диагностические
  артефакты synthetic emulator остаются вне репозитория; секретов/ключей нет.
- Targeted local Gradle test пока выполняется; PASS не заявлен.
- Реальная проверка эффектов0/50/100, проигрывания/паузы/MP4 и фотофильтров следует
  после восстановления базового instrumented-прохода. Наличие названий не приёмка.

Локально Android SDK/Java17/Gradle8.9 доступны; нет emulator и /dev/kvm.
Реальные инструментальные тесты запускаются в CI Android35 x86_64 (не ARM64-телефон).

## Изолированный блок галереи53.1

Удалён сам слот miniPlayer из GalleryModeScreen и передаваемый callback.
Внешний Scaffold больше не рисует AudioMiniPlayer при AppMode.GALLERY;
переключатель Медиа/Альбомы сохранён. Музыкальный сервис, очередь, позиция,
скорость и управление в MusicModeScreen не менялись. Локальные
GalleryAlbumNavigationTest/GalleryRangeSelectionTest/AudioQueuePlaybackTest
прошли (Gradle27s). Уведомление и фоновое воспроизведение на физическом устройстве
NOT_DEVICE_TESTED. Отдельный коммит от Media3.

## P0: расширение реального кодек-теста

Локально4Canvas/thumbnail теста теперь прошли, instrumented Kotlin compile прошёл.
Внешний HTTP proxy потребовал системный Java truststore, проверка TLS не отключалась.
Добавлен экспорт всех10эффектов0/50/100%, сравнение реальных декодированных RGB
(31MP4 с общим NONE-baseline) и сохранение синтетических MP4/CSV в CI artifacts.
OPTION_CLOSEST вместо sync-keyframe нужен для мерцания в300ms, иначе читался
первый ключевой кадр снулевой фазой. Выполнение нового теста на Android пока pending.
CI52766fc: qa упал на новом fixture-validator до Gradle; зависимость FFmpeg явно
добавлена, runner закреплён ubuntu24.04 в24542da. Результат device job ещё ожидается.

## P0: SurfaceView/PixelCopy проверка предпросмотра

Добавлен только в debug отдельный Activity-хост синтетического видео; в Release
его нет. Instrumentation проверяет реальные SurfaceView-пиксели при смене эффектов
на паузе (REDRAW), воспроизведение, уровни0/50/100 и чередование двух клипов.
Отдельно сравниваются paused-frame и декодированный MP4 одного timestamp, включая
фазу мерцания; сохраняются PNG. Это проверка Media3 pipeline, не полная приёмка
пользовательского экрана VideoEditorScreen/ARM64. Пока NOT_DEVICE_TESTED.
Локальная компиляция debug/androidTest прошла до последнего уточнения timestamp;
полная локальная проверка1027unit/Compose:0failures,1skipped по XML, lint/compile
ещё выполняются. Точный SHA и результаты CI фиксируются после завершения.

## Результаты bd92479 и следующий тестовый источник

Device job125408265832: cinemaBarsAreActuallyBakedIntoMp4 PASS; matrix показал
изменённые кадры VIGNETTE/SCANLINES/CINEMA_BARS, но PULSE50% получил0delta:
белое осветляющее наложение нельзя проверить на белом источнике. Это дефект
тестового стимула, не доказательство отсутствия эффекта. Добавлен валидный серый
H264 для матрицы и player test; white остаётся для black-bars golden. FFmpeg
полностью декодировал оба источника. Assertions не удалены/не ослаблены.
Артефакт1.2MB получен через GitHub connector (CLI job redirect был недоступен).
Evidence MP4 потерялись после Gradle uninstall; добавлено сохранение из теста
в /sdcard/Download до uninstall с последующим adb pull. Только синтетические файлы.

Compose drag test20→15→20 для обеих границ на density1и2 прошёл локально.
Он проверяет реальные жесты и ширину выделения0.75, а не только функции математики;
добавлены testTag без изменения жестовой реализации. Touch slop в тесте0 для
точного сравнения математических5секунд; системный slop/физический телефон ещё NOT_DEVICE_TESTED.
Общий локальный unit/Compose +lint +androidTest compile закончился SUCCESS:
1027tests,0failures,1skipped (опциональный live Shazam); lint0errors,75warnings.
Это результат до новых drag-тестов/debug-предпросмотра, не приёмка нового SHA.

## Текст/замечание54.1 — конкретный пробел и ограниченное расширение

До этой сессии общие TextLayer/UnifiedTextStyle имели цвет, opacity0–100,
размер, обводку/цвет обводки, шрифты, геометрию и время. Отдельных shadow/bold/
alignment в модели и renderer не было; UI-символы сами по себе не реализуют экспорт.
Добавлены независимые поля bold/shadow/alignment с прежними defaults и
рендеринг в общем TextLayerRenderer (используется фото/JPEG/Media3 текстовым overlay).
Добавлены6стилевых плиток в UnifiedTextStyle, включая различимые векторы выравнивания.
Фоторедактор не переписан, его исходные инструменты/тайминг/пипетка сохранены.
Нулевой opacity исключает слой целиком, включая тень/контур.
TextLayerStyleRenderingTest (native Canvas), TextLayerOpacityV8Test,
VideoOverlayUnifiedTextTest прошли локально. Настоящий MP4 с новыми стилями и
visual parity этой панели остаются NOT_DEVICE_TESTED до отдельного прохода.

## Подтверждённый P0 runtime-дефект и исправление

CI run41485981783, SHA f48e614: MP4 matrix всех10эффектов0/50/100 PASS,
cinema golden PASS. Получены31синтетических MP4 и metrics.csv. Примеры mean RGB
изменения относительно0: PULSE50/100=23/46, CINEMA_BARS=15.53/29.78,
SPOTLIGHT=28.81/56.57. Native SurfaceView test FAIL с timeout20s.
Logcat объяснил реальную причину: ExoPlaybackException →
UnsupportedOperationException «Replaying when enableReplayableCache is set to false».
REDRAW допустим в1.9.0, но pre-prepare одного empty effects НЕДОСТАТОЧНО.

Добавлен VideoEditorPlayerFactory: редакторный MediaCodecVideoRenderer создаёт
PlaybackVideoGraphWrapper с setEnableReplayableCache(true); используется и
реальным VideoEditorScreen, и debug-probe. Обычный видео/аудиоплеер не меняется.
Upstream tag1.9.0 ReplayableFrameCacheGlShaderProgram CAPACITY=2: кеш GPU-кадров
ограничен, но добавляет память/вычисления только редактору. Физическая4K-память
всё ещё NOT_DEVICE_TESTED. Локальный AndroidTest compile10s PASS; реальное
устранение ошибки подтверждается следующим CI, не одной компиляцией.
