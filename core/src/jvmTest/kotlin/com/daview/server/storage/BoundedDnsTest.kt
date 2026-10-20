package com.daview.server.storage

import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BoundedDnsTest {
    @Test
    fun `a resolver that never answers fails within the limit`() {
        val release = CountDownLatch(1)
        val stuck = dns { release.await(30, TimeUnit.SECONDS); emptyList() }
        try {
            val started = System.nanoTime()
            assertFailsWith<UnknownHostException> { BoundedDns(200, stuck).lookup("share.example") }
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertTrue(elapsedMs < 5_000, "The lookup was waited for ${elapsedMs}ms")
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `answers and resolver failures pass through`() {
        val loopback = InetAddress.getLoopbackAddress()
        assertEquals(listOf(loopback), BoundedDns(5_000, dns { listOf(loopback) }).lookup("share.example"))
        val failure = assertFailsWith<UnknownHostException> {
            BoundedDns(5_000, dns { throw UnknownHostException("no such host") }).lookup("share.example")
        }
        assertEquals("no such host", failure.message)
    }

    private fun dns(answer: (String) -> List<InetAddress>) = object : Dns {
        override fun lookup(hostname: String) = answer(hostname)
    }
}
