package dev.wetube.app.data.local

import dev.wetube.app.core.DEFAULT_INVIDIOUS_HOST
import dev.wetube.app.core.DEFAULT_PIPED_HOST
import dev.wetube.app.core.sanitizeBackendHost
import dev.wetube.app.data.backend.BackendType
import dev.wetube.app.data.prefs.AppSettings
import dev.wetube.app.data.prefs.SettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Largest backup a restore will accept. Real files are a few kilobytes, so this
 * only ever rejects a file that could not have been produced by the app and
 * would otherwise be read into memory wholesale.
 */
internal const val MAX_BACKUP_BYTES: Int = 5 * 1024 * 1024

/**
 * Upper bound on the SponsorBlock categories a backup may restore. The live set
 * is a handful of names; the cap stops a crafted file growing it without bound.
 */
private const val MAX_SPONSOR_CATEGORIES = 32

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
    val version: Int = VERSION,
    val createdAt: Long = 0L,
    /** App preferences worth carrying to a new phone. */
    val settings: BackupSettings? = null,
    val library: LibrarySnapshot = LibrarySnapshot(),
) {
    companion object {
        const val FORMAT = "wetube-backup"

        /**
         * Schema version written by this build. Readers accept every value in
         * `1..VERSION`; a file that omits the field deserialises to this value
         * and is therefore still accepted.
         *
         * v3 adds the account-sync opt-ins ([BackupSettings.syncEnabled] and
         * [BackupSettings.mirrorRemovals]). A v2 file simply lacks both fields,
         * so they decode to their declared defaults and restore stays
         * deterministic: the outcome depends only on the file, never on what the
         * live store happened to hold.
         *
         * v4 adds the device-local collections ([LibrarySnapshot.watchLater],
         * [LibrarySnapshot.queue] and [LibrarySnapshot.playlists]). A v1-v3 file
         * lacks all three, so they decode to empty and restoring an older backup
         * leaves Watch Later, the queue and local playlists empty rather than
         * merging them with what the device already holds.
         */
        const val VERSION = 4
    }
}

@Serializable
data class BackupSettings(
    val backend: BackendType = BackendType.INNERTUBE,
    val invidiousHost: String = DEFAULT_INVIDIOUS_HOST,
    val pipedHost: String = DEFAULT_PIPED_HOST,
    val proxyStreams: Boolean = true,
    val sponsorCategories: Set<String> = setOf("sponsor", "selfpromo", "interaction"),
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
    /**
     * Carried so a phone that is set up from a backup keeps the user's sync
     * choice. Default false matches [AppSettings.syncEnabled]: an older file
     * (v2 and below) has no such field, so restoring one leaves sync off rather
     * than silently opting the new device in.
     */
    val syncEnabled: Boolean = false,
    val mirrorRemovals: Boolean = false,
)

/** The portable subset of [AppSettings] that a backup carries. */
internal fun AppSettings.toBackupSettings(): BackupSettings = BackupSettings(
    backend = backend,
    invidiousHost = invidiousHost,
    pipedHost = pipedHost,
    proxyStreams = proxyStreams,
    sponsorCategories = sponsorCategories,
    region = region,
    showMature = showMature,
    playbackSpeed = playbackSpeed,
    audioOnly = audioOnly,
    videoQuality = videoQuality,
    backgroundPlayback = backgroundPlayback,
    captionsEnabled = captionsEnabled,
    captionLanguage = captionLanguage,
    captionTranslateTo = captionTranslateTo,
    sponsorBlockEnabled = sponsorBlockEnabled,
    showDislikes = showDislikes,
    syncEnabled = syncEnabled,
    mirrorRemovals = mirrorRemovals,
)

/**
 * Applies every portable preference from a backup onto a live snapshot. A
 * field an older file omits decodes to a [BackupSettings] default, so the
 * result depends only on the file — restoring is deterministic.
 *
 * A backup arrives from another device (and, with Drive, from anywhere), so its
 * free-text host fields are re-validated here rather than trusted: an invalid
 * value falls back to the built-in default instead of being concatenated into
 * request URLs.
 */
internal fun BackupSettings.restoreOnto(current: AppSettings): AppSettings = current.copy(
    backend = backend,
    invidiousHost = sanitizeBackendHost(invidiousHost) ?: DEFAULT_INVIDIOUS_HOST,
    pipedHost = sanitizeBackendHost(pipedHost) ?: DEFAULT_PIPED_HOST,
    proxyStreams = proxyStreams,
    sponsorCategories = sponsorCategories.take(MAX_SPONSOR_CATEGORIES).toSet(),
    region = region,
    showMature = showMature,
    playbackSpeed = playbackSpeed.coerceIn(0.25f, 4f),
    audioOnly = audioOnly,
    videoQuality = videoQuality,
    backgroundPlayback = backgroundPlayback,
    captionsEnabled = captionsEnabled,
    captionLanguage = captionLanguage,
    captionTranslateTo = captionTranslateTo,
    sponsorBlockEnabled = sponsorBlockEnabled,
    showDislikes = showDislikes,
    syncEnabled = syncEnabled,
    mirrorRemovals = mirrorRemovals,
)

/**
 * Parses and validates a backup. Unknown formats are rejected, and so is a
 * schema written by a newer build (we cannot know what it added); missing
 * fields simply take their declared defaults.
 */
internal fun Json.parseBackup(text: String): BackupFile {
    val file = runCatching { decodeFromString(BackupFile.serializer(), text) }
        .getOrElse { throw IllegalArgumentException("That file is not a WeTube backup.") }
    require(file.format == BackupFile.FORMAT) { "That file is not a WeTube backup." }
    require(file.version in 1..BackupFile.VERSION) {
        if (file.version > BackupFile.VERSION) {
            "That backup was made by a newer version of WeTube."
        } else {
            "That file is not a WeTube backup."
        }
    }
    return file
}

class BackupStore(
    private val library: LibraryStore,
    private val settings: SettingsStore,
    private val json: Json,
) {

    suspend fun export(): String {
        val current = settings.settings.first()
        val file = BackupFile(
            createdAt = System.currentTimeMillis(),
            settings = current.toBackupSettings(),
            library = library.snapshot(),
        )
        return json.encodeToString(BackupFile.serializer(), file)
    }

    /** Restores a backup and returns what was written, for a status message. */
    suspend fun restore(text: String): LibrarySnapshot {
        // Guard before parsing: decoding a multi-megabyte (or worse) string into
        // a full object graph is the expensive step, and a real backup is
        // thousands of times smaller than this.
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BACKUP_BYTES) {
            "That backup is too large to import."
        }
        val file = json.parseBackup(text)
        library.restore(file.library)
        file.settings?.let { backup ->
            settings.update { current -> backup.restoreOnto(current) }
        }
        return file.library
    }
}
