package dev.wetube.app.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.wetube.app.core.DEFAULT_INVIDIOUS_HOST
import dev.wetube.app.core.DEFAULT_PIPED_HOST
import dev.wetube.app.data.backend.BackendType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "ktube_settings")

data class AppSettings(
    val backend: BackendType = BackendType.INNERTUBE,
    val invidiousHost: String = DEFAULT_INVIDIOUS_HOST,
    val pipedHost: String = DEFAULT_PIPED_HOST,
    val region: String = "US",
    val proxyStreams: Boolean = true,
    val sponsorBlockEnabled: Boolean = true,
    val sponsorCategories: Set<String> = setOf("sponsor", "selfpromo", "interaction"),
    val showDislikes: Boolean = true,
    val playbackSpeed: Float = 1f,
    val audioOnly: Boolean = false,
    /** Video resolution to ask for: 0 = Auto, otherwise a height such as 720. */
    val videoQuality: Int = 0,
    /** Keep audio alive when the app goes to the background. */
    val backgroundPlayback: Boolean = true,
    /**
     * Drop the picture while the app is in the background.
     *
     * A visible video keeps fetching and decoding frames for a screen nobody can
     * see, so by default backgrounded playback steps down to the audio track and
     * restores the picture on return. This is transient: it never changes the
     * user's own [audioOnly] choice, and casting, live streams, saved files and
     * videos without a separate audio track are left alone.
     */
    val audioOnlyInBackground: Boolean = true,
    /**
     * How much storage finished downloads may occupy, in megabytes. `0` means
     * unlimited; otherwise the downloads the app keeps should total no more
     * than this, with the oldest finished ones removed first as new ones arrive.
     */
    val downloadLimitMb: Int = 0,
    /** Subtitle preferences, shared by the player and Shorts. */
    val captionsEnabled: Boolean = false,
    val captionLanguage: String = "",
    val captionTranslateTo: String = "",
    /** Age-gated videos are hidden from feeds while this is off. */
    val showMature: Boolean = false,
    /**
     * Master opt-in for account sync. Off by default: WeTube is fully usable
     * without an account, and pushing anything to a signed-in account is a
     * deliberate act. While this is false the entire sync UI is hidden and the
     * engine is never asked to run, so a user who never touches the switch
     * never has a request signed with their session cookies.
     */
    val syncEnabled: Boolean = false,
    /**
     * Whether a removal made on the account is mirrored into the local library
     * on sync. Off by default: a remote deletion is surfaced, not silently
     * applied, until the user opts in.
     */
    val mirrorRemovals: Boolean = false,
)

class SettingsStore internal constructor(
    private val dataStore: DataStore<Preferences>,
) {

    constructor(context: Context) : this(context.settingsDataStore)

    private object Keys {
        val backend = stringPreferencesKey("backend")
        val invidious = stringPreferencesKey("invidious_host")
        val piped = stringPreferencesKey("piped_host")
        val region = stringPreferencesKey("region")
        val proxy = booleanPreferencesKey("proxy_streams")
        val sponsor = booleanPreferencesKey("sponsorblock")
        val sponsorCats = stringPreferencesKey("sponsor_categories")
        val dislikes = booleanPreferencesKey("show_dislikes")
        val speed = stringPreferencesKey("playback_speed")
        val audioOnly = booleanPreferencesKey("audio_only")
        val videoQuality = stringPreferencesKey("video_quality")
        val backgroundPlayback = booleanPreferencesKey("background_playback")
        val audioOnlyInBackground = booleanPreferencesKey("audio_only_in_background")
        val downloadLimitMb = intPreferencesKey("download_limit_mb")
        val captions = booleanPreferencesKey("captions")
        val captionLanguage = stringPreferencesKey("caption_language")
        val captionTranslate = stringPreferencesKey("caption_translate")
        val showMature = booleanPreferencesKey("show_mature")
        val syncEnabled = booleanPreferencesKey("sync_enabled")
        val mirrorRemovals = booleanPreferencesKey("sync_mirror_removals")
    }

    val settings: Flow<AppSettings> = dataStore.data.map { it.toAppSettings() }

    /**
     * Applies [block] to the *current* snapshot inside the DataStore
     * transaction. Reading `settings.first()` outside `edit` let two overlapping
     * writers snapshot the same state and clobber each other; doing the read
     * here serialises them, so every distinct change survives.
     */
    suspend fun update(block: (AppSettings) -> AppSettings) {
        dataStore.edit { prefs ->
            val next = block(prefs.toAppSettings())
            prefs[Keys.backend] = next.backend.name
            prefs[Keys.invidious] = next.invidiousHost
            prefs[Keys.piped] = next.pipedHost
            prefs[Keys.region] = next.region
            prefs[Keys.proxy] = next.proxyStreams
            prefs[Keys.sponsor] = next.sponsorBlockEnabled
            prefs[Keys.sponsorCats] = next.sponsorCategories.joinToString(",")
            prefs[Keys.dislikes] = next.showDislikes
            prefs[Keys.speed] = next.playbackSpeed.toString()
            prefs[Keys.audioOnly] = next.audioOnly
            prefs[Keys.videoQuality] = next.videoQuality.toString()
            prefs[Keys.backgroundPlayback] = next.backgroundPlayback
            prefs[Keys.audioOnlyInBackground] = next.audioOnlyInBackground
            prefs[Keys.downloadLimitMb] = next.downloadLimitMb
            prefs[Keys.captions] = next.captionsEnabled
            prefs[Keys.captionLanguage] = next.captionLanguage
            prefs[Keys.captionTranslate] = next.captionTranslateTo
            prefs[Keys.showMature] = next.showMature
            prefs[Keys.syncEnabled] = next.syncEnabled
            prefs[Keys.mirrorRemovals] = next.mirrorRemovals
        }
    }

    /** Shared by the read flow and the transaction above, so both see the same defaults. */
    private fun Preferences.toAppSettings(): AppSettings = AppSettings(
        backend = this[Keys.backend]?.let { runCatching { BackendType.valueOf(it) }.getOrNull() }
            ?: BackendType.INNERTUBE,
        invidiousHost = this[Keys.invidious] ?: DEFAULT_INVIDIOUS_HOST,
        pipedHost = this[Keys.piped] ?: DEFAULT_PIPED_HOST,
        region = this[Keys.region] ?: "US",
        proxyStreams = this[Keys.proxy] ?: true,
        sponsorBlockEnabled = this[Keys.sponsor] ?: true,
        sponsorCategories = this[Keys.sponsorCats]?.split(',')?.filter { it.isNotBlank() }?.toSet()
            ?: setOf("sponsor", "selfpromo", "interaction"),
        showDislikes = this[Keys.dislikes] ?: true,
        playbackSpeed = this[Keys.speed]?.toFloatOrNull() ?: 1f,
        audioOnly = this[Keys.audioOnly] ?: false,
        videoQuality = this[Keys.videoQuality]?.toIntOrNull() ?: 0,
        backgroundPlayback = this[Keys.backgroundPlayback] ?: true,
        audioOnlyInBackground = this[Keys.audioOnlyInBackground] ?: true,
        downloadLimitMb = this[Keys.downloadLimitMb] ?: 0,
        captionsEnabled = this[Keys.captions] ?: false,
        captionLanguage = this[Keys.captionLanguage].orEmpty(),
        captionTranslateTo = this[Keys.captionTranslate].orEmpty(),
        showMature = this[Keys.showMature] ?: false,
        syncEnabled = this[Keys.syncEnabled] ?: false,
        mirrorRemovals = this[Keys.mirrorRemovals] ?: false,
    )
}
