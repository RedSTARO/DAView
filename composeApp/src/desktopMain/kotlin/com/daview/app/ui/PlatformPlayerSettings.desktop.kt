package com.daview.app.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.daview.app.data.tracksTextInput
import com.daview.app.platform.PlatformInfo
import com.daview.app.platform.chooseFile
import com.daview.app.platform.customPlayerPath
import com.daview.app.platform.setCustomPlayerPath
import com.daview.app.player.MpvNative
import com.daview.app.player.PlayerPreferences
import com.daview.app.player.VideoAdapter
import com.daview.app.player.VideoEnhancement

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

    SettingsPanel("内置播放器") {
        key(refresh) {
            val available = PlatformInfo.hasInternalPlayer
            SettingsNotice(
                if (available) "libmpv 可用" else "未找到 libmpv · 播放时会使用外部播放器",
                if (available) SettingsTone.Success else SettingsTone.Attention
            )
        }
        SettingsActions {
            FilledTonalButton(onClick = {
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
    }

    SettingsPanel(
        "自定义外部播放器",
        customPlayerPath()?.let { "自定义播放器：$it" }
            ?: "除了自动找到的 PotPlayer / VLC / mpv，也可以指定其他播放器程序。"
    ) {
        SettingsActions {
            FilledTonalButton(onClick = {
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
    }

    val windows = MpvNative.isWindows
    SettingsPanel(
        "RTX 视频增强",
        if (windows) "需要 NVIDIA RTX 显卡，并在 NVIDIA 控制面板或 NVIDIA App 中开启 RTX 视频增强。"
        else "只在 Windows 上可用。"
    ) {
        SettingsSwitch(
            label = "超分辨率",
            checked = enhancement.superResolution,
            enabled = windows,
            detail = "用显卡把低分辨率片源放大并锐化。"
        ) { update(enhancement.copy(superResolution = it)) }
        if (enhancement.superResolution) {
            Text("放大倍率", style = MaterialTheme.typography.labelLarge)
            SettingsChoices(
                (VideoEnhancement.MIN_SCALE..VideoEnhancement.MAX_SCALE).map { it to "$it×" },
                enhancement.scale
            ) { update(enhancement.copy(scale = it)) }
        }
        SettingsSwitch(
            label = "HDR 转换",
            checked = enhancement.videoHdr,
            enabled = windows,
            detail = "将普通（SDR）片源转为 HDR 显示。系统和显示器都要开启 HDR；不影响 HDR 片源。"
        ) { update(enhancement.copy(videoHdr = it)) }
    }
    if (windows) {
        SettingsPanel("播放显卡", "双显卡笔记本上，RTX 视频增强只在 NVIDIA 独显上生效。启用增强时，「自动」会选择 NVIDIA。") {
            SettingsChoices(listOf(null to "自动") + adapters.map { it to it }, adapter) {
                adapter = it
                PlayerPreferences.adapter = it
                customRejected = false
            }
            PlayerPreferences.lastAdapterInUse?.let { used ->
                Text("上次播放使用：$used", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    SettingsPanel("播放器技术细节") {
        TextButton(onClick = { showDetails = !showDetails }) {
            Text(if (showDetails) "收起技术细节" else "展开技术细节")
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
                    Text("libmpv：$detail", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (windows) {
                OutlinedTextField(
                    value = customAdapter,
                    onValueChange = { customAdapter = it; customRejected = false },
                    label = { Text("按名称指定显卡") },
                    supportingText = {
                        Text(if (customRejected) "没有匹配的显卡" else "输入显卡名称的开头，不区分大小写。")
                    },
                    singleLine = true,
                    isError = customRejected,
                    modifier = Modifier.fillMaxWidth().tracksTextInput()
                )
                FilledTonalButton(
                    enabled = customAdapter.isNotBlank(),
                    onClick = {
                        // Validate before storing; an invalid name can otherwise black out playback.
                        if (VideoAdapter.accepts(customAdapter)) {
                            adapter = customAdapter
                            PlayerPreferences.adapter = customAdapter
                            customRejected = false
                        } else {
                            customRejected = true
                        }
                    }
                ) { Text("使用此显卡") }
            }
        }
    }
}

/**
 * The platform chooser, for picking any player executable. Through the shared
 * chooser, which filters in a way Windows' dialog honours and opens where the
 * last one was left.
 */
private fun pickExecutable(): String? {
    val windows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
    return chooseFile("选择播放器程序", if (windows) listOf("exe") else emptyList())?.absolutePath
}

/** The platform chooser, filtered to shared libraries. */
private fun pickLibrary(): String? =
    chooseFile("选择 libmpv", listOf("dll", "so", "dylib"))?.absolutePath
