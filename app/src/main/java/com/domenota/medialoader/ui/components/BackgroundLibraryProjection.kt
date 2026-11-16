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
    publishWhen: Boolean = true, retain: (T, T) -> T = { _, next -> next },
    seed: (suspend () -> T?)? = null, serial: Boolean = false, build: suspend () -> T): BackgroundLibraryValue<T> {
    val key = ProjectionInputs(inputs.toList())
    if (serial) return serialAddressedProjection(empty, guard, key, publishWhen, retain, seed, build)
    var published by remember { mutableStateOf<GuardedProjection<T>?>(null) }
    LaunchedEffect(key, guard, publishWhen) {
        if (guard == null) { published = null; return@LaunchedEffect }
        if (!publishWhen) return@LaunchedEffect
        var previous = published?.takeIf { it.guard == guard }
        if (previous == null && seed != null) {
            val cached = withContext(Dispatchers.IO) { seed() }
            if (cached != null) {
                previous = GuardedProjection(key, guard, cached, fresh = false)
                published = previous
            }
        }
        val retained = previous
        val value = withContext(Dispatchers.IO) {
            val next = build()
            if (retained == null) next else retain(retained.value, next)
        }
        published = GuardedProjection(key, guard, value)
    }
    val current = published
    return if (guard != null && current != null && current.guard == guard)
        BackgroundLibraryValue(current.value, current.fresh && current.inputs == key, usable = true)
    else BackgroundLibraryValue(empty, false)
}
private data class GuardedProjection<T>(val inputs: ProjectionInputs, val guard: Any, val value: T, val fresh: Boolean = true)

private data class AddressedRequest<T>(val key: ProjectionInputs, val seed: (suspend () -> T?)?,
    val build: suspend () -> T, val retain: (T, T) -> T)
private class AddressedMailbox<T> {
    val requests = kotlinx.coroutines.channels.Channel<AddressedRequest<T>>(kotlinx.coroutines.channels.Channel.CONFLATED)
    var submitted: ProjectionInputs? = null
}

/** One active pass per access guard, with at most one newest queued request.
 * Ordinary input changes do not repeatedly cancel a provider. Access changes
 * cancel the whole mailbox; obsolete generations cannot publish. */
@Composable
private fun <T> serialAddressedProjection(empty: T, guard: Any?, key: ProjectionInputs, publishWhen: Boolean,
    retain: (T,T)->T, seed: (suspend () -> T?)?, build: suspend () -> T): BackgroundLibraryValue<T> {
    val mailbox = remember(guard) { AddressedMailbox<T>() }
    val latestKey by rememberUpdatedState(key)
    val latestPublish by rememberUpdatedState(publishWhen)
    var published by remember { mutableStateOf<GuardedProjection<T>?>(null) }
    SideEffect {
        if(guard != null && publishWhen && mailbox.submitted != key) {
            mailbox.submitted = key
            mailbox.requests.trySend(AddressedRequest(key,seed,build,retain))
        } else if(!publishWhen) mailbox.submitted = null
    }
    LaunchedEffect(mailbox) {
        if(guard==null) { published=null; return@LaunchedEffect }
        for(request in mailbox.requests) {
            if(request.key != latestKey || !latestPublish) continue
            var previous=published?.takeIf { it.guard==guard }
            if(previous==null && request.seed!=null) {
                val cached=withContext(Dispatchers.IO) { request.seed.invoke() }
                if(cached!=null && request.key==latestKey && latestPublish) {
                    previous=GuardedProjection(request.key,guard,cached,fresh=false)
                    published=previous
                }
            }
            if(request.key!=latestKey || !latestPublish) continue
            val retained=previous
            val result=withContext(Dispatchers.IO) {
                val next=request.build()
                if(retained==null) next else request.retain(retained.value,next)
            }
            if(request.key==latestKey && latestPublish) published=GuardedProjection(request.key,guard,result)
        }
    }
    val current=published
    return if(guard!=null && current!=null && current.guard==guard)
        BackgroundLibraryValue(current.value,current.fresh && current.inputs==key,usable=true)
    else BackgroundLibraryValue(empty,false)
}
