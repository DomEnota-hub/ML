package com.domenota.medialoader.data.library

import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import org.junit.Assert.*
import org.junit.Test

class DuplicateFolderScopeTest {
    private fun audio(id:String, path:String, private:Boolean=false, hidden:Boolean=false,
        state:DownloadState=DownloadState.COMPLETED)=DownloadEntity(
        id=id,providerId="device",originalName="$id.mp3",mediaType=MediaType.AUDIO,
        state=state,savedUri="content://media/audio/$id",sizeBytes=1000,createdAtEpochMillis=1,
        groupId="external_primary:$path",isPrivate=private,hidden=hidden)
    private fun photo(id:String, bucket:String, album:String)=DownloadEntity(
        id=id,providerId="device",originalName="$id.jpg",mediaType=MediaType.PHOTO,
        state=DownloadState.COMPLETED,savedUri="content://media/images/$id",sizeBytes=2000,
        createdAtEpochMillis=1,bulkMediaKey=bucket,groupId=album)

    @Test fun folderCountsUseOnlyEligibleUniqueFileUris(){
        val a=audio("a","Download/Album/");val b=audio("b","Download/Album/")
        val extra=listOf(a,b,a.copy(id="alias"),audio("private","Download/Album/",private=true),
            audio("hidden","Download/Album/",hidden=true),
            audio("running","Download/Album/",state=DownloadState.RUNNING))
        val options=DuplicateFolderScope.options(extra)
        assertEquals(1,options.size)
        assertEquals(2,options.single().fileCount)
        assertEquals("Album",options.single().name)
        assertEquals("Download/Album",options.single().location)
    }

    @Test fun folderChoiceNeverBecomesWholeLibrary(){
        val a=audio("a","Download/2026/");val b=audio("b","Music/2026/")
        val all=listOf(a,b)
        val key=DuplicateFolderScope.key(a)
        assertNotEquals(key,DuplicateFolderScope.key(b))
        assertEquals(2,DuplicateFolderScope.filter(all,null){it}.size)
        assertTrue(DuplicateFolderScope.filter(all,emptySet()){it}.isEmpty())
        assertEquals(listOf(a),DuplicateFolderScope.filter(all,setOf(key)){it})
        assertEquals("Выборочно: 1 папок",DuplicateFolderScope.label(setOf(key)))
        assertEquals("Все файлы",DuplicateFolderScope.label(null))
    }

    @Test fun sameNamedPhonePhotoAlbumsStaySeparateByBucketId(){
        val a=photo("one","bucket-1","Camera");val b=photo("two","bucket-2","Camera")
        val options=DuplicateFolderScope.options(listOf(a,b))
        assertEquals(2,options.size)
        assertTrue(options.all{it.name=="Camera" && it.fileCount==1})
        assertNotEquals(DuplicateFolderScope.key(a),DuplicateFolderScope.key(b))
        assertEquals(listOf(b),DuplicateFolderScope.filter(listOf(a,b),
            setOf(DuplicateFolderScope.key(b))){it})
    }

    @Test fun unknownAlbumDoesNotSilentlyExpandScope(){
        val a=DownloadEntity("a","device","a.jpg",MediaType.PHOTO,
            DownloadState.COMPLETED,"content://media/images/1",1L,1L)
        val b=a.copy(id="b",savedUri="content://media/images/2")
        val options=DuplicateFolderScope.options(listOf(a,b))
        assertEquals(1,options.size)
        assertEquals("Папка не определена",options.single().name)
        assertEquals(listOf(a,b),DuplicateFolderScope.filter(listOf(a,b),setOf(options.single().key)){it})
    }
}
