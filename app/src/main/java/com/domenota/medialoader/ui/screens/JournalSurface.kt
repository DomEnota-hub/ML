package com.domenota.medialoader.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Same-window journal: the app drawer can observe its existing edge gesture.
 * Keep the caller's journal state alive while another route is on top. */
@Composable
internal fun JournalSurface(onClose:()->Unit, content:@Composable ColumnScope.()->Unit) {
    BackHandler(onBack=onClose)
    Box(Modifier.fillMaxSize().semantics { paneTitle="Журнал приложения" },contentAlignment=Alignment.Center) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha=.56f))
            .clickable(interactionSource=remember{MutableInteractionSource()},indication=null,onClick=onClose))
        Surface(Modifier.fillMaxWidth(.95f).fillMaxHeight(.88f),shape=MaterialTheme.shapes.extraLarge,
            tonalElevation=if(com.domenota.medialoader.ui.theme.isDarkPalette)0.dp else 6.dp) {
            Column(Modifier.fillMaxSize().clickable(interactionSource=remember{MutableInteractionSource()},indication=null){}
                .padding(16.dp)) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.Article,null,tint=MaterialTheme.colorScheme.primary)
                    Text("Журнал приложения",style=MaterialTheme.typography.titleLarge)
                }
                Spacer(Modifier.height(4.dp))
                content()
            }
        }
    }
}
