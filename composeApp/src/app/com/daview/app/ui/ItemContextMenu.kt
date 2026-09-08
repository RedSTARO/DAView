package com.daview.app.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
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
import com.daview.app.data.PlaybackController
import com.daview.app.data.Screen
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
 * What a right-click on a card offers. Everything here acts on the item alone
 * and needs no dialog of its own, so the menu can hang off any card on any
 * screen — the entries that do need one (manual identify, merge duplicates) stay
 * on the detail page where their dialogs have somewhere to live.
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
                playback.playInternalOrExternal(item)
            }
        )
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
    }

    val played = item.playedState == PlayedState.PLAYED
    // A series or season has no watched flag of its own — marking it flips every
    // episode under it — so the label says how wide the change reaches.
    if (item.isPlayable || (item.episodeCount ?: 0) > 0) {
        DropdownMenuItem(
            leadingIcon = {
                Icon(
                    if (played) Icons.Filled.CheckCircleOutline else Icons.Filled.Check,
                    contentDescription = null,
                    tint = if (played) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.primary
                )
            },
            text = { Text(playedActionLabel(item, played)) },
            onClick = {
                dismiss()
                state.togglePlayed(item)
            }
        )
    }

    DropdownMenuItem(
        leadingIcon = {
            Icon(
                if (item.userData.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = null,
                tint = if (item.userData.favorite) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        text = { Text(if (item.userData.favorite) "取消收藏" else "收藏") },
        onClick = {
            dismiss()
            state.toggleFavorite(item)
        }
    )

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
    item.kind == ItemKind.SEASON -> if (played) "整季标记为未观看" else "整季标记为已观看"
    else -> if (played) "全部标记为未观看" else "全部标记为已观看"
}
