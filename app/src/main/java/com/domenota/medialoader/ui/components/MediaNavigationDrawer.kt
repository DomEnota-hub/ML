package com.domenota.medialoader.ui.components

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.R
import com.domenota.medialoader.data.share.InstagramShareSettings
import com.domenota.medialoader.ui.navigation.DrawerEntry
import com.domenota.medialoader.ui.navigation.DrawerGesturePolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Closed until the active root screen has reported its actual local state.
 * Separate owner per route/mode prevents a departing screen's report opening
 * the next screen's drawer before its own selection/dialog state is known. */
internal class DrawerAdmission {
    private var screenReady by mutableStateOf(false)
    private val overlays=mutableStateMapOf<Any,Unit>()
    val admitted:Boolean get()=screenReady&&overlays.isEmpty()
    fun update(allowed:Boolean){screenReady=allowed}
    fun block(owner:Any){overlays[owner]=Unit}
    fun unblock(owner:Any){overlays.remove(owner)}
}
internal val LocalDrawerAdmission=staticCompositionLocalOf<DrawerAdmission?>{null}
@Composable
internal fun ReportDrawerScreen(allowed:Boolean){
    val admission=LocalDrawerAdmission.current ?: return
    SideEffect { admission.update(allowed) }
    DisposableEffect(admission){onDispose{admission.update(false)}}
}

/** Child menus/dialogs can veto the active screen even before window focus
 * changes. Only an actual open overlay registers; no per-row state map churn. */
@Composable
internal fun ReportDrawerOverlay(blocked:Boolean){
    val admission=LocalDrawerAdmission.current ?: return
    if(blocked){
        val owner=remember(admission){Any()}
        DisposableEffect(admission,owner){
            admission.block(owner)
            onDispose{admission.unblock(owner)}
        }
    }
}

