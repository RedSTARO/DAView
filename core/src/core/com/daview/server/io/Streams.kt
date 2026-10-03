package com.daview.server.io

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** InputStream.readNBytes is only available from Android API 33; core supports 26. */
fun InputStream.readUpTo(maxBytes: Int): ByteArray {
    require(maxBytes >= 0) { "maxBytes must not be negative" }
    val output = ByteArrayOutputStream(minOf(maxBytes, 8192))
    val buffer = ByteArray(minOf(maxBytes, 8192))
    var remaining = maxBytes
    while (remaining > 0) {
        val count = read(buffer, 0, minOf(remaining, buffer.size))
        if (count < 0) break
        if (count == 0) {
            val byte = read()
            if (byte < 0) break
            output.write(byte)
            remaining--
        } else {
            output.write(buffer, 0, count)
            remaining -= count
        }
    }
    return output.toByteArray()
}
