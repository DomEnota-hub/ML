package com.domenota.medialoader.data.library

import androidx.room.withTransaction
import com.domenota.medialoader.core.database.*
import kotlinx.coroutines.flow.combine
import java.util.UUID

internal data class MediaCollection(val record:MediaCollectionEntity,val members:List<MediaCollectionMemberEntity>)

internal class MediaOrganizationRepository(private val db:HistoryDatabase) {
    private val dao=db.organizationDao()
    val collections=combine(dao.observeCollections(),dao.observeMembers()){collections,members->
        val grouped=members.groupBy{it.collectionId}
        collections.map{MediaCollection(it,grouped[it.id].orEmpty())}
    }
    val review=dao.observeReview()
    fun operations(id:String)=dao.observeOperations(id)
    suspend fun create(name:String):String {
        val id=UUID.randomUUID().toString()
        dao.insertCollection(MediaCollectionEntity(id,MediaOrganizationPolicy.name(name),System.currentTimeMillis()))
        return id
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
        val existing=dao.members(collectionId).map{MediaOrganizationPolicy.decode(it.identityJson)}.toMutableList()
        keys.forEach{identity->
            if(existing.none{old->old.any(identity::contains)}){
                dao.insertMember(MediaCollectionMemberEntity(UUID.randomUUID().toString(),collectionId,
                    MediaOrganizationPolicy.encode(identity),null,System.currentTimeMillis()))
                existing+=identity
            }
        }
    }
    suspend fun addLink(collectionId:String,raw:String)=db.withTransaction {
        val link=MediaOrganizationPolicy.link(raw)
        check(dao.collection(collectionId)!=null){"Коллекция удалена"}
        if(dao.members(collectionId).none{it.link==link})
            dao.insertMember(MediaCollectionMemberEntity(UUID.randomUUID().toString(),collectionId,null,link,System.currentTimeMillis()))
    }
    suspend fun remove(collectionId:String,id:String){dao.removeMember(collectionId,id)}
    suspend fun setReviewed(ids:List<String>,reviewed:Boolean=true)=db.withTransaction {
        ids.distinct().chunked(500).forEach{dao.setReviewed(it,reviewed)}
    }
}