/** Single app-level overlay; no blanket horizontal gesture interception. */
@Composable
internal fun MediaNavigationDrawer(
    identity:Any,allowed:Boolean,active:DrawerEntry?,
    available:Set<DrawerEntry> = DrawerEntry.entries.filter{it.availableInStage3}.toSet(),
    journalUnlocked:Boolean=false,
    onSelect:(DrawerEntry)->Unit,
    content:@Composable ()->Unit,
){
    val scope=rememberCoroutineScope()
    var progress by remember(identity){mutableFloatStateOf(0f)}
    var animation by remember(identity){mutableStateOf<Job?>(null)}
    var predictive by remember(identity){mutableStateOf(false)}
    val latestAllowed by rememberUpdatedState(allowed)
    val latestSelect by rememberUpdatedState(onSelect)
    val latestAvailable by rememberUpdatedState(available)
    fun settle(open:Boolean){
        animation?.cancel()
        if(!latestAllowed){progress=0f;return}
        animation=scope.launch {
            animate(progress,if(open)1f else 0f,animationSpec=tween(180)){value,_->progress=value}
        }
    }
    DisposableEffect(identity){onDispose{animation?.cancel()}}
    LaunchedEffect(allowed){
        if(!allowed){animation?.cancel();progress=0f;predictive=false}
    }
    val shown=if(allowed)progress.coerceIn(0f,1f) else 0f
    val visible=shown>0f
    BoxWithConstraints(Modifier.fillMaxSize()){
        val panelWidth=maxWidth*.8f
        val density=LocalDensity.current
        val direction=LocalLayoutDirection.current
        val widthPx=with(density){panelWidth.toPx()}
        val edgePx=with(density){24.dp.toPx()}
        val systemInset=WindowInsets.systemGestures.getLeft(density,direction).toFloat()
        val latestProgress by rememberUpdatedState(shown)
        val latestPredictive by rememberUpdatedState(predictive)
        Box(Modifier.fillMaxSize().testTag("ml-drawer-host")
            .pointerInput(identity,allowed,widthPx,systemInset,edgePx){
                if(!allowed||widthPx<=0f)return@pointerInput
                awaitEachGesture {
                    val down=awaitFirstDown(requireUnconsumed=false,pass=PointerEventPass.Initial)
                    if(!latestAllowed||latestPredictive||
                        !DrawerGesturePolicy.canStart(down.position.x,latestProgress,widthPx,systemInset,edgePx))
                        return@awaitEachGesture
                    val start=latestProgress
                    val opening=start<=0f
                    var dragging=false
                    var completed=false
                    var rejected=false
                    try{
                        while(true){
                            val event=awaitPointerEvent(PointerEventPass.Main)
                            val change=event.changes.firstOrNull{it.id==down.id}?:break
                            if(event.changes.count{it.pressed}>1||!latestAllowed||latestPredictive)break
                            val dx=change.position.x-down.position.x
                            val dy=change.position.y-down.position.y
                            if(!change.pressed){
                                if(dragging){change.consume();completed=true}
                                break
                            }
                            if(!dragging){
                                if(change.isConsumed||DrawerGesturePolicy.rejectDirection(dx,dy,viewConfiguration.touchSlop,opening)){
                                    rejected=true;break
                                }
                                if(!DrawerGesturePolicy.acceptHorizontal(dx,dy,viewConfiguration.touchSlop,opening))continue
                                animation?.cancel()
                                dragging=true
                            }
                            change.consume()
                            progress=DrawerGesturePolicy.fraction(start,dx,widthPx)
                        }
                    }finally{
                        if(dragging&&latestAllowed&&!latestPredictive){
                            settle(if(completed&&!rejected)DrawerGesturePolicy.settleOpen(progress,opening) else !opening)
                        }
                    }
                }
            }
            .then(if(allowed&&!visible)Modifier.semantics {
                customActions=listOf(CustomAccessibilityAction("Открыть меню"){
                    if(latestAllowed){settle(true);true}else false
                })
            }else Modifier)){
            Box(Modifier.fillMaxSize().then(if(visible)Modifier.clearAndSetSemantics{} else Modifier)){content()}
            if(visible){
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha=.56f*shown))
                    .testTag("ml-drawer-scrim")
                    .clickable(interactionSource=remember{MutableInteractionSource()},indication=null,
                        onClickLabel="Закрыть меню"){settle(false)})
                Surface(Modifier.align(AbsoluteAlignment.TopLeft)
                    .width(panelWidth).fillMaxHeight()
                    .absoluteOffset{IntOffset(((shown-1f)*widthPx).roundToInt(),0)}
                    .testTag("ml-drawer-panel")
                    .semantics{paneTitle="Меню"}
                    // Blank panel space must not tap through to the scrim.
                    .pointerInput(Unit){awaitPointerEventScope{while(true)awaitPointerEvent()}},
                    color=MaterialTheme.colorScheme.surfaceContainer,
                    shadowElevation=8.dp){
                    DrawerContents(active,latestAvailable,journalUnlocked,shown>=.999f){entry->
                        if(latestAllowed&&entry in latestAvailable){
                            animation?.cancel();progress=0f;latestSelect(entry)
                        }
                    }
                }
            }
        }
    }
    PredictiveBackHandler(enabled=allowed&&(visible||predictive)){events->
        animation?.cancel()
        predictive=true
        val start=progress
        try{
            events.collect{event->progress=(start*(1f-event.progress)).coerceIn(0f,1f)}
            settle(false)
        }catch(cancelled:CancellationException){
            if(latestAllowed)settle(true)
            throw cancelled
        }finally{predictive=false}
    }
}

