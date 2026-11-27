package com.daview.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TonalToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.daview.app.BuildInfo
import com.daview.app.data.AppState
import com.daview.app.data.DEFAULT_UPDATE_SOURCE
import com.daview.app.data.UpdateState
import com.daview.app.data.DesktopShortcuts
import com.daview.app.data.ModalMarker
import com.daview.app.data.ResumeBehavior
import com.daview.app.data.Screen
import com.daview.app.data.ThemeMode
import com.daview.app.data.tracksTextInput
import com.daview.app.libraryIcon
import com.daview.app.platform.PlatformInfo
import com.daview.app.platform.canSelfUpdate
import com.daview.app.platform.openUrl
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

private enum class SettingsTab(val label: String, val detail: String, val filled: ImageVector, val outlined: ImageVector) {
    LIBRARIES("媒体库", "管理目录与扫描", Icons.Filled.VideoLibrary, Icons.Outlined.VideoLibrary),
    STORAGE("存储与刮削", "连接 WebDAV 与设置元数据", Icons.Filled.Cloud, Icons.Outlined.Cloud),
    PLAYBACK("播放", "续播、音轨和字幕", Icons.Filled.PlayCircle, Icons.Outlined.PlayCircle),
    SYNC("同步与备份", "跨设备同步与数据迁移", Icons.Filled.Sync, Icons.Outlined.Sync),
    OFFLINE("离线内容", "本机下载与空间", Icons.Filled.Download, Icons.Outlined.Download),
    GENERAL("通用", "外观、首页与更新", Icons.Filled.Tune, Icons.Outlined.Tune)
}

