package com.domenota.medialoader.data.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import kotlin.math.pow
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Color changes are non-destructive and are applied to the original-resolution output.
 * Preview and export use exactly the same matrix, avoiding a different-looking saved copy.
 */
data class PhotoColorAdjustments(
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val warmth: Float = 0f,
    val exposure: Float = 0f,
    val shadows: Float = 0f,
    val highlights: Float = 0f,
    val blackPoint: Float = 0f,
    val curve: Float = 0f,
    val vibrance: Float = 0f,
    val tint: Float = 0f,
    val sharpness: Float = 0f,
    val clarity: Float = 0f,
    val rgbCurves: PhotoCurves = PhotoCurves(),
    val hsl: Map<HslRange, HslCorrection> = emptyMap(),
    val effect: PhotoEffect = PhotoEffect.NONE,
    val effectStrength: Float = 100f,
    val vignette: Float = 0f,
    val vignetteSize: Float = 60f,
    val glow: Float = 0f,
    val glowSize: Float = 35f,
    val dispersion: Float = 0f,
    val dispersionSize: Float = 35f,
    /** Separate creative tools; preset film grain remains independent. */
    val posterization: Float = 0f,
    val grain: Float = 0f,
    val denoise: Float = 0f,
    val noiseDegree: Float = 0f,
    val noiseHighlights: Float = 25f,
    val noiseSize: Float = 25f,
    val noiseRoughness: Float = 50f,
) {
    init {
        require(listOf(brightness, contrast, saturation, warmth, exposure, shadows, highlights,
            blackPoint, curve, vibrance, tint, sharpness, clarity)
            .all { it.isFinite() && it in -100f..100f })
        require(listOf(effectStrength, vignette, vignetteSize, glow, glowSize,
            dispersion, dispersionSize, posterization, grain, denoise,
            noiseDegree, noiseHighlights, noiseSize, noiseRoughness)
            .all { it.isFinite() && it in 0f..100f })
        require(hsl.size <= HslRange.entries.size)
    }
    fun isNeutral() = this == PhotoColorAdjustments()
}

enum class HslRange(val label: String, val center: Float) {
    RED("Красный", 0f), ORANGE("Оранжевый", 30f), YELLOW("Жёлтый", 60f),
    GREEN("Зелёный", 120f), CYAN("Голубой", 180f), BLUE("Синий", 240f),
    PURPLE("Фиолетовый", 275f), MAGENTA("Пурпурный", 320f)
}
data class HslCorrection(
    val hue: Float = 0f, val saturation: Float = 0f, val lightness: Float = 0f,
) {
    init { require(listOf(hue, saturation, lightness).all { it.isFinite() && it in -100f..100f }) }
    fun isNeutral() = hue == 0f && saturation == 0f && lightness == 0f
}

enum class PhotoEffectCategory(val label: String) {
    ALL("Все"), FAVORITES("Избранное"), POPULAR("Популярное"),
    FILM("Плёнка"), CLASSIC("Классика"),
    PORTRAIT("Портрет"), FOOD("Еда"), CINEMA("Кино"), TRAVEL("Путешествия"),
    NIGHT("Ночь"), TEXTURE("Текстуры")
}

