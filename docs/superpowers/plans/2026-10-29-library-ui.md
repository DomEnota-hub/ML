# Gallery and Music Variant A Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Выполнять последовательно; дополнительные агенты — только по отдельно выбранному пользователем способу работы.

**Goal:** Перенести согласованный HTML-вариант А в native Галерею и Музыку с общей библиотекой, полноценными плейлистами, плеером и общим эквалайзером.

**Architecture:** Compose-компоненты воспроизводят эталон; объединённая библиотека и устойчивые ссылки отделены от истории загрузок. Просмотр использует общую очередь фото/видео; музыка использует существующую MediaSession. Эквалайзер работает в аудиосервисе, а не в жизненном цикле экрана.

**Tech Stack:** Kotlin, Jetpack Compose, Room, coroutines/StateFlow, Android MediaStore, Media3 1.9.0; minSdk 26, targetSdk 35.

**Spec:** `docs/superpowers/specs/2026-10-29-library-ui-design.md`

## Global Constraints

- Визуальный эталон — колонка А `docs/superpowers/references/2026-10-29-library-ui/variant-a-reference.html`; боковые стрелки просмотра убрать.
- Android 8+ (minSdk 26), targetSdk 35, Media3 1.9.0. Не вводить WebView и новый механизм музыкального воспроизведения.
- Подписи источника строго «Файлы приложения» и «Все файлы телефона»; последний включает оба источника без повторов.
- Не создавать фиктивные загрузки для внешних файлов. Избранное/альбомы/плейлисты хранят ссылки; их удаление не удаляет оригинал.
- Приватные алиасы не появляются в общей библиотеке, обложках и очередях; сохранить защиту редактора при проверке разрешений.
- Бегунки только круглые; не возвращать вертикальную полоску. Удаление с красной иконкой/подписью.
- Производственные изменения, увеличение версии, сборка выпуска и выгрузка APK не выполняются во время написания плана.

## Review Focus

- Один файл открыт разными URI: одна карточка, одни отметки и позиция, перенос в «Личное» закрывает все публичные представления — Task 1/2/5.
- Частичный/отозванный доступ и отключение накопителя: не теряем ссылки, не показываем закрытые обложки, не сбрасываем редактор — Task 1/2/5.
- Короткий landscape, большой шрифт и длинные имена: содержимое и основные действия остаются достижимыми — Task 3/4/6/7.
- Смена источника, поиск, поворот и возврат из плеера: выбор треков и реальная очередь не исчезают и не дублируются — Task 6/7.
- Изменение PCM формата/частоты, фон и seek: эквалайзер не ломает скорость, каналы и отключение эффектов; seek overlay не двигает окно — Task 8/9.

## Карта существующего кода

- `ui/MediaLoaderApp.kt`: маршруты, режим/источник, публичные строки, очереди, защита и разрешения. Здесь сейчас выбирается либо downloads, либо phone items; это заменить объединением.
- `ui/LibraryViewModel.kt`, `data/library/PhoneMediaLibrary.kt`, `LibraryPreferences.kt`: асинхронный scan, разрешения, сохранение режима и источника.
- `data/playback/PublicMediaIdentity.kt`: готовые URI/MediaStore/физические идентификаторы. Переиспользовать, не выполнять `capture` на Main и не сканировать полные файлы в композиции.
- `data/catalog/DownloadCatalogRepository.kt`: назначения с FK на downloadId, `requireCompleted()` требует запись загрузки. Внешние ссылки здесь напрямую сохранять нельзя.
- `data/library/PlaylistRepository.kt`: `MediaPlaylist(id, name, trackIds)`, `addTracks`, `removeTrack`, `moveTrack`. Сохранить данные и публичные операции.
- `ui/components/LibraryModeHeader.kt`, `AudioMiniPlayer.kt`, `ui/screens/GalleryModeScreen.kt`, `MusicModeScreen.kt`, `AudioPlayerContent.kt`, `MediaViewerScreen.kt`: точки UI-интеграции.
- `data/playback/AudioPlaybackService.kt`: единственный музыкальный ExoPlayer/MediaSession; подключить DSP здесь.
- `data/playback/FloatingVideoService.kt`, `FloatingVideoGesturePolicy.kt`: собственное overlay-окно; добавить нижнюю перемотку.

