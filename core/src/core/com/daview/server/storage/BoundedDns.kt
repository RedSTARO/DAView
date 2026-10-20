package com.daview.server.storage

import okhttp3.Dns
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * The system resolver, with a limit.
 *
 * OkHttp's call timeout cancels a call but cannot interrupt a lookup in
 * progress, so a resolver that never answered held the startup pull, and the
 * scan and every sync queued behind it, for as long as the platform's own
 * resolver timeout. The lookup itself carries on in its own thread and is
 * simply no longer waited for.
 */
internal class BoundedDns(
    private val timeoutMs: Long,
    private val delegate: Dns = Dns.SYSTEM
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val lookup = resolver.submit(Callable { delegate.lookup(hostname) })
        try {
            return lookup.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            lookup.cancel(true)
            throw UnknownHostException("解析 $hostname 超时").apply { initCause(e) }
        } catch (e: ExecutionException) {
            val cause = e.cause
            throw cause as? UnknownHostException ?: UnknownHostException(hostname).apply { initCause(cause) }
        } catch (e: InterruptedException) {
            lookup.cancel(true)
            Thread.currentThread().interrupt()
            throw InterruptedIOException("解析 $hostname 时被中断")
        }
    }

    private companion object {
        val resolver: ExecutorService = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "daview-dns").apply { isDaemon = true }
        }
    }
}
