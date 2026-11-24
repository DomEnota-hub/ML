package com.domenota.medialoader.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** A keeper outline is presentation only; selection stays in each caller. */
@Composable
internal fun DuplicateRecordCard(
    keeper:Boolean,tag:String,
    accent:Color=MaterialTheme.colorScheme.primary,
    content:@Composable ()->Unit,
){
    Surface(Modifier.fillMaxWidth().padding(vertical=2.dp).testTag(tag),
        shape=RoundedCornerShape(12.dp),
        color=MaterialTheme.colorScheme.surfaceContainer,
        border=if(keeper)BorderStroke(1.dp,accent) else null){
        Box(Modifier.fillMaxWidth().padding(horizontal=6.dp,vertical=6.dp)){content()}
    }
}
