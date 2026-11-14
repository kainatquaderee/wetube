package dev.wetube.app.data.local

import dev.wetube.app.core.DEFAULT_INVIDIOUS_HOST
import dev.wetube.app.data.backend.BackendType
import dev.wetube.app.data.model.VideoItem
import dev.wetube.app.data.prefs.AppSettings
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The backup mapping is pure, so it can be tested without a Context or a file.
 * The JSON here mirrors what [dev.wetube.app.di.ServiceLocator] configures.
 */
class BackupStoreTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    @Test
    fun `settings survive a backup round trip including the new fields`() {
        val original = AppSettings(
            backend = BackendType.PIPED,
            invidiousHost = "https://iv.example",
            pipedHost = "https://piped.example",
            region = "DE",
            proxyStreams = false,
            sponsorBlockEnabled = false,
            sponsorCategories = setOf("sponsor", "intro"),
            showDislikes = false,
            playbackSpeed = 1.75f,
            audioOnly = true,
            videoQuality = 720,
            backgroundPlayback = false,
            captionsEnabled = true,
            captionLanguage = "de",
            captionTranslateTo = "en",
            showMature = true,
        )

        val restored = original.toBackupSettings().restoreOnto(AppSettings())

        assertEquals(original, restored)
    }

    @Test
    fun `sync settings survive a backup round trip`() {
        val original = AppSettings(syncEnabled = true, mirrorRemovals = true)

        val restored = original.toBackupSettings().restoreOnto(AppSettings())

        assertTrue(restored.syncEnabled)
        assertTrue(restored.mirrorRemovals)
    }

    @Test
    fun `a version 2 backup without the sync fields restores their defaults`() {
        // A v2 file predates syncEnabled and mirrorRemovals, so it simply lacks
        // them. Restoring must leave them false even when the live store had
        // them on, because the outcome is defined by the file alone.
        val text = """{"format":"${BackupFile.FORMAT}","version":2,"settings":{"region":"FR"}}"""

        val decoded = json.parseBackup(text)
        val restored = decoded.settings!!.restoreOnto(
            AppSettings(syncEnabled = true, mirrorRemovals = true),
        )

        assertEquals("FR", restored.region)
        assertFalse(restored.syncEnabled)
        assertFalse(restored.mirrorRemovals)
    }

    @Test
    fun `restore uses backup defaults for fields an older file omits`() {
        val current = AppSettings(region = "JP", showMature = true, videoQuality = 1080)
        val restored = BackupSettings(region = "DE").restoreOnto(current)

        assertEquals("DE", restored.region)
        // Not present in the file, so the backup's declared default applies.
        assertFalse(restored.showMature)
        assertEquals(0, restored.videoQuality)
    }

    @Test
    fun `restore clamps an out-of-range playback speed`() {
        assertEquals(4f, BackupSettings(playbackSpeed = 9f).restoreOnto(AppSettings()).playbackSpeed, 0f)
        assertEquals(0.25f, BackupSettings(playbackSpeed = 0f).restoreOnto(AppSettings()).playbackSpeed, 0f)
    }

    @Test
    fun `a full backup parses back with the library and settings intact`() {
        val video = VideoItem(
            id = "dQw4w9WgXcQ",
            title = "Never Gonna Give You Up",
            channelName = "Rick Astley",
            channelId = "UCuAXFkgsw1L7xaCfnd5JJOw",
        )
        val file = BackupFile(
            createdAt = 1_700_000_000_000L,
            settings = AppSettings(
                backend = BackendType.INVIDIOUS,
                region = "GB",
                sponsorCategories = setOf("sponsor"),
            ).toBackupSettings(),
            library = LibrarySnapshot(
                history = listOf(video),
                favourites = listOf(video.copy(title = "Favourite")),
                resume = listOf(ResumeEntry(video, positionMs = 42_000, updatedAt = 1L)),
                subscriptions = listOf(video.copy(id = "UCuAXFkgsw1L7xaCfnd5JJOw")),
            ),
        )

        val decoded = json.parseBackup(json.encodeToString(BackupFile.serializer(), file))

        assertEquals(BackupFile.FORMAT, decoded.format)
        assertEquals(BackupFile.VERSION, decoded.version)
        assertEquals(BackendType.INVIDIOUS, decoded.settings?.backend)
        assertEquals("GB", decoded.settings?.region)
        assertEquals(setOf("sponsor"), decoded.settings?.sponsorCategories)
        assertEquals(listOf(video), decoded.library.history)
        assertEquals(listOf(video.copy(title = "Favourite")), decoded.library.favourites)
        assertEquals(42_000, decoded.library.resume.single().positionMs)
        assertEquals("UCuAXFkgsw1L7xaCfnd5JJOw", decoded.library.subscriptions.single().id)
    }

    @Test
    fun `a backup without a version field is accepted`() {
        val text = """{"format":"${BackupFile.FORMAT}"}"""

        val decoded = json.parseBackup(text)

        assertEquals(BackupFile.VERSION, decoded.version)
    }

    @Test
    fun `a version 1 backup is still accepted`() {
        val text = """{"format":"${BackupFile.FORMAT}","version":1,"library":{}}"""

        val decoded = json.parseBackup(text)

        assertEquals(1, decoded.version)
        assertTrue(decoded.library.history.isEmpty())
    }

    @Test
    fun `missing settings and library fall back to empty values`() {
        val text = """{"format":"${BackupFile.FORMAT}","version":2}"""

        val decoded = json.parseBackup(text)

        assertNull(decoded.settings)
        assertTrue(decoded.library.favourites.isEmpty())
        assertTrue(decoded.library.resume.isEmpty())
    }

    @Test
    fun `a version 3 backup without the local collections parses them as empty`() {
        // v3 predates Watch Later, the queue and local playlists, so it simply
        // lacks them; they must decode to empty rather than fail the file.
        val text = """
            {
              "format": "${BackupFile.FORMAT}",
              "version": 3,
              "library": {
                "history": [{"id": "dQw4w9WgXcQ", "title": "A song", "channelName": "Rick"}]
              }
            }
        """.trimIndent()

        val decoded = json.parseBackup(text)

        assertEquals(1, decoded.library.history.size)
        assertTrue(decoded.library.watchLater.isEmpty())
        assertTrue(decoded.library.queue.isEmpty())
        assertTrue(decoded.library.playlists.isEmpty())
    }

    @Test
    fun `a version 4 backup round-trips the local collections`() {
        val video = VideoItem(id = "dQw4w9WgXcQ", title = "A song", channelName = "Rick")
        val playlist = LocalPlaylist(
            id = "local:abc",
            name = "Mix",
            videoIds = listOf(video.id),
            createdAt = 5L,
            updatedAt = 6L,
        )
        val file = BackupFile(
            library = LibrarySnapshot(
                watchLater = listOf(video),
                queue = listOf(video.copy(id = "other")),
                playlists = listOf(playlist),
            ),
        )

        val decoded = json.parseBackup(json.encodeToString(BackupFile.serializer(), file))

        assertEquals(4, decoded.version)
        assertEquals(BackupFile.VERSION, decoded.version)
        assertEquals(listOf(video), decoded.library.watchLater)
        assertEquals(listOf("other"), decoded.library.queue.map { it.id })
        assertEquals(listOf(playlist), decoded.library.playlists)
    }

    @Test
    fun `unknown fields from a newer minor schema are ignored`() {
        val text = """
            {
              "format": "${BackupFile.FORMAT}",
              "version": 2,
              "futureField": {"nested": true},
              "settings": {"region": "CA", "anotherFutureField": 3}
            }
        """.trimIndent()

        val decoded = json.parseBackup(text)

        assertEquals("CA", decoded.settings?.region)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a newer schema is rejected`() {
        json.parseBackup("""{"format":"${BackupFile.FORMAT}","version":${BackupFile.VERSION + 1}}""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a foreign file is rejected`() {
        json.parseBackup("""{"format":"other-app","version":2}""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `malformed JSON is rejected`() {
        json.parseBackup("not json at all")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a zero or negative version is rejected`() {
        json.parseBackup("""{"format":"${BackupFile.FORMAT}","version":0}""")
    }

    @Test
    fun `a version 1 settings block without the new fields restores documented defaults`() {
        // A 0.1.6-era backup: it predates backend, the hosts, proxyStreams and
        // showDislikes, all of which 0.1.7 added to the portable shape.
        val text = """{"format":"${BackupFile.FORMAT}","version":1,"settings":{"region":"FR"}}"""

        val decoded = json.parseBackup(text)
        val restored = decoded.settings!!.restoreOnto(
            AppSettings(
                backend = BackendType.PIPED,
                proxyStreams = false,
                showDislikes = false,
                sponsorCategories = setOf("intro"),
            ),
        )

        assertEquals("FR", restored.region)
        // Fields the old file never carried fall back to the backup defaults,
        // not to whatever the live store happened to hold.
        assertEquals(BackendType.INNERTUBE, restored.backend)
        assertTrue(restored.proxyStreams)
        assertTrue(restored.showDislikes)
        assertEquals(DEFAULT_INVIDIOUS_HOST, restored.invidiousHost)
        assertEquals("https://pipedapi.kavin.rocks", restored.pipedHost)
        assertEquals(setOf("sponsor", "selfpromo", "interaction"), restored.sponsorCategories)
    }

    @Test
    fun `the new settings fields survive a JSON encode and decode`() {
        val settings = AppSettings(
            backend = BackendType.INVIDIOUS,
            invidiousHost = "https://iv.example",
            pipedHost = "https://piped.example",
            proxyStreams = false,
            showDislikes = false,
            sponsorCategories = setOf("intro", "outro"),
        ).toBackupSettings()
        val file = BackupFile(settings = settings)

        val decoded = json.parseBackup(json.encodeToString(BackupFile.serializer(), file))

        assertEquals(BackendType.INVIDIOUS, decoded.settings?.backend)
        assertEquals("https://iv.example", decoded.settings?.invidiousHost)
        assertEquals("https://piped.example", decoded.settings?.pipedHost)
        assertFalse(decoded.settings?.proxyStreams ?: true)
        assertFalse(decoded.settings?.showDislikes ?: true)
        assertEquals(setOf("intro", "outro"), decoded.settings?.sponsorCategories)
    }
}
