package com.domenota.medialoader.data.editor

import android.content.Context
import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.*
import androidx.media3.effect.*
import java.util.concurrent.atomic.AtomicReference

/** Stable Media3 effect identity; replace colour programs INSIDE the GL stage, never its stream. */
@OptIn(UnstableApi::class)
internal class VideoPreviewColorEffect(private val context: Context,
    private val state: AtomicReference<VideoEditSpec>) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): BaseGlShaderProgram =
        PreviewColors(context, useHdr, state)
}

@OptIn(UnstableApi::class)
private class PreviewColors(private val context: Context, private val hdr: Boolean,
    private val state: AtomicReference<VideoEditSpec>) : BaseGlShaderProgram(hdr, 1) {
    private var grade: VideoColorGrade? = null
    private var stages = emptyList<BaseGlShaderProgram>()
    private var width = 0
    private var height = 0
    private val textures = IntArray(2) { -1 }
    private val fbos = IntArray(2) { -1 }
    private val savedFbo = IntArray(1)
    private val copy = GlProgram(context, "shaders/vertex_shader_transformation_es2.glsl", "shaders/ml_preview_copy.glsl").apply {
        setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
        setFloatsUniform("uTransformationMatrix", GlUtil.create4x4IdentityMatrix())
        setFloatsUniform("uTexTransformationMatrix", GlUtil.create4x4IdentityMatrix())
    }
    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        width = inputWidth; height = inputHeight
        stages.forEach { it.release() }; stages = emptyList(); grade = null
        clearScratch()
        return Size(width, height)
    }
    private fun clearScratch() {
        for (i in 0..1) {
            if (fbos[i] != -1) GlUtil.deleteFbo(fbos[i])
            if (textures[i] != -1) GlUtil.deleteTexture(textures[i])
            fbos[i] = -1; textures[i] = -1
        }
    }
    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, savedFbo, 0)
        try {
            val next = state.get().color
            if (next != grade) {
                stages.forEach { it.release() }; stages = emptyList()
                val created = mutableListOf<BaseGlShaderProgram>()
                try {
                    VideoEditor(context).colorEffects(next).forEach { effect ->
                        val stage = (effect as GlEffect).toGlShaderProgram(context, hdr) as BaseGlShaderProgram
                        created += stage
                        val size = stage.configure(width, height)
                        check(size.width == width && size.height == height) { "Colour stage changed geometry" }
                    }
                    stages = created.toList(); grade = next
                } catch (error: Throwable) { created.forEach { it.release() }; throw error }
            }
            if (stages.size > 1 && textures[0] == -1) for (i in 0..1) {
                textures[i] = GlUtil.createTexture(width, height, hdr)
                fbos[i] = GlUtil.createFboForTexture(textures[i])
            }
            var input = inputTexId
            if (stages.isEmpty()) {
                GlUtil.focusFramebufferUsingCurrentContext(savedFbo[0], width, height)
                copy.use(); copy.setSamplerTexIdUniform("uTexSampler", input, 0)
                copy.bindAttributesAndUniforms(); GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            } else stages.forEachIndexed { index, stage ->
                val last = index == stages.lastIndex
                GlUtil.focusFramebufferUsingCurrentContext(if (last) savedFbo[0] else fbos[index % 2], width, height)
                stage.drawFrame(input, presentationTimeUs)
                if (!last) input = textures[index % 2]
            }
            GlUtil.checkGlError()
        } catch (error: Exception) { throw VideoFrameProcessingException(error, presentationTimeUs) }
        finally { GlUtil.focusFramebufferUsingCurrentContext(savedFbo[0], width, height) }
    }
    override fun release() {
        try { super.release() } finally {
            try { stages.forEach { it.release() }; stages = emptyList(); clearScratch() }
            finally { copy.delete() }
        }
    }
}
