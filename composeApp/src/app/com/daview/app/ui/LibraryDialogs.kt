package com.daview.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.DriveFolderUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.daview.app.data.AppState
import com.daview.app.data.ModalMarker
import com.daview.app.data.tracksTextInput
import com.daview.shared.model.LibraryDto
import com.daview.shared.model.LibraryKind
import com.daview.shared.model.MetadataProvider
import com.daview.shared.model.ScanMode
import com.daview.shared.model.WebDavEntryDto
import kotlinx.coroutines.launch

/**
 * The scanner reports its phase as the identifier it uses internally. The
 * notification already translated these; the app itself showed "scraping" and
 * "probing" on screen.
 */
internal fun scanPhaseLabel(phase: String): String = when (phase) {
    "queued" -> "排队中"
    "syncing" -> "同步刮削数据"
    "listing" -> "读取目录"
    "scanning" -> "扫描新目录"
    "checking" -> "检查已有目录"
    "saving" -> "写入"
    "scraping" -> "获取元数据"
    "probing" -> "读取音轨与字幕"
    "done" -> "完成"
    "cancelled" -> "已取消"
    "error" -> "出错"
    else -> phase
}

internal fun kindLabel(kind: LibraryKind) = when (kind) {
    LibraryKind.MOVIE -> "电影"
    LibraryKind.SERIES -> "电视剧"
    LibraryKind.ANIME -> "番剧"
    LibraryKind.OTHER -> "其他"
}

/**
 * The three ways to scan, said in terms of what each one is for rather than
 * what it skips internally — "不走文件与容器探测" asked the reader to know what a
 * container probe was.
 */
@Composable
fun ColumnScope.ScanMenuItems(state: AppState, libraryId: String, dismiss: () -> Unit) {
    val running = state.scanStatus.any { it.libraryId == libraryId && it.running }
    if (running) {
        DropdownMenuItem(
            text = { Text("停止扫描") },
            onClick = { dismiss(); state.cancelScan(libraryId) }
        )
        return
    }
    ScanOption("扫描新增文件", "先找新目录并为它们获取元数据，再检查已有的剧有没有新集。平时用这个") {
        dismiss(); state.startScan(libraryId, ScanMode.FULL)
    }
    ScanOption("补全缺失的元数据", "不读目录，只为还没有海报和简介的条目获取。填了新的 API Key 之后用") {
        dismiss(); state.startScan(libraryId, ScanMode.MISSING)
    }
    ScanOption("全部重新获取元数据", "每个条目都重新刮削一遍，很慢。手动改过的字段和手动指定会保留") {
        dismiss(); state.startScan(libraryId, ScanMode.REFRESH)
    }
}

@Composable
private fun ScanOption(title: String, detail: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Column(Modifier.padding(vertical = 4.dp)) {
                Text(title)
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        onClick = onClick
    )
}

