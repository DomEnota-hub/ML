package com.domenota.medialoader.ui.screens.gallery

import android.content.Context
import android.os.SystemClock
import com.domenota.medialoader.core.logging.AppLog
import com.domenota.medialoader.data.tasks.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.data.library.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal enum class GalleryOcrPhase { WAITING_ACCESS, WAITING_CATALOG, PAUSED, WAITING_CHARGE, WAITING_ENERGY, RUNNING, COMPLETE, PARTIAL, ERROR }
internal enum class OcrSearchStatus { COMPLETE, PARTIAL, NOT_READY, BLOCKED }
internal data class OcrSearchResult(val ids:Set<String>,val status:OcrSearchStatus)
internal data class GalleryOcrProgress(val phase: GalleryOcrPhase = GalleryOcrPhase.WAITING_ACCESS,
    val done: Int = 0, val total: Int = 0, val failed: Int = 0, val unverified: Int = 0) {
    val complete get() = phase == GalleryOcrPhase.COMPLETE && done == total && failed == 0 && unverified == 0
    val fraction get() = total.takeIf { it > 0 && done in 0..it }?.let { done.toFloat()/it }
}

/** Single execution with durable checkpoints; future task center observes it. */
internal class GalleryOcrController(context: Context) {
    private val prefs = context.getSharedPreferences("gallery-ocr-settings-v1", Context.MODE_PRIVATE)
    var enabled by mutableStateOf(prefs.getBoolean("enabled", false))
        private set
    var chargeOnly by mutableStateOf(prefs.getBoolean("charge_only", true))
        private set
    var progress by mutableStateOf(GalleryOcrProgress())
        internal set
    internal var revision by mutableLongStateOf(0L)
    internal var address by mutableStateOf<Any?>(null)
    internal var read: suspend (String) -> OcrSearchResult = { OcrSearchResult(emptySet(),OcrSearchStatus.NOT_READY) }
    val searchKey: Any? get() = address?.let { it to revision }
    suspend fun search(query: String): OcrSearchResult = read(query)
    fun toggle() {
        enabled = !enabled
        prefs.edit().putBoolean("enabled", enabled).apply()
        AppLog.d("Settings","OCR_ENABLED value=$enabled")
    }
    fun updateChargeOnly(value: Boolean) {
        if(chargeOnly==value)return
        chargeOnly = value
        prefs.edit().putBoolean("charge_only", value).apply()
        AppLog.d("Settings","OCR_CHARGE_ONLY value=$value")
    }
    fun retry() { revision++; if (!enabled) toggle() else retryNonce++ }
    internal var retryNonce by mutableLongStateOf(0L)
}

