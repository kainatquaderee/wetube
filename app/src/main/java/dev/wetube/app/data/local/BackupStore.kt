package dev.wetube.app.data.local

import dev.wetube.app.data.prefs.SettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The backup file the user can push to (and pull from) any storage provider
 * the system file picker offers — including Google Drive.
 *
 * We deliberately do not talk to the Drive API ourselves: the file never
 * leaves the device unless the user picks a destination, no OAuth token is
 * requested from Google, and the same JSON works with Drive, a USB stick or
 * another phone. Accounts and cookies are never included.
 */
@Serializable
data class BackupFile(
    val format: String = FORMAT,
    val version: Int = 1,
    val createdAt: Long = 0L,
    /** App preferences worth carrying to a new phone. */
    val settings: BackupSettings? = null,
    val library: LibrarySnapshot = LibrarySnapshot(),
) {
    companion object {
        const val FORMAT = "wetube-backup"
    }
}

@Serializable
data class BackupSettings(
    val region: String = "US",
    val showMature: Boolean = false,
    val playbackSpeed: Float = 1f,
    val audioOnly: Boolean = false,
    val videoQuality: Int = 0,
    val backgroundPlayback: Boolean = true,
    val captionsEnabled: Boolean = false,
    val captionLanguage: String = "",
    val captionTranslateTo: String = "",
    val sponsorBlockEnabled: Boolean = true,
    val showDislikes: Boolean = true,
)

class BackupStore(
    private val library: LibraryStore,
    private val settings: SettingsStore,
    private val json: Json,
) {

    suspend fun export(): String {
        val current = settings.settings.first()
        val file = BackupFile(
            createdAt = System.currentTimeMillis(),
            settings = BackupSettings(
                region = current.region,
                showMature = current.showMature,
                playbackSpeed = current.playbackSpeed,
                audioOnly = current.audioOnly,
                videoQuality = current.videoQuality,
                backgroundPlayback = current.backgroundPlayback,
                captionsEnabled = current.captionsEnabled,
                captionLanguage = current.captionLanguage,
                captionTranslateTo = current.captionTranslateTo,
                sponsorBlockEnabled = current.sponsorBlockEnabled,
                showDislikes = current.showDislikes,
            ),
            library = library.snapshot(),
        )
        return json.encodeToString(BackupFile.serializer(), file)
    }

    /** Restores a backup and returns what was written, for a status message. */
    suspend fun restore(text: String): LibrarySnapshot {
        val file = runCatching { json.decodeFromString(BackupFile.serializer(), text) }
            .getOrElse { throw IllegalArgumentException("That file is not a WeTube backup.") }
        require(file.format == BackupFile.FORMAT) { "That file is not a WeTube backup." }

        library.restore(file.library)
        file.settings?.let { backup ->
            settings.update { current ->
                current.copy(
                    region = backup.region,
                    showMature = backup.showMature,
                    playbackSpeed = backup.playbackSpeed.coerceIn(0.25f, 4f),
                    audioOnly = backup.audioOnly,
                    videoQuality = backup.videoQuality,
                    backgroundPlayback = backup.backgroundPlayback,
                    captionsEnabled = backup.captionsEnabled,
                    captionLanguage = backup.captionLanguage,
                    captionTranslateTo = backup.captionTranslateTo,
                    sponsorBlockEnabled = backup.sponsorBlockEnabled,
                    showDislikes = backup.showDislikes,
                )
            }
        }
        return file.library
    }
}
