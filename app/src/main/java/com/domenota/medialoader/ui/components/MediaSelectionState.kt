package com.domenota.medialoader.ui.components

import androidx.compose.runtime.saveable.Saver
import java.io.*
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Large select-all requests must not overflow Android's saved-state Binder transaction. */
internal object MediaSelectionState {
    fun encode(values: List<String>): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(GZIPOutputStream(bytes)).use { output ->
            output.writeInt(values.size)
            values.forEach(output::writeUTF)
        }
        bytes.toByteArray()
    }
    fun decode(bytes: ByteArray): ArrayList<String> = DataInputStream(GZIPInputStream(ByteArrayInputStream(bytes))).use { input ->
        val count = input.readInt()
        require(count in 0..100_000)
        ArrayList<String>(count).apply { repeat(count) { add(input.readUTF()) } }
    }
    val saver = Saver<ArrayList<String>, ByteArray>(save = { encode(it) }, restore = { runCatching { decode(it) }.getOrNull() })
}
