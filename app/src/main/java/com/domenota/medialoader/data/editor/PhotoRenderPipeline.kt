package com.domenota.medialoader.data.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Same ordered pixel operations for the editor's preview and export.
 * A caller-owned source is never recycled when retainSource is true. */
internal object PhotoRenderPipeline {
    suspend fun render(context:Context,source:Bitmap,crop:CropFractions,spec:PhotoEditSpec,
        fitInside:Boolean=false,flatten:Boolean=false,retainSource:Boolean=true,
        onProgress:(Int)->Unit={}):Bitmap {
        var working=source
        fun replace(next:Bitmap) {
            val old=working
            working=next
            if(old!==next && (!retainSource || old!==source) && !old.isRecycled) old.recycle()
        }
        try {
            currentCoroutineContext().ensureActive()
            if(crop!=CropFractions() || spec.rotationDegrees%360!=0 || spec.mirrorHorizontal ||
                spec.mirrorVertical || !spec.geometry.isNeutral()) {
                replace(PhotoGeometryMath.preview(working,crop,spec.rotationDegrees,
                    spec.mirrorHorizontal,spec.mirrorVertical,spec.geometry))
            }
            onProgress(50)
            currentCoroutineContext().ensureActive()
            if(!spec.colorAdjustments.isNeutral()) replace(PhotoColorMath.apply(working,spec.colorAdjustments))
            if(spec.localEffects.isNotEmpty()) replace(PhotoLocalEffects.apply(working,spec.localEffects))
            onProgress(75)
            currentCoroutineContext().ensureActive()
            val hasText=spec.textLayers.any { it.text.isNotBlank() && it.opacity>0f }
            if(flatten || spec.graphicWatermark!=null || spec.drawing.isNotEmpty() || hasText) {
                val composed=if(flatten) Bitmap.createBitmap(working.width,working.height,Bitmap.Config.ARGB_8888)
                    else working.copy(Bitmap.Config.ARGB_8888,true)
                try {
                    val canvas=Canvas(composed)
                    if(flatten) { canvas.drawColor(Color.WHITE);canvas.drawBitmap(working,0f,0f,null) }
                    spec.graphicWatermark?.let { PhotoGraphicWatermarkRenderer.draw(context,canvas,
                        composed.width,composed.height,it) }
                    if(spec.drawing.isNotEmpty()) PhotoDrawing.draw(canvas,composed.width,composed.height,spec.drawing)
                    if(hasText) TextLayerRenderer.draw(context,canvas,composed.width,composed.height,
                        spec.textLayers,includeAll=true,fitInside=fitInside)
                } catch(error:Throwable) { composed.recycle();throw error }
                replace(composed)
            }
            currentCoroutineContext().ensureActive()
            return working
        } catch(error:Throwable) {
            if((!retainSource || working!==source) && !working.isRecycled) working.recycle()
            throw error
        }
    }
}
