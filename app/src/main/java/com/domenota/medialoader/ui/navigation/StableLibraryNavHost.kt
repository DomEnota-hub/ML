package com.domenota.medialoader.ui.navigation

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.createGraph
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable

internal class LibraryRouteContent(val route:String, val content:@Composable AnimatedContentScope.(NavBackStackEntry)->Unit)

/** Route topology is immutable across playback/catalog updates. Each destination
 * observes the latest content, not the captured state at graph creation. */
@Composable
internal fun StableLibraryNavHost(navController:NavHostController,startDestination:String,
    modifier:Modifier=Modifier,routes:List<LibraryRouteContent>){
    val latest=rememberUpdatedState(routes)
    val names=routes.map{it.route}
    val graph=remember(navController,startDestination,names){
        navController.createGraph(startDestination=startDestination){
            names.forEachIndexed{index,route->composable(route){entry->latest.value[index].content(this,entry)}}
        }
    }
    NavHost(navController=navController,graph=graph,modifier=modifier)
}
