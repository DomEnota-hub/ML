package com.domenota.medialoader.data.library

import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.MediaType

/** File matches are a navigation aid, not a scan scope. Only the explicit folder
 * checkbox changes selected keys. Keep the same canonical URI as folder options. */
internal data class DuplicateFileSearchHit(
    val row:DownloadEntity,
    val folder:DuplicateFolderOption,
)

internal object DuplicateFileSearch {
    fun matches(rows:List<DownloadEntity>,folders:List<DuplicateFolderOption>,query:String):List<DuplicateFileSearchHit>{
        val search=query.trim()
        if(search.isEmpty())return emptyList()
        val byKey=folders.associateBy{it.key}
        return rows.asSequence().filter(DuplicateFolderScope::eligible).distinctBy{it.savedUri}
            .filter{row->row.originalName.contains(search,ignoreCase=true)||
                (row.mediaType==MediaType.AUDIO&&listOfNotNull(row.recognizedTitle,row.sourceAudioTitle)
                    .any{it.contains(search,ignoreCase=true)})}
            .mapNotNull{row->byKey[DuplicateFolderScope.key(row)]?.let{DuplicateFileSearchHit(row,it)}}
            .sortedWith{a,b->DuplicateFolderTree.compareNatural(a.row.originalName,b.row.originalName)
                .takeIf{it!=0}?:a.folder.key.compareTo(b.folder.key).takeIf{it!=0}
                ?:a.row.id.compareTo(b.row.id)}
            .toList()
    }

    /** Locate the exact key, not another folder with the same label or path. */
    fun pathToKey(roots:List<DuplicateFolderNode>,key:String):List<DuplicateFolderRow>{
        fun visit(nodes:List<DuplicateFolderNode>,depth:Int):List<DuplicateFolderRow>{
            for(node in nodes){
                if(key !in node.allKeys)continue
                val here=DuplicateFolderRow(node,depth)
                if(key in node.ownKeys)return listOf(here)
                val nested=visit(node.children,depth+1)
                if(nested.isNotEmpty())return listOf(here)+nested
            }
            return emptyList()
        }
        return visit(roots,0)
    }

    /** Context ancestors must never select nonmatching siblings. */
    fun selectableKeys(node:DuplicateFolderNode,matching:Set<String>):Set<String> =
        node.allKeys.intersect(matching)
}
