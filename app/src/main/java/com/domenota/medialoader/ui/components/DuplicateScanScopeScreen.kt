package com.domenota.medialoader.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.data.library.DuplicateFolderPathResolver
import com.domenota.medialoader.data.library.DuplicateFolderScope
import com.domenota.medialoader.data.library.DuplicateFolderSort
import com.domenota.medialoader.data.library.DuplicateFolderTree
import com.domenota.medialoader.data.library.DuplicateFileSearch
import com.domenota.medialoader.data.library.DuplicateFileSearchHit
import com.domenota.medialoader.data.library.DuplicatePrivacyGuard
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.domenota.medialoader.core.model.MediaType

/** Same full-screen folder chooser for Gallery and Music.
 * Never expands scope from an ambiguous MediaStore bucket or display name.
 */
@Composable
internal fun DuplicateScanScopeScreen(
    rows: List<DownloadEntity>,
    mediaLabel: String,
    onDismiss: () -> Unit,
    onStart: (Set<String>?) -> Unit,
) {
    val context = LocalContext.current
    val privacyEpoch by DuplicatePrivacyGuard.changes.collectAsStateWithLifecycle()
    val blockedKeys=DuplicatePrivacyGuard.currentBlockedKeys()
    // Parent routes can recreate structurally identical lists during progress ticks.
    // Stabilize the input for the referential background-projection key.
    val stableRows=remember(rows,privacyEpoch,blockedKeys){
        rows.filter{row->row.id !in blockedKeys&&row.savedUri !in blockedKeys&&
            DuplicatePrivacyGuard.accepts(context,privacyEpoch,row.id)}
    }
    val folders = remember(stableRows) { DuplicateFolderScope.options(stableRows) }
    val hasAudio=remember(stableRows){stableRows.any{it.mediaType==MediaType.AUDIO}}
    var choosing by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    var fileFilter by remember { mutableStateOf("") }
    var searchFiles by remember { mutableStateOf(false) }
    var focusedKey by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var expanded by remember { mutableStateOf(emptySet<String>()) }
    var sortMode by remember { mutableStateOf(DuplicateFolderSort.FILE_COUNT) }
    var descending by remember { mutableStateOf(true) }
    var resolvedPaths by remember(stableRows) { mutableStateOf(emptyMap<String, String>()) }
    var resolvingPaths by remember(stableRows) { mutableStateOf(false) }
    LaunchedEffect(choosing, stableRows) {
        if (choosing && folders.any { it.key.startsWith("bucket:") }) {
            resolvingPaths = true
            try {
                resolvedPaths = DuplicateFolderPathResolver.resolve(context, stableRows)
            } finally {
                resolvingPaths = false
            }
        }
    }
    val options = remember(folders, resolvedPaths) {
        DuplicateFolderPathResolver.apply(folders, resolvedPaths)
    }
    val tree = remember(options, sortMode, descending) {
        DuplicateFolderTree.sort(DuplicateFolderTree.build(options), sortMode, descending)
    }
    val focusPath = remember(tree,focusedKey) {
        focusedKey?.let { DuplicateFileSearch.pathToKey(tree,it) }.orEmpty()
    }
    val visible = remember(tree, expanded, filter, focusedKey, focusPath) {
        if(focusedKey!=null)focusPath else DuplicateFolderTree.visible(tree, expanded, filter)
    }
    val matchingKeys = remember(tree, filter, focusedKey, focusPath) {
        focusedKey?.let { if(focusPath.isEmpty())emptySet() else setOf(it) }
            ?: DuplicateFolderTree.matchingKeys(tree, filter)
    }
    val matchedFolderCount=remember(options,matchingKeys){
        DuplicateFolderScope.selectionSummary(options,matchingKeys).folderCount
    }
    // File-name matching and sorting can touch the whole media catalogue.
    // Only publish results for the exact current query and inputs; never keep
    // stale rows when permissions/catalogue change.
    val fileProjection=backgroundLibraryProjection(
        emptyList<DuplicateFileSearchHit>(),blockedKeys,stableRows,options,fileFilter,accessGuard=privacyEpoch,
        enabled=choosing&&searchFiles&&fileFilter.isNotBlank()){
        DuplicateFileSearch.matches(stableRows,options,fileFilter)
    }
    val fileHits=if(fileProjection.ready&&DuplicatePrivacyGuard.generation(context)==privacyEpoch)fileProjection.value else emptyList()
    // Resolve only visible results, once per URI and current catalogue. A
    // nullable value in the map is an attempted but unconfirmed location.
    val checkedFilePaths=remember(stableRows,privacyEpoch){mutableStateMapOf<String,String?>()}
    val selection = remember(selected, options) {
        DuplicateFolderScope.selectionSummary(options, selected)
    }
    val folderCount = remember(options) {
        DuplicateFolderScope.selectionSummary(options, options.mapTo(mutableSetOf()) { it.key }).folderCount
    }
    fun back(){
        if(choosing&&focusedKey!=null){focusedKey=null;searchFiles=true}
        else if(choosing)choosing=false else onDismiss()
    }
    BackHandler { back() }
    val countLabel = "${selection.folderCount} ${DuplicateFolderScope.folderWord(selection.folderCount)}"
    val sizeLabel = selection.sizeBytes?.let {
        android.text.format.Formatter.formatShortFileSize(context, it)
    } ?: "размер неизвестен"
    // Render this screen inside the Activity NavHost viewport. Its parent
    // Scaffold has ALREADY reserved status/navigation bars (safeDrawing).
    // Never create a second Dialog window or apply navigationBarsPadding:
    // dialog window insets were zero on the reported Xiaomi Android 16.
    Surface(Modifier.fillMaxSize().testTag("scope-route-screen"),
        color = MaterialTheme.colorScheme.background) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = MaterialTheme.colorScheme.background,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                bottomBar = {
                    if (choosing) {
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            tonalElevation = 3.dp,
                            shadowElevation = 8.dp,
                        ) {
                            // Footer stays within the Activity's already-safe
                            // viewport. Only the IME needs extra space here.
                            Column(
                                Modifier.fillMaxWidth()
                                    .imePadding()
                                    .padding(horizontal = 16.dp, vertical = 12.dp)
                                    .testTag("scope-fixed-footer"),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text("Выбрано: $countLabel, ${selection.fileCount} ${DuplicateFolderScope.fileWord(selection.fileCount)}, $sizeLabel",
                                    style = MaterialTheme.typography.bodyMedium)
                                Button({
                                    val currentKeys=options.map { it.key }.filterTo(mutableSetOf()) { it in selected }
                                    if(DuplicatePrivacyGuard.generation(context)==privacyEpoch&&currentKeys.isNotEmpty())onStart(currentKeys)
                                },
                                    enabled = selection.fileCount > 0,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                                        .testTag("scope-start-selected")) {
                                    Text("Начать поиск")
                                }
                            }
                        }
                    }
                },
            ) { layoutPadding ->
                Column(
                    Modifier.fillMaxSize()
                        .padding(layoutPadding)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(::back) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад")
                    }
                    Text(if (choosing) "Выбор папок" else "Поиск дубликатов",
                        Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                }
                if (!choosing) {
                    Text("Область поиска: $mediaLabel",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Проверить всё", style = MaterialTheme.typography.titleMedium)
                            Text("Все доступные файлы раздела: ${DuplicateFolderScope.availableFileCount(stableRows)}",
                                style = MaterialTheme.typography.bodySmall)
                            Button({ onStart(null) }, Modifier.fillMaxWidth().testTag("scope-all")) {
                                Text("Проверить всё")
                            }
                        }
                    }
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Проверить выборочно", style = MaterialTheme.typography.titleMedium)
                            Text("Выбор отдельных папок без полного анализа медиатеки.",
                                style = MaterialTheme.typography.bodySmall)
                            OutlinedButton({ choosing = true }, Modifier.fillMaxWidth().testTag("scope-select-folders"),
                                enabled = folders.isNotEmpty()) {
                                Icon(Icons.Rounded.FolderOpen, null)
                                Spacer(Modifier.width(8.dp))
                                Text("Выбрать папки")
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                } else {
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        FilterChip(!searchFiles,{searchFiles=false},
                            label={Text("Папки")},modifier=Modifier.weight(1f).testTag("scope-search-mode-folders"))
                        FilterChip(searchFiles,{searchFiles=true;focusedKey=null},
                            label={Text("Файлы")},modifier=Modifier.weight(1f).testTag("scope-search-mode-files"))
                    }
                    val query=if(searchFiles)fileFilter else filter
                    OutlinedTextField(
                        value = query, onValueChange = {
                            if(searchFiles)fileFilter=it else{filter=it;focusedKey=null}
                        },
                        label = { Text(if(searchFiles){
                            if(hasAudio)"Имя файла или трека" else "Имя фото или видео"
                        }else "Название или путь папки") },
                        leadingIcon={Icon(Icons.Rounded.Search,null)},
                        trailingIcon={if(query.isNotEmpty())IconButton({
                            if(searchFiles)fileFilter="" else {filter="";focusedKey=null}
                        }){Icon(Icons.Rounded.Close,"Очистить поиск")}},
                        modifier = Modifier.fillMaxWidth().testTag(if(searchFiles)"scope-search-file" else "scope-search-folder"),
                        singleLine = true,
                    )
                    if(searchFiles){
                        when{
                            fileFilter.isBlank()->Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center){
                                Text("Введите название файла",style=MaterialTheme.typography.bodyMedium)
                            }
                            !fileProjection.ready->Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center){
                                Text("Ищу совпадения…",style=MaterialTheme.typography.bodyMedium)
                            }
                            else->{
                                Text("Найдено: ${fileHits.size} ${DuplicateFolderScope.fileWord(fileHits.size)}",
                                    modifier=Modifier.testTag("scope-file-count"),style=MaterialTheme.typography.bodySmall)
                                if(fileHits.isEmpty())ScopeSearchEmpty("Попробуйте другое имя файла",Modifier.weight(1f))
                                else LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("scope-file-list"),
                                    verticalArrangement=Arrangement.spacedBy(6.dp)){
                                    items(fileHits,key={requireNotNull(it.row.savedUri)}){hit->
                                        val uri=requireNotNull(hit.row.savedUri)
                                        val needsCheck=hit.folder.key.startsWith("bucket:")
                                        LaunchedEffect(uri,needsCheck,checkedFilePaths){
                                            if(needsCheck&&!checkedFilePaths.containsKey(uri))
                                                checkedFilePaths[uri]=DuplicateFolderPathResolver.resolveFileDirectory(context,hit.row)
                                        }
                                        val checking=needsCheck&&!checkedFilePaths.containsKey(uri)
                                        val directory=if(needsCheck)checkedFilePaths[uri] else hit.folder.directoryId
                                        val conflict=directory!=null&&hit.folder.directoryId!=null&&directory!=hit.folder.directoryId
                                        Card(Modifier.fillMaxWidth()){
                                            Column(Modifier.padding(10.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
                                                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
                                                    Icon(when(hit.row.mediaType){
                                                        MediaType.AUDIO->Icons.Rounded.MusicNote
                                                        MediaType.VIDEO->Icons.Rounded.Videocam
                                                        else->Icons.Rounded.Image
                                                    },null,tint=MaterialTheme.colorScheme.primary)
                                                    Text(hit.row.originalName,Modifier.weight(1f),maxLines=2,overflow=TextOverflow.Ellipsis)
                                                }
                                                if(hit.row.mediaType==MediaType.AUDIO){
                                                    val title=hit.row.recognizedTitle?:hit.row.sourceAudioTitle
                                                    title?.takeIf{it.isNotBlank()&&it!=hit.row.originalName}?.let{
                                                        Text(it,maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall)
                                                    }
                                                }
                                                val parentName=directory?.substringAfter('|')?.substringAfterLast('/')?:hit.folder.name
                                                Text("Папка: $parentName",style=MaterialTheme.typography.bodySmall,maxLines=2,overflow=TextOverflow.Ellipsis)
                                                Text(when{
                                                    checking->"Проверяю расположение…"
                                                    directory!=null->"Расположение: ${directory.substringAfter('|')}"
                                                    else->"Расположение не определено"
                                                },style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=2,overflow=TextOverflow.Ellipsis)
                                                directory?.substringBefore('|')?.takeIf{it.startsWith("volume:")}?.let{volume->
                                                    Text(if(volume=="volume:external_primary")"Внутренняя память" else "Носитель: ${volume.removePrefix("volume:")}",
                                                        style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                                if(conflict)Text("Путь не совпадает с папкой каталога. Обновите каталог.",
                                                    style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
                                                TextButton({
                                                    // Navigation alone never touches selected keys.
                                                    focusedKey=hit.folder.key;filter="";searchFiles=false
                                                },enabled=!checking&&!conflict,
                                                    modifier=Modifier.fillMaxWidth().testTag("scope-file-folder-${hit.row.id}")){
                                                    Text(if(hit.folder.directoryId==null)"Перейти к группе файлов" else "Перейти к папке")
                                                    Spacer(Modifier.width(4.dp));Icon(Icons.Rounded.ChevronRight,null,Modifier.size(18.dp))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }else{
                    if(focusedKey!=null){
                        Text(when{
                            focusPath.isEmpty()->"Папка больше недоступна"
                            focusPath.last().node.id.startsWith("folder:")->"Путь не подтверждён. Отметка включает все доступные файлы только этой группы."
                            else->"Отметьте папку, чтобы включить её доступные файлы без вложенных папок."
                        },
                            modifier=Modifier.testTag("scope-focused-folder"),style=MaterialTheme.typography.bodySmall)
                        TextButton({focusedKey=null;filter=""}){Text("Все папки")}
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(if(filter.isBlank()&&focusedKey==null)"Папки: $folderCount" else "Найдено: $matchedFolderCount из $folderCount папок",
                            modifier = Modifier.weight(1f).testTag("scope-folder-count"),
                            style = MaterialTheme.typography.bodySmall)
                        if(focusedKey==null)TextButton({
                            selected = if (matchingKeys.isNotEmpty() && matchingKeys.all { it in selected })
                                selected - matchingKeys else selected + matchingKeys
                        }, enabled = matchingKeys.isNotEmpty()) {
                            Icon(Icons.Rounded.SelectAll, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (matchingKeys.isNotEmpty() && matchingKeys.all { it in selected })
                                "Снять выбор" else "Выбрать все")
                        }
                    }
                    if(focusedKey==null)Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(sortMode == DuplicateFolderSort.FILE_COUNT,
                            { sortMode = DuplicateFolderSort.FILE_COUNT },
                            label = { Text("Количество") })
                        FilterChip(sortMode == DuplicateFolderSort.NAME,
                            { sortMode = DuplicateFolderSort.NAME; descending = false },
                            label = { Text("Название") })
                        FilterChip(sortMode == DuplicateFolderSort.SIZE,
                            { sortMode = DuplicateFolderSort.SIZE; descending = true },
                            label = { Text("Размер") })
                        TextButton({ descending = !descending }, modifier = Modifier.testTag("scope-sort-direction")) {
                            Text(if (descending) "↓" else "↑")
                        }
                    }
                    if (resolvingPaths) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    if(visible.isEmpty())ScopeSearchEmpty("Попробуйте другое название или поиск по файлам",Modifier.weight(1f))
                    else LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("scope-folder-list"),
                        verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(visible, key = { it.node.id }) { row ->
                            val folder = row.node
                            val contextOnly=focusedKey!=null&&focusedKey !in folder.ownKeys
                            val selectable=if(contextOnly)emptySet() else DuplicateFileSearch.selectableKeys(folder,matchingKeys)
                            val selectedChildren = selectable.count { it in selected }
                            val state = when {
                                selectedChildren == 0 -> ToggleableState.Off
                                selectedChildren == selectable.size -> ToggleableState.On
                                else -> ToggleableState.Indeterminate
                            }
                            val toggleSelection: () -> Unit = {
                                selected = if (state == ToggleableState.On) selected - selectable
                                    else selected + selectable
                            }
                            Row(Modifier.fillMaxWidth().padding(start = (row.depth.coerceAtMost(5) * 12).dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                if (folder.children.isNotEmpty()&&focusedKey==null) {
                                    IconButton({ expanded = if (folder.id in expanded) expanded - folder.id
                                        else expanded + folder.id }, Modifier.size(40.dp)) {
                                        Icon(if (folder.id in expanded || filter.isNotBlank())
                                            Icons.Rounded.ExpandMore else Icons.Rounded.ChevronRight,
                                            "Раскрыть папку")
                                    }
                                } else Spacer(Modifier.width(40.dp))
                                Icon(Icons.Rounded.Folder, null, Modifier.size(20.dp)
                                    .padding(end = 3.dp),tint=MaterialTheme.colorScheme.primary)
                                Column(Modifier.weight(1f).clickable(enabled=!contextOnly) {
                                    if (folder.children.isNotEmpty()&&focusedKey==null) expanded =
                                        if (folder.id in expanded) expanded - folder.id else expanded + folder.id
                                    else toggleSelection()
                                }.padding(vertical = 8.dp)) {
                                    Text(folder.name, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodyMedium)
                                    val verifiedPath = if (folder.id.startsWith("volume:") ||
                                        folder.id.startsWith("file|")) folder.location else null
                                    Text(verifiedPath?.let { "Расположение: $it" }
                                            ?: "Расположение не определено",
                                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    val filtered=filter.isNotBlank()||focusedKey!=null
                                    val scopedSummary=if(filtered)remember(options,selectable){
                                        DuplicateFolderScope.selectionSummary(options,selectable)
                                    }else null
                                    val bytes=if(scopedSummary!=null)scopedSummary.sizeBytes else folder.sizeBytes
                                    val count=scopedSummary?.fileCount?:folder.fileCount
                                    val sizeText = bytes?.let {
                                        android.text.format.Formatter.formatShortFileSize(context, it)
                                    } ?: "размер неизвестен"
                                    val details = if(contextOnly)"Родительская папка" else "$count ${DuplicateFolderScope.fileWord(count)}" +
                                        (if (folder.children.isNotEmpty()&&!filtered) ", вложенных папок: ${folder.folderCount}" else "") +
                                        ", $sizeText"
                                    Text(details, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.testTag("scope-row-details-${folder.id}"),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if(!contextOnly)TriStateCheckbox(state = state, onClick = toggleSelection,enabled=selectable.isNotEmpty(),
                                    modifier = Modifier.testTag("scope-node-${folder.id}"))
                                else Spacer(Modifier.width(48.dp))
                            }
                            HorizontalDivider()
                        }
                    }
                    }
                }
                }
            }
    }
}

@Composable
private fun ScopeSearchEmpty(hint:String,modifier:Modifier=Modifier){
    Column(modifier.fillMaxWidth().testTag("scope-search-empty"),verticalArrangement=Arrangement.Center,
        horizontalAlignment=Alignment.CenterHorizontally){
        Icon(Icons.Rounded.Search,null,Modifier.size(36.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Ничего не найдено",style=MaterialTheme.typography.titleMedium)
        Text(hint,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign=androidx.compose.ui.text.style.TextAlign.Center)
    }
}
