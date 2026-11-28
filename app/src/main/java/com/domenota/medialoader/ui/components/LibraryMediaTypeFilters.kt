package com.domenota.medialoader.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.core.model.MediaType

@Composable
internal fun LibraryMediaTypeFilters(type:MediaType?,onType:(MediaType?)->Unit,enabled:Boolean=true){
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=16.dp),
        horizontalArrangement=Arrangement.spacedBy(8.dp)){
        for((value,label) in listOf(null to "Все",MediaType.PHOTO to "Фото",MediaType.VIDEO to "Видео",MediaType.AUDIO to "Музыка"))
            FilterChip(selected=type==value,onClick={onType(value)},label={Text(label)},enabled=enabled)
    }
}
