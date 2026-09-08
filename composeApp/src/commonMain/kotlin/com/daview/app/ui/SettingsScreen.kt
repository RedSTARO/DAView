package com.daview.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.daview.app.data.AppState
import com.daview.app.data.Screen
import com.daview.app.platform.PlatformInfo
import com.daview.app.platform.copyToClipboard
import com.daview.app.platform.openUrl
import com.daview.app.platform.pickTextFile
import com.daview.shared.model.LibraryDto
import com.daview.shared.model.LibraryKind
import com.daview.shared.model.ScanMode
import com.daview.shared.model.ScraperSettingsDto
import com.daview.shared.model.StorageSettingsDto
import com.daview.shared.model.SyncResultDto
import com.daview.shared.model.SyncSettingsDto
import com.daview.shared.model.WebDavEntryDto
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsScreen(state: AppState) {
    var pickerOpen by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 48.dp)
    ) {
        item { SectionHeader("媒体库") }
        items(state.libraries, key = { it.id }) { library ->
            LibraryCard(state, library)
        }
        item {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Button(onClick = { pickerOpen = true }) {
                    Icon(Icons.Filled.Folder, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("从 WebDAV 添加媒体库")
                }
            }
        }

        item { HorizontalDivider(Modifier.padding(20.dp)) }
        item { StorageSection(state) }
        item { ScraperSection(state) }
        item { SyncSection(state) }
        item { BackupSection(state) }
        item { ClientSection(state) }
    }

    if (pickerOpen) {
        WebDavPickerDialog(state, onDismiss = { pickerOpen = false })
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LibraryCard(state: AppState, library: LibraryDto) {
    val status = state.scanStatus.firstOrNull { it.libraryId == library.id }
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 5.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    LinkText(
                        library.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        onClick = { state.navigate(Screen.Library(library.id)) }
                    )
                    Text(
                        "${kindLabel(library.kind)} · ${library.path} · ${library.itemCount} 项",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "刮削顺序: " + library.providerOrder.joinToString(" → ") { it.name.lowercase() },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Box {
                    var scanMenu by remember { mutableStateOf(false) }
                    IconButton(onClick = { scanMenu = true }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "扫描")
                    }
                    DropdownMenu(scanMenu, onDismissRequest = { scanMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("扫描文件并刮削新条目") },
                            onClick = { scanMenu = false; state.startScan(library.id, ScanMode.FULL) }
                        )
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text("仅刮削未刮削的条目")
                                    Text(
                                        "不走文件与容器探测，只补没有元数据的",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            onClick = { scanMenu = false; state.startScan(library.id, ScanMode.MISSING) }
                        )
                        DropdownMenuItem(
                            text = { Text("重新刮削全部") },
                            onClick = { scanMenu = false; state.startScan(library.id, ScanMode.REFRESH) }
                        )
                    }
                }
                IconButton(onClick = { state.deleteLibrary(library.id) }) {
                    Icon(Icons.Filled.Delete, contentDescription = "删除")
                }
            }
            if (status != null && status.running) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "${status.phase} ${status.current}/${status.total} · ${status.message}",
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(6.dp))
                LinearWavyProgressIndicator(
                    progress = { if (status.total > 0) status.current.toFloat() / status.total else 0f },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            status?.error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun StorageSection(state: AppState) {
    val settings = state.serverSettings ?: return
    var url by remember(settings) { mutableStateOf(settings.storage.url) }
    var user by remember(settings) { mutableStateOf(settings.storage.username) }
    var password by remember(settings) { mutableStateOf("") }

    Column(Modifier.padding(horizontal = 20.dp)) {
        Text("WebDAV 存储", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(url, { url = it }, label = { Text("地址") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(user, { user = it }, label = { Text("账号") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            password, { password = it },
            label = { Text(if (settings.storage.passwordSet) "密码（留空保持不变）" else "密码") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = {
            state.saveServerSettings(
                settings.copy(storage = StorageSettingsDto(url = url, username = user, password = password))
            )
            password = ""
        }) { Text("保存存储设置") }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ScraperSection(state: AppState) {
    val settings = state.serverSettings ?: return
    var tmdb by remember(settings) { mutableStateOf("") }
    var tvdb by remember(settings) { mutableStateOf("") }
    var bangumi by remember(settings) { mutableStateOf("") }
    var language by remember(settings) { mutableStateOf(settings.scraper.language) }

    Column(Modifier.padding(horizontal = 20.dp)) {
        Text("刮削源", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(
            "TMDB 与 TheTVDB 需要自行申请 API Key；bangumi.tv 无需密钥，填 Token 只是提高频率限制。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            tmdb, { tmdb = it },
            label = { Text(if (settings.scraper.tmdbApiKeySet) "TMDB API Key（已设置）" else "TMDB API Key") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            tvdb, { tvdb = it },
            label = { Text(if (settings.scraper.tvdbApiKeySet) "TheTVDB API Key（已设置）" else "TheTVDB API Key") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            bangumi, { bangumi = it },
            label = { Text(if (settings.scraper.bangumiTokenSet) "bangumi.tv Token（已设置）" else "bangumi.tv Token（可选）") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            language, { language = it },
            label = { Text("元数据语言") },
            supportingText = { Text("例如 zh-CN、zh-TW、ja-JP、en-US") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = {
            state.saveServerSettings(
                settings.copy(
                    scraper = ScraperSettingsDto(
                        tmdbApiKey = tmdb,
                        tvdbApiKey = tvdb,
                        bangumiToken = bangumi,
                        language = language
                    )
                )
            )
            tmdb = ""; tvdb = ""; bangumi = ""
        }) { Text("保存刮削设置") }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ClientSection(state: AppState) {
    Column(Modifier.padding(horizontal = 20.dp)) {
        Text("客户端", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("深色主题", Modifier.weight(1f))
            Switch(checked = state.darkTheme, onCheckedChange = { state.setTheme(it) })
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "服务器: ${state.serverUrl}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "平台: ${PlatformInfo.name}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        state.serverInfo?.let {
            Text(
                "服务端版本: ${it.version} · 共 ${it.itemCount} 个条目",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(12.dp))
        FilledTonalButton(onClick = { state.disconnect() }) { Text("断开连接") }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * Sync goes through the share itself: there is no second server to talk to, so
 * the devices agree through one file on the storage they already have in common.
 *
 * Whether that storage takes writes cannot be asked — the gateway leaves PUT out
 * of its OPTIONS response even when it honours it — so switching this on runs a
 * real upload, and a refusal turns the switch back off with the reason shown.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SyncSection(state: AppState) {
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf<SyncSettingsDto?>(null) }
    var path by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state.client) {
        val api = state.client ?: return@LaunchedEffect
        runCatching { api.syncSettings() }.onSuccess {
            settings = it
            path = it.remotePath
        }
    }

    fun apply(block: suspend (com.daview.shared.api.DaViewClient) -> Unit) {
        val api = state.client ?: return
        scope.launch {
            busy = true
            message = null
            error = null
            try {
                block(api)
            } catch (e: Throwable) {
                error = e.message ?: "请求失败"
            } finally {
                busy = false
            }
        }
    }

    fun show(result: SyncResultDto) {
        if (result.ok) message = result.message else error = result.message
    }

    Column(Modifier.padding(horizontal = 20.dp)) {
        Text("跨端同步", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            "把观看进度、媒体库定义与服务器设置写成一个文件放在 WebDAV 上，其它设备读回来合并。" +
                "同一条记录以时间较新的一方为准。不含刮削结果——那个每台设备扫描一次就有，" +
                "带上会让每次上传从几 KB 变成几 MB。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        val current = settings
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("启用同步", Modifier.weight(1f))
            Switch(
                checked = current?.enabled == true,
                enabled = current != null && !busy,
                onCheckedChange = { want ->
                    apply { api ->
                        val updated = api.updateSyncSettings(
                            (current ?: SyncSettingsDto()).copy(enabled = want, remotePath = path)
                        )
                        settings = updated
                        path = updated.remotePath
                        if (want && !updated.enabled) {
                            error = updated.lastError ?: "存储不接受写入，已保持关闭"
                        } else if (want) {
                            message = "同步已开启"
                        }
                    }
                }
            )
        }

        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = path,
            onValueChange = { path = it },
            label = { Text("WebDAV 上的同步文件路径") },
            supportingText = { Text("相对于 WebDAV 根目录，只有这个文件会被写入") },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                enabled = state.client != null && !busy,
                onClick = {
                    apply { api ->
                        settings = api.updateSyncSettings(
                            (settings ?: SyncSettingsDto()).copy(
                                enabled = settings?.enabled == true,
                                remotePath = path
                            )
                        )
                        show(api.syncUpload())
                        settings = api.syncSettings()
                    }
                }
            ) { Text("立即上传") }

            FilledTonalButton(
                enabled = state.client != null && !busy,
                onClick = {
                    apply { api ->
                        show(api.syncPull())
                        settings = api.syncSettings()
                        state.refreshLibraries()
                        state.refreshHome()
                    }
                }
            ) { Text("从云端合并") }
        }

        if (busy) {
            Spacer(Modifier.height(8.dp))
            LinearWavyProgressIndicator(Modifier.fillMaxWidth())
        }
        current?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                listOfNotNull(
                    it.lastUploadAt?.let { _ -> "已上传" } ?: "尚未上传",
                    it.lastPullAt?.let { _ -> "已合并过云端记录" },
                    when (it.writable) {
                        true -> "存储可写"
                        false -> "存储只读，无法同步"
                        null -> null
                    }
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        message?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        (error ?: current?.lastError)?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(20.dp))
    }
}

/**
 * Migration is two steps: download the file here, drop it into the new server's
 * data directory as `import.json`, press import there. No file picker is
 * involved, which keeps it identical on desktop, web and Android.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BackupSection(state: AppState) {
    val scope = rememberCoroutineScope()
    var includeItems by remember { mutableStateOf(true) }
    var includeSecrets by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(Modifier.padding(horizontal = 20.dp)) {
        Text("备份与迁移", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            "导出一个 JSON：服务器设置、媒体库定义、观看进度（位置 / 已看 / 收藏 / 音轨字幕选择），" +
                "以及可选的整份刮削结果。在新机器上点「导入」选中这个文件即可。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ToggleButton(checked = includeItems, onCheckedChange = { includeItems = it }) {
                Text("包含刮削数据")
            }
            ToggleButton(checked = includeSecrets, onCheckedChange = { includeSecrets = it }) {
                Text("包含凭据")
            }
        }
        if (includeSecrets) {
            Spacer(Modifier.height(6.dp))
            Text(
                "文件里会明文带上 WebDAV 密码与 API Key。不勾选的话，导入方保留自己已有的凭据。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        if (!includeItems) {
            Spacer(Modifier.height(6.dp))
            Text(
                "不含刮削数据时文件只有几十 KB，但新服务端需要重新扫描一遍；" +
                    "手动指定的刮削 id 也在刮削数据里。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                enabled = state.client != null && !busy,
                onClick = {
                    val api = state.client ?: return@Button
                    val url = api.backupUrl(items = includeItems, secrets = includeSecrets)
                    openUrl(url)
                    error = null
                    message = "已打开下载地址"
                }
            ) { Text("导出") }

            FilledTonalButton(
                enabled = state.client != null && !busy,
                onClick = {
                    val api = state.client ?: return@FilledTonalButton
                    copyToClipboard(api.backupUrl(items = includeItems, secrets = includeSecrets))
                    error = null
                    message = "下载地址已复制，可以用 curl -o backup.json 取"
                }
            ) { Text("复制地址") }

            FilledTonalButton(
                enabled = state.client != null && !busy,
                onClick = {
                    val api = state.client ?: return@FilledTonalButton
                    scope.launch {
                        error = null
                        message = null
                        // The chooser runs before the busy flag so the progress
                        // bar does not sit there while the user browses.
                        val content = runCatching { pickTextFile() }.getOrNull()
                        if (content.isNullOrBlank()) return@launch
                        busy = true
                        try {
                            val summary = api.importBackup(content)
                            message = "已导入：媒体库 ${summary.libraries} 个、条目 ${summary.items} 项、" +
                                "观看记录 ${summary.userData} 条" +
                                (if (summary.settingsApplied) "，设置已应用" else "")
                            state.refreshLibraries()
                            state.loadServerSettings()
                        } catch (e: Throwable) {
                            error = e.message ?: "导入失败"
                        } finally {
                            busy = false
                        }
                    }
                }
            ) { Text("导入…") }
        }

        if (busy) {
            Spacer(Modifier.height(8.dp))
            LinearWavyProgressIndicator(Modifier.fillMaxWidth())
        }
        message?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(20.dp))
    }
}

private fun kindLabel(kind: LibraryKind) = when (kind) {
    LibraryKind.MOVIE -> "电影"
    LibraryKind.SERIES -> "电视剧"
    LibraryKind.ANIME -> "番剧"
    LibraryKind.OTHER -> "其他"
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun WebDavPickerDialog(state: AppState, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var path by remember { mutableStateOf("/") }
    var entries by remember { mutableStateOf<List<WebDavEntryDto>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(LibraryKind.MOVIE) }

    fun load(target: String) {
        val api = state.client ?: return
        scope.launch {
            loading = true
            error = null
            try {
                entries = api.browseStorage(target)
                path = target
                name = target.trim('/').substringAfterLast('/')
            } catch (e: Throwable) {
                error = e.message
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { load("/") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择媒体库目录") },
        text = {
            Column(Modifier.heightIn(max = 460.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { load("/" + path.trim('/').substringBeforeLast('/', "")) },
                        enabled = path.trim('/').isNotEmpty()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "上一级")
                    }
                    Text(path, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (loading) {
                    LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                }
                LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 240.dp)) {
                    items(entries.filter { it.isDirectory }, key = { it.path }) { entry ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                                .clickable { load(entry.path) }
                        ) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Folder, contentDescription = null)
                                Spacer(Modifier.width(10.dp))
                                Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    name, { name = it },
                    label = { Text("媒体库名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LibraryKind.entries.forEach { option ->
                        ToggleButton(checked = kind == option, onCheckedChange = { kind = option }) {
                            Text(kindLabel(option))
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = path.trim('/').isNotEmpty() && name.isNotBlank(),
                onClick = {
                    state.createLibrary(
                        LibraryDto(id = "", name = name, kind = kind, path = path, language = "zh-CN")
                    ) { onDismiss() }
                }
            ) { Text("创建并扫描") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
