package com.domenota.medialoader.data.editor

/** Empty input remains empty in the model; the hint exists only in the live preview. */
internal object VideoTextDraft {
    fun preview(layers: List<TextLayer>, selectedId: String?): List<TextLayer> = layers.map {
        if (it.id == selectedId && it.text.isBlank()) it.copy(text = "Ваш текст") else it
    }
}
