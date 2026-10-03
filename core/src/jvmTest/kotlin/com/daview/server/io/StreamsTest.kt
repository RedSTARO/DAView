package com.daview.server.io

import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StreamsTest {
    @Test
    fun `bounded reads retain the unread suffix without closing the stream`() {
        val source = ByteArray(20_000) { (it % 251).toByte() }
        val stream = ByteArrayInputStream(source)
        assertContentEquals(source.copyOfRange(0, 10_000), stream.readUpTo(10_000))
        assertContentEquals(source.copyOfRange(10_000, 20_000), stream.readUpTo(15_000))
        assertEquals(0, stream.readUpTo(1).size)
    }

    @Test
    fun `short and temporarily zero length reads still make progress`() {
        val source = ByteArray(30) { it.toByte() }
        val stream = object : FilterInputStream(ByteArrayInputStream(source)) {
            private var first = true
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (first) { first = false; return 0 }
                return super.read(b, off, minOf(3, len))
            }
        }
        assertContentEquals(source, stream.readUpTo(100))
    }

    @Test
    fun `zero does not consume input and negative limits are rejected`() {
        val stream = ByteArrayInputStream(byteArrayOf(7))
        assertEquals(0, stream.readUpTo(0).size)
        assertFailsWith<IllegalArgumentException> { stream.readUpTo(-1) }
        assertEquals(7, stream.read())
    }
}
