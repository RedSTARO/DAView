package com.daview.app.data

import com.daview.app.platform.ExternalPlayRequest
import com.daview.app.platform.ExternalPlaybackHandle
import com.daview.app.platform.ExternalPlayerInfo
import com.daview.shared.model.MediaItemDto
import com.daview.shared.model.PlaybackInfoDto
import com.daview.shared.model.PlaybackProgressRequest
import com.daview.shared.model.PlaybackStartRequest
import com.daview.shared.model.PlaybackStopRequest
import com.daview.shared.model.PlayerKind
import com.daview.shared.model.SessionStateDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class PlayerHandle : ExternalPlaybackHandle {
    var stops = 0
    override val canObserveExit = true
    override fun isRunning() = stops == 0
    override suspend fun awaitExit() = Unit
    override fun stop() { ++stops }
}

private class PlaybackStub : PlaybackBackend {
    val player = ExternalPlayerInfo("mpv", "mpv", executablePath = "fake-mpv")
    override val externalPlayers = listOf(player)
    override val hasInternalPlayer = true
    override val deviceName = "test"
    val starts = mutableListOf<PlaybackStartRequest>()
    val reads = mutableListOf<String>()
    val stops = mutableListOf<PlaybackStopRequest>()
    val events = mutableListOf<String>()
    val active = mutableMapOf<String, PlaybackInfoDto>()
    val handles = mutableMapOf<String, PlayerHandle>()
    var syncs = 0
    var read: suspend (String) -> MediaItemDto = { media(it) }
    var startCall: suspend (PlaybackStartRequest) -> PlaybackInfoDto = { create(it.itemId) }
    var stopCall: suspend (PlaybackStopRequest) -> Unit = {}
    var poll: suspend () -> List<SessionStateDto> = { active.values.map { session(it) } }
    var launchCall: (ExternalPlayRequest) -> ExternalPlaybackHandle? = {
        PlayerHandle().also { handle -> handles[it.streamUrl] = handle }
    }
    fun create(itemId: String, sessionId: String = "session-$itemId"): PlaybackInfoDto = PlaybackInfoDto(
        sessionId = sessionId, item = media(itemId), streamUrl = "stream-$itemId", startPositionMs = 123L
    ).also { active[it.sessionId] = it }
    fun session(info: PlaybackInfoDto, position: Long = 0L) = SessionStateDto(
        info.sessionId, info.item.id, info.item.name, PlayerKind.MPV, "test", position,
        startedAt = 0L, updatedAt = 0L
    )
    override suspend fun item(id: String): MediaItemDto { reads += id; return read(id) }
    override suspend fun nextEpisode(id: String) = read(id)
    override suspend fun start(request: PlaybackStartRequest): PlaybackInfoDto {
        starts += request
        return startCall(request)
    }
    override suspend fun stop(request: PlaybackStopRequest) {
        stops += request
        events += "stop:${request.sessionId}"
        stopCall(request)
        active.remove(request.sessionId)
    }
    override suspend fun report(request: PlaybackProgressRequest) = Unit
    override suspend fun keepAlive(sessionId: String) = Unit
    override suspend fun sessions() = poll()
    override suspend fun sync() { ++syncs; events += "sync" }
    override fun launch(request: ExternalPlayRequest) = launchCall(request)
}

private class PlaybackFixture : AutoCloseable {
    val base = StateFixture()
    val state = base.state
    val backend = PlaybackStub()
    val controller = PlaybackController(state, base.scope, backend, base.dispatcher)
    init { state.switchTo(Screen.Search) }
    fun drain() = base.drain()
    fun play(id: String): PlaybackInfoDto {
        controller.playInternal(media(id))
        drain()
        return checkNotNull(controller.info)
    }
    override fun close() {
        if (controller.externalPlayerLabel == null) controller.info?.let { controller.closePlayer(it.sessionId, 123L) }
        base.close()
        assertFalse(ActivePlayback.externalRunning)
    }
}

