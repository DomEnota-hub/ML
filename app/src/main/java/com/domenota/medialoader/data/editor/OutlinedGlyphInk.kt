package com.domenota.medialoader.data.editor

import kotlin.math.abs

/** Conservative second method for repeated white-fill / dark-outline captions.
 * A light component must be enclosed by a narrow dark component. A component
 * connected to a frame/door edge or image boundary is ambiguous, not ink.
 * No background model, box fill or unconstrained flood/dilation is used. */
internal object OutlinedGlyphInk {
    fun extract(pixels: IntArray, width: Int, height: Int): BooleanArray? {
        if (width < 7 || height < 7 || width.toLong() * height > 600_000 ||
            pixels.size != width * height) return null
        fun shade(p: Int): Int {
            val r = p ushr 16 and 255; val g = p ushr 8 and 255; val b = p and 255
            if (p ushr 24 < 220 || maxOf(r,g,b) - minOf(r,g,b) > 35) return -1
            return (r + g + b) / 3
        }
        val shades = IntArray(pixels.size) { shade(pixels[it]) }
        val labels = IntArray(pixels.size)
        val queue = IntArray(pixels.size)
        val mask = BooleanArray(pixels.size)
        val minSide = minOf(width,height)
        val radius = (minSide * .12f).toInt().coerceIn(2,4)
        var nextLabel = 0
        fun neighbors(i: Int, action: (Int) -> Unit) {
            val x = i % width; val y = i / width
            if (x > 0) action(i - 1)
            if (x + 1 < width) action(i + 1)
            if (y > 0) action(i - width)
            if (y + 1 < height) action(i + width)
        }
        val darkComponents = ArrayList<IntArray>()
        val darkAt = IntArray(pixels.size) { -1 }
        for (start in pixels.indices) {
            if (darkAt[start] >= 0 || shades[start] !in 0..80) continue
            if (darkComponents.size >= 1024) return null
            val id = darkComponents.size
            var head = 0; var tail = 1; queue[0] = start; darkAt[start] = id
            while (head < tail) {
                neighbors(queue[head++]) { n ->
                    if (darkAt[n] < 0 && shades[n] in 0..80) {
                        darkAt[n] = id; queue[tail++] = n
                    }
                }
            }
            darkComponents.add(queue.copyOf(tail))
        }
        val chosenDark = mutableSetOf<Int>()
        var cores = 0
        for (start in pixels.indices) {
            if (labels[start] != 0 || shades[start] < 210) continue
            val label = ++nextLabel
            var head = 0; var tail = 1; queue[0] = start; labels[start] = label
            var edge = false; var boundary = 0; var darkBoundary = 0
            val adjacent = mutableMapOf<Int,Int>()
            while (head < tail) {
                val i = queue[head++]
                val x = i % width; val y = i / width
                if (x == 0 || y == 0 || x == width-1 || y == height-1) edge = true
                neighbors(i) { n ->
                    if (shades[n] >= 210) {
                        if (labels[n] == 0) { labels[n] = label; queue[tail++] = n }
                    } else {
                        boundary++
                        if (darkAt[n] >= 0) {
                            darkBoundary++
                            adjacent[darkAt[n]] = (adjacent[darkAt[n]] ?: 0) + 1
                        }
                    }
                }
            }
            if (edge || tail < 5) continue
            // A second substantial interior light component cannot be ignored:
            // an unresolved part of one glyph must not become a complete mask.
            if (tail * 100L > pixels.size * 38L || boundary < 8 ||
                darkBoundary * 100L < boundary * 80L) return null
            // O/P/A and other letters with counters can have separate
            // interior and exterior outline components around the same fill.
            val darkIds = adjacent.filterValues { it >= 2 }.keys
            if (darkIds.isEmpty() || darkIds.sumOf { adjacent.getValue(it) } * 100L < boundary * 80L)
                return null
            if (darkIds.any { id ->
                    val outline = darkComponents[id]
                    outline.any { it % width == 0 || it % width == width-1 ||
                        it / width == 0 || it / width == height-1 } ||
                        outline.size * 100L > pixels.size * 45L
                }) return null
            for (j in 0 until tail) mask[queue[j]] = true
            chosenDark.addAll(darkIds)
            cores++
        }
        if (cores == 0) return null
        val coreMask = mask.copyOf()
        for (id in chosenDark) {
            val outline = darkComponents[id]
            for (i in outline) {
                val x = i % width; val y = i / width
                var near = false
                for (dy in -radius..radius) for (dx in -radius..radius) {
                    if (abs(dx)+abs(dy) > radius) continue
                    val nx = x + dx; val ny = y + dy
                    if (nx in 0 until width && ny in 0 until height && coreMask[ny*width+nx]) near = true
                }
                // Reject the whole symbol rather than cutting a connected seam.
                if (!near) return null
                mask[i] = true
            }
        }
        val count = mask.count { it }
        if (count < 10 || count * 100L > pixels.size * 65L) return null
        return mask
    }
}
