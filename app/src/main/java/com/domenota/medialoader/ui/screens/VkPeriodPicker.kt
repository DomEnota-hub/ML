package com.domenota.medialoader.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.domenota.medialoader.ui.components.NeutralTextButton as TextButton
import com.domenota.medialoader.ui.components.NeutralOutlinedButton as OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.domenota.medialoader.data.vkcommunity.VkDatePeriod
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Two taps can be in either order; presets edit only the draft until Apply. */
@Composable
fun VkPeriodPicker(
    current: VkDatePeriod,
    onApply: (VkDatePeriod) -> Unit,
    onDismiss: () -> Unit,
    singleDate: Boolean = false,
) {
    val today = remember { LocalDate.now() }
    var month by remember { mutableStateOf(YearMonth.from(current.endInclusive)) }
    var first by remember { mutableStateOf<LocalDate?>(current.start) }
    var second by remember { mutableStateOf<LocalDate?>(current.endInclusive) }
    val left = listOfNotNull(first, second).minOrNull()
    val right = listOfNotNull(first, second).maxOrNull()
    val monthName = remember { DateTimeFormatter.ofPattern("LLLL yyyy", Locale("ru")) }
    val startWeekOffset = month.atDay(1).dayOfWeek.value - 1

    Dialog(onDismissRequest = onDismiss) {
        com.domenota.medialoader.ui.theme.TransparentWindowNavigation()
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(12.dp)) {
                Text(if (singleDate) "Дата начала проверки" else "Выбрать период",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()) {
                    IconButton(onClick = { month = month.minusYears(1) },
                        modifier = Modifier.size(40.dp)) { Text("«") }
                    IconButton(onClick = { month = month.minusMonths(1) },
                        modifier = Modifier.size(40.dp)) { Text("‹") }
                    Text(month.atDay(1).format(monthName),
                        modifier = Modifier.weight(1f),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    IconButton(enabled = month.plusMonths(1).atDay(1) <= today,
                        onClick = { month = month.plusMonths(1) },
                        modifier = Modifier.size(40.dp)) { Text("›") }
                    IconButton(enabled = month.plusYears(1).atDay(1) <= today,
                        onClick = { month = month.plusYears(1) },
                        modifier = Modifier.size(40.dp)) { Text("»") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс").forEach { day ->
                        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                            Text(day, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                repeat(6) { week ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        repeat(7) { weekday ->
                            val number = week * 7 + weekday - startWeekOffset + 1
                            val day = if (number in 1..month.lengthOfMonth()) month.atDay(number) else null
                            val endpoint = day != null && (day == left || day == right)
                            val between = day != null && left != null && right != null &&
                                day > left && day < right
                            val background = when {
                                endpoint -> MaterialTheme.colorScheme.primary
                                between -> MaterialTheme.colorScheme.primaryContainer
                                else -> Color.Transparent
                            }
                            Box(Modifier.size(40.dp)
                                .background(background, CircleShape)
                                .clickable(enabled = day != null && day <= today) {
                                    val selected = day ?: return@clickable
                                    if (singleDate || first == null || second != null) {
                                        first = selected; second = null
                                    } else second = selected
                                }, contentAlignment = Alignment.Center) {
                                if (day != null) Text(day.dayOfMonth.toString(),
                                    color = when {
                                        endpoint -> MaterialTheme.colorScheme.onPrimary
                                        day > today -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                                        else -> MaterialTheme.colorScheme.onSurface
                                    })
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(modifier = Modifier.weight(1f), onClick = {
                        val value = VkDatePeriod.lastDays(7, today)
                        first = value.start; second = if (singleDate) null else value.endInclusive
                        month = YearMonth.from(today)
                    }) { Text(if (singleDate) "За 7 дней" else "7 дней") }
                    OutlinedButton(modifier = Modifier.weight(1f), onClick = {
                        val value = VkDatePeriod.lastDays(30, today)
                        first = value.start; second = if (singleDate) null else value.endInclusive
                        month = YearMonth.from(today)
                    }) { Text(if (singleDate) "За 30 дней" else "30 дней") }
                }
                OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                        val value = VkDatePeriod.wholeMonth(month.atDay(1), today)
                        first = value.start; second = if (singleDate) null else value.endInclusive
                    }) { Text(if (singleDate) "Начало месяца" else "Весь месяц") }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                        Text("Отмена")
                    }
                    OutlinedButton(enabled = first != null, modifier = Modifier.weight(1f), onClick = {
                        val start = first ?: return@OutlinedButton
                        onApply(VkDatePeriod.of(start, if (singleDate) start else second ?: start))
                    }) { Text("Применить") }
                }
            }
        }
    }
}
