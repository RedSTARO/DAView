package com.daview.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.daview.app.data.AppState
import com.daview.app.data.PlaybackController
import com.daview.app.data.Screen
import com.daview.app.platform.PlatformInfo
import com.daview.app.platform.copyToClipboard
import com.daview.app.platform.openUrl
import com.daview.shared.model.PlaybackInfoDto

/**
 * In-app playback surface. Only Android ships one today; desktop and web hand
 * off to an external player and show [ExternalPlaybackPanel] instead.
 */
@Composable
expect fun InternalPlayer(
    info: PlaybackInfoDto,
    onProgress: (positionMs: Long, paused: Boolean, audioIndex: Int?, subtitleIndex: Int?) -> Unit,
    onClose: (positionMs: Long) -> Unit
)

@Composable
fun PlayerScreen(state: AppState, playback: PlaybackController) {
    val info = playback.info
    if (info == null) {
        EmptyState("没有正在播放的内容", "回到媒体库选择要播放的影片。") {
            Button(onClick = { state.back() }) { Text("返回") }
        }
        return
    }

    if (PlatformInfo.hasInternalPlayer) {
        InternalPlayer(
            info = info,
            onProgress = { position, paused, audio, subtitle ->
                playback.reportProgress(position, paused, audio, subtitle)
            },
            onClose = { position ->
                playback.stop(position)
                state.back()
            }
        )
    } else {
        ExternalPlaybackPanel(state, playback)
    }
}

/**
 * Shown while an external player has the file. Position comes from the server,
 * which derives it from the byte ranges the player requests — it is close, not
 * exact, and the panel says so.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ExternalPlaybackPanel(state: AppState, playback: PlaybackController) {
    val info = playback.info ?: return
    val session = playback.externalSession

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.widthIn(max = 560.dp).padding(24.dp)
        ) {
            Column(Modifier.padding(28.dp)) {
                Text(
                    playback.externalPlayerLabel?.let { "正在通过 $it 播放" } ?: "外部播放",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.secondary
                )
                Spacer(Modifier.height(4.dp))
                // Whatever is playing has a page of its own: the show when it
                // belongs to one, the film otherwise.
                LinkText(
                    info.item.seriesName?.let { "$it · ${info.item.name}" } ?: info.item.name,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    onClick = { state.navigate(Screen.Detail(info.item.seriesId ?: info.item.id)) }
                )

                Spacer(Modifier.height(20.dp))
                val runtime = session?.runtimeMs ?: info.runtimeMs
                val position = session?.positionMs ?: info.startPositionMs
                LinearWavyProgressIndicator(
                    progress = {
                        if (runtime != null && runtime > 0) (position.toFloat() / runtime).coerceIn(0f, 1f) else 0f
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatDuration(position), style = MaterialTheme.typography.bodySmall)
                    Text(formatDuration(runtime), style = MaterialTheme.typography.bodySmall)
                }

                Spacer(Modifier.height(6.dp))
                Text(
                    when (session?.positionSource) {
                        "client" -> "进度由播放器上报"
                        "cue+clock" -> "进度依据 Matroska 索引与播放时长推算，可能有数秒误差"
                        "clock" -> "进度依据字节位置与播放时长推算，可能有误差"
                        else -> "等待播放器开始读取…"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(22.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(onClick = { copyToClipboard(info.streamUrl) }) {
                        Text("复制播放地址")
                    }
                    TextButton(onClick = {
                        playback.stopExternal()
                        state.back()
                    }) {
                        Text("结束播放")
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text(
                    "播放地址指向 DAView 服务器，服务器再跳转到存储直链；" +
                        "保持这个页面打开，进度会自动同步到所有客户端。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
