package com.domenota.medialoader.data.editor

/** Bounded edit history. Seeking/playing does not create history entries. */
internal class VideoEditHistory<T : Any>(private val limit: Int = 60) {
    init { require(limit > 0) }

    private val past = ArrayDeque<T>()
    private val future = ArrayDeque<T>()
    private var latest: T? = null

    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()

    /** Observe a new edit snapshot; keeps first snapshot as the baseline. */
    fun record(value: T): Boolean {
        val old = latest
        if (old == value) return false
        if (old != null) {
            past.addLast(old)
            if (past.size > limit) past.removeFirst()
            future.clear()
        }
        latest = value
        return true
    }

    data class Checkpoint<T>(val past: List<T>, val future: List<T>, val latest: T?)
    fun checkpoint(): Checkpoint<T> = Checkpoint(past.toList(), future.toList(), latest)
    /** Cancelling a live-preview panel also removes its provisional undo/redo entries. */
    fun restore(checkpoint: Checkpoint<T>) {
        past.clear(); past.addAll(checkpoint.past.takeLast(limit))
        future.clear(); future.addAll(checkpoint.future.takeLast(limit))
        latest = checkpoint.latest
    }

    fun undo(): T? {
        if (past.isEmpty()) return null
        val old = requireNotNull(latest)
        future.addLast(old)
        return past.removeLast().also { latest = it }
    }

    fun redo(): T? {
        if (future.isEmpty()) return null
        val old = requireNotNull(latest)
        past.addLast(old)
        if (past.size > limit) past.removeFirst()
        return future.removeLast().also { latest = it }
    }
}
