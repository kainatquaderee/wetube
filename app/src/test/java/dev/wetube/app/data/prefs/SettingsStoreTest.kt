package dev.wetube.app.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.wetube.app.core.DEFAULT_INVIDIOUS_HOST
import dev.wetube.app.data.backend.BackendType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Exercises the real DataStore engine, not a fake, because the bug this guards
 * against (two writers snapshotting the same value and clobbering each other)
 * only exists in the transaction boundary.
 */
class SettingsStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: SettingsStore

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val file = File(folder.newFolder("settings"), "settings.preferences_pb")
        dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        store = SettingsStore(dataStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `empty store exposes the documented defaults`() = runBlocking {
        val settings = store.settings.first()

        assertEquals(BackendType.INNERTUBE, settings.backend)
        assertEquals(DEFAULT_INVIDIOUS_HOST, settings.invidiousHost)
        assertEquals("https://pipedapi.kavin.rocks", settings.pipedHost)
        assertEquals("US", settings.region)
        assertTrue(settings.proxyStreams)
        assertFalse(settings.audioOnly)
        assertEquals(0, settings.videoQuality)
        assertEquals(1f, settings.playbackSpeed, 0f)
        assertEquals(setOf("sponsor", "selfpromo", "interaction"), settings.sponsorCategories)
        assertFalse(settings.syncEnabled)
        assertFalse(settings.mirrorRemovals)
    }

    @Test
    fun `syncEnabled defaults to false and round-trips through update`() = runBlocking {
        assertFalse(store.settings.first().syncEnabled)

        store.update { it.copy(syncEnabled = true) }

        assertTrue(store.settings.first().syncEnabled)
    }

    @Test
    fun `fifty concurrent updates all survive`() = runBlocking {
        val writes = 50
        val jobs = (0 until writes).map { index ->
            launch(Dispatchers.Default) {
                store.update { current ->
                    current.copy(sponsorCategories = current.sponsorCategories + "c$index")
                }
            }
        }
        jobs.joinAll()

        val categories = store.settings.first().sponsorCategories
        val expected = (0 until writes).mapTo(mutableSetOf()) { "c$it" } +
            setOf("sponsor", "selfpromo", "interaction")
        assertEquals(expected, categories)
        assertEquals(writes + 3, categories.size)
    }

    @Test
    fun `update round-trips every persisted field`() = runBlocking {
        store.update {
            it.copy(
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
        }

        val settings = store.settings.first()
        assertEquals(BackendType.PIPED, settings.backend)
        assertEquals("https://iv.example", settings.invidiousHost)
        assertEquals("https://piped.example", settings.pipedHost)
        assertEquals("DE", settings.region)
        assertFalse(settings.proxyStreams)
        assertFalse(settings.sponsorBlockEnabled)
        assertEquals(setOf("sponsor", "intro"), settings.sponsorCategories)
        assertFalse(settings.showDislikes)
        assertEquals(1.75f, settings.playbackSpeed, 0f)
        assertTrue(settings.audioOnly)
        assertEquals(720, settings.videoQuality)
        assertFalse(settings.backgroundPlayback)
        assertTrue(settings.captionsEnabled)
        assertEquals("de", settings.captionLanguage)
        assertEquals("en", settings.captionTranslateTo)
        assertTrue(settings.showMature)
    }

    @Test
    fun `concurrent updates to different keys do not clobber each other`() = runBlocking {
        // Different keys: a read-modify-write that rebuilt the prefs from
        // defaults would let one writer erase another's field.
        val jobs = listOf(
            launch(Dispatchers.Default) { repeat(25) { i -> store.update { it.copy(region = "R$i") } } },
            launch(Dispatchers.Default) { repeat(25) { i -> store.update { it.copy(captionLanguage = "L$i") } } },
            launch(Dispatchers.Default) { repeat(25) { i -> store.update { it.copy(videoQuality = i) } } },
        )
        jobs.joinAll()

        val settings = store.settings.first()
        assertEquals("R24", settings.region)
        assertEquals("L24", settings.captionLanguage)
        assertEquals(24, settings.videoQuality)
        // Untouched preferences keep their defaults rather than being reset.
        assertTrue(settings.proxyStreams)
        assertEquals(BackendType.INNERTUBE, settings.backend)
    }

    @Test
    fun `an update that empties the sponsor category set round-trips as empty`() = runBlocking {
        store.update { it.copy(sponsorCategories = emptySet()) }

        assertTrue(store.settings.first().sponsorCategories.isEmpty())
    }

    @Test
    fun `an unrecognised stored backend falls back to the default`() = runBlocking {
        // A value written by a build that offered a backend this one removed.
        dataStore.edit { it[stringPreferencesKey("backend")] = "GONE_IN_A_LATER_BUILD" }

        assertEquals(BackendType.INNERTUBE, store.settings.first().backend)
    }

    @Test
    fun `a malformed stored speed falls back to 1x`() = runBlocking {
        dataStore.edit { it[stringPreferencesKey("playback_speed")] = "fast" }

        assertEquals(1f, store.settings.first().playbackSpeed, 0f)
    }
}
