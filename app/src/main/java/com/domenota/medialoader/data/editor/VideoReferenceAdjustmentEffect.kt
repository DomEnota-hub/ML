package com.domenota.medialoader.data.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import kotlin.math.roundToInt

/** Small LUT construction happens on Media3's GL worker, never on Compose's main thread. */
@OptIn(UnstableApi::class)
internal data class VideoReferenceAdjustmentEffect(val settings: VideoReferenceAdjustments) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): BaseGlShaderProgram {
        if (useHdr) throw VideoFrameProcessingException("Коррекция видео требует SDR")
        return try { ReferenceAdjustmentShader(context, settings) }
        catch (error: java.io.IOException) { throw VideoFrameProcessingException(error) }
        catch (error: GlUtil.GlException) { throw VideoFrameProcessingException(error) }
    }
}

/** Matrix/tone algorithms shared with the photo editor; interpolation adds at most LUT quantization. */
internal object VideoReferenceAdjustmentLut {
    const val SIZE = 33
    fun bitmap(settings: VideoReferenceAdjustments): Bitmap {
        val photo = settings.toPhoto()
        val matrix = PhotoColorMath.matrix(photo).array
        val tone = PhotoToneMath.prepare(photo)
        val scratch = FloatArray(3)
        val pixels = IntArray(SIZE * SIZE * SIZE)
        for (r in 0 until SIZE) for (g in 0 until SIZE) for (b in 0 until SIZE) {
            val red = (r * 255f / (SIZE - 1)).roundToInt()
            val green = (g * 255f / (SIZE - 1)).roundToInt()
            val blue = (b * 255f / (SIZE - 1)).roundToInt()
            fun channel(row: Int) = (matrix[row] * red + matrix[row + 1] * green +
                matrix[row + 2] * blue + matrix[row + 4]).roundToInt().coerceIn(0, 255)
            val mapped = Color.rgb(channel(0), channel(5), channel(10))
            pixels[b + SIZE * (g + SIZE * r)] = PhotoToneMath.tonePixel(mapped, tone, scratch)
        }
        return Bitmap.createBitmap(pixels, SIZE, SIZE * SIZE, Bitmap.Config.ARGB_8888)
    }
}

@OptIn(UnstableApi::class)
private class ReferenceAdjustmentShader(context: Context, settings: VideoReferenceAdjustments) :
    BaseGlShaderProgram(false, 1) {
    private val program = GlProgram(context, "shaders/vertex_shader_transformation_es2.glsl",
        "shaders/ml_video_adjustment.glsl")
    private var lutTexture = -1
    init {
        try {
            program.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(),
                GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
            program.setFloatsUniform("uTransformationMatrix", GlUtil.create4x4IdentityMatrix())
            program.setFloatsUniform("uTexTransformationMatrix", GlUtil.create4x4IdentityMatrix())
            program.setFloatUniform("uLutSize", VideoReferenceAdjustmentLut.SIZE.toFloat())
            program.setFloatUniform("uSharpness", settings.sharpness / 100f * .95f)
            val bitmap = VideoReferenceAdjustmentLut.bitmap(settings)
            try { lutTexture = GlUtil.createTexture(bitmap) } finally { bitmap.recycle() }
        } catch (error: Throwable) {
            program.delete()
            throw error
        }
    }
    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        program.setFloatsUniform("uTexelSize", floatArrayOf(1f / inputWidth, 1f / inputHeight))
        return Size(inputWidth, inputHeight)
    }
    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            program.setSamplerTexIdUniform("uToneLut", lutTexture, 1)
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GlUtil.checkGlError()
        } catch (error: GlUtil.GlException) { throw VideoFrameProcessingException(error, presentationTimeUs) }
    }
    override fun release() {
        try { super.release() } finally {
            try { if (lutTexture != -1) GlUtil.deleteTexture(lutTexture) }
            finally { lutTexture = -1; program.delete() }
        }
    }
}
