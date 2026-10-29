package com.domenota.medialoader.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.core.logging.DisplayLogEntry
import com.domenota.medialoader.core.logging.LogDisplay
import com.domenota.medialoader.core.logging.LogExplanation
import com.domenota.medialoader.core.logging.LogTone

/**
 * One journal, two representations. Raw mode does NOT collapse or translate data;
 * exported Markdown is untouched by the selected presentation/filter.
 */
@Composable
internal fun AppLogViewer(
    rawText: String,
    readable: Boolean,
    onReadableChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val events = remember(rawText) { LogDisplay.entries(rawText) }
    var selectedTone by remember { mutableStateOf<LogTone?>(null) }
    val filtered = remember(events, selectedTone) { LogDisplay.filter(events, selectedTone) }
    val displayed = remember(events, selectedTone, readable) {
        // Compact before filtering so non-adjacent events are never merged.
        if (readable) LogDisplay.filter(LogDisplay.compact(events), selectedTone) else filtered
    }
    val tones = remember { LogTone.entries.toList() }
    val name = if (readable) "Понятный" else "Технический"

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = readable,
                onClick = { onReadableChange(true) },
                label = { Text("Понятный") },
                modifier = Modifier.weight(1f),
            )
            FilterChip(
                selected = !readable,
                onClick = { onReadableChange(false) },
                label = { Text("Технический") },
                modifier = Modifier.weight(1f),
            )
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = selectedTone == null,
                onClick = { selectedTone = null },
                label = { Text("Все · ${events.size}") })
            tones.forEach { tone ->
                val count = events.count { it.tone == tone }
                FilterChip(
                    selected = selectedTone == tone,
                    onClick = { selectedTone = if (selectedTone == tone) null else tone },
                    leadingIcon = {
                        Spacer(Modifier.size(9.dp).background(tone.tint(), CircleShape))
                    },
                    label = { Text("${tone.label} · $count") },
                )
            }
        }
        Text(
            if (selectedTone != null && filtered.isEmpty()) "По выбранному фильтру событий нет."
            else "Режим «$name» · событий: ${filtered.size}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (displayed.isEmpty()) {
                item {
                    Text("Журнал пока пуст или события не соответствуют фильтру.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                items(displayed) { entry ->
                    if (readable) ReadableLogLine(entry) else TechnicalLogLine(entry)
                }
            }
        }
    }
}

@Composable
private fun LogTone.tint(): Color = when (this) {
    LogTone.ERROR -> MaterialTheme.colorScheme.error
    LogTone.WARN -> Color(0xFFF29A27)
    LogTone.OK -> if (MaterialTheme.colorScheme.background.luminance() > 0.5f)
        Color(0xFF15964A) else Color(0xFF65D49A)
    LogTone.INFO -> Color(0xFF2588E8)
    LogTone.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun ReadableLogLine(entry: DisplayLogEntry) {
    val tint = entry.tone.tint()
    val detail = LogExplanation.forLine(entry.original)
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            "${entry.timestamp.substringAfter(' ')} · ${LogDisplay.groupName(entry)}" +
                (if (entry.repeats > 1) " · повторов: ${entry.repeats}" else ""),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = tint, fontWeight = FontWeight.Bold)) {
                    append(entry.tone.label.substringBefore(' '))
                    append(" · ")
                }
                append(LogDisplay.readable(entry))
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        detail?.advice?.let {
            Text(
                "Что можно сделать: $it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TechnicalLogLine(entry: DisplayLogEntry) {
    val tint = entry.tone.tint()
    // Dynamic hostnames, file paths and IDs must not be highlighted as actions.
    val key = entry.event
        .substringBefore(" host=")
        .substringBefore(" id=")
        .substringBefore(" url=")
        .substringBefore(" op=")
        .substringBefore(" path=")
        .let { if ('=' in it) "" else it }
    Text(
        buildAnnotatedString {
            append(entry.timestamp)
            append(" ")
            withStyle(SpanStyle(color = tint, fontWeight = FontWeight.Bold)) {
                append("[${entry.technicalLevel}] [${entry.tag}]")
                if (key.isNotEmpty()) append(" $key")
            }
            append(if (key.isNotEmpty()) entry.event.removePrefix(key) else " ${entry.event}")
            if (entry.metadata.isNotEmpty()) {
                append(" · ")
                append(entry.metadata)
            }
            for (stackLine in entry.stack) {
                append("\n")
                append(stackLine)
            }
        },
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
