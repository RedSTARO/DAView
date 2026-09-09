package com.daview.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import com.daview.app.player.PlayerPreferences
import com.daview.app.player.VideoAdapter
import com.daview.app.player.VideoEnhancement
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * The desktop player's settings: where libmpv is, and the two NVIDIA switches.
 *
 * The switches are stated as requests rather than states. Nothing in this
 * process can read back whether the driver is really doing the work — mpv only
 * checks that the extension call returned success, and the features additionally
 * depend on a toggle in the NVIDIA app that no API here exposes. So the honest
 * thing is to say what will be asked for, and let the player screen report what
 * mpv logged when it asked.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
actual fun PlatformPlayerSettings() {
    var refresh by remember { mutableStateOf(0) }
    var enhancement by remember { mutableStateOf(PlayerPreferences.enhancement) }
    var adapter by remember { mutableStateOf(PlayerPreferences.adapter) }
    var adapters by remember { mutableStateOf(emptyList<String>()) }
    var customAdapter by remember { mutableStateOf("") }
    var customRejected by remember { mutableStateOf(false) }

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
                if (available) "libmpv 已加载" else "未找到 libmpv，播放会交给外部播放器",
                style = MaterialTheme.typography.bodyMedium,
                color = if (available) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
            val detail = if (available) {
                listOfNotNull(MpvNative.loadedFrom, MpvNative.apiVersion?.let { "客户端 API $it" })
                    .joinToString(" · ")
            } else {
                MpvNative.loadError.orEmpty()
            }
            if (detail.isNotBlank()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
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

        if (MpvNative.isWindows) {
            Spacer(Modifier.height(12.dp))
            Text("显卡", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "笔记本上画面通常由核显驱动，而 NVIDIA 的视频增强只存在于独显上——" +
                    "跑错卡时驱动只会把调用失败掉。“自动”在开了 RTX 时会要求走 NVIDIA，" +
                    "其余情况交给 mpv 自己选。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
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
                    ) { Text(candidate) }
                }
            }

            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = customAdapter,
                    onValueChange = { customAdapter = it; customRejected = false },
                    label = { Text("或填显卡名称前缀") },
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
                when {
                    customRejected -> "没有显卡的名称以它开头"
                    else -> "按显卡描述的前缀匹配，不区分大小写，取第一个命中的。" +
                        (PlayerPreferences.lastAdapterInUse?.let { "上次实际用的是：$it" } ?: "")
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (customRejected) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "NVIDIA 视频增强",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            "RTX Video Super Resolution 与 RTX Video HDR，走 D3D11 视频处理器。" +
                "这两项不是 DLSS——DLSS 需要渲染管线提供的运动矢量与深度，解码出来的视频帧没有这些。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))

        val windows = MpvNative.isWindows
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("RTX Video Super Resolution")
                Text(
                    "把画面交给显卡放大再输出。倍率必须大于 1——倍率为 1 时滤镜会整条直通，" +
                        "驱动扩展根本不会被设置，界面上看不出任何区别。",
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
            Row(
                Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("放大倍率", style = MaterialTheme.typography.bodySmall)
                (VideoEnhancement.MIN_SCALE..VideoEnhancement.MAX_SCALE).forEach { scale ->
                    ToggleButton(
                        checked = enhancement.scale == scale,
                        onCheckedChange = { update(enhancement.copy(scale = scale)) }
                    ) { Text("${scale}x") }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("RTX Video HDR")
                Text(
                    "把 SDR 片源转成 HDR10 输出。源本身已经是 HDR 时驱动会跳过，" +
                        "并且需要系统与显示器都开着 HDR。",
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

        Spacer(Modifier.height(8.dp))
        Text(
            if (windows) {
                "两项都要求 NVIDIA RTX 显卡，并且在 NVIDIA 控制面板 / NVIDIA App 的" +
                    "「调整视频图像设置」里打开对应开关——否则驱动会接受调用但什么也不做。" +
                    "双显卡笔记本上，播放时会显式要求走 NVIDIA 适配器。"
            } else {
                "这两项只在 Windows 上存在，当前系统不可用；内置播放器本身仍然可以使用。"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
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
