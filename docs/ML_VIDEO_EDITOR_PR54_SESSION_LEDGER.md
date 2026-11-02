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

## Общие фотофильтры — корректирующий GPU-проход (ещё требуется device proof)

Адаптер SharedVideoFilterCatalog.adjustments был приблизительным: другая формула
контраста/теплоты и имитация fade, зерно накладывалось иной полупрозрачной текстурой.
Новый VideoPhotoPresetEffect берёт точную PhotoColorMath.matrix и повторяет fade/
зерно PhotoStylizeMath. Это общий GlEffect для preview и MP4, manual video sliders
накладываются отдельно и пресет не применяется повторно. Legacy video presets
сохраняются. Шум — deterministic hash тех же координат; GPU luminance texture
1byte/pixel (UHD8.3MB), без ARGB-копии каждого видеокадра на CPU. Матрица/интенсивность
готовятся один раз; GPU-проход SDR учитывает SMPTE170M границу Media3 linear space.
HDR явно отклоняется как неподдерживаемый для этого прохода, а не окрашивается
неверными коэффициентами; поддержка/тонмаппинг HDR остаётся релизным ограничением.

Добавлен actual-MP4 тест каждого PhotoEffect против PhotoColorMath.apply исходного
кадра с допуском meanRGB10 для H264/YUV, включая fade/grain и50% для4представителей.
Источник — синтетический цветной BT709 H264 с AAC440Hz, не белый фон. Новый shader
ещё НЕ считается подтверждённым до выполнения на Android. Компиляция и целевые
SharedVideoFilterCatalog/VideoColorEffects tests прошли локально16s.

## Звук — устранение ограничения speed1×

Аудит нашёл явный reject отделения звука при clip.speed!=1 и отсутствие скорости
в независимой AudioTrack. Добавлены speed0.25–4, source/output-time преобразование,
корректное обрезание видимой аудиодорожки, clamp суммарного fade и setPlaybackSpeed
в preview. FFmpeg использует цепочки atempo0.5–2 до fade; видео копируется без
перекодирования аудиопакетом. UI скорости добавлен в существующую аудиопанель.
Старые проекты имеют default1×. Никаких физических перемещений источника.

Целевые AudioRetiming/MixCommand/EditHistoryAndDetach/VideoProjectDraft тесты и
androidTest compile прошли локально11s. Добавлен реальный instrumented сценарий:
отделение при2× → MP4; libmp3lame MP3 → внешняя дорожка с gain/fade → MP4/AAC →
M4A → декодированный PCM. Проверяются отсутствие тишины/удвоения, fade-in и
сохранение440Hz при ускорении. Выполнение на Android пока pending.
Доказательство MP3/AACaudio-tier не подменяется генерацией аргументов команды.

## Дополнительные фактические результаты и исправления после 1ef59dd

CI41490792592 / 1ef59dd: cinema golden и MP4 matrix10×0/50/100 PASS;
реальное отделение2×, MP3 libmp3lame, микширование, AAC/M4A/PCM PASS
(samples6144, RMS0.02055, pitch450Hz при частоте источника440Hz).
Preview всё ещё timeout; replay cache сам по себе не решает смену списка effects.
В1.9 configure нового input stream вызывает frameCache.onNewInputStream и очищает
кеш. updateEffects теперь при паузе принудительно инициирует seek в соседнюю1ms
позицию и возвращается в исходную. Логическая позиция сохраняется, звук не
запускается. Device proof нового пути pending; timeout сохраняет stage/PNG.

Полный unit CI выявил2 layout-регрессии новых text styles: палитра перестала быть
видна сразу. ecc2906 вернул палитру перед расширенными плитками; обаSDK34/35
EditorTextDialogLayoutTest прошли локально. Видеоредактор также зависел от темы
приложения (светлый фон при светлых иконках). 6b16e95 изолировал тёмную палитру v8.
Добавлен debug-only host реального VideoEditorScreen и снимки root/editor/trim/
aspect для визуальной сверки человеком. Скриншоты не объявляются golden PASS.

