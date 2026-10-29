package com.domenota.medialoader.data.vkcommunity

object VkResultPosition {
    const val PAGE_SIZE = 60

    fun page(index: Int): Int = index / PAGE_SIZE
    fun offset(index: Int): Int = index % PAGE_SIZE
    fun move(index: Int, direction: Int, total: Int): Int =
        if (total <= 0) 0 else (index + direction).coerceIn(0, total - 1)
}
