package com.domenota.medialoader.testing

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.data.editor.EditorExportState
import com.domenota.medialoader.ui.screens.VideoEditorScreen
import com.domenota.medialoader.ui.theme.MediaLoaderTheme

/** Opens the actual editor with a synthetic source. No release component or alternate UI. */
class VideoEditorUiProbeActivity : ComponentActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        enableEdgeToEdge()
        val item = DownloadEntity(id = "ui-reference-probe", providerId = "synthetic",
            originalName = "Synthetic reference", mediaType = MediaType.VIDEO,
            state = DownloadState.COMPLETED, savedUri = requireNotNull(intent.data).toString(),
            sizeBytes = null, createdAtEpochMillis = 0L)
        setContent {
            MediaLoaderTheme {
                VideoEditorScreen(item, EditorExportState(), onBack = { finish() },
                    onExport = {}, onSaveFrame = { _, _ -> }, onExtractAudio = { _, _ -> },
                    onCancelExport = {})
            }
        }
    }
}
