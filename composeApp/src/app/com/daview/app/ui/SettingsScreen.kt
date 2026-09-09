package com.daview.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Checkbox
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.daview.app.platform.pickTextFile
import com.daview.app.platform.saveTextFile
import com.daview.server.api.BackupOptions
import com.daview.server.api.backupFileName
import com.daview.shared.api.DaViewJson
import com.daview.shared.model.BackupFileDto
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
                    Text("添加媒体库…")
                }
            }
        }

        item { HorizontalDivider(Modifier.padding(20.dp)) }
        item { StorageSection(state) }
        item { ScraperSection(state) }
        item { SyncSection(state) }
        item { BackupSection(state) }
        item { PlatformPlayerSettings() }
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
                        "刮削顺序: " + library.providerOrder.joinToString(" → ") { it.displayName },
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
                var confirmDelete by remember { mutableStateOf(false) }
                IconButton(onClick = { confirmDelete = true }) {
                    Icon(Icons.Filled.Delete, contentDescription = "删除媒体库")
                }
                if (confirmDelete) {
                    // This button sits next to the scan button and used to fire
                    // straight through, taking every scraped record and every
                    // watch position in the library with it.
                    AlertDialog(
                        onDismissRequest = { confirmDelete = false },
                        title = { Text("删除媒体库「${library.name}」？") },
                        text = {
                            Text(
                                "本机这个库的 ${library.itemCount} 条刮削结果、观看进度和收藏都会被清除，" +
                                    "网盘上的文件不受影响。重新添加后需要再扫描一次。"
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                confirmDelete = false
                                state.deleteLibrary(library.id)
                            }) {
                                Text("删除", color = MaterialTheme.colorScheme.error)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirmDelete = false }) { Text("取消") }
                        }
                    )
                }
            }
            if (status != null && status.running) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${scanPhaseLabel(status.phase)} ${status.current}/${status.total} · ${status.message}",
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    // A scan of a large share is minutes of round trips; starting
                    // one by accident should not mean waiting it out.
                    TextButton(onClick = { state.cancelScan(library.id) }) { Text("取消") }
                }
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

/** A section heading, so the six of them stop each spelling it out again. */
@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
}

/**
 * A label and a switch that answer to the whole row.
 *
 * Written as a bare Row the switch was the only live part: 52dp of a 360dp row,
 * with the label inert and the row invisible to a screen reader, which read the
 * switch alone and could not say what it switched.
 */
@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/**
 * A field whose contents should not be read over a shoulder, or learnt by the
 * phone keyboard.
 *
 * The WebDAV password and the three scraper keys were plain fields: shown in
 * full while typed, and offered to the IME for prediction and its dictionary.
 * The eye is here because these are pasted more often than typed, and a paste
 * that silently went wrong is worse than a briefly visible secret.
 */
@Composable
private fun SecretField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value,
        onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation =
            if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = imeAction
        ),
        keyboardActions = KeyboardActions(
            onNext = { onImeAction() },
            onDone = { onImeAction() }
        ),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (visible) "隐藏" else "显示"
                )
            }
        },
        modifier = modifier
    )
}

