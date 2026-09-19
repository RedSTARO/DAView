package com.daview.server.library

import com.daview.shared.model.ChapterDto
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Chapters come out of the default edition, in order, without the hidden ones,
 * and their start times are nanoseconds whatever the segment's scale.
 */
class MkvChaptersTest {

    /** One EBML element: its id bytes, an 8-byte size, and the payload. */
    private fun element(id: Long, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val idBytes = generateSequence(id) { it shr 8 }.takeWhile { it > 0 }.map { (it and 0xFF).toByte() }.toList().reversed()
        idBytes.forEach { out.write(it.toInt()) }
        // 0x01 then seven bytes of length: the widest size vint there is.
        out.write(0x01)
        for (shift in 48 downTo 0 step 8) out.write(((payload.size.toLong() shr shift) and 0xFF).toInt())
        out.write(payload)
        return out.toByteArray()
    }

    private fun uint(id: Long, value: Long): ByteArray {
        val bytes = (56 downTo 0 step 8).map { ((value shr it) and 0xFF).toByte() }.toByteArray()
        return element(id, bytes)
    }

    private fun text(id: Long, value: String) = element(id, value.toByteArray(Charsets.UTF_8))

    private fun atom(startMs: Long, title: String, hidden: Boolean = false): ByteArray = element(
        0xB6,
        uint(0x91, startMs * 1_000_000) +
            (if (hidden) uint(0x98, 1) else ByteArray(0)) +
            element(0x80, text(0x85, title))
    )

    private fun edition(default: Boolean, vararg atoms: ByteArray): ByteArray = element(
        0x45B9,
        (if (default) uint(0x45DB, 1) else ByteArray(0)) + atoms.fold(ByteArray(0)) { acc, it -> acc + it }
    )

    @Test
    fun `the default edition's visible chapters, in order`() {
        val payload = edition(false, atom(0, "其他版本")) +
            edition(
                true,
                atom(90_000, "Part A"),
                atom(0, "OP"),
                atom(1_320_000, "ED"),
                atom(1_400_000, "Preview", hidden = true)
            )

        val chapters = MkvProbe.parseChapters(payload)

        assertEquals(listOf(0L, 90_000L, 1_320_000L), chapters.map { it.startMs })
        assertEquals(listOf("OP", "Part A", "ED"), chapters.map { it.title })
    }

    @Test
    fun `opening and ending chapters are recognised by name`() {
        assertTrue(ChapterDto(0, "OP").isIntro)
        assertTrue(ChapterDto(0, "Opening").isIntro)
        assertTrue(ChapterDto(0, "ED").isOutro)
        assertTrue(!ChapterDto(0, "Part A").isIntro)
    }
}
