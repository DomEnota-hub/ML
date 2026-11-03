package com.domenota.medialoader.ui.screens

import android.view.LayoutInflater
import android.view.TextureView
import androidx.media3.ui.PlayerView
import com.domenota.medialoader.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VideoPreviewSurfaceTest {
    @Test fun editorUsesCompositedTextureSurfaceInsteadOfSurfaceView() {
        val view = LayoutInflater.from(RuntimeEnvironment.getApplication())
            .inflate(R.layout.video_editor_preview, null, false) as PlayerView
        assertTrue(view.videoSurfaceView is TextureView)
        assertFalse(view.useController)
    }
}
