package com.domenota.medialoader.data.editor

import kotlin.math.abs
import kotlin.math.ln

internal class NormalizedTextShape(val bits: BooleanArray, val aspect: Float) {
    private val ink = bits.count { it }.coerceAtLeast(1)
    private val distances: IntArray by lazy {
        val d = IntArray(bits.size) { if (bits[it]) 0 else 320 }
        for (index in d.indices) {
            if (index % 256 > 0) d[index] = minOf(d[index], d[index - 1] + 1)
            if (index >= 256) d[index] = minOf(d[index], d[index - 256] + 1)
        }
        for (index in d.indices.reversed()) {
            if (index % 256 < 255) d[index] = minOf(d[index], d[index + 1] + 1)
            if (index + 256 < d.size) d[index] = minOf(d[index], d[index + 256] + 1)
        }
        d
    }
    fun distanceTo(other: NormalizedTextShape): Float {
        val a = bits.indices.sumOf { if (bits[it]) other.distances[it].coerceAtMost(6) else 0 }.toFloat() / ink
        val b = bits.indices.sumOf { if (other.bits[it]) distances[it].coerceAtMost(6) else 0 }.toFloat() / other.ink
        val shape = (a + b) / 12f
        val density = abs(ink - other.ink).toFloat() / maxOf(ink, other.ink)
        val proportions = abs(ln((aspect / other.aspect).coerceAtLeast(0.001f))).coerceAtMost(2f)
        return shape + density * 0.18f + proportions * 0.12f
    }
}
