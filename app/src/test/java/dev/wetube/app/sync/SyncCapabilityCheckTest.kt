package dev.wetube.app.sync

import dev.wetube.app.data.backend.OpResult
import dev.wetube.app.data.backend.RemoteChannel
import dev.wetube.app.data.backend.RemotePlaylist
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives [SyncCapabilityCheck] through the same [FakeSyncBackend] the engine
 * tests use, so the check is exercised against real [OpResult]s rather than a
 * mock. No Android types are involved.
 */
class SyncCapabilityCheckTest {

    private val backend = FakeSyncBackend()
    private val check = SyncCapabilityCheck(backend)

    @Test
    fun `checkReads reports one probe per read`() = runBlocking {
        backend.accountChannelIdResult = OpResult.Ok(FakeSyncBackend.ACCOUNT_CHANNEL_ID)
        backend.subscriptionsResult = OpResult.Ok(listOf(RemoteChannel("UCa", "A")))
        backend.playlistsResult = OpResult.Ok(listOf(RemotePlaylist("PLx", "Mine")))

        val probes = check.checkReads()

        assertEquals(3, probes.size)
        assertEquals(
            listOf(
                SyncCapabilityCheck.PROBE_ACCOUNT,
                SyncCapabilityCheck.PROBE_SUBSCRIPTIONS,
                SyncCapabilityCheck.PROBE_PLAYLISTS,
            ),
            probes.map { it.name },
        )
        assertTrue("every read succeeded", probes.all { it.passed })
        assertEquals("1 subscription(s)", probes[1].detail)
    }

    @Test
    fun `checkReads marks a failing read as failed with its reason`() = runBlocking {
        backend.playlistsResult = OpResult.Terminal("HTTP 403")

        val probes = check.checkReads()

        val failed = probes.single { !it.passed }
        assertEquals(SyncCapabilityCheck.PROBE_PLAYLISTS, failed.name)
        assertEquals("HTTP 403", failed.detail)
        // The other reads still ran and passed.
        assertTrue(probes.filter { it.name != SyncCapabilityCheck.PROBE_PLAYLISTS }.all { it.passed })
    }

    @Test
    fun `checkWriteProbe creates then deletes exactly the returned id`() = runBlocking {
        backend.createPlaylistId = "PLprobe"

        val probes = check.checkWriteProbe()

        assertEquals(2, probes.size)
        assertTrue(probes.all { it.passed })
        assertEquals(
            listOf(FakeSyncBackend.M_CREATE_PLAYLIST, FakeSyncBackend.M_DELETE_PLAYLIST),
            backend.calls.map { it.method },
        )
        val create = backend.calls.first { it.method == FakeSyncBackend.M_CREATE_PLAYLIST }
        assertEquals(SyncCapabilityCheck.WRITE_PROBE_TITLE, create.args[0])
        assertEquals(emptyList<String>(), create.args[1])
        val delete = backend.calls.first { it.method == FakeSyncBackend.M_DELETE_PLAYLIST }
        assertEquals(listOf("PLprobe"), delete.args)
    }

    @Test
    fun `a failed create skips the delete and deletes nothing`() = runBlocking {
        backend.failNextWrites(1, OpResult.Terminal("HTTP 403"))

        val probes = check.checkWriteProbe()

        assertEquals(2, probes.size)
        assertEquals(SyncCapabilityCheck.PROBE_CREATE_PLAYLIST, probes[0].name)
        assertFalse(probes[0].passed)
        assertEquals("HTTP 403", probes[0].detail)
        assertEquals(SyncCapabilityCheck.PROBE_DELETE_PLAYLIST, probes[1].name)
        assertFalse(probes[1].passed)
        assertEquals(0, backend.calls.count { it.method == FakeSyncBackend.M_DELETE_PLAYLIST })
    }
}
