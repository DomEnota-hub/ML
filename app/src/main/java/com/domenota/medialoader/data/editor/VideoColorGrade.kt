package com.domenota.medialoader.data.editor

/** Filter categories shown in the video workspace, using the same vocabulary for every clip. */
enum class VideoFilterGroup(val label: String) {
    BASIC("Базовые"),
    CINEMA("Кино"),
    COLOR("Цвет"),
}

enum class VideoFilterPreset(
    val label: String,
    val group: VideoFilterGroup,
    val adjustments: VideoColorAdjustments,
) {
    ORIGINAL("Оригинал", VideoFilterGroup.BASIC, VideoColorAdjustments()),
    BRIGHT("Яркий", VideoFilterGroup.BASIC,
        VideoColorAdjustments(brightness = .055f, contrast = .14f, saturation = 16f)),
    SOFT("Мягкий", VideoFilterGroup.BASIC,
        VideoColorAdjustments(brightness = .035f, contrast = -.14f, saturation = -9f)),
    CINEMATIC("Кино", VideoFilterGroup.CINEMA,
        VideoColorAdjustments(contrast = .24f, saturation = -12f, red = 1.05f, blue = .92f)),
    VINTAGE("Винтаж", VideoFilterGroup.CINEMA,
        VideoColorAdjustments(contrast = -.08f, saturation = -24f,
            red = 1.13f, green = 1.035f, blue = .85f)),
    MONO("Ч/Б", VideoFilterGroup.CINEMA,
        VideoColorAdjustments(contrast = .17f, saturation = -100f)),
    WARM("Тёплый", VideoFilterGroup.COLOR,
        VideoColorAdjustments(red = 1.15f, blue = .88f, saturation = 7f)),
    COOL("Холодный", VideoFilterGroup.COLOR,
        VideoColorAdjustments(red = .9f, blue = 1.14f, saturation = 5f)),
}

/**
 * Non-destructive per-clip correction. Ranges match Media3 Effects; all values
 * are finite so invalid NaN/Infinity cannot reach the shader or export.
 */
data class VideoColorAdjustments(
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val hue: Float = 0f,
    val lightness: Float = 0f,
    val red: Float = 1f,
    val green: Float = 1f,
    val blue: Float = 1f,
) {
    init {
        require(brightness.isFinite() && brightness in -1f..1f)
        require(contrast.isFinite() && contrast in -1f..1f)
        require(saturation.isFinite() && saturation in -100f..100f)
        require(hue.isFinite() && hue in -180f..180f)
        require(lightness.isFinite() && lightness in -100f..100f)
        require(listOf(red, green, blue).all { it.isFinite() && it in 0f..2f })
    }
    val isNeutral: Boolean get() = this == VideoColorAdjustments()
}

/** Manual sliders and preset intensity are independent; both are preserved. */
data class VideoColorGrade(
    val preset: VideoFilterPreset = VideoFilterPreset.ORIGINAL,
    val intensity: Float = 1f,
    val manual: VideoColorAdjustments = VideoColorAdjustments(),
    /** Nullable for compatibility with existing legacy video projects/presets. */
    val photoEffect: PhotoEffect? = null,
    val reference: VideoReferenceAdjustments = VideoReferenceAdjustments(),
) {
    init {
        require(intensity.isFinite() && intensity in 0f..1f)
    }

    val isNeutral: Boolean get() =
        (intensity == 0f ||
            if (photoEffect != null) photoEffect == PhotoEffect.NONE
            else preset == VideoFilterPreset.ORIGINAL) && manual.isNeutral && reference.isNeutral

    fun resolved(): VideoColorAdjustments {
        val basis = photoEffect?.let(SharedVideoFilterCatalog::adjustments)
            ?: preset.adjustments
        val t = intensity
        fun offset(filter: Float, manualValue: Float, neutral: Float = 0f,
            min: Float, max: Float): Float =
            (neutral + (filter - neutral) * t + (manualValue - neutral)).coerceIn(min, max)
        return VideoColorAdjustments(
            brightness = offset(basis.brightness, manual.brightness,
                min = -1f, max = 1f),
            contrast = offset(basis.contrast, manual.contrast,
                min = -1f, max = 1f),
            saturation = offset(basis.saturation, manual.saturation,
                min = -100f, max = 100f),
            hue = offset(basis.hue, manual.hue, min = -180f, max = 180f),
            lightness = offset(basis.lightness, manual.lightness,
                min = -100f, max = 100f),
            red = offset(basis.red, manual.red, 1f, 0f, 2f),
            green = offset(basis.green, manual.green, 1f, 0f, 2f),
            blue = offset(basis.blue, manual.blue, 1f, 0f, 2f),
        )
    }
}
