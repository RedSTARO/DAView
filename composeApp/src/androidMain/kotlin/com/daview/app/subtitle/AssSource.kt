package com.daview.app.subtitle

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Fetches and decodes a subtitle file. */
object AssSource {

    /** A whole subtitle is a megabyte at the outside; this is a guard, not a budget. */
    private const val LIMIT = 16 * 1024 * 1024

    private val cache = LinkedHashMap<String, AssScript>(4, 0.75f, true)

    suspend fun load(url: String): Result<AssScript> = withContext(Dispatchers.IO) {
        synchronized(cache) { cache[url] }?.let { return@withContext Result.success(it) }
        runCatching {
            val script = AssScript.parse(decode(fetch(url)))
            synchronized(cache) {
                cache[url] = script
                // Two or three scripts is everything a session touches; the cap
                // is only here so a long sitting cannot grow without end.
                while (cache.size > 8) cache.remove(cache.keys.first())
            }
            script
        }
    }

    private fun fetch(url: String): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 20_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("User-Agent", "DAView/1.0")
        try {
            val code = connection.responseCode
            check(code in 200..299) { "HTTP $code" }
            val out = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    check(out.size() <= LIMIT) { "subtitle larger than $LIMIT bytes" }
                }
            }
            return out.toByteArray()
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Decodes a subtitle whose encoding it has to work out.
     *
     * Most of the scripts here are UTF-8 with a byte-order mark, but not all:
     * of forty sampled from the library, two were in a Chinese legacy encoding
     * with nothing to say so. UTF-8 is self-checking, so it is tried strictly
     * first and only a failure leads to guessing — and the guess is between
     * GB18030 and Big5, decided by which produces the more plausible text
     * rather than by which is more common.
     */
    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8)
        }
        if (bytes.size >= 2) {
            if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
                return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16LE)
            }
            if (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
                return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE)
            }
        }
        strict(StandardCharsets.UTF_8, bytes)?.let { return it }

        val candidates = listOfNotNull(
            charsetOrNull("GB18030")?.let { it to String(bytes, it) },
            charsetOrNull("Big5")?.let { it to String(bytes, it) }
        )
        return candidates.maxByOrNull { plausibility(it.second) }?.second
            ?: String(bytes, StandardCharsets.UTF_8)
    }

    private fun charsetOrNull(name: String): Charset? = runCatching { Charset.forName(name) }.getOrNull()

    private fun strict(charset: Charset, bytes: ByteArray): String? = runCatching {
        val decoder: CharsetDecoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    }.getOrNull()

    /**
     * How much the decoded text looks like a subtitle rather than noise: ASCII
     * and everyday CJK score, replacement characters and private-use ones do
     * not. Text read through the wrong Chinese encoding lands mostly in the
     * rarer blocks, which is what separates the two.
     */
    private fun plausibility(text: String): Int {
        var score = 0
        for (ch in text) {
            val code = ch.code
            score += when {
                code < 0x80 -> 1
                code in 0x4E00..0x9FFF -> 2
                code in 0x3000..0x303F || code in 0xFF01..0xFF60 -> 1
                code == 0xFFFD || code in 0xE000..0xF8FF -> -8
                else -> -1
            }
        }
        return score
    }
}