Новые компоненты ниже размещать в указанных каталогах и оставлять небольшими; не переносить всю библиотечную логику в `MediaLoaderApp.kt`.

## Task 1: Объединённая библиотека и стабильные идентификаторы

**Files:** Create `data/library/UnifiedMediaLibrary.kt`, `data/library/LibraryMediaRef.kt`; Modify `ui/LibraryViewModel.kt`, `ui/MediaLoaderApp.kt`; Test `src/test/.../data/library/UnifiedMediaLibraryTest.kt` (все относительные src-пути ниже внутри `app/`).

**Interfaces:** `LibraryMediaRef(key: String, aliases: Set<String>, item: DownloadEntity, appDownloadId: String?)`; `UnifiedMediaLibrary.merge(app: List<LibraryMediaRef>, phone: List<LibraryMediaRef>, privateKeys: Set<String>): List<LibraryMediaRef>`. `key` устойчив при переходе между источниками; для известной загрузки канонической остаётся её id. Файлы только с одинаковым названием не объединять. IO-обогащение идентичности выполняет ViewModel с кэшем, merge остаётся чистой функцией.

- [ ] Написать тесты: app+MediaStore-алиас дают 1 элемент с app метаданными; одинаковые имена/разные URI дают 2; приватный alias исключён; порядок устойчив; исчезнувший phone ref не удаляет доступный app ref.
- [ ] Запустить `:app:testDebugUnitTest --tests '*UnifiedMediaLibraryTest'`, убедиться в ожидаемом падении до реализации.
- [ ] Реализовать объединение/индекс ссылок и выдачу APP либо APP+PHONE. Сохранить разрешения/refresh shield; фоновые запросы провайдерам и никаких чтений полных файлов в UI.
- [ ] Повторить тесты плюс существующие `PhoneMediaLibraryTest`, `LibraryRefreshShieldTest`, `PrivatePlaybackAliasTest`; получить 0 failures. Commit только относящихся файлов.

## Task 2: Метаданные внешних файлов и совместимость папок/плейлистов

**Files:** Create `data/library/ExternalMediaCatalogRepository.kt`; Modify `data/library/PlaylistRepository.kt`, `data/catalog/DownloadCatalogRepository.kt` только через адаптер, `ui/LibraryViewModel.kt`; Test `data/library/ExternalMediaCatalogRepositoryTest.kt`, `PlaylistRepositoryTest.kt`.

**Interfaces:** `ExternalMediaCatalogRepository` хранит версионированные локальные ссылки вне таблицы downloads, по паттерну атомарного хранения PlaylistRepository: `suspend setFavorite(ref: LibraryMediaRef, favorite: Boolean)`, `suspend setFolder(ref: LibraryMediaRef, folderId: String?)`; наблюдаемые метаданные через StateFlow. Адаптер маршрутизирует app refs в существующий каталог, внешние — в новый; папки остаются общими. Membership использует key Task 1, старые trackIds продолжают разрешаться через aliases.

- [ ] Тестами закрепить: внешний файл избранный/папка без строки downloads и операций с байтами; перезапуск сохраняет связи; снятие папки сохраняет избранное; удаление папки сохраняет файл; app/phone alias не создаёт второй трек; прежний плейлист читается.
- [ ] Запустить выбранные тесты до реализации, зафиксировать ожидаемые падения.
- [ ] Реализовать хранение/адаптер и разрешение ссылок; добавить доступные внешние элементы в логические папки приложения. Недоступные ссылки удерживать без показа приватных метаданных. Файлы, помеченные только избранными, не импортировать в историю загрузок.
- [ ] Проверить тесты миграции/ссылок и `PlaylistRepositoryTest`; commit.

## Task 3: Общая оболочка варианта А

**Files:** Modify `ui/components/LibraryModeHeader.kt`, `ui/MediaLoaderApp.kt`, `data/library/LibraryPreferences.kt`; Create `ui/components/LibraryUiStyle.kt`, `LibraryBottomSwitcher.kt`; Test `ui/LibraryModeHeaderTest.kt`, `LibraryModeShortViewportTest.kt`.

