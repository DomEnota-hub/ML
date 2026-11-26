package com.domenota.medialoader.ui.screens

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.data.privatevault.PrivateFileStore
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal enum class PrivateArchiveSortField(val label: String) {
    NAME("Название"), FILE_DATE("Дата файла"), ADDED("Дата добавления"),
    SIZE("Размер"), COUNT("Количество файлов")
}
internal data class PrivateArchiveSort(
    val field: PrivateArchiveSortField = PrivateArchiveSortField.ADDED,
    val ascending: Boolean = false,
) {
    fun save(prefs: SharedPreferences, prefix: String) {
        prefs.edit().putString(prefix + "_sort", field.name)
            .putBoolean(prefix + "_sort_ascending", ascending).apply()
    }
    companion object {
        fun read(prefs: SharedPreferences, prefix: String) = PrivateArchiveSort(
            runCatching { PrivateArchiveSortField.valueOf(prefs.getString(prefix + "_sort", "ADDED")!!) }
                .getOrDefault(PrivateArchiveSortField.ADDED),
            prefs.getBoolean(prefix + "_sort_ascending", false)
        )
    }
}
internal data class PrivateArchiveSortKey(
    val id: String, val name: String, val fileDate: Long?, val added: Long?,
    val bytes: Long?, val count: Int = 1,
)
internal fun <T> sortPrivateArchive(
    items: List<T>, sort: PrivateArchiveSort, key: (T) -> PrivateArchiveSortKey,
): List<T> = items.map { it to key(it) }.sortedWith { left, right ->
    val a = left.second
    val b = right.second
    fun compareKnown(x: Long?, y: Long?): Int = when {
        x == null && y == null -> 0
        x == null -> 1
        y == null -> -1
        sort.ascending -> x.compareTo(y)
        else -> y.compareTo(x)
    }
    val order = when (sort.field) {
        PrivateArchiveSortField.NAME -> a.name.lowercase(Locale.ROOT)
            .compareTo(b.name.lowercase(Locale.ROOT)) * if (sort.ascending) 1 else -1
        PrivateArchiveSortField.FILE_DATE -> compareKnown(a.fileDate, b.fileDate)
        PrivateArchiveSortField.ADDED -> compareKnown(a.added, b.added)
        PrivateArchiveSortField.SIZE -> compareKnown(a.bytes, b.bytes)
        PrivateArchiveSortField.COUNT -> compareKnown(a.count.toLong(), b.count.toLong())
    }
    if (order != 0) order else a.id.compareTo(b.id)
}.map { it.first }

internal fun groupPrivateArchiveKey(
    id: String, name: String, keys: List<PrivateArchiveSortKey>,
): PrivateArchiveSortKey {
    val bytes = if (keys.any { it.bytes == null }) null else runCatching {
        keys.fold(0L) { sum, key -> Math.addExact(sum, requireNotNull(key.bytes)) }
    }.getOrNull()
    return PrivateArchiveSortKey(id, name, keys.mapNotNull { it.fileDate }.maxOrNull(),
        keys.mapNotNull { it.added }.maxOrNull(), bytes, keys.size)
}

/** Read only actual metadata from admitted rows, on demand. No inferred dates or persisted private index. */
@Composable
internal fun rememberPrivateArchiveDates(
    context: Context, rows: Map<String, DownloadEntity>, enabled: Boolean,
    privateSection: Boolean, stillAllowed: () -> Boolean = { true },
): Map<String, Long> {
    var dates by remember(rows, enabled) { mutableStateOf(emptyMap<String, Long>()) }
    val currentAllowed by rememberUpdatedState(stillAllowed)
    LaunchedEffect(rows, enabled) {
        if (!enabled) return@LaunchedEffect
        val found = withContext(Dispatchers.IO) {
            val privateStore = if (privateSection) PrivateFileStore(context) else null
            buildMap<String, Long> {
                for ((id, row) in rows) {
                    currentCoroutineContext().ensureActive()
                    if (!currentAllowed()) break
                    val address = row.savedUri ?: continue
                    val value = try {
                        if (privateSection && row.isPrivate) {
                            privateStore?.file(address)?.takeIf { it.isFile }?.lastModified()
                        } else if (!privateSection && !row.isPrivate && !row.hidden) {
                            val uri = Uri.parse(address)
                            if (uri.scheme != "content") null
                            else {
                                val document = DocumentsContract.isDocumentUri(context, uri)
                                val column = if (document) DocumentsContract.Document.COLUMN_LAST_MODIFIED
                                    else MediaStore.MediaColumns.DATE_MODIFIED
                                context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use {
                                    if (it.moveToFirst() && !it.isNull(0)) {
                                        val raw = it.getLong(0)
                                        if (document) raw else Math.multiplyExact(raw, 1000L)
                                    } else null
                                }
                            }
                        } else null
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                    if (value != null && value > 0) put(id, value)
                }
            }
        }
        if (currentAllowed()) dates = found
    }
    return dates
}

@Composable
internal fun PrivateArchiveSortMenu(
    sort: PrivateArchiveSort, onChange: (PrivateArchiveSort) -> Unit,
    enabled: Boolean, privateSection: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, enabled = enabled) {
            Icon(Icons.Rounded.Sort, "Сортировка")
        }
        DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            PrivateArchiveSortField.values().forEach { field ->
                val label = if (privateSection && field == PrivateArchiveSortField.ADDED)
                    "Дата добавления в медиатеку" else field.label
                DropdownMenuItem(
                    text = { Text(if (sort.field == field) "✓ " + label else label) },
                    onClick = { onChange(sort.copy(field = field)); expanded = false })
            }
            HorizontalDivider()
            DropdownMenuItem(text = { Text(if (sort.ascending) "✓ По возрастанию" else "По возрастанию") },
                onClick = { onChange(sort.copy(ascending = true)); expanded = false })
            DropdownMenuItem(text = { Text(if (!sort.ascending) "✓ По убыванию" else "По убыванию") },
                onClick = { onChange(sort.copy(ascending = false)); expanded = false })
        }
    }
}
