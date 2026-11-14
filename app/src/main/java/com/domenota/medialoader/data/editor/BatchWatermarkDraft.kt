package com.domenota.medialoader.data.editor

import org.json.JSONArray
import org.json.JSONObject

/** Saveable text/style/relative transforms only. No URI, preview pixels, or private media metadata. */
internal object BatchWatermarkDraft {
    fun encode(layers: List<TextLayer>): String = JSONArray().apply {
        layers.forEach { layer -> put(JSONObject().apply {
            put("id",layer.id);put("text",layer.text);put("font",layer.fontId)
            put("x",layer.centerX);put("y",layer.centerY);put("size",layer.size);put("angle",layer.rotation)
            put("color",layer.color);put("outlineColor",layer.outlineColor);put("outline",layer.outline)
            put("opacity",layer.opacity);put("bold",layer.bold);put("shadow",layer.shadow)
            put("alignment",layer.alignment.name)
        }) }
    }.toString()

    fun decode(value: String): List<TextLayer> = runCatching {
        val array=JSONArray(value)
        require(array.length()<=8)
        (0 until array.length()).map { index -> array.getJSONObject(index).let { item ->
            TextLayer(id=item.getString("id"),text=item.getString("text"),fontId=item.getString("font"),
                centerX=item.getDouble("x").toFloat(),centerY=item.getDouble("y").toFloat(),
                size=item.getDouble("size").toFloat(),rotation=item.getDouble("angle").toFloat(),
                color=item.getInt("color"),outlineColor=item.getInt("outlineColor"),
                outline=item.getDouble("outline").toFloat(),opacity=item.getDouble("opacity").toFloat(),
                bold=item.getBoolean("bold"),shadow=item.getBoolean("shadow"),
                alignment=TextLayerAlignment.valueOf(item.getString("alignment")))
        } }
    }.getOrDefault(emptyList())
}
