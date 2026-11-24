package com.domenota.medialoader.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.ViewList
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("ui", 0) }
    var gridMode by rememberSaveable { mutableStateOf(prefs.getBoolean("favorites_grid", false)) }
    var type by rememberSaveable{mutableStateOf<MediaType?>(null)}
    val visible=remember(rows,type,ready){
        if(ready)com.domenota.medialoader.ui.navigation.filterLibraryMedia(rows,type){it.mediaType}else emptyList()
    }
    val queues=remember(visible){visible.groupBy{it.mediaType}.mapValues{(_,v)->v.map{it.id}}}
    Column(Modifier.fillMaxSize().testTag("unified-favorites")){
        Row(Modifier.fillMaxWidth().padding(8.dp),verticalAlignment=Alignment.CenterVertically){
            IconButton(onBack){Icon(Icons.AutoMirrored.Rounded.ArrowBack,"Назад")}
            Text("Избранное",Modifier.weight(1f),style=MaterialTheme.typography.headlineSmall)
            IconButton(onClick={
                gridMode=!gridMode
                prefs.edit().putBoolean("favorites_grid",gridMode).apply()
            }){
                Icon(if(gridMode) Icons.Rounded.ViewList else Icons.Rounded.GridView,
                    if(gridMode) "Показать списком" else "Показать сеткой",
                    tint=MaterialTheme.colorScheme.primary)
            }
        }
        com.domenota.medialoader.ui.components.LibraryMediaTypeFilters(type,{type=it})
        if(!galleryAccess&&(type==null||type!=MediaType.AUDIO))
            TextButton(onGalleryAccess,Modifier.padding(horizontal=8.dp)){Text("Разрешить доступ к фото и видео")}
        if(!musicAccess&&(type==null||type==MediaType.AUDIO))
            TextButton(onMusicAccess,Modifier.padding(horizontal=8.dp)){Text("Разрешить доступ к музыке")}
        if(loading||!ready)LinearProgressIndicator(Modifier.fillMaxWidth())
        if(ready&&visible.isEmpty()&&!loading)Text("Нет избранных файлов",Modifier.padding(20.dp))
        LazyVerticalGrid(columns=GridCells.Fixed(if(gridMode)4 else 1),modifier=Modifier.weight(1f),
            contentPadding=PaddingValues(if(gridMode)4.dp else 16.dp),
            horizontalArrangement=Arrangement.spacedBy(4.dp),
            verticalArrangement=Arrangement.spacedBy(if(gridMode)4.dp else 8.dp)){
            items(visible,key={it.id}){row->
                val ui=row.toUi()
                val displayName = com.domenota.medialoader.ui.model.displayFileName(ui.title,ui.kind)
                if(gridMode) {
                    Column {
                        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                            Surface(onClick={onOpen(row.id,queues[row.mediaType].orEmpty())},
                                modifier=Modifier.fillMaxSize().testTag("favorite-row-${row.id}")
                                    .semantics { contentDescription = displayName },
                                shape=MaterialTheme.shapes.small){
                                DownloadThumbnail(ui.savedUri,ui.previewUrl,ui.kind,Modifier.fillMaxSize())
                            }
                            IconButton({onRemove(row.id)},Modifier.align(Alignment.TopEnd).size(48.dp)){
                                Surface(shape=androidx.compose.foundation.shape.CircleShape,
                                    color=MaterialTheme.colorScheme.surface.copy(alpha=.9f)){
                                    Icon(Icons.Rounded.Favorite,"Убрать из избранного",Modifier.size(24.dp),tint=MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                        Text(displayName,
                            maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            style=MaterialTheme.typography.labelSmall)
                    }
                } else Surface(onClick={onOpen(row.id,queues[row.mediaType].orEmpty())},
                    modifier=Modifier.testTag("favorite-row-${row.id}"),
                    shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surfaceContainer){
                    Row(Modifier.fillMaxWidth().padding(10.dp),verticalAlignment=Alignment.CenterVertically,
                        horizontalArrangement=Arrangement.spacedBy(12.dp)){
                        DownloadThumbnail(ui.savedUri,ui.previewUrl,ui.kind,Modifier.size(52.dp))
                        Column(Modifier.weight(1f)){
                            Text(displayName,style=MaterialTheme.typography.bodyLarge,maxLines=2,
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
