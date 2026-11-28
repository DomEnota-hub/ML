package com.domenota.medialoader.data.playback

import android.content.Context
import com.domenota.medialoader.data.tasks.*
import kotlinx.coroutines.*

/** Manual, process-scoped executor. A killed process restores the checkpoint paused;
 * no background network activation or persisted raw metadata. */
internal object LyricsBatchCoordinator {
    private enum class Stop { NONE, PAUSE, CANCEL }
    private enum class Action { NEW, RESUME, RETRY, CANCEL_PAUSED }
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private lateinit var app:Context
    private lateinit var store:LyricsBatchStore
    private val idle=TaskReporter.create(TaskOwner.LYRICS,TaskKind.LYRICS,"lyrics-idle")
    private var initialized=false
    private var job:Job?=null
    private var activeToken:TaskCorrelation?=null
    private var plan:LyricsBatchPlan?=null
    @Volatile private var stop=Stop.NONE

    @Synchronized fun initialize(context:Context) {
        if(initialized)return
        initialized=true;app=context.applicationContext;store=LyricsBatchStore(app)
        scope.launch {
            if(!TaskHistoryStore.awaitReady())return@launch
            val saved=runCatching{store.read()}
            synchronized(this@LyricsBatchCoordinator) {
                plan=saved.getOrNull()
                val restored=plan
                if(saved.isFailure) {
                    val reporter=TaskReporter.create(TaskOwner.LYRICS,TaskKind.LYRICS)
                    reporter.report(TaskStatus.FAILED,errors=1,details=TaskDetails(failure=TaskFailure.IO))
                }
                if(restored!=null&&!restored.status.terminal)showPaused(restored,true)
                else {
                    // If a process stopped after the checkpoint but before history flush,
                    // the same correlation restores the actual terminal row, not a new job.
                    if(restored!=null) {
                        val id=taskId(TaskOwner.LYRICS,restored.token.value)
                        val ended=requireNotNull(restored.finishedAt)
                        val history=TaskCenter.registry.history.value
                        if(ended>history.clearedAt && history.rows.none{it.id==id}){
                            TaskCenter.registry.record(TaskOwner.LYRICS,TaskSnapshot(id,TaskKind.LYRICS,restored.status,
                                files=countProgress(restored.cursor,restored.keys.size),errors=restored.outcomes.failed,
                                updatedAt=ended,details=restored.details(true)))
                        }
                    }
                    showIdle()
                }
            }
        }
    }
    private fun reporter(token:TaskCorrelation)=TaskReporter.create(TaskOwner.LYRICS,TaskKind.LYRICS,token.value)
    private fun showIdle() {
        val commands=buildSet{add(TaskCommand.RESUME);if(plan?.failed?.isNotEmpty()==true)add(TaskCommand.RETRY)}
        idle.report(TaskStatus.PAUSED,idle=true,commands=commands,onCommand={command->
            when(command){TaskCommand.RESUME->launch(Action.NEW);TaskCommand.RETRY->launch(Action.RETRY);else->Unit}
        })
    }
    private fun showPaused(value:LyricsBatchPlan,recovered:Boolean=false,failure:TaskFailure?=null) {
        reporter(value.token).report(TaskStatus.PAUSED,files=countProgress(value.cursor,value.keys.size),
            errors=value.outcomes.failed,details=value.details(recovered).let{if(failure!=null)it.copy(failure=failure)else it},commands=setOf(TaskCommand.RESUME,TaskCommand.CANCEL),onCommand={command->
                when(command){TaskCommand.RESUME->launch(Action.RESUME,value.token);TaskCommand.CANCEL->launch(Action.CANCEL_PAUSED,value.token);else->Unit}
            })
    }
    @Synchronized private fun launch(action:Action,expected:TaskCorrelation?=null) {
        if(job!=null)return
        val previous=plan
        if(expected!=null&&(previous==null||previous.token!=expected||previous.status.terminal))return
        if(action==Action.RETRY&&previous?.failed.isNullOrEmpty())return
        if(action in setOf(Action.RESUME,Action.CANCEL_PAUSED)&&previous==null)return
        if(action in setOf(Action.NEW,Action.RETRY)&&previous!=null&&!previous.status.terminal)return
        stop=Stop.NONE
        val token=if(action==Action.RESUME||action==Action.CANCEL_PAUSED)requireNotNull(previous).token else TaskCorrelation.create()
        val report=reporter(token)
        idle.remove()
        val task=scope.launch(start=CoroutineStart.LAZY){ execute(action,previous,token,report) }
        activeToken=token;job=task;task.start()
    }
    @Synchronized private fun requestStop(token:TaskCorrelation,value:Stop) {
        if(job!=null&&activeToken==token&&stop==Stop.NONE)stop=value
    }
    private suspend fun execute(action:Action,previous:LyricsBatchPlan?,token:TaskCorrelation,report:TaskReporter) {
        var current=if(action==Action.RESUME||action==Action.CANCEL_PAUSED)previous else null
        var finalFailure:TaskFailure?=null
        var recovered=action==Action.RESUME
        fun keepGoing()=stop==Stop.NONE
        fun publish(status:TaskStatus,wait:TaskWait?=null,stage:TaskStage=TaskStage.SCANNING) {
            val value=current
            val commands=if(stop!=Stop.NONE||status.terminal)emptySet() else buildSet{
                add(TaskCommand.CANCEL);if(value!=null)add(TaskCommand.PAUSE)
            }
            report.report(status,files=value?.let{countProgress(it.cursor,it.keys.size)},errors=value?.outcomes?.failed?:0,
                wait=wait,stage=stage,details=value?.details(recovered)?:TaskDetails(correlation=token,trigger=TaskTrigger.USER),
                commands=commands,onCommand={command->when(command){
                    TaskCommand.CANCEL->requestStop(token,Stop.CANCEL);TaskCommand.PAUSE->requestStop(token,Stop.PAUSE);else->Unit
                }})
        }
        fun checkpoint(value:LyricsBatchPlan){store.save(value);current=value;synchronized(this){plan=value}}
        try {
            if(action==Action.CANCEL_PAUSED){
                checkpoint(requireNotNull(current).terminal(TaskStatus.CANCELLED))
            } else {
                publish(TaskStatus.WAITING,TaskWait.CATALOG)
                val catalog=LyricsBatchCatalog.read(app,::keepGoing)
                if(!keepGoing())return
                if(current==null){
                    val keys=if(action==Action.RETRY)requireNotNull(previous).failed else catalog.entries.keys.toList()
                    val inheritedCooldown=previous?.retryNotBefore ?: 0L
                    checkpoint(LyricsBatchPlan.fresh(keys,token).copy(retryNotBefore=inheritedCooldown))
                }
                val local=LocalTrackLyrics(app)
                var consecutiveErrors=0
                while(keepGoing()){
                    currentCoroutineContext().ensureActive()
                    val value=requireNotNull(current)
                    if(value.cursor==value.keys.size){checkpoint(value.finish());break}
                    if(!catalog.current())break
                    val waitMs=value.retryNotBefore-System.currentTimeMillis()
                    if(waitMs>0){
                        publish(TaskStatus.WAITING,TaskWait.NETWORK)
                        delay(waitMs.coerceAtMost(1000L))
                        continue
                    }
                    if(BackgroundMediaWork.isBusy()){
                        publish(TaskStatus.WAITING,TaskWait.RESOURCE);delay(500);continue
                    }
                    val key=value.keys[value.cursor]
                    val access=catalog.entries[key]
                    publish(TaskStatus.RUNNING)
                    var outcome=LyricsBatchOutcome.SKIPPED
                    if(access!=null){
                        fun allowed()=keepGoing()&&catalog.allowed(access)
                        if(!allowed())break
                        val cached=local.read(access,::allowed)
                        if(!allowed())break
                        if(cached!=null)outcome=LyricsBatchOutcome.CACHED
                        else {
                            val target=local.networkTarget(access,allowed=::allowed)
                            if(!allowed())break
                            if(target!=null&&target.version==key){
                                publish(TaskStatus.RUNNING,stage=TaskStage.TRANSFER)
                                val result=LrclibLyricsProvider.shared.search(target.signature){
                                    allowed()&&local.networkCurrent(access,target,::allowed)
                                }
                                if(!allowed()||!catalog.current())break
                                when(result){
                                    is LyricsLookup.RetryLater->{
                                        val now=System.currentTimeMillis()
                                        val delta=result.seconds.coerceIn(1L,Long.MAX_VALUE/2000)*1000+1000L
                                        checkpoint(value.copy(retryNotBefore=maxOf(value.retryNotBefore,now+delta)))
                                        continue
                                    }
                                    is LyricsLookup.Matches->{
                                        val hit=LrclibProtocol.automatic(target.signature,result.hits)
                                        val lyrics=hit?.lyrics
                                        if(hit!=null&&lyrics!=null&&!hit.candidate.instrumental){
                                            publish(TaskStatus.RUNNING,stage=TaskStage.SAVING)
                                            outcome=if(local.saveNetwork(access,target,lyrics,::allowed)!=null)LyricsBatchOutcome.DOWNLOADED
                                                else if(local.read(access,::allowed)!=null)LyricsBatchOutcome.CACHED
                                                else LyricsBatchOutcome.FAILED
                                        }
                                    }
                                    LyricsLookup.Unavailable->outcome=LyricsBatchOutcome.FAILED
                                    LyricsLookup.Revoked->{
                                        if(!catalog.current()||!allowed())break
                                        outcome=LyricsBatchOutcome.SKIPPED
                                    }
                                }
                            }
                        }
                        if(!allowed())break
                    }
                    if(!keepGoing()||!catalog.current())break
                    checkpoint(value.advance(outcome))
                    consecutiveErrors=if(outcome==LyricsBatchOutcome.FAILED)consecutiveErrors+1 else 0
                    // A provider outage must not turn into a thousand rapid failures.
                    if(consecutiveErrors>=3&&requireNotNull(current).cursor<requireNotNull(current).keys.size)break
                }
            }
        } catch(cancelled:CancellationException){throw cancelled}
          catch(error:Exception){if(stop==Stop.NONE)finalFailure=TaskFailureClassifier.from(error)}
        finally {
            withContext(NonCancellable) {
                val saved=current
                if(saved!=null&&stop==Stop.CANCEL&&!saved.status.terminal){
                    runCatching{checkpoint(saved.terminal(TaskStatus.CANCELLED))}
                        .onFailure{finalFailure=TaskFailure.CACHE_WRITE}
                }
                val value=current
                if(value?.status?.terminal==true){
                    report.report(value.status,files=countProgress(value.cursor,value.keys.size),errors=value.outcomes.failed,
                        details=value.details(recovered))
                    TaskHistoryStore.persistCurrent(TaskCenter.registry)
                }else if(value==null){
                    val status=if(stop==Stop.CANCEL)TaskStatus.CANCELLED else TaskStatus.FAILED
                    report.report(status,errors=if(status==TaskStatus.FAILED)1 else 0,
                        details=TaskDetails(correlation=token,trigger=TaskTrigger.USER,failure=finalFailure))
                    TaskHistoryStore.persistCurrent(TaskCenter.registry)
                }else if(finalFailure!=null) {
                    // A failed checkpoint leaves the last durable cursor available;
                    // never claim the uncommitted item has completed.
                    report.report(TaskStatus.PAUSED,files=countProgress(value.cursor,value.keys.size),errors=value.outcomes.failed,
                        details=value.details(recovered).copy(failure=finalFailure))
                }
                synchronized(this@LyricsBatchCoordinator){
                    job=null;activeToken=null;stop=Stop.NONE
                    val live=plan
                    if(live!=null&&!live.status.terminal)showPaused(live,recovered,finalFailure) else showIdle()
                }
            }
        }
    }
}
