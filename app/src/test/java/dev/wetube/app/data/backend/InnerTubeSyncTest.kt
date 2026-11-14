package dev.wetube.app.data.backend

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises [InnerTubeSync] through a scripted [SyncTransport].
 *
 * The transport is the only seam, so no test here touches a socket, a cookie or
 * a `Context`: every response is a canned [SyncReply] and every request is
 * recorded and asserted. That keeps the request *shapes* (which the sync engine
 * and docs/SYNC_PROTOCOL.md depend on) as testable as the response parsing, and
 * it is what lets the write paths be checked long before the SYNC-0 capability
 * test proves them against a real account.
 *
 * Fixtures under `src/test/resources/sync` are hand-written, shape-accurate JSON
 * — never captured from an account.
 */
class InnerTubeSyncTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    /** One recorded POST: where it went and the non-context members it carried. */
    private data class Request(val path: String, val extra: JsonObject)

    /**
     * A [SyncTransport] that records every call and answers from a script.
     *
     * The responder is a plain lambda so each test can map a path (or a
     * `browseId` inside the extra) to a canned reply, and the recorded requests
     * let the same test assert the request shape it caused.
     */
    private class FakeTransport : SyncTransport {
        val requests = mutableListOf<Request>()

        var responder: (String, JsonObject) -> SyncReply = { _, _ -> SyncReply.Http(200, "{}") }

        override suspend fun post(path: String, extra: JsonObject): SyncReply {
            requests += Request(path, extra)
            return responder(path, extra)
        }
    }

    private fun backend(transport: SyncTransport) = InnerTubeSync("Test sync", transport, json)

    private fun fixture(name: String): String =
        javaClass.getResource("/sync/$name")!!.readText()

    private fun JsonObject.string(key: String): String =
        getValue(key).jsonPrimitive.contentOrNull.orEmpty()

    // ----- result helpers ---------------------------------------------------

    private fun <T> assertOk(result: OpResult<T>): T {
        assertTrue("expected Ok but was $result", result is OpResult.Ok)
        @Suppress("UNCHECKED_CAST")
        return (result as OpResult.Ok<T>).value
    }

    private fun assertRetryable(result: OpResult<*>) =
        assertTrue("expected Retryable but was $result", result is OpResult.Retryable)

    private fun assertTerminal(result: OpResult<*>) =
        assertTrue("expected Terminal but was $result", result is OpResult.Terminal)

    private fun assertAuth(result: OpResult<*>) =
        assertTrue("expected AuthRequired but was $result", result is OpResult.AuthRequired)

    // ----- pullSubscriptions ------------------------------------------------

    @Test
    fun `pullSubscriptions reads channels from FEchannels`() = runTest {
        val transport = FakeTransport().apply {
            responder = { _, _ -> SyncReply.Http(200, fixture("fe_channels.json")) }
        }

        val channels = assertOk(backend(transport).pullSubscriptions())

        assertEquals("browse", transport.requests.single().path)
        assertEquals("FEchannels", transport.requests.single().extra.string("browseId"))
        assertEquals(
            listOf("UCAAAABBBBCCCCDDDDEEEEFF", "UCGGGGHHHHIIIIJJJJKKKKLL"),
            channels.map { it.channelId },
        )
        assertEquals("Alpha Studio", channels[0].title)
        assertEquals("https://example.com/alpha.jpg", channels[0].avatar)
        assertEquals("1.2M subscribers", channels[0].subscriberText)
        // The second card carries no channelId, only its navigation endpoint.
        assertEquals("Beta Channel", channels[1].title)
        assertEquals("https://example.com/beta.jpg", channels[1].avatar)
    }

    @Test
    fun `pullSubscriptions falls back to FEsubscriptions when FEchannels is empty`() = runTest {
        val transport = FakeTransport().apply {
            responder = { _, extra ->
                when (extra.string("browseId")) {
                    "FEchannels" -> SyncReply.Http(200, "{}")
                    else -> SyncReply.Http(200, fixture("fe_subscriptions.json"))
                }
            }
        }

        val channels = assertOk(backend(transport).pullSubscriptions())

        assertEquals(
            listOf("FEchannels", "FEsubscriptions"),
            transport.requests.map { it.extra.string("browseId") },
        )
        // The degraded fallback still yields channel ids from each card's byline.
        assertEquals(listOf("UCMMMMNNNNOOOOPPPPQQQQRR"), channels.map { it.channelId })
    }

    @Test
    fun `pullSubscriptions returns empty when both endpoints are empty`() = runTest {
        val transport = FakeTransport().apply {
            responder = { _, _ -> SyncReply.Http(200, "{}") }
        }

        val channels = assertOk(backend(transport).pullSubscriptions())

        assertTrue(channels.isEmpty())
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun `pullSubscriptions is retryable when both endpoints answer 500`() = runTest {
        val transport = FakeTransport().apply {
            responder = { _, _ -> SyncReply.Http(500, "boom") }
        }

        assertRetryable(backend(transport).pullSubscriptions())
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun `pullSubscriptions asks for auth when both endpoints answer 401`() = runTest {
        val transport = FakeTransport().apply {
            responder = { _, _ -> SyncReply.Http(401, "") }
        }

        assertAuth(backend(transport).pullSubscriptions())
    }

    // ----- pullPlaylists ----------------------------------------------------

    @Test
    fun `pullPlaylists parses grid and lockup playlists`() = runTest {
        val transport = FakeTransport().apply {
            responder = { _, _ -> SyncReply.Http(200, fixture("fe_playlist_aggregation.json")) }
        }

        val playlists = assertOk(backend(transport).pullPlaylists())

        assertEquals("FEplaylist_aggregation", transport.requests.single().extra.string("browseId"))
        assertEquals(
            listOf("PLroadtrip0000000000000000", "PLfavourites00000000000", "PLlockup000000000000000000"),
            playlists.map { it.playlistId },
        )
        assertEquals(12, playlists.first { it.title == "Road trip" }.itemCount)
        assertEquals(7, playlists.first { it.title == "Saved mix" }.itemCount)
    }

    @Test
    fun `pullPlaylists carries ids and titles without inferring ownership`() = runTest {
        val transport = FakeTransport().apply {
            responder = { _, _ -> SyncReply.Http(200, fixture("fe_playlist_aggregation.json")) }
        }

        val playlists = assertOk(backend(transport).pullPlaylists())

        // Ownership is never a property of a pull: the engine matches its
        // remembered mirror id against these ids, so a title is just data.
        val favourites = playlists.single { it.title == "Favourites" }
        assertEquals("PLfavourites00000000000", favourites.playlistId)
        assertEquals(3, favourites.itemCount)
    }

    @Test
    fun `pullPlaylists is an empty list for an empty account`() = runTest {
        val transport = FakeTransport().apply {
            responder = { _, _ -> SyncReply.Http(200, "{}") }
        }

        assertTrue(assertOk(backend(transport).pullPlaylists()).isEmpty())
    }

    // ----- pullPlaylist -----------------------------------------------------

    @Test
    fun `pullPlaylist requests the VL browse id and reads memberships`() = runTest {
        val transport = FakeTransport().apply {
            responder = { _, _ -> SyncReply.Http(200, fixture("playlist_first_page.json")) }
        }

        val page = assertOk(backend(transport).pullPlaylist("PLxyz"))

        assertEquals("VLPLxyz", transport.requests.single().extra.string("browseId"))
        assertEquals(listOf("v1", "v2"), page.items.map { it.videoId })
        assertEquals("set-1", page.items[0].setVideoId)
        assertEquals(1, page.items[0].index)
        assertEquals("First song", page.items[0].title)
        assertEquals("Dana", page.items[0].channelName)
        assertEquals("UCAAAABBBBCCCCDDDDEEEEFF", page.items[0].channelId)
        assertEquals("https://example.com/v1.jpg", page.items[0].thumbnail)
        assertEquals(3723, page.items[0].durationSeconds) // 1:02:03
        assertEquals(225, page.items[1].durationSeconds) // 3:45
        assertEquals("tok-2", page.next)
    }

    @Test
    fun `pullPlaylist sends the token from the previous page as a continuation`() = runTest {
        val transport = FakeTransport().apply {
            responder = { _, extra ->
                if (extra.containsKey("continuation")) {
                    SyncReply.Http(200, fixture("playlist_next_page.json"))
                } else {
                    SyncReply.Http(200, fixture("playlist_first_page.json"))
                }
            }
        }

        val first = assertOk(backend(transport).pullPlaylist("PLxyz"))
        assertEquals("tok-2", first.next)

        val second = assertOk(backend(transport).pullPlaylist("PLxyz", first.next))

        val secondExtra = transport.requests[1].extra
        assertEquals("tok-2", secondExtra.string("continuation"))
        assertFalse(secondExtra.containsKey("browseId"))
        assertEquals("v3", second.items.single().videoId)
        assertEquals("set-3", second.items.single().setVideoId)
        assertEquals("tok-3", second.next)
    }

    @Test
    fun `pullPlaylist leaves setVideoId empty when the renderer omits it`() = runTest {
        val transport = FakeTransport().apply {
            responder = { _, _ -> SyncReply.Http(200, fixture("playlist_first_page.json")) }
        }

        val page = assertOk(backend(transport).pullPlaylist("PLxyz"))

        // v2 has no setVideoId: removal is impossible and must not be guessed.
        assertEquals("", page.items.single { it.videoId == "v2" }.setVideoId)
    }

    // ----- accountChannelId -------------------------------------------------

    @Test
    fun `accountChannelId reads the id from the account menu`() = runTest {
        val transport = FakeTransport().apply {
            responder = { _, _ -> SyncReply.Http(200, fixture("account_menu.json")) }
        }

        val id = assertOk(backend(transport).accountChannelId())

        assertEquals("account/account_menu", transport.requests.single().path)
        assertTrue(transport.requests.single().extra.isEmpty())
        assertEquals("UCAAAABBBBCCCCDDDDEEEEFF", id)
    }

    @Test
    fun `accountChannelId is ok with an empty id when the menu has none`() = runTest {
        val transport = FakeTransport().apply {
            responder = { _, _ -> SyncReply.Http(200, """{"actions": []}""") }
        }

        // The engine only checks Ok; accountKey is derived separately, so a menu
        // without a channel id must not fail the call.
        assertEquals("", assertOk(backend(transport).accountChannelId()))
    }

    // ----- error table ------------------------------------------------------

    @Test
    fun `a 401 is an auth wall`() = runTest {
        assertAuth(accountChannelIdWith(SyncReply.Http(401, "")))
    }

    @Test
    fun `a 403 is an auth wall`() = runTest {
        assertAuth(accountChannelIdWith(SyncReply.Http(403, "forbidden")))
    }

    @Test
    fun `a 429 and a 503 are retryable`() = runTest {
        assertRetryable(accountChannelIdWith(SyncReply.Http(429, "")))
        assertRetryable(accountChannelIdWith(SyncReply.Http(503, "unavailable")))
    }

    @Test
    fun `a 400 is terminal`() = runTest {
        assertTerminal(accountChannelIdWith(SyncReply.Http(400, "bad request")))
    }

    @Test
    fun `an unreachable transport is retryable`() = runTest {
        assertRetryable(accountChannelIdWith(SyncReply.Unreachable("connection reset")))
    }

    @Test
    fun `a 2xx body with LOGIN_REQUIRED is an auth wall`() = runTest {
        assertAuth(accountChannelIdWith(SyncReply.Http(200, """{"error":{"status":"LOGIN_REQUIRED"}}""")))
    }

    @Test
    fun `a 2xx body that is not JSON is terminal`() = runTest {
        assertTerminal(accountChannelIdWith(SyncReply.Http(200, "not json at all")))
    }

    @Test
    fun `a 2xx body with a top-level error is terminal`() = runTest {
        assertTerminal(accountChannelIdWith(SyncReply.Http(200, """{"error":{"code":400}}""")))
    }

    @Test
    fun `the sign-in wording is a wall only inside the error envelope`() = runTest {
        // The same phrase in plain data (a title) must not abort a valid sync ...
        val titleOnly = SyncReply.Http(200, """{"title":"Sign in to confirm you are not a bot"}""")
        assertEquals("", assertOk(accountChannelIdWith(titleOnly)))
        // ... while inside the error envelope it still is a dead session.
        val wall = SyncReply.Http(200, """{"error":{"message":"Sign in to confirm you're not a bot"}}""")
        assertAuth(accountChannelIdWith(wall))
    }

    private suspend fun accountChannelIdWith(reply: SyncReply): OpResult<String> {
        val transport = FakeTransport().apply { responder = { _, _ -> reply } }
        return backend(transport).accountChannelId()
    }

    // ----- write request shapes ---------------------------------------------

    @Test
    fun `subscribe posts the channel ids to subscription slash subscribe`() = runTest {
        val transport = FakeTransport()

        assertOk(backend(transport).subscribe(listOf("UCone0000000000000000000")))

        assertEquals("subscription/subscribe", transport.requests.single().path)
        assertEquals(
            listOf("UCone0000000000000000000"),
            transport.requests.single().extra.getValue("channelIds").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `unsubscribe posts the channel ids to subscription slash unsubscribe`() = runTest {
        val transport = FakeTransport()

        assertOk(backend(transport).unsubscribe(listOf("UCtwo0000000000000000000")))

        assertEquals("subscription/unsubscribe", transport.requests.single().path)
        assertEquals(
            listOf("UCtwo0000000000000000000"),
            transport.requests.single().extra.getValue("channelIds").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `addToPlaylist posts one ACTION_ADD_VIDEO per id`() = runTest {
        val transport = FakeTransport()

        assertOk(backend(transport).addToPlaylist("PLxyz", listOf("v1", "v2")))

        val request = transport.requests.single()
        assertEquals("playlist/edit", request.path)
        assertEquals("PLxyz", request.extra.string("playlistId"))
        val actions = request.extra.getValue("actions").jsonArray.map { it.jsonObject }
        assertEquals(2, actions.size)
        assertEquals("ACTION_ADD_VIDEO", actions[0].string("action"))
        assertEquals("v1", actions[0].string("addedVideoId"))
        assertEquals("v2", actions[1].string("addedVideoId"))
    }

    @Test
    fun `removeFromPlaylist carries the setVideoId per removal`() = runTest {
        val transport = FakeTransport()

        assertOk(
            backend(transport).removeFromPlaylist(
                "PLxyz",
                listOf(RemotePlaylistItem(videoId = "v9", setVideoId = "set-9")),
            ),
        )

        val action = transport.requests.single().extra.getValue("actions").jsonArray.single().jsonObject
        assertEquals("ACTION_REMOVE_VIDEO", action.string("action"))
        assertEquals("v9", action.string("removedVideoId"))
        assertEquals("set-9", action.string("setVideoId"))
    }

    @Test
    fun `createPlaylist posts the privacy and returns the id`() = runTest {
        val transport = FakeTransport().apply {
            responder = { _, _ -> SyncReply.Http(200, """{"playlistId":"PLnew00000000000000000000"}""") }
        }

        val id = assertOk(backend(transport).createPlaylist("Favourites"))

        val request = transport.requests.single()
        assertEquals("playlist/create", request.path)
        assertEquals("Favourites", request.extra.string("title"))
        assertEquals("PRIVATE", request.extra.string("privacyStatus"))
        assertEquals("PLnew00000000000000000000", id)
    }

    @Test
    fun `createPlaylist finds a nested playlist id`() = runTest {
        val transport = FakeTransport().apply {
            responder = { _, _ ->
                SyncReply.Http(200, """{"playlistEditEndpoint":{"playlistId":"PLnested0000000000000000"}}""")
            }
        }

        assertEquals(
            "PLnested0000000000000000",
            assertOk(backend(transport).createPlaylist("Favourites")),
        )
    }

    @Test
    fun `createPlaylist without an id is terminal`() = runTest {
        val transport = FakeTransport().apply {
            responder = { _, _ -> SyncReply.Http(200, """{"status":"ok"}""") }
        }

        assertTerminal(backend(transport).createPlaylist("Favourites"))
    }

    @Test
    fun `renamePlaylist posts the rename action`() = runTest {
        val transport = FakeTransport()

        assertOk(backend(transport).renamePlaylist("PLxyz", "New name"))

        val request = transport.requests.single()
        assertEquals("playlist/edit", request.path)
        val action = request.extra.getValue("actions").jsonArray.single().jsonObject
        assertEquals("ACTION_SET_PLAYLIST_NAME", action.string("action"))
        assertEquals("New name", action.string("playlistName"))
    }

    @Test
    fun `setPlaylistPrivacy posts the privacy action`() = runTest {
        val transport = FakeTransport()

        assertOk(backend(transport).setPlaylistPrivacy("PLxyz", PlaylistPrivacy.UNLISTED))

        val action = transport.requests.single().extra.getValue("actions").jsonArray.single().jsonObject
        assertEquals("ACTION_SET_PLAYLIST_PRIVACY", action.string("action"))
        assertEquals("UNLISTED", action.string("privacyStatus"))
    }

    @Test
    fun `deletePlaylist posts playlist slash delete`() = runTest {
        val transport = FakeTransport()

        assertOk(backend(transport).deletePlaylist("PLxyz"))

        val request = transport.requests.single()
        assertEquals("playlist/delete", request.path)
        assertEquals("PLxyz", request.extra.string("playlistId"))
    }
}
