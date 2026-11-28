package com.domenota.medialoader.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.domenota.medialoader.core.logging.MusicRefreshBlock
import com.domenota.medialoader.core.logging.MusicRefreshTrace
import kotlinx.coroutines.delay

/** Keyed only by real read-model state; no log per composition, no media data.
 * A trailing transition is emitted even if the last change was rate-limited. */
@Composable
internal fun MusicReadModelTrace(block:MusicRefreshBlock,ready:Boolean,usable:Boolean,loading:Boolean,
    error:Boolean,count:Int,guardPresent:Boolean,accessEpoch:Long=0L,privacyEpoch:Long=0L,reasonMask:Int=0) {
    LaunchedEffect(block,ready,usable,loading,error,count,guardPresent,accessEpoch,privacyEpoch,reasonMask){
        if(!MusicRefreshTrace.transition(block,ready,usable,loading,error,count,guardPresent,accessEpoch,privacyEpoch,reasonMask)){
            delay(500)
            MusicRefreshTrace.transition(block,ready,usable,loading,error,count,guardPresent,accessEpoch,privacyEpoch,reasonMask)
        }
    }
}
