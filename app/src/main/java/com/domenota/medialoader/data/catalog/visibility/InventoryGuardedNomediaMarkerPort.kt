package com.domenota.medialoader.data.catalog.visibility

/**
 * A verified SAF folder can change between its initial inventory read and
 * the actual .nomedia creation. Recheck immediately before the write.
 *
 * This guard never mutates media. The wrapped port is still the only
 * component allowed to create or delete the exact .nomedia marker.
 */
internal class InventoryGuardedNomediaMarkerPort(
    private val delegate: NomediaMarkerPort,
    private val expected: List<NomediaMediaSignature>,
    private val inventory: suspend (String) -> List<NomediaSafEntry>,
) : NomediaMarkerPort by delegate {
    override suspend fun createEmpty(treeUri: String): NomediaMarkerProof {
        val current = inventory(treeUri).map {
            NomediaMediaSignature(it.displayName, it.byteCount ?: -1L)
        }
        check(nomediaInventoryMatches(expected, current)) {
            "Состав физической папки изменился перед созданием .nomedia. Ничего не скрыто"
        }
        return delegate.createEmpty(treeUri)
    }
}
