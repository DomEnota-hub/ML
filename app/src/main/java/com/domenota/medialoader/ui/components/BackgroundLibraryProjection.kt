package com.domenota.medialoader.ui.components

import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class ProjectionInputs(private val refs: List<Any?>) {
    override fun equals(other: Any?) = other is ProjectionInputs &&
        refs.size == other.refs.size && refs.indices.all { index ->
            val ref = refs[index]
            ref === other.refs[index] || ((ref is Number || ref is Boolean || ref is String || ref is Enum<*>) && ref == other.refs[index])
        }
    override fun hashCode() = refs.fold(1) { hash, ref -> 31 * hash + (if (ref is Number || ref is Boolean || ref is String || ref is Enum<*>) ref.hashCode() else System.identityHashCode(ref)) }
}
internal data class BackgroundLibraryValue<T>(val value: T, val ready: Boolean, val usable: Boolean = ready)
private data class PublishedLibraryValue<T>(val inputs: ProjectionInputs, val privacy: Set<String>?, val value: T)

/** Retain valid public data during updates, but fail closed synchronously on a privacy change. */
@Composable
internal fun <T> backgroundLibraryProjection(empty: T, privacy: Set<String>?, vararg inputs: Any?,
    build: () -> T): BackgroundLibraryValue<T> {
    val key = ProjectionInputs(inputs.toList() + listOf(privacy))
    var published by remember { mutableStateOf<PublishedLibraryValue<T>?>(null) }
    LaunchedEffect(key) {
        com.domenota.medialoader.core.logging.LibraryWorkCounters.increment(
            com.domenota.medialoader.core.logging.LibraryWork.BACKGROUND_PROJECTION)
        val snapshot = androidx.compose.runtime.snapshots.Snapshot.takeSnapshot()
        val value = try { withContext(Dispatchers.Default) { snapshot.enter { build() } } }
            finally { snapshot.dispose() }
        published = PublishedLibraryValue(key, privacy, value)
    }
    val current = published
    return if (current != null && current.privacy == privacy)
        BackgroundLibraryValue(current.value, current.inputs == key, usable = true)
    else BackgroundLibraryValue(empty, false)
}

/** Like the regular projection, but permits suspending targeted provider lookups.
 * The caller supplies immutable inputs and a semantic access guard. A changed guard
 * immediately hides the previous generation; routine data revisions retain it. */
@Composable
internal fun <T> addressedLibraryProjection(empty: T, guard: Any?, vararg inputs: Any?,
    publishWhen: Boolean = true, build: suspend () -> T): BackgroundLibraryValue<T> {
    val key = ProjectionInputs(inputs.toList())
    var published by remember { mutableStateOf<GuardedProjection<T>?>(null) }
    LaunchedEffect(key, guard, publishWhen) {
        if (guard == null) { published = null; return@LaunchedEffect }
        if (!publishWhen) return@LaunchedEffect
        val value = withContext(Dispatchers.IO) { build() }
        published = GuardedProjection(key, guard, value)
    }
    val current = published
    return if (guard != null && current != null && current.guard == guard)
        BackgroundLibraryValue(current.value, current.inputs == key, usable = true)
    else BackgroundLibraryValue(empty, false)
}
private data class GuardedProjection<T>(val inputs: ProjectionInputs, val guard: Any, val value: T)
