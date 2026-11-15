package com.domenota.medialoader.data.library

import androidx.room.withTransaction
import com.domenota.medialoader.core.database.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onEach
import java.util.UUID

internal data class MediaCollection(val record:MediaCollectionEntity,val members:List<MediaCollectionMemberEntity>)

internal class MediaOrganizationRepository(private val db:HistoryDatabase) {
    private val dao=db.organizationDao()
    val collections=combine(dao.observeCollections(),dao.observeMembers()){collections,members->
        val grouped=members.groupBy{it.collectionId}
        collections.map{MediaCollection(it,grouped[it.id].orEmpty())}
    }.onEach {
        com.domenota.medialoader.core.logging.LibraryWorkCounters.increment(
            com.domenota.medialoader.core.logging.LibraryWork.COLLECTION_EMISSION)
    }.distinctUntilChanged()
    val review=dao.observeReview().onEach {
        com.domenota.medialoader.core.logging.LibraryWorkCounters.increment(
            com.domenota.medialoader.core.logging.LibraryWork.REVIEW_EMISSION)
    }.distinctUntilChanged()
    fun operations(id:String)=dao.observeOperations(id)
    suspend fun create(name:String,refs:List<LibraryMediaRef> = emptyList(),link:String?=null):String = db.withTransaction {
        val id=UUID.randomUUID().toString()
        dao.insertCollection(MediaCollectionEntity(id,MediaOrganizationPolicy.name(name),System.currentTimeMillis()))
        if(refs.isNotEmpty())addMedia(id,refs)
        if(link!=null)addLink(id,link)
        id
    }
    suspend fun rename(id:String,name:String){check(dao.rename(id,MediaOrganizationPolicy.name(name))==1)}
    suspend fun delete(id:String){dao.deleteCollection(id)}
    suspend fun addMedia(collectionId:String,refs:List<LibraryMediaRef>)=db.withTransaction {
        check(dao.collection(collectionId)!=null){"Коллекция удалена"}
        val keys=refs.map{ref->
            val current=ref.appDownloadId?.let{db.downloadDao().byId(it)}
            check(ref.appDownloadId==null||current!=null&&!current.isPrivate&&!current.hidden&&current.savedUri==ref.item.savedUri){"Файл изменился"}
            MediaOrganizationPolicy.keys(ref).also{require(it.isNotEmpty()){"Не подтверждён адрес файла"}}
        }
        val members=dao.members(collectionId)
        var next=(members.maxOfOrNull{it.sortOrder?:it.addedAt}?:-1L)+1L
        val existing=members.map{MediaOrganizationPolicy.decode(it.identityJson)}.toMutableList()
        keys.forEach{identity->
            if(existing.none{old->old.any(identity::contains)}){
                dao.insertMember(MediaCollectionMemberEntity(UUID.randomUUID().toString(),collectionId,
                    MediaOrganizationPolicy.encode(identity),null,System.currentTimeMillis(),next++))
                existing+=identity
            }
        }
    }
    suspend fun addSelection(collectionId:String,refs:List<LibraryMediaRef>,links:List<String>)=db.withTransaction {
        addMedia(collectionId,refs)
        links.forEach{addLink(collectionId,it)}
    }
    suspend fun addLink(collectionId:String,raw:String)=db.withTransaction {
        val link=MediaOrganizationPolicy.link(raw)
        check(dao.collection(collectionId)!=null){"Коллекция удалена"}
        val members=dao.members(collectionId)
        if(members.none{it.link==link})
            dao.insertMember(MediaCollectionMemberEntity(UUID.randomUUID().toString(),collectionId,null,link,System.currentTimeMillis(),
                (members.maxOfOrNull{it.sortOrder?:it.addedAt}?:-1L)+1L))
    }
    /** Reorder only the supplied visible slots. Reject stale snapshots; leave timestamps and identities intact. */
    suspend fun reorder(collectionId:String,expected:List<String>,ordered:List<String>)=db.withTransaction {
        val members=dao.members(collectionId)
        val ids=members.map{it.id}
        check(ordered.size==expected.size && ordered.toSet()==expected.toSet() && expected.distinct()==expected)
        check(ids.filter{it in expected.toSet()}==expected){"Состав коллекции изменился"}
        check(members.filter{it.id in expected.toSet()}.all{it.link==null}){"Ссылки не переставляются"}
        val iterator=ordered.iterator()
        ids.map{if(it in expected.toSet())iterator.next() else it}.forEachIndexed{index,id->
            check(dao.updateMemberOrder(collectionId,id,index.toLong())==1)
        }
    }
    suspend fun remove(collectionId:String,id:String){dao.removeMember(collectionId,id)}
    suspend fun removeSelection(collectionId:String,ids:List<String>)=db.withTransaction {
        check(dao.collection(collectionId)!=null){"Коллекция удалена"}
        ids.distinct().forEach{dao.removeMember(collectionId,it)}
    }
    suspend fun setReviewed(ids:List<String>,reviewed:Boolean=true)=db.withTransaction {
        ids.distinct().chunked(500).forEach{dao.setReviewed(it,reviewed)}
    }
}
