package com.daview.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.daview.app.platform.PlatformInfo
import com.daview.app.player.MpvNative
import com.daview.app.platform.customPlayerPath
import com.daview.app.platform.setCustomPlayerPath
import com.daview.app.player.PlayerPreferences
import com.daview.app.player.VideoAdapter
import com.daview.app.player.VideoEnhancement
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * The desktop player's settings: where libmpv is, the external player, and
 * the two NVIDIA switches. Written for someone choosing, not someone
 * debugging — the load path and naming an adapter by hand sit behind a fold.
 *
 * The switches are stated as requests rather than states. Nothing in this
 * process can read back whether the driver is really doing the work — mpv only
 * checks that the extension call returned success, and the features additionally
 * depend on a toggle in the NVIDIA app that no API here exposes. So the honest
 * thing is to say what will be asked for, and let the player screen report what
 * mpv logged when it asked.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
actual fun PlatformPlayerSettings() {
    var refresh by remember { mutableStateOf(0) }
    var enhancement by remember { mutableStateOf(PlayerPreferences.enhancement) }
    var adapter by remember { mutableStateOf(PlayerPreferences.adapter) }
    var adapters by remember { mutableStateOf(emptyList<String>()) }
    var customAdapter by remember { mutableStateOf("") }
    var customRejected by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }

    // Asking costs one throwaway mpv instance per candidate, so it happens once
    // when the page opens rather than on every recomposition.
    LaunchedEffect(refresh) {
        adapters = VideoAdapter.available()
        // A stored choice that is not one of the offered ones is a typed-in
        // description; keep it in the field so it can be seen and edited.
        if (adapter != null && adapter !in adapters) customAdapter = adapter.orEmpty()
    }

    fun update(value: VideoEnhancement) {
        enhancement = value
        PlayerPreferences.enhancement = value
    }

    Column(Modifier.padding(horizontal = 20.dp)) {
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text("内置播放器", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))

        key(refresh) {
            val available = PlatformInfo.hasInternalPlayer
            Text(
                if (available) "可以使用" else "不可用：没有找到 libmpv，播放会交给外部播放器",
                style = MaterialTheme.typography.bodyMedium,
                color = if (available) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                pickLibrary()?.let {
                    PlayerPreferences.libmpvPath = it
                    refresh++
                }
            }) { Text("指定 libmpv…") }
            if (PlayerPreferences.libmpvPath != null) {
                TextButton(onClick = {
                    PlayerPreferences.libmpvPath = null
                    refresh++
                }) { Text("恢复默认位置") }
            }
        }

        Spacer(Modifier.height(12.dp))
        Text("外部播放器", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            customPlayerPath()?.let { "自定义播放器：$it" }
                ?: "除了自动找到的 PotPlayer / VLC / mpv，也可以指定任意一个播放器程序。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                pickExecutable()?.let {
                    setCustomPlayerPath(it)
                    refresh++
                }
            }) { Text("指定播放器程序…") }
            if (customPlayerPath() != null) {
                TextButton(onClick = {
                    setCustomPlayerPath(null)
                    refresh++
                }) { Text("清除") }
            }
        }

        val windows = MpvNative.isWindows
        Spacer(Modifier.height(12.dp))
        Text("RTX 视频增强", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            if (windows) {
                "需要 NVIDIA RTX 显卡，并在 NVIDIA 控制面板或 NVIDIA App 里打开「RTX 视频增强」，否则开了也没有效果。"
            } else {
                "只在 Windows 上可用。"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("超分辨率")
                Text(
                    "用显卡把低分辨率片源放大并锐化。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(16.dp))
            Switch(
                checked = enhancement.superResolution,
                enabled = windows,
                onCheckedChange = { update(enhancement.copy(superResolution = it)) }
            )
        }

        if (enhancement.superResolution) {
            FlowRow(
                Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically
            ) {
                Text("放大倍率", style = MaterialTheme.typography.bodySmall)
                (VideoEnhancement.MIN_SCALE..VideoEnhancement.MAX_SCALE).forEach { scale ->
                    ToggleButton(
                        checked = enhancement.scale == scale,
                        onCheckedChange = { update(enhancement.copy(scale = scale)) }
                    ) { Text("${scale}×") }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("HDR 转换")
                Text(
                    "把普通（SDR）片源转成 HDR 显示。系统和显示器都要开着 HDR；片源本身是 HDR 时不起作用。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(16.dp))
            Switch(
                checked = enhancement.videoHdr,
                enabled = windows,
                onCheckedChange = { update(enhancement.copy(videoHdr = it)) }
            )
        }

        if (windows) {
            Spacer(Modifier.height(12.dp))
            Text("用哪块显卡播放", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "双显卡笔记本上，RTX 视频增强只在 NVIDIA 独显上生效。「自动」在开启增强时会选 NVIDIA。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            // Adapter names run long; a fixed row pushed the last ones off the edge.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                ToggleButton(
                    checked = adapter == null,
                    onCheckedChange = {
                        adapter = null
                        PlayerPreferences.adapter = null
                        customRejected = false
                    }
                ) { Text("自动") }
                adapters.forEach { candidate ->
                    ToggleButton(
                        checked = adapter == candidate,
                        onCheckedChange = {
                            adapter = candidate
                            PlayerPreferences.adapter = candidate
                            customRejected = false
                        }
                    ) { Text(candidate, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            PlayerPreferences.lastAdapterInUse?.let { used ->
                Text(
                    "上次播放用的是：$used",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // What only matters when something is not working: where libmpv was
        // loaded from, and naming an adapter by hand.
        TextButton(onClick = { showDetails = !showDetails }, contentPadding = PaddingValues(0.dp)) {
            Text(if (showDetails) "收起技术细节" else "技术细节")
        }
        if (showDetails) {
            key(refresh) {
                val detail = if (PlatformInfo.hasInternalPlayer) {
                    listOfNotNull(MpvNative.loadedFrom, MpvNative.apiVersion?.let { "客户端 API $it" })
                        .joinToString(" · ")
                } else {
                    MpvNative.loadError.orEmpty()
                }
                if (detail.isNotBlank()) {
                    Text(
                        "libmpv：$detail",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (windows) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = customAdapter,
                        onValueChange = { customAdapter = it; customRejected = false },
                        label = { Text("按名称指定显卡") },
                        singleLine = true,
                        isError = customRejected,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        enabled = customAdapter.isNotBlank(),
                        onClick = {
                            // mpv rejects a name matching no adapter at the moment it
                            // is set, so a typo can be refused here instead of turning
                            // into a black screen at the next play.
                            if (VideoAdapter.accepts(customAdapter)) {
                                adapter = customAdapter
                                PlayerPreferences.adapter = customAdapter
                                customRejected = false
                            } else {
                                customRejected = true
                            }
                        }
                    ) { Text("使用") }
                }
                Text(
                    if (customRejected) "没有显卡的名称以它开头" else "填显卡名称的开头部分即可，不区分大小写。",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (customRejected) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** The platform chooser, for picking any player executable. */
private fun pickExecutable(): String? {
    val dialog = FileDialog(null as Frame?, "选择播放器程序", FileDialog.LOAD).apply {
        isVisible = true
    }
    val file = dialog.file ?: return null
    return File(dialog.directory ?: "", file).absolutePath
}

/** The platform chooser, filtered to shared libraries. */
private fun pickLibrary(): String? {
    val dialog = FileDialog(null as Frame?, "选择 libmpv", FileDialog.LOAD).apply {
        setFilenameFilter { _, name ->
            name.endsWith(".dll", true) || name.contains(".so") || name.endsWith(".dylib", true)
        }
        isVisible = true
    }
    val file = dialog.file ?: return null
    return File(dialog.directory ?: "", file).absolutePath
}
