package com.domenota.medialoader.data.playback.alignment

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import com.domenota.medialoader.data.playback.*
import com.domenota.medialoader.data.tasks.*
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** One explicit track at a time. Durable checkpoint holds hashes and random correlation only.
 * After process death the job is paused; explicit resume revalidates the source and restarts
 * the bounded computation. No raw PCM, text, URI or native session is checkpointed. */
internal object LyricsAlignmentCoordinator {
    data class State(val version:String?=null,val running:Boolean=false,val message:String?=null,val serial:Long=0,
        val completedTextHash:String?=null)
    private data class Request(val version:String,val text:String,val token:TaskCorrelation)
    private val mutable=MutableStateFlow(State())
    val state=mutable.asStateFlow()
    @Volatile var notificationTaskId:String?=null;private set
    private var initialized=false
    private var pending:Request?=null
    private var supplied:AudioBookmarkAdmission?=null
    private var job:Job?=null
    // A recovery keeps the task correlation, but always gets fresh service ownership.
    // Otherwise a fast resume can look like a duplicate START of the finishing run.
    private var executionToken:String?=null
    @Volatile private var cancelled=false
    private lateinit var app:Context
    private fun checkpoint()=AtomicFile(File(app.noBackupFilesDir,"lyrics-alignment-job-v1.json"))
    private fun digest(s:String)=MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it.toInt() and 255)}
    fun messageFor(value:State,current:TrackLyrics?):String? = value.message.takeIf {
        value.completedTextHash==null || (current!=null && current.timed.isNotEmpty() && digest(current.plain)==value.completedTextHash)
    }
    private fun report(r:Request)=TaskReporter.create(TaskOwner.LYRICS,TaskKind.LYRICS,r.token.value)
    private fun details(r:Request,recovered:Boolean=false,outcome:TaskOutcomes?=null,failure:TaskFailure?=null)=
        TaskDetails(correlation=r.token,trigger=if(recovered)TaskTrigger.RECOVERY else TaskTrigger.USER,
            recovered=recovered,outcomes=outcome,failure=failure,operation=TaskOperation.LYRICS_ALIGNMENT)
    private fun save(r:Request) {
        val f=checkpoint();val output=f.startWrite()
        try{output.write(JSONObject().put("schema",1).put("version",r.version).put("text",r.text).put("token",r.token.value).toString().toByteArray());f.finishWrite(output)}
        catch(t:Throwable){f.failWrite(output);throw t}
    }
    @Synchronized fun initialize(context:Context) {
        if(initialized)return;initialized=true;app=context.applicationContext
        pending=runCatching {
            if(checkpoint().baseFile.length() !in 1..2048)return@runCatching null
            val o=JSONObject(checkpoint().readFully().toString(Charsets.UTF_8))
            require(o.getInt("schema")==1)
            Request(o.getString("version"),o.getString("text"),requireNotNull(TaskCorrelation.parse(o.getString("token"))))
                .also{require(listOf(it.version,it.text).all{hash->hash.matches(Regex("[0-9a-f]{64}"))})}
        }.getOrNull()
        pending?.let{r->paused(r)}
    }
    @Synchronized private fun paused(r:Request) {
        if(pending!=r)return
        executionToken=null;notificationTaskId=null
        mutable.value=State(r.version,false,"Синхронизация прервана. Можно повторить из Центра задач",mutable.value.serial+1)
        report(r).report(TaskStatus.PAUSED,details=details(r,true,failure=TaskFailure.INTERRUPTED),
            commands=setOf(TaskCommand.RESUME,TaskCommand.CANCEL),onCommand={command->
                when(command){TaskCommand.RESUME->resume(r);TaskCommand.CANCEL->cancelPaused(r);else->Unit}
            })
    }
    @Synchronized private fun cancelPaused(r:Request) {
        if(pending!=r||mutable.value.running)return
        checkpoint().delete();pending=null;supplied=null
        report(r).report(TaskStatus.CANCELLED,details=details(r,true))
        mutable.value=State(r.version,false,"Синхронизация отменена",mutable.value.serial+1)
    }
    @Synchronized private fun resume(r:Request) {
        if(pending!=r||mutable.value.running)return
        activate(r,null)
    }
    @Synchronized fun start(context:Context,access:AudioBookmarkAdmission,version:String,original:TrackLyrics) {
        initialize(context)
        check(!mutable.value.running){"Уже выполняется синхронизация другого трека"}
        check(original.timed.isEmpty()&&original.plain.isNotBlank())
        access.requireCurrent(app)
        val old=pending
        val hash=digest(original.plain)
        check(old==null || old.version==version&&old.text==hash){"Сначала возобновите или отмените предыдущую синхронизацию в Центре задач"}
        val r=old?:Request(version,hash,TaskCorrelation.create())
        save(r);pending=r;activate(r,access)
    }
    private fun activate(r:Request,access:AudioBookmarkAdmission?) {
        val reporter=report(r)
        supplied=access;cancelled=false;notificationTaskId=reporter.taskId
        val execution=TaskCorrelation.create().value
        executionToken=execution
        mutable.value=State(r.version,true,"Подготовка синхронизации",mutable.value.serial+1)
        reporter.report(TaskStatus.QUEUED,stage=TaskStage.ENQUEUE,details=details(r,access==null),
            commands=setOf(TaskCommand.CANCEL),onCommand={if(it==TaskCommand.CANCEL)cancel(r.token.value)})
        try{LyricsAlignmentService.start(app,execution)}catch(t:Throwable){
            paused(r);throw t
        }
    }
    @Synchronized fun cancel(token:String?=null) {
        if(token!=null&&pending?.token?.value!=token&&executionToken!=token)return
        cancelled=true;job?.cancel()
    }
    @Synchronized fun token()=executionToken
    @Synchronized fun promotionFailed(token:String,error:Exception) {
        val r=pending?.takeIf{executionToken==token} ?: return
        if(job!=null)return
        checkpoint().delete();pending=null;supplied=null;executionToken=null;notificationTaskId=null
        report(r).report(TaskStatus.FAILED,errors=1,details=details(r,failure=TaskFailureClassifier.from(error)))
        mutable.value=State(r.version,false,"Не удалось запустить фоновую синхронизацию",mutable.value.serial+1)
    }
    @Synchronized fun serviceStopped(token:String) {
        val r=pending?.takeIf{executionToken==token} ?: return
        if(job!=null)job?.cancel() else if(mutable.value.running)paused(r)
    }
    suspend fun execute(service:LyricsAlignmentService,token:String) {
        val coroutine=currentCoroutineContext()
        val r=synchronized(this){
            pending?.takeIf{executionToken==token}?.also{check(job==null);job=coroutine[Job]}
        } ?: return
        val reporter=report(r)
        var recovered=supplied==null
        var status=TaskStatus.FAILED;var failure:TaskFailure?=null;var outcomes=TaskOutcomes(failed=1)
        var resultMessage="Не удалось синхронизировать текст"
        try {
            BackgroundMediaWork.setBusy("lyrics-alignment",true)
            fun checkRunning(){coroutine.ensureActive();if(cancelled)throw CancellationException("Cancelled")}
            checkRunning()
            // A sheet lease proves its UI read, not a durable background admission.
            // Every start, including an ordinary supplied request, gets the same proof.
            val catalog=LyricsBatchCatalog.read(app){!cancelled&&coroutine.isActive}
            val access=catalog.entries[r.version] ?: error("Исходный трек изменился или недоступен")
            val store=LocalTrackLyrics(app)
            fun allowed()=!cancelled&&coroutine.isActive&&runCatching{access.requireCurrent(app)}.isSuccess&&
                catalog.allowed(access)
            fun current()=allowed()&&store.versionKey(access,::allowed)==r.version
            check(current())
            val original=store.read(access,::allowed) ?: error("Исходный текст недоступен")
            check(digest(original.plain)==r.text){"Версия текста изменилась"}
            if(original.timed.isNotEmpty()) {
                status=TaskStatus.COMPLETE;outcomes=TaskOutcomes(cached=1);resultMessage="Используется сохранённая синхронизация"
            }else{
                fun progress(phase:String,percent:Int?) {
                    checkRunning();check(current())
                    mutable.value=State(r.version,true,phase,mutable.value.serial)
                    service.show(token,phase,percent)
                    reporter.report(percent=percent,stage=if(phase=="Проверка синхронизации")TaskStage.VERIFYING else TaskStage.PROCESSING,
                        details=details(r,recovered),commands=setOf(TaskCommand.CANCEL),onCommand={if(it==TaskCommand.CANCEL)cancel(r.token.value)})
                }
                progress("Подготовка синхронизации",null)
                val lines=original.plain.lines().filter{it.isNotBlank()}
                val aligned=GigaAmInference.align(app,Uri.parse(requireNotNull(access.ref.item.savedUri)),lines,::allowed,::progress)
                checkRunning();check(current())
                if(aligned==null) {
                    status=TaskStatus.PARTIAL;outcomes=TaskOutcomes(skipped=1)
                    resultMessage="Надёжная синхронизация не подтверждена. Обычный текст сохранён"
                }else{
                    progress("Сохранение синхронизации",null)
                    check(catalog.current()){"Доступ к медиатеке изменился"}
                    val timed=lines.mapIndexed{i,line->LyricLine(aligned.onsetsMs[i],line)}
                    check(store.saveAlignment(access,r.version,original,timed,::current)!=null){"Текст изменился или результат не сохранён"}
                    status=TaskStatus.COMPLETE;outcomes=TaskOutcomes(generated=1);resultMessage="Текст синхронизирован"
                }
            }
        }catch(cancel:CancellationException){
            status=if(cancelled)TaskStatus.CANCELLED else TaskStatus.PAUSED
            outcomes=TaskOutcomes();resultMessage=if(cancelled)"Синхронизация отменена" else "Синхронизация прервана"
        }catch(oom:OutOfMemoryError){failure=TaskFailure.DECODE;resultMessage="Недостаточно памяти для синхронизации. Обычный текст сохранён"}
        catch(error:Exception){failure=TaskFailureClassifier.from(error);resultMessage=error.message?:resultMessage}
        finally {
            withContext(NonCancellable) {
                BackgroundMediaWork.setBusy("lyrics-alignment",false)
                service.finishNotice(token)
                synchronized(this@LyricsAlignmentCoordinator){
                    job=null;supplied=null;executionToken=null
                    if(status.terminal){checkpoint().delete();pending=null;notificationTaskId=null}
                }
                if(status==TaskStatus.PAUSED)paused(r)
                else {
                    reporter.report(status,files=if(status==TaskStatus.COMPLETE||status==TaskStatus.PARTIAL)countProgress(1,1)else null,
                        errors=if(status==TaskStatus.FAILED)1 else 0,details=details(r,recovered,outcomes,failure))
                    mutable.value=State(r.version,false,resultMessage,mutable.value.serial+1,
                        completedTextHash=if(status==TaskStatus.COMPLETE)r.text else null)
                }
                TaskHistoryStore.persistCurrent(TaskCenter.registry)
            }
        }
    }
}
