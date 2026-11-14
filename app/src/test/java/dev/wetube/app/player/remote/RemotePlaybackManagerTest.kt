package dev.wetube.app.player.remote

import dev.wetube.app.data.model.StreamFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session state machine against a scripted renderer: no socket, no Android,
 * virtual time. Every case pins one promise of the cast path — the connect
 * order, when a seek is worth it, that buffering never looks like a pause, that
 * a disconnect really stops polling, and that a dead stream recovers a bounded
 * number of times.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemotePlaybackManagerTest {

    private val target = RemoteTarget(
        id = "uuid:living-room",
        friendlyName = "Living room TV",
        controlUrl = "http://192.168.1.10:9197/control",
        address = "192.168.1.10",
    )

    private fun media(url: String = "https://cdn.test/video.mp4") = RemoteMedia(
        videoId = "abc123",
        url = url,
        mime = "video/mp4",
        title = "A video",
    )

    private fun TestScope.manager(
        renderer: RemoteRenderer,
        resolve: suspend (String) -> StreamFormat? = {
            StreamFormat(
                url = "https://cdn.test/fresh",
                label = "360p",
                mime = "video/mp4",
                height = 360,
            )
        },
    ): RemotePlaybackManager = manager(renderer, backgroundScope, resolve)

    private fun manager(
        renderer: RemoteRenderer,
        scope: CoroutineScope,
        resolve: suspend (String) -> StreamFormat? = {
            StreamFormat(
                url = "https://cdn.test/fresh",
                label = "360p",
                mime = "video/mp4",
                height = 360,
            )
        },
        // Virtual time does not advance a monotonic clock, so the default is a
        // frozen one: segment time and "no starved gap" both hold by construction
        // unless a test supplies its own clock.
        nowMs: () -> Long = { 0L },
    ): RemotePlaybackManager = RemotePlaybackManager(
        discovery = FakeDiscovery,
        resolveProgressive = resolve,
        scope = scope,
        http = FakeHttp,
        rendererFor = { renderer },
        nowMs = nowMs,
    )

    @Test
    fun `connect probes loads seeks and plays in that order`() = runTest {
        val renderer = FakeRenderer()
        val manager = manager(renderer)

        val connected = manager.connect(target, media(), startAtMs = 10_000)

        assertTrue(connected)
        assertEquals(
            listOf("probe", "load:https://cdn.test/video.mp4", "seek:10000", "play", "volume"),
            renderer.calls,
        )
        val active = manager.state.value as RemoteSessionState.Active
        assertEquals(target.friendlyName, active.target.friendlyName)
        assertEquals(10_000L, active.positionMs)
        assertFalse(active.paused)
    }

    @Test
    fun `a handoff at or below five seconds is not seeked`() = runTest {
        val renderer = FakeRenderer()

        manager(renderer).connect(target, media(), startAtMs = 5_000)

        assertFalse(renderer.calls.any { it.startsWith("seek") })
    }

    @Test
    fun `a handoff past five seconds is seeked once`() = runTest {
        val renderer = FakeRenderer()

        manager(renderer).connect(target, media(), startAtMs = 5_001)

        assertEquals(1, renderer.calls.count { it == "seek:5001" })
    }

    @Test
    fun `a buffering renderer does not flip the paused state`() = runTest {
        val renderer = FakeRenderer()
        val manager = manager(renderer)
        manager.connect(target, media())
        manager.pause()

        renderer.nextPosition = RemotePosition(10_000, 60_000)
        renderer.nextTransport = RemoteTransport.TRANSITIONING
        advanceTimeBy(3_000)
        runCurrent()

        val active = manager.state.value as RemoteSessionState.Active
        assertTrue("pause is the user's, not the device's", active.paused)
        assertTrue(active.transitioning)
    }

    @Test
    fun `an unknown transport state changes neither paused nor failed`() = runTest {
        val renderer = FakeRenderer()
        val manager = manager(renderer)
        manager.connect(target, media())
        manager.pause()

        renderer.nextTransport = RemoteTransport.OTHER
        advanceTimeBy(3_000)
        runCurrent()

        val active = manager.state.value as RemoteSessionState.Active
        assertTrue(active.paused)
        assertFalse("an unrecognised state must not start a spinner", active.transitioning)
    }

    @Test
    fun `disconnect returns the renderer's last position`() = runTest {
        val renderer = FakeRenderer()
        val manager = manager(renderer)
        manager.connect(target, media(), startAtMs = 30_000)
        renderer.nextPosition = RemotePosition(42_000, 60_000)

        val position = manager.disconnect()

        assertEquals(42_000L, position)
        assertEquals(RemoteSessionState.Idle, manager.state.value)
    }

    @Test
    fun `disconnect stops the poll loop`() = runTest {
        val renderer = FakeRenderer()
        val manager = manager(renderer)
        manager.connect(target, media())

        manager.disconnect()
        val after = renderer.calls.size

        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(after, renderer.calls.size)
    }

    @Test
    fun `three unanswered polls end the session as transport lost`() = runTest {
        val renderer = FakeRenderer()
        val manager = manager(renderer)
        manager.connect(target, media())

        renderer.failPosition = RemoteFailure.Unreachable(target.address)
        advanceTimeBy(3_000)
        runCurrent()

        val failed = manager.state.value as RemoteSessionState.Failed
        assertEquals(RemoteFailure.TransportLost(target.friendlyName), failed.failure)
    }

    @Test
    fun `a starved poll gap forces a transport check and recovers a dead stream`() = runTest {
        var now = 0L
        val renderer = FakeRenderer()
        val manager = manager(renderer, backgroundScope, nowMs = { now })
        manager.connect(target, media())
        runCurrent()

        // One ordinary tick proves the stream alive at 30 s; no transport read
        // yet, because that is only due every third tick.
        renderer.nextPosition = RemotePosition(30_000, 60_000)
        renderer.nextTransport = RemoteTransport.PLAYING
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(0, renderer.calls.count { it == "transport" })

        // The loop is starved for a minute (a frozen process), then wakes to a
        // renderer that has stopped at zero: the URL died unobserved. Forcing the
        // transport read on the starved tick is what turns that into a reload
        // instead of a live-looking session over an orphaned stream.
        now += 60_000
        renderer.nextPosition = RemotePosition(0, 60_000)
        renderer.nextTransport = RemoteTransport.STOPPED
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(1, renderer.calls.count { it == "transport" })
        assertTrue(
            "the starved tick must reload the dead stream",
            renderer.calls.any { it.startsWith("load:https://cdn.test/fresh") },
        )
        assertTrue(manager.state.value is RemoteSessionState.Active)
    }

    @Test
    fun `a starved gap after the retry cap ends the session as transport lost`() = runTest {
        var now = 0L
        val renderer = FakeRenderer()
        val manager = manager(renderer, backgroundScope, nowMs = { now })
        manager.connect(target, media())
        runCurrent()

        // Burn the three reloads the watchdog allows while the loop is healthy.
        repeat(3) { deathCycle(renderer) }
        assertTrue(manager.state.value is RemoteSessionState.Active)

        // Prove progress, then starve the loop and wake to a stream stopped at
        // zero. With the retries spent, the forced transport read on the starved
        // tick ends the session honestly instead of leaving a stale live one.
        renderer.nextPosition = RemotePosition(30_000, 60_000)
        renderer.nextTransport = RemoteTransport.PLAYING
        advanceTimeBy(3_000)
        runCurrent()

        now += 60_000
        renderer.nextPosition = RemotePosition(0, 60_000)
        renderer.nextTransport = RemoteTransport.STOPPED
        advanceTimeBy(1_000)
        runCurrent()

        val failed = manager.state.value as RemoteSessionState.Failed
        assertEquals(RemoteFailure.TransportLost(target.friendlyName), failed.failure)
    }

    @Test
    fun `a dead stream is reloaded with a fresh URL and the same position`() = runTest {
        val renderer = FakeRenderer()
        val manager = manager(renderer)
        manager.connect(target, media())

        deathCycle(renderer)

        assertEquals(
            listOf("load:https://cdn.test/fresh"),
            renderer.calls.filter { it.startsWith("load:https://cdn.test/fresh") },
        )
        assertEquals(1, renderer.calls.count { it == "seek:30000" })
        assertTrue(manager.state.value is RemoteSessionState.Active)
    }

    @Test
    fun `stream death is retried three times and then fails`() = runTest {
        val renderer = FakeRenderer()
        val manager = manager(renderer)
        manager.connect(target, media())

        repeat(4) { deathCycle(renderer) }

        // One initial load plus exactly three reloads.
        assertEquals(4, renderer.calls.count { it.startsWith("load:") })
        assertEquals(3, renderer.calls.count { it == "seek:30000" })
        val failed = manager.state.value as RemoteSessionState.Failed
        assertEquals(RemoteFailure.TransportLost(target.friendlyName), failed.failure)
    }

    @Test
    fun `an unsupported handoff leaves local playback alone`() = runTest {
        val renderer = FakeRenderer().apply { failProbe = RemoteFailure.Unsupported }
        val manager = manager(renderer)

        val connected = manager.connect(target, media(), startAtMs = 10_000)

        assertFalse(connected)
        assertFalse(renderer.calls.contains("play"))
        val failed = manager.state.value as RemoteSessionState.Failed
        assertEquals(RemoteFailure.Unsupported, failed.failure)
    }

    /**
     * One watchdog cycle: prove progress, then report `STOPPED` at zero on a
     * transport tick. The poll loop reads transport every third tick, so each
     * phase advances exactly that far.
     */
    private fun TestScope.deathCycle(renderer: FakeRenderer) {
        renderer.nextPosition = RemotePosition(30_000, 60_000)
        renderer.nextTransport = RemoteTransport.PLAYING
        advanceTimeBy(3_000)
        runCurrent()

        renderer.nextPosition = RemotePosition(0, 60_000)
        renderer.nextTransport = RemoteTransport.STOPPED
        advanceTimeBy(3_000)
        runCurrent()
    }

    private class FakeRenderer : RemoteRenderer {

        val calls = mutableListOf<String>()

        var nextPosition = RemotePosition(0, 0)
        var nextTransport = RemoteTransport.PLAYING
        var volumeValue: Float? = 0.5f
        var failProbe: RemoteFailure? = null
        var failPosition: RemoteFailure? = null
        var failTransport: RemoteFailure? = null

        override suspend fun probe(caps: RemoteCapabilities): RemoteCapabilities {
            calls += "probe"
            failProbe?.let { throw RemoteCallException(it) }
            return caps
        }

        override suspend fun load(media: RemoteMedia) {
            calls += "load:${media.url}"
        }

        override suspend fun play() {
            calls += "play"
        }

        override suspend fun pause() {
            calls += "pause"
        }

        override suspend fun stop() {
            calls += "stop"
        }

        override suspend fun seekTo(positionMs: Long) {
            calls += "seek:$positionMs"
        }

        override suspend fun position(): RemotePosition {
            calls += "position"
            failPosition?.let { throw RemoteCallException(it) }
            return nextPosition
        }

        override suspend fun transportState(): RemoteTransport {
            calls += "transport"
            failTransport?.let { throw RemoteCallException(it) }
            return nextTransport
        }

        override suspend fun volume(): Float? {
            calls += "volume"
            return volumeValue
        }

        override suspend fun setVolume(level: Float) {
            calls += "setVolume:$level"
            volumeValue = level
        }
    }

    private object FakeHttp : LanHttp {
        override suspend fun post(
            url: String,
            contentType: String,
            headers: Map<String, String>,
            body: String,
        ): String = error("the manager must not speak HTTP directly")

        override suspend fun get(url: String): String = error("the manager must not speak HTTP directly")
    }

    private object FakeDiscovery : RemoteDiscovery {
        override val targets: StateFlow<List<RemoteTarget>> = MutableStateFlow(emptyList())
        override val scanning: StateFlow<Boolean> = MutableStateFlow(false)
        override val failure: StateFlow<RemoteFailure?> = MutableStateFlow(null)
        override fun start() = Unit
        override fun stop() = Unit
        override suspend fun refresh(): List<RemoteTarget> = emptyList()
    }
}