/** Original names/values stay stable for existing editing sessions. No Xiaomi assets. */
enum class PhotoEffect(
    val label: String,
    val category: PhotoEffectCategory,
    val saturationMultiplier: Float = 1f,
    val warmthOffset: Float = 0f,
    val contrastOffset: Float = 0f,
    val fade: Float = 0f,
    val grain: Float = 0f,
) {
    NONE("Оригинал", PhotoEffectCategory.POPULAR),
    MONO("Ч/Б", PhotoEffectCategory.CLASSIC, saturationMultiplier = 0f),
    VIVID("Яркий", PhotoEffectCategory.POPULAR, saturationMultiplier = 1.3f),
    WARM("Тёплый", PhotoEffectCategory.CLASSIC, warmthOffset = 20f),
    COOL("Холодный", PhotoEffectCategory.CLASSIC, warmthOffset = -20f),
    PORTRAIT_SOFT("Мягкий", PhotoEffectCategory.PORTRAIT, contrastOffset = -12f, fade = 8f),
    PORTRAIT_GLOW("Сияние", PhotoEffectCategory.PORTRAIT, warmthOffset = 9f, fade = 12f),
    FOOD_POP("Аппетитный", PhotoEffectCategory.FOOD, saturationMultiplier = 1.16f,
        warmthOffset = 11f, contrastOffset = 9f),
    FOOD_FRESH("Свежий", PhotoEffectCategory.FOOD, saturationMultiplier = 1.11f,
        warmthOffset = -7f),
    FILM_FADED("Плёнка", PhotoEffectCategory.FILM, contrastOffset = -13f,
        fade = 24f, grain = 12f),
    FILM_WARM("Ретро", PhotoEffectCategory.FILM, warmthOffset = 18f,
        contrastOffset = -8f, fade = 22f, grain = 15f),
    CINEMA_COLD("Драма", PhotoEffectCategory.CINEMA, warmthOffset = -20f,
        contrastOffset = 20f, saturationMultiplier = .85f),
    CINEMA_GOLD("Золотой", PhotoEffectCategory.CINEMA, warmthOffset = 22f,
        contrastOffset = 13f),
    TRAVEL_BLUE("Море", PhotoEffectCategory.TRAVEL, warmthOffset = -13f,
        saturationMultiplier = 1.1f),
    TRAVEL_SUN("Закат", PhotoEffectCategory.TRAVEL, warmthOffset = 26f,
        saturationMultiplier = 1.12f),
    NIGHT_CYAN("Неон", PhotoEffectCategory.NIGHT, warmthOffset = -25f,
        contrastOffset = 20f, saturationMultiplier = 1.15f),
    NIGHT_MOODY("Ночной", PhotoEffectCategory.NIGHT, contrastOffset = 17f,
        saturationMultiplier = .78f),
    TEXTURE_GRAIN("Зерно", PhotoEffectCategory.TEXTURE, grain = 38f),
    TEXTURE_VINTAGE("Старая плёнка", PhotoEffectCategory.TEXTURE,
        saturationMultiplier = .8f, fade = 26f, grain = 27f),
    CLASSIC_FADE("Матовый", PhotoEffectCategory.CLASSIC, contrastOffset = -20f, fade = 22f),
    CLASSIC_HIGH_CONTRAST("Контрастный", PhotoEffectCategory.CLASSIC,
        contrastOffset = 43f, saturationMultiplier = .92f),
    CLASSIC_PASTEL("Пастель", PhotoEffectCategory.CLASSIC,
        contrastOffset = -24f, saturationMultiplier = .68f, fade = 12f),
    FILM_DUSTY("Пыльная плёнка", PhotoEffectCategory.FILM,
        warmthOffset = 7f, contrastOffset = -21f, saturationMultiplier = .73f,
        fade = 35f, grain = 22f),
    FILM_NOIR("Нуар", PhotoEffectCategory.FILM,
        saturationMultiplier = 0f, contrastOffset = 37f, fade = 5f, grain = 24f),
    PORTRAIT_ROSE("Розовый свет", PhotoEffectCategory.PORTRAIT,
        warmthOffset = 15f, contrastOffset = -17f, saturationMultiplier = .94f,
        fade = 15f),
    PORTRAIT_CLEAN("Чистый портрет", PhotoEffectCategory.PORTRAIT,
        warmthOffset = -6f, contrastOffset = -7f, saturationMultiplier = 1.07f),
    CINEMA_TEAL("Холодная сталь", PhotoEffectCategory.CINEMA,
        warmthOffset = -33f, contrastOffset = 32f, saturationMultiplier = .70f),
    CINEMA_DESERT("Пустынный кадр", PhotoEffectCategory.CINEMA,
        warmthOffset = 35f, contrastOffset = 23f, saturationMultiplier = .77f, fade = 7f),
    TRAVEL_FOREST("Лесной свет", PhotoEffectCategory.TRAVEL,
        warmthOffset = -12f, contrastOffset = 10f, saturationMultiplier = 1.22f),
    FOOD_GOLD("Карамель", PhotoEffectCategory.FOOD,
        warmthOffset = 26f, contrastOffset = 16f, saturationMultiplier = 1.20f),
    NIGHT_BLUE("Синяя ночь", PhotoEffectCategory.NIGHT,
        warmthOffset = -38f, contrastOffset = 30f, saturationMultiplier = .60f),
    TEXTURE_FADE("Засветка", PhotoEffectCategory.TEXTURE,
        contrastOffset = -31f, saturationMultiplier = .78f, fade = 42f, grain = 9f),
    FILM_AMBER("Янтарь", PhotoEffectCategory.FILM, saturationMultiplier = 1.05f, warmthOffset = 31f, contrastOffset = -6f, fade = 24f, grain = 18f),
    FILM_SILVER("Серебро", PhotoEffectCategory.FILM, saturationMultiplier = 0f, warmthOffset = -12f, contrastOffset = 23f, fade = 8f, grain = 14f),
    FILM_LAVENDER("Лавандовая плёнка", PhotoEffectCategory.FILM, saturationMultiplier = .64f, warmthOffset = -16f, contrastOffset = -16f, fade = 21f, grain = 17f),
    FILM_GOLDEN_HOUR("Золотой час", PhotoEffectCategory.FILM, saturationMultiplier = 1.21f, warmthOffset = 42f, contrastOffset = -8f, fade = 15f, grain = 11f),
    FILM_CROSS("Кросс-процесс", PhotoEffectCategory.FILM, saturationMultiplier = 1.48f, warmthOffset = -15f, contrastOffset = 38f, fade = 5f, grain = 19f),
    FILM_POLAROID("Полароид", PhotoEffectCategory.FILM, saturationMultiplier = .81f, warmthOffset = 13f, contrastOffset = -17f, fade = 30f, grain = 20f),
    CLASSIC_ICE("Лёд", PhotoEffectCategory.CLASSIC, saturationMultiplier = .75f, warmthOffset = -44f, contrastOffset = 14f, fade = 0f, grain = 0f),
    CLASSIC_SEPIA("Сепия", PhotoEffectCategory.CLASSIC, saturationMultiplier = .35f, warmthOffset = 41f, contrastOffset = -7f, fade = 14f, grain = 4f),
    CLASSIC_MIST("Дымка", PhotoEffectCategory.CLASSIC, saturationMultiplier = .61f, warmthOffset = -8f, contrastOffset = -44f, fade = 38f, grain = 0f),
    CLASSIC_BOLD("Графика", PhotoEffectCategory.CLASSIC, saturationMultiplier = 1.65f, warmthOffset = -4f, contrastOffset = 46f, fade = 0f, grain = 0f),
    CLASSIC_CREAM("Сливочный", PhotoEffectCategory.CLASSIC, saturationMultiplier = .83f, warmthOffset = 28f, contrastOffset = -23f, fade = 21f, grain = 0f),
    PORTRAIT_PEARL("Жемчуг", PhotoEffectCategory.PORTRAIT, saturationMultiplier = .68f, warmthOffset = -7f, contrastOffset = -29f, fade = 17f, grain = 0f),
    PORTRAIT_HONEY("Мёд", PhotoEffectCategory.PORTRAIT, saturationMultiplier = 1.1f, warmthOffset = 32f, contrastOffset = -19f, fade = 10f, grain = 0f),
    PORTRAIT_MATTE("Мягкая кожа", PhotoEffectCategory.PORTRAIT, saturationMultiplier = .86f, warmthOffset = 6f, contrastOffset = -32f, fade = 26f, grain = 0f),
    PORTRAIT_BRIGHT("Светлый портрет", PhotoEffectCategory.PORTRAIT, saturationMultiplier = 1.18f, warmthOffset = 11f, contrastOffset = -18f, fade = 21f, grain = 0f),
    FOOD_CITRUS("Цитрус", PhotoEffectCategory.FOOD, saturationMultiplier = 1.47f, warmthOffset = 2f, contrastOffset = 21f, fade = 0f, grain = 0f),
    FOOD_COCOA("Какао", PhotoEffectCategory.FOOD, saturationMultiplier = .73f, warmthOffset = 34f, contrastOffset = 30f, fade = 9f, grain = 3f),
    FOOD_MINT("Мята", PhotoEffectCategory.FOOD, saturationMultiplier = 1.24f, warmthOffset = -31f, contrastOffset = 5f, fade = 4f, grain = 0f),
    CINEMA_NOIR("Кинонуар", PhotoEffectCategory.CINEMA, saturationMultiplier = 0f, warmthOffset = -18f, contrastOffset = 49f, fade = 0f, grain = 21f),
    CINEMA_DUSK("Сумерки", PhotoEffectCategory.CINEMA, saturationMultiplier = .67f, warmthOffset = -28f, contrastOffset = 25f, fade = 19f, grain = 9f),
    CINEMA_FIRE("Пламя", PhotoEffectCategory.CINEMA, saturationMultiplier = 1.57f, warmthOffset = 46f, contrastOffset = 29f, fade = 0f, grain = 8f),
    CINEMA_FADE("Киноплёнка", PhotoEffectCategory.CINEMA, saturationMultiplier = .76f, warmthOffset = -5f, contrastOffset = -12f, fade = 34f, grain = 12f),
    TRAVEL_JUNGLE("Джунгли", PhotoEffectCategory.TRAVEL, saturationMultiplier = 1.4f, warmthOffset = -24f, contrastOffset = 17f, fade = 0f, grain = 0f),
    TRAVEL_SAND("Песок", PhotoEffectCategory.TRAVEL, saturationMultiplier = .91f, warmthOffset = 33f, contrastOffset = -15f, fade = 18f, grain = 0f),
    TRAVEL_GLACIER("Ледник", PhotoEffectCategory.TRAVEL, saturationMultiplier = 1.17f, warmthOffset = -41f, contrastOffset = 8f, fade = 5f, grain = 0f),
    NIGHT_VIOLET("Фиолетовая ночь", PhotoEffectCategory.NIGHT, saturationMultiplier = 1.34f, warmthOffset = -44f, contrastOffset = 35f, fade = 14f, grain = 9f),
    NIGHT_SMOKE("Дымный", PhotoEffectCategory.NIGHT, saturationMultiplier = .34f, warmthOffset = -28f, contrastOffset = 19f, fade = 22f, grain = 12f),
    TEXTURE_COARSE("Шероховатость", PhotoEffectCategory.TEXTURE, saturationMultiplier = .91f, warmthOffset = -5f, contrastOffset = 18f, fade = 9f, grain = 58f),
    TEXTURE_WASHED("Выцветший", PhotoEffectCategory.TEXTURE, saturationMultiplier = .52f, warmthOffset = 19f, contrastOffset = -37f, fade = 49f, grain = 25f),
}
object PhotoEffectCatalog {
    fun forCategory(category: PhotoEffectCategory,
        favorites: Set<PhotoEffect> = emptySet()): List<PhotoEffect> =
        if (category == PhotoEffectCategory.ALL) PhotoEffect.entries
        else if (category == PhotoEffectCategory.FAVORITES)
            PhotoEffect.entries.filter { it in favorites }
        else PhotoEffect.entries.filter { it.category == category ||
            (category == PhotoEffectCategory.POPULAR &&
                it in listOf(PhotoEffect.MONO, PhotoEffect.WARM, PhotoEffect.FILM_FADED,
                    PhotoEffect.FOOD_POP, PhotoEffect.TRAVEL_SUN)) }
}