/**
 * Everything about a library that can change after it exists: its name, what
 * kind of thing it holds, which sources are asked and in what order, and in
 * which language. Only the folder is fixed, because the ids every watch record
 * hangs off are derived from it.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LibraryEditDialog(state: AppState, library: LibraryDto, onDismiss: () -> Unit) {
    ModalMarker()
    var name by remember(library.id) { mutableStateOf(library.name) }
    var kind by remember(library.id) { mutableStateOf(library.kind) }
    var language by remember(library.id) { mutableStateOf(library.language) }
    val allProviders = listOf(MetadataProvider.TMDB, MetadataProvider.TVDB, MetadataProvider.BANGUMI)
    // Order of the enabled ones first, then the rest switched off.
    var order by remember(library.id) {
        mutableStateOf(
            library.providerOrder.filter { it in allProviders }.ifEmpty { allProviders }
                .let { enabled -> enabled.map { it to true } + (allProviders - enabled.toSet()).map { it to false } }
        )
    }
    val settings = state.serverSettings
    fun configured(provider: MetadataProvider) = when (provider) {
        MetadataProvider.TMDB -> settings?.scraper?.tmdbApiKeySet == true
        MetadataProvider.TVDB -> settings?.scraper?.tvdbApiKeySet == true
        else -> true
    }
    val scrapingChanged = kind != library.kind || language != library.language ||
        order.filter { it.second }.map { it.first } != library.providerOrder

    AdaptiveAlertDialog(
        onDismissRequest = onDismiss,
        title = { LibraryDialogTitle("编辑媒体库", Icons.Filled.Folder) },
        text = {
            LibraryDialogBody {
                FolderLocation(
                    label = "WebDAV 目录 · 不可更改",
                    path = library.path,
                    detail = "观看记录与此目录关联。若要使用其他目录，请新建媒体库。"
                )
                LibraryFormSection("媒体库资料") {
                    OutlinedTextField(
                        name, { name = it },
                        label = { Text("媒体库名称") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth().tracksTextInput()
                    )
                    LibraryKindChoice(kind) { kind = it }
                }
                LibraryFormSection("元数据来源", "勾选要使用的来源，按从上到下的顺序尝试。") {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        order.forEachIndexed { index, (provider, enabled) ->
                            key(provider) {
                                ProviderRow(
                                    provider = provider,
                                    enabled = enabled,
                                    configured = configured(provider),
                                    index = index,
                                    lastIndex = order.lastIndex,
                                    onEnabledChange = { on ->
                                        order = order.toMutableList().also { it[index] = provider to on }
                                    },
                                    move = { direction ->
                                        order = order.toMutableList().also { list -> list.add(index + direction, list.removeAt(index)) }
                                    }
                                )
                            }
                        }
                    }
                    if (order.none { it.second }) SettingsNotice("至少选择一个元数据来源。", SettingsTone.Error)
                }
                LibraryFormSection("元数据语言") {
                    SettingsChoices(listOf("" to "跟随设置") + METADATA_LANGUAGES, language) { language = it }
                }
                if (scrapingChanged) {
                    SettingsNotice("保存后会重新获取已有条目的元数据，以应用新的类型、来源或语言。", SettingsTone.Attention)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank() && order.any { it.second },
                onClick = {
                    state.updateLibrary(
                        library.copy(
                            name = name.trim(),
                            kind = kind,
                            language = language,
                            providerOrder = order.filter { it.second }.map { it.first }
                        ),
                        rescan = scrapingChanged
                    ) { onDismiss() }
                }
            ) { Text(if (scrapingChanged) "保存并重新获取" else "保存", style = MaterialTheme.typography.labelLargeEmphasized) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LibraryDialogTitle(title: String, icon: ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
        Text(
            title,
            style = MaterialTheme.typography.headlineSmallEmphasized,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() }
        )
    }
}

/** Title and actions stay with the adaptive host; even with IME the form can scroll. */
@Composable
private fun LibraryDialogBody(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LibraryFormSection(title: String, detail: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmallEmphasized,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() }
        )
        detail?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        content()
    }
}

private val libraryKinds = LibraryKind.entries.map { it to kindLabel(it) }

@Composable
private fun LibraryKindChoice(selected: LibraryKind, onSelect: (LibraryKind) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("类型", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SettingsChoices(libraryKinds, selected, onSelect = onSelect)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FolderLocation(label: String, path: String, detail: String? = null) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        Modifier.fillMaxWidth(),
        color = scheme.secondaryContainer,
        contentColor = scheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelMediumEmphasized)
            Text(path, style = MaterialTheme.typography.bodyMediumEmphasized)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ProviderRow(
    provider: MetadataProvider,
    enabled: Boolean,
    configured: Boolean,
    index: Int,
    lastIndex: Int,
    onEnabledChange: (Boolean) -> Unit,
    move: (Int) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        Modifier.fillMaxWidth(),
        color = scheme.surfaceContainerHighest,
        shape = MaterialTheme.shapes.small
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f)
                    .toggleable(value = enabled, role = Role.Checkbox, onValueChange = onEnabledChange)
                    .heightIn(min = 48.dp).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Checkbox(checked = enabled, onCheckedChange = null)
                Column(Modifier.weight(1f)) {
                    Text(
                        provider.displayName,
                        style = if (enabled) MaterialTheme.typography.titleSmallEmphasized else MaterialTheme.typography.titleSmall
                    )
                    if (!configured) {
                        Text("缺少 API Key，不会使用", style = MaterialTheme.typography.bodySmall, color = scheme.tertiary)
                    }
                }
            }
            IconButton(enabled = index > 0, onClick = { move(-1) }) {
                Icon(Icons.Filled.ArrowUpward, contentDescription = "上移 ${provider.displayName}")
            }
            IconButton(enabled = index < lastIndex, onClick = { move(1) }) {
                Icon(Icons.Filled.ArrowDownward, contentDescription = "下移 ${provider.displayName}")
            }
        }
    }
}

/** The four languages the scrapers are actually asked for. */
internal val METADATA_LANGUAGES = listOf(
    "zh-CN" to "简体中文",
    "zh-TW" to "繁体中文",
    "ja-JP" to "日语",
    "en-US" to "英语"
)

