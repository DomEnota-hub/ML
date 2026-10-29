package com.domenota.medialoader.core.diagnostics

import java.net.URI
import java.util.Locale

/** User-supplied fixtures are kept only in memory and never serialized into reports. */
data class FullDiagnosticInput(
    val links: Map<DiagnosticSource, String>,
    val artist: String,
    val title: String,
) {
    fun linkFor(source: DiagnosticSource): String? = links[source]

    companion object {
        val EMPTY = FullDiagnosticInput(emptyMap(), "", "")

        fun parse(rawLinks: String, artist: String, title: String): FullDiagnosticInput {
            val links = buildMap {
                rawLinks.lineSequence().take(20).map(String::trim).forEach { raw ->
                    val uri = runCatching { URI(raw) }.getOrNull() ?: return@forEach
                    if (!uri.scheme.equals("https", true) || uri.userInfo != null) return@forEach
                    val host = uri.host?.lowercase(Locale.ROOT) ?: return@forEach
                    val source = when (host) {
                        "instagram.com", "www.instagram.com", "m.instagram.com" -> DiagnosticSource.INSTAGRAM
                        "youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be" -> DiagnosticSource.YOUTUBE
                        "vk.com", "m.vk.com" -> DiagnosticSource.VK
                        "rutube.ru", "www.rutube.ru" -> DiagnosticSource.RUTUBE
                        else -> null
                    }
                    if (source != null && source !in this) put(source, raw)
                }
            }
            return FullDiagnosticInput(links, artist.trim().take(120), title.trim().take(120))
        }
    }
}

/** Pure classification: success applies to the named stage, never to an entire download. */
object SourceWorkflowRules {
    fun missingInput(source: DiagnosticSource): DiagnosticOutcome = DiagnosticOutcome.unchecked(
        if (source in listOf(DiagnosticSource.PRIMARY_MUSIC, DiagnosticSource.RESERVE_MUSIC))
            "Не задан контрольный поисковый запрос для этого источника."
        else "Не задана контрольная ссылка для этого источника.")

    fun extracted(count: Int): DiagnosticOutcome = if (count > 0) DiagnosticOutcome(
        DiagnosticState.OK, DiagnosticEvidence.VERIFIED,
        "Получены метаданные $count объектов.",
        "WORKFLOW_METADATA_OK",
    ) else DiagnosticOutcome(
        DiagnosticState.UNAVAILABLE, DiagnosticEvidence.VERIFIED,
        "Рабочий сценарий завершился без результатов; материал или запрос могли измениться.",
        "WORKFLOW_EMPTY",
    )

    fun blocked(): DiagnosticOutcome = DiagnosticOutcome(
        DiagnosticState.UNAVAILABLE, DiagnosticEvidence.VERIFIED,
        "Рабочий сценарий не смог получить материал. Возможны ограничения доступа, VPN или сессии.",
        "WORKFLOW_ACCESS_LIMITED",
    )

    fun authorizationRequired(): DiagnosticOutcome = DiagnosticOutcome(
        DiagnosticState.UNAVAILABLE, DiagnosticEvidence.VERIFIED,
        "Источник запросил вход для контрольного материала. Текущая сессия не дала доступ.",
        "WORKFLOW_AUTH_REQUIRED",
    )

    fun unavailableMaterial(): DiagnosticOutcome = DiagnosticOutcome(
        DiagnosticState.UNAVAILABLE, DiagnosticEvidence.VERIFIED,
        "Контрольный материал недоступен или не поддерживается.",
        "WORKFLOW_CONTENT_UNAVAILABLE",
    )

    fun rateLimited(): DiagnosticOutcome = DiagnosticOutcome(
        DiagnosticState.UNAVAILABLE, DiagnosticEvidence.VERIFIED,
        "Источник ограничил частоту запросов. Повторную проверку лучше отложить.",
        "WORKFLOW_RATE_LIMITED",
    )

    fun musicTimeout(stage: String): DiagnosticOutcome = DiagnosticOutcome(
        DiagnosticState.UNAVAILABLE, DiagnosticEvidence.NONE,
        when (stage) {
            "PAGE" -> "Страница резервного поиска не открылась за 12 секунд."
            "DOM_CALLBACK" -> "Страница открылась, но скрипт поиска не ответил за 16 секунд."
            "SCRIPT_PARSE" -> "Скрипт поиска вернул данные неизвестного формата."
            "DOM_STRUCTURE" -> "Страница загрузилась, но результаты не обнаружены в ожидаемой структуре."
            else -> "Страница открылась, но результаты не появились за 16 секунд."
        },
        when (stage) {
            "PAGE" -> "MUZMO_PAGE_TIMEOUT"
            "DOM_CALLBACK" -> "MUZMO_DOM_CALLBACK_TIMEOUT"
            "SCRIPT_PARSE" -> "MUZMO_SCRIPT_PARSE_FAILED"
            "DOM_STRUCTURE" -> "MUZMO_DOM_STRUCTURE_UNKNOWN"
            else -> "MUZMO_RESULTS_TIMEOUT"
        },
    )

    fun failed(): DiagnosticOutcome = DiagnosticOutcome(
        DiagnosticState.UNAVAILABLE, DiagnosticEvidence.NONE,
        "Рабочий сценарий не завершился; причина требует изучения технического журнала.",
        "WORKFLOW_UNAVAILABLE",
    )
}