class PlaybackControllerRaceTest {
    @Test
    fun `external menu replacement retires old owner and rejects its late poll`() = PlaybackFixture().use { f ->
        val oldPoll = PendingRead<List<SessionStateDto>>()
        var calls = 0
        f.backend.poll = {
            if (++calls == 1) oldPoll.await() else f.backend.active.values.map { f.backend.session(it, 22L) }
        }
        f.controller.playExternal(media("a"), f.backend.player)
        f.drain()
        val old = checkNotNull(f.controller.info)
        val oldHandle = checkNotNull(f.backend.handles["stream-a"])
        f.controller.leaveExternalPanel()
        f.controller.playExternal(media("b"), f.backend.player)
        f.drain()
        val newHandle = checkNotNull(f.backend.handles["stream-b"])
        assertEquals(1, oldHandle.stops)
        assertEquals(listOf(old.sessionId), f.backend.stops.map { it.sessionId })
        oldPoll.complete(listOf(f.backend.session(old, 999L)))
        f.drain()
        assertEquals("session-b", f.controller.info?.sessionId)
        assertEquals("session-b", f.controller.externalSession?.sessionId)
        assertEquals(22L, f.controller.externalSession?.positionMs)
        assertEquals(0, newHandle.stops)
        assertTrue(ActivePlayback.externalRunning)
        f.controller.stopExternal()
        f.drain()
        assertEquals(1, newHandle.stops)
        assertEquals(listOf("session-a", "session-b"), f.backend.stops.map { it.sessionId })
    }

    @Test
    fun `exit during next item lookup invalidates the whole handover`() = PlaybackFixture().use { f ->
        val old = f.play("a")
        val lookup = PendingRead<MediaItemDto>()
        f.backend.read = { lookup.await() }
        f.controller.skipTo("b", positionMs = 456L)
        assertTrue(f.controller.starting)
        f.controller.skipTo("c", positionMs = 456L)
        f.drain()
        assertEquals(listOf("b"), f.backend.reads)
        f.controller.stopAndLeave()
        // Model the engine disposal callback after navigation.
        f.controller.closePlayer(old.sessionId, 456L)
        lookup.complete(media("b"))
        f.drain()
        assertEquals(Screen.Search, f.state.current)
        assertNull(f.controller.info)
        assertFalse(f.controller.starting)
        assertEquals(listOf("a"), f.backend.starts.map { it.itemId })
        assertEquals(listOf(PlaybackStopRequest(old.sessionId, 456L)), f.backend.stops)
    }

    @Test
    fun `exit during session creation releases only the cancelled handover session`() = PlaybackFixture().use { f ->
        val old = f.play("a")
        val acquired = PendingRead<Unit>()
        f.backend.startCall = { request ->
            val created = f.backend.create(request.itemId)
            if (request.itemId == "b") acquired.await()
            created
        }
        f.controller.skipTo("b", positionMs = 789L)
        f.drain()
        f.controller.stopAndLeave()
        f.controller.closePlayer(old.sessionId, 789L)
        f.controller.playInternal(media("c"))
        f.drain()
        acquired.complete(Unit)
        f.drain()
        assertEquals("session-c", f.controller.info?.sessionId)
        assertEquals(Screen.Player("c"), f.state.current)
        assertEquals(setOf("session-a", "session-b"), f.backend.stops.map { it.sessionId }.toSet())
        assertEquals(setOf("session-c"), f.backend.active.keys)
    }

    @Test
    fun `cancelled acquisition finally cannot dismiss a newer start overlay`() = PlaybackFixture().use { f ->
        val a = PendingRead<Unit>()
        val b = PendingRead<Unit>()
        f.backend.startCall = { request ->
            val created = f.backend.create(request.itemId)
            if (request.itemId == "a") a.await() else b.await()
            created
        }
        f.controller.playInternal(media("a"))
        f.drain()
        f.controller.cancelStart()
        f.controller.playInternal(media("b"))
        f.drain()
        a.complete(Unit)
        f.drain()
        assertTrue(f.controller.starting)
        assertEquals("b", f.controller.startingName)
        assertNull(f.controller.info)
        assertEquals(listOf("session-a"), f.backend.stops.map { it.sessionId })
        b.complete(Unit)
        f.drain()
        assertFalse(f.controller.starting)
        assertEquals("session-b", f.controller.info?.sessionId)
    }