Фото-MP4 тест обнаружил MONO50 meanRGBerror21.02. Фактический конструктор
DefaultVideoFrameProcessor.Factory.Builder1.9 задаёт WORKING_COLOR_SPACE_DEFAULT,
то есть encoded SMPTE170M SDR; GlEffect javadoc ошибочно говорит linear. Удалён
двойной encode/decode из shader. Единая PhotoColorMath matrix/fade/grain сохранена.
Доказательство соответствия всему каталогу ожидает повторного actual-MP4 теста.

Дополнительные instrumented проверки:8 временных текстовых слоёв + стикер,
настоящие MP4 trim15с справа/слева/восстановление20с с проверкой первого/последнего
исходного кадра и SHA256 оригинала. Компиляция пройдена, device execution pending.

## JPEG кадр — выявленное расхождение с текущим изображением

Старый VideoFrameExporter применял только crop/rotation/text; игнорировал mirror,
photo/video effects и graphics. Кнопка была отключена для всех клипов, кроме
исходного первого. Теперь выбирается клип по project playhead, его source position
и локальные интервалы слоёв. Media3 обрабатывает не более300ms исходника тем же
previewEffects pipeline; затем из результата сохраняется JPEG95. Overlay/FX clock
сохраняет исходный clip.inMs. Исходник не меняется, весь проект не кодируется.
Для картинки-клипа эта кнопка пока отключена (явное ограничение).
Целевые FramePosition/Overlay unit и AndroidTest compile PASS; новая реальная
проверка JPEG из второго клипа с cinema bars и timed text ожидает Android CI.

## P0 preview подтверждён + устранение lint API-зависимости

CI41491860276 / b59dde6: actual SurfaceView pause0/50/100, playing,
чередование двух клипов и meanRGB preview/MP4 tolerance12 для ВСЕХ10эффектов
PASS (в instrumented suite остался только известный photo-gamma fail).
Audio runtime и31MP4 matrix вновь PASS. Это эмуляторAPI35x86_64, не ARM64телефон.

Lint выявил WrongConstant для Typeface bitwise style и RestrictedApi в
PlaybackVideoGraphWrapper. Typeface теперь использует явные BOLD/BOLD_ITALIC.
Внутренний Media3 wrapper полностью удалён, не подавлен: публичный seek-путь
updateEffects не использует REDRAW, поэтому replay-cache/subclass ему не нужны.
Ресурсы дополнительного GPU-кеша больше не выделяются. Compile и целевой native
text-render test PASS; повторный device test публичного пути обязателен.

## 6f5d668 — реальные результаты Android (получены после возобновления)

CI41495545928: QA job125450258932 SUCCESS: все1033unit/Compose (1optional skip),
lint, WebViewJS, ARM64 QA и unsigned production preflight package/version/ABI/size.
Native job125450258059:9/10PASS. Отдельно подтверждены весь PhotoEffect catalogue
(meanRGB обычно1.2–3.9), MP4 matrix10×0/50/100,8timed text layers+sticker, MP3/AAC,
JPEG из второго клипа и отменаTransformer с очисткой/повторным экспортом.
Trim: right15000ms / left15000ms / restored20000ms ТОЧНО, first/last source-frame
meanRGB1.2–2.3, SHA256 исходника не изменился. Native UI root/editor/trim/aspect
открываются без краша, снимки получены; полного visual parity это не доказывает.

Остался preview-test: raw decoder metadata833333us относился к prefetch, а не
видимому item-relative paused clock300ms. Для сравнения используется logical
paused position; это не ослабляет проверки реальных пикселей.
Повторный84e6fd9 выявил также нестабильность double-seek: на full vignette мог
остаться half кадр. Новый public API путь создаёт свежие MediaItem periods в
точной сохранённой позиции, не полагается на coalesced seeks/REDRAW/internal API.
По просьбе пользователя повторяется только этот P0 тест; успешные9native тестов
и QA не повторяются на каждом промежуточном коммите.

## UI / недостающая коррекция по HTML

Найдены13 reference controls против8 legacy RGB/HSL. Добавлена независимая
VideoReferenceAdjustments в per-clip grade: матрица/тон PhotoColorMath/PhotoToneMath
→33³ LUT наGL worker (~144KB), реальная3×3резкость; grain/vignette используют
общий настоящий overlay painter. Прежние настройки/фотофильтры не подменяются.
UI: точные13SVG изHTML, один выбранный slider, Тон/Цвет/Детализация, полные подписи;
legacy RGB/HSL доступны в «Ещё → Дополнительная коррекция». Добавлена реальная
Android-проверка half/full для13контролов (ещё pending), без повторения1033тестов.