@Composable
private fun StorageSection(state: AppState) {
    val settings = state.serverSettings ?: return
    var url by remember(settings) { mutableStateOf(settings.storage.url) }
    var user by remember(settings) { mutableStateOf(settings.storage.username) }
    var password by remember(settings) { mutableStateOf("") }

    fun save() {
        state.saveServerSettings(
            settings.copy(storage = StorageSettingsDto(url = url, username = user, password = password))
        )
        password = ""
    }

    Column(Modifier.padding(horizontal = 20.dp)) {
        SectionTitle("WebDAV 存储")
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            url, { url = it },
            label = { Text("地址") },
            singleLine = true,
            // Next rather than the default Done on every field: filling this
            // form closed the keyboard after each line and needed another tap.
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Next
            ),
            supportingText = if (url.startsWith("http://")) {
                {
                    Text(
                        "http 是明文：这里填的账号密码会以可还原的形式在网络上传输。",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            } else null,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            user, { user = it },
            label = { Text("账号") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        SecretField(
            password, { password = it },
            label = if (settings.storage.passwordSet) "密码（留空保持不变）" else "密码",
            imeAction = ImeAction.Done,
            onImeAction = { save() },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = { save() }) { Text("保存存储设置") }
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

    fun save() {
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
        tmdb = ""
        tvdb = ""
        bangumi = ""
    }

    Column(Modifier.padding(horizontal = 20.dp)) {
        SectionTitle("刮削源")
        Spacer(Modifier.height(4.dp))
        Text(
            "TMDB 与 TheTVDB 需要自行申请 API Key；bangumi.tv 无需密钥，填 Token 只是提高频率限制。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        SecretField(
            tmdb, { tmdb = it },
            label = if (settings.scraper.tmdbApiKeySet) "TMDB API Key（已设置）" else "TMDB API Key",
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        SecretField(
            tvdb, { tvdb = it },
            label = if (settings.scraper.tvdbApiKeySet) "TheTVDB API Key（已设置）" else "TheTVDB API Key",
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        SecretField(
            bangumi, { bangumi = it },
            label = if (settings.scraper.bangumiTokenSet) "bangumi.tv Token（已设置）" else "bangumi.tv Token（可选）",
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        // Free text accepted "ja" or "ja_JP" and then quietly returned English.
        // These four are what the scrapers are actually asked for.
        LanguageField(language) { language = it }
        Spacer(Modifier.height(12.dp))
        Button(onClick = { save() }) { Text("保存刮削设置") }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ClientSection(state: AppState) {
    Column(Modifier.padding(horizontal = 20.dp)) {
        SectionTitle("客户端")
        Spacer(Modifier.height(8.dp))
        SwitchRow("深色主题", state.darkTheme) { state.setTheme(it) }
        SwitchRow("播完自动播下一集", state.autoPlayNext) { state.setAutoPlay(it) }
        Spacer(Modifier.height(8.dp))
        ImageCacheRow(state)
        Spacer(Modifier.height(8.dp))
        Text(
            "平台: ${PlatformInfo.name}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        state.serverInfo?.let {
            Text(
                "版本 ${it.version} · 共 ${it.itemCount} 个条目",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            "媒体库在本机，没有需要连接的服务器",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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

    LaunchedEffect(Unit) {
        runCatching { state.library.syncSettings() }.onSuccess {
            settings = it
            path = it.remotePath
        }
    }

    fun apply(block: suspend () -> Unit) {
        scope.launch {
            busy = true
            message = null
            error = null
            try {
                block()
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
        SectionTitle("跨端同步")
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
        SwitchRow(
            label = "启用同步",
            checked = current?.enabled == true,
            enabled = current != null && !busy,
            onCheckedChange = { want ->
                    apply {
                        val updated = state.library.updateSyncSettings(
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
                enabled = !busy,
                onClick = {
                    apply {
                        settings = state.library.updateSyncSettings(
                            (settings ?: SyncSettingsDto()).copy(
                                enabled = settings?.enabled == true,
                                remotePath = path
                            )
                        )
                        show(state.library.syncUpload())
                        settings = state.library.syncSettings()
                    }
                }
            ) { Text("立即上传") }

            FilledTonalButton(
                enabled = !busy,
                onClick = {
                    apply {
                        show(state.library.syncPull())
                        settings = state.library.syncSettings()
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
        SectionTitle("备份与迁移")
        Spacer(Modifier.height(8.dp))
        Text(
            "导出一个 JSON：服务器设置、媒体库定义、观看进度（位置 / 已看 / 收藏 / 音轨字幕选择），" +
                "以及可选的整份刮削结果。在新机器上点「导入」选中这个文件即可。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(4.dp))
        // Checkboxes, not filled buttons. Selected, these two looked exactly
        // like the "导出" button ten dp below them — two states and two actions
        // in one visual register.
        CheckboxRow("包含刮削数据", includeItems) { includeItems = it }
        CheckboxRow("包含凭据", includeSecrets) { includeSecrets = it }
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
                enabled = !busy,
                onClick = {
                    scope.launch {
                        error = null
                        message = null
                        // Streamed into the file the user picked: a full
                        // catalogue is a few megabytes, and it is assembled a
                        // page at a time rather than held in memory first.
                        val chunks = state.library.backupChunks(
                            BackupOptions(items = includeItems, secrets = includeSecrets)
                        )
                        val saved = runCatching {
                            saveTextFile(backupFileName()) { out -> chunks.forEach(out::append) }
                        }.getOrNull()
                        message = saved?.let { "已导出到 $it" }
                        if (saved == null) error = "没有写入文件"
                    }
                }
            ) { Text("导出…") }

            FilledTonalButton(
                enabled = !busy,
                onClick = {
                    scope.launch {
                        error = null
                        message = null
                        // The chooser runs before the busy flag so the progress
                        // bar does not sit there while the user browses.
                        val content = runCatching { pickTextFile() }.getOrNull()
                        if (content.isNullOrBlank()) return@launch
                        busy = true
                        try {
                            val summary = state.library.importBackup(
                                DaViewJson.decodeFromString(BackupFileDto.serializer(), content)
                            )
                            message = "已导入：媒体库 ${summary.libraries} 个、条目 ${summary.items} 项、" +
                                "观看记录 ${summary.userData} 条、手动指定 ${summary.pins} 条" +
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

/** A label and a checkbox that answer to the whole row, as [SwitchRow] does. */
@Composable
private fun CheckboxRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(Modifier.width(12.dp))
        Text(label)
    }
}

/**
 * The metadata language, as the four values the scrapers are actually asked
 * for. It was a free-text box whose supporting text listed these same four, and
 * a near miss ("ja", "ja_JP") is not rejected anywhere — TMDB simply answers in
 * English and nothing says why.
 */
@Composable
private fun LanguageField(value: String, onChange: (String) -> Unit) {
    val options = listOf(
        "zh-CN" to "简体中文",
        "zh-TW" to "繁体中文",
        "ja-JP" to "日语",
        "en-US" to "英语"
    )
    Column {
        Text(
            "元数据语言",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (code, label) ->
                SegmentedButton(
                    selected = value == code,
                    onClick = { onChange(code) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size)
                ) {
                    Text(label, maxLines = 1)
                }
            }
        }
    }
}

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
    "scraping" -> "刮削"
    "probing" -> "解析容器"
    "done" -> "完成"
    "cancelled" -> "已取消"
    "error" -> "出错"
    else -> phase
}

/** What the artwork cache is holding, and a way to be rid of it. */
@Composable
private fun ImageCacheRow(state: AppState) {
    var bytes by remember { mutableStateOf<Long?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { bytes = state.library.imageCacheBytes() }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("图片缓存")
            Text(
                bytes?.let { "海报与背景图占用 ${formatSize(it)}" } ?: "统计中…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(
            enabled = !busy && (bytes ?: 0L) > 0L,
            onClick = {
                scope.launch {
                    busy = true
                    state.library.clearImageCache()
                    bytes = state.library.imageCacheBytes()
                    busy = false
                    state.notify("图片缓存已清除")
                }
            }
        ) { Text("清除") }
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
        scope.launch {
            loading = true
            error = null
            try {
                entries = state.library.browseStorage(target)
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
            Column(Modifier.verticalScroll(rememberScrollState())) {
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
                Column {
                    entries.filter { it.isDirectory }.forEach { entry ->
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
                        LibraryDto(id = "", name = name, kind = kind, path = path, language = "")
                    ) { onDismiss() }
                }
            ) { Text("创建并扫描") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