**Interfaces:** расширить header callback-ами `onSearch`, `onPrivate`, `onSettings`; source/mode callbacks сохранить. `LibraryBottomSwitcher` принимает текущую вкладку и `onSelect`; темы/отступы задаёт `LibraryUiStyle`, ограниченный новыми экранами.

- [ ] Перенести заголовок/иконки/селектор и темы строго по числам ТЗ; заменить главную нижнюю навигацию в новых режимах внутренним переключателем. Переключение на Downloader и настройки остаются доступными сверху.
- [ ] Реализовать раскрывающийся поиск и доступные названия иконок. Не фиксировать высокую шапку над коротким viewport; минимальный touch target 48 dp.
- [ ] Обновить существующие тесты выбора режима/источника, настроек и короткого экрана. Проверить, что повторный выбор источника не запускает лишнее сканирование и перезапуск плеера.
- [ ] Сверить обе темы с HTML при ширине 390 dp и крупном шрифте. Commit. Тесты точных пикселей отступов не писать.

## Task 4: Галерея и обложки альбомов

**Files:** Modify `ui/screens/GalleryModeScreen.kt`; Create `ui/screens/gallery/GalleryAlbumsContent.kt`, `GalleryMediaCell.kt`, `GalleryLibraryState.kt`; Test `ui/GalleryLibraryStateTest.kt`, `LibraryModeScreensTest.kt`.

**Interfaces:** `GalleryLibraryState` содержит источник, вкладку, выбранную категорию/альбом, query и позиции списков; сохраняется через ViewModel/SavedStateHandle либо Saver. Отфильтрованная очередь всегда выдаётся в существующий `onOpen(id: String, queue: List<String>)`.

- [ ] Добавить тесты фильтров «Все медиа / Избранное / Видео», даты, совпадающих имён альбомов телефона/приложения и стабильного порядка очереди.
- [ ] Реализовать плотную четырёхколоночную сетку А, реальные даты/длительности, затем двухколоночные карточки альбомов с реальными обложками/счётчиками. Подключить ссылки Task 2 и нижний переключатель Task 3.
- [ ] Подключить поиск, папки, пустые/недоступные состояния. Восстановить состояние и прокрутку при возврате из просмотра и после поворота.
- [ ] Прогнать тесты фильтров, открытие категории/альбома и возврат; визуально сравнить портрет/landscape и обе темы с эталоном. Commit.

## Task 5: Смешанная очередь и чистый просмотр медиа

**Files:** Modify `ui/screens/MediaViewerScreen.kt`, `ui/MediaLoaderApp.kt`; Create `ui/screens/gallery/GalleryMediaViewer.kt`, `GalleryViewerState.kt`; Test `ui/GalleryMediaViewerTest.kt`.

**Interfaces:** gallery-specific presentation получает `initialId`, неизменяемый snapshot queue ids, `resolve(id)` через индекс Task 1, callbacks существующих share/edit/favorite/delete. Не ломать маршруты просмотра загрузчика и приватного раздела. Текущее фото/видео определяется состоянием pager, а не стартовым route id.

- [ ] Написать регрессии: фото→видео→фото свайпом вне группы; видео из свайпа не autoplay; уход останавливает воспроизведение; удаление/отзыв доступа выбирает соседа или сетку; private alias блокируется.
- [ ] Устранить фактическую причину неработающей очереди, проверив route snapshot, callbacks и поглощение жестов; использовать общий pager для обоих типов. Не накладывать второй независимый pager на текущий.
- [ ] Сделать панели А с реальной датой/временем и пятью действиями; сведения/имя убрать в меню. Удалить все боковые стрелки и нижний счётчик. Согласовать тап/свайп с zoom/pan; video controls не должны запускать перелистывание случайно.
- [ ] Проверить zoom, скрытие панелей, возврат редактора, сохранение позиции и privacy-тесты. Восстановление после поворота включает текущий элемент и видимость панелей. Commit.

## Task 6: Музыкальная библиотека и экран добавления

**Files:** Modify `ui/screens/MusicModeScreen.kt`, `data/library/LibraryAudioQueue.kt`, `ui/MediaLoaderApp.kt`; Create `ui/screens/music/MusicTrackRow.kt`, `PlaylistTracksScreen.kt`, `PlaylistTrackSelectionState.kt`; Test `ui/PlaylistTrackSelectionTest.kt`, `LibraryModeScreensTest.kt`.

