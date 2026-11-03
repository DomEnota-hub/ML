package com.domenota.medialoader.ui.screens

import android.app.Activity
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.domenota.medialoader.data.library.PhoneMediaTrash
import com.domenota.medialoader.ui.components.MediaDeletionPolicy
import com.domenota.medialoader.ui.model.DownloadUiItem
import kotlinx.coroutines.CancellationException

private data class PhoneTrashRequest(
    val permanently: Boolean,
    val batches: List<List<Uri>>,
)

/**
 * Complements ML's existing private trash with Android 11+ MediaStore trash.
 * Never mutates a phone media URI without a per-batch system consent result.
 */
@Composable
internal fun PhoneTrashController(
    appItems: List<DownloadUiItem>,
    onBack: () -> Unit,
    onOpenApp: (String) -> Unit,
    onRestoreApp: (Set<String>) -> Unit,
    onDeleteApp: (Set<String>) -> Unit,
) {
    val context = LocalContext.current
    var sortedAppItems by remember { mutableStateOf(appItems) }
    LaunchedEffect(appItems) {
        sortedAppItems = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            appItems.sortedByDescending { row ->
                row.savedUri?.let { value -> val uri = Uri.parse(value)
                    if (uri.scheme == "file") java.io.File(uri.path.orEmpty()).lastModified() else row.trashOrderMs
                } ?: row.trashOrderMs
            }
        }
    }
    var preview by remember { mutableStateOf<DownloadUiItem?>(null) }
    preview?.let { item -> TrashMediaPreview(item) { preview = null } }
    var phoneItems by remember { mutableStateOf(emptyList<DownloadUiItem>()) }
    var refresh by remember { mutableIntStateOf(0) }
    var request by remember { mutableStateOf<PhoneTrashRequest?>(null) }
    var index by remember { mutableIntStateOf(0) }

    LaunchedEffect(refresh) {
        try { phoneItems = PhoneMediaTrash.list(context) }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) {
            phoneItems = emptyList()
            Toast.makeText(context, "Не удалось прочитать системную корзину", Toast.LENGTH_SHORT).show()
        }
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            index++
        } else {
            request = null
            refresh++
        }
    }
    LaunchedEffect(request, index) {
        val action = request ?: return@LaunchedEffect
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            request = null
            return@LaunchedEffect
        }
        if (index >= action.batches.size) {
            request = null
            refresh++
            return@LaunchedEffect
        }
        try {
            val batch = action.batches[index]
            val pending = if (action.permanently) {
                MediaStore.createDeleteRequest(context.contentResolver, batch)
            } else {
                MediaStore.createTrashRequest(context.contentResolver, batch, false)
            }
            launcher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            request = null
            refresh++
            Toast.makeText(context, "Операция с корзиной не выполнена", Toast.LENGTH_LONG).show()
        }
    }

    fun begin(permanently: Boolean, ids: Set<String>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || request != null) return
        val uris = PhoneMediaTrash.authorizedUris(ids, phoneItems)
        if (uris.isEmpty()) return
        request = PhoneTrashRequest(
            permanently = permanently,
            batches = uris.chunked(MediaDeletionPolicy.REQUEST_LIMIT),
        )
        index = 0
    }

    TrashScreen(
        items = sortedAppItems,
        phoneItems = phoneItems,
        onBack = onBack,
        onOpen = { id -> if (PhoneMediaTrash.isPhoneTrashId(id)) preview = phoneItems.firstOrNull { it.id == id } else onOpenApp(id) },
        onRestore = onRestoreApp,
        onDelete = onDeleteApp,
        onRestorePhone = { begin(false, it) },
        onDeletePhone = { begin(true, it) },
    )
}
