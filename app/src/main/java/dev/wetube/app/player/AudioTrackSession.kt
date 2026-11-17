package dev.wetube.app.player

import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import dev.wetube.app.data.model.AudioStream
import java.util.Locale

/**
 * One row in the player's audio menu.
 *
 * A choice stands for a group of tracks rather than a single stream: a dub is
 * one `Tracks.Group`, and which stream inside it plays (the bitrate) is the
 * player's business.
 */
data class AudioTrackOption(
    /**
     * The group the track lives in; [AudioTrackSession.choose] pins it.
     *
     * `null` for a resolved dub, which is not a track of the stream in the
     * player at all: YouTube publishes every language as its own format, so
     * such a row is played by rebuilding the item instead of by pinning.
     */
    val group: Tracks.Group? = null,
    /** Index of the chosen track inside [group]. */
    val trackIndex: Int = 0,
    /** The track's ISO-639 language (`Format.language`), "" when it carries none. */
    val language: String,
    /** What the menu calls this track: its own label, else its language's name. */
    val label: String,
    /** True while this is the group the player is decoding. */
    val selected: Boolean,
    /**
     * Which of the media's untagged tracks this is, counted in media order, or
     * `null` when the language and label already name it.
     *
     * A manifest may carry dubs without saying anything about them, and then
     * every one of them looks like every other: same empty language, same
     * "Unknown language" row. The count is the only thing that tells them
     * apart; a reload keeps the media order, so it survives a rebuild exactly
     * as the pair does.
     */
    val untagged: Int? = null,
    /** The resolved dub this row plays, or `null` for a track of the stream. */
    val stream: AudioStream? = null,
) {

    /**
     * Identity of the choice across a reload of the same video.
     *
     * A quality change or a background step-down rebuilds the MediaItem and
     * every `TrackGroup` with it, so the group object cannot be remembered; the
     * language and label come from the manifest and survive the rebuild. The
     * menu dedupes on the same pair, which is why a language offered twice is
     * one row — but the pair says nothing at all about an untagged track, so
     * [untagged] joins the key for those. Without it the second untagged track
     * of a video shares the first one's row and cannot be chosen at all.
     */
    val key: String
        get() = stream?.let { "stream|${it.languageCode}|${it.displayName}" }
            ?: if (untagged == null) "$language|$label" else "$language|$label|$untagged"
}

/**
 * The audio-track state of whatever is currently in the player.
 *
 * ExoPlayer chooses an audio track by itself, and on a video that carries dubs
 * that choice is whatever the stream happens to list first — which is how a
 * video can start speaking Japanese to an English viewer. Two things fix that:
 * every media item is steered towards the device's own language, and the viewer
 * can pick a track by hand from the More menu.
 *
 * The hand pick belongs to one video. A quality change or a background
 * step-down rebuilds the same item and must keep it; the next video must not
 * inherit it. That is why the pick is remembered as an [AudioTrackOption.key]
 * and reconciled against the groups of every new track list, never as a
 * `TrackGroup`: a rebuilt item replaces those objects. Unlike [CaptionSession]
 * nothing is persisted — the choice is about these dubs, and a new video starts
 * from the device's language again.
 */
@OptIn(UnstableApi::class)
class AudioTrackSession {

    /** The audio tracks the menu can offer, in the order the media lists them. */
    var options by mutableStateOf<List<AudioTrackOption>>(emptyList())
        private set

    /** The track that is playing now, or `null` until the tracks are known. */
    var selected by mutableStateOf<AudioTrackOption?>(null)
        private set

    /** The video the [pick] belongs to, so the next one can drop it. */
    private var mediaId: String? = null

    /**
     * Every dub the resolution handed out for this video.
     *
     * More than one means the language is a property of the URL, not of the
     * stream, so the menu is built from these rows instead of `currentTracks` —
     * which can only ever show the one dub the item was built with.
     */
    private var streams: List<AudioStream> = emptyList()

    /** What the viewer picked by hand, or `null` while the default steering rules. */
    private var pick: AudioTrackOption? = null

