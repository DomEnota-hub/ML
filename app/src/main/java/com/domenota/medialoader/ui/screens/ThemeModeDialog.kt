package com.domenota.medialoader.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.ui.theme.LightColors
import com.domenota.medialoader.ui.theme.MediaLoaderColors

@Composable
internal fun ThemeModeDialog(selected:String,onDismiss:()->Unit,onSelect:(String)->Unit){
    AlertDialog(onDismissRequest=onDismiss,title={Text("Тема")},text={
        Row(Modifier.fillMaxWidth().selectableGroup(),horizontalArrangement=Arrangement.spacedBy(6.dp)){
            listOf("light" to "Светлая","dark" to "Тёмная","system" to "Системная").forEach{(key,label)->
                val chosen=key==selected
                val shape=RoundedCornerShape(12.dp)
                Column(Modifier.weight(1f).clip(shape)
                    .background(if(chosen)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.45f))
                    .border(if(chosen)2.dp else 1.dp,if(chosen)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,shape)
                    .selectable(selected=chosen,role=Role.RadioButton,onClick={onSelect(key)}).padding(5.dp),
                    horizontalAlignment=Alignment.CenterHorizontally){
                    Row(Modifier.fillMaxWidth().height(76.dp).clip(RoundedCornerShape(8.dp))){
                        if(key!="dark")ThemeMiniature(LightColors,Modifier.weight(1f))
                        if(key!="light")ThemeMiniature(MediaLoaderColors,Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(label,style=MaterialTheme.typography.labelMedium,textAlign=TextAlign.Center)
                    Box(Modifier.height(22.dp),contentAlignment=Alignment.Center){
                        if(chosen)Icon(Icons.Rounded.CheckCircle,"Выбрана",Modifier.size(18.dp),tint=MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    },confirmButton={TextButton(onDismiss){Text("Закрыть")}})
}

@Composable private fun ThemeMiniature(colors:ColorScheme,modifier:Modifier){
    Column(modifier.fillMaxHeight().background(colors.background).padding(4.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(3.dp),verticalAlignment=Alignment.CenterVertically){
            Box(Modifier.size(6.dp).background(colors.primary,RoundedCornerShape(2.dp)))
            Box(Modifier.height(4.dp).weight(1f).background(colors.onBackground.copy(alpha=.65f),RoundedCornerShape(3.dp)))
        }
        repeat(2){
            Column(Modifier.fillMaxWidth().weight(1f).background(colors.surface,RoundedCornerShape(4.dp)).padding(3.dp),verticalArrangement=Arrangement.spacedBy(3.dp)){
                Box(Modifier.fillMaxWidth(.75f).height(3.dp).background(colors.onSurface.copy(alpha=.65f),RoundedCornerShape(2.dp)))
                Box(Modifier.fillMaxWidth(.5f).height(3.dp).background(colors.onSurfaceVariant.copy(alpha=.45f),RoundedCornerShape(2.dp)))
            }
        }
        Box(Modifier.fillMaxWidth(.7f).height(5.dp).background(colors.primary,RoundedCornerShape(3.dp)))
    }
}
