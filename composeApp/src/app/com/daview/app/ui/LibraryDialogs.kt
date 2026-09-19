package com.daview.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.DriveFolderUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    "listing" -> "读取目录"
    "scanning" -> "扫描"
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
    ScanOption("扫描新增文件", "读取目录的变化，只为新条目获取元数据。平时用这个") {
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
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑媒体库") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "目录：${library.path}（目录不能改：观看记录都挂在由它算出的 id 上。换目录请新建媒体库）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    name, { name = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().tracksTextInput()
                )
                Spacer(Modifier.height(12.dp))
                Text("类型", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LibraryKind.entries.forEach { option ->
                        ToggleButton(checked = kind == option, onCheckedChange = { kind = option }) {
                            Text(kindLabel(option))
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("元数据来源（从上到下依次尝试）", style = MaterialTheme.typography.labelLarge)
                order.forEachIndexed { index, (provider, enabled) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = enabled,
                            onCheckedChange = { on ->
                                order = order.toMutableList().also { it[index] = provider to on }
                            }
                        )
                        Column(Modifier.weight(1f)) {
                            Text(provider.displayName)
                            if (!configured(provider)) {
                                Text(
                                    "未填写 API Key，不会被使用",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        IconButton(enabled = index > 0, onClick = {
                            order = order.toMutableList().also { list -> list.add(index - 1, list.removeAt(index)) }
                        }) { Icon(Icons.Filled.ArrowUpward, contentDescription = "上移") }
                        IconButton(enabled = index < order.lastIndex, onClick = {
                            order = order.toMutableList().also { list -> list.add(index + 1, list.removeAt(index)) }
                        }) { Icon(Icons.Filled.ArrowDownward, contentDescription = "下移") }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("元数据语言", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (listOf("" to "跟随设置") + METADATA_LANGUAGES).forEach { (code, label) ->
                        ToggleButton(checked = language == code, onCheckedChange = { language = code }) {
                            Text(label)
                        }
                    }
                }
                if (scrapingChanged) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "改了类型、来源或语言，已有条目要重新获取元数据才会用上新设置。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
            ) { Text(if (scrapingChanged) "保存并重新获取" else "保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
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
 * The path and the name and kind stay put while the folder list scrolls between
 * them — they used to all scroll together, so with a long list the name field
 * was at the bottom of it and the path had scrolled away. The folder that will
 * be added is said in so many words, and a name typed by hand is not replaced
 * the moment another folder is opened.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
fun WebDavPickerDialog(state: AppState, onDismiss: () -> Unit) {
    ModalMarker()
    val scope = rememberCoroutineScope()
    var path by remember { mutableStateOf("/") }
    var entries by remember { mutableStateOf<List<WebDavEntryDto>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var nameEdited by remember { mutableStateOf(false) }
    var kind by remember { mutableStateOf(LibraryKind.MOVIE) }
    var kindEdited by remember { mutableStateOf(false) }

    fun load(target: String) {
        scope.launch {
            loading = true
            error = null
            try {
                entries = state.library.browseStorage(target)
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加媒体库") },
        text = {
            // Scrolls as a whole on a short window: the name and the kind below
            // the list were pushed out of reach on a phone held sideways.
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Tooltip("回到根目录") {
                        TextButton(onClick = { load("/") }, enabled = !atRoot && !loading) {
                            Icon(Icons.Filled.Home, contentDescription = "回到根目录")
                        }
                    }
                    TextButton(
                        onClick = { load("/" + path.trim('/').substringBeforeLast('/', "")) },
                        enabled = !atRoot && !loading
                    ) {
                        Icon(Icons.Filled.DriveFolderUpload, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("上一级")
                    }
                }
                Text(
                    path,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "这一层：${folders.size} 个子目录" + if (videos > 0) "，$videos 个视频文件" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (loading) LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
                    items(folders, key = { it.path }) { entry ->
                        Surface(
                            onClick = { if (!loading) load(entry.path) },
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                        ) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Folder, contentDescription = null)
                                Spacer(Modifier.width(10.dp))
                                Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(
                    if (atRoot) "进入要添加的目录。" else "将添加：$path",
                    style = MaterialTheme.typography.labelLarge
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    name, { name = it; nameEdited = true },
                    label = { Text("媒体库名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().tracksTextInput()
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LibraryKind.entries.forEach { option ->
                        ToggleButton(checked = kind == option, onCheckedChange = { kind = option; kindEdited = true }) {
                            Text(kindLabel(option))
                        }
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
            ) { Text("添加并扫描") }
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
