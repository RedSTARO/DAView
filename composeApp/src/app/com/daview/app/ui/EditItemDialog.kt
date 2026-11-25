@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.daview.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.daview.app.data.AppState
import com.daview.app.data.ModalMarker
import com.daview.app.data.tracksTextInput
import com.daview.shared.model.MediaItemDto

/**
 * Correcting an entry by hand.
 *
 * The scraper gets a share of them wrong — a same-named remake, a folder with
 * no year in it, a source that ranks the reissue first — and a title no source
 * has ever heard of would otherwise sit in the library as "XXX.2019.1080p.WEB-DL"
 * forever.
 *
 * What is typed here is remembered field by field: a rescan and a full
 * re-scrape both leave those fields alone, and 「恢复为刮削结果」 hands them back.
 */
@Composable
fun EditItemDialog(state: AppState, item: MediaItemDto, onDismiss: () -> Unit) {
    ModalMarker()
    var name by remember(item.id) { mutableStateOf(item.name) }
    var originalName by remember(item.id) { mutableStateOf(item.originalName.orEmpty()) }
    var overview by remember(item.id) { mutableStateOf(item.overview.orEmpty()) }
    var year by remember(item.id) { mutableStateOf(item.year?.toString().orEmpty()) }
    var genres by remember(item.id) { mutableStateOf(item.genres.joinToString("、")) }
    val titleFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { titleFocus.requestFocus() } }

    fun save() {
        if (name.isBlank()) return
        // Only the fields that were changed. Every one was sent before, so
        // correcting a title also pinned the overview, the year and the genres
        // against every later scrape.
        val genreList = genres.split('、', ',', '/').map { it.trim() }.filter { it.isNotEmpty() }
        state.updateItem(
            item = item,
            name = name.trim().takeIf { it != item.name },
            originalName = originalName.trim().takeIf { it != item.originalName.orEmpty() },
            overview = overview.trim().takeIf { it != item.overview.orEmpty() },
            // An emptied field clears the year; 0 is how "no year" is sent.
            year = when {
                year.isBlank() -> 0.takeIf { item.year != null }
                else -> year.toIntOrNull()?.takeIf { it != item.year }
            },
            genres = genreList.takeIf { it != item.genres }
        )
        onDismiss()
    }

    val yearField: @Composable (Modifier) -> Unit = { modifier ->
        OutlinedTextField(
            year, { year = it.filter { c -> c.isDigit() }.take(4) },
            label = { Text("年份") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
            modifier = modifier.tracksTextInput()
        )
    }
    val genresField: @Composable (Modifier) -> Unit = { modifier ->
        OutlinedTextField(
            genres, { genres = it },
            label = { Text("类型") },
            supportingText = { Text("用「、」分隔") },
            singleLine = true,
            // The last single-line field: Enter saves, as the button does.
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { save() }),
            modifier = modifier.tracksTextInput()
        )
    }

    AdaptiveAlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Edit, contentDescription = null) },
        title = { Text("编辑条目信息", Modifier.semantics { heading() }) },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(item.name, style = MaterialTheme.typography.titleMediumEmphasized)
                        Text(
                            "扫描与重新刮削都会保留你改过的字段。若要恢复，打开详情页的「更多」→「恢复为刮削结果」。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("基本信息", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmallEmphasized)
                    OutlinedTextField(
                        name, { name = it },
                        label = { Text("标题") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth().focusRequester(titleFocus).tracksTextInput()
                    )
                    OutlinedTextField(
                        originalName, { originalName = it },
                        label = { Text("原名") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth().tracksTextInput()
                    )
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        if (maxWidth >= 360.dp && LocalDensity.current.fontScale <= 1.4f) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                yearField(Modifier.weight(1f))
                                genresField(Modifier.weight(2f))
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                yearField(Modifier.fillMaxWidth())
                                genresField(Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
                OutlinedTextField(
                    overview, { overview = it },
                    label = { Text("简介") },
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth().tracksTextInput()
                )
            }
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank(), onClick = { save() },
                shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.SmallContentPadding
            ) { Text("保存", style = MaterialTheme.typography.labelLargeEmphasized) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
