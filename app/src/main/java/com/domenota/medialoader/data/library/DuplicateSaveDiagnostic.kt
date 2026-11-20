package com.domenota.medialoader.data.library

import android.content.Context
import com.domenota.medialoader.core.logging.AppLog

/** Failure codes contain no filenames, user paths, IDs, or acoustic data. */
internal object DuplicateSaveDiagnostic {
    fun record(context:Context,stage:String,error:Throwable?) {
        val heap=Runtime.getRuntime()
        val used=(heap.totalMemory()-heap.freeMemory())/(1024L*1024L)
        val limit=heap.maxMemory()/(1024L*1024L)
        val free=runCatching{context.noBackupFilesDir.usableSpace/(1024L*1024L)}.getOrDefault(-1)
        val type=error?.javaClass?.simpleName?.take(60) ?: "Guard"
        AppLog.e("MUSIC_REPORT_COMMIT","stage=$stage type=$type heapMiB=$used/$limit freeStorageMiB=$free")
    }
}