object PhotoColorMath {
    fun matrix(values: PhotoColorAdjustments): ColorMatrix {
        val strength = values.effectStrength / 100f
        val preset = values.effect
        val presetSaturation = 1f + (preset.saturationMultiplier - 1f) * strength
        val saturation = (presetSaturation * (1f + values.saturation / 100f)).coerceIn(0f, 2.6f)
        val inverse = 1f - saturation
        val contrast = (1f + (values.contrast +
            preset.contrastOffset * strength).coerceIn(-100f, 100f) / 100f * .8f)
            .coerceIn(.2f, 1.8f)
        val exposure = 2f.pow(values.exposure / 100f)
        val warmth = (values.warmth + preset.warmthOffset * strength)
            .coerceIn(-100f, 100f) / 350f
        // Green / magenta tint is separate from the orange / blue warmth axis.
        val tint = values.tint / 100f * .18f
        val redGain = contrast * exposure * (1f + warmth) * (1f + tint * .38f)
        val greenGain = contrast * exposure * (1f - tint)
        val blueGain = contrast * exposure * (1f - warmth) * (1f + tint * .38f)
        val offset = 128f * (1f - contrast) + values.brightness * 1.25f
        val red = floatArrayOf(inverse * .213f + saturation, inverse * .715f, inverse * .072f)
        val green = floatArrayOf(inverse * .213f, inverse * .715f + saturation, inverse * .072f)
        val blue = floatArrayOf(inverse * .213f, inverse * .715f, inverse * .072f + saturation)
        return ColorMatrix(floatArrayOf(
            red[0] * redGain, red[1] * redGain, red[2] * redGain, 0f, offset,
            green[0] * greenGain, green[1] * greenGain, green[2] * greenGain, 0f, offset,
            blue[0] * blueGain, blue[1] * blueGain, blue[2] * blueGain, 0f, offset,
            0f, 0f, 0f, 1f, 0f,
        ))
    }