/**
 * Picks the folder a new library points at.
 *
 * The bounded directory list can scroll without moving the current path or the
 * form, and the whole dialog body can also scroll in short panes. Manual names
 * and kinds are preserved as people move between directories.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WebDavPickerDialog(state: AppState, onDismiss: () -> Unit) {
    ModalMarker()
    val scope = rememberCoroutineScope()
    var path by remember { mutableStateOf("/") }
    var entries by remember { mutableStateOf<List<WebDavEntryDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var attemptedPath by remember { mutableStateOf("/") }
    val directoryList = rememberLazyListState()
    var name by remember { mutableStateOf("") }
    var nameEdited by remember { mutableStateOf(false) }
    var kind by remember { mutableStateOf(LibraryKind.MOVIE) }
    var kindEdited by remember { mutableStateOf(false) }

    fun load(target: String) {
        loading = true
        error = null
        attemptedPath = target
        scope.launch {
            try {
                entries = state.library.browseStorage(target)
                if (path != target) directoryList.requestScrollToItem(0)
                path = target
                val folder = target.trim('/').substringAfterLast('/')
                if (!nameEdited) name = folder
                if (!kindEdited) guessKind(folder)?.let { kind = it }
            } catch (e: Throwable) {
                error = "无法读取这个目录：${state.describe(e)}"
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { load("/") }

    val folders = entries.filter { it.isDirectory }
    val videos = entries.count { !it.isDirectory && isVideoName(it.name) }
    val atRoot = path.trim('/').isEmpty()

    AdaptiveAlertDialog(
        onDismissRequest = onDismiss,
        title = { LibraryDialogTitle("添加媒体库", Icons.Filled.FolderOpen) },
        text = {
            LibraryDialogBody {
                LibraryFormSection("选择 WebDAV 目录") {
                    FolderLocation(
                        label = if (atRoot) "当前目录 · 根目录" else "将添加的目录",
                        path = path,
                        detail = if (atRoot) "进入要添加的目录，不可选择根目录。" else null
                    )
                    SettingsActions {
                        TextButton(onClick = { load("/") }, enabled = !atRoot && !loading) {
                            Icon(Icons.Filled.Home, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                            Text("根目录")
                        }
                        TextButton(
                            onClick = { load("/" + path.trim('/').substringBeforeLast('/', "")) },
                            enabled = !atRoot && !loading
                        ) {
                            Icon(Icons.Filled.DriveFolderUpload, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                            Text("上一级")
                        }
                    }
                    if (loading) {
                        Text(
                            "正在读取目录…",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                        )
                        LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                    } else {
                        error?.let {
                            SettingsNotice(it, SettingsTone.Error)
                            FilledTonalButton(onClick = { load(attemptedPath) }) { Text("重试读取") }
                        }
                    }
                    if (error == null && !loading) {
                        Text(
                            if (folders.isEmpty()) {
                                if (videos > 0) "没有子目录 · 本层有 $videos 个视频文件" else "没有子目录或视频文件"
                            } else {
                                "${folders.size} 个子目录" + if (videos > 0) " · 本层有 $videos 个视频文件" else ""
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (folders.isNotEmpty()) {
                        Box {
                            LazyColumn(
                                Modifier.fillMaxWidth().heightIn(max = 224.dp),
                                state = directoryList,
                                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)
                            ) {
                                itemsIndexed(folders, key = { _, entry -> entry.path }) { index, entry ->
                                    SegmentedListItem(
                                        onClick = { if (!loading) load(entry.path) },
                                        enabled = !loading,
                                        shapes = ListItemDefaults.segmentedShapes(index, folders.size),
                                        colors = ListItemDefaults.segmentedColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                                        ),
                                        leadingContent = { Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(20.dp)) },
                                        trailingContent = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(20.dp)) },
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                                    ) {
                                        Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                            Box(Modifier.matchParentSize()) {
                                VerticalScrollbarFor(directoryList, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                            }
                        }
                    }
                }
                HorizontalDivider()
                LibraryFormSection("媒体库资料") {
                    OutlinedTextField(
                        name, { name = it; nameEdited = true },
                        label = { Text("媒体库名称") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth().tracksTextInput()
                    )
                    LibraryKindChoice(kind) {
                        kind = it
                        kindEdited = true
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !atRoot && name.isNotBlank() && !loading,
                onClick = {
                    state.createLibrary(
                        LibraryDto(id = "", name = name.trim(), kind = kind, path = path, language = "")
                    ) { onDismiss() }
                }
            ) { Text("添加并扫描", style = MaterialTheme.typography.labelLargeEmphasized) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** A first guess at what a folder holds, from its name. Only a default; the buttons decide. */
private fun guessKind(folder: String): LibraryKind? {
    val name = folder.lowercase()
    return when {
        listOf("anime", "ani", "番", "动画", "動畫", "アニメ").any { it in name } -> LibraryKind.ANIME
        listOf("tv", "series", "show", "剧", "劇", "ドラマ").any { it in name } -> LibraryKind.SERIES
        listOf("movie", "film", "电影", "電影", "映画").any { it in name } -> LibraryKind.MOVIE
        else -> null
    }
}

private val videoExtensions = setOf("mkv", "mp4", "m4v", "avi", "mov", "ts", "m2ts", "wmv", "flv", "webm", "rmvb", "iso")

private fun isVideoName(name: String) = name.substringAfterLast('.', "").lowercase() in videoExtensions
