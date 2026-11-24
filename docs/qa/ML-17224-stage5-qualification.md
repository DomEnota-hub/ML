# MediaLoader 1.7.224 · этап 5 · qualification attempt 1 и исправление регрессии

Дата: 24.11.2026 МСК.
Функциональный Draft PR #189; технический CI-only Draft PR #190.
Исходный frozen HEAD: `0c5ea380eda50ac75397f8cf4ec8e07d65585919`; версия 1.7.224 / code 284.

## Qualify attempt 1

GitHub Actions run: https://github.com/DomEnota-hub/ML/actions/runs/45344360294

| Gate | Фактическое завершение |
|---|---|
| Quick | PASS |
| JVM test-source compilation | PASS |
| Full JVM | FAIL |
| Lint | PASS |
| ARM64 / candidate verification | PASS |
| Exact qualification | FAIL, gate требует успешного JVM |

Кандидат из первого цикла **UNQUALIFIED**, не подписывать, не публиковать, не переносить в Drive как release.

Точная причина: `DuplicateScanScopeDialogTest#unknownFolderRetainsReadableCountWithLongDisplayName`.
JVM отчёт: один тест с `AssertionError` на `assertExists` в тестовом файле, строка 158.
В диагностическом XML Compose прямо указано: узел `scope-row-details-volume:external_primary|Music`
отсутствует в *merged* semantics tree, но **существует в unmerged tree** (точно один).
На первом bulk Gradle отчитался о 2448 тестах, одном failed и одном skip;
runner завершил неполную full-квалификацию. Это не свидетельствует, что остальные
изолированные методы прошли: они не были выполнены в упавшем full-run.

Исправление: только этот тест обращается к существующему вложенному узлу через
`onNodeWithTag(..., useUnmergedTree = true)`. Проверка текста `1 файл` не удалена
и не ослаблена, поведение приложения, Compose semantics и требуемые 2485 ID не меняются.
Ранее успешно выполненные этапы не засчитываются как квалификация другого SHA.

## Следующее выполнение

После завершения первого run удалена `ci:qualify` с технического PR #190.
Зафиксировать новый exact SHA вместе с этим исправлением и повторно поставить
`ci:qualify` только один раз: fresh Quick → JVM 2485 ID → lint → ARM64
→ DEX и exact-source qualification. Разрешённый skip остаётся ровно один.

Статус до завершения нового CI: `ATTEMPT1_FAILED / ROOT_CAUSE_CONFIRMED / FIX_COMMITTED /
REQUALIFICATION_REQUIRED / DEVICE_QA_PENDING / NOT_STABLE`.
