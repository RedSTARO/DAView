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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
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
import com.daview.shared.model.IdentifyContextDto
import com.daview.shared.model.IdentifyRequest
import com.daview.shared.model.MediaItemDto
import com.daview.shared.model.MetadataProvider
import com.daview.shared.model.ScrapeCandidateDto
import kotlinx.coroutines.launch

/**
 * Manual override for a wrong scrape.
 *
 * Automatic matching goes wrong in ways no threshold fixes: two works share a
 * title, a folder carries no year, a provider ranks a remake first. Here the
 * user names the entry themselves — paste the id from the provider's own URL,
 * or search it — and the item is rebuilt from that entry and pinned, so later
 * scans reuse the id instead of searching again.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun IdentifyDialog(state: AppState, item: MediaItemDto, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var context by remember { mutableStateOf<IdentifyContextDto?>(null) }
    var provider by remember { mutableStateOf<MetadataProvider?>(null) }
    var providerId by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var year by remember { mutableStateOf("") }
    var candidates by remember { mutableStateOf<List<ScrapeCandidateDto>>(emptyList()) }
    var searched by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(item.id) {
        busy = true
        try {
            val loaded = state.library.identifyContext(item.id)
            context = loaded
            provider = loaded.lockedProvider ?: loaded.providers.firstOrNull()
            query = loaded.defaultQuery
            year = loaded.defaultYear?.toString().orEmpty()
            providerId = loaded.lockedProvider
                ?.let { loaded.providerIds[it.name.lowercase()] }
                .orEmpty()
        } catch (e: Throwable) {
            error = e.message
        } finally {
            busy = false
        }
    }

    fun search() {
        val source = provider ?: return
        scope.launch {
            busy = true
            error = null
            try {
                candidates = state.library.identifySearch(item.id, source, query, year.toIntOrNull())
                searched = true
            } catch (e: Throwable) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }

    fun apply(id: String) {
        val source = provider ?: return
        scope.launch {
            busy = true
            error = null
            try {
                val updated = state.library.identify(
                    item.id,
                    IdentifyRequest(provider = source, providerId = id),
                    state.links
                )
                state.notify("已指定为 ${updated.name}")
                state.loadDetail(item.id)
                state.refreshLibraries()
                onDismiss()
            } catch (e: Throwable) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("手动指定刮削条目") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val loaded = context
                Text(
                    "文件夹识别为「${loaded?.defaultQuery ?: item.name}」" +
                        (loaded?.defaultYear?.let { " ($it)" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                loaded?.lockedProvider?.let { locked ->
                    Text(
                        "当前已手动指定：${locked.displayName} ${loaded.providerIds[locked.name.lowercase()].orEmpty()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(Modifier.height(12.dp))
                if (loaded != null && loaded.providers.isEmpty()) {
                    Text(
                        "没有可用的刮削源。TMDB 与 TheTVDB 需要在设置里填写 API Key。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        loaded?.providers.orEmpty().forEach { option ->
                            ToggleButton(
                                checked = provider == option,
                                onCheckedChange = {
                                    provider = option
                                    candidates = emptyList()
                                    searched = false
                                    providerId = loaded?.providerIds?.get(option.name.lowercase()).orEmpty()
                                }
                            ) {
                                Text(option.displayName)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = providerId,
                    onValueChange = { providerId = it },
                    label = { Text("条目 id") },
                    supportingText = { Text(provider?.idHint.orEmpty()) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text(
                    "不知道 id 就先搜索，点击结果即可指定",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("片名") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = year,
                        onValueChange = { year = it.filter { c -> c.isDigit() }.take(4) },
                        label = { Text("年份") },
                        singleLine = true,
                        modifier = Modifier.width(96.dp)
                    )
                    FilledTonalIconButton(
                        onClick = { search() },
                        enabled = provider != null && query.isNotBlank() && !busy
                    ) {
                        Icon(Icons.Filled.Search, contentDescription = "搜索")
                    }
                }

                if (busy) {
                    Spacer(Modifier.height(8.dp))
                    LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (searched && candidates.isEmpty() && !busy) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "没有结果。换个写法，或直接填 id。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Column {
                    candidates.forEach { candidate ->
                        CandidateRow(candidate) { apply(candidate.providerId) }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = provider != null && providerId.isNotBlank() && !busy,
                onClick = { apply(providerId) }
            ) { Text("应用") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun CandidateRow(candidate: ScrapeCandidateDto, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable(onClick = onClick)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                candidate.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                listOfNotNull(
                    candidate.year?.toString(),
                    candidate.originalTitle?.takeIf { it != candidate.title },
                    "id ${candidate.providerId}"
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            candidate.overview?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
