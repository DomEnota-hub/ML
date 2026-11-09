package com.domenota.medialoader.data.editor

/**
 * One automatic SAM mask consists of many scanline strokes but is ONE user action.
 * Manual brush strokes are recorded individually. Undo/redo must never leave
 * a partially reverted automatic selection.
 */
internal class MaskActionGrouping {
    private val undo = ArrayDeque<Int>()
    private val redo = ArrayDeque<Int>()

    fun record(strokeCount: Int) {
        require(strokeCount > 0)
        undo.addLast(strokeCount)
        redo.clear()
    }

    /** No fallback 'one stroke': empty history must be a true no-op. */
    fun undoCount(): Int = if (undo.isEmpty()) 0 else undo.removeLast().also { redo.addLast(it) }

    fun redoCount(): Int = if (redo.isEmpty()) 0 else redo.removeLast().also { undo.addLast(it) }

    fun clear() {
        undo.clear()
        redo.clear()
    }
}
