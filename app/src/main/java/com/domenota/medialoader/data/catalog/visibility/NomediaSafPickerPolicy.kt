package com.domenota.medialoader.data.catalog.visibility

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import com.domenota.medialoader.core.logging.AppLog

/**
 * EXTRA_INITIAL_URI is a location hint, NOT a permission.
 * A canonical document URI is used instead of an as-yet-ungranted tree URI.
 * Never guess physical filesystem paths or broaden the selected tree.
 */
internal object NomediaSafPickerPolicy {
    private const val PROVIDER = "com.android.externalstorage.documents"

    fun suggestedInitialDocument(tree:Uri):Uri {
        require(tree.scheme=="content" && tree.authority==PROVIDER)
        val id=DocumentsContract.getTreeDocumentId(tree)
        require(nomediaSafTreeEligible(id))
        return DocumentsContract.buildDocumentUri(PROVIDER,id)
    }

    fun isExactlyRequestedTree(expected:Uri,returned:Uri):Boolean {
        if(expected.scheme!="content" || expected.authority!=PROVIDER ||
            returned.scheme!="content" || returned.authority!=PROVIDER) return false
        val id=runCatching{DocumentsContract.getTreeDocumentId(expected)}.getOrNull()?:return false
        val chosen=runCatching{DocumentsContract.getTreeDocumentId(returned)}.getOrNull()?:return false
        return id==chosen && nomediaSafTreeEligible(chosen) &&
            returned==DocumentsContract.buildTreeDocumentUri(PROVIDER,chosen)
    }

    fun hasExactReadWriteGrant(resolver:ContentResolver,requested:Uri):Boolean =
        resolver.persistedUriPermissions.any { grant->
            isExactlyRequestedTree(requested,grant.uri) &&
                grant.isReadPermission && grant.isWritePermission
        }
}

/** Safe diagnostics: no filenames, folder IDs, physical paths or SAF URIs. */
internal object NomediaSafTrace {
    fun event(code:String,files:Int=0){
        require(code.matches(Regex("[A-Z0-9_]{3,54}")))
        AppLog.i("NOMEDIA_SAF","stage=$code mediaCount=$files")
    }
    fun failure(code:String,error:Throwable){
        require(code.matches(Regex("[A-Z0-9_]{3,54}")))
        AppLog.w("NOMEDIA_SAF","stage=$code type=${error.javaClass.simpleName.take(48)}")
    }
}