@Composable
internal fun rememberGalleryOcr(
    refs: List<LibraryMediaRef>, fingerprints: Map<String,String>, access: Any?,
    blocked: Set<String>, epoch: Long, galleryAccessEpoch: Long, permissionTag:String, ensureCatalog: () -> Unit,
    validate: suspend (List<String>) -> List<LibraryMediaRef>,
): GalleryOcrController {
    val context = LocalContext.current.applicationContext
    val controller = remember(context) { GalleryOcrController(context) }
    val store = remember(context) { GalleryOcrIndex(context) }
    val invalidation by GalleryOcrIndex.invalidations.collectAsState()
    // The incoming catalog includes music and videos. Only photo changes should
    // invalidate an OCR run; real privacy/permission changes always revoke it.
    val photos = remember(refs,fingerprints) { GalleryOcrPolicy.photoInputs(refs,fingerprints) }
    val fence = remember(blocked,epoch,galleryAccessEpoch,permissionTag,invalidation) { Any() }
    val token = remember(photos,access!=null,fence) { if(access!=null) Any() else null }
    var lastFence by remember(controller) { mutableStateOf<Any?>(null) }
    val liveToken by rememberUpdatedState(token)
    val liveValidate by rememberUpdatedState(validate)
    val liveEnsure by rememberUpdatedState(ensureCatalog)
    var catalog by remember(token) { mutableStateOf<GalleryOcrCatalog?>(null) }
    LaunchedEffect(token) {
        if (token != null) {
            delay(600)
            catalog = withContext(Dispatchers.Default) { GalleryOcrCatalog(photos.first,photos.second,blocked) }
        }
    }
    SideEffect {
        controller.address = token
        controller.read = read@{ query ->
            val current = catalog ?: return@read OcrSearchResult(emptySet(),OcrSearchStatus.NOT_READY)
            fun active() = token != null && liveToken === token && GalleryOcrIndex.invalidations.value == invalidation
            if (!active()) return@read OcrSearchResult(emptySet(),OcrSearchStatus.BLOCKED)
            if(!withContext(Dispatchers.IO){store.exists()})return@read OcrSearchResult(emptySet(),OcrSearchStatus.NOT_READY)
            val hits = store.search(current,query,epoch,::active)
            if (!active()) return@read OcrSearchResult(emptySet(),OcrSearchStatus.BLOCKED)
            val result = linkedSetOf<String>()
            for (batch in hits.chunked(GalleryOcrPolicy.BATCH * 4)) {
                if (!active()) return@read OcrSearchResult(emptySet(),OcrSearchStatus.BLOCKED)
                val verified = liveValidate(batch.map { it.ref.key }).associateBy { it.key }
                for (candidate in batch) {
                    if (verified[candidate.ref.key]?.let(candidate::same) == true &&
                        candidate.admitted(context,epoch) && candidate.stillSame(context))
                        result += candidate.ref.item.id
                }
            }
            if(!active()||DuplicatePrivacyGuard.generation(context)!=epoch) return@read OcrSearchResult(emptySet(),OcrSearchStatus.BLOCKED)
            val p=controller.progress
            val complete=p.total==current.candidates.size&&p.done==p.total&&p.failed==0&&p.unverified==0&&
                current.unverified==0&&result.size==hits.size
            OcrSearchResult(result,if(complete)OcrSearchStatus.COMPLETE else OcrSearchStatus.PARTIAL)
        }
    }
    val reporter = remember(controller) { TaskReporter.create(TaskOwner.OCR, TaskKind.OCR) }
    DisposableEffect(reporter) { onDispose { reporter.remove() } }
    LaunchedEffect(controller.enabled,controller.progress) {
        val p=controller.progress
        val idle = !controller.enabled && p.done==0
        val status=if (!controller.enabled) TaskStatus.PAUSED else when(p.phase) {
            GalleryOcrPhase.WAITING_ACCESS, GalleryOcrPhase.WAITING_CATALOG, GalleryOcrPhase.WAITING_CHARGE, GalleryOcrPhase.WAITING_ENERGY -> TaskStatus.WAITING
            GalleryOcrPhase.PAUSED -> TaskStatus.PAUSED
            GalleryOcrPhase.RUNNING -> TaskStatus.RUNNING
            GalleryOcrPhase.COMPLETE -> TaskStatus.COMPLETE
            GalleryOcrPhase.PARTIAL -> TaskStatus.PARTIAL
            GalleryOcrPhase.ERROR -> TaskStatus.FAILED
        }
        val wait=if(status!=TaskStatus.WAITING) null else when(p.phase) {
            GalleryOcrPhase.WAITING_ACCESS -> TaskWait.PERMISSION
            GalleryOcrPhase.WAITING_CATALOG -> TaskWait.CATALOG
            GalleryOcrPhase.WAITING_CHARGE -> TaskWait.CHARGING
            GalleryOcrPhase.WAITING_ENERGY -> TaskWait.ENERGY_SAVER
            else -> null
        }
        val commands=when { controller.enabled && !status.terminal -> setOf(TaskCommand.PAUSE); !controller.enabled && status==TaskStatus.PAUSED -> setOf(TaskCommand.RESUME); status in setOf(TaskStatus.FAILED,TaskStatus.PARTIAL) -> setOf(TaskCommand.RETRY); else -> emptySet() }
        reporter.report(status,files=countProgress(p.done,p.total),errors=p.failed+p.unverified,wait=wait,stage=TaskStage.SCANNING,commands=commands,idle=idle,onCommand={ action ->
            when(action) { TaskCommand.PAUSE -> if(controller.enabled)controller.toggle(); TaskCommand.RESUME -> if(!controller.enabled)controller.toggle(); TaskCommand.RETRY -> if(controller.progress.phase in setOf(GalleryOcrPhase.ERROR,GalleryOcrPhase.PARTIAL))controller.retry(); else -> Unit }
        })
    }
    LaunchedEffect(controller.enabled) { if (controller.enabled) liveEnsure() }
    LaunchedEffect(token,catalog,controller.enabled,controller.retryNonce) {
        val current = catalog
        fun active() = token != null && liveToken === token && GalleryOcrIndex.invalidations.value == invalidation
        if(lastFence !== fence) {
            controller.progress = GalleryOcrProgress(phase=GalleryOcrPhase.WAITING_CATALOG)
            lastFence=fence
        }
        if (current == null || !active()) {
            controller.progress = controller.progress.copy(phase=GalleryOcrPhase.WAITING_CATALOG)
            return@LaunchedEffect
        }
        val total = current.candidates.size
        val failed = linkedSetOf<String>()
        val ready = linkedSetOf<String>()
        val run=AppLog.newOperationId("ocr")
        val started=SystemClock.elapsedRealtime()
        var lastPhase:GalleryOcrPhase?=null
        var lastDone=0
        var lastAt=started
        fun publish(phase: GalleryOcrPhase) {
            controller.progress = GalleryOcrProgress(phase,ready.size,total,failed.size,current.unverified)
            val now=SystemClock.elapsedRealtime()
            if(lastPhase!=phase||ready.size-lastDone>=128||now-lastAt>=15_000L){
                AppLog.d("OCR","OCR_RUN session=$run phase=$phase indexed=${ready.size} total=$total failed=${failed.size} unverified=${current.unverified} ms=${now-started}")
                lastPhase=phase;lastDone=ready.size;lastAt=now
            }
        }
        try {
            if (!controller.enabled && !store.exists()) { publish(GalleryOcrPhase.PAUSED); return@LaunchedEffect }
            ready.addAll(store.reconcile(current,epoch,::active))
            if (!active()) return@LaunchedEffect
            controller.revision++
            if (!controller.enabled) { publish(GalleryOcrPhase.PAUSED); return@LaunchedEffect }
            for (batch in current.candidates.filterNot { it.key in ready }.chunked(GalleryOcrPolicy.BATCH)) {
                currentCoroutineContext().ensureActive()
                if (!active()) return@LaunchedEffect
                while (true) {
                    val reason=TaskHistoryStore.waitReason(context,controller.chargeOnly) ?: break
                    if(!active()) return@LaunchedEffect
                    publish(if(reason==TaskWait.CHARGING)GalleryOcrPhase.WAITING_CHARGE else GalleryOcrPhase.WAITING_ENERGY); delay(2000)
                }
                publish(GalleryOcrPhase.RUNNING)
                val verified = liveValidate(batch.map { it.ref.key }).associateBy { it.key }
                val recognized = mutableListOf<Pair<GalleryOcrCandidate,String>>()
                for (candidate in batch) {
                    currentCoroutineContext().ensureActive()
                    if (!active()) return@LaunchedEffect
                    while (true) {
                        val reason=TaskHistoryStore.waitReason(context,controller.chargeOnly) ?: break
                        if(!active()) return@LaunchedEffect
                        publish(if(reason==TaskWait.CHARGING)GalleryOcrPhase.WAITING_CHARGE else GalleryOcrPhase.WAITING_ENERGY)
                        delay(2000)
                    }
                    publish(GalleryOcrPhase.RUNNING)
                    try {
                        if (verified[candidate.ref.key]?.let(candidate::same) != true ||
                            !candidate.admitted(context,epoch) || !candidate.stillSame(context)) {
                            failed += candidate.key; continue
                        }
                        val body = GalleryOcrReader.read(context,candidate)
                        if (active() && candidate.admitted(context,epoch) && candidate.stillSame(context))
                            recognized += candidate to body else failed += candidate.key
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { failed += candidate.key }
                }
                currentCoroutineContext().ensureActive()
                if (!active()) return@LaunchedEffect
                val final = liveValidate(recognized.map { it.first.ref.key }).associateBy { it.key }
                val safe = recognized.filter { (candidate, _) ->
                    final[candidate.ref.key]?.let(candidate::same) == true && candidate.stillSame(context)
                }
                val written = store.checkpoint(safe,epoch,::active)
                failed += batch.map { it.key }.filterNot(written::contains)
                ready.addAll(written)
                controller.revision++
                publish(GalleryOcrPhase.RUNNING)
                delay(40)
            }
            publish(if (failed.isEmpty() && current.unverified == 0) GalleryOcrPhase.COMPLETE else GalleryOcrPhase.PARTIAL)
        } catch (cancelled: CancellationException) {
            AppLog.d("OCR","OCR_RUN session=$run phase=CANCELLED ms=${SystemClock.elapsedRealtime()-started}")
            throw cancelled
        }
        catch (_: Exception) { if (active()) publish(GalleryOcrPhase.ERROR) }
    }
    return controller
}

/** Search affordance only; the existing controller owns execution. */
@Composable
internal fun GalleryOcrIndicator(controller: GalleryOcrController, onOpen:()->Unit) {
    val p=controller.progress
    if(p.complete)return
    val fraction=p.fraction
    val tint=MaterialTheme.colorScheme.primary
    Box(Modifier.size(48.dp).clickable(role=Role.Button,onClickLabel="Открыть задачу распознавания текста",onClick=onOpen),
        contentAlignment=Alignment.Center) {
        if(fraction!=null) {
            CircularProgressIndicator(progress={fraction},modifier=Modifier.size(32.dp),color=tint,
                trackColor=MaterialTheme.colorScheme.outlineVariant,strokeWidth=2.dp)
            Text((fraction*100).toInt().toString()+"%",style=MaterialTheme.typography.labelSmall,
                color=tint,fontWeight=FontWeight.SemiBold)
        } else Icon(Icons.Rounded.Info,"Индекс текста не готов",tint=tint)
    }
}
@Composable
internal fun GalleryOcrNotice(controller: GalleryOcrController,modifier:Modifier=Modifier) {
    val p=controller.progress
    if(p.complete)return
    val status=when {
        !controller.enabled->"Индексация приостановлена или не запущена."
        p.phase==GalleryOcrPhase.WAITING_CHARGE->"Ожидание зарядки."
        p.phase==GalleryOcrPhase.WAITING_ENERGY->"Ожидание отключения энергосбережения."
        p.phase==GalleryOcrPhase.WAITING_ACCESS->"Ожидание доступа."
        p.phase==GalleryOcrPhase.WAITING_CATALOG->"Подготовка медиатеки."
        p.phase==GalleryOcrPhase.ERROR->"Ошибка индексации."
        p.phase==GalleryOcrPhase.PARTIAL->"Индекс готов частично."
        else->"Индексация текста."
    }
    Text(status+" Поиск по тексту пока доступен не для всех фото.",modifier,
        style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
}
@Composable
internal fun OcrTaskChargeSetting(controller:GalleryOcrController,modifier:Modifier=Modifier) {
    val global by TaskHistoryStore.settings.collectAsState()
    Row(modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,
        horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text("OCR только при зарядке",style=MaterialTheme.typography.bodyMedium)
            Text(if(global.charging)"Задано общим условием Центра задач" else "Условие фоновой индексации",
                style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked=global.charging||controller.chargeOnly,enabled=!global.charging,onCheckedChange=controller::updateChargeOnly)
    }
}
