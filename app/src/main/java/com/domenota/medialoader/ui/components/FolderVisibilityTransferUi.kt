package com.domenota.medialoader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.domenota.medialoader.data.catalog.visibility.VisibilityExecutionPhase
import com.domenota.medialoader.data.catalog.visibility.VisibilityExecutionSnapshot

/** A small pure mapping: text and actions must reflect verified state, not an
 * animation, guessed ETA or an uncommitted file count. */
internal data class VisibilityTransferPresentation(
    val message: String,
    val explanation: String,
    val canPause: Boolean = false,
    val canResume: Boolean = false,
    val isComplete: Boolean = false,
    val canDismiss: Boolean = false,
)

internal fun visibilityTransferPresentation(state: VisibilityExecutionSnapshot): VisibilityTransferPresentation =
    when (state.phase) {
        VisibilityExecutionPhase.IDLE -> VisibilityTransferPresentation(
            "Перенос не запущен","Данные не изменяются")
        VisibilityExecutionPhase.PREPARING -> VisibilityTransferPresentation(
            "Подготовка","Проверяем файлы и разрешения")
        VisibilityExecutionPhase.RUNNING -> VisibilityTransferPresentation(
            "Перенос выполняется","Проверенные этапы сохраняются. Окно можно свернуть",canPause=true)
        VisibilityExecutionPhase.PAUSE_REQUESTED -> VisibilityTransferPresentation(
            "Завершаем текущую проверку…","Приостановка произойдёт после сохранения контрольной точки")
        VisibilityExecutionPhase.PAUSED -> VisibilityTransferPresentation(
            "Приостановлено","Контрольная точка сохранена. Продолжение требует проверки",canResume=true)
        VisibilityExecutionPhase.WAITING_FOR_ANDROID -> VisibilityTransferPresentation(
            "Требуется подтверждение Android","Вернитесь в приложение и подтвердите системный запрос")
        VisibilityExecutionPhase.VERIFYING -> VisibilityTransferPresentation(
            "Проверяем результат","Все элементы обработаны; финальная проверка ещё выполняется")
        VisibilityExecutionPhase.COMPLETED -> VisibilityTransferPresentation(
            "Перенос завершён","Результат подтверждён после проверки всех элементов",isComplete=true)
        VisibilityExecutionPhase.RECOVERABLE_PENDING -> VisibilityTransferPresentation(
            "Перенос не завершён","Исходные данные и журнал сохранены; требуется проверка",canResume=true)
        VisibilityExecutionPhase.FAILED -> VisibilityTransferPresentation(
            "Перенос остановлен",
            if(state.hasPendingJournal) "Сохранённое состояние можно проверить и продолжить"
            else "Операция не началась. Исходные файлы не изменены",
            canResume=state.hasPendingJournal,canDismiss=!state.hasPendingJournal)
    }

@Composable
private fun transferAccent(): Color = MaterialTheme.colorScheme.primary

@Composable
private fun transferSurface(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < .5f) Color(0xFF232630) else Color.White

@Composable
private fun statusTileSurface(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < .5f) Color(0xFF30333D) else Color(0xFFF4F1FA)

@Composable
private fun TransferProgress(state: VisibilityExecutionSnapshot,compact:Boolean) {
    val track = if (MaterialTheme.colorScheme.background.luminance() < .5f)
        Color(0xFF444652) else Color(0xFFE9E4F1)
    val progress = if (state.total > 0)
        state.confirmed.toFloat()/state.total.coerceAtLeast(1) else 0f
    Box(Modifier.fillMaxWidth().height(if (compact) 5.dp else 9.dp)
        .background(track,RoundedCornerShape(99.dp))
        .testTag("visibility-transfer-track")) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f,1f)).height(if(compact) 5.dp else 9.dp)
            .background(transferAccent(),RoundedCornerShape(99.dp))
            .semantics { contentDescription = "Подтверждено ${state.confirmed} из ${state.total}" })
    }
}

