package com.domenota.medialoader.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import com.domenota.medialoader.data.storage.RecoverySandbox
import com.domenota.medialoader.ui.components.NeutralTextButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun RecoverySandboxSection(onBusyChange: (Boolean) -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var status by rememberSaveable { mutableStateOf("Только сгенерированные файлы. Оригиналы недоступны этому инструменту.") }
    fun run(action: String) {
        if (busy) return
        busy = true
        onBusyChange(true)
        status = "Проверка искусственной пробы…"
        scope.launch {
            try { status = RecoverySandbox(context).execute(action) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { status = "Остановлено безопасно: ${e.message ?: e.javaClass.simpleName}. Можно продолжить или откатить по журналу." }
            finally { busy = false; onBusyChange(false) }
        }
    }
    Column {
        Text("Этап 2 · журнал восстановления", style = MaterialTheme.typography.titleSmall)
        Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        NeutralTextButton(enabled = !busy, onClick = { run("JPEG") }) { Text("Создать искусственный JPEG и проверить") }
        NeutralTextButton(enabled = !busy, onClick = { run("MP4") }) { Text("Создать искусственный MP4 со звуком") }
        NeutralTextButton(enabled = !busy, onClick = { run("STATUS") }) { Text("Прочитать журнал") }
        NeutralTextButton(enabled = !busy, onClick = { run("RESUME") }) { Text("Продолжить прерванную пробу") }
        NeutralTextButton(enabled = !busy, onClick = { run("ROLLBACK") }) { Text("Откатить ссылки на исходную QA-копию") }
        Text("Массовое восстановление выключено. Испытание реального файла требует отдельного допуска.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