**Interfaces:** отдельный route добавления с playlistId; `PlaylistTrackSelectionState(playlistId, selectedKeys, source, query)` сохраняется через SavedStateHandle. `toggle(key)`, `confirm()` добавляет только новые доступные уникальные ключи через `PlaylistRepository.addTracks`. `onPreview(id, queue)` запускает тот же сервис, не создаёт экранный ExoPlayer.

- [ ] Тесты: выбрать из APP, сменить на PHONE, выбрать alias/другой трек, итог без повтора; поиск/поворот не теряют выбор; существующий участник повторно не добавляется; закрытие без подтверждения не меняет playlist; файл исчез перед confirm; предпрослушивание продолжается при выходе.
- [ ] Реализовать списки/карточки плейлистов, выделение играющего трека и отдельный экран с `+`/галочкой, поиском, источником и «Добавить · N». Убрать старый текстовый AddPlaylistTracksDialog.
- [ ] Подключить реальную очередь предпрослушивания, сохранить её при последующем поиске и смене источника. Показать результат пропусков при исчезновении файлов; не раскрывать приватные имена. Сохранить операции удаления участника/плейлиста и изменения порядка.
- [ ] Запустить selection/playlist/queue/privacy тесты; проверить экран с мини-плеером и крупным шрифтом. Commit.

## Task 7: Мини-плеер и полный музыкальный плеер А

**Files:** Modify `ui/components/AudioMiniPlayer.kt`, `ui/screens/AudioPlayerContent.kt`, `ui/MediaLoaderApp.kt`; Create `ui/screens/music/MusicPlayerControls.kt`; Test `ui/MusicPlayerStateTest.kt`.

**Interfaces:** существующий `BackgroundAudioPlayer` и MediaController остаются источниками воспроизведения; добавить `onEqualizer` и `onQueue` маршруты. Не менять порядок очереди из-за открытия полного плеера.

- [ ] Перенести обложку, типографику, timeline, времена, play/pause/prev/next/shuffle/repeat и нижние favorite/eq/queue по HTML А; скорость и редактор сохранить в меню. Мини-плеер — над нижней навигацией, со своей полосой прогресса.
- [ ] Подключить настоящие обложки и состояния сервиса, seek, избранное для обоих источников. При неизвестной duration показать корректное недоступное состояние перемотки, а не фиктивный конец трека.
- [ ] Тестами проверить одну очередь/позицию при переходах mini↔full, сохранение shuffle/repeat и отсутствие повторного запуска после поворота. Проверить фоновое уведомление/наушники на доступном adb target.
- [ ] Сопоставить layout с эталоном в обеих темах, коротком экране и длинных названиях. Commit.

## Task 8: Общий эквалайзер — отдельный проверяемый модуль

**Files:** Create `data/playback/EqualizerSettingsRepository.kt`, `EqualizerAudioProcessor.kt`, `ui/screens/music/EqualizerScreen.kt`; Modify `data/playback/AudioPlaybackService.kt`, `ui/MediaLoaderApp.kt`; Test `data/playback/EqualizerAudioProcessorTest.kt`, `EqualizerSettingsRepositoryTest.kt`.

**Interfaces:** `EqualizerSettings(enabled: Boolean, gainsDb: List<Float>, preset: String)` с пятью значениями и StateFlow; репозиторий `suspend setSettings(settings)` сохраняет атомарно; обработчик `EqualizerAudioProcessor : BaseAudioProcessor`, конфигурация передаётся потоку аудио через безопасный snapshot. Точки: 60/230/910/3600/14000 Гц, gain −12…+12; значения пресетов строго из ТЗ.