    @Test
    fun `Back waits for final position callback without stopping the session early`() = PlaybackFixture().use { f ->
        val playback = f.play("a")
        f.controller.reportProgress(100L, false, null, null)
        f.drain()
        f.controller.stopAndLeave()
        f.drain()
        assertTrue(f.backend.stops.isEmpty(), "No estimated stop may precede engine disposal")
        f.controller.closePlayer(playback.sessionId, 95_432L)
        f.controller.closePlayer(playback.sessionId, 100L)
        f.drain()
        assertEquals(listOf(PlaybackStopRequest(playback.sessionId, 95_432L)), f.backend.stops)
        assertEquals(listOf("stop:${playback.sessionId}", "sync"), f.backend.events)
        assertEquals(1, f.backend.syncs)
    }

    @Test
    fun `dispose after UI scope cancellation preserves zero position and still syncs`() = PlaybackFixture().use { f ->
        val playback = f.play("a")
        f.base.scope.cancel()
        f.drain()
        assertTrue(f.backend.stops.isEmpty())
        f.controller.closePlayer(playback.sessionId, 0L)
        f.drain()
        // Zero is a seek to the beginning, not an instruction to use the old estimate.
        assertEquals(listOf(PlaybackStopRequest(playback.sessionId, 0L)), f.backend.stops)
        assertEquals(1, f.backend.syncs)
        assertTrue(f.backend.active.isEmpty())
    }

    @Test
    fun `creation returning after UI cancellation is never published and is cleaned up`() = PlaybackFixture().use { f ->
        val acquisition = PendingRead<Unit>()
        f.backend.startCall = {
            val created = f.backend.create(it.itemId)
            acquisition.await()
            created
        }
        f.controller.playInternal(media("a"))
        f.drain()
        f.base.scope.cancel()
        acquisition.complete(Unit)
        f.drain()
        assertNull(f.controller.info)
        assertNull(f.controller.error)
        assertEquals(Screen.Search, f.state.current)
        assertEquals(listOf("session-a"), f.backend.stops.map { it.sessionId })
        assertTrue(f.backend.active.isEmpty())
    }

    @Test
    fun `external launch cancellation cleans acquired session without showing an error`() = PlaybackFixture().use { f ->
        f.backend.launchCall = { throw CancellationException("launch cancelled") }
        f.controller.playExternal(media("a"), f.backend.player)
        f.drain()
        assertNull(f.controller.error)
        assertNull(f.controller.info)
        assertFalse(f.controller.starting)
        assertEquals(listOf("session-a"), f.backend.stops.map { it.sessionId })
        assertTrue(f.backend.active.isEmpty())
    }

    @Test
    fun `external launch failure cleans its session and allows retry`() = PlaybackFixture().use { f ->
        f.backend.launchCall = { null }
        f.controller.playExternal(media("a"), f.backend.player)
        f.drain()
        assertTrue(f.controller.errorFor("a")?.contains("无法启动") == true)
        assertNull(f.controller.info)
        assertFalse(f.controller.starting)
        assertTrue(f.backend.active.isEmpty())
        f.backend.launchCall = { PlayerHandle() }
        f.controller.playExternal(media("a"), f.backend.player)
        f.drain()
        assertNull(f.controller.error)
        assertEquals("session-a", f.controller.info?.sessionId)
    }

    @Test
    fun `cleanup timeout cancels a suspended backend stop instead of waiting forever`() = StateFixture().use { f ->
        val backend = PlaybackStub()
        val interrupted = CompletableDeferred<Unit>()
        backend.stopCall = {
            try { awaitCancellation() } finally { interrupted.complete(Unit) }
        }
        f.state.switchTo(Screen.Search)
        val controller = PlaybackController(f.state, f.scope, backend, Dispatchers.Default, cleanupTimeoutMs = 25L)
        controller.playInternal(media("a"))
        f.drain()
        val sessionId = checkNotNull(controller.info).sessionId
        f.scope.cancel()
        controller.closePlayer(sessionId, 55L)
        runBlocking { withTimeout(5_000L) { interrupted.await() } }
        assertEquals(listOf(PlaybackStopRequest(sessionId, 55L)), backend.stops)
        assertEquals(0, backend.syncs)
    }
}
