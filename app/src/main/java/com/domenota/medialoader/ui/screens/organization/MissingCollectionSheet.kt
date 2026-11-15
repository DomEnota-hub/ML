package com.domenota.medialoader.ui.screens.organization

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.database.MediaCollectionMemberEntity
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.ui.components.NoticeSheet
import com.domenota.medialoader.ui.components.CachedDownloadThumbnail
import com.domenota.medialoader.ui.model.MediaKind

internal fun missingCollectionDisplayName(row: DownloadEntity): String {
    val extensions = when(row.mediaType) {
        MediaType.PHOTO -> setOf("jpg","jpeg","png","webp","gif","heic","heif","bmp","avif")
        MediaType.VIDEO -> setOf("mp4","mkv","webm","mov","avi","m4v","3gp")
        MediaType.AUDIO -> setOf("mp3","m4a","aac","flac","ogg","opus","wav","wma")
    }
    val name=row.originalName
    val extension=name.substringAfterLast('.', "")
    return if(extension.lowercase(java.util.Locale.ROOT) in extensions && name.substringBeforeLast('.').isNotBlank()) name.substringBeforeLast('.') else name
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MissingCollectionSheet(members:List<MediaCollectionMemberEntity>,metadata:Map<String,DownloadEntity>,
    canRecover:(DownloadEntity)->Boolean,onRecover:(DownloadEntity)->Unit,onInfo:(String)->Unit,onRemove:(String)->Unit,onDismiss:()->Unit,
    selecting:Boolean=false,chosen:Set<String> = emptySet(),enabled:Boolean=true,onToggle:(String)->Unit={}){
    NoticeSheet(onDismissRequest=onDismiss) {
        Column(Modifier.fillMaxWidth().testTag("collection-missing-sheet").padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Row(verticalAlignment=Alignment.CenterVertically){
                Text("Недоступные файлы",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
                IconButton(onDismiss){Icon(Icons.Rounded.Close,"Закрыть")}
            }
            Text("Файлы сейчас недоступны. Они по-прежнему входят в коллекцию.",color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodyMedium)
            LazyColumn(Modifier.fillMaxWidth().weight(1f,fill=false),verticalArrangement=Arrangement.spacedBy(8.dp),contentPadding=PaddingValues(bottom=16.dp)){
                items(members,key={it.id}){member->
                    val row=metadata[member.id]
                    var menu by remember{mutableStateOf(false)}
                    Surface(modifier=Modifier.then(if(selecting)Modifier.clickable(enabled=enabled){onToggle(member.id)} else Modifier),shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surfaceVariant){
                        Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
                                val kind=when(row?.mediaType){MediaType.PHOTO->MediaKind.IMAGE;MediaType.VIDEO->MediaKind.VIDEO;MediaType.AUDIO->MediaKind.AUDIO;null->null}
                                if(row!=null&&kind!=null)CachedDownloadThumbnail(row.savedUri,row.previewUrl,kind,Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)))
                                else Box(Modifier.size(56.dp),contentAlignment=Alignment.Center){Icon(Icons.Rounded.BrokenImage,null,tint=MaterialTheme.colorScheme.onSurfaceVariant)}
                                Column(Modifier.weight(1f)){
                                    Text(row?.let(::missingCollectionDisplayName)?:"Недоступный файл",maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleSmall)
                                    if(row!=null)Text(when(row.mediaType){MediaType.PHOTO->"Фото";MediaType.VIDEO->"Видео";MediaType.AUDIO->"Аудио"}+" · "+when(row.providerId){"instagram"->"Instagram";"youtube"->"YouTube";"vk"->"VK";"rutube"->"RUTUBE";"device"->"Устройство";else->row.providerId},style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("Файл недоступен",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
                                }
                                if(selecting)PickerMark(member.id in chosen,false,Modifier.testTag("missing-select-${member.id}"),enabled){onToggle(member.id)}
                                else Box{
                                    IconButton({menu=true},enabled=enabled){Icon(Icons.Rounded.MoreVert,"Действия недоступного файла")}
                                    DropdownMenu(menu,{menu=false}){
                                        DropdownMenuItem({Text("Информация о файле")},{menu=false;onInfo(member.id)})
                                        DropdownMenuItem({Text("Исключить из коллекции")},{menu=false;onRemove(member.id)})
                                    }
                                }
                            }
                            if(!selecting && row!=null&&canRecover(row))OutlinedButton({onRecover(row)},Modifier.fillMaxWidth(),enabled=enabled){
                                Icon(Icons.Rounded.Download,null,Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text("Восстановить")
                            }else if(!selecting) Text("Восстановление недоступно",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
