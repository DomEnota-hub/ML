package com.domenota.medialoader.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.data.preferences.VideoThumbnailMode
import com.domenota.medialoader.ui.components.NeutralTextButton as TextButton

internal data class SettingsChoice<T>(val value:T,val title:String,val detail:String,val icon:ImageVector)

/** A radio option, with a semantic icon or a schematic preview and only the active accent. */
@Composable
internal fun SettingsChoiceCard(title:String,detail:String,selected:Boolean,icon:ImageVector,onClick:()->Unit,
    preview:(@Composable ()->Unit)?=null) {
    val colors=MaterialTheme.colorScheme
    val shape=RoundedCornerShape(14.dp)
    Row(Modifier.fillMaxWidth().clip(shape)
        .background(if(selected)colors.primaryContainer else colors.surfaceVariant.copy(alpha=.44f))
        .border(if(selected)2.dp else 1.dp,if(selected)colors.primary else colors.outlineVariant,shape)
        .selectable(selected=selected,role=Role.RadioButton,onClick=onClick)
        .heightIn(min=64.dp).padding(horizontal=10.dp,vertical=10.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(9.dp)) {
        if(preview!=null)preview()
        else Box(Modifier.size(40.dp).background(colors.surface.copy(alpha=.8f),RoundedCornerShape(10.dp)),
            contentAlignment=Alignment.Center) {
            Icon(icon,null,Modifier.size(22.dp),tint=if(selected)colors.primary else colors.onSurfaceVariant)
        }
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
            Text(title,style=MaterialTheme.typography.titleSmall,color=colors.onSurface)
            if(detail.isNotBlank())Text(detail,style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant)
        }
        Box(Modifier.size(22.dp),contentAlignment=Alignment.Center) {
            if(selected)Icon(Icons.Rounded.CheckCircle,null,Modifier.size(21.dp),tint=colors.primary)
        }
    }
}

@Composable
internal fun <T> SettingsChoiceDialog(title:String,selected:T,options:List<SettingsChoice<T>>,onDismiss:()->Unit,
    footer:String?=null,preview:(@Composable (T)->Unit)?=null,onSelect:(T)->Unit) {
    AlertDialog(onDismissRequest=onDismiss,title={Text(title)},text={
        Column(Modifier.fillMaxWidth().heightIn(max=440.dp).verticalScroll(rememberScrollState()).selectableGroup(),
            verticalArrangement=Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                val thumbnail: (@Composable ()->Unit)? = if(preview==null)null else {{preview(option.value)}}
                SettingsChoiceCard(option.title,option.detail,selected==option.value,option.icon,{onSelect(option.value)},thumbnail)
            }
            footer?.let{Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        }
    },confirmButton={TextButton(onDismiss){Text("Закрыть")}})
}

/** Neutral illustrative preview, not a fetched image or a claim about the current video. */
@Composable
internal fun ThumbnailChoicePreview(mode:VideoThumbnailMode) {
    val colors=MaterialTheme.colorScheme
    Canvas(Modifier.size(54.dp,42.dp).clip(RoundedCornerShape(7.dp)).background(colors.surface)) {
        val w=size.width;val h=size.height
        val frame=mode==VideoThumbnailMode.FRAME
        val inset=if(frame)w*.09f else 0f
        val top=if(frame)h*.08f else 0f
        val bottom=if(frame)h*.78f else h
        val right=w-inset
        drawRect(colors.tertiaryContainer,Offset(inset,top),Size(right-inset,bottom-top))
        drawCircle(colors.onTertiaryContainer.copy(alpha=.42f),h*.09f,Offset(w*.72f,top+(bottom-top)*.25f))
        val mountains=Path().apply {
            moveTo(inset,bottom);lineTo(inset+(right-inset)*.32f,top+(bottom-top)*.30f)
            lineTo(inset+(right-inset)*.58f,top+(bottom-top)*.69f)
            lineTo(inset+(right-inset)*.76f,top+(bottom-top)*.39f);lineTo(right,bottom);close()
        }
        drawPath(mountains,colors.onTertiaryContainer.copy(alpha=.62f))
        if(frame) {
            val play=Path().apply{moveTo(w*.45f,h*.30f);lineTo(w*.45f,h*.55f);lineTo(w*.61f,h*.425f);close()}
            drawPath(play,colors.surface)
            drawLine(colors.outlineVariant,Offset(w*.12f,h*.90f),Offset(w*.88f,h*.90f),strokeWidth=2.dp.toPx())
            drawLine(colors.onSurfaceVariant,Offset(w*.12f,h*.90f),Offset(w*.50f,h*.90f),strokeWidth=2.dp.toPx())
        }
    }
}
