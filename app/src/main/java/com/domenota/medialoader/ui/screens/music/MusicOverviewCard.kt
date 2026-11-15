package com.domenota.medialoader.ui.screens.music

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.ui.theme.musicPlaybackAccent

/** One adaptive card geometry for all seven music overview destinations. */
@Composable
internal fun MusicOverviewCard(title:String, description:String, icon:ImageVector, tag:String,
    onOpen:()->Unit, period:String?=null) {
    val shape=RoundedCornerShape(16.dp)
    Surface(Modifier.fillMaxWidth().padding(horizontal=17.dp,vertical=4.dp).clip(shape)
        .clickable(onClick=onOpen).testTag(tag),shape=shape,color=MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.fillMaxWidth().heightIn(min=64.dp).padding(horizontal=12.dp,vertical=8.dp),
            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(42.dp).background(musicPlaybackAccent.copy(alpha=.13f),RoundedCornerShape(10.dp)),contentAlignment=Alignment.Center) {
                Icon(icon,null,Modifier.size(26.dp),tint=musicPlaybackAccent)
            }
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(1.dp)) {
                Text(title,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold,color=musicPlaybackAccent)
                Text(description,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                period?.let{Text(it,style=MaterialTheme.typography.bodySmall,color=musicPlaybackAccent)}
            }
            Icon(Icons.Rounded.ChevronRight,null,Modifier.size(20.dp),tint=musicPlaybackAccent)
        }
    }
}
