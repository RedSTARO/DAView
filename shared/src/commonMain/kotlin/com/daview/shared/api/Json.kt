package com.daview.shared.api

import kotlinx.serialization.json.Json

/**
 * How DAView reads and writes its own files — backups and the sync file.
 *
 * Unknown keys are ignored on purpose: a file written by a newer build has to
 * stay readable by an older one, or a device that has not been updated would
 * refuse to sync.
 */
val DaViewJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    isLenient = true
}
