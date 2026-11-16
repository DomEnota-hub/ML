package com.domenota.medialoader.data.catalog.visibility

import androidx.room.Room
import com.domenota.medialoader.core.database.*
import com.domenota.medialoader.core.model.*
import com.domenota.medialoader.data.library.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class VisibilityCollectionLinksTest {
    private fun ref(id: String, uri: String, inode: Int, app: Boolean): LibraryMediaRef {
        val row = DownloadEntity(id,if(app) "instagram" else "device","$id.jpg",MediaType.PHOTO,
            DownloadState.COMPLETED,uri,12,1000L)
        return LibraryMediaRef(id,setOf("uri:$uri","file-stat:1:$inode:12:1"),row,id.takeIf { app })
    }
    @Test fun twoMovesPreserveOrderAndAllowAddressedVerificationOfFirstAfterSecond() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),HistoryDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = db.organizationDao(); dao.insertCollection(MediaCollectionEntity("c","Collection",1))
            val first = ref("a","content://media/external/images/media/1",1,true)
            val second = ref("phone:image:2","content://media/external/images/media/2",2,false)
            val firstNew = ref("a","file:///hidden/a.jpg",3,true)
            val secondNew = ref("phone:image:2","file:///hidden/b.jpg",4,false)
            fun member(id:String, r:LibraryMediaRef, order:Long) = MediaCollectionMemberEntity(id,"c",
                MediaOrganizationPolicy.encode(MediaOrganizationPolicy.keys(r)),null,order,order,CollectionMemberMetadata.encode(r))
            dao.insertMember(member("a-member",first,5)); dao.insertMember(member("b-member",second,6))
            val link = MediaCollectionMemberEntity("link","c",null,"https://example.org",7,7)
            dao.insertMember(link)
            val service = VisibilityCollectionLinks(db)
            val aOld = MediaOrganizationPolicy.keys(first); val bOld = MediaOrganizationPolicy.keys(second)
            val aMembers = service.capture(aOld); val bMembers = service.capture(bOld)
            assertEquals(setOf("a-member"),aMembers); assertEquals(setOf("b-member"),bMembers)
            service.rebind(aOld,firstNew,aMembers); service.rebind(bOld,secondNew,bMembers)
            service.verify(aOld,firstNew,aMembers); service.verify(bOld,secondNew,bMembers)
            // Replay after the Room commit is harmless, including the external identity.
            service.rebind(bOld,secondNew,bMembers); service.verify(bOld,secondNew,bMembers)
            assertEquals(listOf(member("a-member",firstNew,5),member("b-member",secondNew,6),link),dao.members("c"))
            assertNull(db.downloadDao().byId(second.key))
        } finally { db.close() }
    }
    @Test fun changedMembershipCannotBeSilentlyAcknowledged() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),HistoryDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao=db.organizationDao(); dao.insertCollection(MediaCollectionEntity("c","Collection",1))
            val first=ref("a","content://media/external/images/media/1",1,true)
            val target=ref("a","file:///hidden/a.jpg",2,true)
            val keys=MediaOrganizationPolicy.keys(first)
            dao.insertMember(MediaCollectionMemberEntity("m","c",MediaOrganizationPolicy.encode(keys),null,1,1))
            val service=VisibilityCollectionLinks(db); val members=service.capture(keys)
            dao.removeMember("c","m")
            try { service.rebind(keys,target,members); fail("Missing membership") } catch (_:IllegalStateException) { }
            assertTrue(dao.members("c").isEmpty())
        } finally { db.close() }
    }
}
