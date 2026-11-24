package com.domenota.medialoader.data.library

import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import org.junit.Assert.*
import org.junit.Test

class DuplicateFileSearchTest {
    private fun audio(id:String,name:String,folder:String,volume:String="external_primary")=
        DownloadEntity(id,"device",name,MediaType.AUDIO,DownloadState.COMPLETED,
            "content://media/audio/$id",1000L,1L,groupId="$volume:Music/$folder/")

    @Test fun russianFilenameAndTrackMetadataMatchCaseInsensitively(){
        val a=audio("a","Вселенная.mp3","Alpha")
        val b=audio("b","track.mp3","Beta").copy(recognizedTitle="Моя Вселенная")
        val c=audio("c","unknown.mp3","Gamma").copy(sourceAudioTitle="ВСЕЛЕННАЯ")
        val d=audio("d","other.mp3","Alpha")
        val rows=listOf(a,b,c,d)
        val folders=DuplicateFolderScope.options(rows)
        assertEquals(setOf("a","b","c"),DuplicateFileSearch.matches(rows,folders,"  всЕЛЕННАЯ ").map{it.row.id}.toSet())
        assertTrue(DuplicateFileSearch.matches(rows,folders,"Alpha").isEmpty())
        assertTrue(DuplicateFileSearch.matches(rows,folders,"   ").isEmpty())
    }

    @Test fun photoAndVideoAreSearchableAndUnknownPathsAreNotInvented(){
        val a=audio("a","Космос.jpg","Alpha").copy(mediaType=MediaType.PHOTO,
            groupId="Pictures/Space",bulkMediaKey="one")
        val b=a.copy(id="b",originalName="Космос.mp4",mediaType=MediaType.VIDEO,
            savedUri="content://media/video/b",bulkMediaKey="two")
        val rows=listOf(a,b)
        val folders=DuplicateFolderScope.options(rows)
        val hits=DuplicateFileSearch.matches(rows,folders,"космос")
        assertEquals(setOf(MediaType.PHOTO,MediaType.VIDEO),hits.map{it.row.mediaType}.toSet())
        assertTrue(hits.all{it.folder.directoryId==null})
        assertEquals(2,hits.map{it.folder.key}.distinct().size)
    }

    @Test fun aliasesAndUnavailableRowsCannotCreateExtraHits(){
        val a=audio("a","match.mp3","Alpha")
        val rows=listOf(a,a.copy(id="alias"),audio("b","match.mp3","Beta").copy(hidden=true),
            audio("c","match.mp3","Beta").copy(isPrivate=true),
            audio("d","match.mp3","Beta").copy(state=DownloadState.RUNNING),
            audio("e","match.mp3","Beta").copy(savedUri=null))
        val folders=DuplicateFolderScope.options(rows)
        assertEquals(listOf("a"),DuplicateFileSearch.matches(rows,folders,"match").map{it.row.id})
        assertTrue(DuplicateFileSearch.matches(rows,emptyList(),"match").isEmpty())
    }

    @Test fun exactKeyNavigationDistinguishesSameNamesOnDifferentVolumes(){
        val a=audio("a","match.mp3","Shared")
        val b=audio("b","match.mp3","Shared","external_secondary")
        val roots=DuplicateFolderTree.build(DuplicateFolderScope.options(listOf(a,b)))
        val key=DuplicateFolderScope.key(b)
        val path=DuplicateFileSearch.pathToKey(roots,key)
        assertEquals(listOf("Music","Shared"),path.map{it.node.name})
        assertEquals(setOf(key),path.last().node.ownKeys)
        assertEquals(setOf(key),DuplicateFileSearch.selectableKeys(path.first().node,setOf(key)))
        assertTrue(DuplicateFileSearch.pathToKey(roots,"missing").isEmpty())
        assertEquals(emptySet<String>(),DuplicateFileSearch.selectableKeys(roots.first{it.id.startsWith("volume:external_primary")},setOf(key)))
    }

    @Test fun contextParentCannotSelectHiddenSiblingOrNestedScope(){
        val a=audio("a","one.mp3","Alpha")
        val b=audio("b","two.mp3","Beta")
        val c=audio("c","three.mp3","Alpha/Child")
        val rows=listOf(a,b,c)
        val tree=DuplicateFolderTree.build(DuplicateFolderScope.options(rows))
        val exact=setOf(DuplicateFolderScope.key(a))
        val path=DuplicateFileSearch.pathToKey(tree,exact.single())
        assertEquals(exact,DuplicateFileSearch.selectableKeys(path.first().node,exact))
        assertEquals(exact,DuplicateFileSearch.selectableKeys(path.last().node,exact))
        assertEquals(listOf(a),DuplicateFolderScope.filter(rows,exact){it})
    }
}
