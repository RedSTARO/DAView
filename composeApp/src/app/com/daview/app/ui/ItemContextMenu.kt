package com.daview.app.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import com.daview.app.data.AppState
import com.daview.app.data.Confirmation
import com.daview.app.data.PlaybackController
import com.daview.app.data.Screen
import com.daview.app.theme.favoriteColor
import com.daview.shared.model.DownloadState
import com.daview.shared.model.ItemKind
import com.daview.shared.model.MediaItemDto
import com.daview.shared.model.PlayedState

/**
 * The right-click half of "open this item's menu". The long-press half lives in
 * [combinedClickable][androidx.compose.foundation.combinedClickable] at the call
 * site, because tap and long-press have to be decided by one detector or the two
 * fight over the same gesture — this one only ever sees the secondary button.
 *
 * It listens on the initial pass and consumes what it takes, so the card
 * underneath never sees the press and the detail page does not open behind the
 * menu. [onPointerType] reports what pressed last, which is how the caller keeps
 * a long press on a mouse from opening the menu a second way.
 *
 * The detector is installed once and never restarted, so both callbacks must
 * stay valid for the life of the node: write state from them, do not close over
 * a value that changes.
 */
fun Modifier.secondaryClick(
    onPointerType: (PointerType) -> Unit,
    onOpen: (Offset) -> Unit
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        if (event.type != PointerEventType.Press) return@awaitEachGesture
        val change = event.changes.firstOrNull() ?: return@awaitEachGesture
        onPointerType(change.type)
        if (!event.buttons.isSecondaryPressed) return@awaitEachGesture

        onOpen(change.position)
        // Swallow the whole gesture, release included: a right-click that ends
        // on the card would otherwise register as a click on it.
        change.consume()
        while (true) {
            val next = awaitPointerEvent(PointerEventPass.Initial)
            next.changes.forEach { it.consume() }
            if (next.changes.none { it.pressed }) return@awaitEachGesture
        }
    }
}

/**
 * Menu anchor, converted where it is used: the gesture reports pixels from the
 * card's corner, [androidx.compose.material3.DropdownMenu] wants dp. Keeping the
 * raw offset in state is what lets the gesture callbacks close over nothing that
 * can change. A null offset — a long press, which has no cursor — is the corner.
 */
internal fun Offset?.toDpOffset(density: Density): DpOffset =
    this?.let { with(density) { DpOffset(it.x.toDp(), it.y.toDp()) } } ?: DpOffset.Zero

/**
 * The same menu, shaped for the `menu` parameter of [PosterCard] and [MediaRow]
 * so a screen full of cards names it once.
 */
fun cardMenu(
    state: AppState,
    playback: PlaybackController?
): @Composable ColumnScope.(MediaItemDto, () -> Unit) -> Unit =
    { item, dismiss -> ItemMenuItems(state, playback, item, dismiss) }

/**
 * What a right-click on a card offers. Everything here acts on the item alone;
 * anything that has to be confirmed asks through a dialog the app hosts, since
 * the menu is gone the moment an entry is picked.
 *
 * [playback] is null on screens that cannot start playback; the playback entries
 * are then left out rather than shown dead.
 */
