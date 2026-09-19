package com.daview.app.platform

import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * File chooser for Android, over the Storage Access Framework.
 *
 * An app may only read a file the user picked through the system chooser, and
 * the chooser is an activity result — so the launcher has to be registered
 * while the activity is being created, and the suspending call has to wait for
 * a callback that arrives much later.
 */
object AndroidFilePicker {

    private var launcher: ActivityResultLauncher<Array<String>>? = null
    private var saveLauncher: ActivityResultLauncher<String>? = null
    private var activity: ComponentActivity? = null
    private var pending: CancellableContinuation<String?>? = null
    private var pendingSave: CancellableContinuation<String?>? = null
    private var pendingWrite: ((Appendable) -> Unit)? = null
    private var bytesLauncher: ActivityResultLauncher<Array<String>>? = null
    private var pendingBytes: CancellableContinuation<PickedFile?>? = null

    /** Called from the activity's onCreate; registering later throws. */
    fun register(owner: ComponentActivity) {
        activity = owner
        saveLauncher = owner.registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            val waiting = pendingSave
            val write = pendingWrite
            pendingSave = null
            pendingWrite = null
            if (waiting == null || !waiting.isActive) return@registerForActivityResult
            if (uri == null || write == null) {
                waiting.resume(null)
                return@registerForActivityResult
            }
            val path = runCatching {
                owner.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { write(it) }
                uri.toString()
            }.getOrNull()
            waiting.resume(path)
        }
        bytesLauncher = owner.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val waiting = pendingBytes
            pendingBytes = null
            if (waiting == null || !waiting.isActive) return@registerForActivityResult
            if (uri == null) {
                waiting.resume(null)
                return@registerForActivityResult
            }
            val picked = runCatching {
                val name = owner.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val column = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
                } ?: uri.lastPathSegment.orEmpty()
                val bytes = owner.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
                PickedFile(name, bytes)
            }.getOrNull()
            waiting.resume(picked)
        }
        launcher = owner.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val waiting = pending
            pending = null
            if (waiting == null || !waiting.isActive) return@registerForActivityResult
            if (uri == null) {
                waiting.resume(null)
                return@registerForActivityResult
            }
            val text = runCatching {
                owner.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            waiting.resume(text)
        }
    }

    fun unregister(owner: ComponentActivity) {
        if (activity === owner) {
            activity = null
            launcher = null
            saveLauncher = null
            bytesLauncher = null
        }
    }

    /**
     * The counterpart of [pick]: the app cannot write to a path of its own
     * choosing either, so an export goes through the same system chooser and
     * the bytes are written into whatever the user picked.
     */
    suspend fun save(suggestedName: String, write: (Appendable) -> Unit): String? {
        val target = saveLauncher ?: return null
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                pendingSave = continuation
                pendingWrite = write
                continuation.invokeOnCancellation { pendingSave = null; pendingWrite = null }
                runCatching { target.launch(suggestedName) }.onFailure {
                    pendingSave = null
                    pendingWrite = null
                    continuation.resume(null)
                }
            }
        }
    }

    suspend fun pick(): String? {
        val target = launcher ?: return null
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                pending = continuation
                continuation.invokeOnCancellation { pending = null }
                // Some file managers hand JSON out as octet-stream, so the filter
                // stays wide rather than hiding the file the user came for.
                runCatching {
                    target.launch(arrayOf("application/json", "text/plain", "*/*"))
                }.onFailure {
                    pending = null
                    continuation.resume(null)
                }
            }
        }
    }

    /** A binary file of one of [mimeTypes], read whole. Images and subtitle files. */
    suspend fun pickBytes(mimeTypes: Array<String>): PickedFile? {
        val target = bytesLauncher ?: return null
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                pendingBytes = continuation
                continuation.invokeOnCancellation { pendingBytes = null }
                runCatching { target.launch(mimeTypes) }.onFailure {
                    pendingBytes = null
                    continuation.resume(null)
                }
            }
        }
    }
}
