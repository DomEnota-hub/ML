package com.domenota.medialoader.data.catalog.visibility

/** An Intent cannot be trusted as an instruction to mutate private media.
 * It carries only a nonce matching a process-local, user-requested command.
 * A restarted Service after process death finds no in-memory command and stops.
 * Durable batch journals are never auto-replayed from Service.onStartCommand. */
internal sealed interface VisibilityWorkCommand {
    data class SetHidden(val folderId: String, val hidden: Boolean) : VisibilityWorkCommand {
        init { require(folderId.isNotBlank() && folderId.length <= 256) }
    }
    data object ExplicitResume : VisibilityWorkCommand
}

internal data class VisibilityWorkReservation(val id: Long, val command: VisibilityWorkCommand)

internal class VisibilityWorkCommandSlot {
    private var nextId = 0L
    private var queued: VisibilityWorkReservation? = null
    private var running: VisibilityWorkReservation? = null

    @Synchronized fun offer(command: VisibilityWorkCommand): VisibilityWorkReservation {
        check(queued == null && running == null) { "Перенос уже выполняется" }
        return VisibilityWorkReservation(++nextId,command).also { queued = it }
    }

    /** Returns null for unknown/stale/replayed intents. Never execute twice. */
    @Synchronized fun claim(id: Long): VisibilityWorkReservation? {
        val request = queued?.takeIf { it.id == id && running == null } ?: return null
        queued = null
        running = request
        return request
    }

    @Synchronized fun cancelUnclaimed(id: Long): Boolean {
        if (queued?.id != id) return false
        queued = null
        return true
    }

    @Synchronized fun finish(id: Long): Boolean {
        if (running?.id != id) return false
        running = null
        return true
    }

    @Synchronized fun isBusy(): Boolean = queued != null || running != null
}
