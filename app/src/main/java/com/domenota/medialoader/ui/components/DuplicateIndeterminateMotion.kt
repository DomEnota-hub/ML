package com.domenota.medialoader.ui.components

import android.animation.ValueAnimator
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Only for an indeterminate task. Never replace real-progress bars or player timelines.
 * A single lightweight translating marker; static content never flashes or resets.
 */
@Composable
private fun markerProgress():Float {
    if(!ValueAnimator.areAnimatorsEnabled())return .5f
    val transition=rememberInfiniteTransition(label="duplicate-indeterminate")
    return transition.animateFloat(
        initialValue=0f,targetValue=1f,
        animationSpec=infiniteRepeatable(tween(2400,easing=FastOutSlowInEasing),RepeatMode.Reverse),
        label="indeterminate-marker"
    ).value
}

@Composable
internal fun MusicSyncProgress(modifier:Modifier=Modifier, accent:Color=MaterialTheme.colorScheme.primary) {
    val progress=markerProgress()
    val base=MaterialTheme.colorScheme.onSurfaceVariant
    // Deliberately stable waveform. The moving scan line, not the bars, shows
    // that the app is looking for corresponding positions in two recordings.
    val peaks=floatArrayOf(.15f,.35f,.8f,.38f,.6f,.2f,.95f,.48f,.7f,.3f,.55f,.9f,
        .22f,.72f,.4f,.86f,.26f,.62f,.33f,.77f,.16f,.45f,.93f,.37f,.68f)
    Canvas(modifier.fillMaxWidth().height(66.dp)
        .testTag("music-sync-motion")
        .semantics{progressBarRangeInfo=ProgressBarRangeInfo.Indeterminate}) {
        val gap=size.width/(peaks.size+1)
        val amplitude=size.height*.15f
        val stroke=2.dp.toPx()
        for(row in 0..1) {
            val center=size.height*(if(row==0).27f else .74f)
            val color=if(row==0)base.copy(alpha=.65f) else accent.copy(alpha=.8f)
            peaks.forEachIndexed{index,height->
                val x=gap*(index+1)
                val bar=height*amplitude
                drawLine(color,Offset(x,center-bar),Offset(x,center+bar),strokeWidth=stroke)
            }
        }
        val x=size.width*(.04f+.92f*progress)
        drawLine(accent,Offset(x,0f),Offset(x,size.height),strokeWidth=1.7.dp.toPx())
        drawCircle(accent,radius=3.6.dp.toPx(),center=Offset(x,4.dp.toPx()))
        drawCircle(accent,radius=3.6.dp.toPx(),center=Offset(x,size.height-4.dp.toPx()))
    }
}

@Composable
internal fun GalleryScanProgress(modifier:Modifier=Modifier,accent:Color=MaterialTheme.colorScheme.primary) {
    val progress=markerProgress()
    val base=MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier.fillMaxWidth().height(68.dp)
        .testTag("gallery-scan-motion")
        .semantics{progressBarRangeInfo=ProgressBarRangeInfo.Indeterminate}) {
        val columns=6
        val rows=2
        val spacing=5.dp.toPx()
        val w=(size.width-(columns-1)*spacing)/columns
        val h=(size.height-(rows-1)*spacing)/rows
        for(row in 0 until rows)for(column in 0 until columns){
            drawRoundRect(base.copy(alpha=if((row+column)%3==0).15f else .10f),
                topLeft=Offset(column*(w+spacing),row*(h+spacing)),
                size=Size(w,h),cornerRadius=CornerRadius(4.dp.toPx()))
        }
        val x=size.width*progress
        drawLine(accent.copy(alpha=.2f),Offset(x-8.dp.toPx(),0f),
            Offset(x-8.dp.toPx(),size.height),strokeWidth=9.dp.toPx())
        drawLine(accent,Offset(x,0f),Offset(x,size.height),strokeWidth=1.6.dp.toPx())
    }
}
