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
private data class PublishedLibraryValue<T>(val inputs: ProjectionInputs, val privacy: Set<String>,
    val accessGuard: Any, val value: T, val retentionGuard: Any? = null)

/** Retain valid public data during updates, but fail closed synchronously on a privacy change. */
@Composable
internal fun <T> backgroundLibraryProjection(empty: T, privacy: Set<String>?, vararg inputs: Any?,
    accessGuard: Any? = privacy, enabled: Boolean = true,
    retentionGuard: Any? = null, revalidateRetained: ((T) -> T)? = null,
    /** Runs on Default, never on the Compose/UI thread. Only for an identical access/privacy epoch. */
    equivalentContent: ((T, T) -> Boolean)? = null,
    kind: com.domenota.medialoader.core.logging.LibraryProjectionKind = com.domenota.medialoader.core.logging.LibraryProjectionKind.OTHER,
    build: () -> T): BackgroundLibraryValue<T> {
    val key = ProjectionInputs(inputs.toList())
    var published by remember { mutableStateOf<PublishedLibraryValue<T>?>(null, referentialEqualityPolicy()) }
    LaunchedEffect(key, privacy, accessGuard, enabled, retentionGuard) {
        if (privacy == null || accessGuard == null || !enabled) {
            if (com.domenota.medialoader.core.logging.AppLog.isDetailedPerformance())
                com.domenota.medialoader.core.logging.AppLog.i("Perf", "READ_MODEL_GUARD kind=$kind " +
                    "reason=${if (privacy == null) "PRIVACY" else if (accessGuard == null) "ACCESS" else "DISABLED"} " +
                    "retained=false")
            published = null
            return@LaunchedEffect
        }
        val previous = published
        if (com.domenota.medialoader.core.logging.AppLog.isDetailedPerformance() && previous != null &&
            (previous.accessGuard != accessGuard || previous.privacy != privacy || previous.retentionGuard != retentionGuard))
            com.domenota.medialoader.core.logging.AppLog.i("Perf", "READ_MODEL_GUARD kind=$kind reason=GUARD_TRANSITION " +
                "samePermissionPrivacyEpoch=${retentionGuard != null && previous.retentionGuard == retentionGuard} " +
                "hasRemovalPolicy=${revalidateRetained != null}")
        val reason = when {
            previous == null -> com.domenota.medialoader.core.logging.LibraryProjectionReason.INITIAL
            previous.privacy != privacy || previous.accessGuard != accessGuard -> com.domenota.medialoader.core.logging.LibraryProjectionReason.ACCESS
            else -> com.domenota.medialoader.core.logging.LibraryProjectionReason.INPUT
        }
        com.domenota.medialoader.core.logging.LibraryWorkCounters.increment(
            com.domenota.medialoader.core.logging.LibraryWork.BACKGROUND_PROJECTION)
        val timer = com.domenota.medialoader.core.logging.LibraryProjectionCounters.start(kind, reason)
        val snapshot = androidx.compose.runtime.snapshots.Snapshot.takeSnapshot()
        try {
            val value = withContext(Dispatchers.Default) {
                val next = snapshot.enter { build() }
                // Duplicate MediaStore/catalog notifications can produce an
                // identical 11k-row catalog. Retain its prepared grid/cache.
                // Never reuse content across an access/privacy change.
                if (previous != null && previous.privacy == privacy &&
                    previous.accessGuard == accessGuard &&
                    previous.retentionGuard == retentionGuard &&
                    equivalentContent?.invoke(previous.value, next) == true
                ) previous.value else next
            }
            published = PublishedLibraryValue(key, privacy, accessGuard, value, retentionGuard)
            timer.complete()
        } finally { snapshot.dispose(); timer.close() }
    }
    val current = published
    val allowed = enabled && privacy != null && accessGuard != null && current != null &&
        current.privacy == privacy && current.accessGuard == accessGuard && current.retentionGuard == retentionGuard
    // A visibility receipt is narrower than a permission/privacy epoch. The caller
    // may synchronously REMOVE denied rows from an authenticated read model while
    // its worker rebuilds. It may never add a new locator through this bridge.
    val bridged = remember(current, key, privacy, accessGuard, enabled, retentionGuard) {
        if ((!allowed || current?.inputs != key) && enabled && privacy != null && accessGuard != null && current != null &&
            retentionGuard != null && current.retentionGuard == retentionGuard && revalidateRetained != null)
            PublishedLibraryValue(current.inputs, privacy, accessGuard,
                revalidateRetained(current.value), retentionGuard)
        else null
    }
    if (bridged != null) {
        // Persist the removal: a later SHOW must wait for a newly verified worker
        // generation instead of reviving a locator from before HIDE.
        if (!allowed) SideEffect { if (published === current) published = bridged }
        return BackgroundLibraryValue(bridged.value, false, usable = true)
    }
    if (allowed && current != null) {
        // Counts reuse of the published generation, not a new worker invocation.
        SideEffect { com.domenota.medialoader.core.logging.LibraryProjectionCounters.reuse(kind) }
        return BackgroundLibraryValue(current.value, current.inputs == key, usable = true)
    }
    return BackgroundLibraryValue(empty, false)
}

/** Like the regular projection, but permits suspending targeted provider lookups.
 * The caller supplies immutable inputs and a semantic access guard. A changed guard
 * immediately hides the previous generation; routine data revisions retain it. */
@Composable
internal fun <T> addressedLibraryProjection(empty: T, guard: Any?, vararg inputs: Any?,
    publishWhen: Boolean = true, retain: (T, T) -> T = { _, next -> next },
    seed: (suspend () -> T?)? = null, serial: Boolean = false,
    revalidateRetained: ((T) -> T)? = null, build: suspend () -> T): BackgroundLibraryValue<T> {
    val key = ProjectionInputs(inputs.toList())
    if (serial) return serialAddressedProjection(empty, guard, key, publishWhen, retain, seed, revalidateRetained, build)
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
        BackgroundLibraryValue(remember(current, key) {
            if (current.inputs != key && revalidateRetained != null) revalidateRetained(current.value) else current.value
        }, current.fresh && current.inputs == key, usable = true)
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
    retain: (T,T)->T, seed: (suspend () -> T?)?, revalidateRetained: ((T) -> T)?,
    build: suspend () -> T): BackgroundLibraryValue<T> {
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
        BackgroundLibraryValue(remember(current,key) {
            if(current.inputs!=key && revalidateRetained!=null) revalidateRetained(current.value) else current.value
        },current.fresh && current.inputs==key,usable=true)
    else BackgroundLibraryValue(empty,false)
}
