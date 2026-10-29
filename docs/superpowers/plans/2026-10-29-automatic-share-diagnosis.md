# Автоматическая загрузка: диагностика журнала 1.7.95

Исследовано **после** завершения UI, эквалайзера, видео, итогового ревью,
исправлений и полного тестового/сборочного прогона. Продуктовый код автозагрузки
в рамках этого дополнительного диагностического запроса не менялся.

## Что подтверждает журнал

Экспорт: 2026-10-29 09:58 МСК; 60 строк, события 08:58–09:15, версия 1.7.95 (157),
Android 16. Ни исходные URL, ни идентификаторы файлов/сессий здесь не сохраняются.

| Время МСК | Подтверждённый сценарий | Результат |
| --- | --- | --- |
| 08:58:50–08:59:01 | `Instagram visual auto-download scheduled` → анализ → очередь → `Instagram automatic share done` | 1/1 файл завершён |
| 09:05:28–09:05:35 | `Received shared text intent · generation=1` → анализ → обычная очередь | файл завершён; фонового сценария Share нет |
| 09:08:53–09:09:00 | `Received shared text intent · generation=2` → анализ → обычная очередь | файл завершён; фонового сценария Share нет |
| 09:13:47 | HTTP 403 миниатюры Instagram CDN | относится к обложке, не к передаче файла |

Всего: три начала/успеха анализа; три постановки в очередь, три запуска и три
успешных завершения скачивания; ноль ошибок скачивания. Единственный WARN —
миниатюра. Журнал **не подтверждает отказ самого фонового загрузчика**: единственная
зафиксированная фоновая задача отработала. Он подтверждает, что последующие две
ссылки были обработаны главным окном в обычном сценарии.

## Дефект маршрутизации в коде

- `data/share/InstagramShareActivity.kt:30` проверяет настройку «Автоматический»
  и каноническую ссылку, затем ставит `InstagramAutoShareWorker`.
- `MainActivity.kt:30` и `:47` принимают ACTION_SEND без проверки этой настройки.
  Ветка `onNewIntent` пишет ровно тот `Received shared text intent`, который виден
  в 09:05 и 09:08.
- `ui/MediaLoaderApp.kt:792` при получении такого текста безусловно открывает
  главное окно/выбор (`showPicker = true`) и запускает обычный анализ.
- MainActivity экспортируется, поэтому явный ACTION_SEND в её компонент возможен,
  даже при том, что текущий SEND intent-filter расположен на InstagramShareActivity.

**Подтверждённая слабая точка:** режим зависит от точки входа. ACTION_SEND, пришедший
сразу в MainActivity, обходит автоматическую обработку, даже если настройка включена.
Это объясняет разные сценарии при одинаковой включённой настройке.

## Что нельзя достоверно установить по этому экспорту

MainActivity могла получить ссылку напрямую (например, сохранённая явная цель
«Поделиться»), либо после ручной переадресации из InstagramShareActivity.
В журнале нет входного компонента, причины переадресации и значения настройки
для двух этих попыток. Настройка на момент экспорта тоже не записана. Поэтому
конкретное поведение Xiaomi/Instagram или сброс настройки не объявляются доказанными.
Другие неудачные попытки из описания пользователя в этих 60 строках не представлены.

Парсер автоматического режима и InstagramProvider используют один
InstagramLinkParser; обычный анализ обеих ссылок успешно нашёл Instagram-медиа.
Версия о непригодных ссылках этим фрагментом не поддерживается. Проверка сети,
аккаунта и background/foreground ограничений здесь не показывает отказов.

## Минимальное направление исправления

Единый выбор ручного/автоматического маршрута для обоих входов MainActivity
(`onCreate`, `onNewIntent`) и прозрачного receiver до установки sharedText в UI.
Сохранить дедупликацию WorkManager и ожидание фиксации задачи; не запускать вторую
очередь параллельно с picker. Ручная вставка и другие источники должны остаться
интерактивными.

Для проверяемости журналировать безопасные признаки: точку входа, action/MIME,
режим, результат распознавания ссылки, выбранный маршрут/причину fallback и ID
задачи — без cookie и полных URL. Проверить холодный и уже открытый MainActivity,
прямой явный SEND, обычный receiver, ручной режим, другие источники, повторную
ссылку и поворот, не создавая повторную задачу.

Это результат диагностики и конкретный план локальной правки; устранение обхода
не объявляется выполненным. Согласованная переработка Галереи/Музыки завершена
отдельно и прошла свои итоговые проверки.


## Release 1.7.96 — automatic share fix completed

User authorized the automatic-download fix and release, requesting minimal checks.
This supersedes the earlier diagnosis-only/no-publication state. Shared routing now
covers cold MainActivity ACTION_SEND and warm onNewIntent; existing receiver and
worker retain persistence-before-return and deduplication. Manual routes unchanged.
Nine targeted tests passed; arm64 release build passed (7m32s). Signed APK certificate
matches 1.7.95. Full suite was not repeated; earlier feature gates remain complete.
Release notes and artifact checksum: `docs/RELEASE_1.7.96.md`.
Google Drive upload succeeded on retry and metadata confirms 103597119 bytes:
https://drive.google.com/file/d/14ekw1UMkCm84B4IUrkw_4Pb66N5dcQPM/view?usp=drivesdk
No raw diagnostic data or signing secrets committed. Device/OEM runtime testing
remains for installation; no claim of physical-device verification.
