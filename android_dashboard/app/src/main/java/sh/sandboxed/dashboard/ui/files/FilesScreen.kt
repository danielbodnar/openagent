package sh.sandboxed.dashboard.ui.files

import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import sh.sandboxed.dashboard.data.AppContainer
import sh.sandboxed.dashboard.data.FileEntry
import sh.sandboxed.dashboard.ui.TestTags
import sh.sandboxed.dashboard.ui.tag
import sh.sandboxed.dashboard.ui.components.ErrorBanner
import sh.sandboxed.dashboard.ui.components.GlassCard
import sh.sandboxed.dashboard.ui.theme.Palette
import java.io.File

private data class FilesState(
    val path: String = ".",
    val backStack: List<String> = emptyList(),
    val entries: List<FileEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val info: String? = null,
)

private class FilesViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(FilesState())
    val state: StateFlow<FilesState> = _state.asStateFlow()

    init { refresh() }

    fun cd(path: String) {
        _state.update { it.copy(path = path, backStack = it.backStack + it.path) }
        refresh()
    }

    /// Jump straight to a path (breadcrumb tap, quick-nav shortcut, or the
    /// editable path field). Pushes the current location so Up still works.
    fun jumpTo(path: String) {
        val target = path.trim().ifBlank { "." }
        if (target == _state.value.path) return
        cd(target)
    }

    fun up() {
        val stack = _state.value.backStack
        if (stack.isEmpty()) return
        _state.update { it.copy(path = stack.last(), backStack = stack.dropLast(1)) }
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val path = _state.value.path
            runCatching { container.api.listFiles(path) }
                .onSuccess { list ->
                    _state.update { st -> st.copy(entries = list.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })), loading = false) }
                }
                .onFailure { e ->
                    // Fall back to "." once if the requested path can't be opened (e.g. server returns
                    // 500 because the directory doesn't exist on this install).
                    if (path != ".") {
                        _state.update { it.copy(path = ".", backStack = emptyList(), error = "Could not open $path — falling back to workspace root") }
                        refresh()
                    } else {
                        _state.update { it.copy(error = e.message, loading = false) }
                    }
                }
        }
    }

    fun mkdir(name: String) {
        val child = normalizedFolderName(name)
        if (child == null) {
            _state.update { it.copy(error = folderNameValidationError(name) ?: "Invalid folder name") }
            return
        }

        val newPath = childPath(_state.value.path, child)
        viewModelScope.launch {
            runCatching { container.api.mkdir(newPath) }
                .onSuccess { refresh() }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

    fun delete(entry: FileEntry) {
        viewModelScope.launch {
            runCatching { container.api.rm(entry.path, recursive = entry.isDirectory) }
                .onSuccess { refresh() }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

    fun upload(name: String, contentType: String?, bytes: ByteArray) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            runCatching { container.api.uploadFile(_state.value.path, name, contentType, bytes) }
                .onSuccess { _state.update { it.copy(info = "Uploaded $name", loading = false) }; refresh() }
                .onFailure { e -> _state.update { it.copy(error = e.message, loading = false) } }
        }
    }

    fun download(entry: FileEntry, target: File, onDone: (File) -> Unit) {
        viewModelScope.launch {
            runCatching { container.api.downloadToFile(entry.path, target) }
                .onSuccess { onDone(target) }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

    fun clearMessages() { _state.update { it.copy(error = null, info = null) } }

    private fun childPath(parent: String, name: String): String {
        val base = parent.trim().trimEnd('/')
        return when {
            base.isBlank() || base == "." -> "./$name"
            base == "/" -> "/$name"
            else -> "$base/$name"
        }
    }
}

private fun normalizedFolderName(name: String): String? {
    val child = name.trim()
    return child.takeIf { folderNameValidationError(it) == null }
}

private fun folderNameValidationError(name: String): String? {
    val child = name.trim()
    return when {
        child.isBlank() -> "Enter a folder name"
        child == "." || child == ".." -> "Folder name cannot be . or .."
        child.any { it == '/' || it == '\\' } -> "Folder name cannot contain path separators"
        else -> null
    }
}

@Composable
fun FilesScreen(container: AppContainer) {
    val vm = remember { FilesViewModel(container) }
    val state by vm.state.collectAsState()
    val ctx = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    var showMkdir by remember { mutableStateOf(false) }
    var showPathEdit by remember { mutableStateOf(false) }
    var showQuickNav by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<FileEntry?>(null) }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            val name = ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val nameIdx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIdx >= 0 && c.moveToFirst()) c.getString(nameIdx) else null
            } ?: uri.lastPathSegment ?: "upload.bin"
            val bytes = input.readBytes()
            vm.upload(name, ctx.contentResolver.getType(uri), bytes)
        }
    }

    LaunchedEffect(state.error, state.info) {
        state.error?.let { snackbar.showSnackbar(it); vm.clearMessages() }
        state.info?.let { snackbar.showSnackbar(it); vm.clearMessages() }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Files", style = MaterialTheme.typography.titleMedium, color = Palette.TextPrimary, modifier = Modifier.weight(1f))
            IconButton(onClick = { pickFile.launch("*/*") }, modifier = Modifier.tag(TestTags.FILES_UPLOAD)) { Icon(Icons.Filled.Upload, "Upload", tint = Palette.Accent) }
            IconButton(onClick = { showMkdir = true }, modifier = Modifier.tag(TestTags.FILES_NEW_FOLDER)) { Icon(Icons.Filled.CreateNewFolder, "New folder", tint = Palette.Accent) }
            IconButton(onClick = vm::refresh, modifier = Modifier.tag(TestTags.FILES_REFRESH)) { Icon(Icons.Filled.Refresh, "Refresh", tint = Palette.TextSecondary) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = vm::up, enabled = state.backStack.isNotEmpty(), modifier = Modifier.tag(TestTags.FILES_UP)) {
                Icon(Icons.Filled.ArrowUpward, "Up", tint = Palette.TextSecondary)
            }
            Breadcrumbs(
                path = state.path,
                onCrumb = { vm.jumpTo(it) },
                onEdit = { showPathEdit = true },
                modifier = Modifier.weight(1f),
            )
            Box {
                IconButton(onClick = { showQuickNav = true }, modifier = Modifier.tag(TestTags.FILES_QUICKNAV)) {
                    Icon(Icons.Filled.Bookmark, "Quick navigation", tint = Palette.TextSecondary)
                }
                DropdownMenu(expanded = showQuickNav, onDismissRequest = { showQuickNav = false }) {
                    listOf(
                        "Workspace root" to ".",
                        "/root/context" to "/root/context",
                        "/root" to "/root",
                        "/tmp" to "/tmp",
                    ).forEach { (label, target) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = { showQuickNav = false; vm.jumpTo(target) },
                        )
                    }
                }
            }
        }
        state.error?.let { Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { ErrorBanner(it) } }
        if (state.loading && state.entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Palette.Accent)
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(state.entries, key = { it.path }) { entry ->
                    FileRow(
                        entry = entry,
                        onClick = { if (entry.isDirectory) vm.cd(entry.path) },
                        onDownload = {
                            val cacheFile = File(ctx.cacheDir, entry.name)
                            vm.download(entry, cacheFile) { f ->
                                val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension)
                                    ?: "application/octet-stream"
                                val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(uri, mime)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                runCatching { ctx.startActivity(Intent.createChooser(intent, "Open ${f.name}")) }
                            }
                        },
                        onDelete = { pendingDelete = entry },
                    )
                }
            }
        }
    }

    SnackbarHost(snackbar)

    if (showMkdir) {
        var name by remember { mutableStateOf("") }
        val nameError = folderNameValidationError(name).takeIf { name.isNotEmpty() }
        AlertDialog(
            onDismissRequest = { showMkdir = false },
            title = { Text("New folder") },
            text = {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text("Folder name") },
                    isError = nameError != null,
                    supportingText = nameError?.let { { Text(it) } },
                    modifier = Modifier.tag(TestTags.FILES_NEW_FOLDER_NAME),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Palette.Card,
                        unfocusedContainerColor = Palette.Card,
                        focusedTextColor = Palette.TextPrimary,
                        unfocusedTextColor = Palette.TextPrimary,
                        cursorColor = Palette.Accent,
                    ),
                )
            },
            confirmButton = {
                Button(
                    onClick = { vm.mkdir(name); showMkdir = false },
                    enabled = folderNameValidationError(name) == null,
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent),
                    modifier = Modifier.tag(TestTags.FILES_NEW_FOLDER_CREATE),
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showMkdir = false }, modifier = Modifier.tag(TestTags.FILES_NEW_FOLDER_CANCEL)) { Text("Cancel") }
            },
            containerColor = Palette.Card,
        )
    }

    if (showPathEdit) {
        var pathInput by remember(state.path) { mutableStateOf(state.path) }
        AlertDialog(
            onDismissRequest = { showPathEdit = false },
            title = { Text("Go to path") },
            text = {
                OutlinedTextField(
                    value = pathInput, onValueChange = { pathInput = it }, singleLine = true,
                    label = { Text("Path (\".\" = workspace root)") },
                    modifier = Modifier.fillMaxWidth().tag(TestTags.FILES_PATH_FIELD),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Palette.Card,
                        unfocusedContainerColor = Palette.Card,
                        focusedTextColor = Palette.TextPrimary,
                        unfocusedTextColor = Palette.TextPrimary,
                        cursorColor = Palette.Accent,
                    ),
                )
            },
            confirmButton = {
                Button(
                    onClick = { vm.jumpTo(pathInput); showPathEdit = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent),
                    modifier = Modifier.tag(TestTags.FILES_PATH_GO),
                ) { Text("Go") }
            },
            dismissButton = { TextButton(onClick = { showPathEdit = false }) { Text("Cancel") } },
            containerColor = Palette.Card,
        )
    }

    pendingDelete?.let { e ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete ${e.name}?") },
            text = { Text(if (e.isDirectory) "Folder and all its contents will be removed." else "This file will be removed.") },
            confirmButton = {
                Button(
                    onClick = { vm.delete(e); pendingDelete = null },
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.Error),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
            containerColor = Palette.Card,
        )
    }
}

