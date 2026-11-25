# MediaLoader CI v2 — актуальный порядок с 26.11.2026

**Единый CI: .github/workflows/ml-ci.yml. Старый универсальный baseline и обязательный полный qualify для каждого QA APK отменены.** Исторические отчёты в docs/ci и docs/qa — доказательства прошлых прогонов, не инструкции. Работать только от текущего HEAD из Сводки.md, не от main/старых PR. Проверенный переход: [#45707065400](https://github.com/DomEnota-hub/ML/actions/runs/45707065400), SHA 5dc52b674ff9cd63bbcdf6f79fd20f765fb5c365, 34 helper PASS, chrome 49/49, 0 skip/isolated, ARM64/DEX 292550 methods 0 findings, QA_ONLY receipt validated. Прогон без подписи и доставки; не является новой версией или stable.

## Выбор режима
| Сценарий | Режим |
|---|---|
| Документы | Без Gradle/CI приложения |
| Локальная правка | quick + релевантный профиль |
| Функциональная правка | quick + затронутые компоненты и их зависимости |
| Критические изменения | профильные защитные тесты + lint; verify при широком/неизвестном риске |
| QA APK | qa + достаточные профили; ARM64/DEX и QA_ONLY |
| Стабильный выпуск | qualify: полный актуальный JVM, lint, ARM64/DEX, QUALIFIED |
| Измерения | perf, без APK и допуска |

Запуск только явный workflow_dispatch либо одна метка ci:quick-<profile>, ci:qa-<profile>, ci:verify, ci:qualify. Через dispatch quick/qa поддерживают quick_profiles (запятая) и quick_lint. Через метку — один профиль; если комбинированный охват необходим, использовать dispatch, а при его недоступности расширенный подход по риску, не притворяться что узкий профиль достаточен. QA с baseline-only запрещён. Полный qualify — stable или обоснованная системная проверка, не штатный QA. При активном прогоне не менять HEAD/не ставить новую CI-метку; удалить метку после терминального статуса.

## Профили и проверки
Состав определяется scripts/ci/focused.py и docs/ci/required-jvm-tests.json; числа не являются нормой. baseline (3 ID) — минимальная промежуточная проверка, не замена тематической. chrome — локальные визуальные правки Header/Drawer/Settings без изменений маршрутов, прав доступа, состояния, persistence и данных; navigation — маршруты/допуск; collections — коллекции, обложки, членство; archive, privacy, library, playback, editors, download, storage, diagnostics — по затронутому функционалу. При общей архитектуре выбирать реальный охват/verify. Защитные проверки привязаны к рискованным областям; archive/privacy/storage требуют lint, в других профилях добавить по риску. Компиляция androidTest обязательна для verify/qualify, archive/privacy/storage/editors, иначе по реальной необходимости. Тестовые результаты не кэшировать. Не скрывать failures/новые skips. Устаревшие тесты удалять после смысловой оценки; совместимость/сохранность пользовательских данных не ослаблять.
Полный JVM: scripts/run-jvm-ci.py full, bulk + граф свежих JVM для изолированных методов. Действующая изоляция сохранена для Compose; Bitmap-only cover fallback вынесен в обычный Robolectric-класс. В LibraryVisualCaptureTest штатно только smoke; PNG по явному ML_VISUAL_CAPTURE_DIR. При точечных проверках выбираются только изолированные методы выбранных классов; не запускать коллекции по привычке. XML/ID и маркеры должны соответствовать выбранному набору. Full inventory сейчас 2542, один ранее разрешённый live Shazam skip, число изменяется при осмысленном обновлении тестов. Raw evidence сохранять. Повтор после доказанного transport failure до тестов допустим; реальный функциональный сбой исправить.

## Допуск сборки и доставка
QA: successful run с quick+jvm+arm64 (+lint если требовался), QA_ONLY.json, coverage фактических профилей, live HEAD, exact source и хеши. 
`python3 scripts/ci/gate.py consume-qa /path/receipt/QA_ONLY.json /path/candidate`
Stable: success quick+jvm+lint+arm64, QUALIFIED.json, full coverage:
`python3 scripts/ci/gate.py consume /path/receipt/QUALIFIED.json /path/candidate`
QA receipt отвергается stable gate. Не подписывать stale, failed или просто UNQUALIFIED artifact. Успешный предыдущий attempt допустим только того же run/SHA. Сверка SHA в точке запуска и допуска, не повторять без причины. Офлайн подпись DomEnota, v2/v3, сертификат, zipalign, ARM64, package/version, DEX подписанного APK, split и SHA-256. Точные исходники, README и контрольные суммы на Drive. Не публиковать Releases, не передавать ключ/GAPI, не использовать эмулятор. Устройство проверяет владелец; stable только по его решению.

## Эксплуатация
Никаких альтернативных путей сборки. allowed-workflows.json содержит штатный CI и разрешённые ручные model research/signing tools без полномочий выпуска. Исторические workflow/runner/инструкции не возвращать из Git. Не менять версии приложения при инфраструктурной работе, не удалять ранее доставленные APK. При достаточных локальных проверках лишний CI не нужен. При активном CI проверять состояние в текущем сеансе, не обещать фоновых действий. Сводка содержит рабочую ветку и текущую точку. 

## Итог перехода
CI v2 подтверждён: chrome QA #45707065400; full qualify #45710936819 (2567 PASS, 1 согласованный skip); QA-storage с обязательным lint #45715170363 (868/868, 0 skips, QA_ONLY с тремя lanes). Последний run проверил удаление 27 недостижимых тестов старого физического транспорта и новую проверку настоящих входов менеджера, оставив legacy journals/codec/read-only recovery. Полный inventory теперь 2542, 36 isolated methods. Full на этом последнем test-only SHA не запускался; затронутый профиль прошёл полностью. Сохранять fresh workers у реально нестабильных Compose-тестов; не усложнять схему ради редкого full запуска. Полный Lint сохраняется, warm-cache lint 1m46, ARM64 1m30 на test-only SHA, cold/changed-source время не гарантировано. Переиспользовать один активный PR #200 и рабочую ветку, чтобы кеши не терялись при каждой QA. Старые 24 PR закрыты без merge после подтверждения ancestry; 7 старых меток сняты. Старые refs не использовать. Не удалять историю релизов; ограничение GitHub-администратора вне этого регламента.