- [ ] До подключения UI проверить Media3 1.9.0 API аудиоцепочки и PCM16/float-путь. Выбрать программный пятиполосный фильтр с плавной сменой коэффициентов и запасом усиления; если нельзя обеспечить реальную обработку на действующем пути, сначала решить это, а не отдавать декоративный экран.
- [ ] Тесты DSP: bypass сохраняет вход; нулевые gains не меняют скорость/каналы; boost/cut меняет RMS синуса около целевой полосы; ограниченные gain не дают overflow/NaN; изменение sample rate и stereo после flush не используют прежнее состояние; полоса выше Найквиста не включается. Репозиторий восстанавливает выключатель, значения и «Свой» после перезапуска.
- [ ] Реализовать обработчик в сервисе, lifecycle/cleanup и обновление без пересоздания очереди. Проверить несовместимость с offload/passthrough и переключать на поддерживаемый PCM-путь только по необходимости; выключенный эффект должен иметь минимальные накладные расходы.
- [ ] Реализовать экран А с общим выключателем, пресетами, круглыми бегунками, текущими дБ и сбросом. Сохранение автоматическое; никаких индивидуальных настроек треков.
- [ ] Проверить DSP/settings тесты и на доступном устройстве: фон, seek, Bluetooth/проводной выход, переключение треков разных форматов, отсутствие щелчков/искажений на пресетах. При отсутствии устройства пометить этот gate непроверенным. Commit.

## Task 9: Прогресс основного и плавающего видео

**Files:** Modify `ui/screens/MediaViewerScreen.kt`, `data/playback/FloatingVideoService.kt`, `FloatingVideoGesturePolicy.kt`; Create `data/playback/FloatingVideoSeekBar.kt`; Test `data/playback/FloatingVideoSeekPolicyTest.kt`.

**Interfaces:** overlay seekbar получает существующий Player, читает позицию только пока окно живо, отдаёт seek в миллисекундах; границы вычисляются в координатах окна. Touch-область полосы исключается из drag/resize окна. Во время scrub таймер не перетирает положение бегунка.

- [ ] Опустить основную timeline ближе к нижней панели; проверить fullscreen/inline, landscape/portrait и safe insets.
- [ ] Добавить тонкую полосу по нижнему краю overlay с расширенным touch target; tap и drag→seek, исходный play/pause сохранить. Проверить, что close/expand не перекрыты областью полосы.
- [ ] Тесты policy: x=0→0, x=width→duration, выход за края clamp, неизвестная duration блокирует seek; scrub не двигает окно; drag вне полосы работает; cleanup удаляет callbacks.
- [ ] На устройстве проверить перемотку и сохранение позиции при возврате overlay→full. Commit.

## Task 10: Финальная сверка и передача результата

- [ ] Пройти каждый раздел ТЗ и связать с готовой задачей/проверкой. Убедиться, что декоративные строки и фото HTML не попали в продукт, старые действия редактора и скорости доступны, у удаления красная иконка, в просмотре нет стрелок.
- [ ] Снять реальные экраны А при 390 dp и около 740 dp высоты в обеих темах: медиа, альбомы, просмотр, треки, плейлист, добавление, полный плеер, эквалайзер. Сравнить композицию/отступы с HTML; исправить видимые расхождения. Landscape/large-font проверять на достижимость элементов, не растягивать портрет механически.
- [ ] Запустить полный unit-suite и release-compile gate, один раз после финальных правок:

```bash
export JAVA_HOME=/workspace/medialoader-1.6.14-toolchain/jdk-17
export ANDROID_HOME=/workspace/medialoader-1.6.14-toolchain/android-sdk
./gradlew :app:testDebugUnitTest :app:assembleRelease -Parm64Release --offline --console=plain --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx3072m -Pkotlin.compiler.execution.strategy=in-process
```

- [ ] Зафиксировать `BUILD SUCCESSFUL`, число выполненных/пропущенных тестов и конкретные непроверенные сценарии устройства. Повторять проверки только после изменений или реальной ошибки.
- [ ] Передать пользователю результат с реальными скриншотами и кратким перечнем функциональных изменений. Подпись/выгрузка новой версии — следующий шаг по команде пользователя.

## Проверка плана перед передачей

Покрытие ТЗ: общий UI→3; источники/privacy→1/2; галерея/альбомы→4; просмотр→5; музыка/добавление→6; плеер→7; эквалайзер→8; видео→9; визуальная/общая регрессия→10. Интерфейсы refs/queues используются согласованно. Главные реальные риски вынесены в Review Focus и привязаны к тестам владельца.

Рекомендуемый порядок: 1→2→3→4→5→6→7→8→9→10. Работать последовательными локальными этапами и показывать реальные экраны после 4/5 и 7/8. Каждый этап заканчивается работоспособным состоянием. Новую версию не публиковать до итоговой проверки и команды пользователя.
