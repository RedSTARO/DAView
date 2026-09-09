package com.daview.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.daview.app.data.AppState
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
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MergeDialog(state: AppState, item: MediaItemDto, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("合并重复条目") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "被选中的条目会并入「${item.name}」：它们的季与分集移到这里，自己不再单独出现。" +
                        "原始记录会保留，随时可以拆分，重新扫描也会自动重新应用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (merged.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text("已合并", style = MaterialTheme.typography.labelLarge)
                    merged.forEach { source ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(source.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                source.path?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            TextButton(
                                enabled = !busy,
                                onClick = { run { state.library.unmerge(source.id, state.links) } }
                            ) { Text("拆分") }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("搜索要并入的条目") },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                TextButton(enabled = !busy, onClick = { run {} }) { Text("搜索") }

                if (busy) {
                    Spacer(Modifier.height(8.dp))
                    LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }

                Column {
                    results.forEach { candidate ->
                        val checked = candidate.id in selected
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable {
                                selected = if (checked) selected - candidate.id else selected + candidate.id
                            }
                        ) {
                            Row(
                                Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(checked = checked, onCheckedChange = null)
                                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                    Text(
                                        listOfNotNull(candidate.name, candidate.year?.toString())
                                            .joinToString(" · "),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        listOfNotNull(
                                            candidate.path,
                                            candidate.episodeCount?.takeIf { it > 0 }?.let { "$it 集" }
                                        ).joinToString(" · "),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (checked) {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
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
                }
            ) { Text("合并 ${selected.size} 项") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}
