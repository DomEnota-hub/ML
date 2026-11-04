package com.domenota.medialoader.data.editor

/** HTML v8's thirteen controls. Existing legacy RGB/HSL values remain independent. */
data class VideoReferenceAdjustments(
    val exposure: Float = 0f, val brightness: Float = 0f, val contrast: Float = 0f,
    val highlights: Float = 0f, val shadows: Float = 0f, val saturation: Float = 0f,
    val vibrance: Float = 0f, val warmth: Float = 0f, val tint: Float = 0f,
    val blackPoint: Float = 0f, val sharpness: Float = 0f,
    val grain: Float = 0f, val vignette: Float = 0f,
) {
    init {
        require(listOf(exposure, brightness, contrast, highlights, shadows, saturation,
            vibrance, warmth, tint).all { it.isFinite() && it in -100f..100f })
        require(listOf(blackPoint, sharpness, grain, vignette).all { it.isFinite() && it in 0f..100f })
    }
    val isNeutral: Boolean get() = this == VideoReferenceAdjustments()
    val needsColorPass: Boolean get() = copy(grain = 0f, vignette = 0f) != VideoReferenceAdjustments()
    fun toPhoto() = PhotoColorAdjustments(exposure = exposure, brightness = brightness,
        contrast = contrast, highlights = highlights, shadows = shadows, saturation = saturation,
        vibrance = vibrance, warmth = warmth, tint = tint, blackPoint = blackPoint,
        sharpness = sharpness, grain = grain, vignette = vignette)

    fun value(tool: VideoReferenceAdjustment): Float = when (tool) {
        VideoReferenceAdjustment.EXPOSURE -> exposure; VideoReferenceAdjustment.BRIGHTNESS -> brightness
        VideoReferenceAdjustment.CONTRAST -> contrast; VideoReferenceAdjustment.HIGHLIGHTS -> highlights
        VideoReferenceAdjustment.SHADOWS -> shadows; VideoReferenceAdjustment.SATURATION -> saturation
        VideoReferenceAdjustment.VIBRANCE -> vibrance; VideoReferenceAdjustment.WARMTH -> warmth
        VideoReferenceAdjustment.TINT -> tint; VideoReferenceAdjustment.BLACK_POINT -> blackPoint
        VideoReferenceAdjustment.SHARPNESS -> sharpness; VideoReferenceAdjustment.GRAIN -> grain
        VideoReferenceAdjustment.VIGNETTE -> vignette
    }
    fun changed(tool: VideoReferenceAdjustment, value: Float): VideoReferenceAdjustments {
        require(value.isFinite())
        val v = value.coerceIn(tool.minimum, 100f)
        return when (tool) {
            VideoReferenceAdjustment.EXPOSURE -> copy(exposure = v); VideoReferenceAdjustment.BRIGHTNESS -> copy(brightness = v)
            VideoReferenceAdjustment.CONTRAST -> copy(contrast = v); VideoReferenceAdjustment.HIGHLIGHTS -> copy(highlights = v)
            VideoReferenceAdjustment.SHADOWS -> copy(shadows = v); VideoReferenceAdjustment.SATURATION -> copy(saturation = v)
            VideoReferenceAdjustment.VIBRANCE -> copy(vibrance = v); VideoReferenceAdjustment.WARMTH -> copy(warmth = v)
            VideoReferenceAdjustment.TINT -> copy(tint = v); VideoReferenceAdjustment.BLACK_POINT -> copy(blackPoint = v)
            VideoReferenceAdjustment.SHARPNESS -> copy(sharpness = v); VideoReferenceAdjustment.GRAIN -> copy(grain = v)
            VideoReferenceAdjustment.VIGNETTE -> copy(vignette = v)
        }
    }
}

enum class VideoReferenceAdjustment(val label: String, val group: String, val svgKey: String,
    val minimum: Float = -100f) {
    EXPOSURE("Выдержка", "Тон", "exposure"), BRIGHTNESS("Светлота", "Тон", "brightness"),
    CONTRAST("Контраст", "Тон", "contrast"), HIGHLIGHTS("Светлые участки", "Тон", "highlights"),
    SHADOWS("Тени", "Тон", "shadows"), SATURATION("Насыщенность", "Цвет", "saturation"),
    VIBRANCE("Красочность", "Цвет", "vibrance"), WARMTH("Теплота", "Цвет", "warmth"),
    TINT("Оттенок", "Цвет", "tint"), BLACK_POINT("Точка черного", "Детализация", "black", 0f),
    SHARPNESS("Резкость", "Детализация", "sharp", 0f), GRAIN("Зернистость", "Детализация", "grain", 0f),
    VIGNETTE("Виньетка", "Детализация", "vignette", 0f),
}
