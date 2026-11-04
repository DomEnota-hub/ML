package com.domenota.medialoader.ui.components

import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class ProjectionInputs(private val refs: List<Any?>) {
    override fun equals(other: Any?) = other is ProjectionInputs &&
        refs.size == other.refs.size && refs.indices.all { refs[it] === other.refs[it] }
    override fun hashCode() = refs.fold(1) { hash, ref -> 31 * hash + System.identityHashCode(ref) }
}
internal data class BackgroundLibraryValue<T>(val value: T, val ready: Boolean)
private data class PublishedLibraryValue<T>(val inputs: ProjectionInputs, val privacy: Set<String>?, val value: T)

/** Retain valid public data during updates, but fail closed synchronously on a privacy change. */
@Composable
internal fun <T> backgroundLibraryProjection(empty: T, privacy: Set<String>?, vararg inputs: Any?,
    build: () -> T): BackgroundLibraryValue<T> {
    val key = ProjectionInputs(inputs.toList() + listOf(privacy))
    var published by remember { mutableStateOf<PublishedLibraryValue<T>?>(null) }
    LaunchedEffect(key) {
        val snapshot = androidx.compose.runtime.snapshots.Snapshot.takeSnapshot()
        val value = try { withContext(Dispatchers.Default) { snapshot.enter { build() } } }
            finally { snapshot.dispose() }
        published = PublishedLibraryValue(key, privacy, value)
    }
    val current = published
    return if (current != null && current.privacy == privacy)
        BackgroundLibraryValue(current.value, current.inputs == key)
    else BackgroundLibraryValue(empty, false)
}
