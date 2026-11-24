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
        assertEquals("Выборочно: 1 папка",DuplicateFolderScope.label(setOf(key)))
        assertEquals("Все файлы",DuplicateFolderScope.label(null))
        assertEquals("папки",DuplicateFolderScope.folderWord(2))
        assertEquals("папок",DuplicateFolderScope.folderWord(11))
        assertEquals("папка",DuplicateFolderScope.folderWord(21))
    }

    @Test fun sameNamedPhonePhotoAlbumsStaySeparateByBucketId(){
        val a=photo("one","bucket-1","Camera");val b=photo("two","bucket-2","Camera")
        val options=DuplicateFolderScope.options(listOf(a,b))
        assertEquals(2,options.size)
        assertEquals(setOf("Camera №1", "Camera №2"), options.map { it.name }.toSet())
        assertTrue(options.all { it.fileCount == 1 && it.location == "Расположение не определено" })
        assertNotEquals(DuplicateFolderScope.key(a),DuplicateFolderScope.key(b))
        assertEquals(listOf(b),DuplicateFolderScope.filter(listOf(a,b),
            setOf(DuplicateFolderScope.key(b))){it})
    }

    @Test fun visualBucketPathHintNeverCreatesAnUnverifiedLocation() {
        val a = photo("one", "bucket-a", "DCIM/Camera/")
        val b = photo("two", "bucket-b", "DCIM/Camera/")
        val options = DuplicateFolderScope.options(listOf(a, b))
        assertEquals(2, options.size)
        assertEquals(setOf("Camera №1", "Camera №2"), options.map { it.name }.toSet())
        assertTrue(options.all { it.location == "Расположение не определено" && it.directoryId == null })
        assertEquals(2, DuplicateFolderTree.build(options).size)
        val resolved = DuplicateFolderPathResolver.apply(options,
            mapOf("bucket:bucket-a" to "volume:external_primary|DCIM/Camera"))
        assertEquals("DCIM/Camera", resolved.first { it.key == "bucket:bucket-a" }.location)
        assertNull(resolved.first { it.key == "bucket:bucket-b" }.directoryId)
    }

    @Test fun selectionSummaryCountsSharedVerifiedDirectoryOnceWithoutExpandingKeys() {
        val a = photo("one", "bucket-a", "Shared")
        val b = photo("two", "bucket-b", "Shared")
        val options = DuplicateFolderPathResolver.apply(DuplicateFolderScope.options(listOf(a, b)),
            mapOf("bucket:bucket-a" to "volume:external_primary|Pictures/Shared",
                "bucket:bucket-b" to "volume:external_primary|Pictures/Shared"))
        val both = options.mapTo(mutableSetOf()) { it.key }
        val selected = DuplicateFolderScope.selectionSummary(options, both)
        assertEquals(1, selected.folderCount)
        assertEquals(2, selected.fileCount)
        assertEquals(4000L, selected.sizeBytes)
        assertEquals(1, DuplicateFolderScope.selectionSummary(options, setOf("bucket:bucket-a")).fileCount)
        assertEquals(0, DuplicateFolderScope.selectionSummary(options, setOf("removed-key")).folderCount)
        assertEquals(listOf(a, b), DuplicateFolderScope.filter(listOf(a, b), both) { it })
    }

    @Test fun missingNegativeOrOverflowedSizesRemainUnknownNotZero() {
        assertNull(DuplicateFolderScope.totalKnownBytes(listOf(100L, null)))
        assertNull(DuplicateFolderScope.totalKnownBytes(listOf(-1L)))
        assertNull(DuplicateFolderScope.totalKnownBytes(listOf(Long.MAX_VALUE, 1L)))
        assertEquals(0L, DuplicateFolderScope.totalKnownBytes(emptyList()))
        assertEquals(100L, DuplicateFolderScope.totalKnownBytes(listOf(60L, 40L)))
        val unknown = audio("x", "Music/Unknown").copy(sizeBytes = null)
        assertNull(DuplicateFolderScope.options(listOf(unknown)).single().sizeBytes)
        assertEquals("файл", DuplicateFolderScope.fileWord(1))
        assertEquals("файла", DuplicateFolderScope.fileWord(2))
        assertEquals("файлов", DuplicateFolderScope.fileWord(12))
    }

    @Test fun overviewFileCountExcludesAliasesAndIneligibleMedia() {
        val valid = audio("one","Music/Album")
        val alias = valid.copy(id="alias")
        val hidden = audio("hidden","Music/Album",hidden=true)
        val running = audio("running","Music/Album",state=DownloadState.RUNNING)
        assertEquals(1, DuplicateFolderScope.availableFileCount(listOf(valid, alias, hidden, running)))
        assertEquals(1, DuplicateFolderScope.options(listOf(valid, alias, hidden, running)).single().fileCount)
    }

    @Test fun unknownAlbumDoesNotSilentlyExpandScope(){
        val a=DownloadEntity("a","device","a.jpg",MediaType.PHOTO,
            DownloadState.COMPLETED,"content://media/images/1",1L,1L)
        val b=a.copy(id="b",savedUri="content://media/images/2")
        val options=DuplicateFolderScope.options(listOf(a,b))
        assertEquals(2,options.size)
        assertEquals(2,options.map{it.name}.distinct().size)
        assertTrue(options.all{it.name.startsWith("Папка без пути №")})
        assertNotEquals(DuplicateFolderScope.key(a),DuplicateFolderScope.key(b))
        assertEquals(listOf(a),DuplicateFolderScope.filter(listOf(a,b),
            setOf(DuplicateFolderScope.key(a))){it})
    }
}
