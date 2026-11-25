@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.daview.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.daview.app.data.AppState
import com.daview.app.data.ModalMarker
import com.daview.app.data.tracksTextInput
import com.daview.shared.model.IdentifyContextDto
import com.daview.shared.model.IdentifyRequest
import com.daview.shared.model.MediaItemDto
import com.daview.shared.model.MetadataProvider
import com.daview.shared.model.ScrapeCandidateDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Manual override for a wrong scrape.
 *
 * Automatic matching goes wrong in ways no threshold fixes: two works share a
 * title, a folder carries no year, a provider ranks a remake first. Here the
 * user names the entry themselves — paste the id from the provider's own URL,
 * or search it — and the item is rebuilt from that entry and pinned, so later
 * scans reuse the id instead of searching again.
 *
 * A search result is picked first and applied with the button, not applied on
 * the click that picks it: applying replaces every field the scrape wrote, and
 * a stray click on the row below the right one did exactly that.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IdentifyDialog(state: AppState, item: MediaItemDto, onDismiss: () -> Unit) {
    ModalMarker()
    val scope = rememberCoroutineScope()
    var context by remember { mutableStateOf<IdentifyContextDto?>(null) }
    var provider by remember { mutableStateOf<MetadataProvider?>(null) }
    var providerId by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var year by remember { mutableStateOf("") }
    var candidates by remember { mutableStateOf<List<ScrapeCandidateDto>>(emptyList()) }
    var picked by remember { mutableStateOf<ScrapeCandidateDto?>(null) }
    var searched by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val queryFocus = remember { FocusRequester() }

    // One search at a time: switching source starts another while the first
    // may still be waiting, and its late answer used to replace the list
    // under the newly selected source.
    var searchJob by remember { mutableStateOf<Job?>(null) }
    var searchGeneration by remember { mutableStateOf(0) }

    fun search() {
        val source = provider ?: return
        if (query.isBlank()) return
        searchJob?.cancel()
        val generation = ++searchGeneration
        searchJob = scope.launch {
            busy = true
            error = null
            picked = null
            try {
                val found = state.library.identifySearch(item.id, source, query, year.toIntOrNull())
                if (generation == searchGeneration) {
                    candidates = found
                    searched = true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (generation == searchGeneration) error = state.describe(e)
            } finally {
                if (generation == searchGeneration) busy = false
            }
        }
    }

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
            error = state.describe(e)
        } finally {
            busy = false
        }
        runCatching { queryFocus.requestFocus() }
        // The folder name is already in the box; searching it straight away
        // saves the click that nearly everyone makes first anyway.
        search()
    }

    fun apply(id: String) {
        // A picked result names its own source; the selected toggle is only
        // the source of an id typed by hand.
        val source = picked?.takeIf { it.providerId == id }?.provider ?: provider ?: return
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
                state.loadDetail(item.id, quiet = true)
                state.refreshLibraries()
                onDismiss()
            } catch (e: Throwable) {
                error = state.describe(e)
            } finally {
                busy = false
            }
        }
    }

    val loaded = context
    val colors = MaterialTheme.colorScheme
    val queryField: @Composable (Modifier) -> Unit = { modifier ->
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("片名") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { search() }),
            modifier = modifier.focusRequester(queryFocus).tracksTextInput()
        )
    }
    val yearField: @Composable (Modifier) -> Unit = { modifier ->
        OutlinedTextField(
            value = year,
            onValueChange = { year = it.filter { c -> c.isDigit() }.take(4) },
            label = { Text("年份") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { search() }),
            modifier = modifier.tracksTextInput()
        )
    }
    val submitSearch: @Composable () -> Unit = {
        Tooltip("搜索") {
            FilledTonalIconButton(onClick = { search() }, enabled = provider != null && query.isNotBlank() && !busy) {
                Icon(Icons.Filled.Search, contentDescription = "搜索")
            }
        }
    }

    AdaptiveAlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Search, contentDescription = null) },
        title = { Text("手动指定刮削条目", Modifier.semantics { heading() }) },
        text = {
            LazyColumn(Modifier.fillMaxWidth()) {
                item("context") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(color = colors.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("文件夹识别结果", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                                Text(
                                    (loaded?.defaultQuery ?: item.name) + (loaded?.defaultYear?.let { " ($it)" } ?: ""),
                                    style = MaterialTheme.typography.titleMediumEmphasized
                                )
                                loaded?.lockedProvider?.let { locked ->
                                    Text(
                                        "当前已手动指定：${locked.displayName} ${loaded.providerIds[locked.name.lowercase()].orEmpty()}",
                                        style = MaterialTheme.typography.labelMediumEmphasized,
                                        color = colors.tertiary
                                    )
                                }
                            }
                        }
                        Text(
                            "应用后会用所选条目的标题、简介、海报等替换当前信息，包括手动修改过的字段。",
                            style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant
                        )
                    }
                }
                item("search") {
                    Column(Modifier.padding(top = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (loaded != null && loaded.providers.isEmpty()) {
                            Text(
                                "没有可用的刮削源。TMDB 与 TheTVDB 需要在设置里填写 API Key。",
                                style = MaterialTheme.typography.bodyMedium, color = colors.error
                            )
                        } else if (!loaded?.providers.isNullOrEmpty()) {
                            Text("刮削源", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmallEmphasized)
                            FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                loaded.providers.forEach { option ->
                                    val checked = provider == option
                                    ToggleButton(
                                        checked = checked,
                                        onCheckedChange = {
                                            provider = option
                                            candidates = emptyList()
                                            picked = null
                                            searched = false
                                            providerId = loaded.providerIds[option.name.lowercase()].orEmpty()
                                            search()
                                        },
                                        colors = ToggleButtonDefaults.toggleButtonColors(
                                            containerColor = colors.surfaceContainerLow,
                                            contentColor = colors.onSurfaceVariant,
                                            checkedContainerColor = colors.secondaryContainer,
                                            checkedContentColor = colors.onSecondaryContainer
                                        )
                                    ) {
                                        if (checked) {
                                            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(ToggleButtonDefaults.IconSize))
                                            Spacer(Modifier.width(ToggleButtonDefaults.IconSpacing))
                                        }
                                        Text(
                                            option.displayName,
                                            style = if (checked) MaterialTheme.typography.labelLargeEmphasized else MaterialTheme.typography.labelLarge
                                        )
                                    }
                                }
                            }
                        }
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            if (maxWidth >= 340.dp && LocalDensity.current.fontScale <= 1.35f) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    queryField(Modifier.weight(1f))
                                    yearField(Modifier.width(96.dp))
                                    submitSearch()
                                }
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    queryField(Modifier.fillMaxWidth())
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        yearField(Modifier.weight(1f))
                                        submitSearch()
                                    }
                                }
                            }
                        }
                        if (busy) LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                        error?.let {
                            Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = colors.error, style = MaterialTheme.typography.bodyMedium)
                        }
                        if (searched && candidates.isEmpty() && !busy) {
                            Text(
                                "没有找到匹配条目。可以改用原名搜索，或直接输入条目 ID。",
                                style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant
                            )
                        }
                    }
                }
                if (candidates.isNotEmpty()) item("results-heading") {
                    Text(
                        "搜索结果 · ${candidates.size}", Modifier.padding(bottom = 8.dp).semantics { heading() },
                        style = MaterialTheme.typography.titleSmallEmphasized
                    )
                }
                itemsIndexed(candidates, contentType = { _, _ -> "candidate" }) { index, candidate ->
                    CandidateRow(state, candidate, picked == candidate, index, candidates.size) {
                        picked = candidate
                        providerId = candidate.providerId
                    }
                }
                item("provider-id") {
                    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        HorizontalDivider()
                        OutlinedTextField(
                            value = providerId,
                            onValueChange = { providerId = it; picked = null },
                            label = { Text("条目 ID") },
                            supportingText = { Text(provider?.idHint.orEmpty()) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { if (provider != null && providerId.isNotBlank()) apply(providerId) }),
                            modifier = Modifier.fillMaxWidth().tracksTextInput()
                        )
                        picked?.let { match ->
                            Text("将指定为「${match.title}」", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = provider != null && providerId.isNotBlank() && !busy,
                onClick = { apply(providerId) },
                shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.SmallContentPadding
            ) { Text("应用指定", style = MaterialTheme.typography.labelLargeEmphasized) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun CandidateRow(
    state: AppState, candidate: ScrapeCandidateDto, selected: Boolean, index: Int, count: Int, onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = ListItemDefaults.SegmentedGap / 2)) {
        val showPoster = maxWidth >= 224.dp && LocalDensity.current.fontScale <= 1.5f
        SegmentedListItem(
            selected = selected,
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().semantics { role = Role.RadioButton },
            shapes = ListItemDefaults.segmentedShapes(index, count),
            colors = ListItemDefaults.segmentedColors(
                containerColor = colors.surfaceContainerLow,
                selectedContainerColor = colors.secondaryContainer,
                selectedContentColor = colors.onSecondaryContainer
            ),
            leadingContent = if (showPoster) ({ CandidatePoster(state, candidate.posterUrl) }) else null,
            trailingContent = { RadioButton(selected = selected, onClick = null) }
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    candidate.title,
                    style = if (selected) MaterialTheme.typography.titleMediumEmphasized else MaterialTheme.typography.titleMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                Text(
                    listOfNotNull(
                        candidate.year?.toString(),
                        candidate.originalTitle?.takeIf { it != candidate.title },
                        "ID ${candidate.providerId}"
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                candidate.overview?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it, style = MaterialTheme.typography.bodySmall,
                        color = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun CandidatePoster(state: AppState, posterUrl: String?) {
    // Posters distinguish remakes that share the same title. Only visible results request them.
    val poster by produceState<String?>(null, posterUrl) {
        value = posterUrl?.let { url -> runCatching { state.library.remoteImage(url)?.toString() }.getOrNull() }
    }
    Surface(
        modifier = Modifier.width(48.dp).aspectRatio(2f / 3f),
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        val path = poster
        if (path == null) ArtworkPlaceholder(null, Modifier.fillMaxSize())
        else AsyncImage(java.io.File(path), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}
