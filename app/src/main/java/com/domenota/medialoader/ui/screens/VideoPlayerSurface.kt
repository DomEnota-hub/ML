package com.domenota.medialoader.ui.screens

import android.content.Context
import android.view.LayoutInflater
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.domenota.medialoader.R

/** Gallery uses Compose controls exclusively. Never inflate a native controller there. */
@OptIn(UnstableApi::class)
internal fun createLocalVideoPlayerView(context: Context, galleryPagingEnabled: Boolean): PlayerView =
    if (galleryPagingEnabled) {
        LayoutInflater.from(context).inflate(R.layout.ml_gallery_player_view, null, false) as PlayerView
    } else {
        PlayerView(context)
    }
