package com.domenota.medialoader.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.data.gapi.GapiKey
import com.domenota.medialoader.data.gapi.GapiKeyStore
import com.domenota.medialoader.ui.components.NeutralTextButton
import java.text.DateFormat
import java.util.Date

@Composable
fun GapiKeysDialog(
    store: GapiKeyStore,
    gateRevision: Any,
    onDismissRequest: () -> Unit,
) {
    var keys by remember(store) { mutableStateOf(store.snapshot()) }
    var builtinAllowed by remember(store) { mutableStateOf(store.builtinAllowed()) }
    var fallback by remember(store) { mutableStateOf(store.fallbackEnabled) }
    var help by remember { mutableStateOf(false) }
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<GapiKey?>(null) }
    var name by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var remove by remember { mutableStateOf<GapiKey?>(null) }
    var balanceTarget by remember { mutableStateOf<GapiKey?>(null) }
    var limitInput by remember { mutableStateOf("") }
    var remainderInput by remember { mutableStateOf("") }
    var sharedWith by remember { mutableStateOf<String?>(null) }
    var balanceError by remember { mutableStateOf<String?>(null) }
    fun refresh() {
        keys = store.snapshot()
        builtinAllowed = store.builtinAllowed()
        fallback = store.fallbackEnabled
    }
    fun mutate(action: () -> Boolean) {
        error = if (action()) null else "Не удалось сохранить изменение. Проверьте данные и повторите."
        refresh()
    }
    LaunchedEffect(store, gateRevision) {
        while (true) { refresh(); kotlinx.coroutines.delay(1000) }
    }
    if (help) AlertDialog(onDismissRequest = { help = false }, title = { Text("GAPI: как подключить") },
        text = { Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("1. Откройте gapi.dev и нажмите «Войти». Войдите через Google или GitHub.")
            Text("2. В личном кабинете откройте «Ключи API» и создайте ключ. Скопируйте его целиком.")
            Text("3. Вернитесь сюда, нажмите «Добавить ключ», задайте название и вставьте ключ. Поднимите его выше, если хотите использовать первым.")
            Text("Баланс расчётный и учитывает запросы только с этого устройства. Точный остаток доступен в личном кабинете GAPI.")
            NeutralTextButton(onClick = { uriHandler.openUri("https://gapi.dev/ru/docs") }) { Text("Открыть GAPI") }
        } }, confirmButton = { NeutralTextButton(onClick = { help = false }) { Text("Понятно") } })
    // Always show masked built-in keys; developer settings must not control API availability.
    val visibleKeys = keys

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text("Ключи GAPI", color = MaterialTheme.colorScheme.onSurface) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                Text("GAPI — основной способ загрузки из Instagram и видео VK.",
                    color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(8.dp))
                Text("Сначала используется верхний доступный ключ. Когда его месячный лимит исчерпан, приложение переключается на следующий.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                NeutralTextButton(onClick = { help = true }) { Text("Как получить ключ · FAQ") }
                Text("Расчётный баланс обновляется по запросам с этого устройства.", style = MaterialTheme.typography.bodySmall)
                if (!store.storageAvailable) Text(
                    "Защищённое хранилище ключей недоступно. GAPI отключён. Перезапустите приложение и попробуйте снова.",
                    color = MaterialTheme.colorScheme.error,
                )
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Резервный способ", color = MaterialTheme.colorScheme.onSurface)
                        Text("При ошибке API использовать прежний способ загрузки и сохранённый аккаунт, если он потребуется",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = fallback, enabled = store.storageAvailable, onCheckedChange = {
                        store.fallbackEnabled = it
                        refresh()
                        if (fallback != it) error = "Не удалось сохранить настройку"
                    })
                }
                if (visibleKeys.isEmpty()) Text(
                    "Ключей GAPI в этой сборке нет. Добавьте свой ключ или установите сборку со встроенными ключами.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                visibleKeys.forEachIndexed { index, key ->
                    Spacer(Modifier.height(12.dp))
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${index + 1}. ${key.name}", style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface)
                                Text("${if (key.builtIn) "Встроенный" else "Свой"} · ••••",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("Расчётный остаток: ${key.remaining?.toString() ?: "неизвестен"}${key.monthlyLimit?.let { " из $it" } ?: ""}", style = MaterialTheme.typography.bodySmall)
                                key.lastUsage?.let { Text("Последний запрос: $it", style = MaterialTheme.typography.bodySmall) }
                                Text(keyStatus(key, store.currentMonth()),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (key.invalid) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = key.enabled, enabled = store.storageAvailable, onCheckedChange = { enabled ->
                                mutate { store.setEnabled(key.id, enabled) }
                            })
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            IconButton(modifier = Modifier.size(40.dp), enabled = index > 0, onClick = {
                                val target = visibleKeys[index - 1]
                                mutate { store.move(key.id, keys.indexOf(target) - keys.indexOf(key)) }
                            }) { Icon(Icons.Rounded.KeyboardArrowUp, "Повысить приоритет") }
                            IconButton(modifier = Modifier.size(40.dp), enabled = index < visibleKeys.lastIndex, onClick = {
                                val target = visibleKeys[index + 1]
                                mutate { store.move(key.id, keys.indexOf(target) - keys.indexOf(key)) }
                            }) { Icon(Icons.Rounded.KeyboardArrowDown, "Понизить приоритет") }
                            NeutralTextButton(onClick = {
                                editing = key; name = key.name; token = ""; error = null
                            }) { Text("Название") }
                        }
                        key.balanceCheckedAt?.let { Text("Сверено: ${DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(it))}", style = MaterialTheme.typography.bodySmall) }
                        NeutralTextButton(enabled = store.storageAvailable, onClick = {
                            balanceTarget = key
                            limitInput = (key.monthlyLimit ?: 200).toString()
                            remainderInput = key.remaining?.toString().orEmpty()
                            sharedWith = keys.firstOrNull { it.id != key.id && it.balanceId == key.balanceId }?.id
                            balanceError = null
                        }) { Text("Изменить остаток") }
                        if (key.invalid || key.exhaustedMonth == store.currentMonth() ||
                            key.pauseUntilMillis > System.currentTimeMillis()) {
                            NeutralTextButton(enabled = store.storageAvailable, onClick = {
                                mutate { store.resetStatus(key.id) }
                            }) { Text("Проверить снова") }
                        }
                        NeutralTextButton(onClick = { remove = key }) {
                            Text("Удалить", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Spacer(Modifier.height(8.dp))
                NeutralTextButton(onClick = ::refresh) { Text("Обновить статусы") }
            }
        },
        confirmButton = { NeutralTextButton(onClick = onDismissRequest) { Text("Закрыть") } },
        dismissButton = { NeutralTextButton(enabled = store.storageAvailable, onClick = {
            adding = true; name = ""; token = ""; error = null
        }) { Text("Добавить ключ") } },
    )

    if (adding || editing != null) AlertDialog(
        onDismissRequest = { adding = false; editing = null; token = ""; error = null },
        title = { Text(if (adding) "Добавить ключ GAPI" else "Название ключа") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it; error = null },
                    label = { Text("Название") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (adding) OutlinedTextField(
                    value = token, onValueChange = { token = it; error = null },
                    label = { Text("API ключ") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            NeutralTextButton(enabled = name.isNotBlank() && (!adding || token.isNotBlank()), onClick = {
                val saved = if (adding) store.add(name, token) else store.rename(editing!!.id, name)
                if (saved) {
                    token = ""; adding = false; editing = null; error = null; refresh()
                } else error = "Не удалось сохранить. Ключ должен быть уникальным и без пробелов."
            }) { Text("Сохранить") }
        },
        dismissButton = { NeutralTextButton(onClick = {
            token = ""; adding = false; editing = null; error = null
        }) { Text("Отмена") } },
    )
    balanceTarget?.let { key ->
        val limit = limitInput.toIntOrNull()
        val remaining = remainderInput.toIntOrNull()
        AlertDialog(onDismissRequest = { balanceTarget = null }, title = { Text("Остаток GAPI") },
            text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Расчётный баланс · обновляется 1-го числа в 00:00 UTC (03:00 МСК).")
                OutlinedTextField(limitInput, { limitInput = it.filter(Char::isDigit); balanceError = null },
                    label = { Text("Месячный лимит") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(remainderInput, { remainderInput = it.filter(Char::isDigit); balanceError = null },
                    label = { Text("Осталось токенов") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                Text("Счёт GAPI", style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(sharedWith == null, onClick = { sharedWith = null }); Text("Отдельный аккаунт")
                }
                keys.filter { it.id != key.id }.forEach { other ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(sharedWith == other.id, onClick = {
                            sharedWith = other.id
                            limitInput = (other.monthlyLimit ?: 200).toString()
                            remainderInput = other.remaining?.toString().orEmpty()
                        })
                        Text("Общий с «${other.name}»")
                    }
                }
                Text("Ключи одного аккаунта используют общий баланс. Укажите фактический остаток из кабинета.",style = MaterialTheme.typography.bodySmall)
                balanceError?.let { Text(it,color = MaterialTheme.colorScheme.error) }
            } }, confirmButton = {
                NeutralTextButton(enabled = limit != null && limit > 0 && remaining != null && remaining in 0..limit, onClick = {
                    if (store.setBalance(key.id,requireNotNull(limit),requireNotNull(remaining),sharedWith)) {
                        balanceTarget = null; refresh()
                    } else balanceError = "Не удалось сохранить баланс"
                }) { Text("Сохранить") }
            }, dismissButton = { NeutralTextButton({ balanceTarget = null }) { Text("Отмена") } })
    }
    remove?.let { key ->
        AlertDialog(
            onDismissRequest = { remove = null },
            title = { Text("Удалить ключ?") },
            text = { Text("«${key.name}» будет удалён из списка ключей GAPI.") },
            confirmButton = { NeutralTextButton(onClick = {
                mutate { store.remove(key.id) }; remove = null
            }) { Text("Удалить", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { NeutralTextButton(onClick = { remove = null }) { Text("Отмена") } },
        )
    }
}

private fun keyStatus(key: GapiKey, currentMonth: String): String = when {
    !key.enabled -> "Выключен"
    key.invalid -> "Ключ отклонён сервисом"
    key.exhaustedMonth == currentMonth || key.remaining == 0 -> "Месячный лимит исчерпан · восстановится в следующем месяце (UTC)"
    key.pauseUntilMillis > System.currentTimeMillis() ->
        "Пауза до ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(key.pauseUntilMillis))}"
    else -> "Готов к запросам"
}
