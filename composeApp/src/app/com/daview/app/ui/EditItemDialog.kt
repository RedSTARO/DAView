package com.daview.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
        state.updateItem(
            item = item,
            name = name.trim(),
            originalName = originalName.trim(),
            overview = overview.trim(),
            year = year.toIntOrNull(),
            genres = genres.split('、', ',', '/')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        )
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑条目信息") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "改过的字段会记成手动填写：重新扫描和重新刮削都不会覆盖它们。" +
                        "要改回刮削到的内容，用详情页「更多」里的「恢复为刮削结果」。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    name, { name = it },
                    label = { Text("标题") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth().focusRequester(titleFocus).tracksTextInput()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    originalName, { originalName = it },
                    label = { Text("原名") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth().tracksTextInput()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    year, { year = it.filter { c -> c.isDigit() }.take(4) },
                    label = { Text("年份") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Next
                    ),
                    modifier = Modifier.fillMaxWidth().tracksTextInput()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    genres, { genres = it },
                    label = { Text("类型") },
                    supportingText = { Text("用「、」分隔") },
                    singleLine = true,
                    // The last single-line field: Enter saves, as the button does.
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                    modifier = Modifier.fillMaxWidth().tracksTextInput()
                )
                Spacer(Modifier.height(8.dp))
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
            Button(enabled = name.isNotBlank(), onClick = { save() }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