    /**
     * The dub the viewer picked for [mediaId], or `null` when they have not.
     *
     * Keyed by the media id on purpose: a rebuild of the same video must keep the
     * pick, while the next video — and a resolution that never reached the player
     * — must not inherit a language that belongs to another upload.
     */
    fun pickedStream(mediaId: String): AudioStream? =
        pick?.stream?.takeIf { mediaId == this.mediaId }

    /**
     * Configures track selection for the item about to be prepared.
     *
     * Called for every media item, not once when the player is built: the shared
     * player is handed a new item for every video, and the steering has to
     * travel with it. A different [mediaId] also drops the previous video's
     * pick, whose override would otherwise keep the new video's audio groups
     * out — an override restricts selection to the group it names.
     */
    fun onMediaItem(player: Player, mediaId: String, streams: List<AudioStream> = emptyList()) {
        val differentVideo = mediaId != this.mediaId
        if (differentVideo) {
            this.mediaId = mediaId
            pick = null
            options = emptyList()
            selected = null
        }
        this.streams = streams
        // The pick's own language first, so reloading the same video lands on
        // the same dub even before its rebuilt groups are known.
        val language = pick?.language?.takeIf { it.isNotBlank() } ?: preferredAudioLanguage()
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .apply {
                if (differentVideo) clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                setPreferredAudioLanguage(language.takeIf { it.isNotBlank() })
            }
            .build()
    }

    /**
     * Mirrors the player's audio tracks into [options] and re-attaches a hand
     * pick to the groups of a rebuilt item.
     */
    fun onTracksChanged(player: Player, tracks: Tracks) {
        options = if (streams.size > 1) streamOptions() else audioTrackOptions(tracks)
        selected = options.firstOrNull { it.selected }
        val picked = pick ?: return
        // Nothing is known about this item's audio yet, so the pick has to wait
        // for a later event rather than be thrown away.
        if (options.isEmpty()) return
        val match = options.firstOrNull { it.key == picked.key }
        if (match == null) {
            // The rebuilt item no longer offers that track — audio-only playback
            // keeps just one — so the pick and its dead override go together.
            this.pick = null
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                .build()
            return
        }
        apply(player, match)
    }

    /**
     * Applies the viewer's own pick, which then follows this video only.
     *
     * The rows are updated here rather than after the player answers, so the
     * menu and the closed row show the new choice the moment it is tapped.
     */
    fun choose(player: Player, option: AudioTrackOption) {
        pick = option
        selected = option
        options = options.map { it.copy(selected = it.key == option.key) }
        val group = option.group ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(
                TrackSelectionOverride(group.mediaTrackGroup, listOf(option.trackIndex)),
            )
            .build()
    }

    /**
     * Pins [option]'s group, unless the group is already the one in force.
     *
     * Answering a tracks change by writing the parameters again would only earn
     * another tracks change, so an override that is already there is left alone.
     * That is also what keeps a rebuffer from touching a selection at all.
     */
    private fun apply(player: Player, option: AudioTrackOption) {
        if (option.selected) return
        val group = option.group ?: return
        val parameters = player.trackSelectionParameters
        val already = parameters.overrides[group.mediaTrackGroup]
            ?.trackIndices?.toList() == listOf(option.trackIndex)
        if (already) return
        player.trackSelectionParameters = parameters.buildUpon()
            .setOverrideForType(
                TrackSelectionOverride(group.mediaTrackGroup, listOf(option.trackIndex)),
            )
            .build()
    }

    /**
     * The rows of a dubbed video: the resolved dubs themselves.
     *
     * The picked dub is the one marked, and until there is a pick it is the one
     * [defaultAudioStream] chose — the same function the item was built with, so
     * the menu cannot disagree with what is playing.
     */
    private fun streamOptions(): List<AudioTrackOption> {
        val chosen = pick?.stream ?: defaultAudioStream(streams)
        return streams.map { stream ->
            AudioTrackOption(
                language = stream.languageCode,
                label = stream.displayName.ifBlank {
                    audioLanguageName(stream.languageCode).ifBlank { "Unknown language" }
                },
                selected = stream == chosen,
                stream = stream,
            )
        }
    }
}

/**
 * The dub a video starts in: the viewer's own language, then YouTube's default,
 * then the original recording, then whatever came first.
 *
 * A dubbed video lists every language as an equal-looking format, so without
 * this the player starts in the language that happens to be listed first —
 * which is how an English viewer hears Japanese. [locale] is a parameter so the
 * rule can be pinned without touching the device's own setting.
 */