/// Clickable path segments. Tapping a segment jumps to that ancestor; tapping
/// the leaf (or the root label) opens the editable path dialog.
@Composable
private fun Breadcrumbs(path: String, onCrumb: (String) -> Unit, onEdit: () -> Unit, modifier: Modifier = Modifier) {
    if (path == "." || path.isBlank()) {
        Text(
            "Workspace root",
            color = Palette.TextSecondary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = modifier.clickable(onClick = onEdit).tag(TestTags.FILES_PATH),
        )
        return
    }
    val absolute = path.startsWith("/")
    val segments = path.trim('/').split('/').filter { it.isNotBlank() }
    LazyRow(modifier = modifier.tag(TestTags.FILES_PATH), verticalAlignment = Alignment.CenterVertically) {
        item {
            Text(
                if (absolute) "/" else ".",
                color = Palette.TextTertiary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.clickable { onCrumb(if (absolute) "/" else ".") }.padding(vertical = 4.dp),
            )
        }
        itemsIndexed(segments) { i, seg ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (i > 0 || !absolute) Text("/", color = Palette.TextTertiary, style = MaterialTheme.typography.bodyMedium)
                val isLeaf = i == segments.lastIndex
                val target = (if (absolute) "/" else "") + segments.take(i + 1).joinToString("/")
                Text(
                    seg,
                    color = if (isLeaf) Palette.TextPrimary else Palette.TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .clickable { if (isLeaf) onEdit() else onCrumb(target) }
                        .padding(vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun FileRow(entry: FileEntry, onClick: () -> Unit, onDownload: () -> Unit, onDelete: () -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (entry.isDirectory) Icons.Filled.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
                contentDescription = null,
                tint = if (entry.isDirectory) Palette.AccentLight else Palette.TextSecondary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.name, color = Palette.TextPrimary, style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (entry.isDirectory) "folder" else humanSize(entry.size),
                    color = Palette.TextTertiary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (entry.isFile) IconButton(onClick = onDownload) { Icon(Icons.Filled.Download, "Download", tint = Palette.AccentLight) }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Delete", tint = Palette.Error) }
        }
    }
}

private fun humanSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var size = bytes / 1024.0
    var idx = 0
    while (size >= 1024 && idx < units.lastIndex) { size /= 1024; idx++ }
    return "%.1f %s".format(size, units[idx])
}