    fun apply(source: Bitmap, values: PhotoColorAdjustments): Bitmap {
        if (values.isNeutral()) return source
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        Canvas(output).drawBitmap(source, 0f, 0f,
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(matrix(values))
            })
        if (values.shadows != 0f || values.highlights != 0f || values.blackPoint != 0f ||
            values.curve != 0f || values.vibrance != 0f || !values.rgbCurves.isNeutral() ||
            values.hsl.values.any { !it.isNeutral() }) {
            // Bounded row buffers: avoid another width*height int array for 12–48 MP photos.
            val rows = 24
            val colors = IntArray(output.width * rows)
            val prepared = PhotoToneMath.prepare(values)
            val rgbScratch = FloatArray(3)
            var top = 0
            while (top < output.height) {
                val count = min(rows, output.height - top)
                output.getPixels(colors, 0, output.width, 0, top, output.width, count)
                for (i in 0 until count * output.width) {
                    colors[i] = PhotoToneMath.tonePixel(colors[i], prepared, rgbScratch)
                }
                output.setPixels(colors, 0, output.width, 0, top, output.width, count)
                top += count
            }
        }
        var working: Bitmap = output
        try {
            if (values.denoise > 0f) {
                val denoised = PhotoCreativeMath.denoise(working, values.denoise)
                if (denoised !== working) {
                    working.recycle()
                    working = denoised
                }
            }
            if (values.sharpness != 0f || values.clarity != 0f) {
                val detailed = PhotoDetailMath.apply(working, values.sharpness, values.clarity)
                if (detailed !== working) {
                    working.recycle()
                    working = detailed
                }
            }
            val styled = PhotoStylizeMath.apply(working, values)
            if (styled !== working) {
                working.recycle()
                working = styled
            }
            val creative = PhotoCreativeMath.apply(working, values)
            if (creative !== working) {
                working.recycle()
                working = creative
            }
            return working
        } catch (error: Throwable) {
            if (!working.isRecycled) working.recycle()
            throw error
        }
    }
}
