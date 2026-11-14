package com.domenota.medialoader.data.editor

import android.content.Context
import android.net.Uri
import kotlin.math.roundToInt

enum class BatchPhotoFormat(val extension:String,val mime:String) {
    JPEG("jpg","image/jpeg"), PNG("png","image/png"), WEBP("webp","image/webp")
}
data class BatchPhotoSpec(
    val maxSide:Int=1920, val format:BatchPhotoFormat=BatchPhotoFormat.JPEG,
    val quality:Int=85, val removeGps:Boolean=true, val removeExif:Boolean=false,
    val effect:PhotoEffect=PhotoEffect.NONE, val watermark:String="",
    val effectStrength:Float=100f,
    val watermarkLayers:List<TextLayer> = emptyList(),
    /** The shared workspace supplies the full edit; legacy callers retain their previous options. */
    val editSpec: PhotoEditSpec? = null,
    val cropPreset: CropAspectPreset = CropAspectPreset.FREE,
    val cropReferenceWidth: Int = 0,
    val cropReferenceHeight: Int = 0,
) {
    init { require(maxSide in 0..8192 && quality in 1..100 && watermark.length<=100 && effectStrength.isFinite() && effectStrength in 0f..100f && watermarkLayers.size<=8) }
    fun photoEdit(): PhotoEditSpec = editSpec ?: PhotoEditSpec(
        colorAdjustments = PhotoColorAdjustments(effect=effect,effectStrength=effectStrength),
        textLayers = textLayers())
    fun colorAdjustments() = editSpec?.colorAdjustments ?:
        PhotoColorAdjustments(effect=effect,effectStrength=effectStrength)
    fun cropFor(width: Int, height: Int): CropFractions {
        val edit = photoEdit()
        return PhotoBatchCrop.adapt(CropFractions(edit.cropLeft,edit.cropRight,
            edit.cropTop,edit.cropBottom), cropPreset, cropReferenceWidth,
            cropReferenceHeight, width, height)
    }
    /** Old saved callers retain the bottom-center string watermark. Rich drafts take precedence. */
    fun textLayers(): List<TextLayer> = if(watermarkLayers.isNotEmpty()) watermarkLayers else
        if(watermark.isNotBlank()) listOf(TextLayer(text=watermark,centerX=.5f,centerY=.9f,size=.045f,opacity=.8f)) else emptyList()
    fun dimensions(w:Int,h:Int):Pair<Int,Int> {
        require(w>0 && h>0)
        val scale=if(maxSide==0) 1.0 else minOf(1.0,maxSide.toDouble()/maxOf(w,h))
        return (w*scale).roundToInt().coerceAtLeast(1) to (h*scale).roundToInt().coerceAtLeast(1)
    }
}
data class BatchPhotoOutcome(val id:String,val name:String,val error:String?=null)
data class BatchPhotoState(val running:Boolean=false,val total:Int=0,
    val outcomes:List<BatchPhotoOutcome> = emptyList(),val cancelled:Boolean=false,
    val spec:BatchPhotoSpec?=null) {
    val failed get()=outcomes.filter{it.error!=null}
}

class BatchPhotoEditor(private val context:Context) {
    suspend fun export(uri:Uri,spec:BatchPhotoSpec):PhotoExportResult =
        PhotoEditor(context).exportBatch(uri,spec)
}
