@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.daview.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.daview.app.data.AppState
import com.daview.app.data.ModalMarker
import com.daview.app.data.tracksTextInput
import com.daview.shared.model.MediaItemDto
import kotlinx.coroutines.launch

/**
 * Folds duplicate entries into one.
 *
 * The same show lands in the library twice more often than it sounds: two
 * folders for one series, a copy in the anime library and another in the TV
 * one, or — as in the reference share — the same name spelled with different
 * capitalisation. Merging moves the duplicates' seasons and episodes under this
 * item and stops the spares showing up as separate entries; they are flagged
 * rather than deleted, so it can be undone and re-applied after a rescan.
 */
@Composable
fun MergeDialog(state: AppState, item: MediaItemDto, onDismiss: () -> Unit) {
    ModalMarker()
    val scope = rememberCoroutineScope()
    val queryFocus = remember { FocusRequester() }
    var query by remember { mutableStateOf(item.name) }
    var results by remember { mutableStateOf<List<MediaItemDto>>(emptyList()) }
    var merged by remember { mutableStateOf<List<MediaItemDto>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun reload() {
        merged = state.library.mergedSources(item.id, state.links)
        results = state.library.items(state.links, kind = item.kind, search = query, limit = 40).items
            .filter { it.id != item.id }
    }

    LaunchedEffect(item.id) {
        busy = true
        runCatching { reload() }.onFailure { error = it.message }
        busy = false
        runCatching { queryFocus.requestFocus() }
    }

    fun run(block: suspend () -> Unit) {
        scope.launch {
            busy = true
            error = null
            try {
                block()
                reload()
            } catch (e: Throwable) {
                error = e.message ?: "操作失败"
            } finally {
                busy = false
            }
        }
    }

    val colors = MaterialTheme.colorScheme
    AdaptiveAlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.MergeType, contentDescription = null) },
        title = { Text("合并重复条目", Modifier.semantics { heading() }) },
        text = {
            LazyColumn(Modifier.fillMaxWidth()) {
                item("target-and-search") {
                    Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Surface(color = colors.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("并入此条目", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                                Text(item.name, style = MaterialTheme.typography.titleMediumEmphasized)
                            }
                        }
                        Text(
                            "选中条目的季和分集会移到这里，原条目不再单独显示。原始记录会保留，可随时拆分；重新扫描仍会保留合并。",
                            style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                label = { Text("搜索重复条目") },
                                singleLine = true,
                                // Read-only rather than disabled: keeping focus preserves the cursor.
                                readOnly = busy,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { run {} }),
                                modifier = Modifier.weight(1f).focusRequester(queryFocus).tracksTextInput()
                            )
                            Tooltip("搜索") {
                                FilledTonalIconButton(enabled = !busy, onClick = { run {} }) {
                                    Icon(Icons.Filled.Search, contentDescription = "搜索")
                                }
                            }
                        }
                        if (busy) LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                        error?.let {
                            Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = colors.error, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                if (merged.isNotEmpty()) {
                    item("merged-heading") { MergeHeading("已合并 · ${merged.size}") }
                    itemsIndexed(merged, key = { _, source -> "merged:${source.id}" }, contentType = { _, _ -> "merged" }) { _, source ->
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                            color = colors.surfaceContainerLow,
                            shape = MaterialTheme.shapes.small
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                                    Text(source.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    source.path?.let {
                                        Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                TextButton(
                                    enabled = !busy,
                                    onClick = {
                                        run {
                                            state.library.unmerge(source.id, state.links)
                                            // The page behind still listed the split-off seasons.
                                            state.loadDetail(item.id, quiet = true)
                                            state.refreshLibraries()
                                        }
                                    }
                                ) { Text("拆分") }
                            }
                        }
                    }
                }
                item("results-heading") {
                    MergeHeading("可并入的条目", Modifier.padding(top = if (merged.isEmpty()) 0.dp else 12.dp))
                    if (results.isEmpty() && !busy && error == null) {
                        Text("没有找到可合并的条目。试试其他片名。", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    }
                }
                itemsIndexed(results, key = { _, candidate -> "candidate:${candidate.id}" }, contentType = { _, _ -> "candidate" }) { index, candidate ->
                    val checked = candidate.id in selected
                    MergeCandidate(candidate, checked, index, results.size) { next ->
                        selected = if (next) selected + candidate.id else selected - candidate.id
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = selected.isNotEmpty() && !busy,
                onClick = {
                    run {
                        state.library.merge(item.id, selected.toList(), state.links)
                        selected = emptySet()
                        state.loadDetail(item.id)
                        state.refreshLibraries()
                    }
                },
                shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.SmallContentPadding
            ) { Text("合并 ${selected.size} 项", style = MaterialTheme.typography.labelLargeEmphasized) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
private fun MergeHeading(title: String, modifier: Modifier = Modifier) {
    Text(title, modifier.padding(bottom = 8.dp).semantics { heading() }, style = MaterialTheme.typography.titleSmallEmphasized)
}

@Composable
private fun MergeCandidate(candidate: MediaItemDto, checked: Boolean, index: Int, count: Int, onCheckedChange: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    SegmentedListItem(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = Modifier.fillMaxWidth().padding(vertical = ListItemDefaults.SegmentedGap / 2).semantics { role = Role.Checkbox },
        shapes = ListItemDefaults.segmentedShapes(index, count),
        colors = ListItemDefaults.segmentedColors(
            containerColor = colors.surfaceContainerLow,
            selectedContainerColor = colors.secondaryContainer,
            selectedContentColor = colors.onSecondaryContainer,
            selectedSupportingContentColor = colors.onSecondaryContainer
        ),
        leadingContent = { Checkbox(checked = checked, onCheckedChange = null) },
        supportingContent = {
            val note = listOfNotNull(candidate.path, candidate.episodeCount?.takeIf { it > 0 }?.let { "$it 集" }).joinToString(" · ")
            if (note.isNotEmpty()) Text(note, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    ) {
        Text(
            listOfNotNull(candidate.name, candidate.year?.toString()).joinToString(" · "),
            style = if (checked) MaterialTheme.typography.titleMediumEmphasized else MaterialTheme.typography.titleMedium,
            maxLines = 2, overflow = TextOverflow.Ellipsis
        )
    }
}
