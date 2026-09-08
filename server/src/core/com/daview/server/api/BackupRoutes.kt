package com.daview.server.api

import com.daview.server.ServerContext
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondBytesWriter
import io.ktor.utils.io.writeFully

/**
 * Writes the backup straight to the socket instead of building it in memory.
 *
 * The format lives in `:core`, which hands over one fragment at a time; all
 * that is left here is turning fragments into bytes on a connection.
 */
suspend fun ApplicationCall.respondBackup(context: ServerContext, options: BackupOptions) {
    response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"${backupFileName()}\"")
    respondBytesWriter(contentType = ContentType.Application.Json) {
        for (chunk in backupChunks(context, options)) {
            val bytes = chunk.toByteArray(Charsets.UTF_8)
            writeFully(bytes, 0, bytes.size)
        }
        flush()
    }
}
