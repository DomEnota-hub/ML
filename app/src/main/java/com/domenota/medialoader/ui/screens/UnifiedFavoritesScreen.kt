package com.domenota.medialoader.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.ui.components.DownloadThumbnail
import com.domenota.medialoader.ui.model.toUi

/** One library view, not another favorites store. No automatic permission
 * prompt; app-owned items remain usable with phone access denied. */
@Composable
internal fun UnifiedFavoritesScreen(rows:List<DownloadEntity>,ready:Boolean,loading:Boolean,
    galleryAccess:Boolean,musicAccess:Boolean,onGalleryAccess:()->Unit,onMusicAccess:()->Unit,
    onBack:()->Unit,onOpen:(String,List<String>)->Unit,onRemove:(String)->Unit){
    var type by rememberSaveable{mutableStateOf<MediaType?>(null)}
    val visible=remember(rows,type,ready){if(ready)rows.filter{type==null||it.mediaType==type}else emptyList()}
    val queues=remember(visible){visible.groupBy{it.mediaType}.mapValues{(_,v)->v.map{it.id}}}
    Column(Modifier.fillMaxSize().testTag("unified-favorites")){
        Row(Modifier.fillMaxWidth().padding(8.dp),verticalAlignment=Alignment.CenterVertically){
            IconButton(onBack){Icon(Icons.AutoMirrored.Rounded.ArrowBack,"Назад")}
            Text("Избранное",style=MaterialTheme.typography.headlineSmall)
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=16.dp),
            horizontalArrangement=Arrangement.spacedBy(8.dp)){
            for((value,label) in listOf(null to "Все",MediaType.PHOTO to "Фото",MediaType.VIDEO to "Видео",MediaType.AUDIO to "Музыка")){
                FilterChip(selected=type==value,onClick={type=value},label={Text(label)})
            }
        }
        if(!galleryAccess&&(type==null||type!=MediaType.AUDIO))
            TextButton(onGalleryAccess,Modifier.padding(horizontal=8.dp)){Text("Разрешить доступ к фото и видео")}
        if(!musicAccess&&(type==null||type==MediaType.AUDIO))
            TextButton(onMusicAccess,Modifier.padding(horizontal=8.dp)){Text("Разрешить доступ к музыке")}
        if(loading||!ready)LinearProgressIndicator(Modifier.fillMaxWidth())
        if(ready&&visible.isEmpty()&&!loading)Text("Нет избранных файлов",Modifier.padding(20.dp))
        LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(16.dp),
            verticalArrangement=Arrangement.spacedBy(8.dp)){
            items(visible,key={it.id}){row->
                val ui=row.toUi()
                Surface(onClick={onOpen(row.id,queues[row.mediaType].orEmpty())},
                    shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surfaceContainer){
                    Row(Modifier.fillMaxWidth().padding(10.dp),verticalAlignment=Alignment.CenterVertically,
                        horizontalArrangement=Arrangement.spacedBy(12.dp)){
                        DownloadThumbnail(ui.savedUri,ui.previewUrl,ui.kind,Modifier.size(52.dp))
                        Column(Modifier.weight(1f)){
                            Text(ui.title,style=MaterialTheme.typography.bodyLarge,maxLines=2,
                                overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Text(listOfNotNull(when(row.mediaType){MediaType.PHOTO->"Фото";MediaType.VIDEO->"Видео";MediaType.AUDIO->"Музыка"},
                                ui.sizeLabel).joinToString(", "),style=MaterialTheme.typography.bodySmall,
                                color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton({onRemove(row.id)}){
                            Icon(Icons.Rounded.Favorite,"Убрать из избранного",tint=MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}
