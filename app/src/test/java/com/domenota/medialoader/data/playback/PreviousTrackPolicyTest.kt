package com.domenota.medialoader.data.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class PreviousTrackPolicyTest{
    private fun item(id:String)=MediaItem.Builder().setMediaId(id).setUri("file:///missing-$id.mp3").build()
    @Test fun exactBoundaryAndMissingPredecessor(){
        for(position in listOf(0L,2999L,3000L))assertFalse(PreviousTrackPolicy.restart(position,true))
        assertTrue(PreviousTrackPolicy.restart(3001,true))
        assertTrue(PreviousTrackPolicy.restart(0,false))
    }
    @Test fun bothSessionCommandsUseTheSamePolicyWithoutReplacingQueue(){
        val raw=ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
        val player=MusicPreviousPlayer(raw)
        try{
            raw.setMediaItems(listOf(item("a"),item("b"),item("c")))
            raw.seekTo(1,3001L);raw.pause()
            player.seekToPreviousMediaItem()
            assertEquals("b",raw.currentMediaItem?.mediaId);assertEquals(0L,raw.currentPosition)
            player.seekToPrevious()
            assertEquals("a",raw.currentMediaItem?.mediaId)
            assertFalse(raw.playWhenReady);assertEquals(3,raw.mediaItemCount)
            raw.seekTo(1,3000L)
            player.seekToPreviousMediaItem()
            assertEquals("a",raw.currentMediaItem?.mediaId)
            raw.seekTo(0,2999L)
            player.seekToPrevious()
            assertEquals("a",raw.currentMediaItem?.mediaId);assertEquals(0L,raw.currentPosition)
            raw.setMediaItems(listOf(item("one")));raw.seekTo(4000L)
            player.seekToPrevious()
            assertEquals("one",raw.currentMediaItem?.mediaId);assertEquals(0L,raw.currentPosition)
        }finally{raw.release()}
    }
    @Test fun repeatAndShuffleUseActualPlayerOrder(){
        val raw=ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
        val player=MusicPreviousPlayer(raw)
        try{
            raw.setMediaItems(listOf(item("a"),item("b"),item("c")))
            raw.shuffleModeEnabled=true;raw.repeatMode=Player.REPEAT_MODE_ALL
            raw.seekTo(1,2000L)
            val expected=raw.getMediaItemAt(raw.previousMediaItemIndex).mediaId
            player.seekToPrevious()
            assertEquals(expected,raw.currentMediaItem?.mediaId)
            assertTrue(raw.shuffleModeEnabled);assertEquals(Player.REPEAT_MODE_ALL,raw.repeatMode)
        }finally{raw.release()}
    }
}
