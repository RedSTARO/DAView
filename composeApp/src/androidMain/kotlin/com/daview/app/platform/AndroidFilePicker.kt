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
    private var activity: ComponentActivity? = null
    private var pending: CancellableContinuation<String?>? = null

    /** Called from the activity's onCreate; registering later throws. */
    fun register(owner: ComponentActivity) {
        activity = owner
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
}
