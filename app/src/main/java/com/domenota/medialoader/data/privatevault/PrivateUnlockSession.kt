package com.domenota.medialoader.data.privatevault

/** User preference only. The authorization itself never leaves process memory. */
enum class PrivateUnlockDuration(val title: String, internal val millis: Long?) {
    ON_EXIT("До выхода из раздела", 0L),
    ONE_MINUTE("1 минута", 60_000L),
    FIVE_MINUTES("5 минут", 300_000L),
    FIFTEEN_MINUTES("15 минут", 900_000L),
    THIRTY_MINUTES("30 минут", 1_800_000L),
    SIXTY_MINUTES("60 минут", 3_600_000L),
    UNTIL_PROCESS_END("До закрытия приложения", null);
}

/** One-shot re-entry permit, separate from the live file access state.
 * All calls must be under the access manager monitor. No wall clock or storage. */
internal class PrivateUnlockSession(private val now: () -> Long) {
    private var permit: Permit? = null
    private data class Permit(val started: Long, val duration: Long?)

    fun clear() { permit = null }

    fun suspend(duration: PrivateUnlockDuration, wasUnlocked: Boolean) {
        // Duplicate navigation/background events cannot refresh an existing permit.
        if (!wasUnlocked) return
        permit = if (duration.millis == 0L) null else Permit(now(), duration.millis)
    }

    fun consume(): Boolean {
        val candidate = permit ?: return false
        permit = null
        val elapsed = now() - candidate.started
        return elapsed >= 0L && (candidate.duration == null || elapsed < candidate.duration)
    }
}
