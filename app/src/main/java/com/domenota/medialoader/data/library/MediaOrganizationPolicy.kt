package com.domenota.medialoader.data.library

import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.database.MediaCollectionMemberEntity
import com.domenota.medialoader.core.model.DownloadState
import java.net.URI
import org.json.JSONArray

/** Shared catalog identity; never match by title or size alone. No private rows. */
internal object MediaOrganizationPolicy {
    fun keys(ref:LibraryMediaRef):Set<String> {
        val row=ref.item
        if(row.hidden||row.isPrivate||row.state!=DownloadState.COMPLETED||row.savedUri.isNullOrBlank())return emptySet()
        return buildSet {
            ref.appDownloadId?.takeIf{it.isNotBlank()}?.let{add("app:$it")}
            ref.aliases.filter{it.startsWith("file-stat:")}.forEach(::add)
            val size=row.sizeBytes
            if(size!=null&&size>0&&row.createdAtEpochMillis>0)
                (ref.aliases+"uri:${row.savedUri}").filter{it.startsWith("media:")||it.startsWith("uri:")||it.startsWith("file:")}
                    .forEach{add("locator:$it:$size:${row.createdAtEpochMillis}")}
        }
    }
    fun encode(keys:Set<String>)=JSONArray(keys.sorted()).toString()
    fun decode(value:String?):Set<String> = runCatching {
        val a=JSONArray(value?:return emptySet())
        (0 until a.length()).map{a.getString(it)}.toSet()
    }.getOrDefault(emptySet())
    fun link(raw:String):String {
        val value=raw.trim()
        require(value.length in 1..4096)
        val uri=URI(value)
        require(uri.scheme.equals("https",true)||uri.scheme.equals("http",true))
        require(!uri.host.isNullOrBlank()&&uri.userInfo==null){"Нужна обычная ссылка HTTP(S)"}
        return value
    }
    fun name(raw:String)=raw.trim().also{require(it.length in 1..80){"Название: от 1 до 80 символов"}}
    /** Only one verified candidate; a conflicting locator cannot attach a different file. */
    fun resolve(members:List<MediaCollectionMemberEntity>,refs:List<LibraryMediaRef>):Map<String,LibraryMediaRef> {
        val index=refs.flatMap{ref->keys(ref).map{it to ref}}.groupBy({it.first},{it.second})
        return members.mapNotNull{ member->
            val matches=decode(member.identityJson).flatMap{index[it].orEmpty()}.distinctBy{it.key}
            matches.singleOrNull()?.let{member.id to it}
        }.toMap()
    }
    data class Passport(val row:DownloadEntity,val parent:LibraryMediaRef?,val children:List<LibraryMediaRef>,val parentKnown:Boolean)
    fun passport(ref:LibraryMediaRef,public:List<LibraryMediaRef>):Passport {
        val parentId=ref.item.derivedFromId
        val parent=parentId?.let{id->public.filter{it.key==id||it.appDownloadId==id||id in it.aliases}.singleOrNull()}
        val ids=ref.aliases+ref.key+listOfNotNull(ref.appDownloadId)
        return Passport(ref.item,parent,public.filter{it.key!=ref.key&&it.item.derivedFromId in ids}.distinctBy{it.key},parentId!=null)
    }
}