@Composable
internal fun FolderVisibilityTransferDialog(
    state:VisibilityExecutionSnapshot,
    folderName:String?,
    onCollapse:()->Unit,
    onPause:()->Unit,
    onResume:()->Unit,
    onDone:()->Unit,
) {
    val presentation=visibilityTransferPresentation(state)
    val primary=transferAccent()
    Dialog(onDismissRequest=onCollapse) {
        Surface(shape=RoundedCornerShape(24.dp),color=transferSurface(),
            modifier=Modifier.fillMaxWidth().widthIn(max=430.dp)
                .heightIn(max=560.dp).testTag("visibility-transfer-expanded")) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement=Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically,
                    horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(48.dp).background(
                        primary.copy(alpha=.14f),RoundedCornerShape(12.dp)),
                        contentAlignment=Alignment.Center) {
                        Icon(Icons.Rounded.Folder,null,tint=primary,modifier=Modifier.size(27.dp))
                    }
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                        Text("Перенос файлов",style=MaterialTheme.typography.titleLarge,
                            fontWeight=FontWeight.SemiBold)
                        Text(folderName ?: "Выбранная папка",
                            style=MaterialTheme.typography.bodySmall,
                            color=MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines=1,overflow=TextOverflow.Ellipsis)
                    }
                    IconButton(onClick=onCollapse,modifier=Modifier.size(48.dp)) {
                        Icon(Icons.Rounded.KeyboardArrowDown,"Свернуть окно")
                    }
                }
                Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.Bottom,
                        horizontalArrangement=Arrangement.SpaceBetween) {
                        Text(if (state.total > 0) "${state.confirmed} / ${state.total}" else "Подготовка",
                            style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.SemiBold)
                        Text(if (state.total > 0) "${((state.confirmed.toLong()*100L + state.total/2L)/state.total)}%" else "…",
                            color=primary,style=MaterialTheme.typography.titleMedium,
                            modifier=Modifier.testTag("visibility-transfer-percent"))
                    }
                    TransferProgress(state,compact=false)
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                        Text("Подтверждённые файлы",style=MaterialTheme.typography.bodySmall,
                            color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(if (state.total>0) "${state.total-state.confirmed} осталось" else "Ожидание плана",
                            style=MaterialTheme.typography.bodySmall,
                            color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Surface(color=statusTileSurface(),shape=RoundedCornerShape(12.dp)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp),
                        verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment=Alignment.CenterVertically,
                            horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            Icon(when(state.phase) {
                                VisibilityExecutionPhase.COMPLETED -> Icons.Rounded.CheckCircle
                                VisibilityExecutionPhase.PAUSED -> Icons.Rounded.Pause
                                VisibilityExecutionPhase.FAILED,
                                VisibilityExecutionPhase.RECOVERABLE_PENDING,
                                VisibilityExecutionPhase.WAITING_FOR_ANDROID -> Icons.Rounded.WarningAmber
                                else -> Icons.Rounded.Refresh
                            },null,tint=primary,modifier=Modifier.size(19.dp))
                            Text(presentation.message,style=MaterialTheme.typography.bodyMedium,
                                fontWeight=FontWeight.Medium,modifier=Modifier.testTag("visibility-transfer-stage"))
                        }
                        Text(presentation.explanation,style=MaterialTheme.typography.bodySmall,
                            color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Button(onClick=onCollapse,modifier=Modifier.weight(1f).heightIn(min=48.dp)) {
                        Text("Свернуть")
                    }
                    OutlinedButton(
                        onClick=when {
                            presentation.isComplete || presentation.canDismiss -> onDone
                            presentation.canResume -> onResume
                            presentation.canPause -> onPause
                            else -> ({})
                        },
                        enabled=presentation.canPause||presentation.canResume||
                            presentation.isComplete||presentation.canDismiss,
                        modifier=Modifier.weight(1f).heightIn(min=48.dp)
                    ) {
                        Icon(when {
                            presentation.canResume -> Icons.Rounded.PlayArrow
                            presentation.isComplete -> Icons.Rounded.CheckCircle
                            presentation.canDismiss -> Icons.Rounded.KeyboardArrowDown
                            else -> Icons.Rounded.Pause
                        },null,modifier=Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(when {
                            presentation.isComplete -> "Готово"
                            presentation.canDismiss -> "Закрыть"
                            presentation.canResume -> "Продолжить"
                            state.phase == VisibilityExecutionPhase.PAUSE_REQUESTED -> "Остановка…"
                            presentation.canPause -> "Пауза"
                            else -> "Ожидание"
                        },fontSize=13.sp,maxLines=1)
                    }
                }
            }
        }
    }
}

/** This never includes private folder names, paths or media previews. */
@Composable
internal fun FolderVisibilityMiniPanel(
    state:VisibilityExecutionSnapshot,
    onOpen:()->Unit,
    modifier:Modifier=Modifier,
) {
    val present=visibilityTransferPresentation(state)
    Surface(modifier=modifier.fillMaxWidth().testTag("visibility-transfer-compact"),
        shape=RoundedCornerShape(16.dp),color=transferSurface(),shadowElevation=5.dp) {
        Column(Modifier.clickable(onClick=onOpen).padding(12.dp),
            verticalArrangement=Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,
                horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Rounded.Folder,null,tint=transferAccent())
                Column(Modifier.weight(1f)) {
                    Text("Перенос файлов",style=MaterialTheme.typography.bodyMedium,
                        fontWeight=FontWeight.Medium)
                    Text(if(state.total>0) "${present.message} • ${state.confirmed}/${state.total}"
                        else present.message,style=MaterialTheme.typography.bodySmall,
                        color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,
                        overflow=TextOverflow.Ellipsis)
                }
                OutlinedButton(onClick=onOpen,modifier=Modifier.heightIn(min=48.dp)) {
                    Text("Открыть")
                }
            }
            TransferProgress(state,compact=true)
        }
    }
}
