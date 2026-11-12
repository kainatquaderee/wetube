package dev.wetube.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.wetube.app.data.backend.BackendType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "ktube_settings")

data class AppSettings(
    val backend: BackendType = BackendType.INNERTUBE,
    val invidiousHost: String = "https://invidious.materialio.us",
    val pipedHost: String = "https://pipedapi.kavin.rocks",
    val region: String = "US",
    val proxyStreams: Boolean = true,
    val sponsorBlockEnabled: Boolean = true,
    val sponsorCategories: Set<String> = setOf("sponsor", "selfpromo", "interaction"),
    val showDislikes: Boolean = true,
    val playbackSpeed: Float = 1f,
    val audioOnly: Boolean = false,
    /** Keep audio alive when the app goes to the background. */
    val backgroundPlayback: Boolean = true,
    /** Subtitle preferences, shared by the player and Shorts. */
    val captionsEnabled: Boolean = false,
    val captionLanguage: String = "",
    val captionTranslateTo: String = "",
    /** Age-gated videos are hidden from feeds while this is off. */
    val showMature: Boolean = false,
)

class SettingsStore(private val context: Context) {

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
        val backgroundPlayback = booleanPreferencesKey("background_playback")
        val captions = booleanPreferencesKey("captions")
        val captionLanguage = stringPreferencesKey("caption_language")
        val captionTranslate = stringPreferencesKey("caption_translate")
        val showMature = booleanPreferencesKey("show_mature")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            backend = p[Keys.backend]?.let { runCatching { BackendType.valueOf(it) }.getOrNull() } ?: BackendType.INNERTUBE,
            invidiousHost = p[Keys.invidious] ?: "https://invidious.materialio.us",
            pipedHost = p[Keys.piped] ?: "https://pipedapi.kavin.rocks",
            region = p[Keys.region] ?: "US",
            proxyStreams = p[Keys.proxy] ?: true,
            sponsorBlockEnabled = p[Keys.sponsor] ?: true,
            sponsorCategories = p[Keys.sponsorCats]?.split(',')?.filter { it.isNotBlank() }?.toSet()
                ?: setOf("sponsor", "selfpromo", "interaction"),
            showDislikes = p[Keys.dislikes] ?: true,
            playbackSpeed = p[Keys.speed]?.toFloatOrNull() ?: 1f,
            audioOnly = p[Keys.audioOnly] ?: false,
            backgroundPlayback = p[Keys.backgroundPlayback] ?: true,
            captionsEnabled = p[Keys.captions] ?: false,
            captionLanguage = p[Keys.captionLanguage].orEmpty(),
            captionTranslateTo = p[Keys.captionTranslate].orEmpty(),
            showMature = p[Keys.showMature] ?: false,
        )
    }

    suspend fun update(block: (AppSettings) -> AppSettings) {
        val current = settings.first()
        val next = block(current)
        context.dataStore.edit { p ->
            p[Keys.backend] = next.backend.name
            p[Keys.invidious] = next.invidiousHost
            p[Keys.piped] = next.pipedHost
            p[Keys.region] = next.region
            p[Keys.proxy] = next.proxyStreams
            p[Keys.sponsor] = next.sponsorBlockEnabled
            p[Keys.sponsorCats] = next.sponsorCategories.joinToString(",")
            p[Keys.dislikes] = next.showDislikes
            p[Keys.speed] = next.playbackSpeed.toString()
            p[Keys.audioOnly] = next.audioOnly
            p[Keys.backgroundPlayback] = next.backgroundPlayback
            p[Keys.captions] = next.captionsEnabled
            p[Keys.captionLanguage] = next.captionLanguage
            p[Keys.captionTranslate] = next.captionTranslateTo
            p[Keys.showMature] = next.showMature
        }
    }
}
