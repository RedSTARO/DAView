package com.daview.app.player

import com.sun.jna.Pointer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Exercises the real player entry points with fake pointers; never loads libmpv. */
class MpvPlayerLifecycleTest {
    private val executor = Executors.newCachedThreadPool { task ->
        Thread(task, "mpv-lifecycle-test").apply { isDaemon = true }
    }
    private val players = mutableListOf<MpvPlayer>()
    private val libraries = mutableListOf<FakeMpv>()

    @AfterTest
    fun tearDown() {
        libraries.forEach { it.unblock() }
        players.forEach { it.close() }
        executor.shutdown()
        assertTrue(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        libraries.forEach { fake ->
            if (fake.created.get()) fake.awaitDestroyed()
            assertTrue(fake.violations.isEmpty(), fake.violations.joinToString())
        }
    }

    @Test
    fun `close waits for each admitted command or property call and rejects new calls`() {
        for (operation in listOf("command", "set-property", "get-property")) {
            val fake = library()
            val player = player(fake).apply { open(1, MpvPlayer.Config()) }
            val gate = fake.block(operation)
            val active = executor.submit {
                when (operation) {
                    "command" -> player.seekTo(20_000)
                    "set-property" -> player.setPaused(true)
                    else -> player.positionMs
                }
            }
            gate.awaitEntered()

            closeWithoutWaiting(player)
            val counts = listOf("command", "set-property", "get-property").map(fake::count)
            assertFalse(player.bindKey("j", "subtitles"))
            player.setPaused(false)
            assertNull(player.positionMs)
            assertEquals(counts, listOf("command", "set-property", "get-property").map(fake::count))
            assertEquals(0, fake.count("destroy"), "must wait for $operation")

            gate.release.countDown()
            active.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            fake.awaitDestroyed()
        }
    }

    @Test
    fun `a property lease includes decoding and freeing its returned native string`() {
        val fake = library()
        val player = player(fake).apply { open(1, MpvPlayer.Config()) }
        val gate = fake.block("read-string")
        val result = executor.submit<Long?> { player.positionMs }
        gate.awaitEntered() // get_property has returned, but its pointer is still in use.

        closeWithoutWaiting(player)
        assertEquals(0, fake.count("destroy"))
        assertEquals(0, fake.count("free"))
        gate.release.countDown()

        assertEquals(12_500L, result.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        fake.awaitDestroyed()
        assertEquals(1, fake.count("free"))
        assertTrue(fake.calls.indexOf("free") < fake.calls.indexOf("destroy"))
    }

    @Test
    fun `close waits for wait_event to return`() {
        val fake = library()
        val gate = fake.block("wait-event")
        val player = player(fake).apply { open(1, MpvPlayer.Config()) }
        gate.awaitEntered()

        closeWithoutWaiting(player)
        assertEquals(0, fake.count("destroy"))
        gate.release.countDown()
        fake.awaitDestroyed()
        assertEquals(0, fake.count("wakeup"))
        assertEquals(0, fake.count("command"))
    }

    @Test
    fun `an event lease includes reading the returned native event buffer`() {
        val fake = library()
        val gate = fake.block("read-event")
        fake.events.put(8) // MPV_EVENT_FILE_LOADED
        val player = player(fake).apply { open(1, MpvPlayer.Config()) }
        gate.awaitEntered() // wait_event has already returned its pointer.

        closeWithoutWaiting(player)
        assertEquals(0, fake.count("destroy"))
        gate.release.countDown()
        fake.awaitDestroyed()
    }

    @Test
    fun `a listener can reenter and close without deadlocking or outliving the handle`() {
        val fake = library()
        val callback = fake.block("callback")
        val closeReturned = CountDownLatch(1)
        lateinit var activePlayer: MpvPlayer
        activePlayer = player(fake, object : MpvPlayer.Listener {
            override fun onFileLoaded() {
                activePlayer.setPaused(true) // Reentrant read lease while the pump holds one.
                activePlayer.close()
                activePlayer.seekTo(20_000) // Rejected even on the callback's thread.
                closeReturned.countDown()
                callback.stopHere()
            }
        })
        fake.events.put(8)
        activePlayer.open(1, MpvPlayer.Config())
        await(closeReturned)
        callback.awaitEntered()

        assertEquals(0, fake.count("command"))
        assertEquals(0, fake.count("destroy"), "callback still owns the event lease")
        callback.release.countDown()
        fake.awaitDestroyed()
    }

    @Test
    fun `close during creation or initialization retires the eventual handle exactly once`() {
        for (operation in listOf("create", "request-log", "option", "initialize")) {
            val fake = library()
            val gate = fake.block(operation)
            val player = player(fake)
            val opening = executor.submit { player.open(1, MpvPlayer.Config()) }
            gate.awaitEntered()

            closeWithoutWaiting(player)
            assertEquals(0, fake.count("destroy"), "must wait for $operation")
            assertEquals(0, fake.count("wait-event"), "pump must not access an uninitialized handle")
            gate.release.countDown()
            opening.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            fake.awaitDestroyed()
            assertEquals(0, fake.count("wait-event"))
        }
    }

    @Test
    fun `initialization failure destroys the allocated handle and starts no pump`() {
        val fake = library().apply { initializeResult = -1 }
        val player = player(fake)
        assertFailsWith<IllegalStateException> { player.open(1, MpvPlayer.Config()) }
        fake.awaitDestroyed()
        assertEquals(0, fake.count("wait-event"))
        assertFalse(player.bindKey("j", "subtitles"))
        assertNull(player.positionMs)
    }

    @Test
    fun `concurrent and repeated close destroy once including while a call is blocked`() {
        val fake = library()
        val player = player(fake).apply { open(1, MpvPlayer.Config()) }
        val gate = fake.block("command")
        val active = executor.submit { player.play("fake://video") }
        gate.awaitEntered()

        val closing = (1..16).map { executor.submit { player.close() } }
        closing.forEach { it.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        assertEquals(0, fake.count("destroy"))
        gate.release.countDown()
        active.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        fake.awaitDestroyed()
        player.close()
        assertEquals(1, fake.count("destroy"))
    }

    @Test
    fun `calls are rejected throughout destruction and after it completes`() {
        val fake = library()
        val gate = fake.block("destroy")
        val player = player(fake).apply { open(1, MpvPlayer.Config()) }
        closeWithoutWaiting(player)
        gate.awaitEntered()

        fun assertCallsRejected() {
            // Bounded future also catches a caller waiting on the destruction lock.
            executor.submit {
                assertFalse(player.bindKey("j", "subtitles"))
                player.setPaused(true)
                assertNull(player.positionMs)
                assertFailsWith<IllegalStateException> { player.open(2, MpvPlayer.Config()) }
                player.close()
            }.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
        val calls = fake.calls.toList()
        assertCallsRejected()
        assertEquals(calls, fake.calls.toList())
        gate.release.countDown()
        fake.awaitDestroyed()
        assertCallsRejected()
        assertEquals(calls, fake.calls.toList())
    }

    @Test
    fun `close before open creates nothing and a second open preserves the first handle`() {
        val unopened = library()
        val closed = player(unopened)
        closed.close()
        assertFailsWith<IllegalStateException> { closed.open(1, MpvPlayer.Config()) }
        assertEquals(0, unopened.count("create"))
        assertEquals(0, unopened.count("destroy"))

        val fake = library()
        val waiting = fake.block("wait-event")
        val player = player(fake).apply { open(1, MpvPlayer.Config()) }
        waiting.awaitEntered()
        executor.submit {
            assertFailsWith<IllegalStateException> { player.open(2, MpvPlayer.Config()) }
        }.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        assertEquals(1, fake.count("create"))
        assertEquals(0, fake.count("destroy"))
    }

    private fun library() = FakeMpv().also(libraries::add)

    private fun player(fake: FakeMpv, listener: MpvPlayer.Listener = object : MpvPlayer.Listener {}) =
        MpvPlayer(listener, fake).also(players::add)

    private fun closeWithoutWaiting(player: MpvPlayer) {
        executor.submit { player.close() }.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private class Gate {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fun awaitEntered() = await(entered)
        fun stopHere() {
            entered.countDown()
            await(release)
        }
    }

    private class FakeMpv : MpvLibrary {
        val calls = ConcurrentLinkedQueue<String>()
        val violations = ConcurrentLinkedQueue<String>()
        val events = LinkedBlockingQueue<Int>()
        val created = AtomicBoolean(false)
        var initializeResult = 0
        private val handle = Pointer(1)
        private val gates = ConcurrentHashMap<String, Gate>()
        private val active = AtomicInteger(0)
        private val destroyed = AtomicBoolean(false)
        private val destroyDone = CountDownLatch(1)
        private val strings = AtomicInteger(0)

        fun block(operation: String) = Gate().also { gates[operation] = it }
        fun unblock() = gates.values.forEach { it.release.countDown() }
        fun count(operation: String) = calls.count { it == operation }
        fun awaitDestroyed() {
            await(destroyDone)
            assertEquals(1, count("destroy"))
            assertTrue(violations.isEmpty(), violations.joinToString())
        }

        private fun <T> call(operation: String, block: () -> T): T {
            active.incrementAndGet()
            try {
                if (destroyed.get()) violations.add("$operation used a destroyed handle")
                calls.add(operation)
                gates[operation]?.stopHere()
                return block()
            } finally {
                active.decrementAndGet()
            }
        }

        override fun mpv_create(): Pointer = call("create") { created.set(true); handle }
        override fun mpv_initialize(ctx: Pointer): Int = call("initialize") { initializeResult }
        override fun mpv_terminate_destroy(ctx: Pointer) {
            calls.add("destroy")
            if (!destroyed.compareAndSet(false, true)) violations.add("duplicate destroy")
            if (active.get() != 0) violations.add("destroy overlapped a native call or pointer read")
            if (strings.get() != 0) violations.add("destroy ran before the property string was freed")
            try {
                gates["destroy"]?.stopHere()
            } finally {
                destroyDone.countDown()
            }
        }

        override fun mpv_request_log_messages(ctx: Pointer, minLevel: String): Int = call("request-log") { 0 }
        override fun mpv_set_option_string(ctx: Pointer, name: String, data: String): Int = call("option") { 0 }
        override fun mpv_set_property_string(ctx: Pointer, name: String, data: String): Int = call("set-property") { 0 }
        override fun mpv_command(ctx: Pointer, args: Array<String?>): Int = call("command") { 0 }
        override fun mpv_wakeup(ctx: Pointer) = call("wakeup") { }
        override fun mpv_client_api_version(): Long = call("version") { 0 }
        override fun mpv_error_string(error: Int): String = call("error-string") { "fake error" }

        override fun mpv_get_property_string(ctx: Pointer, name: String): Pointer = call("get-property") {
            strings.incrementAndGet()
            object : Pointer(2) {
                override fun getString(offset: Long): String = call("read-string") { "12.5" }
            }
        }

        override fun mpv_free(data: Pointer) = call("free") { strings.decrementAndGet(); Unit }

        override fun mpv_wait_event(ctx: Pointer, timeout: Double): Pointer = call("wait-event") {
            val id = events.poll((timeout * 1000).toLong(), TimeUnit.MILLISECONDS) ?: 0
            object : Pointer(3) {
                override fun getInt(offset: Long): Int = call("read-event") { id }
            }
        }
    }

    private companion object {
        const val TIMEOUT_SECONDS = 5L
        fun await(latch: CountDownLatch) {
            check(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "Timed out waiting for test boundary" }
        }
    }
}
