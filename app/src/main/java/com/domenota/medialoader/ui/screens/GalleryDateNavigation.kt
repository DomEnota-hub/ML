package com.domenota.medialoader.ui.screens

import java.time.LocalDate
import kotlin.math.abs

/** A date without photos opens the closest available day. On a tie prefer newer media. */
internal fun closestGalleryDate(days: Collection<LocalDate>, target: LocalDate): LocalDate? =
    days.minWithOrNull(compareBy<LocalDate> { abs(it.toEpochDay() - target.toEpochDay()) }
        .thenByDescending { it })

/** One grid-wide controls cell precedes the first date header. */
internal fun galleryDateSectionIndex(sectionSizes: Map<LocalDate, Int>, target: LocalDate): Int? {
    val wanted = closestGalleryDate(sectionSizes.keys, target) ?: return null
    var index = 1
    sectionSizes.forEach { (day, size) ->
        if (day == wanted) return index
        index += 1 + size
    }
    return null
}
