package com.daview.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.daview.app.data.AppState
import com.daview.app.data.DesktopShortcuts
import com.daview.app.data.ModalMarker
import com.daview.app.data.ResumeBehavior
import com.daview.app.data.Screen
import com.daview.app.data.ThemeMode
import com.daview.app.data.tracksTextInput
import com.daview.app.platform.PlatformInfo
import com.daview.app.platform.availableExternalPlayers
import com.daview.app.platform.pickDirectory
import com.daview.app.platform.pickTextFile
import com.daview.app.platform.revealInFileManager
import com.daview.app.platform.saveTextFile
import com.daview.server.api.BackupOptions
import com.daview.server.api.backupFileName
import com.daview.shared.api.DaViewJson
import com.daview.shared.model.BackupFileDto
import com.daview.shared.model.DownloadDto
import com.daview.shared.model.DownloadState
import com.daview.shared.model.LibraryDto
import com.daview.shared.model.PREFERRED_LANGUAGES
import com.daview.shared.model.SUBTITLE_PREF_OFF
import com.daview.shared.model.ScraperSettingsDto
import com.daview.shared.model.StorageSettingsDto
import com.daview.shared.model.SyncResultDto
import com.daview.shared.model.SyncSettingsDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The settings, in groups. They were one page of eight sections, the connection
 * to the share below the list of libraries that could not be added without it.
 */
