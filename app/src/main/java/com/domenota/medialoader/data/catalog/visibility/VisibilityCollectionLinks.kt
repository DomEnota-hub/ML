package com.domenota.medialoader.data.catalog.visibility

import androidx.room.withTransaction
import com.domenota.medialoader.core.database.HistoryDatabase
import com.domenota.medialoader.data.library.CollectionMemberMetadata
import com.domenota.medialoader.data.library.LibraryMediaRef
import com.domenota.medialoader.data.library.MediaOrganizationPolicy

/** Addressed collection repair. The original order, member ID, collection ID,
 * date and links do not change. This never uses name/size alone as identity. */
internal class VisibilityCollectionLinks(private val database: HistoryDatabase) {
    suspend fun capture(oldKeys: Set<String>): Set<String> {
        check(oldKeys.isNotEmpty())
        return database.organizationDao().identityMembers().mapNotNull { member ->
            val keys = MediaOrganizationPolicy.decode(member.identityJson)
            check(keys.isNotEmpty()) { "Повреждена идентичность коллекции" }
            if (keys.any(oldKeys::contains)) member.id else null
        }.toSet()
    }
    suspend fun rebind(oldKeys: Set<String>, target: LibraryMediaRef, expectedMembers: Set<String>) = database.withTransaction {
        val dao = database.organizationDao()
        val newKeys = MediaOrganizationPolicy.keys(target)
        check(oldKeys.isNotEmpty() && newKeys.isNotEmpty())
        val rows = dao.identityMembers()
        val matches = rows.mapNotNull { member ->
            val before = requireNotNull(member.identityJson)
            val keys = MediaOrganizationPolicy.decode(before)
            check(keys.isNotEmpty()) { "Повреждена идентичность коллекции" }
            val replacement = VisibilityReferencePolicy.replacementKeys(keys, oldKeys, newKeys)
            if (replacement != null || member.id in expectedMembers && keys == newKeys) member to newKeys else null
        }
        check(matches.map { it.first.id }.toSet() == expectedMembers) { "Состав коллекций изменился" }
        // Do not silently merge two independent members of the same collection.
        check(matches.groupBy { it.first.collectionId }.values.all { it.size == 1 })
        for ((member, keys) in matches) {
            check(rows.none { other -> other.collectionId == member.collectionId && other.id != member.id &&
                MediaOrganizationPolicy.decode(other.identityJson).any(newKeys::contains) }) { "Новый адрес уже есть в коллекции" }
            val replacement = MediaOrganizationPolicy.encode(keys)
            val metadata = CollectionMemberMetadata.encode(target)
            if (member.identityJson != replacement || member.metadataJson != metadata) {
                check(dao.rebindMemberIdentity(member.id,member.collectionId,requireNotNull(member.identityJson),replacement,metadata) == 1)
            }
        }
    }

    /** A later member of the folder can alter the same collection. Verify only
     * this file's addresses rather than requiring an unchanged whole document. */
    suspend fun verify(oldKeys: Set<String>, target: LibraryMediaRef, expectedMembers: Set<String>) {
        val newKeys = MediaOrganizationPolicy.keys(target)
        check(newKeys.isNotEmpty())
        val stale = oldKeys - newKeys
        val found = mutableSetOf<String>()
        for (member in database.organizationDao().identityMembers()) {
            val keys = MediaOrganizationPolicy.decode(member.identityJson)
            check(keys.isNotEmpty())
            check(keys.none(stale::contains)) { "Коллекция всё ещё ссылается на прежний адрес" }
            if (keys.any(newKeys::contains)) {
                check(keys == newKeys && member.metadataJson == CollectionMemberMetadata.encode(target)) { "Идентичность коллекции изменилась" }
                found += member.id
            }
        }
        check(found == expectedMembers) { "Коллекция потеряла перенесённый файл" }
    }
}
