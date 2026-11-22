package com.domenota.medialoader.data.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.annotation.OptIn
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.StaticOverlaySettings
import kotlin.math.roundToInt

/** Static black outside the crop circle, transparent inside. Last overlay in
 * BOTH live preview and Transformer export. No per-video-frame bitmap allocation. */
@OptIn(UnstableApi::class)
internal class VideoCircleMaskOverlay(
    private val specProvider: () -> VideoEditSpec,
) : BitmapOverlay() {
    private data class MaskKey(
        val shape: MediaCropShape, val bounds: CropFractions,
        val rotation: Int, val horizontal: Boolean, val vertical: Boolean,
    )
    private var lastKey: MaskKey? = null
    private var image: Bitmap? = null
    private var size = Size(1, 1)
    private var scale = 1f

    override fun configure(videoSize: Size) {
        super.configure(videoSize)
        size=videoSize
        scale=(1280f/maxOf(videoSize.width,videoSize.height).toFloat()).coerceAtMost(1f)
        lastKey=null
        image?.recycle()
        image=null
    }

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val s=specProvider()
        val key=MaskKey(s.cropShape,CropFractions(s.cropLeft,s.cropRight,s.cropTop,s.cropBottom),
            s.rotationDegrees,s.mirrorHorizontal,s.mirrorVertical)
        if(lastKey==key && image!=null)return requireNotNull(image)
        image?.recycle()
        val active=key.shape==MediaCropShape.CIRCLE
        val width=if(active)(size.width*scale).roundToInt().coerceAtLeast(1) else 1
        val height=if(active)(size.height*scale).roundToInt().coerceAtLeast(1) else 1
        val mask=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        if(active){
            val circle=MediaCircleCrop.region(width,height,key.bounds)
            val canvas=Canvas(mask)
            canvas.drawColor(Color.BLACK)
            val punch=Paint(Paint.ANTI_ALIAS_FLAG).apply{
                xfermode=PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            }
            canvas.drawCircle(circle.centerX,circle.centerY,circle.radius,punch)
            punch.xfermode=null
        }
        image=mask
        lastKey=key
        return mask
    }

    override fun getOverlaySettings(presentationTimeUs: Long): StaticOverlaySettings {
        val active=specProvider().cropShape==MediaCropShape.CIRCLE
        return StaticOverlaySettings.Builder()
            .setScale(if(active)1f/scale else 1f,if(active)1f/scale else 1f)
            .setAlphaScale(if(active)1f else 0f).build()
    }

    override fun release() {
        try { super.release() }
        finally { image?.recycle();image=null;lastKey=null }
    }
}
