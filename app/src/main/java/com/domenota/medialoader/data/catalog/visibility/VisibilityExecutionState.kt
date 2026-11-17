package com.domenota.medialoader.data.catalog.visibility

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Presentation-independent, fail-closed lifecycle for one user-directed transfer.
 * The durable move/batch journals remain the sole source of truth about media.
 * This state is process memory only; after process death the UI must re-check the
 * journals and explicitly ask the user whether to resume. */
internal enum class VisibilityExecutionPhase {
    IDLE, PREPARING, RUNNING, PAUSE_REQUESTED, PAUSED,
    WAITING_FOR_ANDROID, VERIFYING, COMPLETED, FAILED, RECOVERABLE_PENDING
}

internal data class VisibilityExecutionSnapshot(
    val phase: VisibilityExecutionPhase = VisibilityExecutionPhase.IDLE,
    val confirmed: Int = 0,
    val total: Int = 0,
    val hasPendingJournal: Boolean = false,
    val safeFailureCode: String? = null,
) {
    init {
        require(confirmed >= 0 && total >= 0 && confirmed <= total)
    }
    val active: Boolean get() = phase in setOf(
        VisibilityExecutionPhase.PREPARING, VisibilityExecutionPhase.RUNNING,
        VisibilityExecutionPhase.PAUSE_REQUESTED, VisibilityExecutionPhase.WAITING_FOR_ANDROID,
        VisibilityExecutionPhase.VERIFYING,
    )
}

internal class VisibilityExecutionStateMachine {
    private val current = MutableStateFlow(VisibilityExecutionSnapshot())
    val state = current.asStateFlow()

    /** A cold start never replays file deletion or Android permission on its own. */
    @Synchronized fun restoreFromJournal(pending: Boolean, confirmed: Int = 0, total: Int = 0) {
        check(!current.value.active) { "Нельзя подменять активный перенос" }
        current.value = VisibilityExecutionSnapshot(
            phase = if (pending) VisibilityExecutionPhase.RECOVERABLE_PENDING else VisibilityExecutionPhase.IDLE,
            confirmed = confirmed, total = total, hasPendingJournal = pending,
        )
    }

    @Synchronized fun startNew() {
        check(current.value.phase == VisibilityExecutionPhase.IDLE ||
            current.value.phase == VisibilityExecutionPhase.COMPLETED) { "Перенос уже выполняется или требует проверки" }
        check(!current.value.hasPendingJournal) { "Найден незавершённый перенос" }
        current.value = VisibilityExecutionSnapshot(VisibilityExecutionPhase.PREPARING)
    }

    @Synchronized fun beginExplicitResume() {
        check(current.value.phase == VisibilityExecutionPhase.RECOVERABLE_PENDING ||
            current.value.phase == VisibilityExecutionPhase.PAUSED ||
            current.value.phase == VisibilityExecutionPhase.FAILED && current.value.hasPendingJournal) {
            "Нет явно подтверждённого незавершённого переноса"
        }
        current.value = current.value.copy(phase = VisibilityExecutionPhase.PREPARING, safeFailureCode = null)
    }

    @Synchronized fun running(confirmed: Int, total: Int) {
        val old = current.value
        check(old.phase == VisibilityExecutionPhase.PREPARING ||
            old.phase == VisibilityExecutionPhase.RUNNING) { "Недопустимое состояние для переноса" }
        check(total >= old.confirmed && confirmed >= old.confirmed && confirmed <= total) {
            "Число подтверждённых файлов не может уменьшаться"
        }
        current.value = old.copy(phase = VisibilityExecutionPhase.RUNNING,
            confirmed = confirmed, total = total, hasPendingJournal = true)
    }

    @Synchronized fun checkpoint(confirmed: Int, total: Int) {
        val old = current.value
        check(old.phase in setOf(VisibilityExecutionPhase.RUNNING, VisibilityExecutionPhase.PAUSE_REQUESTED,
            VisibilityExecutionPhase.WAITING_FOR_ANDROID)) { "Контрольная точка вне переноса" }
        check(confirmed >= old.confirmed && total >= confirmed && total >= old.total) {
            "Некорректная контрольная точка"
        }
        current.value = old.copy(confirmed = confirmed, total = total,
            phase = if (old.phase == VisibilityExecutionPhase.PAUSE_REQUESTED) VisibilityExecutionPhase.PAUSED
                else old.phase,hasPendingJournal = true)
    }

    @Synchronized fun requestPause() {
        check(current.value.phase == VisibilityExecutionPhase.RUNNING ||
            current.value.phase == VisibilityExecutionPhase.PREPARING) { "Нельзя приостановить этот этап" }
        current.value = current.value.copy(phase = VisibilityExecutionPhase.PAUSE_REQUESTED)
    }

    @Synchronized fun waitingForAndroid() {
        check(current.value.phase == VisibilityExecutionPhase.RUNNING) {
            "Подтверждение Android допустимо только для активного переноса"
        }
        current.value = current.value.copy(phase = VisibilityExecutionPhase.WAITING_FOR_ANDROID)
    }

    @Synchronized fun androidDecision(approved: Boolean) {
        check(current.value.phase == VisibilityExecutionPhase.WAITING_FOR_ANDROID) { "Нет ожидаемого разрешения Android" }
        current.value = current.value.copy(
            phase = if (approved) VisibilityExecutionPhase.RUNNING else VisibilityExecutionPhase.RECOVERABLE_PENDING)
    }

    @Synchronized fun verifying(confirmed: Int, total: Int) {
        check(current.value.phase == VisibilityExecutionPhase.RUNNING) { "Нельзя завершать незапущенный перенос" }
        check(confirmed == total && confirmed >= current.value.confirmed) { "Не все файлы подтверждены" }
        current.value = current.value.copy(phase = VisibilityExecutionPhase.VERIFYING,
            confirmed = confirmed,total = total)
    }

    @Synchronized fun completeAfterFinalCheck() {
        check(current.value.phase == VisibilityExecutionPhase.VERIFYING) { "Не выполнена финальная проверка" }
        check(current.value.confirmed == current.value.total) { "Часть файлов не подтверждена" }
        current.value = current.value.copy(phase = VisibilityExecutionPhase.COMPLETED,hasPendingJournal = false)
    }

    @Synchronized fun failed(reason: String, pending: Boolean) {
        check(reason.matches(Regex("[A-Z0-9_]{1,96}"))) { "Небезопасный диагностический код" }
        check(current.value.active) { "Нет выполняемой операции" }
        current.value = current.value.copy(phase = VisibilityExecutionPhase.FAILED,
            safeFailureCode = reason,hasPendingJournal = pending)
    }
}
