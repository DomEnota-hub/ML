package com.domenota.medialoader.ui.screens.gallery

import android.content.Context
import com.domenota.medialoader.core.database.DownloadEntity

/** Display order within an opened album, independent from album-card rearranging. */
internal enum class GalleryAlbumFileSort(val label: String) {
    DATE_NEW("По дате: сначала новые"),
    DATE_OLD("По дате: сначала старые"),
    NAME_ASC("По названию: А–Я (1, 2, 10)"),
    NAME_DESC("По названию: Я–А (10, 2, 1)"),
    SIZE_LARGE("По размеру: сначала большие"),
    SIZE_SMALL("По размеру: сначала маленькие"),
}

internal class GalleryAlbumFileSortStore(context: Context) {
    private val settings = context.applicationContext
        .getSharedPreferences("gallery_album_file_order", Context.MODE_PRIVATE)

    fun read(source: String, album: String): GalleryAlbumFileSort = runCatching {
        GalleryAlbumFileSort.valueOf(settings.getString("$source:$album", null)
            ?: GalleryAlbumFileSort.DATE_NEW.name)
    }.getOrDefault(GalleryAlbumFileSort.DATE_NEW)

    fun save(source: String, album: String, sort: GalleryAlbumFileSort) {
        settings.edit().putString("$source:$album", sort.name).apply()
    }
}

internal object GalleryAlbumFileOrder {
    /**
     * Compare digit runs by their numeric value without converting to Int/Long.
     * Season 1 episode 10 therefore follows season 1 episode 2, even for long numbers.
     */
    fun compareNatural(a: String, b: String): Int {
        var left = 0
        var right = 0
        while (left < a.length && right < b.length) {
            val aDigit = a[left].isDigit()
            val bDigit = b[right].isDigit()
            if (aDigit && bDigit) {
                val aStart = left
                val bStart = right
                while (left < a.length && a[left].isDigit()) left++
                while (right < b.length && b[right].isDigit()) right++
                val ax = a.substring(aStart, left).trimStart('0').ifEmpty { "0" }
                val bx = b.substring(bStart, right).trimStart('0').ifEmpty { "0" }
                if (ax.length != bx.length) return ax.length.compareTo(bx.length)
                val compared = ax.compareTo(bx)
                if (compared != 0) return compared
            } else {
                val aStart = left
                val bStart = right
                while (left < a.length && !a[left].isDigit()) left++
                while (right < b.length && !b[right].isDigit()) right++
                // When one token is a number and the other isn't, consume
                // the first character only so the comparison always progresses.
                if (aStart == left) left++
                if (bStart == right) right++
                val compared = a.substring(aStart, left)
                    .compareTo(b.substring(bStart, right), ignoreCase = true)
                if (compared != 0) return compared
            }
        }
        return if (left == a.length && right == b.length) 0
            else if (left == a.length) -1 else 1
    }

    fun sorted(rows: List<DownloadEntity>, sort: GalleryAlbumFileSort): List<DownloadEntity> {
        val byName = Comparator<DownloadEntity> { a, b ->
            compareNatural(a.originalName, b.originalName).takeIf { it != 0 }
                ?: a.originalName.compareTo(b.originalName).takeIf { it != 0 }
                ?: a.id.compareTo(b.id)
        }
        val comparator = when (sort) {
            GalleryAlbumFileSort.DATE_NEW ->
                compareByDescending<DownloadEntity> { it.createdAtEpochMillis }.then(byName)
            GalleryAlbumFileSort.DATE_OLD ->
                compareBy<DownloadEntity> { it.createdAtEpochMillis }.then(byName)
            GalleryAlbumFileSort.NAME_ASC -> byName
            GalleryAlbumFileSort.NAME_DESC -> byName.reversed()
            GalleryAlbumFileSort.SIZE_LARGE, GalleryAlbumFileSort.SIZE_SMALL -> Comparator { a, b ->
                val left = a.sizeBytes
                val right = b.sizeBytes
                when {
                    left == null && right != null -> 1
                    right == null && left != null -> -1
                    left != null && right != null && left != right ->
                        if (sort == GalleryAlbumFileSort.SIZE_LARGE) right.compareTo(left)
                        else left.compareTo(right)
                    else -> byName.compare(a, b)
                }
            }
        }
        return rows.sortedWith(comparator)
    }

    fun groupedByDate(sort: GalleryAlbumFileSort) =
        sort == GalleryAlbumFileSort.DATE_NEW || sort == GalleryAlbumFileSort.DATE_OLD
}
