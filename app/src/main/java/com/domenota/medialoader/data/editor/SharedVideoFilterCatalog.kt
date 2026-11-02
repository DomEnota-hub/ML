package com.domenota.medialoader.data.editor

/**
 * The photo editor owns the authoritative preset catalog. The video editor
 * references the same enum and categories directly — there is no copied list.
 *
 * Color parameters below map photo presets onto Media3's supported GPU knobs.
 * Grain/fade need their respective video rendering passes; this adapter is
 * deliberately not used as proof that the two full pipelines are pixel-identical.
 */
internal object SharedVideoFilterCatalog {
    fun forCategory(
        category: PhotoEffectCategory,
        favorites: Set<PhotoEffect> = emptySet(),
    ): List<PhotoEffect> = PhotoEffectCatalog.forCategory(category, favorites)

    fun adjustments(preset: PhotoEffect): VideoColorAdjustments {
        val warmth = preset.warmthOffset / 100f
        val fade = preset.fade / 100f
        return VideoColorAdjustments(
            brightness = (fade * .06f).coerceIn(-1f, 1f),
            contrast = ((preset.contrastOffset - preset.fade * .35f) / 100f)
                .coerceIn(-1f, 1f),
            saturation = ((preset.saturationMultiplier - 1f) * 100f)
                .coerceIn(-100f, 100f),
            red = (1f + warmth * .36f).coerceIn(0f, 2f),
            green = 1f,
            blue = (1f - warmth * .36f).coerceIn(0f, 2f),
        )
    }
}
