package com.daview.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.daview.app.data.AppState
import com.daview.app.data.PlaybackController
import com.daview.app.data.UpNext
import com.daview.app.platform.PlatformInfo
import com.daview.app.platform.copyToClipboard
import kotlinx.coroutines.delay

@Composable
fun PlayerScreen(state: AppState, playback: PlaybackController) {
    val info = playback.info
    val upNext = playback.upNext
    val external = playback.externalPlayerLabel != null

    // What the viewer set on the player — the rotation lock, the subtitle delay
    // — lives here, one level above the player, because the player itself is
    // built anew for every episode.
    val screen = remember { PlayerScreenState() }

    // Presentation belongs to the screen, not to one episode's player: the
    // hand-over to the next episode used to drop out of full screen and back.
    if (!external) PlaybackPresentation(screen.orientation, fullscreen = state.autoFullscreen)

    Box(Modifier.fillMaxSize().background(if (external) MaterialTheme.colorScheme.background else Color.Black)) {
        when {
            upNext != null -> UpNextCard(playback, upNext)
            info == null -> if (playback.starting) Switching(playback.startingName) else EmptyState(
                "没有正在播放的内容",
                "回到媒体库选择要播放的影片。"
            ) {
                Button(onClick = { state.back() }) { Text("返回") }
            }
            external || !PlatformInfo.hasInternalPlayer -> ExternalPlaybackPanel(state, playback)
            playback.starting -> Switching(playback.startingName)
            // One player per session. Reusing one across episodes is what left
            // the Android player on the last frame of the episode before.
            else -> key(info.sessionId) {
                InternalPlayer(
                    info = info,
                    screen = screen,
                    subtitleScale = state.subtitleScale,
                    onProgress = { position, paused, audio, subtitle ->
                        playback.reportProgress(position, paused, audio, subtitle)
                    },
                    onClose = { position -> playback.closePlayer(info.sessionId, position) },
                    // Reaching the end of a file is a different thing from
                    // being closed: it is the one moment where going on to the
                    // next episode is what the viewer wants.
                    onEnded = { position -> playback.onEnded(info.sessionId, position) },
                    onSkip = { itemId, position ->
                        // Another episode starts at its beginning; trying this one
                        // again carries on from where it stopped.
                        playback.skipTo(itemId, fromStart = itemId != info.item.id, positionMs = position)
                    }
                )
            }
        }
    }
}

/** Between two episodes, while the next one is being opened. */
@Composable
private fun Switching(name: String?) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(color = Color.White)
        Spacer(Modifier.height(16.dp))
        Text(
            name?.let { "正在打开 $it" } ?: "正在打开",
            color = Color.White.copy(alpha = 0.85f),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

/**
 * What comes next, with a count-down, instead of a cut straight into it: the
 * next episode can be started now or declined. After three episodes nobody
 * touched it waits to be asked.
 */
@Composable
private fun UpNextCard(playback: PlaybackController, upNext: UpNext) {
    var remaining by remember(upNext) { mutableStateOf(upNext.startsAt?.let { ((it - System.currentTimeMillis()) / 1000).coerceAtLeast(0) }) }
    LaunchedEffect(upNext) {
        val deadline = upNext.startsAt ?: return@LaunchedEffect
        while (System.currentTimeMillis() < deadline) {
            remaining = ((deadline - System.currentTimeMillis() + 999) / 1000).coerceAtLeast(0)
            delay(200)
        }
        playback.playUpNext(auto = true)
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.Enter, Key.NumPadEnter, Key.Spacebar -> { playback.playUpNext(auto = false); true }
                    Key.Escape, Key.Backspace -> { playback.cancelUpNext(); true }
                    else -> false
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.widthIn(max = 480.dp).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                if (upNext.stillWatching) "还在看吗？" else "下一集",
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(8.dp))
            Text(
                upNext.name,
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    upNext.stillWatching -> "已经连续自动播放了好几集，按下面的按钮接着看。"
                    else -> "${remaining ?: 0} 秒后自动播放"
                },
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { playback.playUpNext(auto = false) }) {
                    Text(if (upNext.stillWatching) "继续看" else "立即播放")
                }
                OutlinedButton(onClick = { playback.cancelUpNext() }) {
                    Text(if (upNext.stillWatching) "不看了" else "取消", color = Color.White)
                }
            }
        }
    }
}

/**
 * Shown while an external player has the file. Position comes from the byte
 * ranges the player asks for — close, not exact, and the panel says so.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ExternalPlaybackPanel(state: AppState, playback: PlaybackController) {
    val info = playback.info ?: return
    val session = playback.externalSession
    val label = playback.externalPlayerLabel ?: "外部播放器"

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.widthIn(max = 560.dp).padding(24.dp)
        ) {
            Column(Modifier.padding(28.dp)) {
                Text(
                    "正在通过 $label 播放",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.secondary
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    info.item.seriesName?.let { "$it · ${listOfNotNull(info.item.episodeLabel, info.item.name).joinToString(" ")}" }
                        ?: info.item.name,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2
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
                        "cue+clock", "clock" -> "进度是估算的，可能差几秒；暂停时这里仍会走动"
                        else -> "等待 $label 开始播放…"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(22.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Browsing while the film plays: the panel steps aside and
                    // the player keeps going. The only way out used to be the
                    // button that also killed the player.
                    FilledTonalButton(onClick = { playback.leaveExternalPanel() }) {
                        Text("返回浏览")
                    }
                    TextButton(onClick = {
                        playback.stopExternal()
                        state.back()
                    }) {
                        Text("结束播放并关闭 $label")
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text(
                    "关掉 $label 时进度会自动记下，并同步到其它设备。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(
                    onClick = {
                        copyToClipboard(info.streamUrl)
                        state.notify("已复制。这个地址只在本机、本次播放期间有效")
                    },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                ) {
                    Text("复制播放地址（给别的播放器用）", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
