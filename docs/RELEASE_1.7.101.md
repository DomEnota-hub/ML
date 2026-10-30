# MediaLoader 1.7.101 (163)

## Множественный выбор в Галерее

Долгое нажатие на миниатюру включает выбор. Короткие нажатия отмечают другие
объекты; верхняя панель показывает количество, выбор всех видимых файлов,
отмену и красную кнопку удаления. Работает в общей сетке и внутри альбомов,
с учётом текущего источника/поиска. Отмена удаления сохраняет выбор.
Меню отдельного файла открывается кнопкой ⋮ на миниатюре.

## Доступ к изменению и удалению

При первом подходящем входе в Галерею или Музыку на Android12+ приложение
предлагает один раз открыть специальный доступ «Управление медиафайлами».
Его включает сам пользователь на экране Android. «Не сейчас» и отмена
запоминаются; повторное включение доступно через настройки приложения.

С выданным доступом остаётся только наше подтверждение удаления, без
интерактивного системного запроса MediaStore. Без него телефонные файлы
удаляются общим системным запросом. В смешанной выборке загрузок приложения
и телефонных файлов без специального доступа нужно также подтвердить
перемещение загрузок в корзину: системное окно разрешает лишь свою часть.

## Пакетное удаление

Выбранные телефонные файлы передаются в MediaStore одним запросом вместо
запроса на каждый объект. Очень большие списки делятся на части до2000URI
по ограничению Android. Без специального доступа Android может попросить
подтвердить каждую такую часть. Загрузки приложения сохраняют прежнее
перемещение в корзину; приватные файлы не включаются в публичный выбор.

Ожидающий запрос сохраняет снимок ID/URI независимо от обновлений каталога.
Выделение и запрос хранятся в сжатом сохранённом состоянии, чтобы выбор
тысяч файлов не переполнял Binder при повороте экрана. Повтор после ошибки
продолжает необработанные элементы. Перемещение в корзину идемпотентно.

## Проверка

Только MediaDeletionPolicyTest и GalleryMultipleSelectionTest плюс arm64
release-сборка. Результат и подпись фиксируются после завершения.
Реальную выдачу специального доступа и поведение OEM проверяет пользователь.


## Completed / published

- Five distinct focused cases passed: MediaDeletionPolicyTest4 (confirmation policy,
  mixed-selection consent, URI batches and dedup, 11000-item saved-state round trip
  under128KiB); GalleryMultipleSelectionTest1 (long press, second item, one ordered
  batch callback, select all and cancel, no accidental viewer open).
- Initial UI test could not resolve ComponentActivity in the release manifest;
  switched only its harness to Robolectric.buildActivity + createEmptyComposeRule.
  The corrected single UI case passed; the four passing policy cases were not repeated.
- Final command /tmp/release-1.7.101-package.log: BUILD SUCCESSFUL in3m6s,
  61 tasks (15 executed,46 up-to-date). No full suite or emulator.
- APK version1.7.101/code163, arm64, 103650367bytes.
- SHA256 c42532b58839cca63ffb16f19e14f22a156c3c4a28045389f68473d09fcef9ac
- zipalign-P16 and apksigner verification passed; certificate matches1.7.100.
- APK /workspace/releases/MediaLoader_1.7.101/MediaLoader_1.7.101.apk
- Drive https://drive.google.com/file/d/1AsBD-yD19B3IGrh7YhaZuJwNfNqsJG55/view?usp=drivesdk
