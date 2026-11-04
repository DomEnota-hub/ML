# Editor reference follow-up implementation plan

**Goal:** выполнить пять пунктов пользователя поверх 1.7.113, без полного цикла CI.
**Architecture:** существующий Compose UI; одна математическая модель геометрии для preview/export; дедупликация только проекции выбора файлов, не базы и не файлов.
**Tech Stack:** Kotlin, Compose, Android Canvas/Bitmap, Robolectric.
**Spec:** запрос пользователя от 2026-11-04 и Drive «Образцы»: 1.jpg, 2.jpg, Сверка.mp4 (28.235 с), изучены локально.

## Ограничения

- Основная ветка/сборка 1.7.113 сохранена; без GitHub push и широкого CI.
- Только адресные проверки: EditorItemsProjection/EditorFilePicker, геометрия/линейка/касания и ARM64 Release.
- Имена/размеры не являются идентичностью файла. Файлы не удаляются.
- Визуальные точки остаются 7/9 dp с окантовкой 11 dp; расширяется только захват.

## Порядок

- [x] Убрать алиасы из проекции editorItems, используя уже построенную карту canonical ID и URI; сохранить исходные ID для навигации. Покрыть повторные URI, алиасы с разными URI и одноимённые разные файлы для всех типов.
- [x] Текст альбома/сортировки в EditorFilePickerDialog: onSurface (белый в показанной тёмной теме, контрастный в светлой).
- [x] PhotoGeometryDialog: захват по начальному касанию, сохранение смещения пальца до точки (без скачка), радиус112dp; отмена/undo сохраняются. Линейка: 2dp/unit вместо4dp, точность накопителя сохраняется во всех режимах.
- [x] Перспектива: центрированная homography с сохранением направления H из образца, V отрицательный приближает верхнюю сторону. Устранить независимую нормализацию по X/Y, скрывавшую/растягивавшую перспективу. Стабильный знаменатель при совместных экстремумах.
- [x] Добавить distortion (-100..100, 0 identity), радиальную монотонную сетку с нормализованными координатами. Positive увеличивает центр, negative сжимает центр относительно краёв, как на19–22с. Canvas.drawBitmapMesh; общая preview/export реализация, bounded preview960px.
- [x] VideoV8Icons: обновить trim/speed/volume/reverse/trash/separate/replace/crop/copy по двум jpg; AUDIO/volume должен рисовать динамик, а не ноту. Такой же stroke-стиль для geometry horizon/perspective/distortion/free.
- [x] Запустить выбранные тесты и ARM64 Release, проверить production подпись/пакет. Следующая версия1.7.114/code175. Зафиксировать локально, передать подписанный APK через Dropbox по принятому в сессии способу.

## Проверить при саморевью

Файлы разных типов с одинаковыми именами; старые URI алиасы; углы на краях viewport и перекрытие зон; отмена и reset distortion; совместные экстремумы perspective/distortion и неизменность исходного bitmap. Физический телефон недоступен — не выдавать адресные unit за проверку touch/FPS на устройстве.

## Адресный результат

32 теста / 0 failures / 0 errors / 0 skipped. В том числе native Canvas distortion и Compose-захват за пределами прежней92dp зоны с проверкой сохранения смещения до пальца. Лог: /tmp/ml114-targeted-build.log. Release собирается тем же запуском; полный CI/lint/emulator не запускался.

## Завершение

Итого33 уникальных адресных теста PASS (32 первоначально, затем11 только geometry/lens после исправления порядка — включая новый независимый pixel oracle). Final Release BUILD SUCCESSFUL4m36s, лог /tmp/ml114-release-final.log. Runtime source cff47121eab7bfdb746b8e93cfadcc482f3b5143. APK1.7.114/code175/com.domenota.medialoader/ARM64/non-debug signed official certificate. SHA256 b94795ce58094f42e47ae5ec8f273b71f10de4ce07b2c9e1cb081ec7c79d2713,104576422bytes. Dropbox upload completed, metadata size verified. Подробности/ссылка: /workspace/ML_1.7.114_HANDOFF_20261104.md. ПолныйCI и физическое устройство не проверялись.