@Composable
fun SettingsScreen(state: AppState) {
    val storageReady = state.serverInfo?.storageConfigured == true
    // Somebody with no share configured has one thing to do first.
    var tab by rememberSaveable { mutableStateOf(if (storageReady) SettingsTab.LIBRARIES else SettingsTab.STORAGE) }
    var pendingTab by remember { mutableStateOf<SettingsTab?>(null) }
    val scroll = rememberScrollState()
    val tabScroll = rememberScrollState()
    var previousTab by remember { mutableStateOf(tab) }
    LaunchedEffect(tab) {
        if (tab != previousTab) {
            scroll.scrollTo(0)
            previousTab = tab
        }
    }

    val select: (SettingsTab) -> Unit = { next ->
        if (next != tab) {
            if (state.unsavedSettings) pendingTab = next else tab = next
        }
    }
    val goBack: (() -> Unit)? = if (state.canGoBack) ({ state.back() }) else null

    pendingTab?.let { next ->
        ModalMarker()
        AdaptiveAlertDialog(
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

    // Constraints here are for the usable Browse pane, not the whole window:
    // on a book fold the workspace may only own one side of the hinge.
    BoxWithConstraints(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.TopCenter) {
        val extraText = (LocalDensity.current.fontScale - 1f).coerceAtLeast(0f)
        val margin = if (maxWidth < 600.dp) 16.dp else 24.dp
        val navigationWidth = (220f + 64f * extraText).dp
        val paneGap = 24.dp
        // Budget the category list and the editor after the app rail, hinge and insets.
        // A wide phone needn't reach the whole-window Expanded breakpoint again here.
        val editorMinWidth = (408f + 96f * extraText).dp
        val split = maxWidth >= margin * 2 + navigationWidth + paneGap + editorMinWidth && maxHeight >= 200.dp
        val headerPinned = split && maxHeight >= 360.dp
        Column(Modifier.fillMaxSize()) {
            if (headerPinned) ScreenTopBar("设置", onBack = goBack)
            Row(Modifier.align(Alignment.CenterHorizontally).widthIn(max = 1040.dp).fillMaxSize()) {
                if (split) {
                    SettingsCategories(
                        tab = tab,
                        onSelect = select,
                        modifier = Modifier
                            .padding(start = margin, end = paneGap, top = 12.dp, bottom = 12.dp)
                            .width(navigationWidth)
                    )
                }
                Box(Modifier.weight(1f).fillMaxSize()) {
                    // Keep the same editor/scroll nodes when switching chrome.
                    // In a short pane even the top bar and tabs can scroll away.
                    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(bottom = 24.dp)) {
                        if (!headerPinned) ScreenTopBar("设置", onBack = goBack)
                        if (!split) SettingsCategoryTabs(tab, tabScroll, select, margin)

                        Column(
                            Modifier.fillMaxWidth()
                                .wrapContentWidth(if (split) Alignment.Start else Alignment.CenterHorizontally)
                                .widthIn(max = 760.dp)
                                .padding(start = if (split) 0.dp else margin, end = margin, top = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            // In a short or narrow pane the selected tab already identifies the
                            // category. Leave the first form and its controls above the fold.
                            if (headerPinned) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    SettingsHeading(tab.label, major = true)
                                    Hint(tab.detail)
                                }
                            }
                            UpdateBanner(state)
                            when (tab) {
                                SettingsTab.LIBRARIES -> LibrariesTab(state, storageReady) { select(SettingsTab.STORAGE) }
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
                    VerticalScrollbarFor(scroll, Modifier.align(Alignment.CenterEnd).padding(vertical = 8.dp).fillMaxHeight())
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SettingsCategoryTabs(
    tab: SettingsTab,
    scroll: ScrollState,
    onSelect: (SettingsTab) -> Unit,
    margin: Dp
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = margin).padding(top = 4.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.largeIncreased
    ) {
        PrimaryScrollableTabRow(
            selectedTabIndex = tab.ordinal,
            modifier = Modifier.padding(vertical = 4.dp).focusGroup(),
            scrollState = scroll,
            containerColor = Color.Transparent,
            edgePadding = 4.dp,
            minTabWidth = 48.dp,
            indicator = {},
            divider = {}
        ) {
            SettingsTab.entries.forEach { entry ->
                SettingsCategoryTab(
                    entry = entry,
                    selected = entry == tab,
                    onClick = { onSelect(entry) },
                    modifier = Modifier.padding(horizontal = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun SettingsCategories(tab: SettingsTab, onSelect: (SettingsTab) -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val scroll = rememberScrollState()
    val active = remember { BringIntoViewRequester() }
    // When the short landscape list scrolls, reveal its selected item on entry or resize.
    LaunchedEffect(tab, scroll.viewportSize) {
        if (scroll.viewportSize > 0) active.bringIntoView()
    }
    Surface(modifier, color = scheme.surfaceContainer, shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.verticalScroll(scroll).padding(8.dp)) {
            Text(
                "设置分类",
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 8.dp).semantics { heading() }
            )
            Column(Modifier.selectableGroup().focusGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SettingsTab.entries.forEach { entry ->
                    SettingsCategoryTab(
                        entry = entry,
                        selected = entry == tab,
                        onClick = { onSelect(entry) },
                        modifier = Modifier.fillMaxWidth().then(if (entry == tab) Modifier.bringIntoViewRequester(active) else Modifier),
                        verticalList = true
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SettingsCategoryTab(
    entry: SettingsTab,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    verticalList: Boolean = false
) {
    val scheme = MaterialTheme.colorScheme
    val fill by animateColorAsState(
        targetValue = if (selected) scheme.secondaryContainer else scheme.surfaceContainer,
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "settings-category-fill"
    )
    // Use real tabs in both arrangements. The tonal fill replaces the underline;
    // Tab supplies the same selection announcement, keyboard action and ripple.
    Tab(
        selected = selected,
        onClick = onClick,
        modifier = modifier.clip(RoundedCornerShape(if (verticalList) 20.dp else 16.dp)).background(fill),
        selectedContentColor = scheme.onSecondaryContainer,
        unselectedContentColor = scheme.onSurfaceVariant
    ) {
        Row(
            modifier = Modifier
                .then(if (verticalList) Modifier.fillMaxWidth() else Modifier)
                .heightIn(min = if (verticalList) 56.dp else 48.dp)
                .padding(horizontal = if (verticalList) 16.dp else 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (selected) entry.filled else entry.outlined,
                contentDescription = null,
                modifier = Modifier.size(if (verticalList) 24.dp else 20.dp)
            )
            Text(
                entry.label,
                modifier = if (verticalList) Modifier.weight(1f) else Modifier,
                style = if (selected) MaterialTheme.typography.labelLargeEmphasized else MaterialTheme.typography.labelLarge
            )
            if (verticalList && selected) Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(20.dp))
        }
    }
}

// ---------------------------------------------------------------- libraries

@Composable
private fun LibrariesTab(state: AppState, storageReady: Boolean, openStorage: () -> Unit) {
    var pickerOpen by remember { mutableStateOf(false) }
    if (!storageReady) {
        SettingsPanel("还没有连接 WebDAV", "连接 WebDAV 后，就能把其中的目录添加为媒体库。") {
            Button(onClick = openStorage) { PrimaryLabel("连接 WebDAV…") }
        }
    }
    SettingsPanel(
        title = "媒体库目录",
        description = if (state.libraries.isEmpty()) "添加目录后，DAView 会扫描视频并获取海报和简介。" else null
    ) {
        Button(onClick = { pickerOpen = true }, enabled = storageReady) {
            Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            PrimaryLabel("添加媒体库…")
        }
    }
    if (state.libraries.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.libraries.forEach { library ->
                key(library.id) { LibraryCard(state, library) }
            }
        }
    }
    SettingsPanel("扫描偏好") {
        SettingsSwitch(
            "启动时检查新增内容",
            state.scanOnStartup,
            enabled = storageReady,
            detail = "打开应用后在后台扫描每个媒体库：先找新目录并获取元数据，再检查已有的剧有没有新集。只在发现变化时才提示"
        ) { state.changeScanOnStartup(it) }
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
    SettingsPanel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val scheme = MaterialTheme.colorScheme
            Surface(shape = MaterialTheme.shapes.small, color = scheme.secondaryContainer, contentColor = scheme.onSecondaryContainer) {
                Icon(libraryIcon(library.kind), contentDescription = null, modifier = Modifier.padding(8.dp).size(24.dp))
            }
            LinkText(
                library.name,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMediumEmphasized,
                color = scheme.primary,
                maxLines = 2,
                onClick = { state.switchTo(Screen.Library(library.id)) }
            )
            Box {
                var open by remember { mutableStateOf(false) }
                TipIconButton("媒体库操作", onClick = { open = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "媒体库操作")
                }
                AppMenu(open, onDismissRequest = { open = false }) {
                    DropdownMenuItem(
                        text = { Text("编辑媒体库") },
                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        onClick = { open = false; editOpen = true }
                    )
                    DropdownMenuItem(
                        text = { Text("删除媒体库", color = scheme.error) },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = scheme.error) },
                        onClick = { open = false; confirmDelete = true }
                    )
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Hint("${kindLabel(library.kind)} · ${library.itemCount} 项 · ${library.lastScanAt?.let { "上次扫描 ${formatAgo(it)}" } ?: "还没有扫描过"}")
            Text(library.path, style = MaterialTheme.typography.bodyMedium)
            Hint("元数据来源：" + library.providerOrder.joinToString(" → ") { it.displayName })
        }
        if (status?.running == true) {
            Hint(
                scanPhaseLabel(status.phase) +
                    (if (status.total > 0) " ${status.current}/${status.total}" else "") +
                    (status.message.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
            )
            // Until the scan knows how much there is, show activity rather than 0%.
            if (status.total > 0) {
                LinearWavyProgressIndicator(
                    progress = { status.current.toFloat() / status.total },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                LinearWavyProgressIndicator(Modifier.fillMaxWidth())
            }
            TextButton(onClick = { state.cancelScan(library.id) }) { Text("停止扫描") }
        } else {
            Box {
                var scanMenu by remember { mutableStateOf(false) }
                FilledTonalButton(onClick = { scanMenu = true }) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text("扫描")
                }
                AppMenu(scanMenu, onDismissRequest = { scanMenu = false }) {
                    ScanMenuItems(state, library.id) { scanMenu = false }
                }
            }
        }
        status?.error?.let { SettingsNotice(it, SettingsTone.Error) }
    }
    if (editOpen) LibraryEditDialog(state, library, onDismiss = { editOpen = false })
    if (confirmDelete) {
        ModalMarker()
        // This button sits next to the scan button and used to fire straight
        // through, taking every scraped record and every watch position in
        // the library with it.
        AdaptiveAlertDialog(
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SettingsHeading(text: String, major: Boolean = false) {
    Text(
        text,
        style = if (major) MaterialTheme.typography.headlineSmallEmphasized else MaterialTheme.typography.titleMediumEmphasized,
        modifier = Modifier.semantics { heading() }
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SettingsPanel(
    title: String? = null,
    description: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.largeIncreased, color = scheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null || description != null) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    title?.let { SettingsHeading(it) }
                    description?.let { Hint(it) }
                }
            }
            content()
        }
    }
}

@Composable
private fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Metric(label: String, value: String) {
    val scheme = MaterialTheme.colorScheme
    Surface(shape = MaterialTheme.shapes.extraSmall, color = scheme.surfaceContainerHigh) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMediumEmphasized)
        }
    }
}

internal enum class SettingsTone { Success, Attention, Error }

@Composable
internal fun SettingsNotice(text: String, tone: SettingsTone) {
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when (tone) {
        SettingsTone.Success -> scheme.secondaryContainer to scheme.onSecondaryContainer
        SettingsTone.Attention -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        SettingsTone.Error -> scheme.errorContainer to scheme.onErrorContainer
    }
    Surface(
        Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.extraSmall,
        color = container,
        contentColor = content
    ) {
        Text(text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PrimaryLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLargeEmphasized)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsActions(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

// Make the entire row the switch's target and accessibility node.
@Composable
internal fun SettingsSwitch(
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
            .heightIn(min = 48.dp)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            detail?.let { Hint(it) }
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** Use the short connected group only where all labels can fit; don't wrap its buttons. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun <T> ChoiceRow(label: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val groupWidth = minOf(maxWidth, 480.dp)
        if (groupWidth < (options.size * 136f * fontScale).dp) {
            ChipChoiceRow(label, options, selected, onSelect = onSelect)
        } else {
            val scheme = MaterialTheme.colorScheme
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OptionLabel(label)
                Row(
                    Modifier.widthIn(max = 480.dp).fillMaxWidth().selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)
                ) {
                    options.forEachIndexed { index, (value, text) ->
                        val checked = value == selected
                        TonalToggleButton(
                            checked = checked,
                            onCheckedChange = { if (it) onSelect(value) },
                            modifier = Modifier.weight(1f),
                            shapes = when (index) {
                                0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                            },
                            colors = ToggleButtonDefaults.tonalToggleButtonColors(
                                containerColor = scheme.surfaceContainerHigh,
                                contentColor = scheme.onSurfaceVariant,
                                checkedContainerColor = scheme.secondaryContainer,
                                checkedContentColor = scheme.onSecondaryContainer
                            )
                        ) {
                            if (checked) {
                                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(ToggleButtonDefaults.IconSize))
                                Spacer(Modifier.width(4.dp))
                            }
                            Text(text, style = if (checked) MaterialTheme.typography.labelLargeEmphasized else MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OptionLabel(label: String) {
    Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun <T> ChipChoiceRow(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    enabled: Boolean = true,
    onSelect: (T) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OptionLabel(label)
        SettingsChoices(options, selected, enabled, onSelect)
    }
}

/** Longer lists of choices keep their labels and flow across lines on narrow panes. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun <T> SettingsChoices(
    options: List<Pair<T, String>>,
    selected: T,
    enabled: Boolean = true,
    onSelect: (T) -> Unit
) {
    FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, text) ->
            val checked = value == selected
            FilterChip(
                selected = checked,
                enabled = enabled,
                onClick = { onSelect(value) },
                label = {
                    Text(text, style = if (checked) MaterialTheme.typography.labelLargeEmphasized else MaterialTheme.typography.labelLarge)
                },
                leadingIcon = if (checked) {
                    { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                } else null
            )
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
    supportingText: String? = null,
    imeAction: ImeAction = ImeAction.Next,
    onDone: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value,
        onValueChange,
        label = { Text(label) },
        supportingText = supportingText?.let { { Text(it) } },
        singleLine = true,
        visualTransformation =
            if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = imeAction
        ),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
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
private fun StoredSecret(state: AppState, key: String, isSet: Boolean, onClear: () -> Unit) {
    if (!isSet) return
    val scope = rememberCoroutineScope()
    var shown by remember { mutableStateOf<String?>(null) }
    Column {
        SettingsActions {
            TextButton(onClick = {
                if (shown != null) shown = null
                else scope.launch { shown = state.library.storedSecrets()[key].orEmpty() }
            }) { Text(if (shown != null) "隐藏已存值" else "查看已存值") }
            TextButton(onClick = onClear) { Text("清除", color = MaterialTheme.colorScheme.error) }
        }
        shown?.let { Hint(it) }
    }
}

// ---------------------------------------------------------------- storage and scrapers

@Composable
private fun StorageSection(state: AppState) {
    val settings = state.serverSettings
    if (settings == null) {
        SettingsPanel("WebDAV 存储") { Hint("正在读取存储设置…") }
        return
    }
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
        val submittedPassword = password
        state.saveServerSettings(
            settings.copy(storage = StorageSettingsDto(url = url, username = user, password = password))
        ) { saved ->
            if (saved && password == submittedPassword) password = ""
        }
    }

    SettingsPanel("WebDAV 存储", "媒体库都来自这里。测试连接后，再保存地址和凭据。") {
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
        // Flow left-to-right when both fields have room, otherwise keep IME
        // order in a column. These remain the same focus nodes on resize.
        WebDavCredentials(
            username = {
                OutlinedTextField(
                    user, { user = it; testResult = null },
                    label = { Text("账号") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth().tracksTextInput()
                )
            },
            password = {
                SecretField(
                    password, { password = it; testResult = null },
                    label = "密码",
                    supportingText = "已保存 · 留空保留原密码".takeIf { settings.storage.passwordSet },
                    imeAction = ImeAction.Done,
                    onDone = { save() },
                    modifier = Modifier.fillMaxWidth()
                )
                StoredSecret(state, "password", settings.storage.passwordSet) { confirmClear = true }
            }
        )
        SettingsActions {
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
            Button(enabled = dirty, onClick = { save() }) { PrimaryLabel("保存连接") }
        }
        if (dirty) SettingsNotice("有未保存的修改", SettingsTone.Attention)
        testResult?.let { (ok, message) ->
            SettingsNotice(message, if (ok) SettingsTone.Success else SettingsTone.Error)
        }
    }
    if (confirmClear) {
        ModalMarker()
        AdaptiveAlertDialog(
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WebDavCredentials(username: @Composable () -> Unit, password: @Composable ColumnScope.() -> Unit) {
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val single = maxWidth < (512f * fontScale).dp
        val fieldWidth = if (single) maxWidth else (maxWidth - 12.dp) / 2
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(Modifier.width(fieldWidth)) { username() }
            Column(Modifier.width(fieldWidth), content = password)
        }
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
        val submitted = Triple(scraper.tmdbApiKey, scraper.tvdbApiKey, scraper.bangumiToken)
        state.saveServerSettings(settings.copy(scraper = scraper)) { saved ->
            if (saved) {
                if (tmdb == submitted.first) tmdb = ""
                if (tvdb == submitted.second) tvdb = ""
                if (bangumi == submitted.third) bangumi = ""
            }
        }
    }

    SettingsPanel(
        "元数据来源",
        "TMDB 与 TheTVDB 需要 API Key，才能获取海报、简介和演职人员。bangumi.tv 的 Token 是可选的，可提高访问频率。"
    ) {
        SecretField(
            tmdb, { tmdb = it },
            label = "TMDB API Key",
            supportingText = "已保存 · 留空保留原值".takeIf { settings.scraper.tmdbApiKeySet },
            modifier = Modifier.fillMaxWidth()
        )
        StoredSecret(state, "tmdb", settings.scraper.tmdbApiKeySet) {
            save(settings.scraper.copy(clearTmdbApiKey = true))
        }
        SecretField(
            tvdb, { tvdb = it },
            label = "TheTVDB API Key",
            supportingText = "已保存 · 留空保留原值".takeIf { settings.scraper.tvdbApiKeySet },
            modifier = Modifier.fillMaxWidth()
        )
        StoredSecret(state, "tvdb", settings.scraper.tvdbApiKeySet) {
            save(settings.scraper.copy(clearTvdbApiKey = true))
        }
        SecretField(
            bangumi, { bangumi = it },
            label = "bangumi.tv Token",
            supportingText = if (settings.scraper.bangumiTokenSet) "已保存 · 留空保留原值" else "可选",
            imeAction = ImeAction.Done,
            onDone = { if (dirty) save() },
            modifier = Modifier.fillMaxWidth()
        )
        StoredSecret(state, "bangumi", settings.scraper.bangumiTokenSet) {
            save(settings.scraper.copy(clearBangumiToken = true))
        }
        Button(enabled = dirty, onClick = { save() }) { PrimaryLabel("保存密钥") }
        if (dirty) SettingsNotice("有未保存的修改", SettingsTone.Attention)
    }
    SettingsPanel("默认元数据语言", "选择后立即生效；每个媒体库也可以单独设置。") {
        SettingsChoices(METADATA_LANGUAGES, settings.scraper.language) { language ->
            state.saveServerSettings(settings.copy(scraper = settings.scraper.copy(language = language, tmdbApiKey = "", tvdbApiKey = "", bangumiToken = "")))
        }
    }
}

// ---------------------------------------------------------------- playback

@Composable
private fun PlaybackSection(state: AppState) {
    SettingsPanel("播放行为") {
        SettingsSwitch(
            "播完自动播下一集",
            state.autoPlayNext,
            detail = "播完后倒计时几秒再放下一集，可以取消；连续自动播放几集后会先问一下"
        ) { state.setAutoPlay(it) }
        if (PlatformInfo.isDesktop) {
            SettingsSwitch("开始播放时进入全屏", state.autoFullscreen, detail = "关掉后在窗口里播放，按 F 或 F11 随时全屏") {
                state.changeAutoFullscreen(it)
            }
        }
        ChoiceRow(
            label = "看过一半的内容",
            options = ResumeBehavior.entries.map { it to it.label },
            selected = state.resumeBehavior
        ) { state.changeResumeBehavior(it) }
    }
    SettingsPanel("音轨和字幕", "只用于还没选过音轨或字幕的内容；剧集会沿用上一次的选择。") {
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
        ChipChoiceRow(
            label = "字幕大小",
            options = listOf(0.8f to "小", 1f to "标准", 1.25f to "大", 1.5f to "特大"),
            selected = listOf(0.8f, 1f, 1.25f, 1.5f).minByOrNull { kotlin.math.abs(it - state.subtitleScale) } ?: 1f
        ) { state.changeSubtitleScale(it) }
    }
    DefaultPlayerRow(state)
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

    SettingsPanel("备用播放器", "内置播放器不可用时使用 · ${chosen?.label ?: "自动（按检测顺序）"}") {
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

    LaunchedEffect(state.startupSyncing, busy) {
        if (busy) return@LaunchedEffect
        runCatching { state.library.syncSettings() }.onSuccess {
            // Background startup completion updates status without replacing
            // a remote path the user is still editing.
            if (path == settings?.remotePath.orEmpty()) path = it.remotePath
            settings = it
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

    SettingsPanel("跨端同步", "通过 WebDAV 同步观看进度、收藏、媒体库定义和手动指定。设备之间以较新的记录为准。") {
        SettingsSwitch(
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
            ChipChoiceRow(
                label = "检查间隔",
                options = listOf(5 to "5 分钟", 10 to "10 分钟", 30 to "30 分钟", 60 to "1 小时"),
                selected = listOf(5, 10, 30, 60).minByOrNull { kotlin.math.abs(it - sync.minIntervalMinutes) } ?: 10,
                enabled = !busy
            ) { minutes -> update { it.copy(minIntervalMinutes = minutes) } }

            SettingsSwitch(
                label = "同步刮削数据",
                checked = sync.metadata,
                enabled = sync.enabled && !busy,
                detail = "一台设备刮削过的条目，其它设备不必再问刮削源，也不需要 API Key。只传有变化的条目；" +
                    "媒体库语言不同的设备互不影响。关闭后每台设备自己刮削"
            ) { want -> update { it.copy(metadata = want) } }
        }

        OutlinedTextField(
            value = path,
            onValueChange = { path = it },
            label = { Text("WebDAV 上的同步文件") },
            supportingText = { Text("相对于 WebDAV 根目录；刮削数据写在它旁边的 .metadata 目录，其余文件都不碰") },
            singleLine = true,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (pathDirty) update { it.copy(remotePath = path) } }),
            modifier = Modifier.fillMaxWidth().tracksTextInput()
        )
        if (pathDirty) {
            SettingsActions {
                Button(enabled = !busy, onClick = { update { it.copy(remotePath = path) } }) { PrimaryLabel("保存路径") }
                Hint("有未保存的修改")
            }
        }
    }
    SettingsPanel("同步状态") {
        SettingsActions {
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
            ) { PrimaryLabel("立即上传") }

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

        if (busy) LinearWavyProgressIndicator(Modifier.fillMaxWidth())
        current?.let { sync ->
            SettingsActions {
                Metric("上次上传", formatAgo(sync.lastUploadAt) ?: "尚未上传")
                Metric("上次拉取", formatAgo(sync.lastPullAt) ?: "尚未拉取")
                sync.lastMetadataAt?.let { Metric("刮削数据更新", formatAgo(it).orEmpty()) }
            }
            when (sync.writable) {
                true -> Hint("存储可写")
                false -> SettingsNotice("存储只读，无法同步", SettingsTone.Attention)
                null -> Unit
            }
        }
        message?.let { SettingsNotice(it, SettingsTone.Success) }
        (error ?: current?.lastError ?: current?.metadataError)?.let { SettingsNotice(it, SettingsTone.Error) }
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

    SettingsPanel(
        "备份与迁移",
        "导出 JSON 文件，保存应用设置、媒体库定义与观看记录；可选元数据及凭据。新设备可从文件导入。"
    ) {
        CheckboxRow("包含元数据", includeItems) { includeItems = it }
        CheckboxRow("包含凭据", includeSecrets) { includeSecrets = it }
        if (includeSecrets) {
            SettingsNotice("文件会明文保存 WebDAV 账号密码和 API Key。不勾选时，导入方保留本机已有的凭据。", SettingsTone.Attention)
        }
        if (!includeItems) {
            Hint("不含元数据时文件只有几十 KB。新设备需要重新扫描，但手动指定的条目仍会带上。")
        }
        SettingsActions {
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
            ) { PrimaryLabel("导出…") }

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
                            // The parser's own message is about JSON paths and tokens.
                            error = "这不是 DAView 的备份文件，或文件已损坏、不完整。"
                        } finally {
                            busy = false
                        }
                    }
                }
            ) { Text("导入…") }
        }

        if (busy) LinearWavyProgressIndicator(Modifier.fillMaxWidth())
        message?.let { SettingsNotice(it, SettingsTone.Success) }
        error?.let { SettingsNotice(it, SettingsTone.Error) }
    }

    preview?.let { backup ->
        ModalMarker()
        AdaptiveAlertDialog(
            onDismissRequest = { preview = null },
            title = { Text("导入这个备份？") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
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
                    // What a restore does, which is not what sync does: the file
                    // is taken as it is. This said the newer side would win,
                    // and an older backup then replaced newer progress here.
                    Hint("观看记录以这个文件为准，会覆盖本机同一条目的记录；媒体库与设置会被写入本机。")
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

/** A label and a checkbox that answer to the whole row, as [SettingsSwitch] does. */
@Composable
private fun CheckboxRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange)
            .heightIn(min = 48.dp)
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

    SettingsPanel("下载设置", "视频和外挂字幕只保留在这台设备上，不会跨端同步。离线播放直接读取本地文件。") {
        OfflineLocationRow(state)
        if (PlatformInfo.isAndroid) {
            SettingsSwitch(
                "仅在 Wi-Fi 下下载",
                state.downloadWifiOnly,
                detail = "用移动数据时先排队等着，连上 Wi-Fi 后从中断处继续"
            ) { state.changeDownloadWifiOnly(it) }
        }
    }
    val active = downloads.filter { it.active }
    SettingsPanel("本机下载") {
        SettingsActions {
            Metric("已占用", formatSize(state.downloadedBytes).ifBlank { "0 B" })
            Metric("本机文件", "${downloads.count { it.state == DownloadState.DONE }} 个")
            if (active.isNotEmpty()) Metric("排队或下载", "${active.size} 个")
        }
        if (active.isNotEmpty()) {
            TextButton(onClick = {
                state.stopDownloads("停止全部 ${active.size} 个下载？", active) { state.cancelAllDownloads() }
            }) { Text("全部取消") }
        }
        if (downloads.isEmpty()) {
            Hint("还没有下载。在任意影片或分集的菜单里选「下载到本机」，或在剧集页下载整部、整季。")
        }
    }
    if (downloads.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            downloads.groupBy { it.seriesId ?: it.itemId }.forEach { (id, rows) ->
                key(id) {
                    SettingsPanel {
                        val first = rows.first()
                        if (first.seriesId == null) {
                            DownloadRow(state, first, indent = false)
                        } else {
                            SeriesDownloadHeader(state, first, rows)
                            rows.sortedWith(compareBy({ it.seasonNumber ?: Int.MAX_VALUE }, { it.episodeNumber ?: Int.MAX_VALUE }))
                                .forEach { download ->
                                    HorizontalDivider()
                                    key(download.itemId) { DownloadRow(state, download, indent = true) }
                                }
                        }
                    }
                }
            }
        }
    }
}

/** Where downloads go, how much room is left, and — on a desktop — a way to change it. */
@Composable
private fun OfflineLocationRow(state: AppState) {
    val settings = state.offlineSettings
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("存储位置", style = MaterialTheme.typography.titleSmall)
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
            SettingsActions {
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
}

/** A show with downloads under it: how much of it is here, and one delete for all of it. */
@Composable
private fun SeriesDownloadHeader(state: AppState, first: DownloadDto, rows: List<DownloadDto>) {
    val done = rows.filter { it.state == DownloadState.DONE }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
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
    Column(
        Modifier.fillMaxWidth().padding(start = if (indent) 12.dp else 0.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // Under its show's header the show's name is said already.
            val title = download.seriesName
                ?.takeIf { indent }
                ?.let { download.name.removePrefix("$it · ") }
                ?: download.name
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
                }
            )
            if (download.active) {
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
        SettingsActions {
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
                    state.stopDownloads("停止下载「${download.name}」？", listOf(download)) {
                        state.cancelDownload(download.itemId)
                    }
                } else {
                    // A failed download keeps what arrived for 「重试」 to resume from.
                    state.stopDownloads("删除「${download.name}」已下载的部分？", listOf(download), confirmLabel = "删除") {
                        state.removeDownload(download.itemId)
                    }
                }
            }) {
                Text(if (download.active) "取消" else "删除")
            }
        }
    }
}

// ---------------------------------------------------------------- general

@Composable
private fun GeneralSection(state: AppState) {
    SettingsPanel("外观") {
        ChoiceRow(
            label = "主题",
            options = ThemeMode.entries.map { it to it.label },
            selected = state.themeMode
        ) { state.setTheme(it) }

    }
    SettingsPanel("首页布局", "勾选要显示的内容，用箭头调整顺序。") {
        val sections = state.homeSections
        sections.forEachIndexed { index, (section, shown) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The label toggles the row as the box does, and is what a
                // screen reader announces with it.
                Row(
                    Modifier
                        .weight(1f)
                        .toggleable(value = shown, role = Role.Checkbox) { on ->
                            state.changeHomeSections(sections.toMutableList().also { it[index] = section to on })
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = shown, onCheckedChange = null)
                    Text(section.label, Modifier.weight(1f))
                }
                IconButton(enabled = index > 0, onClick = {
                    state.changeHomeSections(sections.toMutableList().also { it.add(index - 1, it.removeAt(index)) })
                }) { Icon(Icons.Filled.ArrowUpward, contentDescription = "上移「${section.label}」") }
                IconButton(enabled = index < sections.lastIndex, onClick = {
                    state.changeHomeSections(sections.toMutableList().also { it.add(index + 1, it.removeAt(index)) })
                }) { Icon(Icons.Filled.ArrowDownward, contentDescription = "下移「${section.label}」") }
            }
        }

    }
    SettingsPanel("存储空间") { ImageCacheRow(state) }
    if (PlatformInfo.isDesktop) ShortcutsPanel()
    UpdateSection(state)
    SettingsPanel("关于") {
        Hint(
            "DAView ${BuildInfo.VERSION}（安装包版本 ${BuildInfo.PACKAGE_VERSION}）· ${PlatformInfo.name}" +
                (state.serverInfo?.let { " · 共 ${it.itemCount} 个条目" } ?: "")
        )
        Hint("媒体库保存在这台设备上，不依赖任何服务器；设备之间通过 WebDAV 上的同步文件对齐。")
        // The Android app ships FFmpeg's audio decoders under the LGPL, which
        // wants the people who get the app to be told so.
        if (PlatformInfo.hasFfmpegDecoders) {
            Hint("DTS、TrueHD 等音轨由 FFmpeg 6.1.6 解码（LGPL 2.1 或更高版本，未经修改，以共享库随安装包提供）；许可证全文与源代码地址见安装包内 assets/licenses/ffmpeg/。")
        }
    }
}

@Composable
private fun ShortcutsPanel() {
    SettingsPanel("键盘快捷键") {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val fullColumns = maxWidth >= (360f * LocalDensity.current.fontScale.coerceAtLeast(1f)).dp
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DesktopShortcuts.reference.forEach { (keys, action) ->
                    if (fullColumns) {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(keys, Modifier.weight(2f), style = MaterialTheme.typography.labelLarge)
                            Hint(action, Modifier.weight(3f))
                        }
                    } else {
                        Column {
                            Text(keys, style = MaterialTheme.typography.labelLarge)
                            Hint(action)
                        }
                    }
                }
            }
        }
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

// ---------------------------------------------------------------- updates

/**
 * A newer build, near the top whichever group is open: the check runs on
 * start-up, and its answer should not hide at the foot of one form.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
private fun UpdateBanner(state: AppState) {
    val update = state.update
    val manifest = when (update) {
        is UpdateState.Available -> update.manifest
        is UpdateState.Downloading -> update.manifest
        is UpdateState.Downloaded -> update.manifest
        is UpdateState.Installing -> update.manifest
        is UpdateState.Failed -> update.manifest ?: return
        else -> return
    }
    val scheme = MaterialTheme.colorScheme
    Surface(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.largeIncreased,
        color = scheme.tertiaryContainer,
        contentColor = scheme.onTertiaryContainer
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Filled.Update, contentDescription = null)
                SettingsHeading("有新版本 ${manifest.version}")
            }
            Text(
                "当前 ${BuildInfo.VERSION}" + (manifest.publishedAt?.take(10)?.let { " · 发布于 $it" } ?: ""),
                style = MaterialTheme.typography.bodyMedium
            )
            manifest.notes?.takeIf { it.isNotBlank() }?.let { notes ->
                Text(notes, style = MaterialTheme.typography.bodyMedium, maxLines = 8, overflow = TextOverflow.Ellipsis)
            }
            when (update) {
                is UpdateState.Available -> SettingsActions {
                    val asset = update.asset
                    if (asset != null) {
                        Button(onClick = { state.downloadUpdate() }) {
                            PrimaryLabel("下载并安装" + formatSize(asset.size).takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty())
                        }
                    } else {
                        Text("这个版本没有给 ${PlatformInfo.name} 的安装包", style = MaterialTheme.typography.bodyMedium)
                    }
                    manifest.pageUrl?.let { url ->
                        TextButton(
                            onClick = { openUrl(url) },
                            colors = ButtonDefaults.textButtonColors(contentColor = scheme.onTertiaryContainer)
                        ) { Text("发布页") }
                    }
                }
                is UpdateState.Downloading -> {
                    val total = update.total
                    val received = formatSize(update.received).ifBlank { "0 B" }
                    Text(
                        if (total != null && total > 0) "正在下载 $received / ${formatSize(total)}" else "正在下载 $received",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (total != null && total > 0) {
                        LinearWavyProgressIndicator(
                            progress = { (update.received.toFloat() / total).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
                is UpdateState.Downloaded -> SettingsActions {
                    Button(onClick = { state.installUpdate() }) { PrimaryLabel(installLabel()) }
                    if (PlatformInfo.isDesktop) {
                        TextButton(
                            onClick = { revealInFileManager(update.file) },
                            colors = ButtonDefaults.textButtonColors(contentColor = scheme.onTertiaryContainer)
                        ) { Text("定位文件") }
                    }
                }
                is UpdateState.Installing -> Text("正在退出并启动安装程序…", style = MaterialTheme.typography.bodyMedium)
                is UpdateState.Failed -> {
                    SettingsNotice(update.message, SettingsTone.Error)
                    if (update.asset != null) {
                        Button(onClick = { state.downloadUpdate() }) { PrimaryLabel("重试") }
                    }
                }
                else -> Unit
            }
        }
    }
}

/** What pressing the button does on this platform, said plainly. */
private fun installLabel(): String = when {
    PlatformInfo.isAndroid -> "安装"
    PlatformInfo.name.startsWith("Windows") -> "安装并重启"
    else -> "打开安装包"
}

@Composable
private fun UpdateSection(state: AppState) {
    SettingsPanel("应用更新") {
        val update = state.update
        val busy = update is UpdateState.Checking || update is UpdateState.Downloading || update is UpdateState.Installing
        FilledTonalButton(onClick = { state.checkForUpdates() }, enabled = !busy) { Text("检查更新") }
        when (update) {
            UpdateState.Idle -> Unit
            UpdateState.Checking -> Hint("正在检查…")
            is UpdateState.UpToDate -> SettingsNotice("已是最新版本（${update.version}）", SettingsTone.Success)
            is UpdateState.Failed ->
                if (update.manifest == null) SettingsNotice(update.message, SettingsTone.Error) else Hint("有新版本，见页面顶部")
            else -> Hint("有新版本，见页面顶部")
        }
        if (!canSelfUpdate()) {
            Hint("这是调试版本：不会自动检查更新，发布版的安装包也装不到它上面")
        }
        SettingsSwitch(
            "启动时自动检查更新",
            state.checkUpdatesOnStartup,
            detail = "仅在发现新版本时提示"
        ) { state.changeCheckUpdatesOnStartup(it) }
        OutlinedTextField(
            state.updateSource,
            { state.changeUpdateSource(it) },
            label = { Text("更新清单地址") },
            placeholder = { Text(DEFAULT_UPDATE_SOURCE) },
            supportingText = { Text("留空使用默认地址：仓库 updates 分支上的 update.json，每次发布由 CI 写入") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().tracksTextInput()
        )
        OutlinedTextField(
            state.updateMirror,
            { state.changeUpdateMirror(it) },
            label = { Text("GitHub 镜像前缀（可选）") },
            placeholder = { Text("https://ghproxy.net/") },
            supportingText = { Text("加在清单和安装包地址前面，用于难以直连 GitHub 的网络") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().tracksTextInput()
        )
    }
}
