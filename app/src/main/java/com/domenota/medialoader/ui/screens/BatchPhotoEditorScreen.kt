package com.domenota.medialoader.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.data.editor.*
import kotlinx.coroutines.CancellationException

/** Navigation adapter only: the actual tools and preview live in PhotoEditorContent. */
@Composable
fun BatchPhotoEditorScreen(rows:List<DownloadEntity>,state:BatchPhotoState,busy:Boolean,ready:Boolean,
    onBack:()->Unit,onStart:(List<DownloadEntity>,BatchPhotoSpec)->Unit,onCancel:()->Unit) =
    SharedBatchPhotoWorkspace(rows,state,busy,ready,onBack,onStart,onCancel)

@Composable
internal fun BatchPhotoFilmstrip(rows: List<DownloadEntity>, index:Int, enabled:Boolean,
    compare:Boolean,onCompare:()->Unit,onSelect:(Int)->Unit) {
    val context=LocalContext.current
    val cache=remember { BatchPhotoPreviewCache() }
    DisposableEffect(cache) { onDispose { cache.clear() } }
    Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
        Text("${index.coerceIn(0,(rows.size-1).coerceAtLeast(0))+1} / ${rows.size}",color=Color.White,
            modifier=Modifier.weight(1f))
        FilterChip(selected=compare,onClick=onCompare,label={Text("До / После")})
    }
    LazyRow(Modifier.fillMaxWidth(),contentPadding=PaddingValues(horizontal=12.dp),
        horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        itemsIndexed(rows,key={_,row->row.id}) { i,row ->
            val uri=row.savedUri
            var bitmap by remember(row.id,uri,row.sizeBytes,row.createdAtEpochMillis) { mutableStateOf<Bitmap?>(null) }
            LaunchedEffect(row.id,uri,row.sizeBytes,row.createdAtEpochMillis,row.isPrivate,row.hidden) {
                if(uri==null || row.isPrivate || row.hidden) return@LaunchedEffect
                try {
                    val source=Uri.parse(uri)
                    val revision=batchPhotoSourceRevision(context,source)
                    bitmap=cache.image("${row.id}:$uri:${row.sizeBytes}:$revision") {
                        decodeBatchPreview(context,source,192)
                    }
                } catch(e:CancellationException) { throw e } catch(_:Exception) { bitmap=null }
            }
            Surface(onClick={onSelect(i)},enabled=enabled,shape=RoundedCornerShape(8.dp),
                color=if(i==index) MaterialTheme.colorScheme.primary else Color(0xFF252529),
                modifier=Modifier.size(54.dp).testTag("batch-photo-$i")) {
                Box(Modifier.padding(2.dp),contentAlignment=Alignment.Center) {
                    bitmap?.let { Image(it.asImageBitmap(),"Фото ${i+1}",Modifier.fillMaxSize(),contentScale=ContentScale.Fit) }
                        ?: Text("${i+1}",color=Color.White)
                }
            }
        }
    }
}