@Composable
private fun DrawerContents(active:DrawerEntry?,available:Set<DrawerEntry>,journalUnlocked:Boolean,
    fullyOpen:Boolean,onSelect:(DrawerEntry)->Unit){
    val colors=MaterialTheme.colorScheme
    val context=LocalContext.current
    val automatic=InstagramShareSettings.rememberAutomatic()
    val firstFocus=remember{FocusRequester()}
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
        .verticalScroll(rememberScrollState()).padding(horizontal=12.dp,vertical=12.dp),
        verticalArrangement=Arrangement.spacedBy(2.dp)){
        Row(Modifier.fillMaxWidth().padding(vertical=12.dp,horizontal=4.dp),
            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
            Image(painterResource(R.drawable.ml_launcher_artwork),null,
                Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)))
            Text("MediaLoader",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
                .background(if(automatic)colors.primary else colors.surfaceContainerHigh)
                .testTag("drawer-download-mode")
                .semantics {
                    contentDescription="Режим загрузки"
                    stateDescription=if(automatic)"Автоматический" else "Ручной"
                }.toggleable(value=automatic,role=Role.Switch,onValueChange={InstagramShareSettings.toggle(context)}),
                contentAlignment=Alignment.Center){
                Icon(if(automatic)Icons.Rounded.Bolt else ManualHandOutline,null,
                    Modifier.size(24.dp),tint=if(automatic)colors.onPrimary else colors.onSurface)
            }
        }
        HorizontalDivider(color=colors.outlineVariant)
        for(entry in listOf(DrawerEntry.HOME,DrawerEntry.DOWNLOADS,DrawerEntry.GALLERY,DrawerEntry.MUSIC,DrawerEntry.MUSIC_SEARCH))
            DrawerRow(entry,active==entry,entry in available,onSelect,if(entry==DrawerEntry.HOME)Modifier.focusRequester(firstFocus)else Modifier)
        HorizontalDivider(color=colors.outlineVariant)
        Text("Библиотека",Modifier.padding(start=12.dp,top=10.dp,bottom=2.dp),
            style=MaterialTheme.typography.labelMedium,color=colors.onSurfaceVariant)
        for(entry in listOf(DrawerEntry.FAVORITES,DrawerEntry.PRIVATE,DrawerEntry.ARCHIVE,DrawerEntry.COLLECTIONS,DrawerEntry.TRASH))
            DrawerRow(entry,active==entry,entry in available,onSelect)
        HorizontalDivider(color=colors.outlineVariant)
        for(entry in listOf(DrawerEntry.SETTINGS,DrawerEntry.APPEARANCE)+
            if(journalUnlocked&&DrawerEntry.JOURNAL in available)listOf(DrawerEntry.JOURNAL)else emptyList())
            DrawerRow(entry,active==entry,entry in available,onSelect)
    }
    LaunchedEffect(fullyOpen){if(fullyOpen)firstFocus.requestFocus()}
}

@Composable
private fun DrawerRow(entry:DrawerEntry,selected:Boolean,enabled:Boolean,onSelect:(DrawerEntry)->Unit,
    modifier:Modifier=Modifier){
    val colors=MaterialTheme.colorScheme
    val tint=when{!enabled->colors.onSurface.copy(alpha=.38f);selected->colors.primary;else->colors.onSurface}
    val icon=when(entry){
        DrawerEntry.HOME->Icons.Rounded.Home
        DrawerEntry.DOWNLOADS->Icons.Rounded.Download
        DrawerEntry.GALLERY->Icons.Rounded.PhotoLibrary
        DrawerEntry.MUSIC->Icons.Rounded.MusicNote
        DrawerEntry.MUSIC_SEARCH->Icons.Rounded.Search
        DrawerEntry.FAVORITES->Icons.Rounded.FavoriteBorder
        DrawerEntry.PRIVATE->Icons.Rounded.Lock
        DrawerEntry.ARCHIVE->Icons.Rounded.Archive
        DrawerEntry.COLLECTIONS->Icons.Rounded.GridView
        DrawerEntry.TRASH->Icons.Rounded.DeleteOutline
        DrawerEntry.SETTINGS->Icons.Rounded.Settings
        DrawerEntry.APPEARANCE->Icons.Rounded.Palette
        DrawerEntry.JOURNAL->Icons.Rounded.Description
    }
    Row(modifier.fillMaxWidth().heightIn(min=48.dp).clip(RoundedCornerShape(12.dp))
        .background(if(selected)colors.primary.copy(alpha=.14f)else Color.Transparent)
        .testTag("drawer-entry-${entry.name}")
        .selectable(selected=selected,enabled=enabled,role=Role.Button,onClick={onSelect(entry)})
        .padding(horizontal=12.dp,vertical=10.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)){
        Icon(icon,null,Modifier.size(24.dp),tint=tint)
        Text(entry.label,Modifier.weight(1f),style=MaterialTheme.typography.bodyLarge,color=tint)
    }
}
