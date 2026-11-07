package com.domenota.medialoader.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.domenota.medialoader.data.editor.TextLayer
import com.domenota.medialoader.ui.components.*

/** Geometry belongs to the preview frame and timing belongs to the blue lanes. */
@Composable
internal fun VideoTextControls(layer: TextLayer?, count: Int, enabled: Boolean,
    initialTab: UnifiedTextTab, onAdd: () -> Unit, onChange: (TextLayer) -> Unit,
    onConfirm: () -> Unit) {
    var tab by remember(initialTab) { mutableStateOf(initialTab) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val requester = remember { FocusRequester() }
    LaunchedEffect(layer?.id, tab) {
        if (layer != null && tab == UnifiedTextTab.KEYBOARD) {
            requester.requestFocus()
            keyboard?.show()
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Надписи", style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = onAdd, enabled = enabled && count < 8,
                colors = ButtonDefaults.textButtonColors(contentColor = Color.White,
                    disabledContentColor = Color.Gray)) { Text("+ Текст") }
        }
        if (layer != null && tab == UnifiedTextTab.KEYBOARD) {
            OutlinedTextField(layer.text, { onChange(layer.copy(text = it.take(500))) },
                enabled = enabled, maxLines = 3, placeholder = { Text("Введите текст") },
                modifier = Modifier.fillMaxWidth().focusRequester(requester),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                    disabledTextColor = Color.LightGray, cursorColor = Color.White),
                trailingIcon = {
                    IconButton(onClick = { keyboard?.hide(); focus.clearFocus(); onConfirm() }, enabled = enabled) {
                        Icon(Icons.Rounded.Check, "Готово: надпись")
                    }
                })
        }
        UnifiedTextTabs(selected = tab, onSelect = { tab = it })
        if (layer != null) when (tab) {
            UnifiedTextTab.KEYBOARD -> Unit
            UnifiedTextTab.STYLE -> UnifiedTextStyle(layer, onChange, enabled, showSize = false)
            UnifiedTextTab.FONT -> {
                Text("Библиотека шрифтов", style = MaterialTheme.typography.labelMedium)
                EditorFontPicker(layer.fontId) { onChange(layer.copy(fontId = it)) }
            }
        }
    }
}

@Composable
internal fun VideoTextActions(enabled: Boolean, canCopy: Boolean, onBack: () -> Unit,
    onEdit: () -> Unit, onStyle: () -> Unit, onTrim: () -> Unit,
    onCopy: () -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(86.dp),
        verticalAlignment = Alignment.Top) {
        IconButton(onClick = onBack, modifier = Modifier.width(40.dp)) { Icon(Icons.Rounded.ArrowBack, "К главному меню") }
        listOf(Triple("Изменить", Icons.Rounded.Edit, onEdit),
            Triple("Стиль", VideoV8Icons.text, onStyle),
            Triple("Обрезка", VideoV8Icons.trim, onTrim),
            Triple("Копировать", VideoV8Icons.copy, onCopy),
            Triple("Удалить", VideoV8Icons.trash, onDelete)).forEach { (label, icon, action) ->
            TextButton(onClick = action, enabled = enabled && (label != "Копировать" || canCopy),
                modifier = Modifier.weight(1f), contentPadding = PaddingValues(2.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = Color.White,
                    disabledContentColor = Color.Gray)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(icon, null, Modifier.size(26.dp))
                    Spacer(Modifier.height(7.dp))
                    Text(label, fontSize = 11.sp, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
    }
}