Панели получили X/галочку какv8: cancel восстанавливает model+history checkpoint,
apply возвращает root. Исправлены растягивание canvas, двойной status-bar inset,
перенесены frame-step кнопки в trim/More, убраны технические счётчики клипов.
Целевые3новыхmodel/LUT/history tests и compile прошли; новый UI capture pending.

CI теперь: push→короткие затронутыеvideo tests+compile; workflow_dispatch→указанный
native class#method; full_validation=true→один полный release-gate проход.
Публикация/подпись по-прежнему не выполняются до закрытия блокеров и приёмки.

## f847802 / короткий native прогон41533240675

13actual GPU controls half/full PASS; native UI root/editor/trim/adjustment sections PASS.
Preview без replay cache по-прежнему оставлял half vignette при100%; fresh periods
сами по себе проблему не закрыли. Восстановлен Media3 bounded replay cache + seek
из b59dde6, где все10preview/parity уже проходили. Интеграция требует group-level
PlaybackVideoGraphWrapper API в закреплённомMedia3 1.9.0: SuppressLint RestrictedApi
ТОЛЬКО на соответствующем override, с объяснением; не baseline и не скрытие
ошибок тестов. ПамятьGPU2кадра, физический4Kprofiling всё ещё не подтверждён.

Копирование evidence в новом тестовом классе исправлено: executeShellCommand не
обрабатывает ';' как shell; mkdir иcp теперь отдельные команды. XML доказывает
прохождение13регулировок; сырые файлы будут приложены в итоговом проходе.

## Общий холст и музыка

Добавлены8ratio поHTML (включая2.39:1/2.55:1) в project.aspect, отдельно от crop.
Preview/MP4/JPEG используют единый canvas; Presentation применяется до титров,
поэтому надпись может находиться и на полях холста. Выходное разрешение уважает
заданный short side; default ORIGINAL сохраняет прежние проекты. Unit геометрии/
Undo и затронутые composition/toolbar tests прошли; новый actual MP4/JPEG case pending.

«Музыка» теперь открывает native экранv8: Онлайн/На устройстве, импорт черезSAF,
список, прослушивание, удаление из списка и добавление дорожки. Импорт не изменяет
проект до кнопки+; preview использует transient audio focus и освобождается при
закрытии. В эталоне online backend отсутствует, в приложении показано недоступное
состояние без фиктивного каталога. Music import/device audio focus требуют ручной
приёмки. Разделение звука2× также разрешено в UI (старый speed==1 guard удалён).

Текущий локальный блок: affected tests+androidTest compile+lint PASS (4m52s);
1033теста и старые native матрицы повторно не запускались. Далее один focused
native проход P0/canvas/UI; полный release-gate прогон — один раз перед приёмкой.

## Continuation: stable preview and independent audio lanes

8affc40 focused native evidence: project aspect MP4/JPEG and editor/music screen
opening PASS; paused vignette 50→100 remained BROKEN (identical saved pixels).
No claim of final preview correctness from the earlier one-off green run.

Replaced changing effect lists/seek workarounds with a persistent preview graph:
atomic overlay/text parameters, stable GL colour stage, REDRAW using the bounded
Media3 replay cache. Geometry alone rebuilds the player, retaining playlist,
position, volume, speed and play state. Preview input capped at1280 pixels to
bound GPU textures. Export still uses the full-resolution pipeline.
Local androidTest compile PASS; two native-Canvas regression tests PASS for
same-timestamp strength/reselection and same-ID text opacity. Actual native P0
and preview colour correctness remain NOT VERIFIED pending focused CI.

Added independent purple audio lanes with cached actual PCM envelope, selection,
move and reversible source trim respecting .25–4x speed; two rows visible with
scroll for additional tracks. Video source-trim hides the audio rows because its
ruler is source-relative. Real labels retained for imports/detached tracks.
Audio-lane geometry tests plus existing trim/history/detach tests and compile
PASS. Physical gestures and imported-audio audition remain NOT_DEVICE_TESTED.
No full suite, release assembly or lint rerun in this iteration.