internal fun defaultAudioStream(
    streams: List<AudioStream>,
    locale: Locale = Locale.getDefault(),
): AudioStream? {
    if (streams.isEmpty()) return null
    val wanted = preferredAudioLanguage(locale)
    return streams.firstOrNull { it.languageCode.equals(wanted, ignoreCase = true) }
        ?: streams.firstOrNull { it.isDefault }
        ?: streams.firstOrNull { it.original }
        ?: streams.first()
}

/**
 * The audio choices [tracks] offers: one per language, in media order.
 *
 * Only audio groups count — a video or subtitle group has nothing to say about
 * what the viewer hears. Tracks the renderers cannot decode are left out
 * entirely: pinning one would silence playback, and a row that does that is
 * worse than a missing row. Dropping them here is also what keeps the
 * "now playing" row honest, since it is derived from this same list.
 *
 * A track the manifest names neither by language nor by label is counted rather
 * than merged with the one before it: two of them are two dubs, and only the
 * count keeps the second from losing its row to the first. See
 * [AudioTrackOption.key].
 *
 * [displayLocale] is the language the names are written in, which is the
 * viewer's own; it is a parameter so the labels can be pinned without a device.
 */
internal fun audioTrackOptions(
    tracks: Tracks,
    displayLocale: Locale = Locale.getDefault(),
): List<AudioTrackOption> {
    val options = LinkedHashMap<String, AudioTrackOption>()
    var untagged = 0
    for (group in tracks.groups) {
        if (group.type != C.TRACK_TYPE_AUDIO) continue
        // The group's own verdict first: nothing inside it can play at all.
        if (!group.isSupported) continue
        for (index in 0 until group.length) {
            // A dub can be carried twice, once in a codec this device has and
            // once in one it has not; only the playable copy is a choice.
            if (!group.isTrackSupported(index)) continue
            val format = group.getTrackFormat(index)
            val language = format.language.orEmpty()
            val label = audioTrackLabel(format, displayLocale)
            val nameless = language.isBlank() && format.label.isNullOrBlank()
            val option = AudioTrackOption(
                group = group,
                trackIndex = index,
                language = language,
                label = label,
                selected = group.isTrackSelected(index),
                untagged = if (nameless) untagged++ else null,
            )
            val existing = options[option.key]
            options[option.key] = when {
                existing == null -> option
                // The same dub can arrive as several renditions; the row keeps
                // the first, but stays marked while any of them is playing.
                !existing.selected && option.selected -> existing.copy(selected = true)
                else -> existing
            }
        }
    }
    return options.values.toList()
}

/**
 * What the audio menu calls [format].
 *
 * A manifest's own label is the most specific thing it says ("English (US)",
 * "English descriptive audio"), so it wins; a plain dub carries only a language
 * code, which is spelled out in the viewer's language instead.
 */
internal fun audioTrackLabel(format: Format, displayLocale: Locale = Locale.getDefault()): String {
    format.label?.takeIf { it.isNotBlank() }?.let { return it }
    return audioLanguageName(format.language.orEmpty(), displayLocale)
        .ifBlank { "Unknown language" }
}

/**
 * The name of [language] in [displayLocale], e.g. "Japanese" for "ja".
 *
 * The code stands in when no CLDR build knows the language: showing "ja" beats
 * showing nothing, and the viewer can still recognise it.
 */
internal fun audioLanguageName(
    language: String,
    displayLocale: Locale = Locale.getDefault(),
): String {
    if (language.isBlank()) return ""
    val name = Locale.forLanguageTag(language).getDisplayLanguage(displayLocale)
    return name.ifBlank { language }
}

/**
 * The audio language a freshly loaded video should be steered towards.
 *
 * The device's own language, which is the locale the rest of the app speaks:
 * that is the viewer's answer to "what language are you watching in". Empty
 * when the locale names no language (a root or `und` locale), which leaves the
 * choice to the player rather than pinning it to something no track can match.
 */
internal fun preferredAudioLanguage(locale: Locale = Locale.getDefault()): String =
    locale.language.takeIf { it.isNotBlank() && it != "und" }.orEmpty()