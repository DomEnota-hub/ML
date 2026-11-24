package com.domenota.medialoader.data.library

/** One exact resampled sample stream feeds both existing algorithms.
 * PCM is owned by one pair, never persisted. An over-limit stream discards
 * the WHOLE PCM capture, but spectral frames still receive every sample.
 */
internal data class ComparisonFrameCapture(
    val frames:List<ComparisonFrame>,
    val pcm:AudioPcmBlocks?,
    val pcmAttempted:Boolean,
)

internal class ComparisonSampleCapture(
    stepMs:Long,
    capturePcm:Boolean,
    checkActive:()->Unit,
    private val pcmLimit:Int=AudioPcmBlocks.MAX_SAMPLES,
) {
    private val builder=AcousticFingerprints.ComparisonBuilder(stepMs,checkActive)
    private var pcm=if(capturePcm)AudioPcmBlocks() else null
    private val attempted=capturePcm
    init { require(pcmLimit in 1..AudioPcmBlocks.MAX_SAMPLES) }
    fun accept(sample:Float){
        builder.accept(sample)
        val blocks=pcm ?: return
        if(blocks.size>=pcmLimit)pcm=null else blocks.append(sample)
    }
    fun finish()=ComparisonFrameCapture(builder.finish(),pcm,attempted)
}