private enum class SettingsTab(val label: String) {
    LIBRARIES("媒体库"),
    STORAGE("存储与刮削"),
    PLAYBACK("播放"),
    SYNC("同步与备份"),
    OFFLINE("离线内容"),
    GENERAL("通用")
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsScreen(state: AppState) {
    val storageReady = state.serverInfo?.storageConfigured == true
    // Somebody with no share configured has one thing to do first.
    var tab by rememberSaveable { mutableStateOf(if (storageReady) SettingsTab.LIBRARIES else SettingsTab.STORAGE) }
    var pendingTab by remember { mutableStateOf<SettingsTab?>(null) }
    val scroll = rememberScrollState()
    LaunchedEffect(tab) { scroll.scrollTo(0) }

    pendingTab?.let { next ->
        ModalMarker()
        AlertDialog(
            onDismissRequest = { pendingTab = null },
            title = { Text("有未保存的设置") },
            text = { Text("切到「${next.label}」后，这一组里改了但没保存的内容会丢失。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingTab = null
                    tab = next
                }) { Text("放弃修改", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingTab = null }) { Text("继续编辑") } }
        )
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(title = "设置", onBack = if (state.canGoBack) ({ state.back() }) else null)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SettingsTab.entries.forEach { entry ->
                FilterChip(
                    selected = tab == entry,
                    // Moving to another group drops this one's unsaved edits,
                    // so it asks first, as leaving the page does.
                    onClick = { if (state.unsavedSettings && entry != tab) pendingTab = entry else tab = entry },
                    label = { Text(entry.label) }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxSize()) {
            Column(
                // Edge to edge means adjustResize no longer moves the window, so a
                // field in the lower third of this page sat under the soft keyboard
                // with no way to scroll it into view.
                Modifier
                    .fillMaxSize()
                    .imePadding()
                    .verticalScroll(scroll)
                    .padding(bottom = 32.dp)
            ) {
                // Forms read best at a form's width, not at the window's.
                Column(Modifier.fillMaxWidth().wrapContentWidth(Alignment.Start).widthIn(max = 840.dp)) {
                    when (tab) {
                        SettingsTab.LIBRARIES -> LibrariesTab(state, storageReady) { tab = SettingsTab.STORAGE }
                        SettingsTab.STORAGE -> {
                            StorageSection(state)
                            ScraperSection(state)
                        }
                        SettingsTab.PLAYBACK -> {
                            PlaybackSection(state)
                            PlatformPlayerSettings()
                        }
                        SettingsTab.SYNC -> {
                            SyncSection(state)
                            BackupSection(state)
                        }
                        SettingsTab.OFFLINE -> DownloadsSection(state)
                        SettingsTab.GENERAL -> GeneralSection(state)
                    }
                }
            }
            VerticalScrollbarFor(scroll, Modifier.align(Alignment.CenterEnd).padding(vertical = 8.dp))
        }
    }
}

// ---------------------------------------------------------------- libraries

@Composable
private fun LibrariesTab(state: AppState, storageReady: Boolean, openStorage: () -> Unit) {
    var pickerOpen by remember { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 20.dp)) {
        if (!storageReady) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("还没有连接 WebDAV", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "媒体库是 WebDAV 上的目录，要先在「存储与刮削」里填写地址和账号。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = openStorage) { Text("去填写") }
                }
            }
        }
        if (state.libraries.isEmpty() && storageReady) {
            Text(
                "还没有媒体库。添加一个目录，DAView 会扫描里面的视频并获取海报和简介。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }
    }
    state.libraries.forEach { library -> LibraryCard(state, library) }
    Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Button(onClick = { pickerOpen = true }, enabled = storageReady) {
            Icon(Icons.Filled.Folder, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("添加媒体库…")
        }
    }
    if (pickerOpen) {
        WebDavPickerDialog(state, onDismiss = { pickerOpen = false })
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LibraryCard(state: AppState, library: LibraryDto) {
    val status = state.scanStatus.firstOrNull { it.libraryId == library.id }
    var editOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 5.dp),
        // surfaceContainerLow sits 1.05:1 from the page behind it, which with
        // no elevation and no outline is an invisible card edge.
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    LinkText(
                        library.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        onClick = { state.switchTo(Screen.Library(library.id)) }
                    )
                    Text(
                        "${kindLabel(library.kind)} · ${library.path} · ${library.itemCount} 项",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "元数据来源：" + library.providerOrder.joinToString(" → ") { it.displayName } +
                            (library.lastScanAt?.let { " · 上次扫描 ${formatAgo(it)}" } ?: " · 还没有扫描过"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Box {
                    var scanMenu by remember { mutableStateOf(false) }
                    TipIconButton("扫描", onClick = { scanMenu = true }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "扫描")
                    }
                    AppMenu(scanMenu, onDismissRequest = { scanMenu = false }) {
                        ScanMenuItems(state, library.id) { scanMenu = false }
                    }
                }
                TipIconButton("编辑媒体库", onClick = { editOpen = true }) {
                    Icon(Icons.Filled.Edit, contentDescription = "编辑媒体库")
                }
                TipIconButton("删除媒体库", onClick = { confirmDelete = true }) {
                    Icon(Icons.Filled.Delete, contentDescription = "删除媒体库")
                }
            }
            if (status != null && status.running) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${scanPhaseLabel(status.phase)}" +
                            (if (status.total > 0) " ${status.current}/${status.total}" else "") +
                            (status.message.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    // A scan of a large share is minutes of round trips; starting
                    // one by accident should not mean waiting it out.
                    TextButton(onClick = { state.cancelScan(library.id) }) { Text("停止") }
                }
                Spacer(Modifier.height(6.dp))
                // Until the scan knows how much there is, it says it is working
                // rather than sitting at 0%.
                if (status.total > 0) {
                    LinearWavyProgressIndicator(
                        progress = { status.current.toFloat() / status.total },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            status?.error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (editOpen) LibraryEditDialog(state, library, onDismiss = { editOpen = false })
    if (confirmDelete) {
        ModalMarker()
        // This button sits next to the scan button and used to fire straight
        // through, taking every scraped record and every watch position in
        // the library with it.
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除媒体库「${library.name}」？") },
            text = {
                Text(
                    "本机这个库的 ${library.itemCount} 条元数据、观看进度和收藏都会被清除，" +
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

// ---------------------------------------------------------------- building blocks

/** A section heading, so the sections stop each spelling it out again. */
@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)
    )
}

@Composable
private fun Hint(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    )
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
    detail: String? = null,
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
        Column(Modifier.weight(1f)) {
            Text(label)
            detail?.let { Hint(it) }
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** A labelled single choice from a short list, as a segmented row. */
@Composable
private fun <T> ChoiceRow(label: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (value, text) ->
                SegmentedButton(
                    selected = value == selected,
                    onClick = { onSelect(value) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size)
                ) {
                    Text(text, maxLines = 1)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipChoiceRow(label: String, options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (value, text) ->
                FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(text) })
            }
        }
    }
}

/** Tells the app while this form holds edits nobody saved, so leaving can ask first. */
@Composable
private fun DirtyTracker(state: AppState, section: String, dirty: Boolean) {
    DisposableEffect(section, dirty) {
        state.markSettingsDirty(section, dirty)
        onDispose { state.markSettingsDirty(section, false) }
    }
}

/**
 * A field whose contents should not be read over a shoulder, or learnt by the
 * phone keyboard. The eye is here because these are pasted more often than
 * typed, and a paste that silently went wrong is worse than a briefly visible
 * secret.
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
        modifier = modifier.tracksTextInput()
    )
}

/**
 * What is stored for one credential, and the two things to do with it: read it
 * back, or forget it. Stored keys were write-only and could not be removed.
 */
@Composable
private fun StoredSecret(state: AppState, key: String, label: String, isSet: Boolean, onClear: () -> Unit) {
    if (!isSet) return
    val scope = rememberCoroutineScope()
    var shown by remember { mutableStateOf<String?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            shown?.let { "$label：$it" } ?: "$label 已保存",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = {
            if (shown != null) shown = null
            else scope.launch { shown = state.library.storedSecrets()[key].orEmpty() }
        }) { Text(if (shown != null) "隐藏" else "查看") }
        TextButton(onClick = onClear) { Text("清除", color = MaterialTheme.colorScheme.error) }
    }
}

// ---------------------------------------------------------------- storage and scrapers

@Composable
private fun StorageSection(state: AppState) {
    val settings = state.serverSettings ?: return
    val scope = rememberCoroutineScope()
    // Keyed on the storage part alone: saving anything else on the page —
    // the metadata language, a key — rebuilt this form and threw away what
    // had been typed into it.
    var url by remember(settings.storage) { mutableStateOf(settings.storage.url) }
    var user by remember(settings.storage) { mutableStateOf(settings.storage.username) }
    var password by remember(settings.storage) { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val dirty = url != settings.storage.url || user != settings.storage.username || password.isNotEmpty()
    DirtyTracker(state, "storage", dirty)

    fun save() {
        state.saveServerSettings(
            settings.copy(storage = StorageSettingsDto(url = url, username = user, password = password))
        )
        password = ""
    }

    Column(Modifier.padding(horizontal = 20.dp)) {
        SectionTitle("WebDAV 存储")
        Hint("媒体库都是这里的目录。填好后先测试连接，再保存。")
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            url, { url = it; testResult = null },
            label = { Text("地址") },
            placeholder = { Text("https://example.com/webdav") },
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
            modifier = Modifier.fillMaxWidth().tracksTextInput()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            user, { user = it; testResult = null },
            label = { Text("账号") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth().tracksTextInput()
        )
        Spacer(Modifier.height(8.dp))
        SecretField(
            password, { password = it; testResult = null },
            label = if (settings.storage.passwordSet) "新密码（留空保持不变）" else "密码",
            imeAction = ImeAction.Done,
            onImeAction = { save() },
            modifier = Modifier.fillMaxWidth()
        )
        StoredSecret(state, "password", "密码", settings.storage.passwordSet) { confirmClear = true }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            // Asked of the share with what is in the boxes now, saved or not.
            FilledTonalButton(
                enabled = url.isNotBlank() && !testing,
                onClick = {
                    scope.launch {
                        testing = true
                        testResult = try {
                            val entries = state.library.testStorage(StorageSettingsDto(url = url, username = user, password = password))
                            true to "连接成功：根目录下有 ${entries.size} 项"
                        } catch (e: Throwable) {
                            false to "连接失败：${state.describe(e)}"
                        } finally {
                            testing = false
                        }
                    }
                }
            ) { Text(if (testing) "正在测试…" else "测试连接") }
            Button(enabled = dirty, onClick = { save() }) { Text("保存") }
            if (dirty) Hint("有未保存的修改")
        }
        testResult?.let { (ok, message) ->
            Spacer(Modifier.height(6.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
        }
        Spacer(Modifier.height(24.dp))
    }
    if (confirmClear) {
        ModalMarker()
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清除保存的 WebDAV 密码？") },
            text = { Text("清除后无法访问网盘，直到重新填写。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    state.saveServerSettings(settings.copy(storage = settings.storage.copy(password = "", clearPassword = true)))
                }) { Text("清除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun ScraperSection(state: AppState) {
    val settings = state.serverSettings ?: return
    // Emptied by save() itself; keyed on nothing, so clearing one key or saving
    // the storage form does not empty the others.
    var tmdb by remember { mutableStateOf("") }
    var tvdb by remember { mutableStateOf("") }
    var bangumi by remember { mutableStateOf("") }
    val dirty = tmdb.isNotEmpty() || tvdb.isNotEmpty() || bangumi.isNotEmpty()
    DirtyTracker(state, "scraper", dirty)

    fun save(scraper: ScraperSettingsDto = settings.scraper.copy(tmdbApiKey = tmdb, tvdbApiKey = tvdb, bangumiToken = bangumi)) {
        state.saveServerSettings(settings.copy(scraper = scraper))
        tmdb = ""
        tvdb = ""
        bangumi = ""
    }

    Column(Modifier.padding(horizontal = 20.dp)) {
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        SectionTitle("元数据来源")
        Hint("海报、简介和演职人员从这些网站获取。TMDB 与 TheTVDB 需要自己申请 API Key；bangumi.tv 不需要，填 Token 只是提高访问频率。")
        Spacer(Modifier.height(12.dp))
        SecretField(
            tmdb, { tmdb = it },
            label = if (settings.scraper.tmdbApiKeySet) "新的 TMDB API Key（留空保持不变）" else "TMDB API Key",
            modifier = Modifier.fillMaxWidth()
        )
        StoredSecret(state, "tmdb", "TMDB API Key", settings.scraper.tmdbApiKeySet) {
            save(settings.scraper.copy(clearTmdbApiKey = true))
        }
        Spacer(Modifier.height(8.dp))
        SecretField(
            tvdb, { tvdb = it },
            label = if (settings.scraper.tvdbApiKeySet) "新的 TheTVDB API Key（留空保持不变）" else "TheTVDB API Key",
            modifier = Modifier.fillMaxWidth()
        )
        StoredSecret(state, "tvdb", "TheTVDB API Key", settings.scraper.tvdbApiKeySet) {
            save(settings.scraper.copy(clearTvdbApiKey = true))
        }
        Spacer(Modifier.height(8.dp))
        SecretField(
            bangumi, { bangumi = it },
            label = if (settings.scraper.bangumiTokenSet) "新的 bangumi.tv Token（留空保持不变）" else "bangumi.tv Token（可选）",
            imeAction = ImeAction.Done,
            onImeAction = { if (dirty) save() },
            modifier = Modifier.fillMaxWidth()
        )
        StoredSecret(state, "bangumi", "bangumi.tv Token", settings.scraper.bangumiTokenSet) {
            save(settings.scraper.copy(clearBangumiToken = true))
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(enabled = dirty, onClick = { save() }) { Text("保存密钥") }
            if (dirty) Hint("有未保存的修改")
        }
        Spacer(Modifier.height(12.dp))
        // A choice among four, which takes effect as it is made — it looked like
        // it did, and used to wait for a save button further down.
        ChoiceRow(
            label = "元数据语言（媒体库可以单独设置）",
            options = METADATA_LANGUAGES,
            selected = settings.scraper.language
        ) { language ->
            state.saveServerSettings(settings.copy(scraper = settings.scraper.copy(language = language, tmdbApiKey = "", tvdbApiKey = "", bangumiToken = "")))
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ---------------------------------------------------------------- playback

@Composable
private fun PlaybackSection(state: AppState) {
    Column(Modifier.padding(horizontal = 20.dp)) {
        SectionTitle("播放")
        SwitchRow(
            "播完自动播下一集",
            state.autoPlayNext,
            detail = "播完后倒计时几秒再放下一集，可以取消；连续自动播放几集后会先问一下"
        ) { state.setAutoPlay(it) }
        if (PlatformInfo.isDesktop) {
            SwitchRow("开始播放时进入全屏", state.autoFullscreen, detail = "关掉后在窗口里播放，按 F 或 F11 随时全屏") {
                state.changeAutoFullscreen(it)
            }
        }
        ChoiceRow(
            label = "看过一半的内容",
            options = ResumeBehavior.entries.map { it to it.label },
            selected = state.resumeBehavior
        ) { state.changeResumeBehavior(it) }
        // Chips that wrap rather than a segmented row: six languages do not fit
        // across a phone, which is why the list used to stop at three.
        ChipChoiceRow(
            label = "首选音轨语言",
            options = listOf("" to "默认") + PREFERRED_LANGUAGES,
            selected = state.preferredAudioLanguage
        ) { state.changePreferredAudioLanguage(it) }
        ChipChoiceRow(
            label = "首选字幕",
            options = listOf("" to "默认") + PREFERRED_LANGUAGES + (SUBTITLE_PREF_OFF to "关闭"),
            selected = state.preferredSubtitleLanguage
        ) { state.changePreferredSubtitleLanguage(it) }
        Hint("只用于还没选过音轨和字幕的内容。在某一集里换了音轨或字幕，同一部剧的下一集会沿用那个选择。")
        ChoiceRow(
            label = "字幕大小",
            options = listOf(0.8f to "小", 1f to "标准", 1.25f to "大", 1.5f to "特大"),
            selected = listOf(0.8f, 1f, 1.25f, 1.5f).minByOrNull { kotlin.math.abs(it - state.subtitleScale) } ?: 1f
        ) { state.changeSubtitleScale(it) }
        Spacer(Modifier.height(8.dp))
        DefaultPlayerRow(state)
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * Which player the plain play button should use.
 *
 * The button used to take the first entry of a hardcoded detection order, so
 * having both PotPlayer and mpv installed meant PotPlayer every time, and the
 * only way round it was the context menu — once per episode.
 */
@Composable
private fun DefaultPlayerRow(state: AppState) {
    if (PlatformInfo.hasInternalPlayer && PlatformInfo.isAndroid) return
    val players = remember { availableExternalPlayers().filter { it.executablePath != null || it.viaUrlScheme } }
    if (players.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    val chosen = players.firstOrNull { it.id == state.preferredPlayerId }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("内置播放器不可用时使用")
            Hint(chosen?.label ?: "自动（按检测顺序）")
        }
        Box {
            TextButton(onClick = { open = true }) { Text("更改") }
            AppMenu(open, onDismissRequest = { open = false }) {
                DropdownMenuItem(
                    text = { Text("自动（按检测顺序）") },
                    onClick = { open = false; state.setPreferredPlayer("") }
                )
                players.forEach { player ->
                    DropdownMenuItem(
                        text = { Text(player.label) },
                        onClick = { open = false; state.setPreferredPlayer(player.id) }
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- sync and backup

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
    val current = settings
    val pathDirty = current != null && path != current.remotePath
    DirtyTracker(state, "sync", pathDirty)

    fun apply(block: suspend () -> Unit) {
        scope.launch {
            busy = true
            message = null
            error = null
            try {
                block()
            } catch (e: Throwable) {
                error = state.describe(e)
            } finally {
                busy = false
            }
        }
    }

    fun show(result: SyncResultDto) {
        if (result.ok) message = result.message else error = result.message
    }

    fun update(transform: (SyncSettingsDto) -> SyncSettingsDto) = apply {
        val base = settings ?: SyncSettingsDto()
        val updated = state.library.updateSyncSettings(transform(base))
        settings = updated
        // A path typed in and not yet saved stays; changing the interval used
        // to put the saved one back over it.
        if (path == base.remotePath) path = updated.remotePath
    }

    Column(Modifier.padding(horizontal = 20.dp)) {
        SectionTitle("跨端同步")
        Hint(
            "把观看进度、收藏、媒体库定义和手动指定写成 WebDAV 上的一个文件，其它设备读回来合并；" +
                "同一条记录以较新的一方为准。海报和简介不在里面，每台设备自己获取。"
        )

        Spacer(Modifier.height(10.dp))
        SwitchRow(
            label = "启用同步",
            checked = current?.enabled == true,
            enabled = current != null && !busy,
            detail = "开启后自动进行：每次看完一集立即上传；另外每隔一段时间检查并上传、拉取别的设备的记录"
        ) { want ->
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

        current?.let { sync ->
            ChoiceRow(
                label = "检查间隔",
                options = listOf(5 to "5 分钟", 10 to "10 分钟", 30 to "30 分钟", 60 to "1 小时"),
                selected = listOf(5, 10, 30, 60).minByOrNull { kotlin.math.abs(it - sync.minIntervalMinutes) } ?: 10
            ) { minutes -> update { it.copy(minIntervalMinutes = minutes) } }
        }

        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = path,
            onValueChange = { path = it },
            label = { Text("WebDAV 上的同步文件") },
            supportingText = { Text("相对于 WebDAV 根目录；DAView 只会写这一个文件") },
            singleLine = true,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (pathDirty) update { it.copy(remotePath = path) } }),
            modifier = Modifier.fillMaxWidth().tracksTextInput()
        )
        if (pathDirty) {
            TextButton(onClick = { update { it.copy(remotePath = path) } }) { Text("保存路径") }
        }

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
            ) { Text("立即拉取") }
        }

        if (busy) {
            Spacer(Modifier.height(8.dp))
            LinearWavyProgressIndicator(Modifier.fillMaxWidth())
        }
        current?.let {
            Spacer(Modifier.height(8.dp))
            // When, not only whether: "已上传" said nothing about last night.
            Hint(
                listOfNotNull(
                    it.lastUploadAt?.let { at -> "上次上传 ${formatAgo(at)}" } ?: "还没有上传过",
                    it.lastPullAt?.let { at -> "上次拉取 ${formatAgo(at)}" } ?: "还没有拉取过",
                    when (it.writable) {
                        true -> "存储可写"
                        false -> "存储只读，无法同步"
                        null -> null
                    }
                ).joinToString(" · ")
            )
        }
        message?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        (error ?: current?.lastError)?.let {
            Spacer(Modifier.height(6.dp))
            Hint(it, error = true)
        }
        Spacer(Modifier.height(20.dp))
    }
}

/**
 * Export writes a file through the platform's save dialog; import reads one,
 * says what is in it, and only then applies it.
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
    var preview by remember { mutableStateOf<BackupFileDto?>(null) }

    Column(Modifier.padding(horizontal = 20.dp)) {
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        SectionTitle("备份与迁移")
        Hint(
            "导出一个 JSON：应用设置、媒体库定义、观看进度（位置 / 已看 / 收藏 / 音轨字幕选择），" +
                "以及可选的全部元数据。在新设备上点「导入」选中这个文件即可。"
        )

        Spacer(Modifier.height(4.dp))
        // Checkboxes, not filled buttons. Selected, these two looked exactly
        // like the "导出" button ten dp below them — two states and two actions
        // in one visual register.
        CheckboxRow("包含元数据", includeItems) { includeItems = it }
        CheckboxRow("包含凭据", includeSecrets) { includeSecrets = it }
        if (includeSecrets) {
            Spacer(Modifier.height(6.dp))
            Hint("文件里会明文带上 WebDAV 账号密码与 API Key。不勾选的话，导入方保留自己已有的凭据。", error = true)
        }
        if (!includeItems) {
            Spacer(Modifier.height(6.dp))
            Hint("不含元数据时文件只有几十 KB，但新设备需要重新扫描一遍；手动指定的条目仍会带上。")
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
                        busy = true
                        try {
                            // No file chosen is not a failure: the dialog was
                            // closed, and nothing needs saying. A write that failed
                            // says why.
                            runCatching {
                                saveTextFile(backupFileName()) { out -> chunks.forEach(out::append) }
                            }.onSuccess { saved ->
                                message = saved?.let { "已导出到 $it" }
                            }.onFailure {
                                error = "导出失败：${it.message ?: it::class.simpleName}"
                            }
                        } finally {
                            busy = false
                        }
                    }
                }
            ) { Text("导出…") }

            FilledTonalButton(
                enabled = !busy,
                onClick = {
                    scope.launch {
                        error = null
                        message = null
                        val content = runCatching { pickTextFile() }.getOrNull()
                        if (content.isNullOrBlank()) return@launch
                        busy = true
                        try {
                            preview = withContext(Dispatchers.Default) {
                                DaViewJson.decodeFromString(BackupFileDto.serializer(), content)
                            }
                        } catch (e: Throwable) {
                            error = "这不是 DAView 的备份文件：${e.message ?: ""}".trim()
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
            Hint(it, error = true)
        }
        Spacer(Modifier.height(20.dp))
    }

    preview?.let { backup ->
        ModalMarker()
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text("导入这个备份？") },
            text = {
                Column {
                    Text(
                        listOfNotNull(
                            backup.createdAt.takeIf { it > 0 }?.let { "创建于 ${formatAgo(it)}" },
                            "媒体库 ${backup.libraries.size} 个",
                            "观看记录 ${backup.userData.size} 条",
                            "手动指定 ${backup.pins.size} 条",
                            "元数据 ${backup.items.size} 条".takeIf { backup.items.isNotEmpty() },
                            "含应用设置".takeIf { backup.settings != null },
                            "含凭据（会覆盖本机的密码和 Key）".takeIf { backup.containsSecrets }
                        ).joinToString("\n")
                    )
                    Spacer(Modifier.height(8.dp))
                    Hint("观看记录按时间合并，较新的一方为准；媒体库与设置会被写入本机。")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val chosen = backup
                    preview = null
                    scope.launch {
                        busy = true
                        try {
                            val summary = state.library.importBackup(chosen)
                            message = "已导入：媒体库 ${summary.libraries} 个、条目 ${summary.items} 项、" +
                                "观看记录 ${summary.userData} 条、手动指定 ${summary.pins} 条" +
                                (if (summary.settingsApplied) "，设置已应用" else "")
                            state.refreshLibraries()
                            state.loadServerSettings()
                            state.refreshHome()
                        } catch (e: Throwable) {
                            error = state.describe(e)
                        } finally {
                            busy = false
                        }
                    }
                }) { Text("导入") }
            },
            dismissButton = { TextButton(onClick = { preview = null }) { Text("取消") } }
        )
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

// ---------------------------------------------------------------- offline

/**
 * What is kept on this device.
 *
 * Everything else in the app streams from the share, so this is the only place
 * storage on the device itself is spent, and the only place to get it back. A
 * show's episodes are listed under the show, with one way to be rid of all of
 * them; films stand on their own.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DownloadsSection(state: AppState) {
    val downloads = state.downloads
    LaunchedEffect(Unit) {
        state.refreshDownloads()
        state.loadOfflineSettings()
    }

    Column(Modifier.padding(horizontal = 20.dp)) {
        SectionTitle("离线内容")
        Hint("下载的是视频连同它的外挂字幕，只留在这台设备上，不会同步到别的设备。播放时直接读本地文件，不走网络。")
        Spacer(Modifier.height(8.dp))
        OfflineLocationRow(state)
        if (PlatformInfo.isAndroid) {
            SwitchRow(
                "仅在 Wi-Fi 下下载",
                state.downloadWifiOnly,
                detail = "用移动数据时先排队等着，连上 Wi-Fi 后从中断处继续"
            ) { state.changeDownloadWifiOnly(it) }
        }
        Spacer(Modifier.height(6.dp))

        val active = downloads.filter { it.active }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                listOfNotNull(
                    "已占用 ${formatSize(state.downloadedBytes).ifBlank { "0 B" }}",
                    "${downloads.count { it.state == DownloadState.DONE }} 个文件",
                    "${active.size} 个在排队或下载".takeIf { active.isNotEmpty() }
                ).joinToString(" · "),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            if (active.isNotEmpty()) {
                TextButton(onClick = { state.cancelAllDownloads() }) { Text("全部取消") }
            }
        }
        if (downloads.isEmpty()) {
            Spacer(Modifier.height(8.dp))
            Hint("还没有下载。在任意影片或分集的菜单里选「下载到本机」，或在剧集页下载整部、整季。")
        }
        Spacer(Modifier.height(8.dp))

        downloads.groupBy { it.seriesId ?: it.itemId }.forEach { (_, rows) ->
            val first = rows.first()
            if (first.seriesId == null) {
                DownloadRow(state, first, indent = false)
            } else {
                SeriesDownloadHeader(state, first, rows)
                rows.sortedWith(compareBy({ it.seasonNumber ?: Int.MAX_VALUE }, { it.episodeNumber ?: Int.MAX_VALUE }))
                    .forEach { DownloadRow(state, it, indent = true) }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Where downloads go, how much room is left, and — on a desktop — a way to change it. */
@Composable
private fun OfflineLocationRow(state: AppState) {
    val settings = state.offlineSettings
    val scope = rememberCoroutineScope()
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("存储位置")
            Hint(
                settings?.let { current ->
                    listOfNotNull(
                        current.effectiveDirectory,
                        current.freeBytes?.let { "剩余 ${formatSize(it)}" }
                    ).joinToString(" · ")
                } ?: "统计中…"
            )
            if (PlatformInfo.isAndroid) {
                Hint("在应用自己的存储空间里，卸载应用时一并清除")
            } else {
                Hint("改了位置后，之后的下载放到新目录；已有的文件留在原处，照常播放")
            }
        }
        if (PlatformInfo.isDesktop) {
            TextButton(onClick = {
                scope.launch {
                    pickDirectory("选择下载目录", settings?.effectiveDirectory)?.let { state.changeOfflineDirectory(it) }
                }
            }) { Text("更改…") }
            if (settings?.directory?.isNotBlank() == true) {
                TextButton(onClick = { state.changeOfflineDirectory(null) }) { Text("恢复默认") }
            }
            TextButton(
                enabled = settings != null,
                onClick = { settings?.effectiveDirectory?.let { revealInFileManager(it) } }
            ) { Text("打开") }
        }
    }
}

/** A show with downloads under it: how much of it is here, and one delete for all of it. */
@Composable
private fun SeriesDownloadHeader(state: AppState, first: DownloadDto, rows: List<DownloadDto>) {
    val done = rows.filter { it.state == DownloadState.DONE }
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(first.seriesName ?: "剧集", style = MaterialTheme.typography.titleSmall)
            Hint(
                listOfNotNull(
                    "${done.size} 集在本机",
                    formatSize(done.sumOf { it.totalBytes }).takeIf { it.isNotBlank() && done.isNotEmpty() },
                    "${rows.size - done.size} 集在排队或下载".takeIf { rows.size > done.size }
                ).joinToString(" · ")
            )
        }
        if (done.isNotEmpty()) {
            TextButton(onClick = {
                state.confirm(
                    com.daview.app.data.Confirmation(
                        title = "删除「${first.seriesName ?: "这部剧"}」的 ${done.size} 个本地文件？",
                        text = "删除后要再联网播放或重新下载。${formatSize(done.sumOf { it.totalBytes })}".trim(),
                        confirmLabel = "删除",
                        destructive = true,
                        action = { state.removeDownloads(done.map { it.itemId }) }
                    )
                )
            }) { Text("删除整部…") }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DownloadRow(state: AppState, download: DownloadDto, indent: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(start = if (indent) 16.dp else 0.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            // Under its show's header the show's name is said already.
            val title = download.seriesName
                ?.takeIf { indent }
                ?.let { download.name.removePrefix("$it · ") }
                ?: download.name
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when (download.state) {
                    DownloadState.DONE -> listOfNotNull(
                        formatSize(download.totalBytes).takeIf { it.isNotBlank() },
                        "含 ${download.subtitleCount} 条字幕".takeIf { download.subtitleCount > 0 },
                        download.note
                    ).joinToString(" · ")
                    DownloadState.FAILED -> "下载失败：" + (download.error ?: "未知原因")
                    DownloadState.QUEUED -> download.note ?: "排队中"
                    DownloadState.RUNNING ->
                        "${(download.fraction * 100).toInt()}% · " +
                            "${formatSize(download.downloadedBytes)} / ${formatSize(download.totalBytes)}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    download.state == DownloadState.FAILED -> MaterialTheme.colorScheme.error
                    download.note != null -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (download.active) {
                Spacer(Modifier.height(4.dp))
                // Indeterminate until the size is known, rather than a bar
                // sitting at zero while a queued item waits.
                if (download.totalBytes > 0 && download.state == DownloadState.RUNNING) {
                    LinearWavyProgressIndicator(
                        progress = { download.fraction },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
        if (download.state == DownloadState.FAILED) {
            TextButton(onClick = { state.retryDownload(download.itemId) }) { Text("重试") }
        }
        val file = download.file
        if (download.state == DownloadState.DONE && PlatformInfo.isDesktop && file != null) {
            TipIconButton("在文件夹中显示", onClick = { revealInFileManager(file) }) {
                Icon(Icons.Filled.FolderOpen, contentDescription = "在文件夹中显示")
            }
        }
        TextButton(onClick = {
            if (download.state == DownloadState.DONE) {
                state.confirm(
                    com.daview.app.data.Confirmation(
                        title = "删除「${download.name}」的本地文件？",
                        text = "删除后要再联网播放或重新下载。${formatSize(download.totalBytes)}".trim(),
                        confirmLabel = "删除",
                        destructive = true,
                        action = { state.removeDownload(download.itemId) }
                    )
                )
            } else if (download.active) {
                state.cancelDownload(download.itemId)
            } else {
                state.removeDownload(download.itemId)
            }
        }) {
            Text(if (download.active) "取消" else "删除")
        }
    }
}

// ---------------------------------------------------------------- general

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GeneralSection(state: AppState) {
    Column(Modifier.padding(horizontal = 20.dp)) {
        SectionTitle("外观")
        ChoiceRow(
            label = "主题",
            options = ThemeMode.entries.map { it to it.label },
            selected = state.themeMode
        ) { state.setTheme(it) }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        SectionTitle("首页")
        Hint("勾选要显示的行，用箭头调整顺序。")
        Spacer(Modifier.height(4.dp))
        val sections = state.homeSections
        sections.forEachIndexed { index, (section, shown) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = shown,
                    onCheckedChange = { on ->
                        state.changeHomeSections(sections.toMutableList().also { it[index] = section to on })
                    }
                )
                Text(section.label, Modifier.weight(1f))
                IconButton(enabled = index > 0, onClick = {
                    state.changeHomeSections(sections.toMutableList().also { it.add(index - 1, it.removeAt(index)) })
                }) { Icon(Icons.Filled.ArrowUpward, contentDescription = "上移") }
                IconButton(enabled = index < sections.lastIndex, onClick = {
                    state.changeHomeSections(sections.toMutableList().also { it.add(index + 1, it.removeAt(index)) })
                }) { Icon(Icons.Filled.ArrowDownward, contentDescription = "下移") }
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        SectionTitle("存储空间")
        ImageCacheRow(state)

        if (PlatformInfo.isDesktop) {
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            SectionTitle("键盘快捷键")
            DesktopShortcuts.reference.forEach { (keys, action) ->
                Row(Modifier.padding(vertical = 3.dp)) {
                    Text(keys, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.width(200.dp))
                    Text(action, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        SectionTitle("关于")
        state.serverInfo?.let {
            Hint("DAView ${it.version} · ${PlatformInfo.name} · 共 ${it.itemCount} 个条目")
        }
        Hint("媒体库保存在这台设备上，不依赖任何服务器；设备之间通过 WebDAV 上的同步文件对齐。")
        Spacer(Modifier.height(24.dp))
    }
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
            Hint(bytes?.let { "海报与背景图占用 ${formatSize(it).ifBlank { "0 B" }}，清除后会按需重新下载" } ?: "统计中…")
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