@Composable
fun ColumnScope.ItemMenuItems(
    state: AppState,
    playback: PlaybackController?,
    item: MediaItemDto,
    dismiss: () -> Unit
) {
    if (playback != null && item.isPlayable) {
        DropdownMenuItem(
            leadingIcon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
            text = {
                Text(
                    if (item.userData.positionMs > 0) "继续 ${formatDuration(item.userData.positionMs)}"
                    else "播放"
                )
            },
            onClick = {
                dismiss()
                playback.play(item)
            }
        )
        if (item.userData.positionMs > 0) {
            // Watching something again used to mean marking it unwatched first,
            // which threw the resume point away in the process.
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Filled.Replay, contentDescription = null) },
                text = { Text("从头播放") },
                onClick = {
                    dismiss()
                    playback.play(item, startPositionMs = 0L)
                }
            )
        }
        playback.externalPlayers.forEach { player ->
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Filled.OpenInNew, contentDescription = null) },
                text = {
                    Text("用 ${player.label} 播放", maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                onClick = {
                    dismiss()
                    playback.playExternal(item, player)
                }
            )
        }
        HorizontalDivider()
    } else if (playback != null && (item.episodeCount ?: 0) > 0) {
        // A series or a season plays the episode it is up to.
        DropdownMenuItem(
            leadingIcon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
            text = { Text("播放下一集") },
            onClick = {
                dismiss()
                playback.play(item)
            }
        )
        HorizontalDivider()
    }

    val played = item.playedState == PlayedState.PLAYED
    // A series or season has no watched flag of its own — marking it flips every
    // episode under it — so the label says how wide the change reaches, and the
    // change asks first.
    if (item.isPlayable || (item.episodeCount ?: 0) > 0) {
        DropdownMenuItem(
            // A menu item's icon says what the command is, not what state the
            // item is in.
            leadingIcon = {
                Icon(
                    Icons.Filled.Done,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            text = { Text(playedActionLabel(item, played)) },
            onClick = {
                dismiss()
                if (item.isPlayable) {
                    state.togglePlayed(item)
                } else {
                    state.confirm(
                        Confirmation(
                            title = if (played) "把 ${item.episodeCount} 集都标记为未观看？" else "把 ${item.episodeCount} 集都标记为已观看？",
                            text = "「${item.name}」下的每一集都会改，已有的播放进度会被清除。改完后提示条上可以撤销。",
                            confirmLabel = if (played) "全部标记未看" else "全部标记已看",
                            action = { state.togglePlayed(item) }
                        )
                    )
                }
            }
        )
    }

    DropdownMenuItem(
        leadingIcon = {
            Icon(
                if (item.userData.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = null,
                tint = if (item.userData.favorite) favoriteColor
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        text = { Text(if (item.userData.favorite) "取消收藏" else "收藏") },
        onClick = {
            dismiss()
            state.toggleFavorite(item)
        }
    )

    // Only where it is actually on that shelf: partly watched, not finished.
    if (item.isPlayable && item.userData.positionMs > 0 && !item.userData.played) {
        DropdownMenuItem(
            leadingIcon = {
                Icon(
                    Icons.Filled.VisibilityOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            text = { Text("从继续观看中移除") },
            onClick = {
                dismiss()
                state.hideFromResume(item)
            }
        )
    }

    // Only a playable item has bytes worth keeping; a series or a season keeps
    // every episode under it.
    if (item.isPlayable) {
        val existing = state.downloadOf(item.id)
        DropdownMenuItem(
            leadingIcon = {
                Icon(
                    when (existing?.state) {
                        DownloadState.DONE -> Icons.Filled.DownloadDone
                        DownloadState.FAILED -> Icons.Filled.ErrorOutline
                        else -> Icons.Filled.Download
                    },
                    contentDescription = null,
                    tint = when (existing?.state) {
                        DownloadState.DONE -> MaterialTheme.colorScheme.primary
                        DownloadState.FAILED -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            },
            text = {
                when (existing?.state) {
                    DownloadState.DONE -> Text("删除本地文件…")
                    DownloadState.RUNNING, DownloadState.QUEUED -> Text("取消下载（${(existing.fraction * 100).toInt()}%）")
                    // A failed download used to read as never having been tried.
                    DownloadState.FAILED -> Column {
                        Text("重新下载")
                        Text(
                            "上次失败：${existing.error ?: "未知原因"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    null -> Text("下载到本机")
                }
            },
            onClick = {
                dismiss()
                when (existing?.state) {
                    // Deleting a finished copy asks first, as deleting a
                    // library does: the bytes took minutes to arrive.
                    DownloadState.DONE -> state.confirm(
                        Confirmation(
                            title = "删除「${item.name}」的本地文件？",
                            text = ("删除后${if (item.kind == ItemKind.EPISODE) "这一集" else "它"}要再联网播放或重新下载。" +
                                formatSize(existing.totalBytes)).trim(),
                            confirmLabel = "删除",
                            destructive = true,
                            action = { state.removeDownload(item.id) }
                        )
                    )
                    DownloadState.RUNNING, DownloadState.QUEUED -> state.cancelDownload(item)
                    else -> state.download(item)
                }
            }
        )
    } else if ((item.episodeCount ?: 0) > 0) {
        DropdownMenuItem(
            leadingIcon = { Icon(Icons.Filled.Download, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            text = { Text(if (item.kind == ItemKind.SEASON) "下载本季（${item.episodeCount} 集）" else "下载全部（${item.episodeCount} 集）") },
            onClick = {
                dismiss()
                state.download(item)
            }
        )
    }

    // Only whole films and series carry scraped metadata.
    if (item.kind == ItemKind.MOVIE || item.kind == ItemKind.SERIES) {
        DropdownMenuItem(
            leadingIcon = {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            text = { Text("重新刮削") },
            onClick = {
                dismiss()
                state.refreshMetadata(item)
            }
        )
    }

    if (state.current != Screen.Detail(item.id)) {
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text("查看详情") },
            onClick = {
                dismiss()
                state.navigate(Screen.Detail(item.id))
            }
        )
    }
}

private fun playedActionLabel(item: MediaItemDto, played: Boolean): String = when {
    item.isPlayable -> if (played) "标记为未观看" else "标记为已观看"
    item.kind == ItemKind.SEASON -> if (played) "整季标记为未观看…" else "整季标记为已观看…"
    else -> if (played) "全部标记为未观看…" else "全部标记为已观看…"
}
