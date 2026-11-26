package com.domenota.medialoader.data.editor

import com.domenota.medialoader.data.editor.AiModelUpdateChecker.Kind

/**
 * Code-reviewed catalog of the currently supported binary contracts.
 * Metadata from upstream is advisory, not permission to install a changed digest.
 * No unpublished remote catalog or model is represented as available.
 */
internal object AiModelCatalog {
    enum class Distribution { UPSTREAM_DOWNLOAD, NETWORK_OR_OFFLINE, LOCAL_ASSEMBLY }
    data class Entry(
        val kind: Kind,
        val title: String,
        val purpose: String,
        val sizeLabel: String?,
        val revision: String,
        val source: String,
        val license: String,
        val distribution: Distribution,
        val files: List<ExpectedModelBlob>,
        val metadataRepository: String? = null,
    )

    val entries = listOf(
        Entry(Kind.LAMA, "LaMa", "Удаление объектов и текста", "208 МБ",
            "sha256-pinned", "https://huggingface.co/sapienkit/LaMa-ONNX", "Apache-2.0",
            Distribution.UPSTREAM_DOWNLOAD, listOf(ExpectedModelBlob("lama_fp32.onnx", LaMaModelStore.SHA256, 208_044_816L)),
            "sapienkit/LaMa-ONNX"),
        Entry(Kind.MOBILE_SAM, "MobileSAM", "Выделение касанием", "44,7 МБ",
            MobileSamModelStore.REVISION, "https://huggingface.co/Acly/MobileSAM", "MIT (export publisher)",
            Distribution.UPSTREAM_DOWNLOAD, listOf(
                ExpectedModelBlob("mobile_sam_image_encoder.onnx", MobileSamModelStore.ENCODER_SHA256, MobileSamModelStore.ENCODER_BYTES),
                ExpectedModelBlob("sam_mask_decoder_single.onnx", MobileSamModelStore.DECODER_SHA256, MobileSamModelStore.DECODER_BYTES)),
            "Acly/MobileSAM"),
        Entry(Kind.PEOPLE, "EfficientDet-Lite0", "Обнаружение людей", "13,8 МБ",
            PersonDetectorModelStore.REVISION, PersonDetectorModelStore.URL_PATH, "Apache-2.0 runtime; model terms review before redistribution",
            Distribution.UPSTREAM_DOWNLOAD, listOf(ExpectedModelBlob("source/model.tflite", PersonDetectorModelStore.SHA256, PersonDetectorModelStore.MODEL_BYTES)),
            "ketiswp/mediapipe-EfficientDet-Lite0-COCO2017-fp32-onnx"),
        Entry(Kind.GLARE, "SRNet", "Удаление бликов", "230 КБ",
            "6f44a130d2b20568f1a1b1159e32a4ec6b46ef19", "https://github.com/zhiyiYo/Specular-Removal", "MIT",
            Distribution.NETWORK_OR_OFFLINE, listOf(ExpectedModelBlob(SpecularSrNetModelStore.MODEL_NAME, SpecularSrNetModelStore.MODEL_SHA256, SpecularSrNetModelStore.MODEL_BYTES))),
        Entry(Kind.REFLECTION, "DExNet + VGG19", "Удаление отражений", "91 МБ",
            "sha256-pinned", "https://github.com/jjhuangcs/DExNet", "Redistribution of combined weights not established",
            Distribution.LOCAL_ASSEMBLY, listOf(ExpectedModelBlob(ReflectionDExModelStore.MODEL_NAME, ReflectionDExModelStore.EXPECTED_SHA256, ReflectionDExModelStore.EXACT_BYTES))),
    )

    fun entry(kind: Kind): Entry = entries.single { it.kind == kind }

    /** An upstream digest change is not a compatible, approved update. */
    fun installAction(entry: Entry, reflectionBundled: Boolean): String = when (entry.distribution) {
        Distribution.UPSTREAM_DOWNLOAD -> "Скачать"
        Distribution.NETWORK_OR_OFFLINE -> "Скачать"
        Distribution.LOCAL_ASSEMBLY -> if (reflectionBundled) "Установить" else "Скачать"
    }

    fun status(entry: Entry, ready: Boolean, checking: Boolean, result: ModelUpdateResult?, reflectionBundled: Boolean): String {
        if (checking) return "Проверка…"
        val base = if (ready) "Установлена" else "Не установлена"
        val suffix = when (entry.distribution) {
            Distribution.NETWORK_OR_OFFLINE -> if (result?.state == ModelUpdateState.REVIEW_REQUIRED) "новая версия на проверке" else "доступна офлайн"
            Distribution.LOCAL_ASSEMBLY -> if (result?.state == ModelUpdateState.REVIEW_REQUIRED) "новая версия на проверке" else if (ready || reflectionBundled) "локальная версия" else "сборка на устройстве · 694 МБ"
            Distribution.UPSTREAM_DOWNLOAD -> when (result?.state) {
                ModelUpdateState.CURRENT -> if (ready) "актуальна" else null
                ModelUpdateState.REVIEW_REQUIRED -> "новая версия на проверке"
                ModelUpdateState.UNAVAILABLE, ModelUpdateState.UNVERIFIABLE -> "версия не проверена"
                else -> null
            }
        }
        return if (suffix == null) base else "$base · $suffix"
    }
}
