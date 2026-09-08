package com.daview.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import com.daview.app.platform.PlatformInfo
import com.daview.shared.model.LibraryDto
import com.daview.shared.model.LibraryKind
import com.daview.shared.model.ScraperSettingsDto
import com.daview.shared.model.StorageSettingsDto
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
                    Text(library.name, style = MaterialTheme.typography.titleMedium)
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
                IconButton(onClick = { state.startScan(library.id) }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "扫描")
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
