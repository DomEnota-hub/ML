package com.domenota.medialoader.ui.screens

import com.domenota.medialoader.core.database.DownloadEntity
import java.text.Collator
import java.util.Locale

internal enum class MusicLibrarySort(val label: String) {
    TITLE("По названию"), DATE("По дате · новые"), ARTIST("По исполнителю")
}

internal fun musicDisplayTitle(track: DownloadEntity): String =
    (track.recognizedTitle ?: track.sourceAudioTitle ?: track.originalName)
        .substringBeforeLast('.', track.originalName).ifBlank { track.originalName }

internal fun musicGroupLetter(title: String): String {
    val first = title.trim().firstOrNull()?.uppercaseChar() ?: return "#"
    return if (first in 'А'..'Я' || first == 'Ё' || first in 'A'..'Z') first.toString() else "#"
}

internal fun sortMusicRows(rows: List<DownloadEntity>, order: MusicLibrarySort): List<DownloadEntity> {
    val collator = Collator.getInstance(Locale.forLanguageTag("ru-RU")).apply { strength = Collator.PRIMARY }
    return when (order) {
        MusicLibrarySort.DATE -> rows.sortedWith(compareByDescending<DownloadEntity> { it.createdAtEpochMillis }
            .thenComparator { a,b -> collator.compare(musicDisplayTitle(a), musicDisplayTitle(b)) })
        MusicLibrarySort.ARTIST -> rows.sortedWith { a,b ->
            collator.compare(
                a.recognizedArtist ?: a.sourceAudioArtist ?: "",
                b.recognizedArtist ?: b.sourceAudioArtist ?: "",
            ).takeIf { it != 0 } ?: collator.compare(musicDisplayTitle(a), musicDisplayTitle(b))
        }
        MusicLibrarySort.TITLE -> rows.sortedWith { a,b ->
            collator.compare(musicDisplayTitle(a), musicDisplayTitle(b))
        }
    }
}
