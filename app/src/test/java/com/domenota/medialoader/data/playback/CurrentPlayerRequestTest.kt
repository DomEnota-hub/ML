package com.domenota.medialoader.data.playback

import android.content.Intent
import android.os.Bundle
import androidx.media3.common.MediaItem
import com.domenota.medialoader.MainActivity
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class CurrentPlayerRequestTest {
    private fun row(id:String="a",private:Boolean=false)=DownloadEntity(id,"device","$id.mp3",MediaType.AUDIO,
        DownloadState.COMPLETED,"content://media/external/audio/media/12",123L,100L,isPrivate=private)
    private fun media(row:DownloadEntity)=AudioPlaybackMetadata.item(row,setOf(row.id))
    @Test fun notificationIntentIsExplicitImmutableAndCarriesNoTrackOrQueue() {
        val context=RuntimeEnvironment.getApplication()
        val pending=CurrentPlayerRequest.pendingIntent(context)
        val intent=shadowOf(pending).savedIntent
        assertEquals(MainActivity::class.java.name,intent.component?.className)
        assertEquals(CurrentPlayerRequest.ACTION,intent.action)
        assertNull(intent.data);assertNull(intent.extras)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        assertTrue(pending.isImmutable)
        assertEquals(pending,CurrentPlayerRequest.pendingIntent(context))
    }
    @Test fun coldRequestSurvivesRecreationAndConsumedRequestCannotReplay() {
        val cold=CurrentPlayerRequestState().accept(CurrentPlayerRequest.ACTION)
        assertEquals(1,cold.pending)
        val saved=Bundle();cold.save(saved)
        val recreated=CurrentPlayerRequestState.restore(saved).accept(Intent.ACTION_MAIN)
        assertEquals(cold,recreated)
        val consumed=recreated.consume(cold.pending)
        val after=Bundle();consumed.save(after)
        assertEquals(0,CurrentPlayerRequestState.restore(after).pending)
        assertEquals(consumed,consumed.accept(Intent.ACTION_SEND))
    }
    @Test fun warmNewIntentIsDistinctAndOldNavigationCannotConsumeNewerTap() {
        val first=CurrentPlayerRequestState().accept(CurrentPlayerRequest.ACTION)
        val second=first.accept(CurrentPlayerRequest.ACTION)
        assertEquals(2,second.pending)
        assertEquals(second,second.consume(first.pending))
        assertEquals(0,second.consume(second.pending).pending)
        assertEquals(1,CurrentPlayerRequestState(Int.MAX_VALUE,0).accept(CurrentPlayerRequest.ACTION).pending)
    }
    @Test fun navigationWaitsForActualControllerCatalogAndPrivacyBeforeUsingCurrentItem() {
        val row=row();val current=media(row)
        fun resolve(controller:Boolean=true,catalog:Boolean=true,privacy:Boolean=true,item:MediaItem?=current)=
            CurrentPlayerNavigation.resolve(controller,catalog,privacy,item,listOf(row),emptySet())
        assertEquals(CurrentPlayerDestination.Wait,resolve(controller=false))
        assertEquals(CurrentPlayerDestination.Wait,resolve(catalog=false))
        assertEquals(CurrentPlayerDestination.Wait,resolve(privacy=false))
        assertEquals(CurrentPlayerDestination.Unavailable,resolve(item=null))
        assertEquals(row,(resolve() as CurrentPlayerDestination.Open).row)
        val next=row.copy(id="b",savedUri="content://media/external/audio/media/13")
        val latest=CurrentPlayerNavigation.resolve(true,true,true,media(next),listOf(row,next),emptySet())
        assertEquals(next,(latest as CurrentPlayerDestination.Open).row)
    }
    @Test fun privateBlockedHiddenAndUnknownItemsCannotOpenFromPublicNotification() {
        val row=row()
        fun resolve(item:MediaItem=media(row),rows:List<DownloadEntity> = listOf(row),blocked:Set<String> = emptySet())=
            CurrentPlayerNavigation.resolve(true,true,true,item,rows,blocked)
        assertEquals(CurrentPlayerDestination.Unavailable,resolve(media(this.row(private=true))))
        assertEquals(CurrentPlayerDestination.Unavailable,resolve(blocked=setOf(row.id)))
        assertEquals(CurrentPlayerDestination.Unavailable,resolve(blocked=PublicMediaIdentity.uriKeys(requireNotNull(row.savedUri))))
        assertEquals(CurrentPlayerDestination.Unavailable,resolve(rows=listOf(row.copy(hidden=true))))
        assertEquals(CurrentPlayerDestination.Unavailable,resolve(rows=emptyList()))
        assertEquals(CurrentPlayerDestination.Unavailable,resolve(rows=listOf(row.copy(savedUri="content://media/external/audio/media/99"))))
        val renamed=media(row).buildUpon().setMediaId("old-id").build()
        assertEquals(row,(resolve(renamed) as CurrentPlayerDestination.Open).row)
    }
}
