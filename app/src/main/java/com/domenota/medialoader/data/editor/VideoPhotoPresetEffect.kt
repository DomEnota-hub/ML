package com.domenota.medialoader.data.editor

import android.content.Context
import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import java.nio.ByteBuffer

/** The photo catalog's actual matrix/fade/noise, shared by player and MP4. */
@OptIn(UnstableApi::class)
internal data class VideoPhotoPresetEffect(val preset: PhotoEffect, val strength: Float) : GlEffect {
    init { require(strength.isFinite() && strength in 0f..1f) }
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): BaseGlShaderProgram {
        if (useHdr) throw VideoFrameProcessingException("Фотофильтры требуют SDR: сначала преобразуйте HDR в SDR")
        return try { PhotoPresetShader(context, preset, strength) }
        catch (error: java.io.IOException) { throw VideoFrameProcessingException(error) }
        catch (error: GlUtil.GlException) { throw VideoFrameProcessingException(error) }
    }
}

@OptIn(UnstableApi::class)
private class PhotoPresetShader(context: Context, preset: PhotoEffect, strength: Float) :
    BaseGlShaderProgram(false, 1) {
    private val program = GlProgram(context, "shaders/vertex_shader_transformation_es2.glsl",
        "shaders/ml_photo_preset.glsl")
    private val grain = preset.grain / 100f * strength
    private var noiseId = -1
    private var width = 0
    private var height = 0

    init {
        program.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
        program.setFloatsUniform("uTransformationMatrix", GlUtil.create4x4IdentityMatrix())
        program.setFloatsUniform("uTexTransformationMatrix", GlUtil.create4x4IdentityMatrix())
        val matrix = PhotoColorMath.matrix(PhotoColorAdjustments(effect = preset,
            effectStrength = strength * 100f)).array
        for ((name, row) in listOf("uRed" to 0, "uGreen" to 5, "uBlue" to 10)) {
            program.setFloatsUniform(name, floatArrayOf(matrix[row], matrix[row + 1],
                matrix[row + 2], matrix[row + 4] / 255f))
        }
        program.setFloatUniform("uFade", preset.fade / 100f * strength)
        program.setFloatUniform("uGrain", grain)
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        if (width == inputWidth && height == inputHeight) return Size(width, height)
        if (noiseId != -1) GlUtil.deleteTexture(noiseId)
        width = inputWidth; height = inputHeight
        val nw = if (grain > 0f) width else 1
        val nh = if (grain > 0f) height else 1
        // One byte per pixel (8.3MB at UHD), no full-frame ARGB CPU bitmap.
        val pixels = ByteBuffer.allocateDirect(Math.multiplyExact(nw, nh))
        for (y in 0 until nh) for (x in 0 until nw) {
            val hash = (x * 73856093) xor (y * 19349663) xor 0x35A7C29
            pixels.put(((hash xor (hash ushr 13)) and 255).toByte())
        }
        pixels.flip()
        noiseId = GlUtil.generateTexture()
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, noiseId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, nw, nh, 0,
            GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, pixels)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 4)
        GlUtil.checkGlError()
        return Size(width, height)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            program.setSamplerTexIdUniform("uNoise", noiseId, 1)
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GlUtil.checkGlError()
        } catch (error: GlUtil.GlException) { throw VideoFrameProcessingException(error, presentationTimeUs) }
    }

    override fun release() {
        try { super.release() } finally {
            try { if (noiseId != -1) GlUtil.deleteTexture(noiseId) }
            finally { noiseId = -1; program.delete() }
        }
    }
}
