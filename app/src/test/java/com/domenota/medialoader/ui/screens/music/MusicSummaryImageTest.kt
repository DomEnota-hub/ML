package com.domenota.medialoader.ui.screens.music

import android.graphics.Color
import com.domenota.medialoader.data.playback.SmartMusicSnapshot
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MusicSummaryImageTest {
    @Test fun createsPngFromActualSnapshotWithoutMediaFileAccess(){
        val bitmap=MusicSummaryImage.render(MusicSummaryExport("Год",SmartMusicSnapshot(plays=2,wallMs=60_000,
            tracksPlayed=1,artistsPlayed=1),"Тестовая композиция",Color.BLACK,Color.DKGRAY,Color.WHITE,Color.LTGRAY,Color.YELLOW))
        try {
            assertEquals(1080,bitmap.width);assertEquals(1440,bitmap.height)
            val out=ByteArrayOutputStream()
            assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out))
            assertTrue(out.size()>1000)
        }finally{bitmap.recycle()}
    }
}
